package com.shaforostoff.livequeueplayer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * service for playing music
 */
public class Service extends android.service.media.MediaBrowserService implements MediaPlayerStateListener {

    static final String ACTION_PLAYBACK_STATE = "com.shaforostoff.livequeueplayer.PLAYBACK_STATE";
    static final String EXTRA_CURRENT_INDEX = "current_index";
    static final String EXTRA_IS_PLAYING = "is_playing";
    static final String EXTRA_CURRENT_URI = "current_uri";
    static final String EXTRA_PLAYBACK_POSITION_MS = "playback_position_ms";
    static final String EXTRA_PLAYBACK_DURATION_MS = "playback_duration_ms";
    static final String EXTRA_HAS_PENDING_TRACKS = "has_pending_tracks";
    static final String EXTRA_FADE_OUT_IN_PROGRESS = "fade_out_in_progress";
    static final String EXTRA_BROWSE_MODE = "browse_mode";
    static final String EXTRA_ENTRY_IDS = "entry_ids";
    static final String EXTRA_CURRENT_ENTRY_ID = "current_entry_id";
    static final String EXTRA_SEEK_TO_MS = "seek_to_ms";
    static final String EXTRA_QUEUE_ALREADY_PERSISTED = "queue_already_persisted";
    static final String EXTRA_REPLACE_PLAYBACK = "replace_playback";
    static final String EXTRA_QUEUE_INDEX = "queue_index";
    private static final String MEDIA_ROOT_ID = "root";
    private static final long PLAYBACK_PROGRESS_BROADCAST_INTERVAL_MS = 1_000L;

    // Readable by the activity to re-sync state after missed broadcasts (e.g. screen off)
    static volatile boolean sIsPlaying = false;
    static volatile int sCurrentIndex = -1;
    static volatile Uri sCurrentUri = null;
    static volatile int sPlaybackPositionMs = 0;
    static volatile int sPlaybackDurationMs = 0;
    static volatile boolean sHasPendingTracks = false;
    static volatile boolean sFadeOutInProgress = false;
    static volatile boolean sBrowseMode = false;
    static volatile int sCurrentEntryId = -1;
    /** True while this service holds foreground status. The activity consults it to decide
     *  whether service intents are currently permitted (a process with a live FGS is not
     *  "background" to the OS, regardless of screen state). */
    static volatile boolean sForegroundActive = false;

    private HWListener hwListener;
    private Notifications notifications;
    private PlaybackEngine audioPlayer;
    // Strong ref required: SharedPreferences holds change listeners weakly. Fires the
    // MediaBrowser/Android Auto queue-list refresh whenever the persisted queue changes.
    private SharedPreferences.OnSharedPreferenceChangeListener queueChangeListener;
    // Held continuously from playback start through every track transition, so the CPU cannot
    // sleep in the wake-lock-free gap between an old MediaPlayer's PLAYBACK_COMPLETED state
    // (framework releases its setWakeMode lock) and the new MediaPlayer's prepare()+start().
    private PowerManager.WakeLock playbackWakeLock;

    private ServicePlaylist playlist;
    /** Index of the next entry to play in {@link #playlist}. */
    private int playlistPosition = 0;
    /** Title of the current track, retained so {@link #onTrackDurationResolved} can re-publish the
     *  media-session metadata with the duration once AudioPlayer reports it. */
    private String currentTrackTitle = "";
    // Tracks which playlist index has already been retried once, to avoid infinite retry loops.
    private volatile int retriedAtPosition = -1;
    // Set once onDestroy() begins tearing the service down. The boundary invariants (Step 5) are only
    // meaningful while the service is live, so the debug tripwire stops checking past this point.
    private boolean destroyed = false;

    // Step 4 — debug-only chaos control. Lets an off-device harness drive real boundary scenarios
    // (seek-to-boundary, stop/resume, add-below-current, reorder, fade length) over ADB. Registered
    // and referenced only under BuildConfig.DEBUG, so it is stripped from release builds entirely.
    static final String CHAOS_ACTION = "com.shaforostoff.livequeueplayer.CHAOS";
    private static final String CHAOS_TAG = "LqpChaos";
    private BroadcastReceiver chaosReceiver;
    private int chaosIdSeq = 0;
    private int progressAnchorPositionMs = 0;
    private long progressAnchorElapsedMs = 0L;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sIsPlaying) return;
            refreshProgressSnapshot();
            hwListener.updatePlaybackPosition(sPlaybackPositionMs);
            sendPlaybackStateBroadcast();
            progressHandler.postDelayed(this, PLAYBACK_PROGRESS_BROADCAST_INTERVAL_MS);
        }
    };

    public Service() {
    }

    /**
     * setup
     */
    @Override
    public void onCreate() {
        super.onCreate();
        hwListener = new HWListener(this);
        notifications = new Notifications(this);
        playlist = new ServicePlaylist(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        playbackWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveQueuePlayer:Playback");
        playbackWakeLock.setReferenceCounted(false);
        hwListener.create();
        // Publish the existing framework MediaSession token so MediaBrowser clients
        // (Android Auto, Assistant, system media controls) can connect and control playback.
        setSessionToken(hwListener.getSessionToken());
        // Live-refresh the browse list when the queue changes from any component in this
        // (single) process. SharedPreferences dispatches this callback on the main thread,
        // which is required for notifyChildrenChanged().
        queueChangeListener = (prefs, key) -> {
            if (key == null || QueueStore.KEY_QUEUE.equals(key)) {
                notifyChildrenChanged(MEDIA_ROOT_ID);
            }
        };
        QueueStore.prefs(this).registerOnSharedPreferenceChangeListener(queueChangeListener);
        notifications.create();

        if (BuildConfig.DEBUG) registerChaosReceiver();
    }

    // ============================ Step 4 — debug chaos control ============================
    // Everything below is compiled out of release builds (guarded by / only reachable through
    // BuildConfig.DEBUG). It translates am-friendly string commands into the exact intents the UI
    // sends, so an off-device harness exercises the real production code paths. See
    // docs/testing-race-conditions.md and scripts/boundary-chaos.sh.

    private void registerChaosReceiver() {
        chaosReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleChaosCommand(intent.getStringExtra("cmd"),
                        intent.getIntExtra("arg", -1), intent.getIntExtra("arg2", -1));
            }
        };
        IntentFilter filter = new IntentFilter(CHAOS_ACTION);
        // Exported so `adb shell am broadcast` (shell uid) can reach this registered receiver.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chaosReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(chaosReceiver, filter);
        }
    }

    /** Handle one chaos command on the main thread, dispatching real Service intents. */
    private void handleChaosCommand(String cmd, int arg, int arg2) {
        if (cmd == null) return;
        Log.i(CHAOS_TAG, "CMD " + cmd + " arg=" + arg + " arg2=" + arg2);
        switch (cmd) {
            case "status" -> Log.i(CHAOS_TAG, "STATUS idx=" + sCurrentIndex + " playing=" + sIsPlaying
                    + " pending=" + sHasPendingTracks + " fade=" + sFadeOutInProgress
                    + " pos=" + sPlaybackPositionMs + " dur=" + sPlaybackDurationMs
                    + " playlistPos=" + playlistPosition + " size=" + (playlist != null ? playlist.size() : 0)
                    + " hasPlayer=" + (audioPlayer != null));
            case "set_fade" -> AudioOutputRouter.setFadeOutSeconds(this, Math.max(1, Math.min(10, arg)));
            case "seek_lead" -> chaosSeekLead(arg);
            case "stop" -> chaosSend(Launcher.STOP);
            case "resume", "play" -> chaosSend(Launcher.PLAY);
            case "pause" -> chaosSend(Launcher.PAUSE);
            case "play_pause" -> chaosSend(Launcher.PLAY_PAUSE);
            case "skip" -> chaosSend(Launcher.SKIP);
            case "kill" -> chaosSend(Launcher.KILL);
            case "clear_played" -> chaosSend(Launcher.CLEAR_PLAYED_QUEUE);
            case "add_below" -> chaosAddBelowCurrent();
            case "reorder" -> chaosReorderPending(arg, arg2);
            default -> Log.w(CHAOS_TAG, "unknown cmd: " + cmd);
        }
    }

    /** Seek the current track to {@code leadMs} before its end, so the boundary arrives on demand. */
    private void chaosSeekLead(int leadMs) {
        if (sPlaybackDurationMs <= 0) { Log.i(CHAOS_TAG, "seek_lead: duration unknown, skipping"); return; }
        int target = Math.max(0, sPlaybackDurationMs - Math.max(0, leadMs));
        Intent i = new Intent(this, Service.class);
        i.putExtra(Launcher.TYPE, Launcher.SEEK);
        i.putExtra(EXTRA_SEEK_TO_MS, target);
        chaosStart(i);
    }

    private void chaosSend(byte action) {
        Intent i = new Intent(this, Service.class);
        i.putExtra(Launcher.TYPE, action);
        chaosStart(i);
    }

    /** Insert a playable track directly below the current one, via the real SET_PENDING_QUEUE path. */
    private void chaosAddBelowCurrent() {
        if (playlist == null || sCurrentIndex < 0 || sCurrentIndex >= playlist.size()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();
        // The new below-current track reuses the current track's (already durable) URI.
        uris.add(playlist.get(sCurrentIndex).location);
        ids.add(1_000_000 + (chaosIdSeq++));
        collectPending(uris, ids);
        chaosDispatchSetPending(uris, ids);
    }

    /** Move a pending track from index {@code from} to {@code to} (both within the pending sublist). */
    private void chaosReorderPending(int from, int to) {
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();
        collectPending(uris, ids);
        if (from < 0 || from >= uris.size() || to < 0 || to >= uris.size()) {
            Log.i(CHAOS_TAG, "reorder: indices out of range for pending size " + uris.size());
            return;
        }
        uris.add(to, uris.remove(from));
        ids.add(to, ids.remove(from));
        chaosDispatchSetPending(uris, ids);
    }

    /** Append the current pending tracks (everything after the current one) to the given lists. */
    private void collectPending(ArrayList<Uri> uris, ArrayList<Integer> ids) {
        for (int i = playlistPosition; i < playlist.size(); i++) {
            uris.add(playlist.get(i).location);
            ids.add(playlist.get(i).queueEntryId);
        }
    }

    /** Build and dispatch the real SET_PENDING_QUEUE intent (mirrors the activity's syncServicePendingQueue). */
    private void chaosDispatchSetPending(ArrayList<Uri> uris, ArrayList<Integer> ids) {
        int[] idArray = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) idArray[i] = ids.get(i);
        Intent i = new Intent(this, Service.class);
        i.putExtra(Launcher.TYPE, Launcher.SET_PENDING_QUEUE);
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.putExtra(EXTRA_ENTRY_IDS, idArray);
        chaosStart(i);
    }

    private void chaosStart(Intent i) {
        try {
            startService(i);
        } catch (RuntimeException e) {
            Log.w(CHAOS_TAG, "startService rejected (background?): " + e);
        }
    }
    // ========================== end Step 4 — debug chaos control ==========================

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        // Accept all callers; the only browsable content is the persisted play queue.
        return new BrowserRoot(MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> items = new ArrayList<>();
        ArrayList<QueueStore.Entry> queue = QueueStore.load(this);
        for (int i = 0; i < queue.size(); i++) {
            QueueStore.Entry e = queue.get(i);
            String title = (e.name != null && !e.name.isEmpty()) ? e.name : e.uri.getLastPathSegment();
            if (title == null) title = "Track " + (i + 1);
            MediaDescription desc = new MediaDescription.Builder()
                    .setMediaId(String.valueOf(i)) // mediaId == persisted-queue index
                    .setTitle(title)
                    .build();
            items.add(new MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(items);
    }

    private void acquirePlaybackWakeLock() {
        if (playbackWakeLock != null && !playbackWakeLock.isHeld()) {
            playbackWakeLock.acquire();
        }
    }

    private void releasePlaybackWakeLock() {
        if (playbackWakeLock != null && playbackWakeLock.isHeld()) {
            playbackWakeLock.release();
        }
    }

    /**
     * startup logic
     */
    @Override
    public void onStart(final Intent intent, final int startId) {
        // START_STICKY can re-deliver onStartCommand with a null intent after the system killed the
        // process (e.g. low memory while the screen is off). There is nothing to act on — the queue
        // is persisted and the user can resume from the media notification — so bail out instead of
        // dereferencing a null intent and crashing the freshly restarted process.
        if (intent == null) return;
        /* check if called from self */
        if (intent.getAction() == null) {
            var action = intent.getByteExtra(Launcher.TYPE, Launcher.NULL);
            if (action == Launcher.HOST_SESSION) {
                // Entering remote-receive mode (sent from the visible activity, so the promotion
                // is allowed): pin this service to the foreground for the whole hosting session.
                // Remote Bluetooth commands then always reach a foreground service — a background
                // one can neither be started nor re-promoted on Android 14/15.
                notifications.showIdleHostPlaceholder();
                promoteToForeground();
                return;
            }
            if (audioPlayer == null) {
                if (action == Launcher.KILL || action == Launcher.STOP) {
                    onPlaybackStoppedKeepAlive();
                }
                if (action == Launcher.PLAY || action == Launcher.PLAY_PAUSE) {
                    // Media-button route from the activity: the persisted queue is the source
                    // of truth for what to play. Allowed from background because this onStart
                    // was triggered by a MediaSession callback, which the OS treats as system-
                    // initiated.
                    playFromQueueStore();
                }
                if (action == Launcher.CLEAR_QUEUE) {
                    playlist.clear();
                    playlistPosition = 0;
                }
                if (action == Launcher.APPEND_QUEUE) {
                    appendQueueFromIntent(intent);
                }
                if (action == Launcher.PLAY_FROM_QUEUE_INDEX) {
                    playFromQueueIndex(intent.getIntExtra(EXTRA_QUEUE_INDEX, -1));
                }
                return;
            }

            var isPLaying = audioPlayer.isPlaying();
            switch (action) {
                /* start or pause audio playback */
                case Launcher.PLAY_PAUSE -> {
                    if (audioPlayer.isFadeOutInProgress()) {
                        sFadeOutInProgress = false;
                        audioPlayer.cancelFadeOutAndResume();
                    }
                    boolean shouldPlay = !isPLaying;
                    setState(shouldPlay);
                    notifyPlaybackState(shouldPlay, sCurrentIndex, sCurrentUri);
                }
                case Launcher.PLAY -> {
                    if (audioPlayer.isFadeOutInProgress()) {
                        sFadeOutInProgress = false;
                        audioPlayer.cancelFadeOutAndResume();
                    }
                    setState(true);
                    notifyPlaybackState(true, sCurrentIndex, sCurrentUri);
                }
                case Launcher.PAUSE -> {
                    setState(false);
                    notifyPlaybackState(false, sCurrentIndex, sCurrentUri);
                }
                case Launcher.SKIP -> playNextEntry();
                case Launcher.STOP -> {
                    sFadeOutInProgress = true;
                    sendPlaybackStateBroadcast();
                    audioPlayer.fadeOutAndStop(AudioOutputRouter.getFadeOutSeconds(this) * 1_000L);
                }
                case Launcher.APPEND_QUEUE -> appendQueueFromIntent(intent);
                case Launcher.SET_PENDING_QUEUE -> setPendingQueue(intent);
                case Launcher.CLEAR_QUEUE -> clearPendingQueue();
                case Launcher.CLEAR_PLAYED_QUEUE -> clearPlayedQueue();
                case Launcher.PLAY_FROM_QUEUE_INDEX ->
                    playFromQueueIndex(intent.getIntExtra(EXTRA_QUEUE_INDEX, -1));
                case Launcher.SEEK -> {
                    int seekToMs = intent.getIntExtra(EXTRA_SEEK_TO_MS, -1);
                    if (seekToMs >= 0 && audioPlayer != null) seekTo(seekToMs);
                }
                case Launcher.APPLY_EQ -> audioPlayer.applyEqualizerSettings();
                /* cancel current playback but keep the service alive so a remote command can
                 * resume without starting a new foreground service from the background, which
                 * Android 14+ defers until unlock. */
                case Launcher.KILL -> onPlaybackStoppedKeepAlive();
            }
        } else {
            // Reached only via startForegroundService() (see FileBrowserQueueActivity
            // .startPlaybackService), so satisfy its startForeground() deadline before any
            // branch below can skip it or block on I/O.
            ensureForeground();
            sBrowseMode = intent.getBooleanExtra(EXTRA_BROWSE_MODE, false);
            // Replace-in-place restart (a track tapped while another is playing or fading): tear
            // down the current player but stay foreground throughout. This used to be a KILL
            // intent followed by this one, but the KILL's stopForeground() opened a gap the
            // service could not close while the app was backgrounded — Android 14/15 DENIED the
            // re-promotion and App Standby then stopped the demoted service ~1 minute later,
            // cutting playback mid-song.
            // Replace when the caller asked to (EXTRA_REPLACE_PLAYBACK) OR when the live player is
            // genuinely fading out. The activity derives EXTRA_REPLACE_PLAYBACK from the optimistic
            // sFadeOutInProgress static, which can lag the real per-player fade state; keying off
            // the authoritative isFadeOutInProgress() here guarantees a new track started during a
            // fade replaces the fading player instead of being appended onto it (which would leave
            // the faded-to-silent track "playing" and the new one merely queued).
            if (audioPlayer != null
                    && (intent.getBooleanExtra(EXTRA_REPLACE_PLAYBACK, false)
                        || audioPlayer.isFadeOutInProgress())) {
                sFadeOutInProgress = false;
                audioPlayer.onMediaPlayerDestroy();
                audioPlayer = null;
                playlist.clear();
                playlistPosition = 0;
                retriedAtPosition = -1;
            }
            // Double-start race guard. playQueueFrom() starts this foreground service AND dispatches
            // a media-play key (the Android 14+ background-FGS-start workaround). If the media key
            // wins, it routes to playFromQueueStore(), which loads the already-persisted queue and
            // sets audioPlayer. This ACTION_SEND_MULTIPLE intent then arrives as a redundant
            // duplicate — appending its URIs now would double the queue both in memory (replaying
            // the whole set) and in the store. EXTRA_QUEUE_ALREADY_PERSISTED is set only by
            // playQueueFrom (never a genuine append), so with a player already live this intent is
            // always that duplicate: bail before mutating the playlist or the store.
            if (audioPlayer != null && intent.getBooleanExtra(EXTRA_QUEUE_ALREADY_PERSISTED, false)) {
                return;
            }
            int sizeBefore = playlist.size();
            switch (intent.getAction()) {
                case Intent.ACTION_VIEW -> playlist.generate(intent.getData());
                case Intent.ACTION_SEND -> playlist.generate((Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM));
                case Intent.ACTION_SEND_MULTIPLE -> {
                    ArrayList<?> stream = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                    if (stream != null) {
                        ArrayList<Uri> audioList = new ArrayList<>(stream.size());
                        for (Object item : stream) {
                            if (item instanceof Uri uri) audioList.add(uri);
                        }
                        if (!audioList.isEmpty()) playlist.generate(audioList);
                    }
                }
            }
            applyEntryIds(sizeBefore, intent.getIntArrayExtra(EXTRA_ENTRY_IDS));
            ArrayList<QueueStore.Entry> newEntries = new ArrayList<>();
            newEntries.ensureCapacity(playlist.size());
            for (int i = sizeBefore; i < playlist.size(); i++) {
                ServicePlaylist.Entry e = playlist.get(i);
                newEntries.add(new QueueStore.Entry(e.title, e.location));
            }
            if (audioPlayer == null) {
                if (!intent.getBooleanExtra(EXTRA_QUEUE_ALREADY_PERSISTED, false)) {
                    QueueStore.clear(this);
                    QueueStore.save(this, newEntries);
                }
                playEntryFromPlaylist();
            } else {
                ArrayList<QueueStore.Entry> stored = QueueStore.load(this);
                stored.addAll(newEntries);
                QueueStore.save(this, stored);
            }
        }
    }

    /**
     * Promote to the foreground right away to satisfy the startForegroundService() contract.
     * Android kills the process with ForegroundServiceDidNotStartInTimeException when a service
     * started via startForegroundService() fails to call startForeground() within ~5s. The real
     * call lives late inside playEntryFromPlaylist(), and several reachable paths never get there
     * — the already-playing append branch, and the load-failure catch blocks — so we promote
     * eagerly here, before any branching or blocking I/O. Idempotent: a successful
     * playEntryFromPlaylist() re-posts the proper media-styled notification afterwards.
     */
    private void ensureForeground() {
        notifications.ensurePlaceholder();
        promoteToForeground();
    }

    /**
     * startForeground() throws ForegroundServiceStartNotAllowedException (an IllegalStateException)
     * on Android 12+ when the process is background and holds no start exemption — e.g. a remote
     * Bluetooth command arriving with the screen off after playback stopped. Left uncaught it
     * crash-loops the process via START_STICKY redelivery, killing the app-scoped Bluetooth server
     * with it. Degrade to running without the foreground promotion instead: playback still starts,
     * and the next allowed start (media-key routed, or the activity returning) re-promotes.
     */
    private void promoteToForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(Notifications.NOTIFICATION_ID, notifications.notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else
                startForeground(Notifications.NOTIFICATION_ID, notifications.notification);
            sForegroundActive = true;
        } catch (IllegalStateException | SecurityException ignored) {
        }
    }

    /**
     * True while this device hosts a remote-receive session (the app-scoped Bluetooth server is
     * accepting/serving a client). Hosting pins this service to the foreground even when nothing
     * is playing: Android 14/15 grant no FGS-start exemption for the app's own media-key dispatch
     * (observed: tempAllowListReason:<null>, code:DENIED), so a service that ever drops foreground
     * while the app is backgrounded cannot re-promote, and App Standby stops the demoted service
     * about a minute after the screen turns off — cutting playback mid-song.
     */
    protected boolean isRemoteHostSession() {
        return ((App) getApplication()).getBluetoothBridge().isServerRunning();
    }

    /**
     * Factory for the audio engine, isolated behind {@link PlaybackEngine} so tests can substitute a
     * fake that is driven on the paused main looper. Production returns the real {@link AudioPlayer};
     * see docs/testing-race-conditions.md.
     */
    protected PlaybackEngine createPlaybackEngine(Uri location) throws IOException {
        return new AudioPlayer(this, location);
    }

    private void playEntryFromPlaylist() {
        // Acquire before constructing the new AudioPlayer so the wake lock is held through the
        // upcoming prepare() (blocking I/O). For auto-advance this is already held from the
        // previous track; for the first track this is where it first becomes held.
        acquirePlaybackWakeLock();
        if (BuildConfig.DEBUG && (playlistPosition < 0 || playlistPosition >= playlist.size()))
            throw new AssertionError("playEntryFromPlaylist called with playlistPosition="
                    + playlistPosition + " outside [0, " + playlist.size() + ")");
        var entry = playlist.get(playlistPosition);
        playlistPosition++;
        int currentIndex = playlistPosition - 1;
        try {

            AudioOutputRouter.resolve(this);
            // Lock in whether the EQ may engage for this track; the decision must not change
            // mid-track when outputs are plugged or unplugged.
            AudioOutputRouter.snapshotAudioPreviewAvailability(this);
            /* get audio playback logic and start async */
            audioPlayer = createPlaybackEngine(entry.location);
            audioPlayer.start();

            /* create notification for playback control */
            notifications.getNotification(entry.title, hwListener.getSessionToken());

            /* start service as foreground; a denied promotion must not fail the track (and must
             * not fall into this method's IllegalStateException catch, which would burn the
             * retry budget), so the guarded call is factored out */
            promoteToForeground();

            initializeProgressForTrack(entry.location);
            currentTrackTitle = entry.title != null ? entry.title : "";
            sCurrentEntryId = entry.queueEntryId;
            persistPlaybackOffsetFor(entry);
            // Duration is unknown until AudioPlayer finishes prepare() on its own thread; publish
            // the title now with a placeholder 0 and let onTrackDurationResolved() fill it in.
            hwListener.setTrackMetadata(currentTrackTitle, sPlaybackDurationMs);
            notifyPlaybackState(true, currentIndex, entry.location);

        } catch (IllegalArgumentException e) {
            Exceptions.throwError(this, Exceptions.IllegalArgument);
            playOrDestroy();
            return;
        } catch (SecurityException e) {
            Exceptions.throwError(this, Exceptions.Security);
            playOrDestroy();
            return;
        } catch (IllegalStateException e) {
            Exceptions.throwError(this, Exceptions.IllegalState);
            playOrDestroy();
            return;
        } catch (IOException e) {
            Exceptions.throwError(this, Exceptions.IO);
            playOrDestroy();
            return;
        }
        SilenceStreamer.ensure(this);
    }

    /**
     * Keep the persisted playback offset pointing at the track that actually plays. It used to be
     * written only when playback started (playQueueFrom in the activity, playFromQueueIndex here),
     * so after any auto-advance a stop followed by a resume via {@link #playFromQueueStore()}
     * replayed the queue from the original start row instead of the last-played track. Resolved
     * through the stable entry id rather than offset+index arithmetic, because queue edits made
     * mid-playback (remove, move, clear-played) renumber the persisted rows. Id-less playback
     * (browse mode, external ACTION_VIEW/SEND shares) is left untouched: those flows never carried
     * entry ids, and their offset semantics stay as before.
     */
    private void persistPlaybackOffsetFor(ServicePlaylist.Entry entry) {
        if (entry.queueEntryId <= 0) return;
        ArrayList<QueueStore.Entry> persisted = QueueStore.load(this);
        for (int i = 0; i < persisted.size(); i++) {
            if (persisted.get(i).id == entry.queueEntryId) {
                QueueStore.savePlaybackOffset(this, i);
                return;
            }
        }
    }

    public void playOrDestroy() {
        int failedPosition = playlistPosition - 1;
        if (retriedAtPosition != failedPosition) {
            // First failure at this position — retry once before giving up.
            retriedAtPosition = failedPosition;
            playlistPosition = failedPosition;
            onMediaPlayerReset();
            playEntryFromPlaylist();
        } else {
            retriedAtPosition = -1;
            if (!playNextEntry())
                onMediaPlayerDestroy();
        }
    }

    @Override
    public void setState(boolean playing) {
        if (playing) acquirePlaybackWakeLock();
        else releasePlaybackWakeLock();
        audioPlayer.setState(playing);
        hwListener.setState(playing);
        notifications.setState(playing);
    }

    /**
     * forward to startup logic for newer androids
     */
    @SuppressLint("InlinedApi")
    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        onStart(intent, startId);
        return START_STICKY;
    }

    @Override
    public void onMediaPlayerReset() {
        notifications.onMediaPlayerReset();
        hwListener.onMediaPlayerReset();
        if (audioPlayer != null)
            audioPlayer.onMediaPlayerReset();
    }

    @Override
    public void onMediaPlayerDestroy() {
        sFadeOutInProgress = false;
        notifyPlaybackState(false, -1, null);
        // calls onDestroy()
        stopSelf();
    }

    boolean playNextEntry() {
        if (playlistPosition < playlist.size()) {
            onMediaPlayerReset();
            playEntryFromPlaylist();
            return true;
        }
        return false;
    }

    /**
     * Append new URIs from an intent's EXTRA_STREAM to the current playlist.
     * Safe to call while a track is already playing.
     */
    private void appendQueueFromIntent(Intent intent) {
        ArrayList<?> stream = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (stream == null) return;
        ArrayList<Uri> uriList = new ArrayList<>(stream.size());
        for (Object item : stream) {
            if (item instanceof Uri uri) uriList.add(uri);
        }
        if (!uriList.isEmpty()) playlist.generate(uriList);
    }

    /**
     * Atomically replace the pending queue (every track after the currently playing one) with the
     * URIs carried in {@code intent}'s {@link Intent#EXTRA_STREAM}. Done in a single onStart
     * invocation — rather than a CLEAR_QUEUE followed by a separate APPEND_QUEUE — so that an
     * auto-advance PLAYBACK_COMPLETED callback, which is dispatched on this same main-thread
     * message queue, cannot interleave between the clear and the re-append and observe an empty
     * pending playlist (which would stop playback mid-set). Called on every queue edit made while
     * a track is playing, so this window would otherwise be hit constantly over a long session.
     */
    private void setPendingQueue(Intent intent) {
        if (playlistPosition < 0) playlistPosition = 0;
        // Drop the existing pending tracks, keeping the currently playing one (playlistPosition - 1).
        while (playlist.size() > playlistPosition) {
            playlist.remove(playlist.size() - 1);
        }
        // Append the replacement pending set in the same invocation.
        int sizeBefore = playlist.size();
        ArrayList<?> stream = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (stream != null) {
            ArrayList<Uri> uriList = new ArrayList<>(stream.size());
            for (Object item : stream) {
                if (item instanceof Uri uri) uriList.add(uri);
            }
            if (!uriList.isEmpty()) playlist.generate(uriList);
        }
        // Carry the stable queue-entry ids across so the now-playing row keeps resolving by id
        // (not the fragile offset+URI heuristic) once one of these pending tracks starts.
        applyEntryIds(sizeBefore, intent.getIntArrayExtra(EXTRA_ENTRY_IDS));
        sHasPendingTracks = playlistPosition < playlist.size();
        sendPlaybackStateBroadcast();
    }

    /**
     * Stamp the freshly-appended playlist entries (those at [{@code sizeBefore}, size)) with the
     * caller-supplied stable queue-entry ids, positionally. No-op when {@code entryIds} is null.
     */
    private void applyEntryIds(int sizeBefore, int[] entryIds) {
        if (entryIds == null) return;
        for (int i = 0; i < entryIds.length && (sizeBefore + i) < playlist.size(); i++) {
            playlist.get(sizeBefore + i).queueEntryId = entryIds[i];
        }
    }

    /**
     * Remove all tracks queued after the currently playing one.
     */
    private void clearPendingQueue() {
        if (playlistPosition < 0) playlistPosition = 0;
        while (playlist.size() > playlistPosition) {
            playlist.remove(playlist.size() - 1);
        }
        // Update the state of whether there are pending tracks
        sHasPendingTracks = playlistPosition < playlist.size();
        sendPlaybackStateBroadcast();
    }

    /**
     * Remove all already-played tracks queued before the currently playing one, so the current
     * track becomes the first entry in the playlist.
     */
    private void clearPlayedQueue() {
        int removeCount = sCurrentIndex; // tracks queued before the current one
        if (removeCount <= 0) return;    // current track is already first
        for (int i = 0; i < removeCount; i++) {
            playlist.remove(0);
        }
        playlistPosition -= removeCount;
        if (playlistPosition < 0) playlistPosition = 0;
        // Removing entries renumbers every playlist index, so a retry marker captured against the
        // old numbering would now point at the wrong track (denying it a legitimate retry, or
        // granting a spurious one). Clear it — a fresh retry budget is the safe default here.
        retriedAtPosition = -1;
        // Current track is now index 0; rebroadcast so listeners (activity) realign.
        notifyPlaybackState(sIsPlaying, sCurrentIndex - removeCount, sCurrentUri);
    }

    private void seekTo(int positionMs) {
        if (sPlaybackDurationMs > 0 && positionMs >= sPlaybackDurationMs) {
            //onMediaPlayerComplete();
            //return;
            positionMs = Math.max(0, sPlaybackDurationMs - 1000);
        }
        audioPlayer.seekTo(positionMs);
        progressAnchorPositionMs = positionMs;
        progressAnchorElapsedMs = sIsPlaying ? SystemClock.elapsedRealtime() : 0L;
        sPlaybackPositionMs = positionMs;
        hwListener.updatePlaybackPosition(positionMs);
        sendPlaybackStateBroadcast();
    }

    /**
     * destroy on playback complete
     */
    void onMediaPlayerComplete() {
        // The track's natural end raced a user-initiated fade-out (Stop pressed near the end of
        // the track). The user asked for playback to stop, so honor that instead of advancing:
        // auto-advancing here starts the next track at full volume, and — because the fade thread
        // belongs to the now-replaced player — leaves sFadeOutInProgress stuck true, desyncing
        // every fade-state consumer (stop button, remote clients) for the rest of the queue.
        // sFadeOutInProgress covers the window where the STOP intent is still queued behind this
        // callback; the audioPlayer flag covers a fade already running.
        if (sFadeOutInProgress || (audioPlayer != null && audioPlayer.isFadeOutInProgress())) {
            onPlaybackStoppedKeepAlive();
            return;
        }
        if (!playNextEntry())
            onPlaybackStoppedKeepAlive();
    }

    /**
     * Called by AudioPlayer when the user-initiated fade-out finishes.
     */
    void onFadeOutComplete() {
        onPlaybackStoppedKeepAlive();
    }

    /**
     * Load the persisted queue from {@link QueueStore} and start playback from the persisted
     * offset. Called when a Launcher.PLAY arrives with no active player — typically because
     * the activity dispatched a media-play key and the MediaSession routed it back here.
     */
    private void playFromQueueStore() {
        if (audioPlayer != null) return;
        ArrayList<QueueStore.Entry> persisted = QueueStore.load(this);
        int offset = QueueStore.loadPlaybackOffset(this);
        if (persisted.isEmpty() || offset < 0 || offset >= persisted.size()) return;

        playlist.clear();
        playlistPosition = 0;
        for (int i = offset; i < persisted.size(); i++) {
            QueueStore.Entry e = persisted.get(i);
            ServicePlaylist.Entry pe = new ServicePlaylist.Entry();
            pe.title = e.name != null ? e.name : "";
            pe.location = e.uri;
            pe.queueEntryId = e.id;
            playlist.add(pe);
        }
        playEntryFromPlaylist();
    }

    /**
     * Start playback of the persisted queue beginning at {@code index}. Routed here from a
     * MediaSession onPlayFromMediaId callback (e.g. tapping a row in Android Auto). Mirrors
     * {@link #playFromQueueStore()} but with a caller-chosen offset, and replaces any track
     * already playing.
     */
    private void playFromQueueIndex(int index) {
        ArrayList<QueueStore.Entry> persisted = QueueStore.load(this);
        if (index < 0 || index >= persisted.size()) return;
        if (audioPlayer != null) {
            audioPlayer.onMediaPlayerDestroy();
            audioPlayer = null;
        }
        QueueStore.savePlaybackOffset(this, index);
        playlist.clear();
        playlistPosition = 0;
        for (int i = index; i < persisted.size(); i++) {
            QueueStore.Entry e = persisted.get(i);
            ServicePlaylist.Entry pe = new ServicePlaylist.Entry();
            pe.title = e.name != null ? e.name : "";
            pe.location = e.uri;
            pe.queueEntryId = e.id;
            playlist.add(pe);
        }
        playEntryFromPlaylist();
    }

    /**
     * Tear down the current playback but keep the foreground service running, so a subsequent
     * remote command (Bluetooth play_track, media-button play, etc.) can start a new track
     * without needing to start a new service from the background — which Android 14+ defers
     * until the device is unlocked.
     */
    private void onPlaybackStoppedKeepAlive() {
        sFadeOutInProgress = false;
        if (audioPlayer != null) {
            audioPlayer.onMediaPlayerDestroy();
            audioPlayer = null;
        }
        playlist.clear();
        playlistPosition = 0;
        releasePlaybackWakeLock();
        // Stop is not a resumable pause: clear the system media control (STATE_STOPPED) and
        // drop the foreground notification so nothing lingers in a paused-looking state. The
        // service and its MediaSession stay alive (no stopSelf) so a later media-button/remote
        // play can still resume without a fresh background foreground-service start.
        hwListener.setStopped();
        if (isRemoteHostSession()) {
            // Hosting: never leave the foreground state (see isRemoteHostSession). Swap the
            // media notification for the idle placeholder so nothing playing-looking lingers.
            notifications.showIdleHostPlaceholder();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            sForegroundActive = false;
            notifications.onMediaPlayerReset();
        }
        notifyPlaybackState(false, -1, null);
    }

    /**
     * service killing logic
     */
    @Override
    public void onDestroy() {
        destroyed = true;
        sForegroundActive = false;
        if (BuildConfig.DEBUG && chaosReceiver != null) {
            unregisterReceiver(chaosReceiver);
            chaosReceiver = null;
        }
        stopProgressTicks();
        if (queueChangeListener != null) {
            QueueStore.prefs(this).unregisterOnSharedPreferenceChangeListener(queueChangeListener);
            queueChangeListener = null;
        }
        // Leave SilenceStreamer running (including any active preview) —
        // the Activity owns its lifetime and releases it in onStop().
        sFadeOutInProgress = false;
        notifyPlaybackState(false, -1, null);
        onMediaPlayerReset();
        notifications.onMediaPlayerDestroy();
        hwListener.onMediaPlayerDestroy();
        if (audioPlayer != null) {
            audioPlayer.onMediaPlayerDestroy();
            // Clear the field so a duration report still queued from this player's prepare() is
            // dropped by the identity guard in onTrackDurationResolved() instead of running against
            // the now-released MediaSession/notification after teardown.
            audioPlayer = null;
        }
        playlist.clear();
        releasePlaybackWakeLock();

        super.onDestroy();
    }

     private void notifyPlaybackState(boolean isPlaying, int currentIndex, Uri currentUri) {
        if (isPlaying) {
            if (!sIsPlaying) {
                progressAnchorElapsedMs = SystemClock.elapsedRealtime();
            }
            startProgressTicks();
        } else {
            if (currentIndex < 0 || currentUri == null) {
                resetProgressSnapshot();
            } else {
                refreshProgressSnapshot();
                progressAnchorElapsedMs = 0L;
                progressAnchorPositionMs = sPlaybackPositionMs;
            }
            stopProgressTicks();
        }

        sIsPlaying = isPlaying;
        sCurrentIndex = currentIndex;
        sCurrentUri = currentUri;
        if (currentIndex < 0) sCurrentEntryId = -1;
        // Update pending tracks state based on current playlist position
        sHasPendingTracks = playlistPosition < playlist.size();
        sendPlaybackStateBroadcast();
    }

    private void initializeProgressForTrack(Uri trackUri) {
        sPlaybackPositionMs = 0;
        // Duration starts unknown (0) and is filled in by onTrackDurationResolved() once AudioPlayer
        // finishes prepare() on its own thread. It used to be read here with a blocking
        // MediaMetadataRetriever on the (often content://) URI — ~1s per SAF track — which ran inline
        // on the main thread at every transition, widening the inter-track gap and risking an ANR.
        sPlaybackDurationMs = 0;
        progressAnchorPositionMs = 0;
        progressAnchorElapsedMs = SystemClock.elapsedRealtime();
    }

    /**
     * Reported by {@link AudioPlayer} on its own thread once prepare() completes and the native
     * duration is cheaply available via {@code MediaPlayer.getDuration()}. Posted to the main thread
     * so the progress fields, media-session metadata and broadcast are all touched there, matching
     * where the rest of the playback state is mutated. The {@code reporter} identity check drops a
     * stale report from a superseded player (e.g. the user skipped while a slow prepare was still
     * running), so a late duration can never clobber the track that replaced it.
     */
    void onTrackDurationResolved(PlaybackEngine reporter, int durationMs) {
        progressHandler.post(() -> {
            if (reporter != audioPlayer) return;
            sPlaybackDurationMs = Math.max(0, durationMs);
            hwListener.setTrackMetadata(currentTrackTitle, sPlaybackDurationMs);
            refreshProgressSnapshot();
            sendPlaybackStateBroadcast();
        });
    }

    private void refreshProgressSnapshot() {
        if (!sIsPlaying) {
            sPlaybackPositionMs = Math.max(0, progressAnchorPositionMs);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long delta = Math.max(0L, now - progressAnchorElapsedMs);
        long position = (long) progressAnchorPositionMs + delta;
        if (sPlaybackDurationMs > 0) {
            position = Math.min(position, sPlaybackDurationMs);
        }
        sPlaybackPositionMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, position));
    }

    private void resetProgressSnapshot() {
        sPlaybackPositionMs = 0;
        sPlaybackDurationMs = 0;
        progressAnchorPositionMs = 0;
        progressAnchorElapsedMs = 0L;
    }

    private void sendPlaybackStateBroadcast() {
        // Debug-only tripwire (Step 5). This method is the state-committed chokepoint — called at the
        // end of every boundary mutation and on each progress tick — so checking here catches an
        // inconsistency the instant it is published, with a stack trace at the point of corruption,
        // rather than tracks later as mystery silence. Compiled out of release builds.
        if (BuildConfig.DEBUG && !destroyed) assertBoundaryInvariants();

        Intent intent = new Intent(ACTION_PLAYBACK_STATE);
        // Confine to our own package: every receiver is in-app (the activity and Launcher), and an
        // implicit broadcast would otherwise let other apps read the current-track URI or inject a
        // spoofed state to desync our UI. Receivers already register as NOT_EXPORTED on API 33+;
        // this closes the same hole on older releases.
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_IS_PLAYING, sIsPlaying);
        intent.putExtra(EXTRA_CURRENT_INDEX, sCurrentIndex);
        intent.putExtra(EXTRA_CURRENT_URI, sCurrentUri);
        intent.putExtra(EXTRA_PLAYBACK_POSITION_MS, sPlaybackPositionMs);
        intent.putExtra(EXTRA_PLAYBACK_DURATION_MS, sPlaybackDurationMs);
        intent.putExtra(EXTRA_HAS_PENDING_TRACKS, sHasPendingTracks);
        intent.putExtra(EXTRA_FADE_OUT_IN_PROGRESS, sFadeOutInProgress);
        intent.putExtra(EXTRA_BROWSE_MODE, sBrowseMode);
        intent.putExtra(EXTRA_CURRENT_ENTRY_ID, sCurrentEntryId);
        sendBroadcast(intent);
    }

    /**
     * The track-boundary invariants that must hold at every state-commit point (Step 5). Identical to
     * the spec fuzzed off-device in the boundary tests — see docs/testing-race-conditions.md. Only
     * ever invoked under {@link BuildConfig#DEBUG}, so release builds pay nothing.
     */
    private void assertBoundaryInvariants() {
        int size = playlist != null ? playlist.size() : 0;
        boolean hasPlayer = audioPlayer != null;
        int idx = sCurrentIndex;
        int pos = playlistPosition;

        // 1. playlistPosition stays within the queue.
        if (pos < 0 || pos > size) failBoundary("playlistPosition out of range", size, hasPlayer);
        // 2. sCurrentIndex is either "no track" (-1) or a real index.
        if (!(idx == -1 || (idx >= 0 && idx < size))) failBoundary("sCurrentIndex out of range", size, hasPlayer);
        // 3. During active playback the next entry is always exactly one past the current one.
        if (hasPlayer && idx >= 0 && pos != idx + 1)
            failBoundary("playlistPosition must equal sCurrentIndex + 1 during playback", size, hasPlayer);
        // 4. "Playing" implies a live engine sitting on a real track.
        if (sIsPlaying && !(hasPlayer && idx >= 0)) failBoundary("sIsPlaying with no live current track", size, hasPlayer);
    }

    private void failBoundary(String what, int size, boolean hasPlayer) {
        throw new AssertionError("Boundary invariant violated: " + what
                + " [playlistPosition=" + playlistPosition + " sCurrentIndex=" + sCurrentIndex
                + " playlist.size=" + size + " sIsPlaying=" + sIsPlaying + " hasPlayer=" + hasPlayer + "]");
    }

    void onAudioFocusLoss(int currentPositionMs) {
        releasePlaybackWakeLock();
        hwListener.setState(false);
        notifications.setState(false);
        sPlaybackPositionMs = currentPositionMs;
        progressAnchorPositionMs = currentPositionMs;
        progressAnchorElapsedMs = 0L;
        sIsPlaying = false;
        stopProgressTicks();
        sendPlaybackStateBroadcast();
    }

    void onAudioFocusResume(int currentPositionMs) {
        acquirePlaybackWakeLock();
        hwListener.setState(true);
        notifications.setState(true);
        sPlaybackPositionMs = currentPositionMs;
        progressAnchorPositionMs = currentPositionMs;
        progressAnchorElapsedMs = SystemClock.elapsedRealtime();
        sIsPlaying = true;
        startProgressTicks();
        sendPlaybackStateBroadcast();
    }

    private void startProgressTicks() {
        progressHandler.removeCallbacks(progressTickRunnable);
        progressHandler.postDelayed(progressTickRunnable, PLAYBACK_PROGRESS_BROADCAST_INTERVAL_MS);
    }

    private void stopProgressTicks() {
        progressHandler.removeCallbacks(progressTickRunnable);
    }
}

