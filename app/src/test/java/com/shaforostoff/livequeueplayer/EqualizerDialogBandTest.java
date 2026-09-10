package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static com.shaforostoff.livequeueplayer.Taps.doubleTap;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The equalizer dialog's other layout: fixed bands. It serves the device graphic equalizer below
 * API 28 and any remote host still on the six-band partition model, so it is the path neither of
 * this machine's own screenshots nor {@link EqualizerDialogSectionTest} exercises — and it is built
 * in code, with no layout file to catch a mistake at build time.
 *
 * <p>What matters here is that the double-tap reset is wired to both of a band row's values, since
 * one of them only exists on the adjustable variant, and that the response plot stays hidden: a
 * band model has no section curve to draw.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class EqualizerDialogBandTest {

  /** In-memory band sink. {@code adjustable} is the older remote host, whose bands also move. */
  private static final class FakeBandSink extends EqualizerDialog.EqSink {
    static final int[] DEFAULT_FREQ_HZ = {80, 400, 1000, 2000, 5000, 12000};

    final int[] levels = new int[DEFAULT_FREQ_HZ.length];
    final int[] freqHz = DEFAULT_FREQ_HZ.clone();
    final boolean adjustable;

    FakeBandSink(boolean adjustable) {
      this.adjustable = adjustable;
    }

    @Override boolean isEnabled() { return true; }
    @Override void setEnabled(boolean enabled) {}
    @Override int numBands() { return levels.length; }
    @Override short bandLevel(int band) { return (short) levels[band]; }
    @Override int centerFreqMilliHz(int band) { return freqHz[band] * 1000; }
    @Override boolean freqAdjustable() { return adjustable; }

    @Override void nudgeBand(int band, int deltaMillibels) {
      levels[band] = Math.max(-1500, Math.min(1500, levels[band] + deltaMillibels));
    }

    @Override void resetBand(int band) { levels[band] = 0; }

    @Override void nudgeBandFreq(int band, int direction) {
      freqHz[band] += direction * 100;
    }

    @Override void resetBandFreq(int band) { freqHz[band] = DEFAULT_FREQ_HZ[band]; }

    @Override int lowerEdgeMilliHz(int band) { return freqHz[band] * 900; }
    @Override int upperEdgeMilliHz(int band) { return freqHz[band] * 1100; }
  }

  private ActivityController<Activity> controller;
  private FakeBandSink sink;
  private EqualizerDialog.Handle handle;

  private void show(boolean adjustable) {
    controller = Robolectric.buildActivity(Activity.class).setup();
    Activity activity = controller.get();
    activity.setTheme(R.style.app_theme);
    sink = new FakeBandSink(adjustable);
    handle = EqualizerDialog.show(activity, sink);
  }

  @After
  public void tearDown() {
    if (handle != null && handle.isShowing()) handle.dismiss();
    if (controller != null) controller.close();
  }

  @Test
  public void everyBandGetsARowAndNoCurveIsDrawn() {
    show(false);
    assertEquals(sink.levels.length, bandRows().size());
    EqCurveView curve = null;
    for (View v : descendants(root())) if (v instanceof EqCurveView) curve = (EqCurveView) v;
    assertNotNull(curve);
    assertEquals("a band model has no curve to show", View.GONE, curve.getVisibility());
  }

  @Test
  public void doubleTappingABandsGainPutsItBackToFlat() {
    show(false);
    tapUp(gainGroup(2));
    tapUp(gainGroup(2));
    assertEquals(2 * EqualizerSettings.STEP_MILLIBELS, sink.levels[2]);

    doubleTap(arrowValueIn(gainGroup(2)));
    assertEquals(0, sink.levels[2]);
    assertTrue("the row still reads the old gain",
        arrowValueIn(gainGroup(2)).getText().toString().contains("0"));
  }

  /** The adjustable variant puts a second value on the row — its centre frequency, in a group of
   *  its own ahead of the gain — so this also pins that each reset lands on its own value. */
  @Test
  public void doubleTappingAnAdjustableBandsFrequencyPutsItBack() {
    show(true);
    tapUp(freqGroup(1));
    tapUp(freqGroup(1));
    assertEquals(FakeBandSink.DEFAULT_FREQ_HZ[1] + 200, sink.freqHz[1]);
    sink.levels[1] = 500;

    doubleTap(arrowValueIn(freqGroup(1)));
    assertEquals(FakeBandSink.DEFAULT_FREQ_HZ[1], sink.freqHz[1]);
    assertEquals("a frequency reset moved the gain too", 500, sink.levels[1]);

    doubleTap(arrowValueIn(gainGroup(1)));
    assertEquals(0, sink.levels[1]);
  }

  // --- widget tree helpers -----------------------------------------------------------------------

  /** The band rows: the children of the one vertical container that holds one per band. */
  private List<LinearLayout> bandRows() {
    for (View v : descendants(root())) {
      if (!(v instanceof LinearLayout)) continue;
      LinearLayout container = (LinearLayout) v;
      if (container.getOrientation() != LinearLayout.VERTICAL
          || container.getChildCount() != sink.numBands()) {
        continue;
      }
      List<LinearLayout> rows = new ArrayList<>();
      for (int i = 0; i < container.getChildCount(); i++) {
        View child = container.getChildAt(i);
        if (!(child instanceof LinearLayout)
            || ((LinearLayout) child).getOrientation() != LinearLayout.HORIZONTAL) {
          rows.clear();
          break;
        }
        rows.add((LinearLayout) child);
      }
      if (!rows.isEmpty()) return rows;
    }
    throw new AssertionError("no band rows in the dialog");
  }

  /** The row itself, which holds the gain's ▼/value/▲. */
  private LinearLayout gainGroup(int band) {
    return bandRows().get(band);
  }

  /** The adjustable frequency's own group, nested first in the row. */
  private LinearLayout freqGroup(int band) {
    return (LinearLayout) bandRows().get(band).getChildAt(0);
  }

  /** The single value sitting between a ▼/▲ pair among this group's own children. */
  private TextView arrowValueIn(LinearLayout group) {
    TextView found = null;
    for (int i = 1; i + 1 < group.getChildCount(); i++) {
      if (group.getChildAt(i) instanceof TextView
          && isArrow(group.getChildAt(i - 1), "▼") && isArrow(group.getChildAt(i + 1), "▲")) {
        assertNull("two values between arrows in one group", found);
        found = (TextView) group.getChildAt(i);
      }
    }
    assertNotNull("no value between arrows", found);
    return found;
  }

  private void tapUp(LinearLayout group) {
    for (int i = 0; i < group.getChildCount(); i++) {
      if (isArrow(group.getChildAt(i), "▲")) {
        group.getChildAt(i).performClick();
        return;
      }
    }
    throw new AssertionError("no up arrow in the group");
  }

  private boolean isArrow(View view, String glyph) {
    return view instanceof Button && glyph.contentEquals(((Button) view).getText());
  }

  private ViewGroup root() {
    android.app.Dialog shown = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    assertNotNull("no dialog was shown", shown);
    return (ViewGroup) shown.getWindow().getDecorView();
  }

  private List<View> descendants(View view) {
    List<View> out = new ArrayList<>();
    collect(view, out);
    return out;
  }

  private void collect(View view, List<View> out) {
    out.add(view);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
    }
  }
}
