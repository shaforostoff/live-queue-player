package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;

/**
 * Persisted equalizer settings (enabled flag + per-band gain in millibels) plus a cached snapshot
 * of the device's equalizer capabilities. Mirrors the static-helper style of
 * {@link AudioOutputRouter}; settings live in the shared {@code live_queue_player} namespace and
 * are re-applied to every new {@link AudioPlayer} via {@link EqualizerController}.
 */
final class EqualizerSettings {

  private static final String PREFS = "live_queue_player";
  private static final String KEY_ENABLED = "eq_enabled";
  private static final String KEY_BAND_PREFIX = "eq_band_";

  /** Gain change applied per up/down press, in millibels (100 mB = 1 dB). */
  static final int STEP_MILLIBELS = 100;

  /** Immutable snapshot of the device equalizer's fixed capabilities. */
  static final class Caps {
    final int numBands;
    final short minLevel;     // millibels
    final short maxLevel;     // millibels
    final int[] centerFreq;   // milliHz, length == numBands

    Caps(int numBands, short minLevel, short maxLevel, int[] centerFreq) {
      this.numBands = numBands;
      this.minLevel = minLevel;
      this.maxLevel = maxLevel;
      this.centerFreq = centerFreq;
    }
  }

  private static volatile Caps sCachedCaps;

  private EqualizerSettings() {
  }

  static boolean isEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ENABLED, false);
  }

  static void setEnabled(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
  }

  static short getBandLevel(Context context, int band) {
    return (short) prefs(context).getInt(KEY_BAND_PREFIX + band, 0);
  }

  static void setBandLevel(Context context, int band, int millibels) {
    prefs(context).edit().putInt(KEY_BAND_PREFIX + band, millibels).apply();
  }

  /**
   * Publish capabilities discovered from a live {@link Equalizer} so the UI can render bands
   * without allocating its own effect.
   */
  static void cacheCaps(Caps caps) {
    if (caps != null) sCachedCaps = caps;
  }

  /**
   * Device equalizer capabilities, or {@code null} if the device has no usable equalizer. Prefers
   * the snapshot published by a live {@link EqualizerController}; otherwise briefly attaches an
   * {@link Equalizer} to the global output mix (never enabled) to read them.
   */
  static Caps queryCapabilities(Context context) {
    Caps cached = sCachedCaps;
    if (cached != null) return cached;
    Equalizer eq = null;
    try {
      eq = new Equalizer(0, 0);
      short bands = eq.getNumberOfBands();
      short[] range = eq.getBandLevelRange();
      int[] freqs = new int[bands];
      for (short b = 0; b < bands; b++) {
        freqs[b] = eq.getCenterFreq(b);
      }
      Caps caps = new Caps(bands, range[0], range[1], freqs);
      sCachedCaps = caps;
      return caps;
    } catch (RuntimeException e) {
      // UnsupportedOperationException or other native failure: no usable equalizer.
      return null;
    } finally {
      if (eq != null) {
        try { eq.release(); } catch (RuntimeException ignored) {}
      }
    }
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
}
