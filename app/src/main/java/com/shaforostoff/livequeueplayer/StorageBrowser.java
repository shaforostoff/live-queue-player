package com.shaforostoff.livequeueplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Owns the user's current browsing location and all filesystem / SAF DocumentTree I/O.
 *
 * <p>Design: this class is the "where am I browsing / how do I read files" layer. Its navigation
 * methods mutate the owned location state and <b>return</b> the raw {@link FileBrowserQueueActivity.FileEntry}
 * listing; they never touch the UI. The Activity keeps the thin UI tail (sort / filter / scroll /
 * preview reset / playback stop) and reads location through the getters here. Consumers that only
 * need to know the current location (queue save, Bluetooth remote send/receive, recursive tag scan)
 * read it via the zero-copy getters rather than holding the fields themselves.
 */
final class StorageBrowser {

    private static final String BROWSER_PREFS = "browser_prefs";
    private static final String PREF_LAST_TREE_URI = "last_tree_uri";
    private static final String MUSIC_DIRECTORY_NAME = "Music";

    static final String[] AUDIO_EXTENSIONS_NO_PLAYLIST = {
            ".m4a", ".mp3", ".mp4", ".aac", ".ogg", ".flac", ".aiff", ".aif",
            ".wav", ".opus", ".wma", ".3gp"
    };

    private final Context context;

    // -- owned location state ------------------------------------------------
    private boolean browsingDocumentTree;
    private Uri currentTreeUri;
    private final ArrayList<Uri> documentUriStack = new ArrayList<>();
    private File currentFileDirectory;
    private File currentFileRootDirectory;

    StorageBrowser(Context context) {
        this.context = context;
    }

    private ContentResolver resolver() {
        return context.getContentResolver();
    }

    // -- location getters ----------------------------------------------------

    boolean isBrowsingDocumentTree() { return browsingDocumentTree; }
    Uri getCurrentTreeUri() { return currentTreeUri; }
    boolean isDocumentStackEmpty() { return documentUriStack.isEmpty(); }
    boolean hasDocumentLocation() { return currentTreeUri != null && !documentUriStack.isEmpty(); }
    Uri getDocumentRootUri() { return documentUriStack.isEmpty() ? null : documentUriStack.get(0); }
    Uri getCurrentDocumentUri() {
        return documentUriStack.isEmpty() ? null : documentUriStack.get(documentUriStack.size() - 1);
    }
    boolean canPopDocument() { return documentUriStack.size() > 1; }
    File getCurrentFileDirectory() { return currentFileDirectory; }
    File getCurrentFileRootDirectory() { return currentFileRootDirectory; }

    /** True when any folder (file-based or document-tree) is currently open. */
    boolean hasCurrentFolder() {
        if (browsingDocumentTree) return hasDocumentLocation();
        return currentFileDirectory != null;
    }

    boolean canNavigateUpInFiles() {
        if (currentFileDirectory == null || currentFileRootDirectory == null) return false;
        return !sameFileLocation(currentFileDirectory, currentFileRootDirectory);
    }

    /**
     * Save-dialog destination candidates: the current document folder first, walking up to the tree
     * root. Returns a fresh reversed copy of the stack (the same list the save dialog used to build
     * inline), so the internal stack stays encapsulated without adding an extra allocation.
     */
    List<Uri> getDocumentAncestry() {
        List<Uri> out = new ArrayList<>(documentUriStack.size());
        for (int i = documentUriStack.size() - 1; i >= 0; i--) {
            out.add(documentUriStack.get(i));
        }
        return out;
    }

    void clearBrowsingState() {
        browsingDocumentTree = false;
        currentTreeUri = null;
        documentUriStack.clear();
        currentFileDirectory = null;
        currentFileRootDirectory = null;
    }

    // -- file (java.io.File) navigation --------------------------------------

    /**
     * Switches to file-based browsing of {@code dir} and returns its listing (directories first,
     * playlists second, then audio files, all alphabetical). Mutates location state; performs no UI.
     */
    List<FileBrowserQueueActivity.FileEntry> listFolder(File dir) {
        browsingDocumentTree = false;
        currentFileDirectory = dir;
        if (currentFileRootDirectory == null) {
            currentFileRootDirectory = dir;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory())
                return a.isDirectory() ? -1 : 1;
            boolean aPlaylist = FileBrowserQueueActivity.isPlaylistFile(a.getName());
            boolean bPlaylist = FileBrowserQueueActivity.isPlaylistFile(b.getName());
            if (aPlaylist != bPlaylist)
                return aPlaylist ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        // Upper bound: every file becomes at most one entry (hidden / non-audio are skipped).
        List<FileBrowserQueueActivity.FileEntry> entries = new ArrayList<>(files.length);
        for (File f : files) {
            if (f.getName().startsWith(".")) continue; // skip hidden
            if (f.isDirectory()) {
                entries.add(new FileBrowserQueueActivity.FileEntry(f, f.getName(), true));
            } else if (FileBrowserQueueActivity.isAudioFile(f.getName())) {
                entries.add(new FileBrowserQueueActivity.FileEntry(f, f.getName(), false));
            }
        }
        return entries;
    }

    // -- SAF document-tree navigation ----------------------------------------

    /** Sets up document-tree state rooted at {@code treeUri}. Returns false if it could not open. */
    boolean openDocumentTree(Uri treeUri) {
        if (treeUri == null) {
            return false;
        }
        try {
            Uri rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            clearBrowsingState();
            currentTreeUri = treeUri;
            browsingDocumentTree = true;
            documentUriStack.add(rootDocumentUri);
            return true;
        } catch (Exception ignored) {
            clearBrowsingState();
            return false;
        }
    }

    void pushDocument(Uri documentUri) {
        documentUriStack.add(documentUri);
    }

    void popDocument() {
        if (!documentUriStack.isEmpty()) {
            documentUriStack.remove(documentUriStack.size() - 1);
        }
    }

    /**
     * Reads the children of the current document folder. Caller must ensure {@link #hasDocumentLocation()}
     * first. Returns the listing (possibly empty) or {@code null} if the query threw.
     */
    List<FileBrowserQueueActivity.FileEntry> readCurrentDocumentDirectory() {
        List<FileBrowserQueueActivity.FileEntry> entries = new ArrayList<>();
        Uri currentDocumentUri = documentUriStack.get(documentUriStack.size() - 1);
        String documentId = DocumentsContract.getDocumentId(currentDocumentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(currentTreeUri, documentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = resolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return entries;
            }
            while (cursor.moveToNext()) {
                String childDocumentId = cursor.getString(0);
                String childName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (childName == null || childName.startsWith(".")) {
                    continue;
                }
                boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
                if (!isDirectory && !isAudioDocument(childName, mimeType)) {
                    continue;
                }
                Uri childDocumentUri = DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, childDocumentId);
                entries.add(new FileBrowserQueueActivity.FileEntry(childDocumentUri, childName, isDirectory));
            }
            return entries;
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isAudioDocument(String name, String mimeType) {
        if (mimeType != null) {
            if (mimeType.startsWith("audio/")) {
                return true;
            }
            if ("application/vnd.apple.mpegurl".equals(mimeType)
                    || "audio/x-mpegurl".equals(mimeType)
                    || "application/x-mpegurl".equals(mimeType)) {
                return true;
            }
        }
        return FileBrowserQueueActivity.isAudioFile(name);
    }

    // -- recursive audio enumeration (used by remote send/receive + tag scan) -

    List<Uri> collectAllAudioUrisFromDocumentTree(Uri rootDocUri, Uri treeUri) {
        List<Uri> result = new ArrayList<>();
        String rootDocId;
        try {
            rootDocId = DocumentsContract.getDocumentId(rootDocUri);
        } catch (Exception e) {
            return result;
        }
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        ArrayList<String> stack = new ArrayList<>();
        stack.add(rootDocId);
        while (!stack.isEmpty()) {
            String dirDocId = stack.remove(stack.size() - 1);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId);
            try (Cursor cursor = resolver().query(childrenUri, projection, null, null, null)) {
                if (cursor == null) continue;
                while (cursor.moveToNext()) {
                    String childDocId = cursor.getString(0);
                    String childName = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    if (childDocId == null || childName == null) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        stack.add(childDocId);
                    } else if (isAudioDocument(childName, mimeType)) {
                        result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    List<Uri> collectAllAudioUrisFromFileDirectory(File root) {
        List<Uri> result = new ArrayList<>();
        ArrayList<File> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            File dir = stack.remove(stack.size() - 1);
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child == null) continue;
                if (child.isDirectory()) stack.add(child);
                else if (FileBrowserQueueActivity.isAudioFile(child.getName())) result.add(Uri.fromFile(child));
            }
        }
        return result;
    }

    // -- direct path / sibling resolution ------------------------------------

    /** Resolves a root-relative path directly against a file-based root folder, or null if absent. */
    Uri resolveDirectFilePath(File root, String relPath) {
        if (root == null) return null;
        File target = new File(root, relPath);
        return target.isFile() ? Uri.fromFile(target) : null;
    }

    /** Resolves a root-relative path directly against a SAF document-tree root, or null if absent. */
    Uri resolveDirectDocumentPath(Uri rootDocUri, String relPath) {
        if (rootDocUri == null || currentTreeUri == null) return null;
        try {
            String rootDocId = DocumentsContract.getDocumentId(rootDocUri);
            if (rootDocId == null) return null;
            String sep = (rootDocId.endsWith(":") || rootDocId.endsWith("/")) ? "" : "/";
            String targetDocId = rootDocId + sep + relPath;
            Uri target = DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, targetDocId);
            try (Cursor cursor = resolver().query(
                    target,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                 DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()
                        && !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1))) {
                    return target;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    Uri findDocumentChildByName(Uri parentDocumentUri, String childName) {
        if (currentTreeUri == null) {
            return null;
        }
        try {
            String parentDocumentId = DocumentsContract.getDocumentId(parentDocumentUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(currentTreeUri, parentDocumentId);
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            };
            try (Cursor cursor = resolver().query(childrenUri, projection, null, null, null)) {
                if (cursor == null) {
                    return null;
                }
                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(0);
                    String displayName = cursor.getString(1);
                    if (displayName != null && displayName.equals(childName)) {
                        return DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, documentId);
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    static File findFileWithDifferentExtension(File file) {
        File dir = file.getParentFile();
        if (dir == null) return null;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String originalExt = dot >= 0 ? name.substring(dot) : "";
        for (String ext : AUDIO_EXTENSIONS_NO_PLAYLIST) {
            if (ext.equals(originalExt)) continue;
            File candidate = new File(dir, base + ext);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    Uri findDocumentWithDifferentExtension(String volume, String normalizedPath) {
        int dot = normalizedPath.lastIndexOf('.');
        String basePath = dot >= 0 ? normalizedPath.substring(0, dot) : normalizedPath;
        String originalExt = dot >= 0 ? normalizedPath.substring(dot) : "";
        for (String ext : AUDIO_EXTENSIONS_NO_PLAYLIST) {
            if (ext.equals(originalExt)) continue;
            String candidateDocId = volume + ":" + basePath + ext;
            Uri candidateUri = DocumentsContract.buildDocumentUriUsingTree(currentTreeUri, candidateDocId);
            if (documentExists(candidateUri)) {
                return candidateUri;
            }
        }
        return null;
    }

    boolean documentExists(Uri documentUri) {
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID};
        try (Cursor cursor = resolver().query(documentUri, projection, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean sameFileLocation(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Exception ignored) {
            return left.getAbsolutePath().equals(right.getAbsolutePath());
        }
    }

    // -- persistence / device pickers ----------------------------------------

    Uri getRememberedTreeUri() {
        SharedPreferences prefs = context.getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE);
        String uriString = prefs.getString(PREF_LAST_TREE_URI, null);
        if (uriString == null || uriString.isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(uriString);
        } catch (Exception ignored) {
            return null;
        }
    }

    void rememberLastTreeUri(Uri treeUri) {
        if (treeUri == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_LAST_TREE_URI, treeUri.toString()).apply();
    }

    boolean hasReadPermissionForUri(Uri treeUri) {
        if (treeUri == null) {
            return false;
        }
        for (UriPermission permission : resolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && treeUri.equals(permission.getUri())) {
                return true;
            }
        }
        return false;
    }

    Uri findSdCardDocumentUri() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        StorageManager sm = context.getSystemService(StorageManager.class);
        if (sm == null) return null;
        for (StorageVolume vol : sm.getStorageVolumes()) {
            if (!vol.isRemovable()) continue;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    android.content.Intent volIntent = vol.createOpenDocumentTreeIntent();
                    return volIntent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI);
                } catch (Exception ignored) {}
            } else {
                String uuid = vol.getUuid();
                if (uuid != null) {
                    return Uri.parse("content://com.android.externalstorage.documents/tree/"
                            + Uri.encode(uuid + ":"));
                }
            }
        }
        return null;
    }

    static File getMusicDirectoryCompat() {
        return Environment.getExternalStoragePublicDirectory(MUSIC_DIRECTORY_NAME);
    }
}
