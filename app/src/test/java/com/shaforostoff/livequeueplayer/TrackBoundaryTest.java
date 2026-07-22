package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.net.Uri;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * Step 2 — the REAL {@link Service} boundary sequencing, driven on a paused main looper under
 * Robolectric (JVM, no device). A fake {@link PlaybackEngine} stands in for {@code AudioPlayer} at
 * the {@link Service#createPlaybackEngine} seam, so there is no real audio, file I/O, or background
 * prepare thread — the test decides exactly when "prepare finished" and in what order events land.
 *
 * <p>Each test is one cell of the boundary matrix from docs/testing-race-conditions.md. After every
 * step it asserts the same invariants the Step-3 fuzzer uses, read from the Service's observable
 * state ({@code sCurrentIndex}/{@code sIsPlaying}/{@code sHasPendingTracks} + reflected
 * {@code playlistPosition}/{@code playlist}). It lives in the app package to reach the package-private
 * boundary entry points directly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class TrackBoundaryTest {

    private ServiceController<TestableService> controller;
    private TestableService service;

    @Before
    public void setUp() {
        // PAUSED is Robolectric's default; make the dependence explicit — posted work (progress ticks,
        // onTrackDurationResolved) stays queued until we idle(), so ordering is ours to decide.
        shadowOf(Looper.getMainLooper()).pause();
        controller = Robolectric.buildService(TestableService.class).create();
        service = controller.get();
    }

    @After
    public void tearDown() {
        try { controller.destroy(); } catch (RuntimeException ignored) { }
    }

    // --- boundary matrix -------------------------------------------------------------------------

    @Test
    public void completion_advancesToNextTrack() {
        startQueue(3);
        prepareCurrent();                 // track 0 playing
        assertEquals(0, Service.sCurrentIndex);

        service.onMediaPlayerComplete();  // natural end of track 0
        invariants();
        assertEquals("must advance to exactly the next track", 1, Service.sCurrentIndex);
        prepareCurrent();
        assertTrue(Service.sIsPlaying);
    }

    @Test
    public void completion_onLastTrack_stopsCleanly() {
        startQueue(1);
        prepareCurrent();
        service.onMediaPlayerComplete();  // no next entry
        invariants();
        assertEquals(-1, Service.sCurrentIndex);
        assertFalse(Service.sIsPlaying);
    }

    @Test
    public void skipDuringPrepare_thenStalePrepareDoesNotClobber() {
        startQueue(3);
        FakeEngine track0 = service.lastEngine; // still preparing
        // User skips before track 0 finished preparing.
        sendSelf(Launcher.SKIP);
        invariants();
        assertEquals(1, Service.sCurrentIndex);
        FakeEngine track1 = service.lastEngine;
        assertTrue("skip must replace the engine", track0 != track1);

        // The superseded track-0 prepare now completes late. Its duration report must be dropped
        // (identity guard), and it must not resurrect playback of track 0.
        track0.simulatePreparedLate();
        drainLooper();
        invariants();
        assertEquals("stale prepare must not move the current track", 1, Service.sCurrentIndex);

        track1.simulatePrepared();
        invariants();
        assertTrue(Service.sIsPlaying);
    }

    @Test
    public void pauseDuringPrepare_isHonoredWhenPrepareFinishes() {
        startQueue(2);
        FakeEngine track0 = service.lastEngine;
        sendSelf(Launcher.PAUSE);         // arrives while prepare() still running
        invariants();
        assertFalse(Service.sIsPlaying);

        track0.simulatePrepared();        // run() applies the deferred intent (pause), not a default play
        invariants();
        assertFalse("a pause during prepare must survive prepare completion", track0.started);
        assertFalse(Service.sIsPlaying);
    }

    @Test
    public void stopFadeRacingCompletion_doesNotAdvance() {
        startQueue(2);
        prepareCurrent();
        sendSelf(Launcher.STOP);          // begins fade-out; STOP intent's effect is in flight
        assertTrue(service.lastEngine.isFadeOutInProgress());

        service.onMediaPlayerComplete();  // natural end races the user-initiated stop
        invariants();
        assertFalse("stop must win over auto-advance", Service.sIsPlaying);
        assertEquals(-1, Service.sCurrentIndex);
    }

    @Test
    public void setPendingQueueThenCompletion_advancesIntoReplacement() {
        startQueue(2);                    // [t0, t1], playing t0
        prepareCurrent();
        // Replace the pending queue (everything after the current track) with two fresh tracks.
        sendSelfWithUris(Launcher.SET_PENDING_QUEUE, uris(2));
        invariants();
        assertTrue(Service.sHasPendingTracks);

        service.onMediaPlayerComplete();  // must advance into the replacement set, not stop
        invariants();
        assertEquals(1, Service.sCurrentIndex);
        assertTrue(Service.sIsPlaying);
    }

    @Test
    public void prepareFailure_retriesOnceThenAdvances() {
        startQueue(2);
        FakeEngine t0 = service.lastEngine;
        t0.simulatePrepareFailure();      // playOrDestroy retries the same index once
        invariants();
        assertEquals("retry stays on the same track", 0, Service.sCurrentIndex);

        FakeEngine t0retry = service.lastEngine;
        assertTrue(t0 != t0retry);
        t0retry.simulatePrepareFailure(); // second failure → give up and advance
        invariants();
        assertEquals(1, Service.sCurrentIndex);
    }

    /**
     * Regression for the teardown race the Step-5 debug tripwire surfaced: a duration report posted
     * when prepare() finished, still queued when onDestroy() runs, used to republish against a
     * cleared queue / released MediaSession because onDestroy left {@code audioPlayer} non-null.
     * onDestroy now nulls it, so the identity guard drops the stale report.
     */
    @Test
    public void staleDurationReportAfterDestroy_isDroppedNotRepublished() {
        startQueue(2);
        service.lastEngine.simulatePrepared(); // posts onTrackDurationResolved to the paused looper
        controller.destroy();                  // tears down before the report drains (idles internally)
        drainLooper();                          // stale report runs here — must be a no-op, no crash
        // Reaching here without the debug tripwire throwing is the assertion.
    }

    // --- harness ---------------------------------------------------------------------------------

    /** Seed and start a queue of {@code n} file:// tracks through the real external-start path. */
    private void startQueue(int n) {
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris(n));
        service.onStartCommand(i, 0, 1);
        invariants();
        assertEquals(0, Service.sCurrentIndex);
    }

    /** Drive the current engine's prepare to success, mirroring AudioPlayer.run()'s tail. */
    private void prepareCurrent() {
        service.lastEngine.simulatePrepared();
        invariants();
    }

    private void sendSelf(byte action) {
        Intent i = new Intent();                 // action == null → "called from self" branch
        i.putExtra(Launcher.TYPE, action);
        service.onStartCommand(i, 0, nextId());
    }

    private void sendSelfWithUris(byte action, ArrayList<Uri> uris) {
        Intent i = new Intent();
        i.putExtra(Launcher.TYPE, action);
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        service.onStartCommand(i, 0, nextId());
    }

    private int startId = 1;
    private int nextId() { return ++startId; }

    private static ArrayList<Uri> uris(int n) {
        ArrayList<Uri> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(Uri.parse("file:///music/track" + i + ".mp3"));
        return list;
    }

    private void drainLooper() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** The Step-3 invariants, asserted against the real Service's observable state. */
    private void invariants() {
        int position = reflectInt("playlistPosition");
        int size = reflectPlaylistSize();
        boolean hasPlayer = reflectField("audioPlayer") != null;
        int idx = Service.sCurrentIndex;

        assertTrue("position in range: " + position + "/" + size, position >= 0 && position <= size);
        assertTrue("currentIndex in range: " + idx + "/" + size,
                idx >= -1 && idx < Math.max(size, 1) && (size != 0 || idx == -1));
        if (hasPlayer && idx >= 0) {
            assertEquals("playlistPosition must be currentIndex + 1", idx + 1, position);
        }
        if (Service.sIsPlaying) {
            assertTrue("playing implies a live engine", hasPlayer);
            assertTrue("playing implies a current track", idx >= 0);
        }
    }

    // --- reflection into the Service's private boundary state ------------------------------------

    private int reflectInt(String name) {
        return (Integer) reflectField(name);
    }

    private int reflectPlaylistSize() {
        Object playlist = reflectField("playlist");
        return playlist == null ? 0 : ((java.util.List<?>) playlist).size();
    }

    private Object reflectField(String name) {
        try {
            Field f = Service.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failed for field '" + name + "' — did it get renamed?", e);
        }
    }

    // --- test doubles ----------------------------------------------------------------------------

    /** Real Service with the audio engine and the remote-host check swapped for test-controllable stubs. */
    public static class TestableService extends Service {
        FakeEngine lastEngine;

        @Override
        protected PlaybackEngine createPlaybackEngine(Uri location) throws IOException {
            lastEngine = new FakeEngine(this);
            return lastEngine;
        }

        @Override
        protected boolean isRemoteHostSession() {
            return false; // never touch the real App/BluetoothBridge in tests
        }
    }

    /**
     * Stand-in for AudioPlayer with no threads or native player. The test drives its "prepare"
     * lifecycle explicitly; {@link #setState} mirrors AudioPlayer's defer-until-prepared behavior.
     */
    static class FakeEngine implements PlaybackEngine {
        private final Service service;
        boolean prepared, started, released, fadeOut;
        Boolean pendingIntent; // transport command deferred until prepared

        FakeEngine(Service service) { this.service = service; }

        @Override public void start() { /* real one kicks a prepare thread; here the test drives it */ }

        @Override public boolean isPlaying() { return !released && started; }

        @Override public boolean isFadeOutInProgress() { return fadeOut; }

        @Override public void cancelFadeOutAndResume() { if (!released) { fadeOut = false; started = true; } }

        @Override public void setState(boolean playing) {
            if (released) return;
            if (!prepared) { pendingIntent = playing; return; }
            started = playing;
        }

        @Override public void onMediaPlayerReset() { released = true; started = false; }

        @Override public void onMediaPlayerDestroy() { released = true; started = false; }

        @Override public void seekTo(int positionMs) { }

        @Override public void applyEqualizerSettings() { }

        @Override public void fadeOutAndStop(long durationMs) {
            if (released) service.onFadeOutComplete(); else fadeOut = true;
        }

        // --- test-driven prepare lifecycle, mirroring AudioPlayer.run() (AudioPlayer.java:107) ---

        /** prepare() succeeds: report duration on the main looper, then apply the deferred intent. */
        void simulatePrepared() {
            if (released) return;
            prepared = true;
            service.onTrackDurationResolved(this, 1_000);       // posted to the paused looper
            service.setState(pendingIntent == null || pendingIntent);
        }

        /** A superseded player finishing prepare late — same tail, but it has been released, so the
         *  duration report must be dropped by the identity guard and setState is a no-op. */
        void simulatePreparedLate() {
            prepared = true;
            service.onTrackDurationResolved(this, 1_000);
            service.setState(pendingIntent == null || pendingIntent);
        }

        /** prepare() throws → AudioPlayer calls service.playOrDestroy(). */
        void simulatePrepareFailure() {
            service.playOrDestroy();
        }
    }
}
