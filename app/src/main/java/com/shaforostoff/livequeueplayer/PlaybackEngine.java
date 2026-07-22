package com.shaforostoff.livequeueplayer;

/**
 * The audio engine as seen by {@link Service}. {@link AudioPlayer} is the production implementation
 * (a real {@link android.media.MediaPlayer} on its own prepare thread); {@code Service} only ever
 * touches it through this interface plus the {@link Service#createPlaybackEngine} factory.
 *
 * <p>The seam exists so the track-boundary sequencing in {@code Service} can be driven on a paused
 * main looper under Robolectric with a fake engine — no real audio, files, or background threads —
 * see {@code app/src/test/.../boundary/TrackBoundaryTest} and docs/testing-race-conditions.md. It
 * changes no production behavior: the default factory returns a real {@code AudioPlayer}.
 */
interface PlaybackEngine {

    /** Begin preparing/playing (AudioPlayer starts its prepare thread). */
    void start();

    boolean isPlaying();

    boolean isFadeOutInProgress();

    void cancelFadeOutAndResume();

    /** Apply a PLAY (true) / PAUSE (false) transport command; deferred internally until prepared. */
    void setState(boolean playing);

    /** Release this engine so a new one can replace it (track change). */
    void onMediaPlayerReset();

    /** Fully tear down this engine. */
    void onMediaPlayerDestroy();

    void seekTo(int positionMs);

    void applyEqualizerSettings();

    void fadeOutAndStop(long durationMs);
}
