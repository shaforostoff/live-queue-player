package com.shaforostoff.livequeueplayer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves M3U/M3U8 playlist lines to playable URIs, for both plain-file playlists and
 * SAF document-tree playlists. Pure I/O + string logic with no UI state; callers are
 * responsible for running the (potentially slow) resolution off the main thread.
 */
final class PlaylistResolver {

    private final ContentResolver contentResolver;
    private final StorageBrowser storageBrowser;
    private final MetadataExtractor metadataExtractor;

    PlaylistResolver(ContentResolver contentResolver, StorageBrowser storageBrowser,
                     MetadataExtractor metadataExtractor) {
        this.contentResolver = contentResolver;
        this.storageBrowser = storageBrowser;
        this.metadataExtractor = metadataExtractor;
    }

    /** Non-comment, non-empty playlist lines, trimmed and with a leading BOM stripped. */
    List<String> readLines(Uri playlistUri) {
        List<String> lines = new ArrayList<>();
        try (InputStream stream = contentResolver.openInputStream(playlistUri)) {
            if (stream == null) return lines;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && trimmed.charAt(0) == '\uFEFF') trimmed = trimmed.substring(1);
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    lines.add(trimmed);
                }
            }
        } catch (Exception ignored) {}
        return lines;
    }

    /**
     * True if a playlist line parses to an absolute URI we can actually open. Opaque URIs — a
     * scheme with no {@code //} authority, e.g. a Windows path "C:\..." that {@link Uri#parse}
     * reads as scheme "c" — have a null path, can never be played, and would crash File-based
     * title derivation when the queue is replayed on launch. Reject them so the caller falls back
     * to resolving the line as a relative/absolute file path instead.
     */
    private static boolean isUsableAbsoluteUri(Uri parsed) {
        return parsed.getScheme() != null && !parsed.isOpaque();
    }

    /**
     * Resolves one playlist line relative to the playlist's own location: absolute URIs pass
     * through, file-backed playlists resolve against the parent directory (with a
     * different-extension fallback), document-tree playlists resolve via SAF. Null if nothing
     * playable exists. {@code playlistFile} is null for document-tree playlists.
     */
    Uri resolveTargetUri(File playlistFile, Uri playlistUri, String pathValue) {
        Uri parsed = Uri.parse(pathValue);
        if (isUsableAbsoluteUri(parsed)) {
            return parsed;
        }

        if (playlistFile != null) {
            File playlistDir = playlistFile.getParentFile();
            File target = pathValue.startsWith("/")
                    ? new File(pathValue)
                    : new File(playlistDir, pathValue);
            if (target.exists() && target.isFile()) {
                return Uri.fromFile(target);
            }
            File fallback = StorageBrowser.findFileWithDifferentExtension(target);
            return fallback != null ? Uri.fromFile(fallback) : null;
        }

        return resolveDocumentTargetUri(playlistUri, pathValue);
    }

    private Uri resolveDocumentTargetUri(Uri playlistUri, String pathValue) {
        if (storageBrowser.getCurrentTreeUri() == null || playlistUri == null) {
            return null;
        }

        try {
            String playlistDocumentId = DocumentsContract.getDocumentId(playlistUri);
            int separator = playlistDocumentId.indexOf(':');
            if (separator < 0) {
                return null;
            }

            String volume = playlistDocumentId.substring(0, separator);
            String playlistPath = playlistDocumentId.substring(separator + 1).replace('\\', '/');
            int lastSlash = playlistPath.lastIndexOf('/');
            String baseDir = lastSlash >= 0 ? playlistPath.substring(0, lastSlash) : "";

            String normalizedInput = pathValue.replace('\\', '/');
            String combinedPath;
            if (normalizedInput.startsWith("/")) {
                combinedPath = normalizedInput.substring(1);
            } else {
                combinedPath = baseDir.isEmpty() ? normalizedInput : baseDir + "/" + normalizedInput;
            }
            String normalizedPath = normalizeRelativePath(combinedPath);
            if (normalizedPath == null || normalizedPath.length() == 0) {
                return null;
            }

            String targetDocumentId = volume + ":" + normalizedPath;
            Uri targetUri = DocumentsContract.buildDocumentUriUsingTree(storageBrowser.getCurrentTreeUri(), targetDocumentId);
            if (storageBrowser.documentExists(targetUri)) {
                return targetUri;
            }
            return storageBrowser.findDocumentWithDifferentExtension(volume, normalizedPath);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeRelativePath(String path) {
        String[] parts = path.split("/");
        ArrayList<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.length() == 0 || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else {
                stack.add(part);
            }
        }
        if (stack.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(stack.get(i));
        }
        return builder.toString();
    }

    /** Existence test for a document id (against the tag cache or a fetched sibling listing). */
    private interface DocIdExists { boolean test(String docId); }

    /**
     * Resolves {@code docId} to an existing document id: the id itself if present, otherwise the same
     * base name with a different audio extension; null if nothing exists. Shared by the playlist
     * tag-cache pass and the SAF sibling-listing pass.
     */
    private static String resolveExistingDocId(String docId, DocIdExists exists) {
        if (exists.test(docId)) return docId;
        int dot = docId.lastIndexOf('.');
        String base = dot >= 0 ? docId.substring(0, dot) : docId;
        String originalExt = dot >= 0 ? docId.substring(dot) : "";
        for (String ext : StorageBrowser.AUDIO_EXTENSIONS_NO_PLAYLIST) {
            if (ext.equals(originalExt)) continue;
            if (exists.test(base + ext)) return base + ext;
        }
        return null;
    }

    /**
     * Batch variant of {@link #resolveTargetUri} for document-tree playlists: one SAF children
     * query per distinct parent directory instead of one existence check per line, with an
     * in-memory tag-cache pass first. Result list is index-aligned with {@code pathValues};
     * unresolvable entries are null.
     */
    List<Uri> resolveDocumentUrisBatch(Uri playlistUri, List<String> pathValues, Uri treeUri) {
        int n = pathValues.size();
        List<Uri> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) result.add(null);

        String volume;
        String baseDir;
        try {
            String playlistDocumentId = DocumentsContract.getDocumentId(playlistUri);
            int separator = playlistDocumentId.indexOf(':');
            if (separator < 0) return result;
            volume = playlistDocumentId.substring(0, separator);
            String playlistPath = playlistDocumentId.substring(separator + 1).replace('\\', '/');
            int lastSlash = playlistPath.lastIndexOf('/');
            baseDir = lastSlash >= 0 ? playlistPath.substring(0, lastSlash) : "";
        } catch (Exception e) {
            return result;
        }

        // Pass 1: compute target document IDs — pure string ops, no I/O
        String[] targetDocIds = new String[n];
        for (int i = 0; i < n; i++) {
            String pathValue = pathValues.get(i);
            Uri parsed = Uri.parse(pathValue);
            if (isUsableAbsoluteUri(parsed)) {
                result.set(i, parsed);
                continue;
            }
            String normalizedInput = pathValue.replace('\\', '/');
            String combinedPath = normalizedInput.startsWith("/")
                    ? normalizedInput.substring(1)
                    : baseDir.isEmpty() ? normalizedInput : baseDir + "/" + normalizedInput;
            String normalizedPath = normalizeRelativePath(combinedPath);
            if (normalizedPath != null && !normalizedPath.isEmpty())
                targetDocIds[i] = volume + ":" + normalizedPath;
        }

        // Cache pass: when the library has been enumerated, the target's existence can be checked
        // against the in-memory tag cache (whose keys are every enumerated file's URI), avoiding a
        // SAF query per directory. Entries not found here fall through to the batch query below.
        for (int i = 0; i < n; i++) {
            if (result.get(i) != null || targetDocIds[i] == null) continue;
            String hit = resolveExistingDocId(targetDocIds[i],
                    d -> metadataExtractor.containsUri(DocumentsContract.buildDocumentUriUsingTree(treeUri, d)));
            if (hit != null) result.set(i, DocumentsContract.buildDocumentUriUsingTree(treeUri, hit));
        }

        // Collect unique parent directories for entries that still need resolution
        Set<String> parentDocIds = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (result.get(i) != null || targetDocIds[i] == null) continue;
            String docId = targetDocIds[i];
            int colon = docId.indexOf(':');
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            int slash = path.lastIndexOf('/');
            String parentPath = slash >= 0 ? path.substring(0, slash) : "";
            parentDocIds.add(volume + ":" + parentPath);
        }

        // Batch query: one ContentResolver query per distinct parent directory
        Map<String, Set<String>> dirContents = new HashMap<>();
        for (String parentDocId : parentDocIds) {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
            Set<String> children = new HashSet<>();
            try (Cursor cursor = contentResolver.query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                if (cursor != null) {
                    int col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    if (col >= 0) {
                        while (cursor.moveToNext())
                            children.add(cursor.getString(col));
                    }
                }
            } catch (Exception ignored) {}
            dirContents.put(parentDocId, children);
        }

        // Pass 2: match each remaining entry against its fetched sibling listing.
        for (int i = 0; i < n; i++) {
            if (result.get(i) != null || targetDocIds[i] == null) continue;
            String docId = targetDocIds[i];
            int colon = docId.indexOf(':');
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            int slash = path.lastIndexOf('/');
            String parentPath = slash >= 0 ? path.substring(0, slash) : "";
            Set<String> siblings = dirContents.getOrDefault(volume + ":" + parentPath, Collections.emptySet());
            String hit = resolveExistingDocId(docId, siblings::contains);
            if (hit != null) result.set(i, DocumentsContract.buildDocumentUriUsingTree(treeUri, hit));
        }

        return result;
    }

    /** Display name for a playlist line: the last path segment, or the line itself. */
    static String displayName(String playlistValue) {
        String normalized = playlistValue.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            return normalized.substring(slash + 1);
        }
        return playlistValue;
    }
}
