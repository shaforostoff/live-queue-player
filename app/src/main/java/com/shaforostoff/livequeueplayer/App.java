package com.shaforostoff.livequeueplayer;

import android.app.Application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {

    private MetadataExtractor metadataExtractor;
    // Application-scoped so the remote-queue Bluetooth connection survives activity teardown
    // (e.g. screen rotation). The per-activity BluetoothController attaches/detaches as its listener.
    private BluetoothQueueBridge bluetoothBridge;
    // Application-scoped so a long background tag scan submitted from an activity is never rejected
    // when that activity is destroyed mid-scan (e.g. rotation). A per-activity executor shut down in
    // onDestroy would throw RejectedExecutionException on the still-running scan thread's next
    // submit(), and an uncaught exception on that thread kills the whole process — including the
    // playback service. The tasks only touch the (also app-scoped) MetadataExtractor cache.
    private ExecutorService tagReadExecutor;
    // Application-scoped so the browsing location — and its warm listing cache — survives activity
    // teardown. Activities are destroyed and recreated for any unhandled configuration change, and
    // a scheduled dark-theme flip does that behind a locked screen: the relaunch lands in onCreate
    // with the browser reset to the storage root, mid-set. Holding the location here makes that
    // relaunch a re-list of the same folder (a cache hit for the big folders), and
    // StorageBrowser.persistLocation() covers the cold-start case where this instance is gone too.
    private StorageBrowser storageBrowser;
    // Application-scoped for the same reason tagReadExecutor is: an analysis outlives the activity
    // that asked for it, and its result belongs to the (also app-scoped) tag cache rather than to
    // whoever happens to be on screen when it lands.
    private BpmScanner bpmScanner;

    @Override
    public void onCreate() {
        super.onCreate();
        metadataExtractor = new MetadataExtractor(getContentResolver());
        tagReadExecutor = Executors.newFixedThreadPool(4);
        storageBrowser = new StorageBrowser(this);
        // Nothing can be playing yet in a fresh process, so any staged ALAC copy is a leftover.
        AlacMediaDataSource.deleteStaleStagedFiles(this);
        bpmScanner = new BpmScanner(this, metadataExtractor);
        if (BuildConfig.DEBUG) {
            // This app logs nothing in normal operation, so "is BPM analysis even on this device?"
            // is otherwise unanswerable without a debugger. One line at startup: whether the
            // library loaded at all, and whether pffft reached NEON (4) or fell back to scalar (1),
            // which is a sixfold slowdown that reports itself no other way.
            android.util.Log.d("Bpm", BpmNative.isSupported()
                    ? "analysis available, pffft SIMD width " + BpmNative.nativeSimdSize()
                    : "analysis not in this build (" + android.os.Build.SUPPORTED_ABIS[0] + ")");
        }
    }

    /**
     * Give the caches back when the system is about to reclaim memory. Only from BACKGROUND up:
     * UI_HIDDEN arrives every time the app leaves the screen, and dropping the caches there would
     * throw away the warm folder listings (each a ~1 s SAF query to rebuild) of a user who merely
     * switched apps. BACKGROUND means this process is on the list the system kills from, so freeing
     * memory now is what may keep it alive.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_BACKGROUND) return;
        storageBrowser.clearListingCache();
        // A remote-receive host matches requested tracks by title and artist against the tag cache
        // alone, with the screen off; clearing it mid-session would quietly lose those matches
        // until the library is browsed again.
        BluetoothQueueBridge bridge;
        synchronized (this) {
            bridge = bluetoothBridge;
        }
        if (bridge == null || !bridge.isServerRunning()) metadataExtractor.clearCache();
    }

    public MetadataExtractor getMetadataExtractor() {
        return metadataExtractor;
    }

    public ExecutorService getTagReadExecutor() {
        return tagReadExecutor;
    }

    StorageBrowser getStorageBrowser() {
        return storageBrowser;
    }

    BpmScanner getBpmScanner() {
        return bpmScanner;
    }

    private BluetoothFileSender fileSender;
    private BluetoothFileReceiver fileReceiver;

    /** App-scoped like the bridge, so a rotation neither cancels a transfer nor loses its state. */
    synchronized BluetoothFileSender getFileSender() {
        if (fileSender == null) fileSender = new BluetoothFileSender(this, getBluetoothBridge());
        return fileSender;
    }

    synchronized BluetoothFileReceiver getFileReceiver() {
        if (fileReceiver == null) {
            fileReceiver = new BluetoothFileReceiver(this, storageBrowser, getBluetoothBridge());
        }
        return fileReceiver;
    }

    private final ArrivalOrder arrivalOrder = new ArrivalOrder();

    /** UI thread; see {@link ArrivalOrder}. */
    ArrivalOrder getArrivalOrder() {
        return arrivalOrder;
    }

    public synchronized BluetoothQueueBridge getBluetoothBridge() {
        if (bluetoothBridge == null) {
            bluetoothBridge = new BluetoothQueueBridge(this);
        }
        return bluetoothBridge;
    }
}
