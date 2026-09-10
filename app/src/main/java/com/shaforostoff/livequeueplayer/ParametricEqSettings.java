package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted parametric-equalizer settings for the {@link DynamicsEqController} path (API 28+): a
 * master enabled flag plus five fixed-purpose {@link ParametricEq.Section}s. The layout is the one a
 * tango DJ actually works: a low cut and a bass shelf, a mid notch for room reverb on old transfers,
 * a presence bell, and a high shelf for surface hiss. Frequency and Q are set once before the
 * milonga; the four gain knobs are what get touched between tandas.
 *
 * <p>The section <em>types</em> are part of that design and are not user-editable — only frequency,
 * Q, gain and (for the cut filter) engagement are. Everything is stored as integers, with Q scaled
 * by 1000 and gain in millibels, so the same values move unchanged through
 * {@code SharedPreferences}, the Bluetooth protocol and {@link ParametricEq}.
 *
 * <p>Values live in the shared {@code live_queue_player} namespace under {@code peqs_*} keys. The
 * master flag deliberately keeps the older {@code peq_enabled} key so an existing on/off state
 * survives the upgrade; the superseded {@code peq_freq_*}/{@code peq_gain_*} keys from the previous
 * six-band partition model are simply abandoned, since their band-index semantics have no meaning
 * in the section model and defaults here are flat anyway.
 *
 * @see ParametricEq for the magnitude maths and the band grid the sections are rendered onto
 */
final class ParametricEqSettings {

  private static final String PREFS = "live_queue_player";
  private static final String KEY_ENABLED = "peq_enabled";
  private static final String KEY_FREQ_PREFIX = "peqs_freq_";   // int, Hz
  private static final String KEY_Q_PREFIX    = "peqs_q_";      // int, Q × 1000
  private static final String KEY_GAIN_PREFIX = "peqs_gain_";   // int, millibels
  private static final String KEY_ON_PREFIX   = "peqs_on_";     // boolean

  static final int NUM_SECTIONS = 5;

  static final int SLOT_LOW_CUT    = 0;
  static final int SLOT_BASS       = 1;
  static final int SLOT_REVERB     = 2;
  static final int SLOT_BRILLIANCE = 3;
  static final int SLOT_HISS       = 4;

  /** Filter shape of each slot. Fixed: the shapes are the point of the layout. */
  private static final int[] TYPES = {
      ParametricEq.TYPE_HIGH_PASS,
      ParametricEq.TYPE_LOW_SHELF,
      ParametricEq.TYPE_PEAK,
      ParametricEq.TYPE_PEAK,
      ParametricEq.TYPE_HIGH_SHELF,
  };

  private static final int[] LABELS = {
      R.string.eq_section_low_cut,
      R.string.eq_section_bass,
      R.string.eq_section_reverb,
      R.string.eq_section_brilliance,
      R.string.eq_section_hiss,
  };

  private static final int[] DEFAULT_FREQ_HZ = {60, 160, 1000, 5000, 10000};

  /** Flat. A section at 0 dB is bypassed whatever its shape, so this is the default for every
   *  slot and needs no table. */
  static final int DEFAULT_GAIN_MILLIBELS = 0;

  /** Butterworth corner for the cut filter, gentle shelves, a workable notch and a broad bell. */
  private static final int[] DEFAULT_Q_MILLI = {707, 700, 2000, 1200, 700};

  /** The low cut starts disengaged, so switching the equalizer on never silently removes bass. */
  private static final boolean[] DEFAULT_ON = {false, true, true, true, true};

  /** Per-slot frequency limits, wide enough to be useful and narrow enough that each knob keeps
   *  doing the job its label promises. */
  private static final int[] FREQ_MIN_HZ = {20,  50,  300, 2000, 4000};
  private static final int[] FREQ_MAX_HZ = {200, 500, 3000, 12000, 16000};

  /** Gain limits in millibels (100 mB = 1 dB); step shared with the graphic path. */
  static final int GAIN_MIN_MILLIBELS = -1500;
  static final int GAIN_MAX_MILLIBELS = 1500;

  /** Q limits, ×1000. The floor is a very broad tilt. The ceiling is not a matter of taste — it is
   *  the narrowest bell {@link ParametricEq#BAND_COUNT} bands can actually render, and a notch
   *  this narrow is already surgical for the job (a reverb cut on an old transfer wants Q 1–3). */
  static final int Q_MIN_MILLI = 300;
  static final int Q_MAX_MILLI = 4000;

  /** One frequency nudge moves a sixth of an octave — fine enough to park a notch on a resonance,
   *  which a third of an octave (the old band model's step) is not. */
  private static final double FREQ_STEP_RATIO = 1.1225; // 2^(1/6)

  /** One Q nudge is a quarter wider or narrower. */
  private static final double Q_STEP_RATIO = 1.25;

  private ParametricEqSettings() {
  }

  static int numSections() {
    return NUM_SECTIONS;
  }

  static int typeOf(int slot) {
    return inRange(slot) ? TYPES[slot] : ParametricEq.TYPE_PEAK;
  }

  static int labelRes(int slot) {
    return inRange(slot) ? LABELS[slot] : R.string.eq_section_generic;
  }

  static int defaultFreqHz(int slot) {
    return inRange(slot) ? DEFAULT_FREQ_HZ[slot] : 1000;
  }

  static int defaultQMilli(int slot) {
    return inRange(slot) ? DEFAULT_Q_MILLI[slot] : 1000;
  }

  static int freqMinHz(int slot) {
    return inRange(slot) ? FREQ_MIN_HZ[slot] : 20;
  }

  static int freqMaxHz(int slot) {
    return inRange(slot) ? FREQ_MAX_HZ[slot] : 20000;
  }

  static boolean isEnabled(Context context) {
    return prefs(context).getBoolean(KEY_ENABLED, false);
  }

  static void setEnabled(Context context, boolean enabled) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
  }

  /** Snapshot of every section, in slot order — the input to {@link ParametricEq}. */
  static ParametricEq.Section[] sections(Context context) {
    ParametricEq.Section[] out = new ParametricEq.Section[NUM_SECTIONS];
    for (int i = 0; i < NUM_SECTIONS; i++) out[i] = section(context, i);
    return out;
  }

  static ParametricEq.Section section(Context context, int slot) {
    if (!inRange(slot)) return new ParametricEq.Section(ParametricEq.TYPE_PEAK, 1000, 1000, 0, false);
    SharedPreferences p = prefs(context);
    return new ParametricEq.Section(
        TYPES[slot],
        clampFreqHz(slot, p.getInt(KEY_FREQ_PREFIX + slot, DEFAULT_FREQ_HZ[slot])),
        clampQMilli(p.getInt(KEY_Q_PREFIX + slot, DEFAULT_Q_MILLI[slot])),
        clampGainMb(p.getInt(KEY_GAIN_PREFIX + slot, 0)),
        p.getBoolean(KEY_ON_PREFIX + slot, DEFAULT_ON[slot]));
  }

  static void setGainMillibels(Context context, int slot, int millibels) {
    if (!inRange(slot)) return;
    prefs(context).edit().putInt(KEY_GAIN_PREFIX + slot, clampGainMb(millibels)).apply();
  }

  static void setFreqHz(Context context, int slot, int hz) {
    if (!inRange(slot)) return;
    prefs(context).edit().putInt(KEY_FREQ_PREFIX + slot, clampFreqHz(slot, hz)).apply();
  }

  static void setQMilli(Context context, int slot, int qMilli) {
    if (!inRange(slot)) return;
    prefs(context).edit().putInt(KEY_Q_PREFIX + slot, clampQMilli(qMilli)).apply();
  }

  static void setSectionOn(Context context, int slot, boolean on) {
    if (!inRange(slot)) return;
    prefs(context).edit().putBoolean(KEY_ON_PREFIX + slot, on).apply();
  }

  static void nudgeGain(Context context, int slot, int deltaMillibels) {
    setGainMillibels(context, slot, section(context, slot).gainMb + deltaMillibels);
  }

  static void nudgeFreq(Context context, int slot, int direction) {
    setFreqHz(context, slot, stepFreqHz(slot, section(context, slot).freqHz, direction));
  }

  static void nudgeQ(Context context, int slot, int direction) {
    setQMilli(context, slot, stepQMilli(section(context, slot).qMilli, direction));
  }

  // Resets are per-value rather than per-section: each one puts back exactly what one pair of
  // dialog arrows moves, so a double tap on a value never disturbs the neighbouring ones. Engaged
  // state is deliberately not among them — the cut filter's checkbox is its own affordance.

  static void resetGain(Context context, int slot) {
    setGainMillibels(context, slot, DEFAULT_GAIN_MILLIBELS);
  }

  static void resetFreq(Context context, int slot) {
    setFreqHz(context, slot, defaultFreqHz(slot));
  }

  static void resetQ(Context context, int slot) {
    setQMilli(context, slot, defaultQMilli(slot));
  }

  // -------------------------------------------------------- clamps and steps
  // Static and Context-free so the remote sender can apply the identical clamp to its cached copy
  // before the host's authoritative echo arrives, exactly as the old band path did.

  static int clampGainMb(int millibels) {
    return Math.max(GAIN_MIN_MILLIBELS, Math.min(GAIN_MAX_MILLIBELS, millibels));
  }

  static int clampQMilli(int qMilli) {
    return Math.max(Q_MIN_MILLI, Math.min(Q_MAX_MILLI, qMilli));
  }

  static int clampFreqHz(int slot, int hz) {
    return Math.max(freqMinHz(slot), Math.min(freqMaxHz(slot), hz));
  }

  /** Move a section's corner/centre one sixth-octave step, clamped to that slot's range. */
  static int stepFreqHz(int slot, int hz, int direction) {
    return stepFreqHz(hz, direction, freqMinHz(slot), freqMaxHz(slot));
  }

  /** Same step against explicit limits, for the remote sender: it clamps against the limits the
   *  host sent with the section rather than this build's, so the two never disagree. */
  static int stepFreqHz(int hz, int direction, int minHz, int maxHz) {
    int next = direction >= 0
        ? (int) Math.round(hz * FREQ_STEP_RATIO)
        : (int) Math.round(hz / FREQ_STEP_RATIO);
    if (next == hz) next += direction >= 0 ? 1 : -1; // guarantee movement at the low end
    return Math.max(minHz, Math.min(Math.max(minHz, maxHz), next));
  }

  static int stepQMilli(int qMilli, int direction) {
    int next = direction >= 0
        ? (int) Math.round(qMilli * Q_STEP_RATIO)
        : (int) Math.round(qMilli / Q_STEP_RATIO);
    if (next == qMilli) next += direction >= 0 ? 1 : -1;
    return clampQMilli(next);
  }

  private static boolean inRange(int slot) {
    return slot >= 0 && slot < NUM_SECTIONS;
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }
}
