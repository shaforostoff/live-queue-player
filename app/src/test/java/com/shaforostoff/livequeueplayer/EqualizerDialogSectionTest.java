package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static com.shaforostoff.livequeueplayer.Taps.doubleTap;
import static com.shaforostoff.livequeueplayer.Taps.idle;
import static com.shaforostoff.livequeueplayer.Taps.singleTap;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 2 — the equalizer dialog's section layout, driven on the JVM under Robolectric.
 *
 * <p>The dialog builds its rows in code rather than from a layout, and the response plot is a
 * custom {@link EqCurveView} that measures and draws itself, so neither has a resource file that
 * would catch a mistake at build time. This walks the real widget tree: it checks the section rows
 * appear, that a tap moves the value the row is supposed to own (gain for a shelf or bell, corner
 * frequency for the cut filter), that a row discloses its own frequency and Q controls and only
 * ever one row's at a time, that a double tap on any value puts that one value back to its default
 * without disturbing the disclosure it shares the gesture with, and that the plot lays out and
 * draws without throwing.
 *
 * <p>The sink here is a plain in-memory stand-in, which is also what a remote sender's cache looks
 * like — so this covers the section path for both ends without a Service or a Bluetooth link.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class EqualizerDialogSectionTest {

  /** In-memory section sink: the same shape the remote sender's cache has. */
  private static final class FakeSink extends EqualizerDialog.EqSink {
    final ParametricEq.Section[] sections = {
        new ParametricEq.Section(ParametricEq.TYPE_HIGH_PASS, 60, 707, 0, false),
        new ParametricEq.Section(ParametricEq.TYPE_LOW_SHELF, 160, 700, 0, true),
        new ParametricEq.Section(ParametricEq.TYPE_PEAK, 1000, 2000, 0, true),
        new ParametricEq.Section(ParametricEq.TYPE_PEAK, 5000, 1200, 0, true),
        new ParametricEq.Section(ParametricEq.TYPE_HIGH_SHELF, 10000, 700, 0, true),
    };
    boolean enabled = true;

    @Override boolean isEnabled() { return enabled; }
    @Override void setEnabled(boolean on) { enabled = on; }
    @Override int sectionCount() { return sections.length; }
    @Override ParametricEq.Section section(int slot) { return sections[slot]; }
    @Override int sectionLabelRes(int slot) { return ParametricEqSettings.labelRes(slot); }

    @Override void nudgeGain(int slot, int deltaMillibels) {
      sections[slot] = sections[slot].withGain(
          ParametricEqSettings.clampGainMb(sections[slot].gainMb + deltaMillibels));
    }

    @Override void nudgeFreq(int slot, int direction) {
      sections[slot] = sections[slot].withFreq(
          ParametricEqSettings.stepFreqHz(slot, sections[slot].freqHz, direction));
    }

    @Override void nudgeQ(int slot, int direction) {
      sections[slot] = sections[slot].withQ(
          ParametricEqSettings.stepQMilli(sections[slot].qMilli, direction));
    }

    @Override void setSectionOn(int slot, boolean on) {
      sections[slot] = sections[slot].withOn(on);
    }

    @Override void resetGain(int slot) {
      sections[slot] = sections[slot].withGain(ParametricEqSettings.DEFAULT_GAIN_MILLIBELS);
    }

    @Override void resetFreq(int slot) {
      sections[slot] = sections[slot].withFreq(ParametricEqSettings.defaultFreqHz(slot));
    }

    @Override void resetQ(int slot) {
      sections[slot] = sections[slot].withQ(ParametricEqSettings.defaultQMilli(slot));
    }
  }

  private ActivityController<Activity> controller;
  private Activity activity;
  private FakeSink sink;
  private EqualizerDialog.Handle handle;

  @Before
  public void setUp() {
    controller = Robolectric.buildActivity(Activity.class).setup();
    activity = controller.get();
    activity.setTheme(R.style.app_theme);
    sink = new FakeSink();
    handle = EqualizerDialog.show(activity, sink);
  }

  @After
  public void tearDown() {
    if (handle != null && handle.isShowing()) handle.dismiss();
    controller.close();
  }

  @Test
  public void everySectionGetsARowAndThePlotIsShown() {
    assertTrue(handle.isShowing());
    assertEquals(sink.sections.length, valueViews().size());
    assertNotNull("no response plot in the dialog", curveView());
    assertEquals(View.VISIBLE, curveView().getVisibility());
  }

  @Test
  public void aTapOnAGainSectionMovesItsGainByOneStep() {
    int bass = ParametricEqSettings.SLOT_BASS;
    tapMainUp(bass);
    assertEquals(EqualizerSettings.STEP_MILLIBELS, sink.sections[bass].gainMb);
    assertEquals(160, sink.sections[bass].freqHz);          // the live knob is gain, not frequency
    assertTrue(valueViews().get(bass).getText().toString().contains("1"));
  }

  @Test
  public void aTapOnTheCutFilterMovesItsCornerFrequency() {
    int cut = ParametricEqSettings.SLOT_LOW_CUT;
    int before = sink.sections[cut].freqHz;
    tapMainUp(cut);
    assertTrue("corner did not move up", sink.sections[cut].freqHz > before);
    assertEquals("a cut filter has no gain to move", 0, sink.sections[cut].gainMb);
  }

  @Test
  public void gainAndFrequencyStayWithinTheirLimits() {
    int reverb = ParametricEqSettings.SLOT_REVERB;
    for (int i = 0; i < 60; i++) tapMainUp(reverb);
    assertEquals(ParametricEqSettings.GAIN_MAX_MILLIBELS, sink.sections[reverb].gainMb);

    int cut = ParametricEqSettings.SLOT_LOW_CUT;
    for (int i = 0; i < 60; i++) tapMainUp(cut);
    assertEquals(ParametricEqSettings.freqMaxHz(cut), sink.sections[cut].freqHz);
  }

  @Test
  public void everyRowStartsWithItsSetupControlsClosed() {
    assertEquals(sink.sections.length, setupRows().size());
    assertOnlyOpen(-1);
  }

  @Test
  public void tappingARowOpensOnlyThatRowsSetupControls() {
    tapRow(ParametricEqSettings.SLOT_REVERB);
    assertOnlyOpen(ParametricEqSettings.SLOT_REVERB);
  }

  @Test
  public void tappingAnotherRowMovesTheOpenControlsToIt() {
    tapRow(ParametricEqSettings.SLOT_REVERB);
    tapRow(ParametricEqSettings.SLOT_HISS);
    assertOnlyOpen(ParametricEqSettings.SLOT_HISS);
  }

  @Test
  public void tappingTheOpenRowClosesIt() {
    tapRow(ParametricEqSettings.SLOT_BASS);
    assertOnlyOpen(ParametricEqSettings.SLOT_BASS);
    tapRow(ParametricEqSettings.SLOT_BASS);
    assertOnlyOpen(-1);
  }

  @Test
  public void openingARowDoesNotDisturbItsValue() {
    int bass = ParametricEqSettings.SLOT_BASS;
    tapRow(bass);
    assertEquals("opening the controls moved the gain", 0, sink.sections[bass].gainMb);
    assertEquals(160, sink.sections[bass].freqHz);

    // ...and the gain arrows still work while they are open.
    tapMainUp(bass);
    assertEquals(EqualizerSettings.STEP_MILLIBELS, sink.sections[bass].gainMb);
    assertOnlyOpen(bass);
  }

  @Test
  public void doubleTappingAGainValuePutsItBackToFlat() {
    int bass = ParametricEqSettings.SLOT_BASS;
    tapMainUp(bass);
    tapMainUp(bass);
    assertEquals(2 * EqualizerSettings.STEP_MILLIBELS, sink.sections[bass].gainMb);

    doubleTap(valueViews().get(bass));
    assertEquals(ParametricEqSettings.DEFAULT_GAIN_MILLIBELS, sink.sections[bass].gainMb);
    assertTrue("the row still reads the old gain",
        valueViews().get(bass).getText().toString().contains("0"));
    assertEquals("a gain reset moved the frequency too", 160, sink.sections[bass].freqHz);
  }

  @Test
  public void doubleTappingTheCutFiltersValuePutsBackItsCornerFrequency() {
    int cut = ParametricEqSettings.SLOT_LOW_CUT;
    for (int i = 0; i < 4; i++) tapMainUp(cut);
    assertTrue("corner did not move", sink.sections[cut].freqHz > 60);

    doubleTap(valueViews().get(cut));
    assertEquals(ParametricEqSettings.defaultFreqHz(cut), sink.sections[cut].freqHz);
  }

  @Test
  public void doubleTappingTheSetupValuesPutsBackFrequencyAndQ() {
    int reverb = ParametricEqSettings.SLOT_REVERB;
    tapRow(reverb);
    for (int i = 0; i < 3; i++) {
      tapArrowUp(setupRow(reverb), FREQ);
      tapArrowUp(setupRow(reverb), Q);
    }
    assertTrue("frequency did not move", sink.sections[reverb].freqHz > 1000);
    assertTrue("Q did not move", sink.sections[reverb].qMilli > 2000);

    doubleTap(arrowValue(setupRow(reverb), FREQ));
    assertEquals(ParametricEqSettings.defaultFreqHz(reverb), sink.sections[reverb].freqHz);
    assertTrue("resetting the frequency moved Q too", sink.sections[reverb].qMilli > 2000);

    doubleTap(arrowValue(setupRow(reverb), Q));
    assertEquals(ParametricEqSettings.defaultQMilli(reverb), sink.sections[reverb].qMilli);
  }

  /**
   * The main value carries both gestures — the row's disclosure and its own reset — so this is the
   * one place they could tread on each other.
   */
  @Test
  public void aSingleTapOnAValueOpensTheRowAndADoubleTapLeavesTheRowAlone() {
    int bass = ParametricEqSettings.SLOT_BASS;
    singleTap(valueViews().get(bass));
    assertOnlyOpen(bass);

    tapMainUp(bass);
    doubleTap(valueViews().get(bass));
    idle();                                  // any deferred single tap would land here
    assertEquals(ParametricEqSettings.DEFAULT_GAIN_MILLIBELS, sink.sections[bass].gainMb);
    assertOnlyOpen(bass);
  }

  /** Exactly one row's setup controls visible, or none when {@code openSlot} is -1. */
  private void assertOnlyOpen(int openSlot) {
    List<View> rows = setupRows();
    for (int slot = 0; slot < rows.size(); slot++) {
      assertEquals("slot " + slot + " visibility, expected open slot " + openSlot,
          slot == openSlot ? View.VISIBLE : View.GONE, rows.get(slot).getVisibility());
    }
  }

  @Test
  public void theCurveLaysOutAndDrawsForEveryReachableState() {
    EqCurveView curve = curveView();
    drawOnce(curve);                                     // flat, everything at 0 dB

    sink.sections[ParametricEqSettings.SLOT_LOW_CUT] =
        sink.sections[ParametricEqSettings.SLOT_LOW_CUT].withOn(true);
    sink.nudgeGain(ParametricEqSettings.SLOT_BASS, ParametricEqSettings.GAIN_MAX_MILLIBELS);
    sink.nudgeGain(ParametricEqSettings.SLOT_REVERB, ParametricEqSettings.GAIN_MIN_MILLIBELS);
    for (int i = 0; i < 40; i++) sink.nudgeQ(ParametricEqSettings.SLOT_REVERB, 1);
    handle.refresh();
    tapRow(ParametricEqSettings.SLOT_REVERB);            // also draws that band's frequency marker
    drawOnce(curve);

    sink.enabled = false;                                // disabled draws flat, not blank
    handle.refresh();
    drawOnce(curve);
  }

  private void drawOnce(View view) {
    view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    assertTrue("plot measured to nothing", view.getMeasuredHeight() > 0);
    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    Bitmap bitmap = Bitmap.createBitmap(
        view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
  }

  // --- widget tree helpers ---------------------------------------------------------------------

  private EqCurveView curveView() {
    for (View v : descendants(root())) if (v instanceof EqCurveView) return (EqCurveView) v;
    return null;
  }

  /** The per-section value readouts, in slot order: the views a main-row tap must move. */
  private List<TextView> valueViews() {
    List<TextView> out = new ArrayList<>();
    for (LinearLayout group : sectionGroups()) {
      LinearLayout main = (LinearLayout) group.getChildAt(0);
      for (int i = 0; i < main.getChildCount(); i++) {
        View child = main.getChildAt(i);
        // The value sits between the two arrow buttons, and is the only bare TextView there.
        if (child instanceof TextView && !(child instanceof Button)) out.add((TextView) child);
      }
    }
    return out;
  }

  private List<View> setupRows() {
    List<View> out = new ArrayList<>();
    for (LinearLayout group : sectionGroups()) out.add(group.getChildAt(1));
    return out;
  }

  /** Each section is one vertical group of [main row, setup row]. */
  private List<LinearLayout> sectionGroups() {
    List<LinearLayout> out = new ArrayList<>();
    for (View v : descendants(root())) {
      if (!(v instanceof LinearLayout)) continue;
      LinearLayout ll = (LinearLayout) v;
      if (ll.getOrientation() != LinearLayout.VERTICAL || ll.getChildCount() != 2) continue;
      if (ll.getChildAt(0) instanceof LinearLayout && ll.getChildAt(1) instanceof LinearLayout) {
        LinearLayout first = (LinearLayout) ll.getChildAt(0);
        if (first.getOrientation() == LinearLayout.HORIZONTAL) out.add(ll);
      }
    }
    return out;
  }

  /** Tap the row itself — the disclosure — rather than either of its arrow buttons. */
  private void tapRow(int slot) {
    assertTrue("row " + slot + " is not tappable",
        sectionGroups().get(slot).getChildAt(0).performClick());
  }

  private void tapMainUp(int slot) {
    LinearLayout main = (LinearLayout) sectionGroups().get(slot).getChildAt(0);
    Button up = null;
    for (int i = 0; i < main.getChildCount(); i++) {
      View child = main.getChildAt(i);
      if (child instanceof Button && "▲".contentEquals(((Button) child).getText())) {
        up = (Button) child;
      }
    }
    assertNotNull("no up arrow on slot " + slot, up);
    up.performClick();
  }

  // --- arrow/value pairs -----------------------------------------------------------------------
  // "A value between a ▼ and a ▲" is the thing the reset gesture is attached to, so the tests
  // locate it that way rather than by position: the setup line holds Freq then Q, and a cut
  // filter's line holds only Q.

  private static final int FREQ = 0;
  private static final int Q = 1;

  private LinearLayout setupRow(int slot) {
    return (LinearLayout) sectionGroups().get(slot).getChildAt(1);
  }

  private TextView arrowValue(LinearLayout row, int which) {
    return (TextView) row.getChildAt(arrowValueIndices(row).get(which));
  }

  private void tapArrowUp(LinearLayout row, int which) {
    row.getChildAt(arrowValueIndices(row).get(which) + 1).performClick();
  }

  private List<Integer> arrowValueIndices(LinearLayout row) {
    List<Integer> out = new ArrayList<>();
    for (int i = 1; i + 1 < row.getChildCount(); i++) {
      if (row.getChildAt(i) instanceof TextView
          && isArrow(row.getChildAt(i - 1), "▼") && isArrow(row.getChildAt(i + 1), "▲")) {
        out.add(i);
      }
    }
    return out;
  }

  private boolean isArrow(View view, String glyph) {
    return view instanceof Button && glyph.contentEquals(((Button) view).getText());
  }

  /** The dialog has its own Window, so the activity's decor view holds none of these rows. */
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
