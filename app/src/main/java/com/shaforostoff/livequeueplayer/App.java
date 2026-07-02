package com.shaforostoff.livequeueplayer;

import android.app.Application;

public class App extends Application {

    private MetadataExtractor metadataExtractor;
    // Application-scoped so the remote-queue Bluetooth connection survives activity teardown
    // (e.g. screen rotation). The per-activity BluetoothController attaches/detaches as its listener.
    private BluetoothQueueBridge bluetoothBridge;

    @Override
    public void onCreate() {
        super.onCreate();
        metadataExtractor = new MetadataExtractor(getContentResolver());
    }

    public MetadataExtractor getMetadataExtractor() {
        return metadataExtractor;
    }

    public synchronized BluetoothQueueBridge getBluetoothBridge() {
        if (bluetoothBridge == null) {
            bluetoothBridge = new BluetoothQueueBridge();
        }
        return bluetoothBridge;
    }
}
