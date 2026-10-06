package com.shaforostoff.livequeueplayer;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
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
final class BluetoothQueueBridge implements BluetoothFileLink {

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

    /**
     * The file-transfer side channel: every "file_*" message and every binary chunk frame. Called
     * on the read thread, not posted to the UI thread, so begin / chunks / end arrive strictly in
     * wire order and a slow write on the receiving side pushes back on the sender through RFCOMM.
     */
    interface FileSink {
        void onFileMessage(String type, JSONObject obj);
        /** {@code frame} is the raw frame: marker byte, 4-byte transfer id, then the bytes. */
        void onFileChunk(byte[] frame);
        /** A socket is up — the first one, or a reconnect after {@link #onLinkLost}. */
        void onLinkUp();
        /** The socket this sink was fed from is gone; whatever was in flight on it is lost. */
        void onLinkLost();
    }

    /** Swallows callbacks while no activity is attached (e.g. mid-rotation). */
    private static final Listener NO_OP = new Listener() {
        @Override public void onQueueRequestsReceived(List<TrackRequest> tracks) {}
        @Override public void onMatchResultReceived(String jsonLine) {}
        @Override public void onRemoteQueueMessageReceived(String type, JSONObject obj) {}
        @Override public void onConnectionStateChanged(boolean connected, String message) {}
    };

    private static final String SERVICE_NAME = "LiveQueuePlayerRemoteFill";
    private static final UUID SERVICE_UUID = UUID.fromString("0d58a337-968d-4b4c-a8a2-6c4b04e6a8d5");
    private static final int COMPRESS_THRESHOLD = 1024;
    // Sanity cap on the length prefix: a desynced/corrupt frame can otherwise read a garbage
    // length and either try to allocate gigabytes or throw NegativeArraySizeException.
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    // First byte of a binary file-chunk frame. JSON frames start with '{' or '[' and gzip ones with
    // 0x1F, so this can't collide; a peer without file transfer fails to parse it and drops it.
    private static final byte FILE_CHUNK_MARKER = 0x02;
    static final int FILE_CHUNK_HEADER = 5;

    // The bridge is application-scoped (see App), so it outlives any single activity. The current
    // activity attaches via setListener(); the volatile ref lets read/connect threads swap safely.
    private volatile Listener listener = NO_OP;
    private volatile FileSink fileSink;
    private final Object socketLock = new Object();

    private BluetoothServerSocket serverSocket;
    private BluetoothSocket connectedSocket;
    private OutputStream connectedOutput;
    private Thread acceptThread;
    private Thread connectThread;
    private Thread readThread;
    private volatile boolean running;

    private BluetoothAdapter serverAdapter;
    private final Context appContext;
    /** Wakes an accept loop parked while Bluetooth is off; see {@link #awaitAdapterOn()}. */
    private final Object adapterLock = new Object();
    private BroadcastReceiver adapterStateReceiver; // registered while the server runs; main thread
    private volatile boolean wantConnected;
    private volatile BluetoothDevice lastDevice;
    /** Set when the reconnect loop gave up on its own (not by an explicit disconnect). */
    private volatile boolean reconnectGaveUp;

    // Each attempt at an absent device keeps the radio paging for ~5 s, so retrying every 5 s
    // forever held it busy about half the time for as long as the process lived. Retry quickly for
    // a few minutes (a drop mid-set), then slowly, then stop until the user is back.
    private static final long RECONNECT_FAST_PHASE_MS = 5 * 60 * 1_000L;
    private static final long RECONNECT_SLOW_DELAY_MS = 30_000L;
    private static final long RECONNECT_GIVE_UP_MS = 30 * 60 * 1_000L;

    // elapsedRealtime() of the last inbound remote frame, or 0 when nothing has ever arrived.
    // Stamped on the read thread, read by the playback Service's idle watchdog (which retires a
    // service that has sat foreground without playback or remote traffic) — hence static: the
    // watchdog must see the traffic even when this bridge instance is not the one it can reach.
    private static volatile long sLastInboundElapsedMs;

    BluetoothQueueBridge(Context context) {
        appContext = context.getApplicationContext();
    }

    /** @see #sLastInboundElapsedMs */
    static long lastInboundElapsedMs() {
        return sLastInboundElapsedMs;
    }

    /**
     * Attaches the current activity's callback, or {@code null} to detach (falls back to a no-op).
     * When attaching to an already-live connection, callers can query {@link #isConnected()} to
     * decide whether to re-sync UI state that was lost with the previous activity instance.
     */
    void setListener(Listener listener) {
        this.listener = (listener != null) ? listener : NO_OP;
    }

    /** Attaches the file-transfer handler, or {@code null} to drop file traffic. */
    void setFileSink(FileSink sink) {
        this.fileSink = sink;
    }

    /** True while the RFCOMM server socket is accepting (host/receiver role). */
    boolean isServerRunning() {
        return running;
    }

    /** True while a client connection is wanted — connected or actively reconnecting (sender role). */
    boolean isClientActive() {
        return wantConnected;
    }

    /** Delay before the Nth (0-based) retry of a dropped server/client connection. */
    private static long backoffDelayMs(int attempt) {
        if (attempt <= 0) return 1000L;
        if (attempt == 1) return 2000L;
        return 5000L;
    }

    /** Delay before the next client reconnect attempt, or -1 to give up. */
    static long reconnectDelayMs(int attempt, long failingForMs) {
        if (failingForMs >= RECONNECT_GIVE_UP_MS) return -1;
        return failingForMs < RECONNECT_FAST_PHASE_MS ? backoffDelayMs(attempt) : RECONNECT_SLOW_DELAY_MS;
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
        registerAdapterStateReceiver();
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
                if (!serverAdapter.isEnabled()) {
                    // Turned off: no retry can succeed until it is back on, and retrying every 5 s
                    // until then kept this thread waking for nothing. Wait for the state broadcast.
                    listener.onConnectionStateChanged(false, "Bluetooth is off, the server resumes when it is on");
                    if (!awaitAdapterOn()) break;
                    attempt = 0;
                    if (openServerSocket()) {
                        listener.onConnectionStateChanged(false, "Bluetooth server is listening");
                    }
                    continue;
                }
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
        unregisterAdapterStateReceiver();
    }

    /** Blocks the accept thread until Bluetooth is on again. False if the server stopped meanwhile. */
    private boolean awaitAdapterOn() {
        synchronized (adapterLock) {
            // Checked under the lock the receiver notifies with, so a STATE_ON landing between the
            // check and the wait cannot be missed.
            while (running && !serverAdapter.isEnabled()) {
                try {
                    adapterLock.wait();
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return running;
    }

    private void registerAdapterStateReceiver() {
        if (adapterStateReceiver != null) return;
        adapterStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state != BluetoothAdapter.STATE_ON) return;
                synchronized (adapterLock) {
                    adapterLock.notifyAll();
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(adapterStateReceiver, filter);
        }
    }

    private void unregisterAdapterStateReceiver() {
        if (adapterStateReceiver == null) return;
        try {
            appContext.unregisterReceiver(adapterStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        adapterStateReceiver = null;
    }

    @SuppressLint("MissingPermission")
    boolean connect(BluetoothDevice device) {
        if (device == null) return false;
        wantConnected = true;
        reconnectGaveUp = false;
        lastDevice = device;
        startConnectAttempt();
        return true;
    }

    /**
     * Restart reconnecting to the remembered device if the loop gave up on its own; called when the
     * user is back in the app. An explicit disconnect is left alone. True if it restarted.
     */
    boolean resumeReconnectIfGaveUp() {
        BluetoothDevice device = lastDevice;
        if (!reconnectGaveUp || wantConnected || device == null) return false;
        return connect(device);
    }

    private void startConnectAttempt() {
        Thread thread = new Thread(this::runConnectLoop, "bt-queue-connect");
        synchronized (socketLock) {
            if (connectThread != null) connectThread.interrupt();
            connectThread = thread;
        }
        thread.start();
    }

    /** Retries the remembered device with backoff until it connects, is cancelled, or gives up. */
    @SuppressLint("MissingPermission")
    private void runConnectLoop() {
        Thread self = Thread.currentThread();
        long startedAt = SystemClock.elapsedRealtime();
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
                    long delay = reconnectDelayMs(attempt++, SystemClock.elapsedRealtime() - startedAt);
                    if (delay < 0) {
                        synchronized (socketLock) {
                            if (connectThread != self) return; // superseded by a newer attempt
                            wantConnected = false;
                            reconnectGaveUp = true;
                        }
                        listener.onConnectionStateChanged(false, "Stopped reconnecting to " + safeName(device));
                        return;
                    }
                    if (attempt == 1) {
                        listener.onConnectionStateChanged(false, "Reconnecting to " + safeName(device) + "...");
                    }
                    try {
                        Thread.sleep(delay);
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

    @Override
    public boolean send(String type, Object... keysAndValues) {
        try {
            JSONObject msg = new JSONObject().put("type", type);
            for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
                msg.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
            return sendRaw(msg.toString());
        } catch (JSONException e) {
            return false; // only for a NaN/infinite number, which no message carries
        }
    }

    /** Sends one binary chunk of transfer {@code id}: {@code len} bytes of {@code buf}, uncompressed. */
    @Override
    public boolean sendFileChunk(int id, byte[] buf, int len) {
        byte[] frame = new byte[FILE_CHUNK_HEADER + len];
        frame[0] = FILE_CHUNK_MARKER;
        frame[1] = (byte) (id >>> 24);
        frame[2] = (byte) (id >>> 16);
        frame[3] = (byte) (id >>> 8);
        frame[4] = (byte) id;
        System.arraycopy(buf, 0, frame, FILE_CHUNK_HEADER, len);
        try {
            return sendBytes(frame);
        } catch (Exception e) {
            handleSendFailure();
            return false;
        }
    }

    static int fileChunkId(byte[] frame) {
        return ((frame[1] & 0xFF) << 24) | ((frame[2] & 0xFF) << 16) | ((frame[3] & 0xFF) << 8) | (frame[4] & 0xFF);
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

    @Override
    public boolean isConnected() {
        synchronized (socketLock) {
            return connectedSocket != null && connectedSocket.isConnected();
        }
    }

    /** Explicit, user/mode-initiated disconnect — cancels any pending auto-reconnect. */
    void disconnect() {
        wantConnected = false;
        reconnectGaveUp = false;
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
        FileSink sink = fileSink;
        if (sink != null) sink.onLinkUp();
        if (oldReadThread != null) oldReadThread.interrupt();
        newReadThread.start();
    }

    private void readLoop(BluetoothSocket socket) {
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            while (running || socket.isConnected()) {
                int length = in.readInt();
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    // Corrupt or desynced framing — there's no way to resync this stream, so treat
                    // it like the connection dying; handleSocketClosed() reconnects if applicable.
                    throw new IOException("Bad frame length: " + length);
                }
                byte[] data = new byte[length];
                in.readFully(data);
                // Stamp before dispatch: every remote command and state poll passes through here,
                // so this is the single point that keeps the Service's idle watchdog from retiring
                // a live remote session. Framing errors above deliberately do not count as traffic.
                sLastInboundElapsedMs = SystemClock.elapsedRealtime();
                if (length >= FILE_CHUNK_HEADER && data[0] == FILE_CHUNK_MARKER) {
                    FileSink sink = fileSink;
                    if (sink != null) sink.onFileChunk(data);
                    continue;
                }
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
                            if (type.startsWith("file_")) {
                                FileSink sink = fileSink;
                                if (sink != null) sink.onFileMessage(type, obj);
                            } else if ("match_result".equals(type) || type.isEmpty()) {
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
            FileSink sink = fileSink;
            if (sink != null) sink.onLinkLost();
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
