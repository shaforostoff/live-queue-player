package com.shaforostoff.livequeueplayer;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;

/**
 * service for playing music
 */
public class Service extends android.app.Service implements MediaPlayerStateListener {

    static final String ACTION_PLAYBACK_STATE = "com.shaforostoff.livequeueplayer.PLAYBACK_STATE";
    static final String ACTION_PENDING_QUEUE_CLEARED = "com.shaforostoff.livequeueplayer.PENDING_QUEUE_CLEARED";
    static final String EXTRA_CURRENT_INDEX = "current_index";
    static final String EXTRA_IS_PLAYING = "is_playing";
    static final String EXTRA_CURRENT_URI = "current_uri";
    static final String EXTRA_PLAYBACK_POSITION_MS = "playback_position_ms";
    static final String EXTRA_PLAYBACK_DURATION_MS = "playback_duration_ms";
    static final String EXTRA_HAS_PENDING_TRACKS = "has_pending_tracks";
    static final String EXTRA_FADE_OUT_IN_PROGRESS = "fade_out_in_progress";
    private static final long PLAYBACK_PROGRESS_BROADCAST_INTERVAL_MS = 1_000L;

    // Readable by the activity to re-sync state after missed broadcasts (e.g. screen off)
    static volatile boolean sIsPlaying = false;
    static volatile int sCurrentIndex = -1;
    static volatile Uri sCurrentUri = null;
    static volatile int sPlaybackPositionMs = 0;
    static volatile int sPlaybackDurationMs = 0;
    static volatile boolean sHasPendingTracks = false;
    static volatile boolean sFadeOutInProgress = false;

    private HWListener hwListener;
    private Notifications notifications;
    private AudioPlayer audioPlayer;
    private SilenceStreamer silenceStreamer;

    private Playlist playlist;
    /** Index of the next entry to play in {@link #playlist}. */
    private int playlistPosition = 0;
    private int progressAnchorPositionMs = 0;
    private long progressAnchorElapsedMs = 0L;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sIsPlaying) return;
            refreshProgressSnapshot();
            sendPlaybackStateBroadcast();
            progressHandler.postDelayed(this, PLAYBACK_PROGRESS_BROADCAST_INTERVAL_MS);
        }
    };

    public Service() {
    }

    /**
     * unused
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * setup
     */
    @Override
    public void onCreate() {
        super.onCreate();
        hwListener = new HWListener(this);
        notifications = new Notifications(this);
        playlist = new Playlist(this);
        hwListener.create();
        notifications.create();
    }

    /**
     * startup logic
     */
    @Override
    public void onStart(final Intent intent, final int startId) {
        /* check if called from self */
        if (intent.getAction() == null) {
            var action = intent.getByteExtra(Launcher.TYPE, Launcher.NULL);
            if (audioPlayer == null) {
                if (action == Launcher.KILL || action == Launcher.STOP) {
                    stopSelf();
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
                    sendPlaybackStateBroadcast();
                    audioPlayer.fadeOutAndStop(AudioOutputRouter.getFadeOutSeconds(this) * 1_000L);
                }
                case Launcher.APPEND_QUEUE -> appendQueueFromIntent(intent);
                case Launcher.CLEAR_QUEUE -> clearPendingQueue();
                /* cancel audio playback and kill service */
                case Launcher.KILL -> stopSelf();
            }
        } else {
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
                Playlist.Entry e = playlist.get(i);
                newEntries.add(new QueueStore.Entry(e.title, e.location));
            }
            if (audioPlayer == null) {
                QueueStore.clear(this);
                QueueStore.save(this, newEntries);
                playEntryFromPlaylist();
            } else {
                ArrayList<QueueStore.Entry> stored = QueueStore.load(this);
                stored.addAll(newEntries);
                QueueStore.save(this, stored);
            }
        }
    }

    private void playEntryFromPlaylist() {
        var entry = playlist.get(playlistPosition);
        playlistPosition++;
        int currentIndex = playlistPosition - 1;
        try {

            AudioOutputRouter.resolve(this);
            /* get audio playback logic and start async */
            audioPlayer = new AudioPlayer(this, entry.location);
            audioPlayer.start();

            /* create notification for playback control */
            notifications.getNotification(entry.title);

            /* start service as foreground */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(Notifications.NOTIFICATION_ID, notifications.notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else
                startForeground(Notifications.NOTIFICATION_ID, notifications.notification);

            initializeProgressForTrack(entry.location);
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
        if (!playNextEntry())
            onMediaPlayerDestroy();
    }

    @Override
    public void setState(boolean playing) {
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
     * destroy on playback complete
     */
    void onMediaPlayerComplete() {
        if (!playNextEntry())
            onMediaPlayerDestroy();
    }

    /**
     * service killing logic
     */
    @Override
    public void onDestroy() {
        stopProgressTicks();
        // Leave SilenceStreamer running — the Activity owns its lifetime and will
        // release it in onStop() when no service is playing. Just stop the decoder.
        if (silenceStreamer != null) {
            PreviewManager.isPreviewActive = false;
            SilenceStreamer.stopPreview();
            silenceStreamer = null;
        }
        sFadeOutInProgress = false;
        notifyPlaybackState(false, -1, null);
        onMediaPlayerReset();
        notifications.onMediaPlayerDestroy();
        hwListener.onMediaPlayerDestroy();
        if (audioPlayer != null)
            audioPlayer.onMediaPlayerDestroy();
        playlist.clear();

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
        // Update pending tracks state based on current playlist position
        sHasPendingTracks = playlistPosition < playlist.size();
        sendPlaybackStateBroadcast();
    }

    private void initializeProgressForTrack(Uri trackUri) {
        sPlaybackPositionMs = 0;
        sPlaybackDurationMs = loadTrackDurationMs(trackUri);
        progressAnchorPositionMs = 0;
        progressAnchorElapsedMs = SystemClock.elapsedRealtime();
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
        sendBroadcast(intent);
    }

    void onAudioFocusLoss(int currentPositionMs) {
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

    private int loadTrackDurationMs(Uri uri) {
        if (uri == null) {
            return 0;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String durationValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationValue == null) {
                return 0;
            }
            return Math.max(0, Integer.parseInt(durationValue));
        } catch (Exception ignored) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }
}

