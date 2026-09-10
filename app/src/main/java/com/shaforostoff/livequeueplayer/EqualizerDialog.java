package com.shaforostoff.livequeueplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared centered equalizer dialog. Renders an on/off toggle plus, depending on what the
 * {@link EqSink} offers, one of two body layouts:
 *
 * <ul>
 *   <li><b>Sections</b> — the parametric model ({@link ParametricEqSettings}, API 28+ locally, or a
 *       host of that vintage over Bluetooth): a response curve and one gain row per named section,
 *       each row disclosing its own frequency and Q controls when tapped. Frequency and Q get
 *       parked once; the gain rows are what move during a milonga.</li>
 *   <li><b>Bands</b> — the graphic device equalizer, and any remote host still running the older
 *       six-band partition model, which a newer sender must keep talking to.</li>
 * </ul>
 *
 * Every value — anything sitting between a ▼/▲ pair, in either layout — resets to its default on a
 * double tap, which is the way back from a mid-milonga fiddle that went nowhere.
 *
 * The behavior is supplied by an {@link EqSink}: the local sinks write settings and nudge the
 * playback Service, while the remote sink sends Bluetooth commands. {@link Handle#refresh()} lets an
 * async source (a remote {@code eq_state} reply) build/update the rows after the dialog is already
 * shown, the same way the volume popup updates its value when {@code volume_state} arrives.
 */
final class EqualizerDialog {

  /**
   * Supplies values to and receives changes from the dialog. An abstract class rather than an
   * interface so a sink only overrides the model it actually has: a graphic device equalizer never
   * sees a section, and a section sink never sees a band.
   *
   * <p>{@code sectionCount() == 0 && numBands() == 0} means the controls are not yet known (or are
   * unavailable); the dialog then shows {@link #statusText()}.
   */
  abstract static class EqSink {
    abstract boolean isEnabled();
    abstract void setEnabled(boolean enabled);

    CharSequence statusText() { return null; }

    // --- parametric section model -------------------------------------------------------------

    /** Number of parametric sections, or 0 when this sink has no section model. */
    int sectionCount() { return 0; }

    /** Snapshot of one section; never called when {@link #sectionCount()} is 0. */
    ParametricEq.Section section(int slot) { return null; }

    int sectionLabelRes(int slot) { return R.string.eq_section_generic; }

    void nudgeGain(int slot, int deltaMillibels) {}
    void nudgeFreq(int slot, int direction) {}
    void nudgeQ(int slot, int direction) {}
    void setSectionOn(int slot, boolean on) {}

    /** Put one value back to its default. Each covers exactly what one pair of ▼/▲ moves, since
     *  that pair is what the double-tapped value sits between. */
    void resetGain(int slot) {}
    void resetFreq(int slot) {}
    void resetQ(int slot) {}

    /** Every engaged section, for the response plot. */
    ParametricEq.Section[] sections() {
      int n = sectionCount();
      ParametricEq.Section[] out = new ParametricEq.Section[n];
      for (int i = 0; i < n; i++) out[i] = section(i);
      return out;
    }

    // --- band model ---------------------------------------------------------------------------

    int numBands() { return 0; }
    int centerFreqMilliHz(int band) { return 0; }
    short bandLevel(int band) { return 0; }
    void nudgeBand(int band, int deltaMillibels) {}

    /** Flat, for every band model there is. */
    void resetBand(int band) {}

    /** True when each band's center frequency can be moved (a remote host on the older model). */
    boolean freqAdjustable() { return false; }

    void nudgeBandFreq(int band, int direction) {}

    void resetBandFreq(int band) {}

    /** Lower edge of the band's affected range, milliHz. Only shown when {@link #freqAdjustable()}. */
    int lowerEdgeMilliHz(int band) { return 0; }

    /** Upper edge of the band's affected range, milliHz. Only shown when {@link #freqAdjustable()}. */
    int upperEdgeMilliHz(int band) { return 0; }
  }

  /** Live handle to a shown dialog so callers can refresh values or dismiss it. */
  static final class Handle {
    private final EqualizerDialog impl;

    private Handle(EqualizerDialog impl) { this.impl = impl; }

    void refresh()        { impl.refresh(); }
    void dismiss()        { impl.dialog.dismiss(); }
    boolean isShowing()   { return impl.dialog.isShowing(); }
  }

  private final Activity activity;
  private final EqSink sink;
  private final float density;
  private final AlertDialog dialog;
  private final CheckBox enableSwitch;
  private final TextView status;
  private final LinearLayout bandsContainer;
  private final EqCurveView curve;

  private TextView[] bandValues = new TextView[0];
  private TextView[] bandFreqs = new TextView[0];

  /** Section-row widgets, parallel to the slot index. */
  private TextView[] sectionValues = new TextView[0];
  private TextView[] sectionDetails = new TextView[0];
  private TextView[] sectionFreqValues = new TextView[0];
  private TextView[] sectionQValues = new TextView[0];
  private CheckBox[] sectionToggles = new CheckBox[0];
  private TextView[] sectionLabels = new TextView[0];
  private View[] sectionSetupRows = new View[0];

  private boolean sectionMode;

  /** Section whose frequency and Q controls are open, or -1 when none is. At most one is open at a
   *  time: the row is the disclosure, so opening one closes whichever was. */
  private int expandedSlot = -1;

  private EqualizerDialog(Activity activity, EqSink sink) {
    this.activity = activity;
    this.sink = sink;
    this.density = activity.getResources().getDisplayMetrics().density;
    int pad = (int) (12 * density);

    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(pad, pad, pad, pad);

    // The checkbox doubles as the dialog title to save vertical space: "☑ Equalizer".
    enableSwitch = new CheckBox(activity);
    enableSwitch.setText(R.string.eq_dialog_title);
    enableSwitch.setTextSize(22f);
    enableSwitch.setOnCheckedChangeListener((b, checked) -> {
      sink.setEnabled(checked);
      updateCurve();
    });

    // Wrap it so the box+label group is centered in the title area rather than left-aligned.
    LinearLayout titleBar = new LinearLayout(activity);
    titleBar.setGravity(Gravity.CENTER);
    int titlePad = (int) (16 * density);
    titleBar.setPadding(titlePad, titlePad, titlePad, titlePad / 2);
    titleBar.addView(enableSwitch, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    status = new TextView(activity);
    status.setPadding(0, pad, 0, 0);
    status.setVisibility(View.GONE);
    root.addView(status);

    // Shown only in section mode; the band models have no curve to draw.
    curve = new EqCurveView(activity);
    curve.setVisibility(View.GONE);
    root.addView(curve, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    bandsContainer = new LinearLayout(activity);
    bandsContainer.setOrientation(LinearLayout.VERTICAL);
    root.addView(bandsContainer);

    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root);

    dialog = new AlertDialog.Builder(activity)
        .setCustomTitle(titleBar)
        .setView(scroll)
        .setPositiveButton(android.R.string.ok, null)
        .create();
  }

  static Handle show(Activity activity, EqSink sink) {
    EqualizerDialog d = new EqualizerDialog(activity, sink);
    d.dialog.show();
    d.refresh();
    return new Handle(d);
  }

  private void refresh() {
    if (!dialog.isShowing()) return;
    int sections = sink.sectionCount();
    int bands = sink.numBands();
    if (sections <= 0 && bands <= 0) {
      CharSequence msg = sink.statusText();
      status.setText(msg);
      status.setVisibility(msg == null ? View.GONE : View.VISIBLE);
      bandsContainer.setVisibility(View.GONE);
      curve.setVisibility(View.GONE);
      enableSwitch.setEnabled(false);
      return;
    }
    status.setVisibility(View.GONE);
    bandsContainer.setVisibility(View.VISIBLE);
    enableSwitch.setEnabled(true);

    boolean wantSections = sections > 0;
    if (wantSections != sectionMode || rowCount() != (wantSections ? sections : bands)) {
      sectionMode = wantSections;
      if (wantSections) buildSectionRows(sections); else buildBandRows(bands);
    }
    curve.setVisibility(sectionMode ? View.VISIBLE : View.GONE);

    // Sync the toggle without re-triggering the change listener.
    enableSwitch.setOnCheckedChangeListener(null);
    enableSwitch.setChecked(sink.isEnabled());
    enableSwitch.setOnCheckedChangeListener((b, checked) -> {
      sink.setEnabled(checked);
      updateCurve();
    });

    if (sectionMode) {
      for (int s = 0; s < sections; s++) updateSectionRow(s);
      updateCurve();
    } else {
      for (int b = 0; b < bands; b++) {
        bandValues[b].setText(formatDb(activity, sink.bandLevel(b)));
      }
      updateFreqLabels();
    }
  }

  private int rowCount() {
    return sectionMode ? sectionValues.length : bandValues.length;
  }

  // ------------------------------------------------------------------ sections

  private void buildSectionRows(int n) {
    bandsContainer.removeAllViews();
    bandValues = new TextView[0];
    bandFreqs = new TextView[0];
    sectionValues = new TextView[n];
    sectionDetails = new TextView[n];
    sectionFreqValues = new TextView[n];
    sectionQValues = new TextView[n];
    sectionToggles = new CheckBox[n];
    sectionLabels = new TextView[n];
    sectionSetupRows = new View[n];

    int vpad = (int) (3 * density);
    int valueWidth = (int) (66 * density);

    for (int i = 0; i < n; i++) {
      final int slot = i;
      ParametricEq.Section sec = sink.section(slot);
      boolean filter = sec != null && sec.isFilter();

      LinearLayout group = new LinearLayout(activity);
      group.setOrientation(LinearLayout.VERTICAL);
      group.setPadding(0, vpad, 0, vpad);

      LinearLayout main = new LinearLayout(activity);
      main.setOrientation(LinearLayout.HORIZONTAL);
      main.setGravity(Gravity.CENTER_VERTICAL);

      // Only the cut filters get an engage box: a shelf or a bell at 0 dB is already bypassed. The
      // box is still added to the other rows, just invisible, so every label column starts at the
      // same x rather than shifting by a checkbox width on the rows that lack one.
      CheckBox toggle = new CheckBox(activity);
      if (filter) {
        toggle.setOnCheckedChangeListener((b, checked) -> {
          sink.setSectionOn(slot, checked);
          updateSectionRow(slot);
          updateCurve();
        });
        sectionToggles[slot] = toggle;
      } else {
        toggle.setVisibility(View.INVISIBLE);
      }
      main.addView(toggle);

      LinearLayout labelCol = new LinearLayout(activity);
      labelCol.setOrientation(LinearLayout.VERTICAL);
      TextView label = new TextView(activity);
      label.setTextSize(14f);
      sectionLabels[slot] = label;
      TextView detail = new TextView(activity);
      detail.setTextSize(11f);
      detail.setTextColor(activity.getColor(R.color.inputHint));
      sectionDetails[slot] = detail;
      labelCol.addView(label);
      labelCol.addView(detail);
      main.addView(labelCol, new LinearLayout.LayoutParams(
          0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

      // The live control: gain for a shelf or bell, corner frequency for a cut filter — which is
      // the knob that section actually has.
      TextView value = new TextView(activity);
      value.setGravity(Gravity.CENTER);
      value.setTextSize(16f);
      value.setMinWidth(valueWidth);
      sectionValues[slot] = value;
      // Double tap resets it; a single tap on it still opens the row, like a tap anywhere else on
      // the row does. The row's own listener cannot serve here — the value has to consume its taps
      // to tell one gesture from the other.
      resetOnDoubleTap(value, () -> resetMain(slot), () -> toggleExpanded(slot));

      Button down = arrowButton("▼", 18f);
      Button up = arrowButton("▲", 18f);
      down.setOnClickListener(v -> nudgeMain(slot, -1));
      up.setOnClickListener(v -> nudgeMain(slot, 1));

      main.addView(down);
      main.addView(value);
      main.addView(up);
      // The row itself is the disclosure for its frequency and Q controls. The arrow buttons and
      // the engage box consume their own taps, so this only fires on the label and value areas —
      // which is what makes a row both a live gain knob and a way in to its setup.
      main.setOnClickListener(v -> toggleExpanded(slot));
      group.addView(main);

      // Setup line: what the article calls presetting the EQ before the milonga. Frequency lives
      // here for a shelf or bell; for a cut filter it is already the main row, so only Q remains.
      LinearLayout setupRow = new LinearLayout(activity);
      setupRow.setOrientation(LinearLayout.HORIZONTAL);
      setupRow.setGravity(Gravity.CENTER_VERTICAL);
      setupRow.setPadding((int) (28 * density), 0, 0, vpad);
      setupRow.setVisibility(View.GONE);
      if (!filter) {
        TextView freqValue = subControl(setupRow, R.string.eq_setup_freq,
            v -> nudgeSectionFreq(slot, -1), v -> nudgeSectionFreq(slot, 1),
            () -> resetSectionFreq(slot));
        sectionFreqValues[slot] = freqValue;
      }
      TextView qValue = subControl(setupRow, R.string.eq_setup_q,
          v -> nudgeSectionQ(slot, -1), v -> nudgeSectionQ(slot, 1), () -> resetSectionQ(slot));
      sectionQValues[slot] = qValue;
      sectionSetupRows[slot] = setupRow;
      group.addView(setupRow);

      bandsContainer.addView(group);
    }
    if (expandedSlot >= n) expandedSlot = -1;
    applyExpansion();
  }

  /** A compact labelled ▼/▲ pair for the setup line, returning the value view. */
  private TextView subControl(LinearLayout host, int labelRes, View.OnClickListener onDown,
                              View.OnClickListener onUp, Runnable onReset) {
    TextView label = new TextView(activity);
    label.setText(labelRes);
    label.setTextSize(11f);
    label.setTextColor(activity.getColor(R.color.inputHint));
    label.setPadding(0, 0, (int) (4 * density), 0);
    host.addView(label);

    Button down = arrowButton("▼", 13f);
    down.setOnClickListener(onDown);
    host.addView(down);

    TextView value = new TextView(activity);
    value.setGravity(Gravity.CENTER);
    value.setTextSize(13f);
    value.setMinWidth((int) (52 * density));
    resetOnDoubleTap(value, onReset, null);
    host.addView(value);

    Button up = arrowButton("▲", 13f);
    up.setOnClickListener(onUp);
    host.addView(up);

    View spacer = new View(activity);
    host.addView(spacer, new LinearLayout.LayoutParams((int) (10 * density), 1));
    return value;
  }

  private Button arrowButton(String glyph, float textSize) {
    Button b = new Button(activity);
    b.setText(glyph);
    b.setTextSize(textSize);
    int h = (int) (10 * density);
    int v = (int) (6 * density);
    b.setPadding(h, v, h, v);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    return b;
  }

  /**
   * Make a value double-tappable to put it back to its default. "Value" means what sits between a
   * ▼/▲ pair, and the reset covers exactly what that pair moves — nothing else on the row.
   *
   * <p>A {@link GestureDetector} rather than a tap counter because one of these values is also the
   * row's disclosure: {@code onSingleTapConfirmed} lets the two gestures coexist without the row
   * opening and closing again under a double tap. That costs the single tap the double-tap timeout,
   * which is why {@code onSingleTap} is only wired where a value has a second job — a tap on the
   * label still opens the row immediately.
   */
  private void resetOnDoubleTap(View value, Runnable onReset, Runnable onSingleTap) {
    GestureDetector detector = new GestureDetector(activity,
        new GestureDetector.SimpleOnGestureListener() {
          @Override public boolean onDown(MotionEvent e) {
            return true;   // claim the gesture, or the rest of it goes to the parent
          }

          @Override public boolean onDoubleTap(MotionEvent e) {
            if (onReset != null) onReset.run();
            return true;
          }

          @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            if (onSingleTap != null) onSingleTap.run();
            return true;
          }
        });
    value.setClickable(true);
    value.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
  }

  /** The main row's control: gain, or corner frequency for a cut filter. */
  private void nudgeMain(int slot, int direction) {
    ParametricEq.Section sec = sink.section(slot);
    if (sec != null && sec.isFilter()) {
      sink.nudgeFreq(slot, direction);
    } else {
      sink.nudgeGain(slot, direction * EqualizerSettings.STEP_MILLIBELS);
    }
    updateSectionRow(slot);
    updateCurve();
  }

  /** Reset of the main row's control, mirroring {@link #nudgeMain}. */
  private void resetMain(int slot) {
    ParametricEq.Section sec = sink.section(slot);
    if (sec != null && sec.isFilter()) {
      sink.resetFreq(slot);
    } else {
      sink.resetGain(slot);
    }
    updateSectionRow(slot);
    updateCurve();
  }

  private void resetSectionFreq(int slot) {
    sink.resetFreq(slot);
    updateSectionRow(slot);
    updateCurve();
  }

  private void resetSectionQ(int slot) {
    sink.resetQ(slot);
    updateSectionRow(slot);
    updateCurve();
  }

  private void nudgeSectionFreq(int slot, int direction) {
    sink.nudgeFreq(slot, direction);
    updateSectionRow(slot);
    updateCurve();
  }

  private void nudgeSectionQ(int slot, int direction) {
    sink.nudgeQ(slot, direction);
    updateSectionRow(slot);
    updateCurve();
  }

  /** Open this section's controls, or close them if they were already the open ones. */
  private void toggleExpanded(int slot) {
    expandedSlot = expandedSlot == slot ? -1 : slot;
    applyExpansion();
  }

  private void applyExpansion() {
    for (int slot = 0; slot < sectionSetupRows.length; slot++) {
      View row = sectionSetupRows[slot];
      if (row != null) row.setVisibility(slot == expandedSlot ? View.VISIBLE : View.GONE);
      updateSectionLabel(slot);
    }
    curve.setMarkedSlot(expandedSlot);
  }

  /** The label carries the disclosure state, since the row has no other affordance for it. */
  private void updateSectionLabel(int slot) {
    if (slot < 0 || slot >= sectionLabels.length) return;
    TextView label = sectionLabels[slot];
    if (label == null) return;
    label.setText((slot == expandedSlot ? "\u25be " : "\u25b8 ")
        + activity.getString(sink.sectionLabelRes(slot)));
  }

  private void updateSectionRow(int slot) {
    if (slot < 0 || slot >= sectionValues.length) return;
    ParametricEq.Section sec = sink.section(slot);
    if (sec == null) return;
    boolean filter = sec.isFilter();
    updateSectionLabel(slot);

    TextView value = sectionValues[slot];
    if (value != null) {
      value.setText(filter
          ? (sec.on ? formatFreqHz(activity, sec.freqHz) : activity.getString(R.string.eq_off))
          : formatDb(activity, sec.gainMb));
    }
    TextView detail = sectionDetails[slot];
    if (detail != null) {
      detail.setText(filter
          ? activity.getString(R.string.eq_filter_detail, shapeName(sec.type), sec.qMilli / 1000f)
          : activity.getString(R.string.eq_section_detail, shapeName(sec.type),
              formatFreqHz(activity, sec.freqHz), sec.qMilli / 1000f));
    }
    TextView freqValue = sectionFreqValues[slot];
    if (freqValue != null) freqValue.setText(formatFreqHz(activity, sec.freqHz));
    TextView qValue = sectionQValues[slot];
    if (qValue != null) qValue.setText(activity.getString(R.string.eq_q_value, sec.qMilli / 1000f));

    CheckBox toggle = sectionToggles[slot];
    if (toggle != null) {
      // Rebind around the programmatic change so restoring state does not fire a write back.
      toggle.setOnCheckedChangeListener(null);
      toggle.setChecked(sec.on);
      toggle.setOnCheckedChangeListener((b, checked) -> {
        sink.setSectionOn(slot, checked);
        updateSectionRow(slot);
        updateCurve();
      });
    }
  }

  /** Repaint the plot. A disabled equalizer draws flat, matching what is actually being heard. */
  private void updateCurve() {
    if (!sectionMode) return;
    curve.setSections(sink.isEnabled() ? sink.sections() : null);
  }

  private CharSequence shapeName(int type) {
    switch (type) {
      case ParametricEq.TYPE_HIGH_PASS:  return activity.getString(R.string.eq_shape_high_pass);
      case ParametricEq.TYPE_LOW_SHELF:  return activity.getString(R.string.eq_shape_low_shelf);
      case ParametricEq.TYPE_HIGH_SHELF: return activity.getString(R.string.eq_shape_high_shelf);
      case ParametricEq.TYPE_LOW_PASS:   return activity.getString(R.string.eq_shape_low_pass);
      default:                           return activity.getString(R.string.eq_shape_peak);
    }
  }

  // --------------------------------------------------------------------- bands

  /** Refresh every band's frequency label. A parametric band's edges are derived from its
   *  neighbours, so moving one band shifts the displayed range of its neighbours too. */
  private void updateFreqLabels() {
    for (int b = 0; b < bandFreqs.length; b++) {
      if (bandFreqs[b] != null) bandFreqs[b].setText(formatBandFreq(b));
    }
  }

  /** Adjustable bands show their affected range (lower – upper edge); fixed bands show their
   *  center frequency. */
  private CharSequence formatBandFreq(int b) {
    if (sink.freqAdjustable()) {
      // Stacked over two lines: the lower edge above, the upper edge below — the full range rarely
      // fits on one line at the dialog's width.
      return formatFreq(activity, sink.lowerEdgeMilliHz(b))
          + "\n– " + formatFreq(activity, sink.upperEdgeMilliHz(b));
    }
    return formatFreq(activity, sink.centerFreqMilliHz(b));
  }

  private void buildBandRows(int n) {
    bandsContainer.removeAllViews();
    sectionValues = new TextView[0];
    sectionDetails = new TextView[0];
    sectionFreqValues = new TextView[0];
    sectionQValues = new TextView[0];
    sectionToggles = new CheckBox[0];
    sectionLabels = new TextView[0];
    sectionSetupRows = new View[0];
    bandValues = new TextView[n];
    bandFreqs = new TextView[n];
    boolean freqAdjustable = sink.freqAdjustable();
    int vpad = (int) (4 * density);
    int valueWidth = (int) (64 * density);
    int freqWidth = (int) (72 * density);
    for (int b = 0; b < n; b++) {
      final int band = b;
      LinearLayout row = new LinearLayout(activity);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(0, vpad, 0, vpad);

      TextView freq = new TextView(activity);
      freq.setText(formatBandFreq(b));
      freq.setTextSize(14f);
      bandFreqs[b] = freq;

      if (freqAdjustable) {
        // Older remote host: ▼/▲ move the band's center, and the label shows the resulting affected
        // range (edges are shared with neighbours, so update all labels on a change). The gain
        // controls follow. The frequency group takes the flexible (weighted) space.
        freq.setGravity(Gravity.CENTER);
        freq.setMinWidth(freqWidth);
        Button freqDown = new Button(activity);
        freqDown.setText("▼");
        Button freqUp = new Button(activity);
        freqUp.setText("▲");
        freqDown.setOnClickListener(v -> {
          sink.nudgeBandFreq(band, -1);
          updateFreqLabels();
        });
        freqUp.setOnClickListener(v -> {
          sink.nudgeBandFreq(band, 1);
          updateFreqLabels();
        });
        resetOnDoubleTap(freq, () -> {
          sink.resetBandFreq(band);
          updateFreqLabels();
        }, null);
        LinearLayout freqGroup = new LinearLayout(activity);
        freqGroup.setOrientation(LinearLayout.HORIZONTAL);
        freqGroup.setGravity(Gravity.CENTER_VERTICAL);
        freqGroup.addView(freqDown);
        freqGroup.addView(freq);
        freqGroup.addView(freqUp);
        row.addView(freqGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      } else {
        row.addView(freq, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      }

      Button down = new Button(activity);
      down.setText("▼");
      Button up = new Button(activity);
      up.setText("▲");

      TextView value = new TextView(activity);
      value.setGravity(Gravity.CENTER);
      value.setTextSize(16f);
      value.setMinWidth(valueWidth);
      value.setText(formatDb(activity, sink.bandLevel(b)));
      bandValues[b] = value;

      down.setOnClickListener(v -> {
        sink.nudgeBand(band, -EqualizerSettings.STEP_MILLIBELS);
        value.setText(formatDb(activity, sink.bandLevel(band)));
      });
      up.setOnClickListener(v -> {
        sink.nudgeBand(band, EqualizerSettings.STEP_MILLIBELS);
        value.setText(formatDb(activity, sink.bandLevel(band)));
      });
      resetOnDoubleTap(value, () -> {
        sink.resetBand(band);
        value.setText(formatDb(activity, sink.bandLevel(band)));
      }, null);

      row.addView(down);
      row.addView(value);
      row.addView(up);
      bandsContainer.addView(row);
    }
  }

  private static String formatDb(Activity a, int millibels) {
    return a.getString(R.string.eq_band_db, Math.round(millibels / 100f));
  }

  private static String formatFreq(Activity a, int milliHz) {
    return formatFreqHz(a, milliHz / 1000);
  }

  private static String formatFreqHz(Activity a, int hz) {
    if (hz >= 1000) return a.getString(R.string.eq_freq_khz, hz / 1000f);
    return a.getString(R.string.eq_freq_hz, hz);
  }
}
