package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.provider.DocumentsContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * What the Bluetooth file receiver reads off the main thread: the root snapshot, and child lookups
 * that must not depend on whichever tree the browser has open now.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class StorageBrowserRootTest {

    private static final String AUTHORITY = "root.test.docs";
    private StorageBrowser browser;

    @Before
    public void setUp() {
        StorageBrowserListingCacheTest.FolderProvider.duringQuery = null;
        Robolectric.setupContentProvider(StorageBrowserListingCacheTest.FolderProvider.class, AUTHORITY);
        browser = new StorageBrowser(RuntimeEnvironment.getApplication());
    }

    @Test
    public void root_followsTheOpenLocation() {
        assertSame(StorageBrowser.Root.NONE, browser.getRoot());

        assertTrue(browser.openDocumentTree(DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")));
        Uri rootDocument = browser.getCurrentDocumentUri();
        assertEquals(rootDocument, browser.getRoot().document);
        assertNull(browser.getRoot().folder);

        StorageBrowser.Root before = browser.getRoot();
        browser.pushDocument(DocumentsContract.buildDocumentUriUsingTree(rootDocument, "root/sub"));
        assertSame("navigating inside the tree keeps the root", before, browser.getRoot());
        browser.popDocument();
        assertSame(before, browser.getRoot());

        File music = RuntimeEnvironment.getApplication().getFilesDir();
        browser.clearBrowsingState();
        assertSame(StorageBrowser.Root.NONE, browser.getRoot());
        browser.listFolder(music);
        assertEquals(music, browser.getRoot().folder);
        assertNull(browser.getRoot().document);
    }

    /**
     * The receiver resolved its folder in one tree; the user then opened another. Looking a child
     * up under that folder must still query the folder's own tree.
     */
    @Test
    public void childLookup_usesTheParentsTree_notTheOneOpenNow() {
        assertTrue(browser.openDocumentTree(DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")));
        Uri parent = browser.getRoot().document;
        assertTrue(browser.openDocumentTree(DocumentsContract.buildTreeDocumentUri("other.provider", "x")));

        Uri child = browser.findDocumentChildByName(parent, "t5.mp3");
        assertNotNull(child);
        assertEquals(AUTHORITY, child.getAuthority());
        assertEquals("root", DocumentsContract.getTreeDocumentId(child));
        assertEquals("root/t5.mp3", DocumentsContract.getDocumentId(child));
    }
}
