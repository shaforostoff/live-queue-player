package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

/** A track pushed later is placed beside its neighbours in the request that asked for it. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class ArrivalOrderTest {

  private final ArrivalOrder order = new ArrivalOrder();

  @Test
  public void anArrivalSitsBetweenItsQueuedNeighbours() {
    // A and D were queued at once (entries 10, 11); B and C are still to come.
    order.record(Arrays.asList("A/a.mp3", "A/b.mp3", "A/c.mp3", "A/d.mp3"), new int[]{10, 0, 0, 11});

    int[][] b = order.arrive("A/b.mp3", 20);
    assertArrayEquals(new int[]{10}, b[0]);
    assertArrayEquals(new int[]{11}, b[1]);

    // C now follows B, which has arrived, not A.
    int[][] c = order.arrive("A/c.mp3", 21);
    assertArrayEquals(new int[]{20, 10}, c[0]);
    assertArrayEquals(new int[]{11}, c[1]);
  }

  @Test
  public void pathsMatchWhateverTheirSpellingOrForm() {
    order.record(Arrays.asList("x.mp3", "Niño/song.mp3"), new int[]{5, 0});

    int[][] around = order.arrive("/Niño//song.mp3", 6);
    assertArrayEquals(new int[]{5}, around[0]);
  }

  @Test
  public void anUnrequestedOrRepeatedArrivalHasNoNeighbours() {
    order.record(Arrays.asList("a.mp3", "b.mp3"), new int[]{1, 0});

    assertArrayEquals(new int[0], order.arrive("other.mp3", 7)[0]);
    assertArrayEquals(new int[]{1}, order.arrive("b.mp3", 8)[0]);
    assertArrayEquals(new int[0], order.arrive("b.mp3", 9)[0]); // already placed
  }
}
