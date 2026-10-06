package com.shaforostoff.livequeueplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
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
 * current and the worker waits for the bridge to reconnect, then begins the same file again — the
 * host answers with how much it already holds, and only the rest is sent. Only {@link #cancel}
 * gives a file up; a connect with nothing to resume tells the host so ({@code file_abort} without
 * an id), letting it delete a leftover partial.
 */
final class BluetoothFileSender implements BluetoothQueueBridge.FileSink {

    static final class Job {
        final Uri uri;
        final String path;
        final String name;
        int generation; // the cancel generation it was queued in; a cancel orphans it

        Job(Uri uri, String path, String name) {
            this.uri = uri;
            this.path = path;
            this.name = name;
        }
    }

    interface Callback {
        /** UI thread. {@code index} is 1-based within the current run of {@code total} files. */
        void onProgress(int index, int total, String name, int percent);
        /** UI thread, repeatedly while the link is down with {@code name} still to finish. */
        void onWaitingForLink(int index, int total, String name);
        /** UI thread, once the queue has drained. Cancelled files are not counted. */
        void onFinished(int sent, int existing, int failed);
    }

    private enum Result { SENT, EXISTING, FAILED, CANCELLED, INTERRUPTED }

    private static final int CHUNK_BYTES = 8 * 1024;
    private static final long READY_TIMEOUT_MS = 30_000;
    // The host flushes and renames on file_end; a slow SD card can lag the last chunks a while.
    private static final long DONE_TIMEOUT_MS = 60_000;
    private static final long PROGRESS_INTERVAL_MS = 250;
    private static final long LINK_POLL_MS = 500;

    private final Context context;
    private final BluetoothFileLink link;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile Callback callback;

    private final LinkedBlockingDeque<Job> jobs = new LinkedBlockingDeque<>();
    private final LinkedBlockingQueue<JSONObject> replies = new LinkedBlockingQueue<>();
    // Guards the worker's lifecycle, and orders "begin a file" against onLinkUp's abort-all so a
    // reconnect can never kill a file the worker just began on the new link.
    private final Object lock = new Object();
    private Thread worker;
    private Job current; // begun, or waiting to be resumed; null between files
    private int runTotal;
    private volatile int generation;
    private volatile int linkEpoch;
    private volatile int currentId = -1;
    private int nextId = 1;

    BluetoothFileSender(Context context, BluetoothFileLink link) {
        this.context = context.getApplicationContext();
        this.link = link;
    }

    /** Attaches the current activity, or {@code null} to detach (progress is then dropped). */
    void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** Queues {@code more} behind whatever is already being sent. */
    void enqueue(List<Job> more) {
        if (more.isEmpty()) return;
        synchronized (lock) {
            for (Job job : more) job.generation = generation;
            jobs.addAll(more);
            runTotal += more.size();
            if (worker != null) return;
            worker = new Thread(this::drain, "bt-file-send");
            worker.start();
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
        }
    }

    @Override
    public void onLinkLost() {
        linkEpoch++;
    }

    private void drain() {
        int index = 0, sent = 0, existing = 0, failed = 0;
        while (true) {
            Job job;
            int total;
            synchronized (lock) {
                job = jobs.pollFirst();
                if (job == null) {
                    worker = null;
                    runTotal = 0;
                    break;
                }
                total = runTotal;
            }
            if (job.generation != generation) continue;
            final int at = ++index;
            Result result;
            while ((result = sendOne(job, at, total)) == Result.INTERRUPTED) {
                // Hold the job until the bridge reconnects (it retries on its own), then resume it.
                do {
                    report(cb -> cb.onWaitingForLink(at, total, job.name));
                    if (!sleep(LINK_POLL_MS)) break;
                } while (job.generation == generation && !link.isConnected());
            }
            synchronized (lock) {
                current = null;
                currentId = -1;
            }
            if (result == Result.SENT) sent++;
            else if (result == Result.EXISTING) existing++;
            else if (result == Result.FAILED) failed++;
        }
        int s = sent, e = existing, f = failed;
        report(cb -> cb.onFinished(s, e, f));
    }

    private Result sendOne(Job job, int index, int total) {
        int epoch = linkEpoch;
        int id = nextId++;
        long size = sizeOf(job.uri);
        try (InputStream in = context.getContentResolver().openInputStream(job.uri)) {
            if (in == null) return Result.FAILED;
            synchronized (lock) {
                if (job.generation != generation) return Result.CANCELLED;
                current = job;
                currentId = id;
                if (!link.send("file_begin", "id", id, "path", job.path, "file", job.name, "size", size)) {
                    return interruption(epoch);
                }
            }
            JSONObject ready = awaitReply("file_ready", id, job, epoch, READY_TIMEOUT_MS);
            if (ready == null) return outcomeOfNoReply(job, epoch);
            if (!ready.optBoolean("ok")) {
                return "exists".equals(ready.optString("reason")) ? Result.EXISTING : Result.FAILED;
            }

            long done = ready.optLong("offset", 0);
            if (done < 0 || (size >= 0 && done > size) || !skipFully(in, done)) {
                link.send("file_abort", "id", id);
                return Result.FAILED;
            }
            progress(index, total, job.name, done, size);
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
                    progress(index, total, job.name, done, size);
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

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private interface Report { void to(Callback cb); }

    private void report(Report report) {
        uiHandler.post(() -> {
            Callback cb = callback;
            if (cb != null) report.to(cb);
        });
    }

    private void progress(int index, int total, String name, long done, long size) {
        int percent = size > 0 ? (int) (done * 100 / size) : 0;
        report(cb -> cb.onProgress(index, total, name, percent));
    }

    private long sizeOf(Uri uri) {
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
