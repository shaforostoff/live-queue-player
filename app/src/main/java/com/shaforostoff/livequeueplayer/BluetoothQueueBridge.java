package com.shaforostoff.livequeueplayer;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal classic Bluetooth bridge for queue-fill messages.
 */
final class BluetoothQueueBridge {

    static final class TrackRequest {
        final String file;
        final String parent;
        final String title;
        final String artist;
        final String date;

        TrackRequest(String file, String parent) {
            this(file, parent, null, null, null);
        }

        TrackRequest(String file, String parent, String title, String artist, String date) {
            this.file   = file;
            this.parent = parent != null ? parent : "";
            this.title  = title  != null ? title  : "";
            this.artist = artist != null ? artist : "";
            this.date   = date   != null ? date   : "";
        }
    }

    interface Listener {
        void onQueueRequestsReceived(List<TrackRequest> tracks);
        void onMatchResultReceived(String jsonLine);
        void onRemoteQueueMessageReceived(String type, JSONObject obj);
        void onConnectionStateChanged(boolean connected, String message);
    }

    private static final String SERVICE_NAME = "LiveQueuePlayerRemoteFill";
    private static final UUID SERVICE_UUID = UUID.fromString("0d58a337-968d-4b4c-a8a2-6c4b04e6a8d5");
    private static final int COMPRESS_THRESHOLD = 1024;

    private final Listener listener;
    private final Object socketLock = new Object();

    private BluetoothServerSocket serverSocket;
    private BluetoothSocket connectedSocket;
    private OutputStream connectedOutput;
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
        synchronized (socketLock) {
            socket = connectedSocket;
        }
        if (socket == null || !socket.isConnected()) return false;

        try {
            JSONArray payload = new JSONArray();
            for (TrackRequest req : requests) {
                JSONObject obj = new JSONObject();
                obj.put("file", req.file);
                obj.put("parent", req.parent);
                if (!req.title.isEmpty())  obj.put("title",  req.title);
                if (!req.artist.isEmpty()) obj.put("artist", req.artist);
                if (!req.date.isEmpty())   obj.put("date",   req.date);
                payload.put(obj);
            }
            return sendBytes(preparePayload(payload.toString()));
        } catch (Exception e) {
            listener.onConnectionStateChanged(false, "Bluetooth send failed");
            disconnect();
            return false;
        }
    }

    boolean sendRaw(String json) {
        try {
            return sendBytes(preparePayload(json));
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
            connectedOutput = null;
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

    private boolean sendBytes(byte[] payload) throws IOException {
        synchronized (socketLock) {
            OutputStream out = connectedOutput;
            if (out == null) return false;
            int len = payload.length;
            out.write(new byte[]{(byte)(len >>> 24), (byte)(len >>> 16), (byte)(len >>> 8), (byte)len});
            out.write(payload);
            out.flush();
            return true;
        }
    }

    private static byte[] preparePayload(String json) throws IOException {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= COMPRESS_THRESHOLD) return raw;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
            gz.write(raw);
        }
        return buf.toByteArray();
    }

    private static byte[] decompress(byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] tmp = new byte[4096];
            int n;
            while ((n = gz.read(tmp)) != -1) out.write(tmp, 0, n);
            return out.toByteArray();
        }
    }

    private void attachSocket(BluetoothSocket socket, String message) {
        Thread oldReadThread;
        Thread newReadThread = new Thread(() -> readLoop(socket), "bt-queue-read");
        synchronized (socketLock) {
            closeSocketSilently(connectedSocket);
            connectedSocket = socket;
            connectedOutput = null;
            try {
                connectedOutput = socket.getOutputStream();
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
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            while (running || socket.isConnected()) {
                int length = in.readInt();
                byte[] data = new byte[length];
                in.readFully(data);
                // GZIP magic: 0x1F 0x8B
                if (length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
                    data = decompress(data);
                }
                String line = new String(data, StandardCharsets.UTF_8);
                try {
                    if (line.startsWith("{")) {
                        try {
                            JSONObject obj = new JSONObject(line);
                            String type = obj.optString("type", "");
                            if ("match_result".equals(type) || type.isEmpty()) {
                                listener.onMatchResultReceived(line);
                            } else {
                                listener.onRemoteQueueMessageReceived(type, obj);
                            }
                        } catch (Exception ignored) {
                        }
                    } else {
                        JSONArray arr = new JSONArray(line);
                        List<TrackRequest> tracks = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.getJSONObject(i);
                            String file   = obj.optString("file",   "");
                            String parent = obj.optString("parent", "");
                            String title  = obj.optString("title",  "");
                            String artist = obj.optString("artist", "");
                            String date   = obj.optString("date",   "");
                            if (file.length() > 0) tracks.add(new TrackRequest(file, parent, title, artist, date));
                        }
                        if (!tracks.isEmpty()) listener.onQueueRequestsReceived(tracks);
                    }
                } catch (Exception ignored) {
                }
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
