package com.shaforostoff.livequeueplayer;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.content.Intent;
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
public class Service extends android.service.media.MediaBrowserService {

    private static final String TAG = "Service";

    static final String EXTRA_BROWSE_MODE = "browse_mode";
    static final String EXTRA_SEEK_TO_MS = "seek_to_ms";
    static final String EXTRA_REPLACE_PLAYBACK = "replace_playback";
    static final String EXTRA_QUEUE_INDEX = "queue_index";
    static final String EXTRA_CURRENT_ENTRY_ID = "current_entry_id";
    private static final String MEDIA_ROOT_ID = "root";
    private static final long PROGRESS_TICK_INTERVAL_MS = 1_000L;
    /**
     * How long this service may hold foreground status with nothing playing before it retires.
     * A paused track — or a remote-host session — otherwise pins the service (and its silence
     * streamer, notification and process residency) indefinitely: one such session was observed
     * sitting foreground for 8h09m overnight.
     */
    private static final long IDLE_RETIRE_TIMEOUT_MS = 60 * 60 * 1_000L;
    /**
     * How long a track may sit paused before its player is released (see {@link #parkIfStillPaused}).
     * A paused MediaPlayer keeps its codec, buffers and — for software-decoded ALAC — the whole
     * track as PCM in memory (~50 MB for five minutes of CD audio), and this foreground service
     * stops Android from reclaiming any of it.
     */
    private static final long PARK_AFTER_PAUSE_MS = 30 * 60 * 1_000L;

    // The playback state, read by the activities. Every committed change is announced to the
    // state listeners below; they and the activity's own 1s poll read these fields.
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

    /** In-process observers (the activities), main thread only. See {@link #publishState()}. */
    private static final List<Runnable> sStateListeners = new ArrayList<>();
    private static final Handler sListenerHandler = new Handler(Looper.getMainLooper());

    private HWListener hwListener;
    private Notifications notifications;
    private PlaybackEngine audioPlayer;
    // Strong ref required: SharedPreferences holds change listeners weakly. Fires the
    // MediaBrowser/Android Auto queue-list refresh whenever the persisted queue changes.
    private SharedPreferences.OnSharedPreferenceChangeListener queueChangeListener;
    // Held continuously from playback start through every track transition, so the CPU cannot
    // sleep in the wake-lock-free gap between an old MediaPlayer's PLAYBACK_COMPLETED state
    // and the new MediaPlayer's prepare()+start(). The MediaPlayers take no wake lock of their own.
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
    private int progressAnchorPositionMs = 0;
    private long progressAnchorElapsedMs = 0L;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sIsPlaying) return;
            refreshProgressSnapshot();
            hwListener.updatePlaybackPosition(sPlaybackPositionMs);
            // No publishState(): only the position moved, and the activity's own 1s poll redraws
            // progress from sPlaybackPositionMs. Still a state-commit point for the debug check.
            if (BuildConfig.DEBUG && !destroyed) assertBoundaryInvariants();
            progressHandler.postDelayed(this, PROGRESS_TICK_INTERVAL_MS);
        }
    };
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleRetireRunnable = this::retireIfStillIdle;
    /** elapsedRealtime() at which the current idle stretch began; 0 while a track is playing. */
    private long idleSinceElapsedMs = 0L;
    // Real time, unlike the idle timer: memory held while the device sleeps is still held. A
    // non-wakeup alarm never wakes the device for this; it fires at the first wake after it is due.
    private final AlarmManager.OnAlarmListener parkAlarm =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? this::parkIfStillPaused : null;
    private final Runnable parkFallback = this::parkIfStillPaused; // API 23: no OnAlarmListener

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
    }

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
        // Any command counts as activity, including ones that neither start nor stop playback
        // (HOST_SESSION, queue edits, an EQ apply). State-changing ones re-run this via
        // notifyPlaybackState() once the new state is committed.
        updateIdleRetireTimer();
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
            if (action == Launcher.PLAY_FROM_QUEUE_INDEX) {
                playQueueRequest(intent);
                return;
            }
            if (audioPlayer instanceof ParkedEngine parked
                    && (action == Launcher.PLAY || action == Launcher.PLAY_PAUSE)) {
                resumeParkedTrack(parked.positionMs);
                return;
            }
            if (audioPlayer == null) {
                if (action == Launcher.KILL || action == Launcher.STOP) {
                    onPlaybackStoppedKeepAlive();
                }
                if (action == Launcher.PLAY || action == Launcher.PLAY_PAUSE) {
                    // Media-button route from the activity: resume the persisted queue at the
                    // persisted offset. Allowed from background because this onStart was
                    // triggered by a MediaSession callback, which the OS treats as system-initiated.
                    playFromQueueIndex(QueueStore.loadPlaybackOffset(this));
                }
                if (action == Launcher.CLEAR_QUEUE) {
                    playlist.clear();
                    playlistPosition = 0;
                }
                if (action == Launcher.APPEND_QUEUE) {
                    appendQueueFromIntent(intent);
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
                    publishState();
                    audioPlayer.fadeOutAndStop(AudioOutputRouter.getFadeOutSeconds(this) * 1_000L);
                }
                case Launcher.APPEND_QUEUE -> appendQueueFromIntent(intent);
                case Launcher.APPEND_BROWSE_TAIL -> appendBrowseTail();
                case Launcher.SET_PENDING_QUEUE ->
                    setPendingQueue(intent.getIntExtra(EXTRA_CURRENT_ENTRY_ID, -1));
                case Launcher.CLEAR_QUEUE -> clearPendingQueue();
                case Launcher.CLEAR_PLAYED_QUEUE -> clearPlayedQueue();
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
            // A browse-mode track or an external share (ACTION_VIEW/SEND/SEND_MULTIPLE), reached
            // via startForegroundService(), so satisfy its startForeground() deadline before any
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
                audioPlayer.release();
                audioPlayer = null;
                playlist.clear();
                playlistPosition = 0;
                retriedAtPosition = -1;
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
            ArrayList<QueueStore.Entry> newEntries = new ArrayList<>();
            newEntries.ensureCapacity(playlist.size());
            for (int i = sizeBefore; i < playlist.size(); i++) {
                ServicePlaylist.Entry e = playlist.get(i);
                newEntries.add(new QueueStore.Entry(e.title, e.location));
            }
            if (audioPlayer == null) {
                QueueStore.clear(this);
                QueueStore.save(this, newEntries);
                // Nothing usable in the intent (no stream, an empty playlist file): nothing to play.
                if (playlistPosition < playlist.size()) playEntryFromPlaylist();
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

        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // Any failure to set up a track must fail only that track. This runs inside
            // onStartCommand, so anything that escapes — a malformed file's parser bug, an OOM on a
            // huge one — kills the whole process, Bluetooth server included.
            Exceptions.throwError(this, Exceptions.messageFor(e));
            playOrDestroy();
            return;
        }
        SilenceStreamer.ensure(this);
    }

    /**
     * Keep the persisted playback offset pointing at the track that actually plays. It used to be
     * written only when playback started (playFromQueueIndex), so after any auto-advance a stop
     * followed by a resume (a PLAY with no player, which starts at the persisted offset) replayed the queue from the original start row instead of the last-played track. Resolved
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

    /** Release the current engine ahead of a track change, and drop its notification. */
    public void onMediaPlayerReset() {
        notifications.onMediaPlayerReset();
        if (audioPlayer != null)
            audioPlayer.release();
    }

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
     * Re-read the pending queue (everything after the playing track) from {@link QueueStore}, where
     * the activity has just saved its edit. The playing row is named by its stable entry id, since
     * edits renumber rows; when the store no longer has it, the pending tracks are left alone.
     *
     * <p>Truncate and re-append happen in this one onStart invocation, so an auto-advance
     * PLAYBACK_COMPLETED callback — dispatched on this same main-thread message queue — can never
     * observe an empty pending playlist between the two and stop playback mid-set. Called on every
     * queue edit made while a track is playing, so that window would otherwise be hit constantly.
     */
    private void setPendingQueue(int currentEntryId) {
        if (currentEntryId <= 0) return;
        ArrayList<QueueStore.Entry> persisted = QueueStore.load(this);
        int current = -1;
        for (int i = 0; i < persisted.size() && current < 0; i++) {
            if (persisted.get(i).id == currentEntryId) current = i;
        }
        if (current < 0) return;
        dropPendingTracks();
        for (int i = current + 1; i < persisted.size(); i++) {
            playlist.add(ServicePlaylist.Entry.of(persisted.get(i)));
        }
        sHasPendingTracks = playlistPosition < playlist.size();
        publishState();
    }

    /**
     * Append the rest of the browsed folder, which the activity saved to {@link QueueStore} as it
     * went to the background (see FileBrowserQueueActivity.queueRemainingBrowseTracks).
     */
    private void appendBrowseTail() {
        for (Uri uri : QueueStore.loadBrowseTail(this)) {
            playlist.add(ServicePlaylist.Entry.of(ServicePlaylistGenerator.titleFor(uri), uri, -1));
        }
        sHasPendingTracks = playlistPosition < playlist.size();
        publishState();
    }

    /** Remove all tracks queued after the currently playing one. */
    private void clearPendingQueue() {
        dropPendingTracks();
        sHasPendingTracks = false;
        publishState();
    }

    private void dropPendingTracks() {
        if (playlistPosition < 0) playlistPosition = 0;
        // Keeps the currently playing entry (playlistPosition - 1).
        while (playlist.size() > playlistPosition) {
            playlist.remove(playlist.size() - 1);
        }
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
        publishState();
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
     * A request to start the persisted queue at {@link #EXTRA_QUEUE_INDEX}: a Play Queue tap or a
     * remote play_track (through startForegroundService), or an Android Auto row (through the
     * MediaSession). The queue itself is read from {@link QueueStore}, where the activity saved it
     * just before; the intent used to carry every URI from the start row on, and a long queue in
     * one Parcel ran into the 1 MB binder limit.
     */
    private void playQueueRequest(Intent intent) {
        // Started with startForegroundService(): satisfy its startForeground() deadline before any
        // branch below can return.
        ensureForeground();
        // Double-start race guard. playQueueFrom() sends this AND dispatches a media-play key (the
        // Android 14+ background-FGS-start workaround). If the key wins, it has already started the
        // same persisted queue at the same offset, so this is a duplicate, and replacing would cut
        // the track that just started. A live, playing, non-fading player means exactly that unless
        // the caller asked to replace it: playEntryFromPlaylist() commits sIsPlaying synchronously.
        // A paused leftover player — or one dropped by onAudioFocusLoss() — is not playing, so a
        // queue tap after a pause still replaces it. (That case once fell into this guard, and a
        // tap on a Play Queue row after a long pause silently did nothing.)
        if (audioPlayer != null && sIsPlaying && !audioPlayer.isFadeOutInProgress()
                && !intent.getBooleanExtra(EXTRA_REPLACE_PLAYBACK, false)) {
            // The app is otherwise silent on this path; a dropped queue-play request is
            // indistinguishable from a dead tap in logcat without it.
            Log.w(TAG, "dropping duplicate queue-play intent (player already live and playing)");
            return;
        }
        if (!playFromQueueIndex(intent.getIntExtra(EXTRA_QUEUE_INDEX, -1)) && audioPlayer == null) {
            // A stale index, nothing to play: don't leave the placeholder notification pinned.
            onPlaybackStoppedKeepAlive();
        }
    }

    /**
     * Play the persisted queue from row {@code index}, replacing whatever is loaded. Also the resume
     * path: a PLAY with no player starts the queue at the persisted offset. False (and nothing
     * changed) when the row doesn't exist.
     */
    private boolean playFromQueueIndex(int index) {
        ArrayList<QueueStore.Entry> persisted = QueueStore.load(this);
        if (index < 0 || index >= persisted.size()) return false;
        if (audioPlayer != null) {
            sFadeOutInProgress = false;
            audioPlayer.release();
            audioPlayer = null;
        }
        retriedAtPosition = -1;
        sBrowseMode = false;
        QueueStore.savePlaybackOffset(this, index);
        playlist.clear();
        playlistPosition = 0;
        for (int i = index; i < persisted.size(); i++) {
            playlist.add(ServicePlaylist.Entry.of(persisted.get(i)));
        }
        playEntryFromPlaylist();
        return true;
    }

    /**
     * Start (or restart) the idle countdown, or cancel it while a track is actually playing. Called
     * from every state-commit point and from the top of {@link #onStart}, so any command — local,
     * media-button or remote — pushes retirement back out to a full {@link #IDLE_RETIRE_TIMEOUT_MS}.
     */
    private void updateIdleRetireTimer() {
        idleHandler.removeCallbacks(idleRetireRunnable);
        // onDestroy() commits a final notifyPlaybackState(); re-arming from there would outlive the
        // service (and retire() reaches it via stopSelf()).
        if (destroyed) return;
        if (sIsPlaying) {
            idleSinceElapsedMs = 0L;
            return;
        }
        idleSinceElapsedMs = SystemClock.elapsedRealtime();
        // postDelayed runs on the uptime clock, which does not advance in deep sleep, so this fires
        // after IDLE_RETIRE_TIMEOUT_MS of *awake* time — never early, sometimes late in wall-clock
        // terms. That is the right bias here: a sleeping device is not the battery drain being
        // capped, and the elapsedRealtime re-check below makes "late" harmless. An exact alarm would
        // have to wake the device to enforce a battery limit, which defeats the purpose.
        idleHandler.postDelayed(idleRetireRunnable, IDLE_RETIRE_TIMEOUT_MS);
    }

    /**
     * Retire unless something has happened since the countdown was armed. Remote traffic is
     * timestamped on the Bluetooth read thread rather than routed through this service, so it is
     * polled here instead of resetting the timer directly.
     */
    private void retireIfStillIdle() {
        if (sIsPlaying) return;             // re-armed by the next pause/stop
        long lastActivity = Math.max(idleSinceElapsedMs, BluetoothQueueBridge.lastInboundElapsedMs());
        long remaining = IDLE_RETIRE_TIMEOUT_MS - (SystemClock.elapsedRealtime() - lastActivity);
        if (remaining > 0) {
            idleHandler.postDelayed(idleRetireRunnable, remaining);
            return;
        }
        if (FileBrowserQueueActivity.sActivityStarted) {
            // The user is looking at the app; retiring would silently drop a track they paused and
            // may be about to resume. Nothing is playing, so re-check rather than give up entirely.
            idleHandler.postDelayed(idleRetireRunnable, IDLE_RETIRE_TIMEOUT_MS);
            return;
        }
        Log.w(TAG, "retiring: " + (IDLE_RETIRE_TIMEOUT_MS / 60_000L)
                + " min foreground with no playback and no remote traffic");
        retire();
    }

    /**
     * Give up foreground status and stop. Unlike {@link #onPlaybackStoppedKeepAlive()} this does not
     * keep the service around for a later background play command — that is the deliberate cost of
     * the idle cap. Once retired, a remote/media-button play cannot re-promote from the background
     * on Android 14/15 (see {@link #promoteToForeground()}), so the remote session is over until the
     * user opens the app again.
     */
    private void retire() {
        idleHandler.removeCallbacks(idleRetireRunnable);
        // The activity's onStop() leaves the silence streamer running whenever a track is merely
        // paused (it keys off Service.sCurrentUri), so an idle-foreground session holds an AudioTrack
        // thread writing to the secondary output — the real battery cost of sitting idle. Nothing is
        // playing and no activity is visible here, so nothing can want it.
        SilenceStreamer.release();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        sForegroundActive = false;
        // onDestroy() tears down the player, notification and MediaSession.
        stopSelf();
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
            audioPlayer.release();
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
        stopProgressTicks();
        idleHandler.removeCallbacks(idleRetireRunnable);
        cancelParkTimer();
        if (queueChangeListener != null) {
            QueueStore.prefs(this).unregisterOnSharedPreferenceChangeListener(queueChangeListener);
            queueChangeListener = null;
        }
        // Leave SilenceStreamer running (including any active preview) —
        // the Activity owns its lifetime and releases it in onStop().
        sFadeOutInProgress = false;
        notifyPlaybackState(false, -1, null);
        onMediaPlayerReset();
        hwListener.onMediaPlayerDestroy();
        if (audioPlayer != null) {
            audioPlayer.release();
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
        updateIdleRetireTimer();
        updateParkTimer();
        publishState();
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
     * Reported by {@link AudioPlayer} (on the main thread) once prepare() completes and the native
     * duration is cheaply available via {@code MediaPlayer.getDuration()}. Applied from a posted
     * message, after the caller's own state commit, so the progress fields, media-session metadata
     * and broadcast are updated in one place. The {@code reporter} identity check drops a
     * stale report from a superseded player (e.g. the user skipped while a slow prepare was still
     * running), so a late duration can never clobber the track that replaced it.
     */
    void onTrackDurationResolved(PlaybackEngine reporter, int durationMs) {
        progressHandler.post(() -> {
            if (reporter != audioPlayer) return;
            sPlaybackDurationMs = Math.max(0, durationMs);
            hwListener.setTrackMetadata(currentTrackTitle, sPlaybackDurationMs);
            refreshProgressSnapshot();
            publishState();
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

    /** Registers {@code listener} to run on the main thread after every playback-state change. */
    static void addStateListener(Runnable listener) {
        if (!sStateListeners.contains(listener)) sStateListeners.add(listener);
    }

    static void removeStateListener(Runnable listener) {
        sStateListeners.remove(listener);
    }

    private void publishState() {
        // Debug-only tripwire (Step 5). This method is the state-committed chokepoint — called at the
        // end of every boundary mutation — so checking here catches an inconsistency the instant it
        // is published, with a stack trace at the point of corruption, rather than tracks later as
        // mystery silence. Compiled out of release builds.
        if (BuildConfig.DEBUG && !destroyed) assertBoundaryInvariants();

        // Every observer lives in this process and reads the static fields above, so nothing needs
        // to go through system_server (this used to be a broadcast carrying copies of them). Posted
        // rather than called inline, as the broadcast was delivered, so a listener never runs in the
        // middle of the Service's own state transition.
        for (Runnable listener : sStateListeners) {
            sListenerHandler.post(listener);
        }
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
        updateIdleRetireTimer();
        updateParkTimer();
        publishState();
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
        updateIdleRetireTimer();
        updateParkTimer();
        publishState();
    }

    /**
     * Arm the park countdown while a live player sits paused on a track, cancel it otherwise.
     * Called from the same state-commit points as {@link #updateIdleRetireTimer()}.
     */
    private void updateParkTimer() {
        cancelParkTimer();
        if (destroyed || sIsPlaying || sCurrentIndex < 0
                || audioPlayer == null || audioPlayer instanceof ParkedEngine) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + PARK_AFTER_PAUSE_MS,
                        "LiveQueuePlayer:park", parkAlarm, progressHandler);
                return;
            }
        }
        progressHandler.postDelayed(parkFallback, PARK_AFTER_PAUSE_MS);
    }

    private void cancelParkTimer() {
        progressHandler.removeCallbacks(parkFallback);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) am.cancel(parkAlarm);
        }
    }

    /**
     * Release the paused player but keep everything else: the service and its foreground status,
     * the MediaSession and its paused notification, the playlist, and the current track and
     * position the activity and remote clients see. A {@link ParkedEngine} takes the player's place,
     * so a PLAY rebuilds the player and seeks back ({@link #resumeParkedTrack}).
     */
    void parkIfStillPaused() {
        PlaybackEngine engine = audioPlayer;
        if (destroyed || sIsPlaying || sCurrentIndex < 0 || engine == null
                || engine instanceof ParkedEngine || engine.isPlaying() || engine.isFadeOutInProgress()) {
            return;
        }
        // The player's own position, not the progress estimate: that one is anchored before
        // prepare() and runs ahead by however long it took (seconds, for ALAC).
        int positionMs = engine.getCurrentPositionMs();
        if (positionMs < 0) positionMs = sPlaybackPositionMs;
        engine.release();
        audioPlayer = new ParkedEngine(this, positionMs);
        sPlaybackPositionMs = positionMs;
        progressAnchorPositionMs = positionMs;
        progressAnchorElapsedMs = 0L;
        hwListener.updatePlaybackPosition(positionMs);
        publishState();
    }

    /** Rebuild the parked track's player and continue from where it was parked. */
    private void resumeParkedTrack(int positionMs) {
        audioPlayer = null;
        int index = playlistPosition - 1;
        playlistPosition = index;
        playEntryFromPlaylist();
        // A failed rebuild retries and may move on to the next track, which starts from the top.
        if (audioPlayer != null && playlistPosition == index + 1 && positionMs > 0) {
            seekTo(positionMs);
        }
    }

    /**
     * Stands in for a player released by {@link #parkIfStillPaused}. It holds no native resources,
     * only the position to resume at, so every path that expects a live engine keeps working: a
     * SEEK moves the resume point, a STOP completes at once (nothing to fade), a SKIP or a queue
     * tap replaces it like any paused player. PLAY never reaches it — onStart rebuilds a real one.
     */
    private static final class ParkedEngine implements PlaybackEngine {
        private final Service service;
        int positionMs;

        ParkedEngine(Service service, int positionMs) {
            this.service = service;
            this.positionMs = positionMs;
        }

        @Override public void start() { }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isFadeOutInProgress() { return false; }
        @Override public void cancelFadeOutAndResume() { }
        @Override public void setState(boolean playing) { }
        @Override public void release() { }
        @Override public void seekTo(int positionMs) { this.positionMs = positionMs; }
        @Override public int getCurrentPositionMs() { return positionMs; }
        @Override public void applyEqualizerSettings() { }
        @Override public void fadeOutAndStop(long durationMs) { service.onFadeOutComplete(); }
    }

    private void startProgressTicks() {
        progressHandler.removeCallbacks(progressTickRunnable);
        progressHandler.postDelayed(progressTickRunnable, PROGRESS_TICK_INTERVAL_MS);
    }

    private void stopProgressTicks() {
        progressHandler.removeCallbacks(progressTickRunnable);
    }
}

