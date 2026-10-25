package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Two bridges joined by a loopback socket pair standing in for RFCOMM: the first file chunk asks
 * the host for a Wi-Fi path, both ends see the file link drop and come back, and file traffic then
 * keeps flowing — over the new TCP link.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class BluetoothWifiTransferTest {

  private final BluetoothQueueBridge sender = new BluetoothQueueBridge(RuntimeEnvironment.getApplication());
  private final BluetoothQueueBridge host = new BluetoothQueueBridge(RuntimeEnvironment.getApplication());

  @After
  public void tearDown() {
    sender.disconnect();
    host.disconnect();
    BluetoothQueueBridge.offerLoopbackForTest = false;
  }

  @Test
  public void fileTrafficMovesToWifiAndKeepsFlowing() throws Exception {
    BluetoothQueueBridge.offerLoopbackForTest = true;
    Events atSender = new Events(), atHost = new Events();
    sender.setFileSink(atSender);
    host.setFileSink(atHost);
    try (ServerSocket server = new ServerSocket(0)) {
      Socket a = new Socket("127.0.0.1", server.getLocalPort());
      Socket b = server.accept();
      sender.attachLink(a, a.getInputStream(), a.getOutputStream(), "bt");
      host.attachLink(b, b.getInputStream(), b.getOutputStream(), "bt");
    }
    assertEquals("up", atSender.next());
    assertEquals("up", atHost.next());

    assertTrue(sender.sendFileChunk(7, new byte[]{1, 2, 3}, 3));
    assertEquals("chunk 7", atHost.next()); // over Bluetooth, while Wi-Fi is set up

    // Wi-Fi attached: to each sink, the file link dropped and came back.
    assertEquals("lost", atSender.next());
    assertEquals("up", atSender.next());
    assertEquals("lost", atHost.next());
    assertEquals("up", atHost.next());

    assertTrue(sender.send("file_begin", "id", 8));
    assertTrue(sender.sendFileChunk(8, new byte[4096], 4096));
    assertEquals("file_begin 8", atHost.next());
    assertEquals("chunk 8", atHost.next());
    assertTrue(host.send("file_ready", "id", 8));
    assertEquals("file_ready 8", atSender.next());
  }

  private static final class Events implements BluetoothQueueBridge.FileSink {
    final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

    String next() throws InterruptedException {
      String e = queue.poll(10, TimeUnit.SECONDS);
      return e != null ? e : "timeout";
    }

    @Override public void onFileMessage(String type, JSONObject obj) { queue.add(type + " " + obj.optInt("id")); }
    @Override public void onFileChunk(byte[] frame) { queue.add("chunk " + BluetoothQueueBridge.fileChunkId(frame)); }
    @Override public void onLinkUp() { queue.add("up"); }
    @Override public void onLinkLost() { queue.add("lost"); }
    @Override public void onLinkAbandoned() { queue.add("abandoned"); }
  }
}
