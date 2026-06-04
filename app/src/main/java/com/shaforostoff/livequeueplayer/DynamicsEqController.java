package com.shaforostoff.livequeueplayer;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;

/**
 * Parametric equalizer backend for API 28+, built on {@link DynamicsProcessing}. Uses the pre-EQ
 * stage with a fixed number of bands; each band's center frequency and gain come from
 * {@link ParametricEqSettings}. DynamicsProcessing's {@code EqBand} exposes only cutoff frequency
 * and gain (no Q), so this is a freely-tunable multiband EQ rather than a true Q-parametric one.
 *
 * <p>One instance per {@link AudioPlayer}, released alongside the MediaPlayer. Every native call is
 * guarded so an unsupported device/session degrades to a silent no-op rather than crashing playback,
 * matching the defensive style of {@link EqualizerController}.
 */
@TargetApi(Build.VERSION_CODES.P)
final class DynamicsEqController implements EqController {

  /** DynamicsProcessing needs a fixed channel count up front; stereo covers the common case and the
   *  framework adapts band settings applied to all channels. */
  private static final int CHANNEL_COUNT = 2;

  private DynamicsProcessing dp;

  DynamicsEqController(int audioSessionId, Context context) {
    try {
      DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
          DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
          CHANNEL_COUNT,
          /* preEqInUse */ true, ParametricEqSettings.NUM_BANDS,
          /* mbcInUse */ false, 0,
          /* postEqInUse */ false, 0,
          /* limiterInUse */ false)
          .build();
      dp = new DynamicsProcessing(0, audioSessionId, config);
    } catch (RuntimeException e) {
      // No usable DynamicsProcessing effect on this device/session — leave dp null (no-op).
      dp = null;
      return;
    }
    applySettings(context);
  }

  @Override
  public void applySettings(Context context) {
    DynamicsProcessing effect = dp;
    if (effect == null) return;
    try {
      // Enable before setting bands: mirrors the graphic path, where some devices drop values set
      // while disabled.
      effect.setEnabled(ParametricEqSettings.isEnabled(context));

      // A DynamicsProcessing EqBand's cutoff is the *upper* edge of a contiguous region carrying its
      // gain, running up from the previous band's cutoff. Bands are kept in ascending frequency
      // order by ParametricEqSettings, so the logical band index is also the cutoff order. The
      // shared edge helpers place each edge at the geometric mean of adjacent center frequencies (so
      // a slider sits in the middle of its band), drop the first band's lower edge to 0 Hz, and pin
      // the last band's upper edge to the ceiling so it reaches the top of the spectrum.
      int n = ParametricEqSettings.NUM_BANDS;
      for (int i = 0; i < n; i++) {
        int cutoffHz = ParametricEqSettings.upperEdgeHz(context, i);
        int gainMb = ParametricEqSettings.getGainMillibels(context, i);
        DynamicsProcessing.EqBand band =
            new DynamicsProcessing.EqBand(true, cutoffHz, gainMb / 100f);
        effect.setPreEqBandAllChannelsTo(i, band);
      }
    } catch (RuntimeException ignored) {
      // Session went away mid-update; ignore.
    }
  }

  @Override
  public void release() {
    DynamicsProcessing effect = dp;
    dp = null;
    if (effect != null) {
      try { effect.release(); } catch (RuntimeException ignored) {}
    }
  }
}
