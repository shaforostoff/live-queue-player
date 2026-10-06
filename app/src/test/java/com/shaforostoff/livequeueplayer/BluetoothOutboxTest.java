package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the link's writer thread sends next: a remote command never waits behind file traffic, a
 * file's own messages never overtake its bytes, and a sender ahead of the link is held back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class BluetoothOutboxTest {

  private final BluetoothQueueBridge.Outbox outbox = new BluetoothQueueBridge.Outbox();

  @Test
  public void controlMessagesJumpAheadOfFileTraffic() throws Exception {
    byte[] chunk1 = chunk(1), chunk2 = chunk(2), fileEnd = json('e'), play = json('p');
    outbox.putChunk(chunk1);
    outbox.putChunk(chunk2);
    outbox.add(fileEnd, true);
    outbox.add(play, false);

    assertArrayEquals(play, outbox.take());
    assertArrayEquals(chunk1, outbox.take());
    assertArrayEquals(chunk2, outbox.take());
    assertArrayEquals(fileEnd, outbox.take()); // after its bytes, not before
  }

  @Test
  public void aSenderAheadOfTheLinkWaitsForRoom() throws Exception {
    for (int i = 0; i < 4; i++) assertTrue(outbox.putChunk(chunk(i)));
    AtomicBoolean fifthIn = new AtomicBoolean();
    Thread sender = new Thread(() -> {
      try {
        fifthIn.set(outbox.putChunk(chunk(4)));
      } catch (InterruptedException ignored) {
      }
    });
    sender.start();
    sender.join(300);
    assertTrue("blocked while four are queued", sender.isAlive());

    outbox.take();
    sender.join(2_000);
    assertTrue(fifthIn.get());
    // File messages don't count against the chunk room, so an abort is never held up.
    assertTrue(outbox.add(json('a'), true));
  }

  @Test
  public void closingReleasesAWaitingSenderAndTheWriter() throws Exception {
    for (int i = 0; i < 4; i++) outbox.putChunk(chunk(i));
    AtomicBoolean result = new AtomicBoolean(true);
    Thread sender = new Thread(() -> {
      try {
        result.set(outbox.putChunk(chunk(9)));
      } catch (InterruptedException ignored) {
      }
    });
    sender.start();
    sender.join(200);

    outbox.close();
    sender.join(2_000);
    assertFalse(result.get());
    assertNull(outbox.take());
    assertFalse(outbox.add(json('x'), false));
    assertEquals(Thread.State.TERMINATED, sender.getState());
  }

  /** A framed chunk: length prefix, marker, 4-byte id, one byte of data. */
  private static byte[] chunk(int id) {
    return new byte[]{0, 0, 0, 6, 0x02, 0, 0, 0, (byte) id, 7};
  }

  /** A framed one-character JSON message, told apart by its character. */
  private static byte[] json(char c) {
    return new byte[]{0, 0, 0, 2, '{', (byte) c};
  }
}
