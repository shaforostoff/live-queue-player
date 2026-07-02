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
        /** Full path relative to the sender's root folder (e.g. "Artist/Album/song.mp3"), or "". */
        final String path;
        final String title;
        final String artist;
        final String date;

        TrackRequest(String file, String path) {
            this(file, path, null, null, null);
        }

        TrackRequest(String file, String path, String title, String artist, String date) {
            this.file   = file;
            this.path   = path   != null ? path   : "";
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

    private BluetoothAdapter serverAdapter;
    private volatile boolean wantConnected;
    private volatile BluetoothDevice lastDevice;

    BluetoothQueueBridge(Listener listener) {
        this.listener = listener;
    }

    /** Delay before the Nth (0-based) retry of a dropped server/client connection. */
    private static long backoffDelayMs(int attempt) {
        if (attempt <= 0) return 1000L;
        if (attempt == 1) return 2000L;
        return 5000L;
    }

    private static String safeName(BluetoothDevice device) {
        String name = device.getName();
        return name != null ? name : device.getAddress();
    }

    @SuppressLint("MissingPermission")
    boolean startServer(BluetoothAdapter adapter) {
        stopServer();
        if (adapter == null) return false;
        serverAdapter = adapter;
        if (!openServerSocket()) return false;

        running = true;
        acceptThread = new Thread(this::acceptLoop, "bt-queue-accept");
        acceptThread.start();
        listener.onConnectionStateChanged(false, "Bluetooth server is listening");
        return true;
    }

    @SuppressLint("MissingPermission")
    private boolean openServerSocket() {
        try {
            serverSocket = serverAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
            return true;
        } catch (Exception e) {
            listener.onConnectionStateChanged(false, "Bluetooth server failed to start");
            return false;
        }
    }

    /** Keeps listening across transient accept failures instead of giving up permanently. */
    private void acceptLoop() {
        int attempt = 0;
        while (running) {
            try {
                BluetoothSocket socket = serverSocket.accept();
                if (socket == null) continue;
                attempt = 0;
                attachSocket(socket, "Client connected");
            } catch (Exception e) {
                if (!running) break;
                closeServerSocket();
                if (attempt == 0) {
                    listener.onConnectionStateChanged(false, "Bluetooth server dropped, restarting...");
                }
                try {
                    Thread.sleep(backoffDelayMs(attempt++));
                } catch (InterruptedException ie) {
                    break;
                }
                if (!running) break;
                if (!openServerSocket()) continue; // keep retrying with backoff
            }
        }
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
        wantConnected = true;
        lastDevice = device;
        startConnectAttempt();
        return true;
    }

    private void startConnectAttempt() {
        Thread thread = new Thread(this::runConnectLoop, "bt-queue-connect");
        synchronized (socketLock) {
            if (connectThread != null) connectThread.interrupt();
            connectThread = thread;
        }
        thread.start();
    }

    /** Retries the remembered device with backoff until it connects or reconnection is cancelled. */
    @SuppressLint("MissingPermission")
    private void runConnectLoop() {
        Thread self = Thread.currentThread();
        try {
            int attempt = 0;
            while (wantConnected && !self.isInterrupted()) {
                BluetoothDevice device = lastDevice;
                if (device == null) return;
                BluetoothSocket socket = null;
                try {
                    socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                    socket.connect();
                    attachSocket(socket, "Connected to " + safeName(device));
                    return;
                } catch (Exception e) {
                    closeSocketSilently(socket);
                    if (!wantConnected || self.isInterrupted()) return;
                    if (attempt == 0) {
                        listener.onConnectionStateChanged(false, "Reconnecting to " + safeName(device) + "...");
                    }
                    try {
                        Thread.sleep(backoffDelayMs(attempt++));
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        } finally {
            synchronized (socketLock) {
                if (connectThread == self) connectThread = null;
            }
        }
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
                if (!req.path.isEmpty())   obj.put("path",   req.path);
                if (!req.title.isEmpty())  obj.put("title",  req.title);
                if (!req.artist.isEmpty()) obj.put("artist", req.artist);
                if (!req.date.isEmpty())   obj.put("date",   req.date);
                payload.put(obj);
            }
            return sendBytes(preparePayload(payload.toString()));
        } catch (Exception e) {
            handleSendFailure();
            return false;
        }
    }

    boolean sendRaw(String json) {
        try {
            return sendBytes(preparePayload(json));
        } catch (Exception e) {
            handleSendFailure();
            return false;
        }
    }

    /** A write failure means the socket is dead; treat it like the read loop hitting EOF. */
    private void handleSendFailure() {
        BluetoothSocket failed;
        synchronized (socketLock) {
            failed = connectedSocket;
        }
        if (failed != null) handleSocketClosed(failed);
        else listener.onConnectionStateChanged(false, "Bluetooth send failed");
    }

    boolean isConnected() {
        synchronized (socketLock) {
            return connectedSocket != null && connectedSocket.isConnected();
        }
    }

    /** Explicit, user/mode-initiated disconnect — cancels any pending auto-reconnect. */
    void disconnect() {
        wantConnected = false;
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

    /**
     * A socket died on its own (read EOF/error, or a failed write) rather than by user action.
     * In client mode this auto-reconnects to the remembered device with backoff; in server mode
     * there's nothing to reconnect to — the accept loop is already listening for the next client.
     */
    private void handleSocketClosed(BluetoothSocket socket) {
        synchronized (socketLock) {
            if (connectedSocket != socket) return; // already replaced or handled
            connectedSocket = null;
            connectedOutput = null;
            readThread = null;
        }
        closeSocketSilently(socket);
        if (wantConnected && lastDevice != null) {
            listener.onConnectionStateChanged(false, "Connection lost, reconnecting...");
            startConnectAttempt();
        } else {
            listener.onConnectionStateChanged(false, "Bluetooth disconnected");
        }
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
                            String path   = obj.optString("path",   "");
                            String title  = obj.optString("title",  "");
                            String artist = obj.optString("artist", "");
                            String date   = obj.optString("date",   "");
                            if (file.length() > 0) tracks.add(new TrackRequest(file, path, title, artist, date));
                        }
                        if (!tracks.isEmpty()) listener.onQueueRequestsReceived(tracks);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            handleSocketClosed(socket);
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
