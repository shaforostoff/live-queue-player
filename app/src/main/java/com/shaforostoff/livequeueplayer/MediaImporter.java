package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a track that arrives through a transient content provider into the user's granted music
 * folder, so the queue can still play it after the temporary read grant is gone.
 *
 * <p>A file shared from another app via {@code ACTION_SEND}/{@code ACTION_VIEW} (e.g. Telegram's
 * {@code content://org.telegram.messenger.provider/...}) is readable only while that share grant
 * lives — until our task/process is gone. {@link QueueStore} persists the raw URI, so on the next
 * launch {@code ContentResolver} can no longer even resolve the provider ("No content provider")
 * and the track is silently skipped. By copying the bytes, while the grant is still alive, into a
 * folder we hold a <em>persistable</em> SAF grant on, the queued URI stays valid indefinitely.
 *
 * <p>If no SAF music folder has been granted there is nowhere durable to copy to, so the original
 * URI is kept unchanged (same behaviour as before this class existed).
 */
final class MediaImporter {

    private static final String TAG = "MediaImporter";

    // Mirrors StorageBrowser's private prefs so the headless Service can find the granted tree.
    private static final String BROWSER_PREFS = "browser_prefs";
    private static final String PREF_LAST_TREE_URI = "last_tree_uri";

    private static final String IMPORT_FOLDER = "Imported";

    private MediaImporter() {}

    /**
     * If {@code location} points at a transient provider, copy it into {@code Imported/} under the
     * remembered music-folder tree and return the durable copy's URI. Otherwise — or on any failure,
     * or when no writable SAF tree has been granted — return {@code location} unchanged.
     *
     * @param suggestedName fallback file name when the source exposes no display name
     */
    static Uri durableCopyIfNeeded(Context context, Uri location, String suggestedName) {
        if (location == null) return location;
        // file:// re-opens fine on every launch; nothing to copy.
        if (!"content".equalsIgnoreCase(location.getScheme())) return location;
        // A SAF document URI from our own granted tree is already durable.
        if (DocumentsContract.isDocumentUri(context, location)) return location;
        // MediaStore items persist with the media permission; don't duplicate them.
        if (MediaStore.AUTHORITY.equals(location.getAuthority())) return location;

        Uri treeUri = writableRememberedTree(context);
        if (treeUri == null) return location;   // no granted folder -> keep current behaviour

        try {
            Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Uri importDir = findOrCreateChildDir(context, treeUri, rootDoc, IMPORT_FOLDER);
            if (importDir == null) return location;

            String name = sanitizeName(displayName(context, location, suggestedName));

            // A previous import of the same name is assumed to be the same track: reuse it.
            Uri existing = findChild(context, treeUri, importDir, name);
            if (existing != null) {
                Log.w(TAG, "already imported, reusing: " + name);
                return existing;
            }

            // Generic mime keeps the display name (and its extension) verbatim — the app's AIFF/ALAC
            // detection relies on the extension, so we must not let the provider rewrite it.
            Uri target = DocumentsContract.createDocument(
                    context.getContentResolver(), importDir, "application/octet-stream", name);
            if (target == null) return location;

            try (InputStream in = context.getContentResolver().openInputStream(location);
                 OutputStream out = context.getContentResolver().openOutputStream(target, "wt")) {
                if (in == null || out == null) {
                    safeDelete(context, target);
                    return location;
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception copyError) {
                safeDelete(context, target);   // don't leave a truncated file behind
                throw copyError;
            }

            Log.w(TAG, "imported " + location + " -> " + target);
            return target;
        } catch (Exception e) {
            Log.w(TAG, "import failed for " + location + ": " + e);
            return location;   // no worse than before
        }
    }

    /** The remembered tree URI, but only if we still hold a persisted <em>write</em> grant on it. */
    private static Uri writableRememberedTree(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(PREF_LAST_TREE_URI, null);
        if (stored == null || stored.isEmpty()) return null;
        Uri treeUri;
        try {
            treeUri = Uri.parse(stored);
        } catch (Exception e) {
            return null;
        }
        for (UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.isWritePermission() && treeUri.equals(p.getUri())) return treeUri;
        }
        return null;
    }

    private static Uri findOrCreateChildDir(Context context, Uri treeUri, Uri parentDoc, String name) {
        Uri existing = findChild(context, treeUri, parentDoc, name);
        if (existing != null) return existing;
        try {
            return DocumentsContract.createDocument(context.getContentResolver(), parentDoc,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static Uri findChild(Context context, Uri treeUri, Uri parentDoc, String name) {
        String parentId = DocumentsContract.getDocumentId(parentDoc);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        try (Cursor c = context.getContentResolver().query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String displayName(Context context, Uri uri, String fallback) {
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isEmpty()) return "track";
        return name.replace('/', '_').replace('\\', '_');
    }

    private static void safeDelete(Context context, Uri docUri) {
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), docUri);
        } catch (Exception ignored) {
        }
    }
}
