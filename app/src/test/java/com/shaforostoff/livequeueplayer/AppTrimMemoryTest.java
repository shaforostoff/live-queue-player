package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentCallbacks2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

/** App.onTrimMemory gives the caches back only when the system is about to reclaim memory. */
@RunWith(RobolectricTestRunner.class)
@Config(application = App.class, sdk = 33)
public class AppTrimMemoryTest {

    private App app;

    @Before
    public void setUp() {
        app = (App) RuntimeEnvironment.getApplication();
    }

    @Test
    public void uiHidden_keepsTheCaches() {
        fillCaches();
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        assertTrue(listingCached());
        assertTrue("the tag scan is still claimed", tagScanClaimed());
    }

    @Test
    public void background_dropsBothCaches() {
        fillCaches();
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
        assertFalse(listingCached());
        assertFalse("the next browse rescans", tagScanClaimed());
    }

    @Test
    public void background_whileHosting_keepsTheTagCache() throws Exception {
        fillCaches();
        Field running = BluetoothQueueBridge.class.getDeclaredField("running");
        running.setAccessible(true);
        running.setBoolean(app.getBluetoothBridge(), true);

        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
        assertFalse(listingCached());
        assertTrue("remote matching reads it", tagScanClaimed());
    }

    private void fillCaches() {
        listingCache().put("content://tree/folder", new ArrayList<>());
        assertTrue(app.getMetadataExtractor().claimRootScan("root"));
    }

    private boolean listingCached() {
        return !listingCache().isEmpty();
    }

    /** claimRootScan only succeeds once per root until the cache is cleared. */
    private boolean tagScanClaimed() {
        return !app.getMetadataExtractor().claimRootScan("root");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listingCache() {
        try {
            Field f = StorageBrowser.class.getDeclaredField("documentListingCache");
            f.setAccessible(true);
            return (Map<String, Object>) f.get(app.getStorageBrowser());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
