package com.shaforostoff.livequeueplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.StructStatVfs;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Host (REMOTE_RECEIVE) side of a Bluetooth file transfer: the client pushes a track this library
 * lacks, and it lands at the client's root-relative path under this device's selected root folder.
 *
 * <p>Per file the client sends {@code file_begin {id, path, file, size}}, which this answers with
 * {@code file_ready {id, ok, offset, reason}}; on ok come binary chunks carrying the bytes from
 * {@code offset} on, then {@code file_end {id}}, answered with {@code file_done {id, ok}}. Everything
 * runs on the bridge's read thread, so disk writes pace the sender. A file the client re-encoded
 * before sending lands under its new name, and {@code file_begin} also names the {@code requested}
 * path it stands in for: that is the path reported to {@link Callback#onFileReceived}. An existing file is never
 * overwritten. Only audio files are taken, never under a hidden name, and only while the volume
 * keeps {@link #minFreeAfter} free ({@code reason: "no_space"} otherwise).
 *
 * <p>Bytes go to a hidden {@code .<name>.<size>.part} beside the target — invisible to browsing,
 * the tag scan and request matching — renamed to the real name only once whole. When the link drops
 * mid-file the partial stays, and a later {@code file_begin} for the same path and size (after a
 * reconnect, or even from a later session: the partial is found on disk) resumes from its length.
 * It is deleted only once it is clear nothing will resume it: the client aborts it, the client
 * begins a different file, or the client connects with nothing to resume ({@code file_abort}
 * without an id). The partial's location is persisted so that cleanup survives a process restart.
 *
 * <p>Every SAF folder listing is a full child query, ~1 s for a big folder, and the read thread that
 * runs it also carries the client's remote commands. So the folders a transfer walks are listed
 * once and kept, with the names this receiver adds, until the client starts over or a few idle
 * minutes pass.
 */
final class BluetoothFileReceiver implements BluetoothQueueBridge.FileSink {

    interface Callback {
        /**
         * On the UI thread, once a file has landed whole (or was already there). {@code path} is
         * the root-relative path the client asked for: the original's, for a re-encoded file.
         */
        void onFileReceived(String name, String path, Uri uri);
    }

    private static final String PREFS = "bt_file_receiver";
    private static final String KEY_PARTIAL = "partial_uri";
    private static final long FOLDER_CACHE_IDLE_MS = 5 * 60_000;
    private static final int FOLDER_CACHE_MAX = 16;

    private final Context context;
    private final StorageBrowser storageBrowser;
    private final BluetoothFileLink link;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Free space a file must leave on its volume. Package-private so a test can demand more. */
    long minFreeAfter = 32L * 1024 * 1024;

    // UI thread only: the attached activity, and files that landed while none was (mid-rotation).
    private Callback callback;
    private final List<Object[]> undelivered = new ArrayList<>();

    // The file being written. Guarded by this.
    private int currentId = -1;
    private OutputStream out;
    private Uri partUri;
    private File partFile;   // non-null for file:// roots
    private Object parent;   // the target's folder: a DocFolder or a File
    private String targetName;
    private String targetPath;
    private String requestedPath; // what targetPath stands in for, as reported on landing
    private long expected;
    private long written;    // the partial's length, including any resumed prefix

    // The last file landed, so a begin that only repeats it (its file_done was lost on a dropped
    // link) is told "done" instead of queueing it a second time. Guarded by this.
    private String landedKey;
    private long landedSize;
    private boolean repeatsLanded; // the file being opened is that one

    // SAF folders walked, by root-relative path ("" is the root). Guarded by this.
    private Uri cachedRoot;
    private long cacheUsedAt;
    private final Map<String, DocFolder> folders = new LinkedHashMap<String, DocFolder>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DocFolder> eldest) {
            return size() > FOLDER_CACHE_MAX;
        }
    };

    /** A document folder and its children by composed display name, as last listed or written. */
    private static final class DocFolder {
        final Uri uri;
        final Map<String, Uri> children;

        DocFolder(Uri uri, Map<String, Uri> children) {
            this.uri = uri;
            this.children = children;
        }

        Uri child(String name) {
            return children.get(TextNormalizer.compose(name));
        }

        void put(String name, Uri uri) {
            children.put(TextNormalizer.compose(name), uri);
        }

        void remove(String name) {
            children.remove(TextNormalizer.compose(name));
        }
    }

    BluetoothFileReceiver(Context context, StorageBrowser storageBrowser, BluetoothFileLink link) {
        this.context = context.getApplicationContext();
        this.storageBrowser = storageBrowser;
        this.link = link;
    }

    /**
     * Attaches the current activity, or {@code null} to detach; held-back files are delivered now.
     * UI thread, and never waits on the read thread.
     */
    void setCallback(Callback callback) {
        this.callback = callback;
        if (callback == null) return;
        for (Object[] f : undelivered) callback.onFileReceived((String) f[0], (String) f[1], (Uri) f[2]);
        undelivered.clear();
    }

    @Override
    public synchronized void onFileMessage(String type, JSONObject obj) {
        int id = obj.optInt("id", -1);
        switch (type) {
            case "file_begin":
                begin(id, obj.optString("path", ""), obj.optString("file", ""), obj.optLong("size", -1),
                        obj.optString("requested", ""));
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
                    forgetFolders(); // a new session: the library may have changed since
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
    public void onLinkAbandoned() {
    }

    /** The link dropped, or the host is shutting down: stop writing, keep the partial to resume. */
    @Override
    public synchronized void onLinkLost() {
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

    private void begin(int id, String path, String file, long size, String requested) {
        onLinkLost(); // whatever was open is not what this begins
        requestedPath = requested.isEmpty() ? null : requested; // open() defaults it to the target
        String reason = open(path.isEmpty() ? file : path, size);
        if (reason == null) currentId = id;
        long offset = reason == null ? written : 0;
        link.send("file_ready", "id", id, "ok", reason == null, "offset", offset, "reason", reason);
    }

    /**
     * Opens the partial for writing, or returns why not: "exists" (also queues the existing file),
     * "done" (just landed), "bad_path", "not_audio", "no_folder", "no_space", "no_write".
     */
    private String open(String path, long size) {
        List<String> segments = relativeSegments(path);
        if (segments == null) {
            dropRecordedPartial(); // the client has moved on to another file
            return "bad_path";
        }
        targetName = segments.get(segments.size() - 1);
        targetPath = String.join("/", segments);
        if (requestedPath == null) requestedPath = targetPath;
        if (!isAudioName(targetName)) {
            dropRecordedPartial();
            return "not_audio";
        }
        repeatsLanded = TextNormalizer.compose(targetPath).equals(landedKey) && size == landedSize;
        landedKey = null;
        expected = size;
        written = 0;
        String partName = "." + targetName + "." + (size >= 0 ? Long.toString(size) : "unknown") + ".part";
        // One consistent copy: this runs on the Bluetooth read thread while the user may be navigating.
        StorageBrowser.Root root = storageBrowser.getRoot();
        try {
            return root.document != null
                    ? openDocument(root.document, segments, partName, size)
                    : openFile(root.folder, segments, partName, size);
        } catch (Exception e) {
            closeQuietly();
            deletePartial();
            forgetFolders(); // a folder may have gone from under the cache
            return "no_write";
        }
    }

    private String openDocument(Uri rootDoc, List<String> segments, String partName, long size) throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (!rootDoc.equals(cachedRoot) || now - cacheUsedAt > FOLDER_CACHE_IDLE_MS) forgetFolders();
        cachedRoot = rootDoc;
        cacheUsedAt = now;

        DocFolder dir = folders.get("");
        if (dir == null) {
            dir = list(rootDoc);
            if (dir == null) return "no_folder";
            folders.put("", dir);
        }
        StringBuilder rel = new StringBuilder();
        for (int i = 0; i < segments.size() - 1; i++) {
            if (rel.length() > 0) rel.append('/');
            rel.append(TextNormalizer.compose(segments.get(i)));
            dir = subfolder(dir, segments.get(i), rel.toString());
            if (dir == null) return "no_folder";
        }
        parent = dir;
        Uri existing = dir.child(targetName);
        if (existing != null) {
            if (repeatsLanded) return "done";
            dropRecordedPartial();
            deliver(targetName, requestedPath, existing);
            return "exists";
        }
        Uri part = dir.child(partName);
        dropRecordedPartialOtherThan(part);
        long have = part != null ? documentSize(part) : -1;
        ContentResolver resolver = context.getContentResolver();
        if (part != null && (size < 0 || have < 0 || have > size)) {
            // Unverifiable or longer than the file: start over.
            DocumentsContract.deleteDocument(resolver, part);
            dir.remove(partName);
            part = null;
        }
        if (part == null) {
            part = DocumentsContract.createDocument(resolver, dir.uri, "application/octet-stream", partName);
            if (part == null) return "no_write";
            dir.put(partName, part);
            have = 0;
        }
        partUri = part;
        record(part);
        written = have;
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(part, have > 0 ? "wa" : "w");
        if (pfd == null) return "no_write";
        if (!hasRoom(freeBytes(pfd.getFileDescriptor()), size - have)) {
            pfd.close();
            deletePartial(); // a full volume won't take it soon; the client ends its run
            return "no_space";
        }
        out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
        return null;
    }

    private String openFile(File rootFolder, List<String> segments, String partName, long size) throws Exception {
        if (rootFolder == null) return "no_folder";
        File dir = rootFolder;
        for (int i = 0; i < segments.size() - 1; i++) dir = child(dir, segments.get(i));
        File target = child(dir, targetName);
        if (target.exists()) {
            if (repeatsLanded) return "done";
            dropRecordedPartial();
            deliver(targetName, requestedPath, Uri.fromFile(target));
            return "exists";
        }
        if (!dir.isDirectory() && !dir.mkdirs()) return "no_folder";
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
        if (!hasRoom(dir.getUsableSpace(), size - have)) {
            deletePartial();
            return "no_space";
        }
        out = new FileOutputStream(part, have > 0);
        return null;
    }

    /**
     * {@code dir}'s child named {@code name} in whichever Unicode form it exists on disk ("Niño"
     * copied from a Mac is decomposed), or the name as given if none does.
     */
    private static File child(File dir, String name) {
        for (String variant : TextNormalizer.variants(name)) {
            File f = new File(dir, variant);
            if (f.exists()) return f;
        }
        return new File(dir, name);
    }

    /** The subfolder {@code name} of {@code dir}, listed or created; null if creating fails. */
    private DocFolder subfolder(DocFolder dir, String name, String relKey) throws Exception {
        DocFolder cached = folders.get(relKey);
        if (cached != null) return cached;
        Uri uri = dir.child(name);
        DocFolder sub;
        if (uri != null) {
            sub = list(uri);
            if (sub == null) return null;
        } else {
            uri = DocumentsContract.createDocument(context.getContentResolver(), dir.uri,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (uri == null) return null;
            dir.put(name, uri);
            storageBrowser.invalidateDocumentListing(dir.uri);
            sub = new DocFolder(uri, new HashMap<>()); // new, so empty: nothing to list
        }
        folders.put(relKey, sub);
        return sub;
    }

    /** One query for all of {@code dirDoc}'s children; null if it fails. */
    private DocFolder list(Uri dirDoc) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirDoc,
                DocumentsContract.getDocumentId(dirDoc));
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor c = context.getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (c == null) return null;
            DocFolder folder = new DocFolder(dirDoc, new HashMap<>());
            while (c.moveToNext()) {
                String name = c.getString(1);
                if (name != null) folder.put(name, DocumentsContract.buildDocumentUriUsingTree(dirDoc, c.getString(0)));
            }
            return folder;
        } catch (Exception e) {
            return null;
        }
    }

    private void forgetFolders() {
        folders.clear();
        cachedRoot = null;
    }

    private boolean hasRoom(long free, long need) {
        return free < 0 || need <= 0 || free - need >= minFreeAfter;
    }

    /** Free bytes on the volume holding {@code fd}, or -1 if it can't be told (not a local file). */
    private static long freeBytes(FileDescriptor fd) {
        try {
            StructStatVfs st = Os.fstatvfs(fd);
            return st.f_bavail * st.f_frsize;
        } catch (Throwable e) {
            return -1;
        }
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
                storageBrowser.invalidateDocumentListing(((DocFolder) parent).uri);
            }
            forgetRecord();
            partUri = null;
            partFile = null;
            currentId = -1;
            landedKey = TextNormalizer.compose(targetPath);
            landedSize = expected;
            deliver(targetName, requestedPath, landed);
        } else if (id == currentId) {
            dropActive(); // short, failed to write, or failed to rename: not worth resuming
        }
        link.send("file_done", "id", id, "ok", ok);
    }

    /** Renames the finished partial to the real name; null if that name got taken meanwhile. */
    private Uri promote() throws Exception {
        if (partFile != null) {
            File dir = (File) parent;
            // File.renameTo would silently replace an existing file.
            if (child(dir, targetName).exists()) return null;
            File target = new File(dir, targetName);
            if (!partFile.renameTo(target)) return null;
            return Uri.fromFile(target);
        }
        DocFolder dir = (DocFolder) parent;
        if (dir.child(targetName) != null) return null;
        Uri landed = DocumentsContract.renameDocument(context.getContentResolver(), partUri, targetName);
        if (landed != null) {
            dir.children.values().remove(partUri);
            dir.put(targetName, landed);
        }
        return landed;
    }

    private void deliver(String name, String path, Uri uri) {
        // The callback is read when this runs, not now: an activity relaunched in between gets it.
        uiHandler.post(() -> {
            Callback cb = callback;
            if (cb != null) cb.onFileReceived(name, path, uri);
            else undelivered.add(new Object[]{name, path, uri});
        });
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
            if (parent instanceof DocFolder) {
                DocFolder dir = (DocFolder) parent;
                dir.children.values().remove(partUri);
            }
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
        Uri uri = Uri.parse(recorded);
        delete(uri);
        forgetRecord();
        for (DocFolder dir : folders.values()) dir.children.values().remove(uri);
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

    static boolean isAudioName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : StorageBrowser.AUDIO_EXTENSIONS_NO_PLAYLIST) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * Splits a peer-supplied root-relative path into folder names plus the file name, or null when
     * it would step outside the root (".."), names a hidden file or folder (".nomedia" would hide a
     * folder from the media scanner), or names no file. Empty and "." segments are dropped.
     */
    static List<String> relativeSegments(String path) {
        if (path == null) return null;
        List<String> segments = new ArrayList<>();
        for (String s : path.replace('\\', '/').split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.startsWith(".")) return null;
            segments.add(s);
        }
        return segments.isEmpty() ? null : segments;
    }
}
