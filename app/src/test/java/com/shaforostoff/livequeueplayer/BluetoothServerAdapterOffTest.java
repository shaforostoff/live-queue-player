package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

/** With Bluetooth off the server's accept loop waits for it to come back instead of retrying. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class BluetoothServerAdapterOffTest {

    private Application app;
    private BluetoothAdapter adapter;
    private BluetoothQueueBridge bridge;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        adapter = BluetoothAdapter.getDefaultAdapter();
        shadowOf(adapter).setEnabled(true);
        bridge = new BluetoothQueueBridge(app);
        assertTrue(bridge.startServer(adapter));
    }

    @After
    public void tearDown() {
        bridge.stopServer();
    }

    @Test
    public void adapterOff_parksTheLoopUntilStateOn() throws Exception {
        BluetoothServerSocket first = serverSocket();
        assertNotNull(first);

        // Bluetooth turns off: the stack closes the server socket and accept() throws.
        shadowOf(adapter).setEnabled(false);
        first.close();
        Thread accept = acceptThread();
        waitFor(() -> accept.getState() == Thread.State.WAITING);
        assertNull("no socket reopened while off", serverSocket());
        Thread.sleep(1_500);              // past the old 1 s retry: still parked, no retries
        assertEquals(Thread.State.WAITING, accept.getState());

        shadowOf(adapter).setEnabled(true);
        app.sendBroadcast(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON));
        shadowOf(Looper.getMainLooper()).idle();
        waitFor(() -> serverSocket() != null);
        assertTrue("listening again", bridge.isServerRunning());
    }

    @Test
    public void stopWhileParked_endsTheLoop() throws Exception {
        shadowOf(adapter).setEnabled(false);
        serverSocket().close();
        Thread accept = acceptThread();
        waitFor(() -> accept.getState() == Thread.State.WAITING);
        bridge.stopServer();
        accept.join(2_000);
        assertEquals(Thread.State.TERMINATED, accept.getState());
    }

    private BluetoothServerSocket serverSocket() {
        return (BluetoothServerSocket) field("serverSocket");
    }

    private Thread acceptThread() {
        return (Thread) field("acceptThread");
    }

    private Object field(String name) {
        try {
            Field f = BluetoothQueueBridge.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(bridge);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }
}
