package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The client reconnect schedule: quick at first, slow after a few minutes, then it stops. */
public class ReconnectScheduleTest {

    private static final long MIN = 60_000L;

    @Test
    public void firstMinutes_keepTheQuickBackoff() {
        assertEquals(1_000L, BluetoothQueueBridge.reconnectDelayMs(0, 0));
        assertEquals(2_000L, BluetoothQueueBridge.reconnectDelayMs(1, 1_000L));
        assertEquals(5_000L, BluetoothQueueBridge.reconnectDelayMs(20, 4 * MIN));
    }

    @Test
    public void afterFiveMinutes_retriesEveryThirtySeconds() {
        assertEquals(30_000L, BluetoothQueueBridge.reconnectDelayMs(40, 5 * MIN));
        assertEquals(30_000L, BluetoothQueueBridge.reconnectDelayMs(80, 29 * MIN));
    }

    @Test
    public void afterThirtyMinutes_givesUp() {
        assertEquals(-1L, BluetoothQueueBridge.reconnectDelayMs(100, 30 * MIN));
    }
}
