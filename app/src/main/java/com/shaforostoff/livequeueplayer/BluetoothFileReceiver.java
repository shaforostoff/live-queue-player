package com.shaforostoff.livequeueplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Host (REMOTE_RECEIVE) side of a Bluetooth file transfer: the client pushes a track this library
 * lacks, and it lands at the client's root-relative path under this device's selected root folder.
 *
 * <p>Per file the client sends {@code file_begin {id, path, file, size}}, which this answers with
 * {@code file_ready {id, ok, offset, reason}}; on ok come binary chunks carrying the bytes from
 * {@code offset} on, then {@code file_end {id}}, answered with {@code file_done {id, ok}}. Everything
 * runs on the bridge's read thread, so disk writes pace the sender. An existing file is never
 * overwritten.
 *
 * <p>Bytes go to a hidden {@code .<name>.<size>.part} beside the target — invisible to browsing,
 * the tag scan and request matching — renamed to the real name only once whole. When the link drops
 * mid-file the partial stays, and a later {@code file_begin} for the same path and size (after a
 * reconnect, or even from a later session: the partial is found on disk) resumes from its length.
 * It is deleted only once it is clear nothing will resume it: the client aborts it, the client
 * begins a different file, or the client connects with nothing to resume ({@code file_abort}
 * without an id). The partial's location is persisted so that cleanup survives a process restart.
 */
final class BluetoothFileReceiver implements BluetoothQueueBridge.FileSink {

    interface Callback {
        /** On the UI thread, once a file has landed whole (or was already there). */
        void onFileReceived(String name, Uri uri);
    }

    private static final String PREFS = "bt_file_receiver";
    private static final String KEY_PARTIAL = "partial_uri";

    private final Context context;
    private final StorageBrowser storageBrowser;
    private final BluetoothFileLink link;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private Callback callback;
    // Files that landed while no activity was attached (mid-rotation); handed over on attach.
    private final List<Object[]> undelivered = new ArrayList<>();

    // The file being written. Guarded by this.
    private int currentId = -1;
    private OutputStream out;
    private Uri partUri;
    private File partFile;   // non-null for file:// roots
    private Object parent;   // the target's folder: a document Uri or a File
    private String targetName;
    private long expected;
    private long written;    // the partial's length, including any resumed prefix

    BluetoothFileReceiver(Context context, StorageBrowser storageBrowser, BluetoothFileLink link) {
        this.context = context.getApplicationContext();
        this.storageBrowser = storageBrowser;
        this.link = link;
    }

    /** Attaches the current activity, or {@code null} to detach; held-back files are delivered now. */
    synchronized void setCallback(Callback callback) {
        this.callback = callback;
        if (callback == null) return;
        for (Object[] f : undelivered) deliver((String) f[0], (Uri) f[1]);
        undelivered.clear();
    }

    @Override
    public synchronized void onFileMessage(String type, JSONObject obj) {
        int id = obj.optInt("id", -1);
        switch (type) {
            case "file_begin":
                begin(id, obj.optString("path", ""), obj.optString("file", ""), obj.optLong("size", -1));
                break;
            case "file_end":
                end(id);
                break;
            case "file_abort":
                // With an id: that file is cancelled. Without: the client has nothing left to
                // resume, so whatever partial is lying around is dead.
                if (!obj.has("id")) {
                    dropActive();
                    dropRecordedPartial();
                } else if (id == currentId) {
                    dropActive();
                }
                break;
        }
    }

    @Override
    public synchronized void onFileChunk(byte[] frame) {
        if (out == null || BluetoothQueueBridge.fileChunkId(frame) != currentId) return;
        int len = frame.length - BluetoothQueueBridge.FILE_CHUNK_HEADER;
        try {
            out.write(frame, BluetoothQueueBridge.FILE_CHUNK_HEADER, len);
            written += len;
        } catch (Exception e) {
            // A failed write is not a dropped link: the partial is suspect. Keep currentId so the
            // rest of this file's chunks are dropped; file_end then reports failure.
            closeQuietly();
            deletePartial();
        }
    }

    @Override
    public void onLinkUp() {
    }

    @Override
    public synchronized void onLinkLost() {
        suspend();
    }

    /**
     * Stops writing but keeps the partial for a resume — the link dropped, or the hosting activity
     * is finishing (a later session can still pick it up).
     */
    synchronized void suspend() {
        if (out != null) {
            try {
                out.close();
                out = null;
            } catch (Exception e) {
                out = null;
                deletePartial(); // its length can't be trusted
            }
        }
        currentId = -1;
        partUri = null;
        partFile = null;
    }

    private void begin(int id, String path, String file, long size) {
        suspend();
        String reason = open(path.isEmpty() ? file : path, size);
        if (reason == null) currentId = id;
        long offset = reason == null ? written : 0;
        link.send("file_ready", "id", id, "ok", reason == null, "offset", offset, "reason", reason);
    }

    /** Opens the partial for writing, or returns why not ("exists" also queues the existing file). */
    private String open(String path, long size) {
        List<String> segments = relativeSegments(path);
        if (segments == null) {
            dropRecordedPartial(); // the client has moved on to another file
            return "bad_path";
        }
        targetName = segments.get(segments.size() - 1);
        expected = size;
        written = 0;
        String partName = "." + targetName + "." + (size >= 0 ? Long.toString(size) : "unknown") + ".part";
        // One consistent copy: this runs on the Bluetooth read thread while the user may be navigating.
        StorageBrowser.Root root = storageBrowser.getRoot();
        try {
            if (root.document != null) {
                Uri dir = root.document;
                for (int i = 0; i < segments.size() - 1 && dir != null; i++) {
                    dir = documentFolder(dir, segments.get(i));
                }
                if (dir == null) return "no_folder";
                parent = dir;
                Uri existing = storageBrowser.findDocumentChildByName(dir, targetName);
                if (existing != null) {
                    dropRecordedPartial();
                    deliver(targetName, existing);
                    return "exists";
                }
                Uri part = storageBrowser.findDocumentChildByName(dir, partName);
                dropRecordedPartialOtherThan(part);
                long have = part != null ? documentSize(part) : -1;
                ContentResolver resolver = context.getContentResolver();
                if (part != null && (size < 0 || have < 0 || have > size)) {
                    // Unverifiable or longer than the file: start over.
                    DocumentsContract.deleteDocument(resolver, part);
                    part = null;
                }
                if (part == null) {
                    part = DocumentsContract.createDocument(resolver, dir, "application/octet-stream", partName);
                    if (part == null) return "no_write";
                    have = 0;
                }
                partUri = part;
                record(part);
                written = have;
                out = resolver.openOutputStream(part, have > 0 ? "wa" : "w");
            } else {
                if (root.folder == null) return "no_folder";
                File target = new File(root.folder, String.join("/", segments));
                File dir = target.getParentFile();
                if (target.exists()) {
                    dropRecordedPartial();
                    deliver(targetName, Uri.fromFile(target));
                    return "exists";
                }
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return "no_folder";
                parent = dir;
                File part = new File(dir, partName);
                dropRecordedPartialOtherThan(part.exists() ? Uri.fromFile(part) : null);
                long have = part.isFile() ? part.length() : 0;
                if (have > 0 && (size < 0 || have > size)) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    have = 0;
                }
                partFile = part;
                partUri = Uri.fromFile(part);
                record(partUri);
                written = have;
                out = new FileOutputStream(part, have > 0);
            }
            return out != null ? null : "no_write";
        } catch (Exception e) {
            closeQuietly();
            deletePartial();
            return "no_write";
        }
    }

    /** The subfolder {@code name} of {@code parent}, created if missing; null if that fails. */
    private Uri documentFolder(Uri parent, String name) throws Exception {
        Uri child = storageBrowser.findDocumentChildByName(parent, name);
        if (child != null) return child;
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created != null) storageBrowser.invalidateDocumentListing(parent);
        return created;
    }

    private void end(int id) {
        boolean ok = id == currentId && out != null && (expected < 0 || written == expected);
        Uri landed = null;
        if (ok) {
            try {
                out.close();
                out = null;
                landed = promote();
            } catch (Exception e) {
                landed = null;
            }
            ok = landed != null;
        }
        if (ok) {
            if (partFile != null) {
                MediaScannerConnection.scanFile(context, new String[]{landed.getPath()}, null, null);
            } else {
                storageBrowser.invalidateDocumentListing((Uri) parent);
            }
            forgetRecord();
            partUri = null;
            partFile = null;
            currentId = -1;
            deliver(targetName, landed);
        } else if (id == currentId) {
            dropActive(); // short, failed to write, or failed to rename: not worth resuming
        }
        link.send("file_done", "id", id, "ok", ok);
    }

    /** Renames the finished partial to the real name; null if that name got taken meanwhile. */
    private Uri promote() throws Exception {
        if (partFile != null) {
            File target = new File((File) parent, targetName);
            // File.renameTo would silently replace an existing file.
            if (target.exists() || !partFile.renameTo(target)) return null;
            return Uri.fromFile(target);
        }
        if (storageBrowser.findDocumentChildByName((Uri) parent, targetName) != null) return null;
        return DocumentsContract.renameDocument(context.getContentResolver(), partUri, targetName);
    }

    private void deliver(String name, Uri uri) {
        if (callback == null) {
            undelivered.add(new Object[]{name, uri});
            return;
        }
        Callback cb = callback;
        uiHandler.post(() -> cb.onFileReceived(name, uri));
    }

    /** Stops and deletes the file being written. */
    private void dropActive() {
        closeQuietly();
        deletePartial();
        currentId = -1;
    }

    private void closeQuietly() {
        if (out == null) return;
        try {
            out.close();
        } catch (Exception ignored) {
        }
        out = null;
    }

    private void deletePartial() {
        if (partUri != null) {
            delete(partUri);
            forgetRecord();
        }
        partUri = null;
        partFile = null;
    }

    // -- the persisted partial ----------------------------------------------

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void record(Uri part) {
        prefs().edit().putString(KEY_PARTIAL, part.toString()).apply();
    }

    private void forgetRecord() {
        prefs().edit().remove(KEY_PARTIAL).apply();
    }

    private void dropRecordedPartial() {
        dropRecordedPartialOtherThan(null);
    }

    /** Deletes the recorded partial unless it is {@code keep} (the one about to be resumed). */
    private void dropRecordedPartialOtherThan(Uri keep) {
        String recorded = prefs().getString(KEY_PARTIAL, null);
        if (recorded == null || (keep != null && recorded.equals(keep.toString()))) return;
        delete(Uri.parse(recorded));
        forgetRecord();
    }

    private void delete(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                //noinspection ResultOfMethodCallIgnored
                new File(uri.getPath()).delete();
            } else {
                DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            }
        } catch (Exception ignored) {
        }
    }

    private long documentSize(Uri doc) {
        try (Cursor c = context.getContentResolver().query(doc,
                new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * Splits a peer-supplied root-relative path into folder names plus the file name, or null when
     * it would step outside the root ("..") or names no file. Empty and "." segments are dropped.
     */
    static List<String> relativeSegments(String path) {
        if (path == null) return null;
        List<String> segments = new ArrayList<>();
        for (String s : path.replace('\\', '/').split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) return null;
            segments.add(s);
        }
        return segments.isEmpty() ? null : segments;
    }
}
