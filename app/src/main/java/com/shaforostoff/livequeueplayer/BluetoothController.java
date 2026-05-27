package com.shaforostoff.livequeueplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class BluetoothController {

    static final int PERMISSION_REQUEST_CODE = 2003;
    private static final String PREFS_BLUETOOTH = "remote_queue_bluetooth";
    private static final String KEY_TARGET_DEVICE = "target_device";

    interface Callback {
        void onModeSelected();
        void onQueueRequestsReceived(List<BluetoothQueueBridge.TrackRequest> tracks);
        void onMatchResultReceived(String jsonLine);
        void onRemoteQueueMessageReceived(String type, JSONObject obj);
        default void onConnectionStateChanged(boolean connected) {}
    }

    private final Activity activity;
    private final Callback callback;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothQueueBridge bridge;
    private String targetDeviceAddress;
    private boolean serverMode;
    private BroadcastReceiver bondingReceiver;
    private AlertDialog devicePickerDialog;
    private int pendingMode = -1; // -1 = show dialog, 1 = server, 0 = client
    private List<BluetoothQueueBridge.TrackRequest> pendingRequests;

    BluetoothController(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        targetDeviceAddress = activity.getSharedPreferences(PREFS_BLUETOOTH, Context.MODE_PRIVATE)
                .getString(KEY_TARGET_DEVICE, null);
    }

    boolean isServerMode() {
        return serverMode;
    }

    void startRemoteSetupAsServer() {
        pendingMode = 1;
        startRemoteSetup();
    }

    void startRemoteSetupAsClient() {
        pendingMode = 0;
        startRemoteSetup();
    }

    void startRemoteSetup() {
        if (bridge == null) {
            bridge = new BluetoothQueueBridge(new BluetoothQueueBridge.Listener() {
                @Override
                public void onQueueRequestsReceived(List<BluetoothQueueBridge.TrackRequest> tracks) {
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed())
                            callback.onQueueRequestsReceived(tracks);
                    });
                }

                @Override
                public void onMatchResultReceived(String jsonLine) {
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed())
                            callback.onMatchResultReceived(jsonLine);
                    });
                }

                @Override
                public void onRemoteQueueMessageReceived(String type, JSONObject obj) {
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed())
                            callback.onRemoteQueueMessageReceived(type, obj);
                    });
                }

                @Override
                public void onConnectionStateChanged(boolean connected, String message) {
                    activity.runOnUiThread(() -> {
                        if (activity.isDestroyed()) return;
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                        callback.onConnectionStateChanged(connected);
                        if (connected && pendingRequests != null) {
                            List<BluetoothQueueBridge.TrackRequest> toSend = pendingRequests;
                            pendingRequests = null;
                            if (bridge.sendQueueRequests(toSend)) {
                                //String msg = toSend.size() == 1
                                //        ? "Track request sent"
                                //        : "Sent " + toSend.size() + " track(s)";
                                //Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            });
        }
        ensurePermissions();
    }

    void onPermissionGranted() {
        showModeDialog();
    }

    boolean sendRaw(String line) {
        if (bridge == null) return false;
        return bridge.sendRaw(line);
    }

    boolean sendQueueRequests(List<BluetoothQueueBridge.TrackRequest> requests) {
        if (bridge == null || !bridge.isConnected()) {
            pendingRequests = requests;
            startRemoteSetup();
            return false;
        }
        boolean sent = bridge.sendQueueRequests(requests);
        if (!sent) {
            Toast.makeText(activity, R.string.bt_not_connected, Toast.LENGTH_SHORT).show();
        }
        return sent;
    }

    boolean sendQueueRequest(String name, String parentFolderName, String title, String artist, String date) {
        List<BluetoothQueueBridge.TrackRequest> list = new ArrayList<>(1);
        list.add(new BluetoothQueueBridge.TrackRequest(name, parentFolderName, title, artist, date));
        return sendQueueRequests(list);
    }

    void shutdown() {
        dismissDialog();
        unregisterBondingReceiver();
        if (bridge != null) {
            bridge.shutdown();
            bridge = null;
        }
    }

    void dismissDialog() {
        if (devicePickerDialog != null && devicePickerDialog.isShowing()) {
            devicePickerDialog.dismiss();
        }
    }

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    PERMISSION_REQUEST_CODE);
            return;
        }
        showModeDialog();
    }

    private void showModeDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions();
            return;
        }
        if (bluetoothAdapter == null) {
            Toast.makeText(activity, R.string.bt_not_available, Toast.LENGTH_LONG).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            activity.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        if (pendingMode == 1) {
            applyServerMode();
            return;
        }
        if (pendingMode == 0) {
            applyClientMode();
            return;
        }
        String[] options = {activity.getString(R.string.bt_receive_requests), activity.getString(R.string.bt_send_requests)};
        new AlertDialog.Builder(activity)
                .setTitle(R.string.bt_mode_dialog_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        applyServerMode();
                    } else {
                        applyClientMode();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyServerMode() {
        serverMode = true;
        bridge.disconnect();
        bridge.startServer(bluetoothAdapter);
        callback.onModeSelected();
    }

    private void applyClientMode() {
        serverMode = false;
        bridge.stopServer();
        pickServerDevice();
        callback.onModeSelected();
    }

    private void pickServerDevice() {
        if (bluetoothAdapter == null) return;

        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        if (bondedDevices == null || bondedDevices.isEmpty()) {
            Toast.makeText(activity, R.string.bt_no_paired_devices, Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<BluetoothDevice> devices = new ArrayList<>(bondedDevices);
        ArrayList<String> items = new ArrayList<>();
        int selectedIndex = -1;
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);
            items.add((device.getName() != null ? device.getName() : activity.getString(R.string.unknown_device)) + "\n" + device.getAddress());
            if (device.getAddress() != null && device.getAddress().equals(targetDeviceAddress)) {
                selectedIndex = i;
            }
        }

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                activity, android.R.layout.simple_list_item_1, items);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.bt_select_server_device)
                .setAdapter(adapter, (dialog, which) -> connectToServer(devices.get(which)))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> unregisterBondingReceiver());

        if (selectedIndex >= 0) {
            int remembered = selectedIndex;
            builder.setNeutralButton(R.string.bt_connect_remembered,
                    (dialog, which) -> connectToServer(devices.get(remembered)));
        }

        devicePickerDialog = builder.create();
        devicePickerDialog.show();
        registerBondingReceiver(devices, items, adapter);
    }

    private void connectToServer(BluetoothDevice device) {
        if (device == null || bridge == null) return;
        targetDeviceAddress = device.getAddress();
        activity.getSharedPreferences(PREFS_BLUETOOTH, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TARGET_DEVICE, targetDeviceAddress)
                .apply();
        bridge.connect(device);
    }

    private void registerBondingReceiver(ArrayList<BluetoothDevice> devices,
                                         ArrayList<String> items,
                                         android.widget.ArrayAdapter<String> adapter) {
        unregisterBondingReceiver();
        bondingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                String deviceName = device != null && device.getName() != null ? device.getName() : activity.getString(R.string.unknown_device);
                if (device != null && bondState == BluetoothDevice.BOND_BONDED) {
                    boolean exists = false;
                    for (BluetoothDevice d : devices) {
                        if (d.getAddress().equals(device.getAddress())) { exists = true; break; }
                    }
                    if (!exists) {
                        devices.add(device);
                        items.add(deviceName + "\n" + device.getAddress());
                        adapter.notifyDataSetChanged();
                        Toast.makeText(activity, activity.getString(R.string.bt_device_paired, deviceName), Toast.LENGTH_SHORT).show();
                    }
                } else if (device != null && bondState == BluetoothDevice.BOND_NONE) {
                    for (int i = 0; i < devices.size(); i++) {
                        if (devices.get(i).getAddress().equals(device.getAddress())) {
                            devices.remove(i);
                            items.remove(i);
                            adapter.notifyDataSetChanged();
                            Toast.makeText(activity, activity.getString(R.string.bt_device_unpaired, deviceName), Toast.LENGTH_SHORT).show();
                            break;
                        }
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(bondingReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(bondingReceiver, filter);
        }
    }

    private void unregisterBondingReceiver() {
        if (bondingReceiver != null) {
            try {
                activity.unregisterReceiver(bondingReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            bondingReceiver = null;
        }
    }
}
