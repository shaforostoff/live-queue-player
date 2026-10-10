package com.shaforostoff.livequeueplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Client (REMOTE_SEND) side of a Bluetooth file transfer: pushes local tracks the host could not
 * match, one at a time, to land at the same root-relative path there. See
 * {@link BluetoothFileReceiver} for the protocol.
 *
 * <p>A single worker thread drains the job queue; the host's replies arrive on the bridge's read
 * thread and are handed over through {@link #replies}. When the link drops mid-file the job stays
 * current and the worker parks until the bridge reconnects (it is woken by {@link #onLinkUp}, so a
 * bridge that has stopped retrying costs nothing), then begins the same file again — the host
 * answers with how much it already holds, and only the rest is sent. Only {@link #cancel} gives a
 * file up; a connect with nothing to resume tells the host so ({@code file_abort} without an id),
 * letting it delete a leftover partial. A host that is out of space ends the run.
 *
 * <p>A job marked {@link Job#compress} is re-encoded to AAC first (see {@link AacTranscoder}) and
 * lands as an .m4a beside where the original would have; {@code file_begin} then also carries the
 * {@code requested} path, so the host places it where the original was asked for. The encoded copy
 * is made once and kept until the job is done, so a resume sends the same bytes.
 *
 * <p>A partial wake lock is held while bytes are moving, so a transfer keeps going after the screen
 * times out; it is let go while waiting for the link. {@link FileTransferService} keeps the
 * process from being frozen when the user switches away: it runs while a run is in flight, and
 * stops when the bridge gives up reconnecting (see {@link #onLinkAbandoned}) until the link is back.
 */
final class BluetoothFileSender implements BluetoothQueueBridge.FileSink {

    static final class Job {
        final Uri uri;
        final String path;
        final String name;
        boolean compress; // re-encode to AAC before sending
        int generation; // the cancel generation it was queued in; a cancel orphans it
        File encoded;   // worker only: the AAC copy, once made

        Job(Uri uri, String path, String name) {
            this.uri = uri;
            this.path = path;
            this.name = name;
        }
    }

    interface Callback {
        /**
         * UI thread. {@code index} is 1-based within the current run of {@code total} files; the
         * total grows when more are queued mid-run, and the line is then shown again with it.
         */
        void onProgress(int index, int total, String name, int percent);
        /** UI thread, while {@code name} is being re-encoded before it is sent. */
        void onCompressing(int index, int total, String name, int percent);
        /** UI thread, when the link goes down with {@code name} still to finish. */
        void onWaitingForLink(int index, int total, String name);
        /**
         * UI thread, once the queue has drained. Cancelled files are not counted; {@code hostFull}
         * means the host ran out of space and the files after it were not tried.
         */
        void onFinished(int sent, int existing, int failed, boolean hostFull);
    }

    private enum Result { SENT, EXISTING, FAILED, HOST_FULL, CANCELLED, INTERRUPTED }

    private static final int CHUNK_BYTES = 8 * 1024;
    private static final long READY_TIMEOUT_MS = 30_000;
    // The host flushes and renames on file_end; a slow SD card can lag the last chunks a while.
    private static final long DONE_TIMEOUT_MS = 60_000;
    private static final long PROGRESS_INTERVAL_MS = 250;
    private static final long LINK_POLL_MS = 500;
    // The wake lock is taken with a timeout, renewed while bytes move, so a stuck worker can't hold
    // the CPU up for good.
    private static final long WAKE_TIMEOUT_MS = 2 * 60_000;
    private static final long WAKE_RENEW_MS = 30_000;
    private static final String ENCODED_DIR = "bt-send";

    /** Re-encodes a job's track; a test stands in a fake. */
    interface Encoder {
        boolean encode(Context context, Uri uri, File out, AacTranscoder.Progress progress);
    }

    private final Context context;
    private final BluetoothFileLink link;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final PowerManager.WakeLock wakeLock;
    private final File encodedDir;
    Encoder encoder = AacTranscoder::transcode; // package-private for a test
    private long wakeRenewedAt;
    private Callback callback;  // UI thread
    private Report lastStatus;  // UI thread: the last progress/waiting line, replayed on attach
    private FileTransferService service; // UI thread: the foreground service while it is up
    private boolean wantForeground;      // UI thread

    private final LinkedBlockingDeque<Job> jobs = new LinkedBlockingDeque<>();
    private final LinkedBlockingQueue<JSONObject> replies = new LinkedBlockingQueue<>();
    // Guards the worker's lifecycle, and orders "begin a file" against onLinkUp's abort-all so a
    // reconnect can never kill a file the worker just began on the new link.
    private final Object lock = new Object();
    private Thread worker;
    private Job current; // taken off the queue (being encoded, begun, or waiting to be resumed); null between files
    // Files in the current run, counting those queued after it started. Read when a status line
    // is shown, not when its file began, so a file queued mid-run updates the line in flight.
    private volatile int runTotal;
    private int runGeneration = -1; // the cancel generation the current run counts in; under lock
    private volatile int generation;
    private volatile int linkEpoch;
    private volatile int currentId = -1;
    private int nextId = 1;
    private int runIndex; // the 1-based place in the run of the file in flight; under lock

    BluetoothFileSender(Context context, BluetoothFileLink link) {
        this.context = context.getApplicationContext();
        this.link = link;
        PowerManager pm = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveQueuePlayer:FileSend") : null;
        if (wakeLock != null) wakeLock.setReferenceCounted(false);
        // Copies left by a process that died mid-run: nothing will resume them.
        encodedDir = new File(this.context.getCacheDir(), ENCODED_DIR);
        File[] stale = encodedDir.listFiles();
        if (stale != null) for (File f : stale) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /**
     * Attaches the current activity, or {@code null} to detach. UI thread. A newly attached one is
     * shown the transfer's current status straight away, so a rotation doesn't blank it.
     */
    void setCallback(Callback callback) {
        this.callback = callback;
        if (callback != null && lastStatus != null) lastStatus.to(callback);
    }

    /**
     * The foreground service attaching itself (it then shows the current status), or {@code null}
     * as it goes. UI thread. Returns whether it is still wanted.
     */
    boolean attachService(FileTransferService service) {
        this.service = service;
        if (service != null && lastStatus != null) lastStatus.to(service);
        return wantForeground;
    }

    /** Starts or stops {@link FileTransferService}. Any thread; applied in order on the UI thread. */
    private void setForeground(boolean on) {
        uiHandler.post(() -> {
            wantForeground = on;
            if (on) {
                if (service == null) FileTransferService.start(context);
            } else if (service != null) {
                service.finish();
            }
        });
    }

    /** True if a file at {@code path} is queued or being sent. */
    boolean isPending(String path) {
        synchronized (lock) {
            if (current != null && current.generation == generation && current.path.equals(path)) return true;
            for (Job job : jobs) {
                if (job.generation == generation && job.path.equals(path)) return true;
            }
            return false;
        }
    }

    /** Queues {@code more} behind whatever is already being sent, skipping paths already pending. */
    void enqueue(List<Job> more) {
        synchronized (lock) {
            List<Job> fresh = new ArrayList<>(more.size());
            for (Job job : more) {
                if (isPending(job.path)) continue;
                boolean twice = false;
                for (Job f : fresh) twice |= f.path.equals(job.path);
                if (twice) continue;
                job.generation = generation;
                fresh.add(job);
            }
            if (fresh.isEmpty()) return;
            jobs.addAll(fresh);
            if (worker == null || runGeneration != generation) {
                // A new run, or the first files after a Stop: count from 1 again.
                runTotal = 0;
                runIndex = 0;
                runGeneration = generation;
            }
            runTotal += fresh.size();
            if (worker != null) {
                refreshStatus();
                return;
            }
            worker = new Thread(this::drain, "bt-file-send");
            worker.start();
            setForeground(true);
        }
    }

    /**
     * Gives up every queued file and the one in flight. The host deletes the partial when told —
     * now if connected, else on the next connect (see {@link #onLinkUp}).
     */
    void cancel() {
        synchronized (lock) {
            generation++;
            jobs.clear();
            int id = currentId;
            if (id > 0) link.send("file_abort", "id", id);
            lock.notifyAll(); // a worker parked for the link
        }
    }

    @Override
    public void onFileMessage(String type, JSONObject obj) {
        if ("file_ready".equals(type) || "file_done".equals(type)) {
            try {
                obj.put("__type", type);
            } catch (Exception ignored) {
            }
            // A stale reply (one that missed its timeout) is skipped by id in awaitReply.
            replies.offer(obj);
        }
    }

    @Override
    public void onFileChunk(byte[] frame) {
    }

    @Override
    public void onLinkUp() {
        synchronized (lock) {
            Job job = current;
            if (job == null || job.generation != generation) link.send("file_abort");
            lock.notifyAll(); // a worker parked for the link
            if (worker != null) setForeground(true); // let go of while the bridge had given up
        }
    }

    /**
     * The bridge has stopped trying to reconnect, and resumes only when the user is back. A parked
     * run has nothing to do until then, so it lets the foreground service go.
     */
    @Override
    public void onLinkAbandoned() {
        synchronized (lock) {
            if (worker != null && !link.isConnected()) setForeground(false);
        }
    }

    @Override
    public void onLinkLost() {
        linkEpoch++;
    }

    private void drain() {
        int sent = 0, existing = 0, failed = 0;
        boolean hostFull = false;
        while (true) {
            Job job;
            int at;
            synchronized (lock) {
                job = jobs.pollFirst();
                if (job == null) {
                    worker = null;
                    break;
                }
                if (job.generation != generation) continue;
                at = ++runIndex;
                current = job; // pending from here on, through its re-encoding
            }
            Result result;
            while ((result = sendAwake(job, at)) == Result.INTERRUPTED) {
                // Hold the job until the bridge reconnects, then resume it. The bridge may have
                // stopped retrying (it resumes when the user is back), so this parks rather than
                // polls: onLinkUp or cancel wakes it.
                report(cb -> cb.onWaitingForLink(at, runTotal, job.name));
                if (!awaitLink(job)) break;
            }
            synchronized (lock) {
                current = null;
                currentId = -1;
            }
            if (job.encoded != null) {
                //noinspection ResultOfMethodCallIgnored
                job.encoded.delete();
                job.encoded = null;
            }
            if (result == Result.SENT) sent++;
            else if (result == Result.EXISTING) existing++;
            else if (result == Result.FAILED) failed++;
            else if (result == Result.HOST_FULL) {
                // Every file after this one would fail the same way.
                hostFull = true;
                failed++;
                synchronized (lock) {
                    if (job.generation == generation) jobs.clear();
                }
            }
        }
        int s = sent, e = existing, f = failed;
        boolean full = hostFull;
        report(cb -> cb.onFinished(s, e, f, full), false);
        setForeground(false);
    }

    /** Waits until the link is up or the job is cancelled; false if interrupted. */
    private boolean awaitLink(Job job) {
        synchronized (lock) {
            while (job.generation == generation && !link.isConnected()) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@link #sendOne} with the CPU held awake for its duration. */
    private Result sendAwake(Job job, int index) {
        keepAwake();
        try {
            return sendOne(job, index);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
    }

    /** Takes or renews the wake lock; cheap to call per chunk. */
    private void keepAwake() {
        if (wakeLock == null) return;
        long now = SystemClock.elapsedRealtime();
        if (wakeLock.isHeld() && now - wakeRenewedAt < WAKE_RENEW_MS) return;
        wakeLock.acquire(WAKE_TIMEOUT_MS);
        wakeRenewedAt = now;
    }

    private Result sendOne(Job job, int index) {
        if (job.compress && job.encoded == null && !encode(job, index)) {
            if (job.generation != generation) return Result.CANCELLED;
            job.compress = false; // it can't be converted here: send it as it is
        }
        int epoch = linkEpoch;
        int id = nextId++;
        Uri source = job.encoded != null ? Uri.fromFile(job.encoded) : job.uri;
        String path = job.encoded != null ? m4aName(job.path) : job.path;
        String name = job.encoded != null ? m4aName(job.name) : job.name;
        long size = sizeOf(source);
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) return Result.FAILED;
            synchronized (lock) {
                if (job.generation != generation) return Result.CANCELLED;
                current = job;
                currentId = id;
                if (!link.send("file_begin", "id", id, "path", path, "file", name, "size", size,
                        "requested", path.equals(job.path) ? null : job.path)) {
                    return interruption(epoch);
                }
            }
            JSONObject ready = awaitReply("file_ready", id, job, epoch, READY_TIMEOUT_MS);
            if (ready == null) return outcomeOfNoReply(job, epoch);
            if (!ready.optBoolean("ok")) {
                switch (ready.optString("reason")) {
                    case "done":     return Result.SENT;      // it landed, but its file_done was lost
                    case "exists":   return Result.EXISTING;
                    case "no_space": return Result.HOST_FULL;
                    default:         return Result.FAILED;
                }
            }

            long done = ready.optLong("offset", 0);
            if (done < 0 || (size >= 0 && done > size) || !skipFully(in, done)) {
                link.send("file_abort", "id", id);
                return Result.FAILED;
            }
            progress(index, job.name, done, size);
            byte[] buf = new byte[CHUNK_BYTES];
            long lastProgress = SystemClock.elapsedRealtime();
            int n;
            while ((n = in.read(buf)) > 0) {
                if (job.generation != generation) return Result.CANCELLED; // cancel() told the host
                if (linkEpoch != epoch || !link.sendFileChunk(id, buf, n)) return interruption(epoch);
                done += n;
                long now = SystemClock.elapsedRealtime();
                if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                    lastProgress = now;
                    progress(index, job.name, done, size);
                    keepAwake();
                }
            }
            if (!link.send("file_end", "id", id)) return interruption(epoch);
            JSONObject doneReply = awaitReply("file_done", id, job, epoch, DONE_TIMEOUT_MS);
            if (doneReply == null) return outcomeOfNoReply(job, epoch);
            return doneReply.optBoolean("ok") ? Result.SENT : Result.FAILED;
        } catch (Exception e) {
            // Reading the local file failed: nothing a resume would fix.
            link.send("file_abort", "id", id);
            return Result.FAILED;
        }
    }

    /** Re-encodes {@code job} into {@link #encodedDir}; false if cancelled or it can't be done. */
    private boolean encode(Job job, int index) {
        //noinspection ResultOfMethodCallIgnored
        encodedDir.mkdirs();
        File out = new File(encodedDir, nextId + ".m4a");
        report(cb -> cb.onCompressing(index, runTotal, job.name, 0));
        boolean ok = encoder.encode(context, job.uri, out, percent -> {
            keepAwake();
            report(cb -> cb.onCompressing(index, runTotal, job.name, percent));
            return job.generation == generation;
        });
        if (ok && job.generation == generation && out.length() > 0) {
            job.encoded = out;
            return true;
        }
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        return false;
    }

    /** {@code path} with its file name's extension, if any, replaced by .m4a. */
    static String m4aName(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return (dot > slash + 1 ? path.substring(0, dot) : path) + ".m4a";
    }

    /** A send that failed on a dead link is resumable; one that failed on a live link is not. */
    private Result interruption(int epoch) {
        return linkEpoch != epoch || !link.isConnected() ? Result.INTERRUPTED : Result.FAILED;
    }

    private Result outcomeOfNoReply(Job job, int epoch) {
        if (job.generation != generation) return Result.CANCELLED;
        return interruption(epoch);
    }

    /** The reply of {@code type} to {@code id}, or null on timeout, cancel, or a dropped link. */
    private JSONObject awaitReply(String type, int id, Job job, int epoch, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (job.generation == generation && linkEpoch == epoch) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) return null;
            // Wake at least twice a second to notice a cancel or a dropped link.
            JSONObject reply = replies.poll(Math.min(left, LINK_POLL_MS), TimeUnit.MILLISECONDS);
            if (reply != null && type.equals(reply.optString("__type")) && reply.optInt("id", -1) == id) {
                return reply;
            }
        }
        return null;
    }

    private static boolean skipFully(InputStream in, long count) throws Exception {
        byte[] scratch = null;
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                // skip() may stop short or refuse outright; reading always makes progress.
                if (scratch == null) scratch = new byte[CHUNK_BYTES];
                int n = in.read(scratch, 0, (int) Math.min(scratch.length, count));
                if (n < 0) return false;
                skipped = n;
            }
            count -= skipped;
        }
        return true;
    }

    private interface Report { void to(Callback cb); }

    private void report(Report report) {
        report(report, true);
    }

    /** Shows the last status line again, so it picks up a total that grew. Any thread. */
    private void refreshStatus() {
        uiHandler.post(() -> {
            if (lastStatus == null) return;
            if (callback != null) lastStatus.to(callback);
            if (service != null) lastStatus.to(service);
        });
    }

    /** Posts {@code report} to the attached callback; a status line is also kept for replay. */
    private void report(Report report, boolean isStatus) {
        uiHandler.post(() -> {
            lastStatus = isStatus ? report : null;
            Callback cb = callback;
            if (cb != null) report.to(cb);
            if (service != null) report.to(service);
        });
    }

    private void progress(int index, String name, long done, long size) {
        int percent = size > 0 ? (int) (done * 100 / size) : 0;
        report(cb -> cb.onProgress(index, runTotal, name, percent));
    }

    private long sizeOf(Uri uri) {
        return sizeOf(context, uri);
    }

    /** The byte size of {@code uri}, or -1 if unknown. Any thread; it may query a provider. */
    static long sizeOf(Context context, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).length();
        }
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }
}
