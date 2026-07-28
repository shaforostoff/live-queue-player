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

    @Override
    public void onCreate() {
        super.onCreate();
        metadataExtractor = new MetadataExtractor(getContentResolver());
        tagReadExecutor = Executors.newFixedThreadPool(4);
        storageBrowser = new StorageBrowser(this);
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

    public synchronized BluetoothQueueBridge getBluetoothBridge() {
        if (bluetoothBridge == null) {
            bluetoothBridge = new BluetoothQueueBridge();
        }
        return bluetoothBridge;
    }
}
