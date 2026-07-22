package com.shaforostoff.livequeueplayer;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

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
        if (BuildConfig.DEBUG) registerBtChaosReceiver();
    }

    // ===================== Step 4 (Bluetooth) — debug chaos control =====================
    // Debug-only relay so an off-device harness can make THIS phone (in remote-send mode) emit
    // real remote-queue commands over the live Bluetooth link, timed to the receiver's track
    // boundary. It reuses the app-scoped bridge, so it rides the exact same socket the UI uses.
    // Stripped from release builds. See scripts/bluetooth-boundary-chaos.sh.
    static final String CHAOS_BT_ACTION = "com.shaforostoff.livequeueplayer.CHAOS_BT";
    private static final String CHAOS_BT_TAG = "LqpChaosBt";
    private BroadcastReceiver btChaosReceiver;

    private void registerBtChaosReceiver() {
        btChaosReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleBtChaos(intent.getStringExtra("cmd"),
                        intent.getIntExtra("id", -1), intent.getIntExtra("to", -1));
            }
        };
        IntentFilter filter = new IntentFilter(CHAOS_BT_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btChaosReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(btChaosReceiver, filter);
        }
    }

    /** Relay a remote-queue command over the live Bluetooth connection (sender role). */
    private void handleBtChaos(String cmd, int id, int to) {
        if (cmd == null) return;
        BluetoothQueueBridge bridge = getBluetoothBridge();
        if ("btstatus".equals(cmd)) {
            Log.i(CHAOS_BT_TAG, "BTSTATUS server=" + bridge.isServerRunning()
                    + " clientActive=" + bridge.isClientActive() + " connected=" + bridge.isConnected());
            return;
        }
        String json;
        try {
            switch (cmd) {
                case "stop_playback", "resume_playback", "request_queue" ->
                        json = new JSONObject().put("type", cmd).toString();
                case "play_track", "remove_track" ->
                        json = new JSONObject().put("type", cmd).put("id", id).toString();
                case "move_track" ->
                        json = new JSONObject().put("type", "move_track").put("id", id).put("to_position", to).toString();
                default -> { Log.w(CHAOS_BT_TAG, "unknown bt cmd: " + cmd); return; }
            }
        } catch (Exception e) {
            Log.w(CHAOS_BT_TAG, "build failed: " + e);
            return;
        }
        boolean ok = bridge.sendRaw(json);
        Log.i(CHAOS_BT_TAG, "SEND " + json + " ok=" + ok);
    }
    // =================== end Step 4 (Bluetooth) — debug chaos control ===================

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
