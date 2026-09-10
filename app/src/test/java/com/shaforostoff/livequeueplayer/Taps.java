package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.time.Duration;

/**
 * Touch gestures for the equalizer dialog tests. A double tap is a touch gesture rather than a
 * click, so it has to be dispatched as real {@link MotionEvent}s — {@code performClick()} never
 * reaches a {@link android.view.GestureDetector}.
 *
 * <p>Robolectric's looper is paused, which is what makes the deferred single tap observable: it is
 * confirmed only once the clock is advanced past the double-tap timeout, so {@link #idle()} is the
 * difference between "tapped once" and "tapped once and nothing else is coming".
 */
final class Taps {

  private static final long TAP_MS = 20;

  private Taps() {
  }

  static void doubleTap(View view) {
    long down = SystemClock.uptimeMillis();
    tap(view, down);
    tap(view, down + 80);       // inside the double-tap timeout, past its 40 ms floor
  }

  static void singleTap(View view) {
    tap(view, SystemClock.uptimeMillis());
    idle();
  }

  /** Run out the double-tap timeout so a deferred single tap gets confirmed. */
  static void idle() {
    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
  }

  private static void tap(View view, long downTime) {
    assertTrue("the view did not take the touch down",
        view.dispatchTouchEvent(motion(downTime, downTime, MotionEvent.ACTION_DOWN)));
    view.dispatchTouchEvent(motion(downTime, downTime + TAP_MS, MotionEvent.ACTION_UP));
  }

  private static MotionEvent motion(long downTime, long eventTime, int action) {
    return MotionEvent.obtain(downTime, eventTime, action, 1f, 1f, 0);
  }
}
