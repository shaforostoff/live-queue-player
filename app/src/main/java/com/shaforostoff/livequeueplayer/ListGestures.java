package com.shaforostoff.livequeueplayer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;

/**
 * Touch handling shared by the app's lists: swipe a row left or right past half its width to act on
 * it, and — where enabled — hold a row still to pick it up and drag it to a new position. Used by
 * the file browser (swipe only), the local play queue and the remote queue mirror.
 */
final class ListGestures {

    interface PositionTest { boolean test(int position); }
    interface PositionAction { void run(int position); }
    interface RowAction { void run(View row, int position); }
    interface MoveAction { void move(int from, int to); }
    interface DropAction { void drop(int position, boolean cancelled); }

    /**
     * Added to the system long-press timeout (so the accessibility touch-and-hold delay is honoured)
     * before a row starts dragging: reordering is rare next to scrolling, and a plain long-press does
     * nothing else here.
     */
    private static final long DRAG_ARM_EXTRA_MS = 250L;

    private final Activity activity;
    private final ListView list;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float verticalSlop;
    private final float horizontalSlop;
    // The drag may only arm while the finger is essentially still. Past the system touch slop the
    // ListView has already committed to scrolling, so anything beyond it - however slowly it got
    // there - is a scroll, never a hold.
    private final float dragArmSlop;

    private PositionTest canSwipe = position -> true;
    private PositionAction onSwipeLeft;
    private PositionAction onSwipeRight;
    private RowAction onRowTouched;
    private PositionTest canDrag;
    private PositionTest canDropOnto;
    private MoveAction onMove;
    private DropAction onDrop;

    // -- current gesture --
    private float downX, downY;
    private int startPosition = -1;
    private boolean handled;
    private boolean swiping;
    // Set when a swipe gesture moved the row, so the ListView's item click (fired on finger
    // release) is ignored even if the swipe didn't go far enough to trigger its action.
    private boolean suppressClick;
    private View row;
    private View content;           // the part of the row that slides; null when it can't swipe
    private final int[] downScroll = new int[2]; // firstVisiblePosition + its top offset at DOWN
    private Runnable armDrag;
    private View ghost;
    private float touchOffsetX, touchOffsetY;
    private int dragPosition = -1;

    ListGestures(Activity activity, ListView list) {
        this.activity = activity;
        this.list = list;
        float density = activity.getResources().getDisplayMetrics().density;
        verticalSlop = 40f * density;
        horizontalSlop = 20f * density;
        dragArmSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        list.setOnTouchListener(this::onTouch);
    }

    ListGestures swipeIf(PositionTest test) { canSwipe = test; return this; }
    ListGestures onSwipeLeft(PositionAction action) { onSwipeLeft = action; return this; }
    ListGestures onSwipeRight(PositionAction action) { onSwipeRight = action; return this; }
    /** Called on touch-down on a row that may swipe, e.g. to label its swipe hints. */
    ListGestures onRowTouched(RowAction action) { onRowTouched = action; return this; }

    /** Hold-to-drag reordering: {@code onMove} reorders the data as the row passes over others, and
     *  {@code onDrop} runs once the finger lifts with the row at its final position. */
    ListGestures enableDrag(PositionTest canDrag, PositionTest canDropOnto, MoveAction onMove,
                            DropAction onDrop) {
        this.canDrag = canDrag;
        this.canDropOnto = canDropOnto;
        this.onMove = onMove;
        this.onDrop = onDrop;
        return this;
    }

    /** Position of the row being dragged (its adapter hides it under the ghost), or -1. */
    int dragPosition() {
        return dragPosition;
    }

    /** True while {@code position} is mid-swipe, so a refresh must not rebind it. */
    boolean isSwiping(int position) {
        return swiping && position == startPosition;
    }

    /** For the item-click listener: true (once) when this click is the tail of a swipe. */
    boolean consumeSuppressedClick() {
        boolean suppressed = suppressClick;
        suppressClick = false;
        return suppressed;
    }

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                onDown(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                return onMoveEvent(e);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return onUp(v, e.getAction() == MotionEvent.ACTION_CANCEL);
            default:
                return false;
        }
    }

    private void onDown(MotionEvent e) {
        cancelDragArm();
        dragPosition = -1;
        ghost = null;
        downX = e.getX();
        downY = e.getY();
        downScroll[0] = list.getFirstVisiblePosition();
        downScroll[1] = list.getChildCount() > 0 ? list.getChildAt(0).getTop() : 0;
        startPosition = list.pointToPosition((int) downX, (int) downY);
        handled = false;
        swiping = false;
        suppressClick = false;
        row = null;
        content = null;
        if (startPosition < 0) return;
        int child = startPosition - list.getFirstVisiblePosition();
        if (child < 0 || child >= list.getChildCount()) return;
        row = list.getChildAt(child);
        if (canSwipe.test(startPosition)) {
            content = row.findViewById(R.id.swipe_content);
            if (content == null) content = row;
            if (onRowTouched != null) onRowTouched.run(row, startPosition);
        }
        if (onDrop != null) {
            int[] rowPos = new int[2];
            row.getLocationOnScreen(rowPos);
            touchOffsetX = e.getRawX() - rowPos[0];
            touchOffsetY = e.getRawY() - rowPos[1];
            int position = startPosition;
            armDrag = () -> {
                armDrag = null;
                startDrag(position);
            };
            handler.postDelayed(armDrag, ViewConfiguration.getLongPressTimeout() + DRAG_ARM_EXTRA_MS);
        }
    }

    private void startDrag(int position) {
        if (handled || row == null || !canDrag.test(position)) return;
        // The list scrolled under the finger (slow drag, or a fling still settling): the row is no
        // longer where it was touched.
        if (list.getFirstVisiblePosition() != downScroll[0]
                || (list.getChildCount() > 0 && list.getChildAt(0).getTop() != downScroll[1])) {
            return;
        }
        Bitmap bmp = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888);
        row.draw(new Canvas(bmp));
        ImageView image = new ImageView(activity);
        image.setImageBitmap(bmp);
        image.setAlpha(0.85f);
        image.setElevation(8f * activity.getResources().getDisplayMetrics().density);
        int[] decorPos = decorPosition();
        int[] rowPos = new int[2];
        row.getLocationOnScreen(rowPos);
        decor().addView(image, new FrameLayout.LayoutParams(row.getWidth(), row.getHeight()));
        image.setX(rowPos[0] - decorPos[0]);
        image.setY(rowPos[1] - decorPos[1]);
        row.setAlpha(0f);
        ghost = image;
        dragPosition = position;
        list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        list.getParent().requestDisallowInterceptTouchEvent(true);
        cancelListTouch();
    }

    private boolean onMoveEvent(MotionEvent e) {
        if (dragPosition >= 0) {
            int[] decorPos = decorPosition();
            ghost.setX(e.getRawX() - touchOffsetX - decorPos[0]);
            ghost.setY(e.getRawY() - touchOffsetY - decorPos[1]);
            int target = list.pointToPosition((int) e.getX(), (int) e.getY());
            if (target >= 0 && target < list.getCount() && target != dragPosition
                    && canDropOnto.test(target)) {
                onMove.move(dragPosition, target);
                dragPosition = target;
            }
            return true;
        }
        if (handled || startPosition < 0) return handled;
        float dx = e.getX() - downX;
        float dy = e.getY() - downY;
        if (Math.hypot(dx, dy) > dragArmSlop) cancelDragArm();
        if (Math.abs(dy) > verticalSlop && Math.abs(dy) > Math.abs(dx)) {
            // Dominantly vertical past the slop: a list scroll, not a swipe.
            resetRow();
            startPosition = -1;
            return false;
        }
        return swipe(dx, true, onSwipeLeft) || swipe(dx, false, onSwipeRight);
    }

    /** Slides the row for one direction and fires {@code action} once it is dragged past half its
     *  width. Returns true if the touch was consumed as a swipe. */
    private boolean swipe(float dx, boolean toLeft, PositionAction action) {
        if (content == null || action == null) return false;
        if (toLeft ? dx >= -horizontalSlop : dx <= horizontalSlop) return false;
        swiping = true;
        float eff = toLeft ? dx + horizontalSlop : dx - horizontalSlop;
        int w = content.getWidth();
        content.setTranslationX(toLeft ? Math.max(eff, -w) : Math.min(eff, w));
        list.getParent().requestDisallowInterceptTouchEvent(true);
        if (w > 0 && Math.abs(eff) >= w / 2f) {
            handled = true;
            int position = startPosition;
            resetRow();
            action.run(position);
        }
        return true;
    }

    private boolean onUp(View v, boolean cancelled) {
        cancelDragArm();
        if (dragPosition >= 0) {
            decor().removeView(ghost);
            ghost = null;
            if (row != null) row.setAlpha(1f);
            int position = dragPosition;
            dragPosition = -1;
            onDrop.drop(position, cancelled);
            return true;
        }
        if (swiping) suppressClick = true;
        if (!handled) v.performClick();
        resetRow();
        boolean wasHandled = handled;
        startPosition = -1;
        handled = false;
        return wasHandled;
    }

    private void resetRow() {
        if (content != null) {
            content.setTranslationX(0);
            content = null;
        }
        swiping = false;
    }

    private void cancelDragArm() {
        if (armDrag != null) {
            handler.removeCallbacks(armDrag);
            armDrag = null;
        }
    }

    private ViewGroup decor() {
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    private int[] decorPosition() {
        int[] pos = new int[2];
        decor().getLocationOnScreen(pos);
        return pos;
    }

    /**
     * Hands the ListView an ACTION_CANCEL once a drag takes over the gesture. The list saw our
     * ACTION_DOWN and nothing after it, so without this it keeps the row pressed, keeps its tap
     * callbacks pending and stays in touch mode for the rest of the drag. Fed to onTouchEvent rather
     * than dispatchTouchEvent so it bypasses this very listener and the drag state survives.
     */
    private void cancelListTouch() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        list.onTouchEvent(cancel);
        cancel.recycle();
    }
}
