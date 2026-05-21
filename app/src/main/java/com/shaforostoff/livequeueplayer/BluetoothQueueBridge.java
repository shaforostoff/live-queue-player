package com.shaforostoff.livequeueplayer;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minimal classic Bluetooth bridge for queue-fill messages.
 */
final class BluetoothQueueBridge {

    static final class TrackRequest {
        final String file;
        final String parent;

        TrackRequest(String file, String parent) {
            this.file = file;
            this.parent = parent != null ? parent : "";
        }
    }

    interface Listener {
        void onQueueRequestsReceived(List<TrackRequest> tracks);

        void onConnectionStateChanged(boolean connected, String message);
    }

    private static final String SERVICE_NAME = "LiveQueuePlayerRemoteFill";
    private static final UUID SERVICE_UUID = UUID.fromString("0d58a337-968d-4b4c-a8a2-6c4b04e6a8d5");

    private final Listener listener;
    private final Object socketLock = new Object();

    private BluetoothServerSocket serverSocket;
    private BluetoothSocket connectedSocket;
    private BufferedWriter connectedWriter;
    private Thread acceptThread;
    private Thread connectThread;
    private Thread readThread;
    private volatile boolean running;

    BluetoothQueueBridge(Listener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    boolean startServer(BluetoothAdapter adapter) {
        stopServer();
        if (adapter == null) return false;
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
        } catch (Exception e) {
            listener.onConnectionStateChanged(false, "Bluetooth server failed to start");
            return false;
        }

        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    BluetoothSocket socket = serverSocket.accept();
                    if (socket == null) continue;
                    attachSocket(socket, "Client connected");
                } catch (Exception e) {
                    if (running) listener.onConnectionStateChanged(false, "Bluetooth server disconnected");
                    break;
                }
            }
        }, "bt-queue-accept");
        acceptThread.start();
        listener.onConnectionStateChanged(false, "Bluetooth server is listening");
        return true;
    }

    void stopServer() {
        running = false;
        closeServerSocket();
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    @SuppressLint("MissingPermission")
    boolean connect(BluetoothDevice device) {
        if (device == null) return false;
        disconnect();
        Thread thread = new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                socket.connect();
                attachSocket(socket, "Connected to " + device.getName());
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    listener.onConnectionStateChanged(false, "Failed to connect to " + device.getName());
                }
                closeSocketSilently(socket);
            } finally {
                synchronized (socketLock) {
                    if (connectThread == Thread.currentThread()) connectThread = null;
                }
            }
        }, "bt-queue-connect");
        synchronized (socketLock) {
            if (connectThread != null) connectThread.interrupt();
            connectThread = thread;
        }
        thread.start();
        return true;
    }

    boolean sendQueueRequests(List<TrackRequest> requests) {
        if (requests == null || requests.isEmpty()) return false;
        BluetoothSocket socket;
        BufferedWriter writer;
        synchronized (socketLock) {
            socket = connectedSocket;
            writer = connectedWriter;
        }
        if (socket == null || !socket.isConnected() || writer == null) {
            return false;
        }

        try {
            JSONArray payload = new JSONArray();
            for (TrackRequest req : requests) {
                JSONObject obj = new JSONObject();
                obj.put("file", req.file);
                obj.put("parent", req.parent);
                payload.put(obj);
            }
            writer.write(payload.toString());
            writer.write('\n');
            writer.flush();
            return true;
        } catch (Exception e) {
            listener.onConnectionStateChanged(false, "Bluetooth send failed");
            disconnect();
            return false;
        }
    }

    boolean isConnected() {
        synchronized (socketLock) {
            return connectedSocket != null && connectedSocket.isConnected();
        }
    }

    void disconnect() {
        BluetoothSocket toClose;
        Thread connectToInterrupt;
        Thread readToInterrupt;
        synchronized (socketLock) {
            toClose = connectedSocket;
            connectedSocket = null;
            connectedWriter = null;
            connectToInterrupt = connectThread;
            connectThread = null;
            readToInterrupt = readThread;
            readThread = null;
        }
        closeSocketSilently(toClose);
        if (connectToInterrupt != null) connectToInterrupt.interrupt();
        if (readToInterrupt != null) readToInterrupt.interrupt();
        listener.onConnectionStateChanged(false, "Bluetooth disconnected");
    }

    void shutdown() {
        stopServer();
        disconnect();
    }

    private void attachSocket(BluetoothSocket socket, String message) {
        Thread oldReadThread;
        Thread newReadThread = new Thread(() -> readLoop(socket), "bt-queue-read");
        synchronized (socketLock) {
            closeSocketSilently(connectedSocket);
            connectedSocket = socket;
            connectedWriter = null;
            try {
                connectedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            } catch (Exception ignored) {
            }
            oldReadThread = readThread;
            readThread = newReadThread;
        }
        listener.onConnectionStateChanged(true, message);
        if (oldReadThread != null) oldReadThread.interrupt();
        newReadThread.start();
    }

    private void readLoop(BluetoothSocket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            while (running || (socket.isConnected())) {
                String line = reader.readLine();
                if (line == null) break;
                JSONArray arr = new JSONArray(line);
                List<TrackRequest> tracks = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String file = obj.optString("file", "");
                    String parent = obj.optString("parent", "");
                    if (file.length() > 0) tracks.add(new TrackRequest(file, parent));
                }
                if (!tracks.isEmpty()) listener.onQueueRequestsReceived(tracks);
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (socketLock) {
                if (connectedSocket != socket) return;
            }
            disconnect();
        }
    }

    private void closeServerSocket() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }
    }

    private void closeSocketSilently(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}


