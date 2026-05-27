package com.shaforostoff.livequeueplayer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Embeddable controller that mirrors a remote player's queue over Bluetooth.
 * Owns a ListView (with swipe-to-remove and long-press-to-reorder) plus refresh,
 * stop and resume buttons. The hosting Activity provides the views, the shared
 * BluetoothController and dispatches {@code queue_state} / {@code play_state}
 * messages here.
 */
final class RemoteQueueController {

    private static final class TrackEntry {
        final int id;
        String name   = "";
        String title  = "";
        String artist = "";
        String date   = "";

        TrackEntry(int id) { this.id = id; }
    }

    private static final class SwipeState {
        float downX, downY;
        int   startPosition = -1;
        boolean handled;
        View swipingView;
        View contentView;

        void resetView() {
            if (contentView != null) { contentView.setTranslationX(0); contentView = null; }
            swipingView = null;
        }
    }

    private static final class DragState {
        int currentPosition = -1;
        boolean active;
        View ghostView;
        float touchOffsetX, touchOffsetY;

        void reset() {
            currentPosition = -1;
            active = false;
            ghostView = null;
            touchOffsetX = touchOffsetY = 0;
        }
    }

    private final Activity activity;
    private final BluetoothController btController;
    private final ListView queueList;
    private final View refreshButton;
    private final View stopButton;
    private final View playButton;
    private final View volumeButton;

    private final ArrayList<TrackEntry>   queueEntries = new ArrayList<>();
    private final SparseArray<TrackEntry> metaCache    = new SparseArray<>();

    private final QueueAdapter adapter;
    private final Handler uiHandler = new Handler();

    private int     currentId     = -1;
    private String  playbackState = "stopped";
    private int     draggingIndex = -1;
    private boolean scrollToBottomPending;
    private Runnable fadeEndRunnable;

    private PopupWindow volumePopup;
    private TextView    volumeValueText;
    private int         cachedVolumeMax   = 15;
    private int         cachedVolumeValue = -1;

    RemoteQueueController(Activity activity,
                          BluetoothController btController,
                          ListView queueList,
                          View refreshButton,
                          View stopButton,
                          View playButton,
                          View volumeButton) {
        this.activity = activity;
        this.btController = btController;
        this.queueList = queueList;
        this.refreshButton = refreshButton;
        this.stopButton = stopButton;
        this.playButton = playButton;
        this.volumeButton = volumeButton;

        adapter = new QueueAdapter();
        queueList.setAdapter(adapter);

        queueList.setOnItemClickListener((parent, view, position, id) -> {
            if ("playing".equals(playbackState)) return;
            if (position < 0 || position >= queueEntries.size()) return;
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("type", "play_track");
                cmd.put("id", queueEntries.get(position).id);
                btController.sendRaw(cmd.toString());
            } catch (Exception ignored) {}
        });

        stopButton.setOnClickListener(v -> btController.sendRaw("{\"type\":\"stop_playback\"}"));
        playButton.setOnClickListener(v -> btController.sendRaw("{\"type\":\"resume_playback\"}"));
        refreshButton.setOnClickListener(v -> requestQueue());
        volumeButton.setOnClickListener(v -> showVolumePopup());

        installGestureHandler(queueList);
        updatePlaybackButtons();
    }

    private void showVolumePopup() {
        if (volumePopup != null && volumePopup.isShowing()) {
            volumePopup.dismiss();
            return;
        }
        View content = LayoutInflater.from(activity).inflate(R.layout.popup_volume_slider, null);
        volumeValueText = content.findViewById(R.id.tv_volume_value);
        if (cachedVolumeValue >= 0) volumeValueText.setText(String.valueOf(cachedVolumeValue));

        Button btnUp   = content.findViewById(R.id.btn_volume_up);
        Button btnDown = content.findViewById(R.id.btn_volume_down);
        btnUp.setOnClickListener(v -> {
            int next = Math.min(cachedVolumeValue + 1, cachedVolumeMax);
            sendVolume(next);
        });
        btnDown.setOnClickListener(v -> {
            int next = Math.max(cachedVolumeValue - 1, 0);
            sendVolume(next);
        });

        float density = activity.getResources().getDisplayMetrics().density;
        int popupW = (int)(64 * density);
        volumePopup = new PopupWindow(content, popupW, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        volumePopup.setBackgroundDrawable(new ColorDrawable(0));
        volumePopup.setOutsideTouchable(true);
        volumePopup.setOnDismissListener(() -> {
            volumePopup = null;
            volumeValueText = null;
        });
        volumePopup.showAsDropDown(volumeButton);

        btController.sendRaw("{\"type\":\"request_volume\"}");
    }

    private void sendVolume(int value) {
        cachedVolumeValue = value;
        if (volumeValueText != null) volumeValueText.setText(String.valueOf(value));
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("type", "set_volume");
            cmd.put("value", value);
            btController.sendRaw(cmd.toString());
        } catch (Exception ignored) {}
    }

    void onVolumeStateReceived(JSONObject obj) {
        int value = obj.optInt("value", -1);
        int max   = obj.optInt("max",   -1);
        if (max   > 0)  cachedVolumeMax   = max;
        if (value >= 0) cachedVolumeValue = value;
        if (volumeValueText == null) return;
        if (value >= 0) volumeValueText.setText(String.valueOf(value));
    }

    void refreshAndScrollToBottom() {
        scrollToBottomPending = true;
        requestQueue();
    }

    void requestQueue() {
        int maxKnownId = 0, minKnownId = 0;
        for (int i = 0; i < metaCache.size(); i++) {
            int key = metaCache.keyAt(i);
            if (maxKnownId == 0 || key > maxKnownId) maxKnownId = key;
            if (minKnownId == 0 || key < minKnownId) minKnownId = key;
        }
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("type", "request_queue");
            if (maxKnownId > 0) {
                cmd.put("max_known_id", maxKnownId);
                cmd.put("min_known_id", minKnownId);
            }
            btController.sendRaw(cmd.toString());
        } catch (Exception ignored) {}
    }

    void onQueueStateReceived(JSONObject obj) {
        currentId     = obj.optInt("current_id", -1);
        playbackState = obj.optString("playback_state", "stopped");
        JSONArray tracks = obj.optJSONArray("tracks");
        queueEntries.clear();
        if (tracks != null) {
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject t = tracks.optJSONObject(i);
                if (t == null) continue;
                int id = t.optInt("id", 0);
                if (id <= 0) continue;
                TrackEntry entry = new TrackEntry(id);
                if (t.has("name")) {
                    entry.name   = t.optString("name",   "");
                    entry.title  = t.optString("title",  "");
                    entry.artist = t.optString("artist", "");
                    entry.date   = t.optString("date",   "");
                    metaCache.put(id, entry);
                } else {
                    TrackEntry cached = metaCache.get(id);
                    if (cached != null) {
                        entry.name   = cached.name;
                        entry.title  = cached.title;
                        entry.artist = cached.artist;
                        entry.date   = cached.date;
                    }
                }
                queueEntries.add(entry);
            }
        }
        applyStateUpdate(obj, scrollToBottomPending, true);
    }

    void onPlaybackStateReceived(JSONObject obj) {
        String newState = obj.optString("state", "stopped");
        boolean stateChanged = !newState.equals(playbackState);
        playbackState = newState;
        currentId    = obj.optInt("current_id", -1);
        // Only scroll to the current track when transitioning into "playing";
        // fading/stopped state changes should not cause a jarring jump.
        boolean scrollToCurrent = stateChanged && "playing".equals(playbackState);
        applyStateUpdate(obj, false, scrollToCurrent);
        if (stateChanged && ("playing".equals(playbackState) || "stopped".equals(playbackState))) {
            requestQueue();
        }
    }

    private void applyStateUpdate(JSONObject obj, boolean scrollToBottom, boolean scrollToCurrent) {
        applyFadeTimer(obj, playbackState);
        adapter.notifyDataSetChanged();
        updatePlaybackButtons();
        if (scrollToBottom && !queueEntries.isEmpty()) {
            scrollToBottomPending = false;
            final int last = queueEntries.size() - 1;
            queueList.post(() -> scrollTo(queueList, last));
        } else if (scrollToCurrent) {
            ensureCurrentVisible();
        }
    }

    private static void scrollTo(ListView list, int position) {
        int first = list.getFirstVisiblePosition();
        int last  = list.getLastVisiblePosition();
        if (position >= first && position <= last) return;
        if (Math.abs(position - first) > 8 && Math.abs(position - last) > 8)
            list.setSelection(position);
        else
            list.smoothScrollToPosition(position);
    }

    private void ensureCurrentVisible() {
        if (currentId < 0) return;
        int idx = -1;
        for (int i = 0; i < queueEntries.size(); i++) {
            if (queueEntries.get(i).id == currentId) { idx = i; break; }
        }
        if (idx < 0) return;
        final int target = idx;
        queueList.post(() -> scrollTo(queueList, target));
    }

    void onConnected() {
        requestQueue();
    }

    void shutdown() {
        if (fadeEndRunnable != null) {
            uiHandler.removeCallbacks(fadeEndRunnable);
            fadeEndRunnable = null;
        }
        if (volumePopup != null && volumePopup.isShowing()) {
            volumePopup.dismiss();
        }
    }

    private void applyFadeTimer(JSONObject obj, String state) {
        if (!"fading".equals(state)) {
            if (fadeEndRunnable != null) {
                uiHandler.removeCallbacks(fadeEndRunnable);
                fadeEndRunnable = null;
            }
            return;
        }
        if (fadeEndRunnable != null) return; // timer already running — don't reset with a stale duration
        long ms = obj.optLong("fade_duration_ms", 0L);
        if (ms <= 0) return; // server will push "stopped" when done
        fadeEndRunnable = () -> {
            fadeEndRunnable = null;
            playbackState = "stopped";
            updatePlaybackButtons();
            requestQueue();
        };
        uiHandler.postDelayed(fadeEndRunnable, ms);
    }

    private void updatePlaybackButtons() {
        stopButton.setVisibility("playing".equals(playbackState) ? View.VISIBLE : View.GONE);
        playButton.setVisibility("fading".equals(playbackState)  ? View.VISIBLE : View.GONE);
    }

    private void installGestureHandler(ListView list) {
        SwipeState swipeState     = new SwipeState();
        DragState  dragState      = new DragState();
        float      verticalSlop   = 40f * activity.getResources().getDisplayMetrics().density;
        float      horizontalSlop = 10f * activity.getResources().getDisplayMetrics().density;
        Runnable[] longPressRunnable = {null};
        int[]      dragOriginId   = {-1};

        list.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {

                case MotionEvent.ACTION_DOWN: {
                    swipeState.downX         = event.getX();
                    swipeState.downY         = event.getY();
                    swipeState.startPosition = list.pointToPosition((int) event.getX(), (int) event.getY());
                    swipeState.handled       = false;
                    swipeState.swipingView   = null;
                    swipeState.contentView   = null;
                    dragState.reset();
                    dragOriginId[0] = -1;
                    if (longPressRunnable[0] != null) {
                        uiHandler.removeCallbacks(longPressRunnable[0]);
                        longPressRunnable[0] = null;
                    }
                    if (swipeState.startPosition >= 0) {
                        int firstVisible = list.getFirstVisiblePosition();
                        int childIndex   = swipeState.startPosition - firstVisible;
                        if (childIndex >= 0 && childIndex < list.getChildCount()) {
                            swipeState.swipingView = list.getChildAt(childIndex);
                            swipeState.contentView = swipeState.swipingView.findViewById(R.id.swipe_content);
                            if (swipeState.contentView == null) swipeState.contentView = swipeState.swipingView;
                            int[] itemScreenPos = new int[2];
                            swipeState.swipingView.getLocationOnScreen(itemScreenPos);
                            dragState.touchOffsetX = event.getRawX() - itemScreenPos[0];
                            dragState.touchOffsetY = event.getRawY() - itemScreenPos[1];
                        }
                        int pos = swipeState.startPosition;
                        longPressRunnable[0] = () -> {
                            if (!swipeState.handled && !dragState.active && swipeState.swipingView != null) {
                                if (pos >= 0 && pos < queueEntries.size()
                                        && queueEntries.get(pos).id == currentId) {
                                    longPressRunnable[0] = null;
                                    return;
                                }
                                View src = swipeState.swipingView;
                                Bitmap bmp = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
                                src.draw(new Canvas(bmp));
                                ImageView ghost = new ImageView(activity);
                                ghost.setImageBitmap(bmp);
                                ghost.setAlpha(0.85f);
                                ghost.setElevation(8f * activity.getResources().getDisplayMetrics().density);
                                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                                int[] decorPos = new int[2];
                                decor.getLocationOnScreen(decorPos);
                                int[] itemPos = new int[2];
                                src.getLocationOnScreen(itemPos);
                                decor.addView(ghost, new FrameLayout.LayoutParams(src.getWidth(), src.getHeight()));
                                ghost.setX(itemPos[0] - decorPos[0]);
                                ghost.setY(itemPos[1] - decorPos[1]);
                                src.setAlpha(0f);
                                dragState.ghostView       = ghost;
                                dragState.currentPosition = pos;
                                dragState.active          = true;
                                draggingIndex             = pos;
                                if (pos >= 0 && pos < queueEntries.size()) {
                                    dragOriginId[0] = queueEntries.get(pos).id;
                                }
                                longPressRunnable[0] = null;
                                list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                list.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        };
                        uiHandler.postDelayed(longPressRunnable[0], 400L);
                    }
                    return false;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (dragState.active) {
                        if (dragState.ghostView != null) {
                            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                            int[] decorPos = new int[2];
                            decor.getLocationOnScreen(decorPos);
                            dragState.ghostView.setX(event.getRawX() - dragState.touchOffsetX - decorPos[0]);
                            dragState.ghostView.setY(event.getRawY() - dragState.touchOffsetY - decorPos[1]);
                        }
                        int targetPos = list.pointToPosition((int) event.getX(), (int) event.getY());
                        if (targetPos >= 0 && targetPos < queueEntries.size()
                                && targetPos != dragState.currentPosition) {
                            queueEntries.add(targetPos, queueEntries.remove(dragState.currentPosition));
                            dragState.currentPosition = targetPos;
                            draggingIndex             = targetPos;
                            adapter.notifyDataSetChanged();
                        }
                        return true;
                    }

                    if (swipeState.handled || swipeState.startPosition < 0) return swipeState.handled;
                    float dx = event.getX() - swipeState.downX;
                    float dy = event.getY() - swipeState.downY;

                    if (Math.abs(dx) > horizontalSlop || Math.abs(dy) > verticalSlop) {
                        if (longPressRunnable[0] != null) {
                            uiHandler.removeCallbacks(longPressRunnable[0]);
                            longPressRunnable[0] = null;
                        }
                    }
                    if (Math.abs(dy) > verticalSlop && Math.abs(dy) > Math.abs(dx)) {
                        swipeState.resetView();
                        swipeState.startPosition = -1;
                        return false;
                    }
                    if (dx < 0 && swipeState.swipingView != null) {
                        swipeState.contentView.setTranslationX(Math.max(dx, -swipeState.contentView.getWidth()));
                        list.getParent().requestDisallowInterceptTouchEvent(true);
                        if (swipeState.contentView.getWidth() > 0
                                && Math.abs(dx) >= swipeState.contentView.getWidth() / 2f) {
                            swipeState.handled = true;
                            swipeState.resetView();
                            int pos = swipeState.startPosition;
                            if (pos >= 0 && pos < queueEntries.size()) {
                                int trackId = queueEntries.get(pos).id;
                                queueEntries.remove(pos);
                                adapter.notifyDataSetChanged();
                                try {
                                    JSONObject cmd = new JSONObject();
                                    cmd.put("type", "remove_track");
                                    cmd.put("id", trackId);
                                    btController.sendRaw(cmd.toString());
                                } catch (Exception ignored) {}
                            }
                        }
                        return true;
                    }
                    return false;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (longPressRunnable[0] != null) {
                        uiHandler.removeCallbacks(longPressRunnable[0]);
                        longPressRunnable[0] = null;
                    }
                    if (dragState.active) {
                        if (dragState.ghostView != null) {
                            ((ViewGroup) activity.getWindow().getDecorView()).removeView(dragState.ghostView);
                            if (swipeState.swipingView != null) swipeState.swipingView.setAlpha(1f);
                        }
                        int finalPos = dragState.currentPosition;
                        int trackId  = dragOriginId[0];
                        dragState.reset();
                        draggingIndex = -1;
                        adapter.notifyDataSetChanged();
                        if (trackId >= 0 && event.getAction() != MotionEvent.ACTION_CANCEL) {
                            try {
                                JSONObject cmd = new JSONObject();
                                cmd.put("type", "move_track");
                                cmd.put("id", trackId);
                                cmd.put("to_position", finalPos);
                                btController.sendRaw(cmd.toString());
                            } catch (Exception ignored) {}
                        }
                        return true;
                    }
                    if (!swipeState.handled) v.performClick();
                    swipeState.resetView();
                    boolean handled = swipeState.handled;
                    swipeState.startPosition = -1;
                    swipeState.handled       = false;
                    return handled;
                }

                default:
                    return false;
            }
        });
    }

    private final class QueueAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(activity);
        private final int colorBackground;
        private final int colorCurrent;

        QueueAdapter() {
            TypedValue out = new TypedValue();
            activity.getTheme().resolveAttribute(android.R.attr.colorBackground, out, true);
            colorBackground = out.data;
            colorCurrent    = activity.getColor(R.color.queueProgressBackground);
        }

        @Override public int     getCount()         { return queueEntries.size(); }
        @Override public Object  getItem(int pos)   { return queueEntries.get(pos); }
        @Override public long    getItemId(int pos) { return queueEntries.get(pos).id; }
        @Override public boolean hasStableIds()     { return true; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_remote_queue_track, parent, false);
            }
            TrackEntry entry = queueEntries.get(position);

            TextView nameView   = convertView.findViewById(R.id.file_name);
            TextView artistView = convertView.findViewById(R.id.file_artist);
            View     metaRow    = convertView.findViewById(R.id.file_meta_row);
            View     content    = convertView.findViewById(R.id.swipe_content);

            convertView.setAlpha(position == draggingIndex ? 0f : 1f);
            content.setTranslationX(0);

            boolean isCurrent = (currentId >= 0 && entry.id == currentId);
            content.setBackgroundColor(isCurrent ? colorCurrent : colorBackground);

            String displayName = (!entry.title.isEmpty()) ? entry.title : entry.name;
            nameView.setText(displayName);

            boolean hasArtist = !entry.artist.isEmpty();
            artistView.setText(hasArtist ? entry.artist : "");
            metaRow.setVisibility(hasArtist ? View.VISIBLE : View.GONE);

            return convertView;
        }
    }
}
