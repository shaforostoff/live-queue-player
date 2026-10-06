package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The large-folder listing cache, which a Bluetooth file receive invalidates from another thread. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class StorageBrowserListingCacheTest {

    private static final String AUTHORITY = "listing.test.docs";
    private StorageBrowser browser;
    private Uri root;

    @Before
    public void setUp() {
        FolderProvider.queries = 0;
        FolderProvider.duringQuery = null;
        Robolectric.setupContentProvider(FolderProvider.class, AUTHORITY);
        browser = new StorageBrowser(RuntimeEnvironment.getApplication());
        assertTrue(browser.openDocumentTree(DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")));
        root = browser.getCurrentDocumentUri();
    }

    @Test
    public void bigFolder_isServedFromTheCacheTheSecondTime() {
        assertEquals(300, browser.readCurrentDocumentDirectory().size());
        browser.readCurrentDocumentDirectory();
        assertEquals(1, FolderProvider.queries);
    }

    @Test
    public void invalidation_dropsTheCachedListing() {
        browser.readCurrentDocumentDirectory();
        browser.invalidateDocumentListing(root);
        browser.readCurrentDocumentDirectory();
        assertEquals(2, FolderProvider.queries);
    }

    /**
     * A file lands in the folder while it is being queried: the listing may predate the file, so it
     * must not be cached, or the folder would show without it until something else invalidated it.
     */
    @Test
    public void invalidationDuringTheQuery_keepsThatListingOutOfTheCache() throws Exception {
        FolderProvider.duringQuery = () -> {
            Thread receiver = new Thread(() -> browser.invalidateDocumentListing(root), "bt-queue-read");
            receiver.start();
            try {
                receiver.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        };
        browser.readCurrentDocumentDirectory();
        FolderProvider.duringQuery = null;
        browser.readCurrentDocumentDirectory();
        assertEquals("the second read queries again", 2, FolderProvider.queries);
        browser.readCurrentDocumentDirectory();
        assertEquals("and that one is cached", 2, FolderProvider.queries);
    }

    /** A document tree whose root holds 300 audio files, past the cache's minimum folder size. */
    public static class FolderProvider extends ContentProvider {
        static int queries;
        static Runnable duringQuery;

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            queries++;
            if (duringQuery != null) duringQuery.run();
            MatrixCursor cursor = new MatrixCursor(projection);
            for (int i = 0; i < 300; i++) {
                cursor.addRow(new Object[] {"root/t" + i + ".mp3", "t" + i + ".mp3", "audio/mpeg"});
            }
            return cursor;
        }

        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }
}
