package com.shaforostoff.livequeueplayer;

/**
 * Pure parametric-EQ maths, shared by every consumer of the section model: the local host
 * ({@link DynamicsEqController} rendering to the effect), the dialog's {@link EqCurveView}, and the
 * remote sender, which draws the same curve from sections received over Bluetooth.
 *
 * <p>Android's {@code DynamicsProcessing} pre-EQ is not a filter bank — AOSP's {@code DPFrequency}
 * writes one constant gain factor into a contiguous run of FFT bins per band, so a band is a pixel
 * on a magnitude curve rather than a filter with a Q. That is what makes this class possible: the
 * app models real parametric sections (shelving, peaking, high-pass), evaluates their combined
 * magnitude response in closed form, and paints the result onto {@link #BAND_COUNT} bands. The
 * effect never sees a Q; it only ever sees the curve.
 *
 * <p>Magnitudes come from the standard analog prototypes (RBJ cookbook), evaluated on the imaginary
 * axis at {@code x = f / f0}. There is deliberately no bilinear transform: nothing here has to match
 * a biquad's coefficients, only its shape, and the analog prototype is both simpler and free of
 * warping near Nyquist.
 *
 * <p>Stateless and Context-free by design — {@link ParametricEqSettings} owns persistence, this
 * class owns the maths.
 */
final class ParametricEq {

  /** High-pass (low cut): 12 dB/octave below {@code f0}, {@code Q} sets the corner resonance. */
  static final int TYPE_HIGH_PASS  = 0;
  /** Low shelf: {@code gain} below {@code f0}, unity above, half-gain at {@code f0}. */
  static final int TYPE_LOW_SHELF  = 1;
  /** Peaking (bell): {@code gain} at {@code f0}, unity far away, {@code Q} sets the width. */
  static final int TYPE_PEAK       = 2;
  /** High shelf: unity below {@code f0}, {@code gain} above, half-gain at {@code f0}. */
  static final int TYPE_HIGH_SHELF = 3;
  /** Low-pass (high cut): 12 dB/octave above {@code f0}. */
  static final int TYPE_LOW_PASS   = 4;

  /**
   * One parametric section. Immutable so a snapshot can be handed to the view, the controller and
   * the wire encoder without any of them being able to disturb the others.
   *
   * <p>{@code qMilli} is Q × 1000 and {@code gainMb} is millibels, keeping the whole model integral
   * so it survives {@code SharedPreferences} and JSON without float formatting concerns — the same
   * convention the graphic path already uses for gain.
   */
  static final class Section {
    final int type;
    final int freqHz;
    final int qMilli;
    final int gainMb;
    final boolean on;

    Section(int type, int freqHz, int qMilli, int gainMb, boolean on) {
      this.type = type;
      this.freqHz = freqHz;
      this.qMilli = qMilli;
      this.gainMb = gainMb;
      this.on = on;
    }

    /** True for the cut filters, whose live control is a corner frequency rather than a gain. */
    boolean isFilter() {
      return type == TYPE_HIGH_PASS || type == TYPE_LOW_PASS;
    }

    Section withGain(int mb)   { return new Section(type, freqHz, qMilli, mb, on); }
    Section withFreq(int hz)   { return new Section(type, hz, qMilli, gainMb, on); }
    Section withQ(int milli)   { return new Section(type, freqHz, milli, gainMb, on); }
    Section withOn(boolean en) { return new Section(type, freqHz, qMilli, gainMb, en); }
  }

  // ---------------------------------------------------------------- band grid

  /**
   * Number of {@code DynamicsProcessing} pre-EQ bands the curve is painted onto. Log-spaced over
   * {@link #GRID_LO_HZ}..{@link #GRID_TOP_HZ} this is a little over 1/14 octave per band.
   *
   * <p>The count is set by the narrowest section the UI allows, not by taste: a peaking section of
   * Q spans {@code 2·asinh(1/2Q)/ln2} octaves, so {@link ParametricEqSettings#Q_MAX_MILLI} of 4.0
   * is 0.36 octave — five bands across its -3 dB width, which holds the worst-case sampling error
   * to under 2 dB at the extreme of ±15 dB and well under 1 dB at any realistic setting. Shelves
   * and the cut filters are far broader and land inside 0.3 dB. Raising the Q ceiling without
   * raising this count would offer a Q the renderer cannot actually produce.
   *
   * <p>Bands are free in the effect — the FFT cost does not depend on how many there are — but not
   * free to push: the framework sends one {@code setParameter} per band per channel, with no batch
   * call, so this trades against update latency rather than against DSP load. See
   * {@link DynamicsEqController} for the diffing that keeps a tap from rewriting all of them.
   */
  static final int BAND_COUNT = 144;

  /** Bottom of the log grid. Band 0 also carries everything below it, down to DC. */
  static final double GRID_LO_HZ = 20.0;

  /**
   * Top of the log grid. Above the Nyquist frequency of both common sample rates so the last band
   * reaches the top of the spectrum — bins past Nyquist simply clamp inside the effect, whereas a
   * ceiling below it would leave real bins at unity gain and let a hiss cut leak.
   */
  static final double GRID_TOP_HZ = 24000.0;

  private static final double GRID_RATIO = GRID_TOP_HZ / GRID_LO_HZ;

  /**
   * Frame duration requested from the effect, in milliseconds. The native side picks
   * {@code blockSize = nextPow2(durationMs * sampleRate / 1000)}, so this is really a frequency-
   * resolution control: the framework default of 10 ms yields a 512-sample block at 48 kHz —
   * 94 Hz bins, which quantises the entire bass region into one or two bins and makes shelving
   * below 200 Hz meaningless. 40 ms gives a 2048-sample block and 23 Hz bins, at the cost of
   * roughly 43 ms of algorithmic latency. That latency is a constant offset on an already heavily
   * buffered {@code MediaPlayer} path, so it costs cue monitoring rather than mix timing.
   */
  static final float FRAME_DURATION_MS = 40f;

  /** {@code DPFrequency}'s floor; a shorter block is raised to this. */
  private static final int MIN_BLOCK_SIZE = 8;

  /** Upper edge of a band's contiguous region — what {@code EqBand} calls its cutoff frequency. */
  static double bandUpperHz(int band) {
    return GRID_LO_HZ * Math.pow(GRID_RATIO, (band + 1.0) / BAND_COUNT);
  }

  /**
   * Lower edge of a band's region, equal to the upper edge of the band below. Band 0 reports half
   * the grid floor rather than the floor itself: its region actually runs down to DC, and pulling
   * its evaluation point below 20 Hz is what lets an engaged low cut show up as a real cut there
   * instead of being sampled at the corner frequency.
   */
  static double bandLowerHz(int band) {
    if (band <= 0) return GRID_LO_HZ / 2;
    return GRID_LO_HZ * Math.pow(GRID_RATIO, band / (double) BAND_COUNT);
  }

  /** Frequency a band's single gain value is sampled at: the midpoint on a log axis. */
  static double bandCenterHz(int band) {
    return Math.sqrt(bandLowerHz(band) * bandUpperHz(band));
  }

  /** The curve sampled onto the band grid, in dB — exactly what gets pushed to the effect. */
  static float[] bandGainsDb(Section[] sections) {
    float[] gains = new float[BAND_COUNT];
    for (int b = 0; b < BAND_COUNT; b++) {
      gains[b] = (float) responseDb(sections, bandCenterHz(b));
    }
    return gains;
  }

  /**
   * Block size the effect will choose for this sample rate, mirroring {@code DP_configureVariant}:
   * the requested duration in samples, raised to the floor and rounded up to a power of two.
   * Used only to draw the rendered response honestly — the effect is never told this.
   */
  static int blockSizeFor(int sampleRateHz) {
    int desired = (int) (FRAME_DURATION_MS * sampleRateHz / 1000f);
    if (desired < MIN_BLOCK_SIZE) return MIN_BLOCK_SIZE;
    if ((desired & (desired - 1)) == 0) return desired;
    return 1 << (32 - Integer.numberOfLeadingZeros(desired));
  }

  /**
   * FFT bin a band's cutoff lands on, matching {@code DPFrequency}'s own rounding. Adjacent bands
   * that map to the same bin collapse — below the bin spacing the log grid simply cannot be
   * resolved, and drawing that is more useful than hiding it.
   */
  static int bandUpperBin(int band, int sampleRateHz, int blockSize) {
    return (int) (0.5 + bandUpperHz(band) * blockSize / (double) sampleRateHz);
  }

  // ------------------------------------------------------------ magnitudes

  /** Deepest cut reported, so an engaged high-pass yields a drawable number at DC instead of -∞. */
  static final double DB_FLOOR = -60.0;
  private static final double DB_CEILING = 40.0;

  /** Combined response of every engaged section, in dB. Gains multiply, so decibels add. */
  static double responseDb(Section[] sections, double hz) {
    if (sections == null) return 0;
    double sum = 0;
    for (Section s : sections) {
      if (s != null && s.on) sum += sectionDb(s, hz);
    }
    return clampDb(sum);
  }

  /** One section's contribution at {@code hz}, in dB. Zero gain is exactly 0 dB for every type. */
  static double sectionDb(Section s, double hz) {
    if (s == null || s.freqHz <= 0) return 0;
    double f = Math.max(hz, 0.5);            // keep x > 0 so the cut filters stay finite
    double x = f / s.freqHz;
    double x2 = x * x;
    double q = Math.max(s.qMilli, 1) / 1000.0;
    double a = Math.pow(10, (s.gainMb / 100.0) / 40.0);   // half-gain amplitude, per the prototypes
    double r = Math.sqrt(a) * x / q;
    switch (s.type) {
      case TYPE_HIGH_PASS:
        return db(x2 / mag(1 - x2, x / q));
      case TYPE_LOW_PASS:
        return db(1.0 / mag(1 - x2, x / q));
      case TYPE_PEAK:
        return db(mag(1 - x2, a * x / q) / mag(1 - x2, x / (a * q)));
      case TYPE_LOW_SHELF:
        return db(a * mag(a - x2, r) / mag(1 - a * x2, r));
      case TYPE_HIGH_SHELF:
        return db(a * mag(1 - a * x2, r) / mag(a - x2, r));
      default:
        return 0;
    }
  }

  /** Magnitude of {@code a + jb}. Hand-rolled: {@link Math#hypot} guards overflow we cannot reach
   *  and costs an order of magnitude more, which shows up when redrawing the curve per tap. */
  private static double mag(double a, double b) {
    return Math.sqrt(a * a + b * b);
  }

  private static double db(double linear) {
    if (!(linear > 0)) return DB_FLOOR;
    return clampDb(20 * Math.log10(linear));
  }

  private static double clampDb(double db) {
    if (Double.isNaN(db)) return 0;
    return Math.max(DB_FLOOR, Math.min(DB_CEILING, db));
  }

  /** Largest boost anywhere on the curve, in dB — the headroom a boost-heavy setup needs. */
  static double peakBoostDb(Section[] sections) {
    double peak = 0;
    for (int b = 0; b < BAND_COUNT; b++) {
      peak = Math.max(peak, responseDb(sections, bandCenterHz(b)));
    }
    return peak;
  }

  private ParametricEq() {
  }
}
