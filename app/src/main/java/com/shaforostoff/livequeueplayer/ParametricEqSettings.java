package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted parametric-equalizer settings for the {@link DynamicsEqController} path (API 28+): an
 * enabled flag plus, per band, an adjustable center frequency (Hz) and gain (millibels). Unlike the
 * graphic {@link EqualizerSettings}, the band count, frequency range and gain range are app-defined
 * constants — Android's {@code DynamicsProcessing} does not report device capabilities we need.
 * Stored in the shared {@code live_queue_player} namespace under {@code peq_*} keys so it never
 * collides with the graphic path, and re-applied to every new {@link AudioPlayer} via the controller.
 */
final class ParametricEqSettings {

  private static final String PREFS = "live_queue_player";
  private static final String KEY_ENABLED = "peq_enabled";
  private static final String KEY_FREQ_PREFIX = "peq_freq_";   // int, Hz
  private static final String KEY_GAIN_PREFIX = "peq_gain_";   // int, millibels

  static final int NUM_BANDS = 6;

  /** Default center frequencies (Hz), log-spaced across the audible range. */
  private static final int[] DEFAULT_FREQS = {80, 400, 1000, 2000, 5000, 12000};

  /** Gain limits in millibels (100 mB = 1 dB); shared step with the graphic path. */
  static final int GAIN_MIN_MILLIBELS = -1500;
  static final int GAIN_MAX_MILLIBELS = 1500;

  /** Adjustable center-frequency limits, in Hz. */
  static final int FREQ_MIN_HZ = 30;
  static final int FREQ_MAX_HZ = 16000;

  /** Upper edge pinned to the top band so it reaches the top of the spectrum. Kept at/below the
   *  Nyquist frequency of common sample rates (44.1/48 kHz) to avoid clamping surprises, and above
   *  the audible range so nothing audible is left flat. */
  static final int TOP_EDGE_HZ = 20000;

  /** One frequency nudge moves a third of an octave. */
  private static final double FREQ_STEP_RATIO = 1.2599; // 2^(1/3)

  /** Minimum spacing between adjacent bands, as a frequency ratio: a half octave, comfortably
   *  wider than one {@link #FREQ_STEP_RATIO} step so bands keep a visible gap. */
  private static final double MIN_NEIGHBOUR_RATIO = 1.4142; // 2^(1/2)

  private ParametricEqSettings() {
  }

  static int numBands() {
    return NUM_BANDS;
  }

  static int defaultFreqHz(int band) {
    return DEFAULT_FREQS[band];
  }

  static boolean isEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ENABLED, false);
  }

  static void setEnabled(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
  }

  static int getFreqHz(Context context, int band) {
    return prefs(context).getInt(KEY_FREQ_PREFIX + band, DEFAULT_FREQS[band]);
  }

  static void setFreqHz(Context context, int band, int hz) {
    hz = Math.max(minFreqHz(context, band), Math.min(maxFreqHz(context, band), hz));
    prefs(context).edit().putInt(KEY_FREQ_PREFIX + band, hz).apply();
  }

  /** Lowest frequency this band may take: kept at least {@link #MIN_NEIGHBOUR_RATIO} above its lower
   *  neighbour (or the global floor for the first band) so adjacent bands keep a clear gap. */
  static int minFreqHz(Context context, int band) {
    int floor = band == 0 ? FREQ_MIN_HZ : gapAbove(getFreqHz(context, band - 1));
    return Math.max(FREQ_MIN_HZ, floor);
  }

  /** Highest frequency this band may take: kept at least {@link #MIN_NEIGHBOUR_RATIO} below its upper
   *  neighbour (or the global ceiling for the last band). */
  static int maxFreqHz(Context context, int band) {
    int ceil = band == NUM_BANDS - 1 ? FREQ_MAX_HZ : gapBelow(getFreqHz(context, band + 1));
    return Math.max(minFreqHz(context, band), Math.min(FREQ_MAX_HZ, ceil));
  }

  /** Smallest frequency that stays a full {@link #MIN_NEIGHBOUR_RATIO} above {@code lowerNeighbourHz}. */
  static int gapAbove(int lowerNeighbourHz) {
    return (int) Math.ceil(lowerNeighbourHz * MIN_NEIGHBOUR_RATIO);
  }

  /** Largest frequency that stays a full {@link #MIN_NEIGHBOUR_RATIO} below {@code upperNeighbourHz}. */
  static int gapBelow(int upperNeighbourHz) {
    return (int) Math.floor(upperNeighbourHz / MIN_NEIGHBOUR_RATIO);
  }

  /**
   * Lower edge of a band's contiguous region: the geometric mean with its lower neighbour, or 0 for
   * the first band so it reaches the bottom of the spectrum. Equals the upper edge of the band below.
   */
  static int lowerEdgeHz(Context context, int band) {
    if (band <= 0) return 0;
    return geometricMean(getFreqHz(context, band - 1), getFreqHz(context, band));
  }

  /**
   * Upper edge of a band's contiguous region: the geometric mean with its upper neighbour, or
   * {@link #TOP_EDGE_HZ} for the last band so it reaches the top of the spectrum.
   */
  static int upperEdgeHz(Context context, int band) {
    if (band >= NUM_BANDS - 1) return TOP_EDGE_HZ;
    return geometricMean(getFreqHz(context, band), getFreqHz(context, band + 1));
  }

  /** Geometric mean of two frequencies — the natural midpoint on a log-frequency axis. */
  static int geometricMean(int aHz, int bHz) {
    return (int) Math.round(Math.sqrt((double) aHz * bHz));
  }

  static int getGainMillibels(Context context, int band) {
    return prefs(context).getInt(KEY_GAIN_PREFIX + band, 0);
  }

  static void setGainMillibels(Context context, int band, int millibels) {
    millibels = Math.max(GAIN_MIN_MILLIBELS, Math.min(GAIN_MAX_MILLIBELS, millibels));
    prefs(context).edit().putInt(KEY_GAIN_PREFIX + band, millibels).apply();
  }

  /** Move a frequency one third-octave step up ({@code direction > 0}) or down, clamped to range. */
  static int stepFreqHz(int hz, int direction) {
    int next = direction >= 0
        ? (int) Math.round(hz * FREQ_STEP_RATIO)
        : (int) Math.round(hz / FREQ_STEP_RATIO);
    if (next == hz) next += direction >= 0 ? 1 : -1; // guarantee movement at the low end
    return Math.max(FREQ_MIN_HZ, Math.min(FREQ_MAX_HZ, next));
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
}
