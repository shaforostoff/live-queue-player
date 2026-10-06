package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Duration;

/** The silence streamer stops after an hour of wake time paused with no activity on screen. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class SilenceStreamerIdleTest {

    @Before
    public void setUp() {
        shadowOf(Looper.getMainLooper()).pause();
        setStatic("sPausedHiddenArmed", false);
        setStatic("sStoppedWhilePausedHidden", false);
        setStatic("sAppContext", RuntimeEnvironment.getApplication());
        Service.sIsPlaying = false;
        Service.sCurrentUri = Uri.parse("file:///music/t.mp3");
        FileBrowserQueueActivity.sActivityStarted = false;
        // A never-started instance: stop() on it touches no audio, which is all release() needs.
        SilenceStreamer.current = new SilenceStreamer();
    }

    @After
    public void tearDown() {
        Service.sCurrentUri = null;
        Service.sIsPlaying = false;
        SilenceStreamer.current = null;
    }

    @Test
    public void pausedAndHidden_stopsAfterAnHour() {
        SilenceStreamer.onPlaybackOrVisibilityChanged();
        advance(Duration.ofMinutes(59));
        assertNotNull(SilenceStreamer.current);
        advance(Duration.ofMinutes(2));
        assertNull(SilenceStreamer.current);
    }

    @Test
    public void repeatedCalls_doNotRestartTheHour() {
        SilenceStreamer.onPlaybackOrVisibilityChanged();
        advance(Duration.ofMinutes(40));
        SilenceStreamer.onPlaybackOrVisibilityChanged();
        advance(Duration.ofMinutes(21));
        assertNull(SilenceStreamer.current);
    }

    @Test
    public void visibleActivity_keepsIt() {
        FileBrowserQueueActivity.sActivityStarted = true;
        SilenceStreamer.onPlaybackOrVisibilityChanged();
        advance(Duration.ofHours(2));
        assertNotNull(SilenceStreamer.current);
    }

    @Test
    public void resumingPlayback_cancelsTheCountdown() {
        SilenceStreamer.onPlaybackOrVisibilityChanged();
        advance(Duration.ofMinutes(30));
        Service.sIsPlaying = true;
        SilenceStreamer.onPlaybackOrVisibilityChanged();
        advance(Duration.ofHours(2));
        assertNotNull(SilenceStreamer.current);
    }

    @Test
    public void playAfterTheStop_bringsItBack() {
        SilenceStreamer.onPlaybackOrVisibilityChanged();
        advance(Duration.ofMinutes(61));
        assertTrue((Boolean) getStatic("sStoppedWhilePausedHidden"));
        Service.sIsPlaying = true;
        SilenceStreamer.onPlaybackOrVisibilityChanged();
        // ensure() ran; it clears the flag (it cannot start a track here: no second output).
        assertFalse((Boolean) getStatic("sStoppedWhilePausedHidden"));
    }

    private static void advance(Duration d) {
        shadowOf(Looper.getMainLooper()).idleFor(d);
    }

    private static Object getStatic(String name) {
        try {
            Field f = SilenceStreamer.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setStatic(String name, Object value) {
        try {
            Field f = SilenceStreamer.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
