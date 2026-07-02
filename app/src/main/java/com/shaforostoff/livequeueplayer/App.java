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

    @Override
    public void onCreate() {
        super.onCreate();
        metadataExtractor = new MetadataExtractor(getContentResolver());
        tagReadExecutor = Executors.newFixedThreadPool(4);
    }

    public MetadataExtractor getMetadataExtractor() {
        return metadataExtractor;
    }

    public ExecutorService getTagReadExecutor() {
        return tagReadExecutor;
    }

    public synchronized BluetoothQueueBridge getBluetoothBridge() {
        if (bluetoothBridge == null) {
            bluetoothBridge = new BluetoothQueueBridge();
        }
        return bluetoothBridge;
    }
}
