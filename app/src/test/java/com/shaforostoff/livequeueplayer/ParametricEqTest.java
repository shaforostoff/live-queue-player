package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Step 3 — the parametric EQ model, on plain JUnit with no Android on the classpath.
 *
 * <p>{@link ParametricEq} is where the app claims to have something Android's audio effects do not
 * give it: shelving, peaking and cut filters with a real Q, rendered onto a band grid that
 * {@code DynamicsProcessing} will accept. Two things are worth pinning down. First, that the
 * magnitudes really are the textbook prototypes — every anchor below is a value the analytic form
 * fixes exactly, so a transcription slip in one branch cannot hide. Second, and less obviously,
 * that the band grid is fine enough for the narrowest section the UI will let a user dial: the Q
 * ceiling and {@link ParametricEq#BAND_COUNT} are a matched pair, and raising one without the other
 * silently offers a Q the renderer cannot produce.
 */
public class ParametricEqTest {

  /** Anchors are exact in closed form; this only absorbs double rounding. */
  private static final double EXACT = 1e-3;

  /**
   * Errors are measured from 40 Hz up. Band 0 carries everything below the grid floor down to DC
   * and is deliberately sampled below that floor (see {@link ParametricEq#bandLowerHz}), so it
   * reads lower than the curve at 20 Hz by design — a deeper cut in the subsonic region an engaged
   * low cut is there to remove.
   */
  private static final double FROM_HZ = 40;

  private static ParametricEq.Section on(int type, int freqHz, int qMilli, int gainMb) {
    return new ParametricEq.Section(type, freqHz, qMilli, gainMb, true);
  }

  @Test
  public void peakingHitsItsGainAtCentreAndUnityFarAway() {
    ParametricEq.Section bell = on(ParametricEq.TYPE_PEAK, 1000, 2000, 600);
    assertEquals(6.0, ParametricEq.sectionDb(bell, 1000), EXACT);
    assertEquals(0.0, ParametricEq.sectionDb(bell, 20), 0.05);
    assertEquals(0.0, ParametricEq.sectionDb(bell, 20000), 0.05);

    ParametricEq.Section notch = on(ParametricEq.TYPE_PEAK, 1000, 4000, -900);
    assertEquals(-9.0, ParametricEq.sectionDb(notch, 1000), EXACT);
  }

  @Test
  public void shelvesGiveHalfGainAtTheCornerAndFullGainInTheirBand() {
    ParametricEq.Section low = on(ParametricEq.TYPE_LOW_SHELF, 160, 700, 600);
    assertEquals(3.0, ParametricEq.sectionDb(low, 160), EXACT);   // half gain at f0
    assertEquals(6.0, ParametricEq.sectionDb(low, 8), 0.05);      // full gain well below
    assertEquals(0.0, ParametricEq.sectionDb(low, 20000), 0.05);  // unity well above

    ParametricEq.Section high = on(ParametricEq.TYPE_HIGH_SHELF, 10000, 700, -600);
    assertEquals(-3.0, ParametricEq.sectionDb(high, 10000), EXACT);
    assertEquals(0.0, ParametricEq.sectionDb(high, 20), 0.05);
    assertEquals(-6.0, ParametricEq.sectionDb(high, 200000), 0.05);
  }

  @Test
  public void cutFiltersAreButterworthAtQPoint707() {
    ParametricEq.Section hp = on(ParametricEq.TYPE_HIGH_PASS, 80, 707, 0);
    assertEquals(-3.01, ParametricEq.sectionDb(hp, 80), 0.01);    // -3 dB at the corner
    assertEquals(-12.30, ParametricEq.sectionDb(hp, 40), 0.05);   // 12 dB/octave below it
    assertEquals(0.0, ParametricEq.sectionDb(hp, 1600), 0.02);    // passband

    ParametricEq.Section lp = on(ParametricEq.TYPE_LOW_PASS, 8000, 707, 0);
    assertEquals(-3.01, ParametricEq.sectionDb(lp, 8000), 0.01);
    assertEquals(0.0, ParametricEq.sectionDb(lp, 400), 0.02);
  }

  @Test
  public void zeroGainIsExactlyFlatForEveryShape() {
    int[] shapes = {ParametricEq.TYPE_LOW_SHELF, ParametricEq.TYPE_PEAK, ParametricEq.TYPE_HIGH_SHELF};
    for (int shape : shapes) {
      for (int q : new int[] {300, 700, 2000, 4000}) {
        for (double hz : new double[] {25, 200, 1000, 7000, 19000}) {
          assertEquals("shape " + shape + " Q " + q + " at " + hz + " Hz",
              0.0, ParametricEq.sectionDb(on(shape, 1000, q, 0), hz), EXACT);
        }
      }
    }
  }

  @Test
  public void disengagedAndAbsentSectionsContributeNothing() {
    ParametricEq.Section[] off = {
        new ParametricEq.Section(ParametricEq.TYPE_LOW_SHELF, 160, 700, 1500, false),
        null,
    };
    assertEquals(0.0, ParametricEq.responseDb(off, 50), EXACT);
    assertEquals(0.0, ParametricEq.responseDb(null, 50), EXACT);
    assertEquals(0.0, ParametricEq.responseDb(new ParametricEq.Section[0], 50), EXACT);
  }

  @Test
  public void sectionsCompose() {
    ParametricEq.Section a = on(ParametricEq.TYPE_LOW_SHELF, 160, 700, 400);
    ParametricEq.Section b = on(ParametricEq.TYPE_PEAK, 1000, 2000, -300);
    for (double hz : new double[] {30, 160, 500, 1000, 4000, 15000}) {
      assertEquals(ParametricEq.sectionDb(a, hz) + ParametricEq.sectionDb(b, hz),
          ParametricEq.responseDb(new ParametricEq.Section[] {a, b}, hz), EXACT);
    }
  }

  @Test
  public void bandGridIsContiguousAndAscendingAndReachesNyquist() {
    double prevUpper = -1;
    for (int b = 0; b < ParametricEq.BAND_COUNT; b++) {
      double lower = ParametricEq.bandLowerHz(b);
      double upper = ParametricEq.bandUpperHz(b);
      assertTrue("band " + b + " is not ascending", upper > lower);
      if (b > 0) {
        // A band's lower edge must be the previous band's cutoff exactly, or the effect would
        // leave bins between them at unity gain.
        assertEquals("gap before band " + b, prevUpper, lower, 1e-6);
      }
      double centre = ParametricEq.bandCenterHz(b);
      assertTrue("centre outside band " + b, centre > lower && centre < upper);
      prevUpper = upper;
    }
    // Must clear the Nyquist frequency of the highest rate we expect, or a hiss cut leaks.
    assertTrue("top band stops below 24 kHz", prevUpper >= 24000 - 1e-6);
  }

  @Test
  public void blockSizeMatchesTheNativeRounding() {
    // nextPow2(durationMs * rate / 1000), which for the 40 ms request is 2048 at both common rates.
    assertEquals(2048, ParametricEq.blockSizeFor(48000));
    assertEquals(2048, ParametricEq.blockSizeFor(44100));
    for (int rate : new int[] {8000, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 192000}) {
      int block = ParametricEq.blockSizeFor(rate);
      assertTrue("block " + block + " is not a power of two", (block & (block - 1)) == 0);
      assertTrue("block " + block + " too small for " + rate,
          block >= ParametricEq.FRAME_DURATION_MS * rate / 1000f);
      assertTrue("block " + block + " more than doubles the request for " + rate,
          block < 2 * Math.max(8, ParametricEq.FRAME_DURATION_MS * rate / 1000f));
    }
  }

  /**
   * The band grid must be able to render the narrowest section the UI offers. This is the test that
   * fails if {@link ParametricEqSettings#Q_MAX_MILLI} is raised without raising
   * {@link ParametricEq#BAND_COUNT}: it drives a full-depth notch and a full-height bell at the Q
   * ceiling and checks how far the sampled grid departs from the curve the user was shown.
   */
  @Test
  public void gridTracksTheCurveEvenAtTheNarrowestAllowedQ() {
    int q = ParametricEqSettings.Q_MAX_MILLI;
    for (int freq : new int[] {300, 1000, 5000, 12000}) {
      assertTrue("notch at " + freq + " Hz",
          worstErrorDb(new ParametricEq.Section[] {
              on(ParametricEq.TYPE_PEAK, freq, q, ParametricEqSettings.GAIN_MIN_MILLIBELS)}) < 2.0);
      assertTrue("bell at " + freq + " Hz",
          worstErrorDb(new ParametricEq.Section[] {
              on(ParametricEq.TYPE_PEAK, freq, q, ParametricEqSettings.GAIN_MAX_MILLIBELS)}) < 2.0);
    }
  }

  @Test
  public void gridIsAllButExactForShelvesFiltersAndRealisticSettings() {
    assertTrue("low shelf", worstErrorDb(new ParametricEq.Section[] {
        on(ParametricEq.TYPE_LOW_SHELF, 500, 700, 1500)}) < 0.5);
    assertTrue("high shelf", worstErrorDb(new ParametricEq.Section[] {
        on(ParametricEq.TYPE_HIGH_SHELF, 4000, 700, -1500)}) < 0.5);
    assertTrue("low cut", worstErrorDb(new ParametricEq.Section[] {
        on(ParametricEq.TYPE_HIGH_PASS, 200, 707, 0)}) < 1.0);

    // A plausible setup for a worn transfer: bass lift, reverb notch, presence, hiss shelf.
    assertTrue("full setup", worstErrorDb(new ParametricEq.Section[] {
        on(ParametricEq.TYPE_HIGH_PASS, 60, 707, 0),
        on(ParametricEq.TYPE_LOW_SHELF, 160, 700, 400),
        on(ParametricEq.TYPE_PEAK, 1000, 2000, -300),
        on(ParametricEq.TYPE_PEAK, 5000, 1200, 300),
        on(ParametricEq.TYPE_HIGH_SHELF, 10000, 700, -500)}) < 0.7);
  }

  @Test
  public void peakBoostReportsTheHeadroomABoostNeeds() {
    assertEquals(0.0, ParametricEq.peakBoostDb(new ParametricEq.Section[] {
        on(ParametricEq.TYPE_LOW_SHELF, 160, 700, -600)}), EXACT);
    assertEquals(6.0, ParametricEq.peakBoostDb(new ParametricEq.Section[] {
        on(ParametricEq.TYPE_PEAK, 1000, 700, 600)}), 0.3);
  }

  /** Worst absolute departure of the painted band gains from the target curve, above {@link #FROM_HZ}. */
  private static double worstErrorDb(ParametricEq.Section[] sections) {
    float[] gains = ParametricEq.bandGainsDb(sections);
    double worst = 0;
    for (int i = 0; i <= 6000; i++) {
      double hz = 20 * Math.pow(1000, i / 6000.0);
      if (hz < FROM_HZ) continue;
      int band = 0;
      while (band < ParametricEq.BAND_COUNT - 1 && ParametricEq.bandUpperHz(band) < hz) band++;
      worst = Math.max(worst, Math.abs(ParametricEq.responseDb(sections, hz) - gains[band]));
    }
    return worst;
  }
}
