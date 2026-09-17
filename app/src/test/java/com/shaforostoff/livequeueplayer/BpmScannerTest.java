package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The scheduling half of BPM analysis, on the JVM.
 *
 * <p>The analysis itself cannot be tested here — it is arm64-only native code, and this runs on an
 * x86_64 JVM — so {@link BpmEngine} is faked and what is checked is everything around it: which
 * tracks get queued, that none is queued twice, that a cancel actually stops work, and that each of
 * the engine's four kinds of answer is written into the cache the right way. That is where the bugs
 * in this feature live; the numbers are checked on the host by {@code tools/bpmcheck/run.sh}.
 */
@RunWith(RobolectricTestRunner.class)
// sdk 33 because Robolectric 4.13 cannot parse a targetSdk 36 manifest; the plain
// Application because the real App would build a scanner and a thread pool this test has no
// use for. Both match the existing Robolectric tests here.
@Config(application = android.app.Application.class, sdk = 33)
public class BpmScannerTest {

    private MetadataExtractor metadata;

    /** Runs the coalesced notification immediately, so a test never waits on a real main looper. */
    private static final BpmScanner.Dispatcher INLINE = (r, delay) -> r.run();

    @Before
    public void setUp() {
        metadata = new MetadataExtractor(RuntimeEnvironment.getApplication().getContentResolver());
    }

    /**
     * Puts {@code uri} in the cache in the state a tag read leaves an untagged file in.
     *
     * <p>Through the real readSortTags rather than by reaching into the map: nothing about the file
     * exists, every parser fails and is caught, and what is left is the {@code bpm == 0} sentinel
     * this feature keys off. If that ever stops being what an unreadable file produces, this fails
     * here rather than silently in the app.
     */
    private Uri seedUntagged(String name) {
        Uri uri = Uri.parse("content://test/" + name);
        MetadataExtractor.TagEntry e = metadata.readSortTags(uri);
        assertEquals("a file with no readable tags should land on the 0 sentinel", 0, e.bpm);
        assertEquals(0, e.measuredBpm);
        assertTrue(metadata.needsBpmAnalysis(uri));
        return uri;
    }

    /** An engine that answers from a script and records what it was asked. */
    private static final class FakeEngine implements BpmEngine {
        final List<Uri> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        final Deque<Integer> answers = new ArrayDeque<>();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger peakConcurrent = new AtomicInteger();
        CountDownLatch entered;
        CountDownLatch release;
        boolean available = true;

        @Override public boolean available() { return available; }

        @Override public int measure(Uri uri, BpmJob job) {
            int now = concurrent.incrementAndGet();
            peakConcurrent.accumulateAndGet(now, Math::max);
            try {
                seen.add(uri);
                if (entered != null) entered.countDown();
                if (release != null) {
                    // Hold here so a test can cancel mid-measurement, the way a real analysis is
                    // held inside its native finish().
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    if (job.isCancelled()) return RESULT_CANCELLED;
                }
                Integer next = answers.poll();
                return next == null ? RESULT_NO_TEMPO : next;
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }

    private static void drain(BpmScanner scanner) {
        long deadline = System.currentTimeMillis() + 5000;
        while (scanner.activeWorkers() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertEquals("workers should all have exited", 0, scanner.activeWorkers());
    }

    @Test
    public void measuredBpmLandsInTheCacheAndIsMarkedAsMeasured() {
        Uri uri = seedUntagged("a.mp3");
        FakeEngine engine = new FakeEngine();
        engine.answers.add(126);

        BpmScanner scanner = new BpmScanner(metadata, engine, INLINE, 2);
        scanner.submit(Arrays.asList(uri));
        drain(scanner);

        MetadataExtractor.TagEntry e = metadata.peekCachedTags(uri);
        assertEquals(126, e.measuredBpm);
        assertEquals("the tag itself must not be rewritten", 0, e.bpm);
        assertEquals(126, MetadataExtractor.effectiveBpm(e));
        assertTrue(MetadataExtractor.isBpmMeasured(e));
        assertFalse("a measured track must not be queued again", metadata.needsBpmAnalysis(uri));
    }

    @Test
    public void aTaggedBpmAlwaysWins() {
        Uri uri = seedUntagged("b.mp3");
        MetadataExtractor.TagEntry e = metadata.peekCachedTags(uri);
        e.bpm = 118;   // as if the tag read had found one after the analysis was queued

        metadata.recordMeasuredBpm(uri, 126, metadata.cacheGeneration());

        assertEquals(0, e.measuredBpm);
        assertEquals(118, MetadataExtractor.effectiveBpm(e));
        assertFalse(MetadataExtractor.isBpmMeasured(e));
    }

    @Test
    public void noTempoIsRememberedSoTheFileIsNotRetried() {
        Uri uri = seedUntagged("c.mp3");
        FakeEngine engine = new FakeEngine();
        engine.answers.add(BpmEngine.RESULT_NO_TEMPO);

        BpmScanner scanner = new BpmScanner(metadata, engine, INLINE, 1);
        scanner.submit(Arrays.asList(uri));
        drain(scanner);

        MetadataExtractor.TagEntry e = metadata.peekCachedTags(uri);
        assertEquals("analysed, nothing found", -1, e.measuredBpm);
        assertEquals("a negative result must not show as a BPM", 0, MetadataExtractor.effectiveBpm(e));
        assertFalse(metadata.needsBpmAnalysis(uri));

        // A second submit must not reach the engine at all.
        scanner.submit(Arrays.asList(uri));
        drain(scanner);
        assertEquals(1, engine.seen.size());
    }

    @Test
    public void anUnavailableDecoderLeavesTheTrackRetryable() {
        Uri uri = seedUntagged("d.mp3");
        FakeEngine engine = new FakeEngine();
        engine.answers.add(BpmEngine.RESULT_UNAVAILABLE);

        BpmScanner scanner = new BpmScanner(metadata, engine, INLINE, 1);
        scanner.submit(Arrays.asList(uri));
        drain(scanner);

        assertEquals(0, metadata.peekCachedTags(uri).measuredBpm);
        assertTrue("a codec that was busy must be tried again", metadata.needsBpmAnalysis(uri));

        engine.answers.add(130);
        scanner.submit(Arrays.asList(uri));
        drain(scanner);
        assertEquals(130, metadata.peekCachedTags(uri).measuredBpm);
    }

    @Test
    public void theSameTrackIsNeverQueuedTwice() throws Exception {
        Uri uri = seedUntagged("e.mp3");
        FakeEngine engine = new FakeEngine();
        engine.entered = new CountDownLatch(1);
        engine.release = new CountDownLatch(1);
        engine.answers.add(120);

        BpmScanner scanner = new BpmScanner(metadata, engine, INLINE, 4);
        scanner.submit(Arrays.asList(uri));
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS));

        // Submitted again while the first measurement is still running.
        scanner.submit(Arrays.asList(uri, uri, uri));
        engine.release.countDown();
        drain(scanner);

        assertEquals(1, engine.seen.size());
    }

    @Test
    public void concurrencyStaysWithinTheWorkerLimit() throws Exception {
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < 12; i++) uris.add(seedUntagged("f" + i + ".mp3"));

        FakeEngine engine = new FakeEngine();
        for (int i = 0; i < 12; i++) engine.answers.add(120 + i);

        BpmScanner scanner = new BpmScanner(metadata, engine, INLINE, 3);
        scanner.submit(uris);
        drain(scanner);

        assertEquals(12, engine.seen.size());
        assertTrue("peak was " + engine.peakConcurrent.get(), engine.peakConcurrent.get() <= 3);
    }

    @Test
    public void cancelAllStopsWorkInFlightAndRecordsNothing() throws Exception {
        Uri running = seedUntagged("g.mp3");
        List<Uri> rest = new ArrayList<>();
        for (int i = 0; i < 6; i++) rest.add(seedUntagged("h" + i + ".mp3"));

        FakeEngine engine = new FakeEngine();
        engine.entered = new CountDownLatch(1);
        engine.release = new CountDownLatch(1);

        BpmScanner scanner = new BpmScanner(metadata, engine, INLINE, 1);
        List<Uri> all = new ArrayList<>();
        all.add(running);
        all.addAll(rest);
        scanner.submit(all);
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS));

        scanner.cancelAll();
        engine.release.countDown();
        drain(scanner);

        assertEquals("only the one already running should have been touched", 1, engine.seen.size());
        assertEquals("a cancelled measurement must record nothing",
                0, metadata.peekCachedTags(running).measuredBpm);
        for (Uri u : rest) assertTrue(metadata.needsBpmAnalysis(u));
    }

    @Test
    public void clearingTheCacheDropsResultsFromTheOldLibrary() {
        Uri uri = seedUntagged("i.mp3");
        int generation = metadata.cacheGeneration();

        metadata.clearCache();
        metadata.recordMeasuredBpm(uri, 126, generation);

        assertEquals("a stale key must not be resurrected into the fresh cache",
                null, metadata.peekCachedTags(uri));
    }

    @Test
    public void nothingIsQueuedWhenTheAnalysisIsNotInTheBuild() {
        Uri uri = seedUntagged("j.mp3");
        FakeEngine engine = new FakeEngine();
        engine.available = false;

        BpmScanner scanner = new BpmScanner(metadata, engine, INLINE, 2);
        assertFalse(scanner.isAvailable());
        scanner.submit(Arrays.asList(uri));
        drain(scanner);

        assertTrue(engine.seen.isEmpty());
        assertEquals(0, metadata.peekCachedTags(uri).measuredBpm);
    }

    @Test
    public void listenersAreNotifiedOnceForABurstOfResults() {
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < 5; i++) uris.add(seedUntagged("k" + i + ".mp3"));

        FakeEngine engine = new FakeEngine();
        for (int i = 0; i < 5; i++) engine.answers.add(120 + i);

        // Holds the posted runnable instead of running it, so the coalescing window stays open for
        // the whole batch — which is what it does in the app, where the post is delayed 300ms.
        final List<Runnable> posted = new ArrayList<>();
        BpmScanner.Dispatcher deferred = (r, delay) -> posted.add(r);

        AtomicInteger notifications = new AtomicInteger();
        BpmScanner scanner = new BpmScanner(metadata, engine, deferred, 2);
        scanner.addListener(notifications::incrementAndGet);
        scanner.submit(uris);
        drain(scanner);

        assertEquals("five results, one pending redraw", 1, posted.size());
        posted.get(0).run();
        assertEquals(1, notifications.get());
    }

    @Test
    public void cachedKeysAreNotLeakedByRepeatedSubmits() {
        Uri uri = seedUntagged("l.mp3");
        FakeEngine engine = new FakeEngine();
        engine.answers.add(BpmEngine.RESULT_UNAVAILABLE);
        engine.answers.add(BpmEngine.RESULT_UNAVAILABLE);
        engine.answers.add(122);

        BpmScanner scanner = new BpmScanner(metadata, engine, INLINE, 1);
        for (int i = 0; i < 3; i++) {
            scanner.submit(Arrays.asList(uri));
            drain(scanner);
        }
        // Each pass must have reached the engine: an in-flight key that was never released would
        // have made the second and third submits no-ops.
        assertEquals(3, engine.seen.size());
        assertEquals(122, metadata.peekCachedTags(uri).measuredBpm);
    }
}
