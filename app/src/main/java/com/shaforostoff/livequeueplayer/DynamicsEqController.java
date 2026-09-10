package com.shaforostoff.livequeueplayer;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;

/**
 * Parametric equalizer backend for API 28+, built on {@link DynamicsProcessing}.
 *
 * <p>{@code EqBand} exposes only a cutoff frequency and a gain — there is no Q and no filter type
 * anywhere in {@code android.media.audiofx}, on any API level. But the pre-EQ is not a filter bank:
 * AOSP's {@code DPFrequency} writes each band's gain into a contiguous run of FFT bins, so the band
 * list is really an arbitrary magnitude curve sampled at the band edges. This controller exploits
 * that. {@link ParametricEqSettings} holds true parametric sections (shelving, peaking, high-pass,
 * each with its own Q), {@link ParametricEq} evaluates their combined response in closed form, and
 * the result is painted onto {@link ParametricEq#BAND_COUNT} log-spaced bands. The user's model is
 * parametric; the effect only ever sees the curve.
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

  /** Brickwall settings for the output limiter: fast enough to catch a transient, slow enough on
   *  release to stay inaudible, and just below full scale. It exists only to keep a boost-heavy
   *  curve from clipping the sink, and it is engaged only while the curve actually boosts. */
  private static final float LIMITER_ATTACK_MS = 1f;
  private static final float LIMITER_RELEASE_MS = 60f;
  private static final float LIMITER_RATIO = 20f;
  private static final float LIMITER_THRESHOLD_DB = -0.5f;

  /** A boost smaller than this is not worth engaging the limiter for. */
  private static final double LIMITER_ENGAGE_DB = 0.1;

  private DynamicsProcessing dp;

  /** Gains last pushed, in dB, or {@code null} before the first push. The framework sends one
   *  {@code setParameter} per band per channel — there is no batch call, {@code setPreEqAllChannelsTo}
   *  just loops — so at {@link ParametricEq#BAND_COUNT} bands a full rewrite is a few hundred binder
   *  round trips. Only bands that actually moved get re-sent. */
  private float[] pushedGainsDb;

  /** Tracks the limiter's engaged state so it is only re-sent when it flips. */
  private boolean limiterEngaged;
  private boolean limiterKnown;

  DynamicsEqController(int audioSessionId, Context context) {
    try {
      DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
          DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
          CHANNEL_COUNT,
          /* preEqInUse */ true, ParametricEq.BAND_COUNT,
          /* mbcInUse */ false, 0,
          /* postEqInUse */ false, 0,
          /* limiterInUse */ true)
          // The native side derives its FFT block size from this duration, so it is really the
          // frequency-resolution control: see ParametricEq.FRAME_DURATION_MS for the trade.
          .setPreferredFrameDuration(ParametricEq.FRAME_DURATION_MS)
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
      // Keep the effect engaged at all times and express "disabled" as a flat 0 dB curve. Toggling
      // DynamicsProcessing.setEnabled() inserts/removes the effect from the audio chain, which
      // causes an audible hiccup on every on/off; leaving it always enabled and just flattening the
      // curve makes the switch seamless.
      effect.setEnabled(true);

      boolean on = ParametricEqSettings.isEnabled(context);
      ParametricEq.Section[] sections = on ? ParametricEqSettings.sections(context) : null;
      float[] gainsDb = on ? ParametricEq.bandGainsDb(sections) : new float[ParametricEq.BAND_COUNT];

      pushCurve(effect, gainsDb);
      pushLimiter(effect, on && ParametricEq.peakBoostDb(sections) > LIMITER_ENGAGE_DB);
    } catch (RuntimeException ignored) {
      // Session went away mid-update; ignore.
    }
  }

  /**
   * Paint the curve onto the band grid. A band's cutoff is the <em>upper</em> edge of the region
   * carrying its gain, running up from the previous band's cutoff, so the fixed log grid in
   * {@link ParametricEq} is also the cutoff order. Cutoffs never move, which is what lets a change
   * touch only the bands whose gain moved.
   */
  private void pushCurve(DynamicsProcessing effect, float[] gainsDb) {
    boolean first = pushedGainsDb == null;
    for (int b = 0; b < ParametricEq.BAND_COUNT; b++) {
      // A tenth of a dB is well below audibility and below the resolution of the gain step, so
      // skipping unchanged bands costs nothing and saves most of the binder traffic on a tap.
      if (!first && Math.abs(pushedGainsDb[b] - gainsDb[b]) < 0.1f) continue;
      effect.setPreEqBandAllChannelsTo(b,
          new DynamicsProcessing.EqBand(true, (float) ParametricEq.bandUpperHz(b), gainsDb[b]));
    }
    pushedGainsDb = gainsDb;
  }

  /**
   * Engage or release the output limiter. Only sent on a change: a curve that only cuts leaves the
   * limiter out of the way entirely, and a repeated identical push would be pure binder traffic.
   */
  private void pushLimiter(DynamicsProcessing effect, boolean engage) {
    if (limiterKnown && limiterEngaged == engage) return;
    effect.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(
        /* inUse */ true, /* enabled */ engage, /* linkGroup */ 0,
        LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO, LIMITER_THRESHOLD_DB,
        /* postGain */ 0f));
    limiterEngaged = engage;
    limiterKnown = true;
  }

  @Override
  public void release() {
    DynamicsProcessing effect = dp;
    dp = null;
    pushedGainsDb = null;
    limiterKnown = false;
    if (effect != null) {
      try { effect.release(); } catch (RuntimeException ignored) {}
    }
  }
}
