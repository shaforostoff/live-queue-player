package com.shaforostoff.livequeueplayer;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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

    private final Activity activity;
    private final BluetoothController btController;
    private final ListView queueList;
    private final View refreshButton;
    private final View stopButton;
    private final View playButton;
    private final View volumeButton;
    private final View eqButton;

    private final ArrayList<TrackEntry>   queueEntries = new ArrayList<>();
    private final SparseArray<TrackEntry> metaCache    = new SparseArray<>();

    private final QueueAdapter adapter;
    private final ListGestures gestures;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private int     currentId     = -1;
    private int     anchorId      = 0;     // entry id of the remote insert anchor (0 = none)
    private String  playbackState = "stopped";
    private boolean scrollToNewTrackPending;
    private Runnable fadeEndRunnable;

    private PopupWindow volumePopup;
    private TextView    volumeValueText;
    private int         cachedVolumeMax   = 15;
    private int         cachedVolumeValue = -1;

    private EqualizerDialog.Handle eqDialog;
    private boolean eqDeterminate;            // true once any eq_state has arrived
    private boolean eqParametric;             // true when the host runs the DynamicsProcessing path
    private int     eqNumBands;
    private short   eqMin = -1500, eqMax = 1500;          // gain range, millibels
    private int     eqFreqMinHz = 30, eqFreqMaxHz = 16000; // adjustable freq range (parametric)
    private int[]   eqFreqs  = new int[0];    // milliHz, for display
    private short[] eqLevels = new short[0];  // gain, millibels
    private boolean eqEnabled;
    // Section model, populated when the host sends a "sections" array. Empty against a host that
    // predates it, in which case the band cache above is what the dialog renders.
    private ParametricEq.Section[] eqSections = new ParametricEq.Section[0];
    private int[] eqSectionFreqMin = new int[0];
    private int[] eqSectionFreqMax = new int[0];
    // Defaults as the host defines them, for double-tap-to-reset. 0 means the host never said, which
    // a host predating the feature will not — the reset then does nothing rather than inventing a
    // value this end has no business choosing. Gain is exempt: flat is 0 on every host there is.
    private int[] eqSectionFreqDefault = new int[0];
    private int[] eqSectionQDefault = new int[0];
    private int   eqQMin = ParametricEqSettings.Q_MIN_MILLI;
    private int   eqQMax = ParametricEqSettings.Q_MAX_MILLI;

    RemoteQueueController(Activity activity,
                          BluetoothController btController,
                          ListView queueList,
                          View refreshButton,
                          View stopButton,
                          View playButton,
                          View volumeButton,
                          View eqButton) {
        this.activity = activity;
        this.btController = btController;
        this.queueList = queueList;
        this.refreshButton = refreshButton;
        this.stopButton = stopButton;
        this.playButton = playButton;
        this.volumeButton = volumeButton;
        this.eqButton = eqButton;

        adapter = new QueueAdapter();
        queueList.setAdapter(adapter);

        gestures = new ListGestures(activity, queueList)
                .onSwipeLeft(this::removeAt)
                .onSwipeRight(this::toggleAnchorAt)
                // The playing track can't be picked up; the host decides where the rest may go.
                .enableDrag(pos -> pos < queueEntries.size() && queueEntries.get(pos).id != currentId,
                        target -> true,
                        (from, to) -> {
                            queueEntries.add(to, queueEntries.remove(from));
                            adapter.notifyDataSetChanged();
                        },
                        (pos, cancelled) -> {
                            adapter.notifyDataSetChanged();
                            if (!cancelled && pos < queueEntries.size()) {
                                btController.send("move_track", "id", queueEntries.get(pos).id, "to_position", pos);
                            }
                        });

        queueList.setOnItemClickListener((parent, view, position, id) -> {
            if (gestures.consumeSuppressedClick()) return;
            if ("playing".equals(playbackState)) return;
            if (position < 0 || position >= queueEntries.size()) return;
            btController.send("play_track", "id", queueEntries.get(position).id);
        });

        stopButton.setOnClickListener(v -> btController.send("stop_playback"));
        playButton.setOnClickListener(v -> btController.send("resume_playback"));
        refreshButton.setOnClickListener(v -> requestQueue());
        volumeButton.setOnClickListener(v -> showVolumePopup());
        eqButton.setOnClickListener(v -> showEqDialog());

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

        btController.send("request_volume");
    }

    private void sendVolume(int value) {
        cachedVolumeValue = value;
        if (volumeValueText != null) volumeValueText.setText(String.valueOf(value));
        btController.send("set_volume", "value", value);
    }

    void onVolumeStateReceived(JSONObject obj) {
        int value = obj.optInt("value", -1);
        int max   = obj.optInt("max",   -1);
        if (max   > 0)  cachedVolumeMax   = max;
        if (value >= 0) cachedVolumeValue = value;
        if (volumeValueText == null) return;
        if (value >= 0) volumeValueText.setText(String.valueOf(value));
    }

    private void showEqDialog() {
        if (eqDialog != null && eqDialog.isShowing()) return;
        eqDialog = EqualizerDialog.show(activity, new RemoteEqSink());
        btController.send("request_eq");
    }

    void onEqStateReceived(JSONObject obj) {
        eqDeterminate = true;
        eqParametric = "parametric".equals(obj.optString("mode", "graphic"));
        eqNumBands = obj.optInt("num_bands", 0);
        eqEnabled = obj.optBoolean("enabled", eqEnabled);
        JSONArray secs = obj.optJSONArray("sections");
        if (secs != null) {
            // Section host: the real parametric model. Ranges travel with it so this end clamps its
            // optimistic updates exactly as the host will.
            eqMin = (short) obj.optInt("gain_min", eqMin);
            eqMax = (short) obj.optInt("gain_max", eqMax);
            eqQMin = obj.optInt("q_min", eqQMin);
            eqQMax = obj.optInt("q_max", eqQMax);
            int n = secs.length();
            ParametricEq.Section[] parsed = new ParametricEq.Section[n];
            int[] freqMin = new int[n];
            int[] freqMax = new int[n];
            int[] freqDefault = new int[n];
            int[] qDefault = new int[n];
            for (int i = 0; i < n; i++) {
                JSONObject o = secs.optJSONObject(i);
                if (o == null) {
                    parsed[i] = new ParametricEq.Section(ParametricEq.TYPE_PEAK, 1000, 1000, 0, false);
                    freqMin[i] = 20;
                    freqMax[i] = 20000;
                    continue;
                }
                parsed[i] = new ParametricEq.Section(
                        o.optInt("type", ParametricEq.TYPE_PEAK),
                        o.optInt("freq", 1000),
                        o.optInt("q", 1000),
                        o.optInt("gain", 0),
                        o.optBoolean("on", true));
                freqMin[i] = o.optInt("freq_min", 20);
                freqMax[i] = o.optInt("freq_max", 20000);
                freqDefault[i] = o.optInt("freq_default", 0);
                qDefault[i] = o.optInt("q_default", 0);
            }
            eqSections = parsed;
            eqSectionFreqMin = freqMin;
            eqSectionFreqMax = freqMax;
            eqSectionFreqDefault = freqDefault;
            eqSectionQDefault = qDefault;
        } else if (eqParametric) {
            // Host on the superseded six-band partition model: freqs in Hz + gains in millibels.
            eqSections = new ParametricEq.Section[0];
            eqMin = (short) obj.optInt("gain_min", eqMin);
            eqMax = (short) obj.optInt("gain_max", eqMax);
            eqFreqMinHz = obj.optInt("freq_min", eqFreqMinHz);
            eqFreqMaxHz = obj.optInt("freq_max", eqFreqMaxHz);
            if (eqNumBands > 0) {
                JSONArray freqs = obj.optJSONArray("freqs"); // Hz
                JSONArray gains = obj.optJSONArray("gains"); // millibels
                eqFreqs  = new int[eqNumBands];
                eqLevels = new short[eqNumBands];
                for (int i = 0; i < eqNumBands; i++) {
                    int hz = freqs != null ? freqs.optInt(i, 0) : 0;
                    eqFreqs[i]  = hz * 1000; // store milliHz for display
                    eqLevels[i] = (short) (gains != null ? gains.optInt(i, 0) : 0);
                }
            }
        } else {
            // Graphic host: freqs in milliHz + levels in millibels.
            eqSections = new ParametricEq.Section[0];
            eqMin = (short) obj.optInt("min", eqMin);
            eqMax = (short) obj.optInt("max", eqMax);
            if (eqNumBands > 0) {
                JSONArray freqs  = obj.optJSONArray("freqs");
                JSONArray levels = obj.optJSONArray("levels");
                eqFreqs  = new int[eqNumBands];
                eqLevels = new short[eqNumBands];
                for (int i = 0; i < eqNumBands; i++) {
                    eqFreqs[i]  = freqs  != null ? freqs.optInt(i, 0)  : 0;
                    eqLevels[i] = (short) (levels != null ? levels.optInt(i, 0) : 0);
                }
            }
        }
        if (eqDialog != null) eqDialog.refresh();
    }

    /**
     * Remote-playback equalizer: caches state pushed by the server and sends changes back.
     *
     * <p>Every change is applied to the local cache first so the rows and the response curve move
     * under the finger, then sent; the host's {@code eq_state} echo is authoritative and corrects
     * any drift. Two models are served, decided by what the host sent: the section model, and the
     * older band model for a host that predates it.
     */
    private final class RemoteEqSink extends EqualizerDialog.EqSink {
        @Override boolean isEnabled() { return eqEnabled; }

        @Override void setEnabled(boolean enabled) {
            eqEnabled = enabled;
            btController.send("set_eq", "enabled", enabled);
        }

        @Override CharSequence statusText() {
            if (eqNumBands > 0 || eqSections.length > 0) return null;
            return activity.getString(eqDeterminate ? R.string.eq_unavailable : R.string.eq_loading);
        }

        // --- section model ---------------------------------------------------------------------

        @Override int sectionCount() { return eqSections.length; }

        @Override ParametricEq.Section section(int slot) {
            return slot >= 0 && slot < eqSections.length ? eqSections[slot] : null;
        }

        @Override int sectionLabelRes(int slot) { return ParametricEqSettings.labelRes(slot); }

        @Override void nudgeGain(int slot, int deltaMillibels) {
            ParametricEq.Section s = section(slot);
            if (s == null) return;
            int gain = Math.max(eqMin, Math.min(eqMax, s.gainMb + deltaMillibels));
            eqSections[slot] = s.withGain(gain);
            btController.send("set_eq", "section", slot, "gain", gain);
        }

        @Override void nudgeFreq(int slot, int direction) {
            ParametricEq.Section s = section(slot);
            if (s == null) return;
            int loHz = slot < eqSectionFreqMin.length ? eqSectionFreqMin[slot] : 20;
            int hiHz = slot < eqSectionFreqMax.length ? eqSectionFreqMax[slot] : 20000;
            int hz = ParametricEqSettings.stepFreqHz(s.freqHz, direction, loHz, hiHz);
            eqSections[slot] = s.withFreq(hz);
            btController.send("set_eq", "section", slot, "freq", hz);
        }

        @Override void nudgeQ(int slot, int direction) {
            ParametricEq.Section s = section(slot);
            if (s == null) return;
            int q = ParametricEqSettings.stepQMilli(s.qMilli, direction);
            q = Math.max(eqQMin, Math.min(Math.max(eqQMin, eqQMax), q));
            eqSections[slot] = s.withQ(q);
            btController.send("set_eq", "section", slot, "q", q);
        }

        @Override void resetGain(int slot) {
            ParametricEq.Section s = section(slot);
            if (s == null) return;
            int gain = Math.max(eqMin, Math.min(eqMax, ParametricEqSettings.DEFAULT_GAIN_MILLIBELS));
            eqSections[slot] = s.withGain(gain);
            btController.send("set_eq", "section", slot, "gain", gain);
        }

        @Override void resetFreq(int slot) {
            ParametricEq.Section s = section(slot);
            int hz = slot < eqSectionFreqDefault.length ? eqSectionFreqDefault[slot] : 0;
            if (s == null || hz <= 0) return;
            eqSections[slot] = s.withFreq(hz);
            btController.send("set_eq", "section", slot, "freq", hz);
        }

        @Override void resetQ(int slot) {
            ParametricEq.Section s = section(slot);
            int q = slot < eqSectionQDefault.length ? eqSectionQDefault[slot] : 0;
            if (s == null || q <= 0) return;
            eqSections[slot] = s.withQ(q);
            btController.send("set_eq", "section", slot, "q", q);
        }

        @Override void setSectionOn(int slot, boolean on) {
            ParametricEq.Section s = section(slot);
            if (s == null || s.on == on) return;
            eqSections[slot] = s.withOn(on);
            btController.send("set_eq", "section", slot, "on", on);
        }

        // --- band model (host predating the section model) --------------------------------------

        @Override int numBands() { return eqSections.length > 0 ? 0 : eqNumBands; }

        @Override int centerFreqMilliHz(int band) {
            return band < eqFreqs.length ? eqFreqs[band] : 0;
        }

        @Override short bandLevel(int band) {
            return band < eqLevels.length ? eqLevels[band] : 0;
        }

        @Override void nudgeBand(int band, int deltaMillibels) {
            if (band < 0 || band >= eqLevels.length) return;
            int level = Math.max(eqMin, Math.min(eqMax, eqLevels[band] + deltaMillibels));
            eqLevels[band] = (short) level;
            btController.send("set_eq", "band", band, "value", level);
        }

        @Override void resetBand(int band) {
            if (band < 0 || band >= eqLevels.length) return;
            int level = Math.max(eqMin, Math.min(eqMax, 0));
            eqLevels[band] = (short) level;
            btController.send("set_eq", "band", band, "value", level);
        }

        @Override boolean freqAdjustable() { return eqParametric && eqSections.length == 0; }

        @Override void nudgeBandFreq(int band, int direction) {
            if (!freqAdjustable() || band < 0 || band >= eqFreqs.length) return;
            int curHz = eqFreqs[band] / 1000;
            // Keep a half-octave gap from each neighbour, matching that host's clamp; the
            // authoritative eq_state echo corrects any drift anyway.
            int loHz = band == 0 ? eqFreqMinHz : legacyGapAbove(eqFreqs[band - 1] / 1000);
            int hiHz = band == eqFreqs.length - 1 ? eqFreqMaxHz
                    : legacyGapBelow(eqFreqs[band + 1] / 1000);
            if (hiHz < loHz) hiHz = loHz;
            int newHz = Math.max(loHz, Math.min(hiHz, legacyStepFreqHz(curHz, direction)));
            eqFreqs[band] = newHz * 1000;
            btController.send("set_eq", "band", band, "freq", newHz);
        }

        /** That host's own default centre frequencies, so a reset lands where it would have. Only
         *  the six-band shape ever existed, so anything else is left alone. */
        @Override void resetBandFreq(int band) {
            if (!freqAdjustable() || band < 0 || band >= eqFreqs.length) return;
            if (eqFreqs.length != LEGACY_DEFAULT_FREQ_HZ.length) return;
            int hz = LEGACY_DEFAULT_FREQ_HZ[band];
            eqFreqs[band] = hz * 1000;
            btController.send("set_eq", "band", band, "freq", hz);
        }

        // Edges derived from neighbours, mirroring that host (eqFreqs holds milliHz, so the mean
        // operates on milliHz directly). First band falls to 0; last band reaches the ceiling.
        @Override int lowerEdgeMilliHz(int band) {
            if (band <= 0 || band >= eqFreqs.length) return 0;
            return legacyGeometricMean(eqFreqs[band - 1], eqFreqs[band]);
        }

        @Override int upperEdgeMilliHz(int band) {
            if (band < 0 || band >= eqFreqs.length) return 0;
            if (band == eqFreqs.length - 1) return LEGACY_TOP_EDGE_HZ * 1000;
            return legacyGeometricMean(eqFreqs[band], eqFreqs[band + 1]);
        }
    }

    // Geometry of the superseded six-band partition model, kept only for driving a host that still
    // runs it: band edges sit at the geometric mean of adjacent centres, neighbours keep a half
    // octave apart, and one nudge moves a third of an octave.
    private static final int LEGACY_TOP_EDGE_HZ = 20000;
    private static final int[] LEGACY_DEFAULT_FREQ_HZ = {80, 400, 1000, 2000, 5000, 12000};
    private static final double LEGACY_NEIGHBOUR_RATIO = 1.4142;  // 2^(1/2)
    private static final double LEGACY_STEP_RATIO = 1.2599;       // 2^(1/3)

    private static int legacyGeometricMean(int aHz, int bHz) {
        return (int) Math.round(Math.sqrt((double) aHz * bHz));
    }

    private static int legacyGapAbove(int lowerNeighbourHz) {
        return (int) Math.ceil(lowerNeighbourHz * LEGACY_NEIGHBOUR_RATIO);
    }

    private static int legacyGapBelow(int upperNeighbourHz) {
        return (int) Math.floor(upperNeighbourHz / LEGACY_NEIGHBOUR_RATIO);
    }

    private static int legacyStepFreqHz(int hz, int direction) {
        int next = direction >= 0
                ? (int) Math.round(hz * LEGACY_STEP_RATIO)
                : (int) Math.round(hz / LEGACY_STEP_RATIO);
        if (next == hz) next += direction >= 0 ? 1 : -1;
        return next;
    }

    void refreshAndScrollToNewTrack() {
        scrollToNewTrackPending = true;
        requestQueue();
    }

    void requestQueue() {
        int maxKnownId = 0, minKnownId = 0;
        for (int i = 0; i < metaCache.size(); i++) {
            int key = metaCache.keyAt(i);
            if (maxKnownId == 0 || key > maxKnownId) maxKnownId = key;
            if (minKnownId == 0 || key < minKnownId) minKnownId = key;
        }
        boolean known = maxKnownId > 0;
        btController.send("request_queue",
                "max_known_id", known ? maxKnownId : null,
                "min_known_id", known ? minKnownId : null);
    }

    void onQueueStateReceived(JSONObject obj) {
        currentId     = obj.optInt("current_id", -1);
        anchorId      = obj.optInt("anchor_id", 0);
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
        applyStateUpdate(obj, scrollToNewTrackPending, true);
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

    private void applyStateUpdate(JSONObject obj, boolean scrollToNewTrack, boolean scrollToCurrent) {
        applyFadeTimer(obj, playbackState);
        adapter.notifyDataSetChanged();
        updatePlaybackButtons();
        if (scrollToNewTrack && !queueEntries.isEmpty()) {
            scrollToNewTrackPending = false;
            final int target = newTrackScrollTarget();
            queueList.post(() -> scrollTo(queueList, target));
        } else if (scrollToCurrent) {
            ensureCurrentVisible();
        }
    }

    /**
     * Row to scroll to so a just-added track is visible. With an insert anchor set, new tracks
     * land directly above it, so target the row just above the anchor; otherwise the track was
     * appended at the end.
     */
    private int newTrackScrollTarget() {
        if (anchorId > 0) {
            for (int i = 0; i < queueEntries.size(); i++) {
                if (queueEntries.get(i).id == anchorId) return Math.max(0, i - 1);
            }
        }
        return queueEntries.size() - 1;
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
        if (eqDialog != null && eqDialog.isShowing()) {
            eqDialog.dismiss();
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

    /** Optimistically removes the swiped track and tells the host. */
    private void removeAt(int pos) {
        if (pos < 0 || pos >= queueEntries.size()) return;
        int trackId = queueEntries.remove(pos).id;
        adapter.notifyDataSetChanged();
        btController.send("remove_track", "id", trackId);
    }

    /** Optimistically toggles the remote insert anchor on the swiped track and tells the host. */
    private void toggleAnchorAt(int pos) {
        if (pos < 0 || pos >= queueEntries.size()) return;
        TrackEntry entry = queueEntries.get(pos);
        if (entry.id == currentId) return;   // the playing track can't be an anchor
        anchorId = (anchorId == entry.id) ? 0 : entry.id;
        adapter.notifyDataSetChanged();
        btController.send("set_anchor", "id", entry.id);
    }

    private final class QueueAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(activity);
        private final int colorBackground;
        private final int colorCurrent;
        private final int colorAnchor;

        QueueAdapter() {
            TypedValue out = new TypedValue();
            activity.getTheme().resolveAttribute(android.R.attr.colorBackground, out, true);
            colorBackground = out.data;
            colorCurrent    = activity.getColor(R.color.queueProgressBackground);
            colorAnchor     = activity.getColor(R.color.queueProgressFill);
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
            View     anchorMarker = convertView.findViewById(R.id.anchor_marker);

            convertView.setAlpha(position == gestures.dragPosition() ? 0f : 1f);
            content.setTranslationX(0);

            boolean isCurrent = (currentId >= 0 && entry.id == currentId);
            content.setBackgroundColor(isCurrent ? colorCurrent : colorBackground);

            boolean isAnchor = (anchorId > 0 && entry.id == anchorId);
            if (anchorMarker != null) {
                anchorMarker.setVisibility(isAnchor ? View.VISIBLE : View.GONE);
                if (isAnchor) anchorMarker.setBackgroundColor(colorAnchor);
            }

            String displayName = (!entry.title.isEmpty()) ? entry.title : entry.name;
            nameView.setText(displayName);

            boolean hasArtist = !entry.artist.isEmpty();
            artistView.setText(hasArtist ? entry.artist : "");
            metaRow.setVisibility(hasArtist ? View.VISIBLE : View.GONE);

            return convertView;
        }
    }
}
