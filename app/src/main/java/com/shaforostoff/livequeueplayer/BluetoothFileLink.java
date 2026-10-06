package com.shaforostoff.livequeueplayer;

/** What the two ends of a file transfer write through: the {@link BluetoothQueueBridge}, or a test's pipe. */
interface BluetoothFileLink {
    /** Sends {@code {"type": type, key1: value1, ...}}; a null value leaves its key out. */
    boolean send(String type, Object... keysAndValues);
    boolean sendFileChunk(int id, byte[] buf, int len);
    boolean isConnected();
}
