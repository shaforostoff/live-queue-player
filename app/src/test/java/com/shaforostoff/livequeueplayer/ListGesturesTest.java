package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Drives {@link ListGestures} with real MotionEvents on a laid-out ListView: a swipe past half the
 * row fires its action, a short one does not but still swallows the release click, a vertical move
 * is left to the list, and holding a row still picks it up and drags it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ListGesturesTest {

  private static final int ROW_H = 100;
  private static final int WIDTH = 1000;

  private ListView list;
  private ListGestures gestures;
  private final List<String> items = new ArrayList<>(Arrays.asList("a", "b", "c", "d", "e"));
  private final List<String> log = new ArrayList<>();
  private long downTime;

  @Before
  public void setUp() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    list = new ListView(activity);
    list.setAdapter(new ArrayAdapter<String>(activity, 0, items) {
      @Override public View getView(int position, View convertView, ViewGroup parent) {
        TextView v = convertView != null ? (TextView) convertView : new TextView(activity);
        v.setText(getItem(position));
        v.setLayoutParams(new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW_H));
        return v;
      }
    });
    list.setOnItemClickListener((p, v, pos, id) -> {
      if (!gestures.consumeSuppressedClick()) log.add("click " + pos);
    });
    activity.setContentView(list, new FrameLayout.LayoutParams(WIDTH, ROW_H * 5));
    gestures = new ListGestures(activity, list)
        .swipeIf(pos -> pos != 4)
        .onSwipeLeft(pos -> log.add("left " + pos))
        .onSwipeRight(pos -> log.add("right " + pos))
        .enableDrag(pos -> pos != 0, target -> true,
            (from, to) -> {
              items.add(to, items.remove(from));
              log.add("move " + from + ">" + to);
            },
            (pos, cancelled) -> log.add("drop " + pos + (cancelled ? " cancelled" : "")));
    layout();
  }

  @Test
  public void swipePastHalfFiresOnce() {
    down(500, 150);
    move(450, 150);
    move(-100, 150);
    move(-200, 150);
    up(-200, 150);
    assertEquals(Arrays.asList("left 1"), log);

    down(100, 250);
    move(800, 250);
    up(800, 250);
    assertEquals(Arrays.asList("left 1", "right 2"), log);
  }

  @Test
  public void plainTapClicks() {
    down(500, 150);
    up(500, 150);
    idle();
    assertEquals(Arrays.asList("click 1"), log);
  }

  @Test
  public void shortSwipeFiresNothingAndSwallowsTheClick() {
    down(500, 150);
    move(400, 150);           // past the slop, short of half the row
    up(400, 150);
    idle();
    assertEquals(new ArrayList<String>(), log);
    assertFalse(gestures.isSwiping(1));
  }

  @Test
  public void verticalMoveIsAScroll() {
    down(500, 150);
    move(480, 300);
    assertFalse(gestures.isSwiping(1));
    up(480, 300);
    idle();
    assertFalse(log.contains("left 1"));
  }

  @Test
  public void rowThatMayNotSwipeStaysPut() {
    down(500, 450);
    move(-200, 450);
    up(-200, 450);
    assertEquals(new ArrayList<String>(), log);
  }

  @Test
  public void holdThenDragReordersAndDrops() {
    down(500, 150);
    idle();                   // past the long-press timeout + margin: picked up
    assertEquals(1, gestures.dragPosition());
    move(500, 250);
    move(500, 350);
    up(500, 350);
    assertEquals(Arrays.asList("move 1>2", "move 2>3", "drop 3"), log);
    assertEquals(Arrays.asList("a", "c", "d", "b", "e"), items);
    assertEquals(-1, gestures.dragPosition());
  }

  @Test
  public void undraggableRowIsNotPickedUp() {
    down(500, 50);
    idle();
    assertEquals(-1, gestures.dragPosition());
    up(500, 50);
  }

  @Test
  public void movingBeforeTheHoldCompletesCancelsTheDrag() {
    down(500, 150);
    move(500, 190);           // past the touch slop
    idle();
    assertEquals(-1, gestures.dragPosition());
    up(500, 190);
  }

  // ---- helpers ----

  private void layout() {
    list.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(ROW_H * 5, View.MeasureSpec.EXACTLY));
    list.layout(0, 0, WIDTH, ROW_H * 5);
    assertTrue(list.getChildCount() > 0);
  }

  private void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
    layout();
  }

  private void down(float x, float y) {
    downTime = SystemClock.uptimeMillis();
    dispatch(MotionEvent.ACTION_DOWN, x, y);
  }

  private void move(float x, float y) {
    dispatch(MotionEvent.ACTION_MOVE, x, y);
    layout();
  }

  private void up(float x, float y) {
    dispatch(MotionEvent.ACTION_UP, x, y);
    layout();
  }

  private void dispatch(int action, float x, float y) {
    MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
    list.dispatchTouchEvent(e);
    e.recycle();
  }
}
