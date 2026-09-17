package com.shaforostoff.livequeueplayer;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Measures the tempo of tracks that carry no BPM tag, and writes the answer into the tag cache.
 *
 * <p>Application-scoped, for the reason {@link App#getTagReadExecutor()} is: a rotation destroys the
 * activity that asked for the work, and a scanner owned by that activity would either be torn down
 * mid-analysis or would keep the dead activity alive. Neither is wanted, and the results land in the
 * (also app-scoped) tag cache regardless of who is on screen to see them.
 *
 * <p>Threading is one thread per <em>track</em>, never one track across threads: the native analysis
 * is pinned to its calling thread, so the two kinds of parallelism would only fight over the same
 * cores. Workers run at {@link Process#THREAD_PRIORITY_BACKGROUND}, which is what keeps a folder-wide
 * scan from ever being heard — a BPM arriving thirty seconds late is invisible, a dropout mid-set is
 * not.
 */
final class BpmScanner {

    /** Coalesced "some results landed" signal. The values themselves are in the tag cache. */
    interface Listener {
        void onBpmMeasured();
    }

    /** Where the coalesced notification is delivered. The main thread in the app, inline in tests. */
    interface Dispatcher {
        void postDelayed(Runnable r, long delayMs);
    }

    /**
     * A folder of two hundred tracks would otherwise deliver two hundred separate results, each one
     * a full re-sort and re-filter of the list. One redraw per this many milliseconds instead.
     */
    private static final long NOTIFY_DEBOUNCE_MS = 300;

    private final MetadataExtractor metadata;
    private final BpmEngine engine;
    private final Dispatcher dispatcher;
    private final int workerLimit;

    /** Pending work, newest folder first. Guarded by itself; also guards {@link #liveWorkers}. */
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private int liveWorkers;
    /** Queued and finished in the current run, for the progress bar. Both reset once it drains. */
    private int runTotal;
    private int runDone;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, BpmJob> running = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean notifyPending = new AtomicBoolean();

    private static final class Item {
        final String key;
        final Uri uri;
        final int generation;

        Item(String key, Uri uri, int generation) {
            this.key = key;
            this.uri = uri;
            this.generation = generation;
        }
    }

    BpmScanner(Context context, MetadataExtractor metadata) {
        this(metadata, new NativeBpmEngine(context), mainThreadDispatcher(),
                workersFor(context));
    }

    /** For tests: a fake engine, an inline dispatcher and a fixed worker count. */
    BpmScanner(MetadataExtractor metadata, BpmEngine engine, Dispatcher dispatcher, int workerLimit) {
        this.metadata = metadata;
        this.engine = engine;
        this.dispatcher = dispatcher;
        this.workerLimit = Math.max(1, workerLimit);
    }

    private static Dispatcher mainThreadDispatcher() {
        final Handler handler = new Handler(Looper.getMainLooper());
        return handler::postDelayed;
    }

    /**
     * How many tracks to analyse at once.
     *
     * <p>Two cores fewer than the machine has, so the scan never owns the whole device — one core
     * for playback and its effects, one for whatever the user is doing. The figure that bounds this
     * from the other side is memory: the native collector reserves 240 seconds of audio at its
     * analysis rate, which is 21 MB for a file it resamples to 22.05 kHz but 42 MB for a 44.1 kHz
     * one, where the rate is already a power-of-two multiple of the model's and nothing is
     * resampled. Six workers on an eight-core phone is therefore around 250 MB of native heap at
     * the worst, which is survivable on a modern device and is not on a small one — hence the
     * low-RAM cases below, and hence this being the constant to turn down if the low-memory killer
     * ever starts taking the process.
     */
    private static int workersFor(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && (am.isLowRamDevice() || am.getMemoryClass() <= 128)) return 1;
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }

    /** False where the native analysis is not in this build; every caller can then skip its work. */
    boolean isAvailable() {
        return engine.available();
    }

    void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /**
     * Queues every URI that has been tag-read, carries no BPM tag and has not been measured yet.
     * Anything else is dropped here rather than in a worker, so the queue only ever holds real work.
     */
    void submit(Iterable<Uri> uris) {
        if (uris == null || !isAvailable()) return;
        final int generation = metadata.cacheGeneration();

        // Sifted before the lock is taken. submit() runs on the main thread with a whole folder in
        // hand, and every one of these is a cache lookup plus the two string rewrites uriToKey
        // does; none of it needs to hold the workers off their queue.
        ArrayList<Item> batch = null;
        for (Uri uri : uris) {
            if (uri == null || !metadata.needsBpmAnalysis(uri)) continue;
            final String key = MetadataExtractor.uriToKey(uri);
            if (!inFlight.add(key)) continue;          // already queued, or being measured now
            if (batch == null) batch = new ArrayList<>();
            batch.add(new Item(key, uri, generation));
        }
        if (batch == null) return;

        synchronized (queue) {
            queue.addAll(batch);
            runTotal += batch.size();
            startWorkersLocked();
        }
    }

    /**
     * Drops everything pending and stops everything running. Called when the tag cache is cleared —
     * the tracks being measured then belong to a library the user has just replaced.
     */
    void cancelAll() {
        synchronized (queue) {
            for (Item item : queue) inFlight.remove(item.key);
            queue.clear();
            runTotal = runDone;   // whatever is still running will finish and square this off
        }
        for (BpmJob job : running.values()) job.cancel();
    }

    /** True while there is anything queued or being measured. Drives the progress bar. */
    boolean isBusy() {
        synchronized (queue) {
            return liveWorkers > 0;
        }
    }

    /** How far through the current run, 0..1. Meaningless unless {@link #isBusy()}. */
    float progress() {
        synchronized (queue) {
            if (runTotal <= 0) return 0f;
            return Math.min(1f, runDone / (float) runTotal);
        }
    }

    /** Workers currently alive. Debug assertions and tests; not for control flow. */
    int activeWorkers() {
        synchronized (queue) {
            return liveWorkers;
        }
    }

    private void startWorkersLocked() {
        // One worker per queued item, up to the limit. They exit when the queue runs dry rather
        // than idling, so a library that is fully measured costs no threads at all.
        final int wanted = Math.min(workerLimit, queue.size());
        while (liveWorkers < wanted) {
            liveWorkers++;
            Thread t = new Thread(this::work, "bpm-scan");
            t.setDaemon(true);
            t.start();
        }
    }

    private void work() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try {
            for (;;) {
                final Item item;
                synchronized (queue) {
                    item = queue.pollFirst();
                    if (item == null) return;
                }
                measure(item);
            }
        } finally {
            synchronized (queue) {
                liveWorkers--;
                // A submit that arrived while this worker was on its way out would have seen the
                // limit already reached and started nothing. Take another look on the way down.
                startWorkersLocked();
                if (liveWorkers == 0 && queue.isEmpty()) {
                    runTotal = 0;
                    runDone = 0;
                }
            }
            notifyProgress();   // so the bar clears itself when the last worker goes
        }
    }

    private void measure(Item item) {
        final BpmJob job = new BpmJob();
        running.put(item.key, job);
        int result;
        try {
            result = engine.measure(item.uri, job);
        } catch (Throwable t) {
            // An uncaught throwable on this thread would kill the process, taking the playback
            // service with it. See the comment in App.java.
            result = BpmEngine.RESULT_UNAVAILABLE;
        } finally {
            running.remove(item.key);
            inFlight.remove(item.key);
        }

        if (result > 0) {
            metadata.recordMeasuredBpm(item.uri, result, item.generation);
        } else if (result == BpmEngine.RESULT_NO_TEMPO) {
            // Remember the failure, so a folder with a few beatless tracks in it does not re-analyse
            // them every single time it is opened.
            metadata.recordMeasuredBpm(item.uri, -1, item.generation);
        }
        // RESULT_CANCELLED and RESULT_UNAVAILABLE record nothing, so the track is tried again.

        synchronized (queue) {
            runDone++;
        }
        // On every outcome, not only the ones that produced a number: the listener repaints the
        // progress bar as well as the rows, and a folder of tracks with no beat in them would
        // otherwise leave the bar frozen at wherever the last success left it.
        notifyProgress();
    }

    private void notifyProgress() {
        if (listeners.isEmpty()) return;
        if (!notifyPending.compareAndSet(false, true)) return;
        dispatcher.postDelayed(() -> {
            notifyPending.set(false);
            for (Listener l : listeners) l.onBpmMeasured();
        }, NOTIFY_DEBOUNCE_MS);
    }
}
