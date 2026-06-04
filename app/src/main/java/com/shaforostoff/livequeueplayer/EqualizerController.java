package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.audiofx.Equalizer;

/**
 * Attaches an {@link Equalizer} to a {@link android.media.MediaPlayer}'s audio session and keeps
 * it in sync with the persisted {@link EqualizerSettings}. One instance per {@link AudioPlayer},
 * released alongside the MediaPlayer. Every native call is guarded so an unsupported device or a
 * torn-down session degrades to a silent no-op rather than crashing playback, matching the
 * defensive style of {@link AudioPlayer}.
 */
final class EqualizerController implements EqController {

  private Equalizer equalizer;

  EqualizerController(int audioSessionId, Context context) {
    try {
      equalizer = new Equalizer(0, audioSessionId);
      EqualizerSettings.cacheCaps(readCaps(equalizer));
    } catch (RuntimeException e) {
      // No usable equalizer on this device/session — leave equalizer null (no-op).
      equalizer = null;
      return;
    }
    applySettings(context);
  }

  /** Re-read persisted settings and push them to the live effect. */
  @Override
  public void applySettings(Context context) {
    Equalizer eq = equalizer;
    if (eq == null) return;
    try {
      short[] range = eq.getBandLevelRange();
      short numBands = eq.getNumberOfBands();
      // Enable before setting band levels: some devices drop levels set while disabled.
      eq.setEnabled(EqualizerSettings.isEnabled(context));
      for (short b = 0; b < numBands; b++) {
        int level = EqualizerSettings.getBandLevel(context, b);
        level = Math.max(range[0], Math.min(range[1], level));
        eq.setBandLevel(b, (short) level);
      }
    } catch (RuntimeException ignored) {
      // Session went away mid-update; ignore.
    }
  }

  @Override
  public void release() {
    Equalizer eq = equalizer;
    equalizer = null;
    if (eq != null) {
      try { eq.release(); } catch (RuntimeException ignored) {}
    }
  }

  private static EqualizerSettings.Caps readCaps(Equalizer eq) {
    short bands = eq.getNumberOfBands();
    short[] range = eq.getBandLevelRange();
    int[] freqs = new int[bands];
    for (short b = 0; b < bands; b++) {
      freqs[b] = eq.getCenterFreq(b);
    }
    return new EqualizerSettings.Caps(bands, range[0], range[1], freqs);
  }
}
