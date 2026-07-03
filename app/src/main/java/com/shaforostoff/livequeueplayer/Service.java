package com.shaforostoff.livequeueplayer;

import android.annotation.SuppressLint;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * service for playing music
 */
public class Service extends android.service.media.MediaBrowserService implements MediaPlayerStateListener {

    static final String ACTION_PLAYBACK_STATE = "com.shaforostoff.livequeueplayer.PLAYBACK_STATE";
    static final String ACTION_PENDING_QUEUE_CLEARED = "com.shaforostoff.livequeueplayer.PENDING_QUEUE_CLEARED";
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

    private HWListener hwListener;
    private Notifications notifications;
    private AudioPlayer audioPlayer;
    private SilenceStreamer silenceStreamer;
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
        /* check if called from self */
        if (intent.getAction() == null) {
            var action = intent.getByteExtra(Launcher.TYPE, Launcher.NULL);
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
            int[] entryIds = intent.getIntArrayExtra(EXTRA_ENTRY_IDS);
            if (entryIds != null) {
                for (int i = 0; i < entryIds.length && (sizeBefore + i) < playlist.size(); i++) {
                    playlist.get(sizeBefore + i).queueEntryId = entryIds[i];
                }
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(Notifications.NOTIFICATION_ID, notifications.notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else
            startForeground(Notifications.NOTIFICATION_ID, notifications.notification);
    }

    private void playEntryFromPlaylist() {
        // Acquire before constructing the new AudioPlayer so the wake lock is held through the
        // upcoming prepare() (blocking I/O). For auto-advance this is already held from the
        // previous track; for the first track this is where it first becomes held.
        acquirePlaybackWakeLock();
        var entry = playlist.get(playlistPosition);
        playlistPosition++;
        int currentIndex = playlistPosition - 1;
        try {

            AudioOutputRouter.resolve(this);
            // Lock in whether the EQ may engage for this track; the decision must not change
            // mid-track when outputs are plugged or unplugged.
            AudioOutputRouter.snapshotAudioPreviewAvailability(this);
            /* get audio playback logic and start async */
            audioPlayer = new AudioPlayer(this, entry.location);
            audioPlayer.start();

            /* create notification for playback control */
            notifications.getNotification(entry.title, hwListener.getSessionToken());

            /* start service as foreground */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(Notifications.NOTIFICATION_ID, notifications.notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else
                startForeground(Notifications.NOTIFICATION_ID, notifications.notification);

            initializeProgressForTrack(entry.location);
            currentTrackTitle = entry.title != null ? entry.title : "";
            sCurrentEntryId = entry.queueEntryId;
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
        silenceStreamer = SilenceStreamer.current;
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
        ArrayList<?> stream = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (stream != null) {
            ArrayList<Uri> uriList = new ArrayList<>(stream.size());
            for (Object item : stream) {
                if (item instanceof Uri uri) uriList.add(uri);
            }
            if (!uriList.isEmpty()) playlist.generate(uriList);
        }
        sHasPendingTracks = playlistPosition < playlist.size();
        sendPlaybackStateBroadcast();
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
        // Notify that pending queue was cleared
        sendBroadcast(new Intent(ACTION_PENDING_QUEUE_CLEARED));
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        notifications.onMediaPlayerReset();
        notifyPlaybackState(false, -1, null);
    }

    /**
     * service killing logic
     */
    @Override
    public void onDestroy() {
        stopProgressTicks();
        if (queueChangeListener != null) {
            QueueStore.prefs(this).unregisterOnSharedPreferenceChangeListener(queueChangeListener);
            queueChangeListener = null;
        }
        // Leave SilenceStreamer running (including any active preview) —
        // the Activity owns its lifetime and releases it in onStop().
        silenceStreamer = null;
        sFadeOutInProgress = false;
        notifyPlaybackState(false, -1, null);
        onMediaPlayerReset();
        notifications.onMediaPlayerDestroy();
        hwListener.onMediaPlayerDestroy();
        if (audioPlayer != null)
            audioPlayer.onMediaPlayerDestroy();
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
    void onTrackDurationResolved(AudioPlayer reporter, int durationMs) {
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
        Intent intent = new Intent(ACTION_PLAYBACK_STATE);
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

