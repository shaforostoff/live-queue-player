package com.shaforostoff.livequeueplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared centered equalizer dialog. Renders an on/off toggle plus one row per band, each with
 * down/up arrow buttons (like {@code popup_volume_slider}) around the current gain in dB. The
 * behavior is supplied by an {@link EqSink}: the local sink writes {@link EqualizerSettings} and
 * nudges the playback Service, while the remote sink sends Bluetooth commands. {@link Handle#refresh()}
 * lets an async source (a remote {@code eq_state} reply) build/update the rows after the dialog is
 * already shown, the same way the volume popup updates its value when {@code volume_state} arrives.
 */
final class EqualizerDialog {

  /** Supplies values to and receives changes from the dialog. {@code numBands()==0} means the
   *  bands are not yet known (or unavailable); the dialog then shows {@link #statusText()}. */
  interface EqSink {
    boolean isEnabled();
    void setEnabled(boolean enabled);
    int numBands();
    int centerFreqMilliHz(int band);
    short bandLevel(int band);
    void nudgeBand(int band, int deltaMillibels);
    CharSequence statusText();

    /** True when each band's center frequency can be moved (parametric/DynamicsProcessing path). */
    boolean freqAdjustable();
    /** Move a band's center frequency one step up ({@code direction > 0}) or down. No-op when
     *  {@link #freqAdjustable()} is false. */
    void nudgeFreq(int band, int direction);
    /** Lower edge of the band's affected range, milliHz. Only shown when {@link #freqAdjustable()}. */
    int lowerEdgeMilliHz(int band);
    /** Upper edge of the band's affected range, milliHz. Only shown when {@link #freqAdjustable()}. */
    int upperEdgeMilliHz(int band);
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
  private TextView[] bandValues = new TextView[0];
  private TextView[] bandFreqs = new TextView[0];

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
    enableSwitch.setOnCheckedChangeListener((b, checked) -> sink.setEnabled(checked));

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
    int n = sink.numBands();
    if (n <= 0) {
      CharSequence msg = sink.statusText();
      status.setText(msg);
      status.setVisibility(msg == null ? View.GONE : View.VISIBLE);
      bandsContainer.setVisibility(View.GONE);
      enableSwitch.setEnabled(false);
      return;
    }
    status.setVisibility(View.GONE);
    bandsContainer.setVisibility(View.VISIBLE);
    enableSwitch.setEnabled(true);
    if (bandValues.length != n) buildRows(n);
    // Sync the toggle without re-triggering the change listener.
    enableSwitch.setOnCheckedChangeListener(null);
    enableSwitch.setChecked(sink.isEnabled());
    enableSwitch.setOnCheckedChangeListener((b, checked) -> sink.setEnabled(checked));
    for (int b = 0; b < n; b++) {
      bandValues[b].setText(formatDb(activity, sink.bandLevel(b)));
    }
    updateFreqLabels();
  }

  /** Refresh every band's frequency label. A parametric band's edges are derived from its
   *  neighbours, so moving one band shifts the displayed range of its neighbours too. */
  private void updateFreqLabels() {
    for (int b = 0; b < bandFreqs.length; b++) {
      if (bandFreqs[b] != null) bandFreqs[b].setText(formatBandFreq(b));
    }
  }

  /** Parametric bands show their affected range (lower – upper edge); graphic bands show their
   *  fixed center frequency. */
  private CharSequence formatBandFreq(int b) {
    if (sink.freqAdjustable()) {
      // Stacked over two lines: the lower edge above, the upper edge below — the full range rarely
      // fits on one line at the dialog's width.
      return formatFreq(activity, sink.lowerEdgeMilliHz(b))
          + "\n– " + formatFreq(activity, sink.upperEdgeMilliHz(b));
    }
    return formatFreq(activity, sink.centerFreqMilliHz(b));
  }

  private void buildRows(int n) {
    bandsContainer.removeAllViews();
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
        // Parametric path: ▼/▲ move the band's center, and the label shows the resulting affected
        // range (edges are shared with neighbours, so update all labels on a change). The gain
        // controls follow. The frequency group takes the flexible (weighted) space.
        freq.setGravity(Gravity.CENTER);
        freq.setMinWidth(freqWidth);
        Button freqDown = new Button(activity);
        freqDown.setText("▼");
        Button freqUp = new Button(activity);
        freqUp.setText("▲");
        freqDown.setOnClickListener(v -> {
          sink.nudgeFreq(band, -1);
          updateFreqLabels();
        });
        freqUp.setOnClickListener(v -> {
          sink.nudgeFreq(band, 1);
          updateFreqLabels();
        });
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
    int hz = milliHz / 1000;
    if (hz >= 1000) return a.getString(R.string.eq_freq_khz, hz / 1000f);
    return a.getString(R.string.eq_freq_hz, hz);
  }
}
