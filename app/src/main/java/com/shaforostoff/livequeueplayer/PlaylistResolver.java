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
            // A playlist written on macOS spells accents decomposed ("n" + combining tilde) where
            // the file on this device is precomposed (or the reverse), and File.exists() is a
            // byte-exact lookup — so try the line as written, then its other Unicode form.
            for (String variant : TextNormalizer.variants(pathValue)) {
                File candidate = playlistRelativeFile(playlistDir, variant);
                if (candidate.exists() && candidate.isFile()) {
                    return Uri.fromFile(candidate);
                }
            }
            // findFileWithDifferentExtension probes both forms itself.
            File fallback = StorageBrowser.findFileWithDifferentExtension(
                    playlistRelativeFile(playlistDir, pathValue));
            return fallback != null ? Uri.fromFile(fallback) : null;
        }

        return resolveDocumentTargetUri(playlistUri, pathValue);
    }

    /** A playlist line as a File: absolute if it starts with '/', otherwise relative to the playlist. */
    private static File playlistRelativeFile(File playlistDir, String pathValue) {
        return pathValue.startsWith("/") ? new File(pathValue) : new File(playlistDir, pathValue);
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

            // Both Unicode forms of the path, for the same reason as the file branch above.
            for (String variant : TextNormalizer.variants(normalizedPath)) {
                Uri targetUri = DocumentsContract.buildDocumentUriUsingTree(
                        storageBrowser.getCurrentTreeUri(), volume + ":" + variant);
                if (storageBrowser.documentExists(targetUri)) {
                    return targetUri;
                }
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

    /**
     * Looks a candidate document id up (against the tag cache or a fetched sibling listing) and
     * returns the id that actually exists — which may spell accents in the other Unicode form than
     * the candidate did, so the caller must use the returned id, not the one it passed in — or null.
     */
    private interface DocIdLookup { String resolve(String docId); }

    /**
     * Resolves {@code docId} to an existing document id: the id itself if present, otherwise the same
     * base name with a different audio extension; null if nothing exists. Shared by the playlist
     * tag-cache pass and the SAF sibling-listing pass.
     */
    private static String resolveExistingDocId(String docId, DocIdLookup lookup) {
        String hit = lookup.resolve(docId);
        if (hit != null) return hit;
        int dot = docId.lastIndexOf('.');
        String base = dot >= 0 ? docId.substring(0, dot) : docId;
        String originalExt = dot >= 0 ? docId.substring(dot) : "";
        for (String ext : StorageBrowser.AUDIO_EXTENSIONS_NO_PLAYLIST) {
            if (ext.equals(originalExt)) continue;
            hit = lookup.resolve(base + ext);
            if (hit != null) return hit;
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
            String hit = resolveExistingDocId(targetDocIds[i], d -> {
                // The cache is keyed by the URI the provider handed us, so a line whose accents are
                // spelled the other way round only hits on its alternate form.
                for (String variant : TextNormalizer.variants(d)) {
                    if (metadataExtractor.containsUri(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, variant))) {
                        return variant;
                    }
                }
                return null;
            });
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

        // Batch query: one ContentResolver query per distinct parent directory. Each listing is
        // indexed by the composed form of the child's document id, so a playlist line and the
        // provider's own id match even when they spell the accents differently; the values are the
        // provider's ids, which is what has to go into the URI we hand back.
        Map<String, Map<String, String>> dirContents = new HashMap<>();
        for (String parentDocId : parentDocIds) {
            Map<String, String> children = Collections.emptyMap();
            // The parent path came out of the playlist too, so it needs the same treatment.
            for (String variant : TextNormalizer.variants(parentDocId)) {
                children = queryChildDocumentIds(treeUri, variant);
                if (!children.isEmpty()) break;
            }
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
            Map<String, String> siblings =
                    dirContents.getOrDefault(volume + ":" + parentPath, Collections.emptyMap());
            String hit = resolveExistingDocId(docId, d -> siblings.get(TextNormalizer.compose(d)));
            if (hit != null) result.set(i, DocumentsContract.buildDocumentUriUsingTree(treeUri, hit));
        }

        return result;
    }

    /**
     * Document ids of {@code parentDocId}'s children, keyed by their composed form (see
     * {@link TextNormalizer#compose}). Empty when the directory doesn't exist or can't be listed.
     */
    private Map<String, String> queryChildDocumentIds(Uri treeUri, String parentDocId) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        Map<String, String> children = new HashMap<>();
        try (Cursor cursor = contentResolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor != null) {
                int col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                if (col >= 0) {
                    while (cursor.moveToNext()) {
                        String childDocId = cursor.getString(col);
                        if (childDocId != null) {
                            children.put(TextNormalizer.compose(childDocId), childDocId);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return children;
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
