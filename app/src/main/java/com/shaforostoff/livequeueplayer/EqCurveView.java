package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;

/**
 * Minimal frequency-response plot for the parametric equalizer, drawn above the section rows in
 * {@link EqualizerDialog}.
 *
 * <p>Two lines, because they are not the same thing. The solid line is the target response — the
 * combined magnitude of the {@link ParametricEq.Section}s, which is what the DJ is setting up. The
 * faint stepped line is what the device will actually produce once that curve has been sampled onto
 * {@link ParametricEq#BAND_COUNT} bands and those bands have been quantised to the effect's FFT
 * bins. They agree above a few hundred Hz and visibly part company at the bottom, where the bin
 * spacing runs out; showing that is more useful than hiding it, since it is precisely the limit of
 * what {@code DynamicsProcessing} can do.
 *
 * <p>Fed a snapshot rather than a Context, so the same view draws local settings on the host and
 * sections received over Bluetooth on the remote sender — the maths in {@link ParametricEq} is the
 * same on both ends.
 */
final class EqCurveView extends View {

  /** Vertical range, in dB. Wider than one section's gain limit so a stacked curve still fits. */
  private static final float DB_RANGE = 18f;

  /** Frequency axis. */
  private static final double F_LO = 20.0;
  private static final double F_HI = 20000.0;
  private static final double LOG_SPAN = Math.log(F_HI / F_LO);

  /** Sample rate assumed when drawing the rendered line. Both common rates give the same picture to
   *  within a bin; the point of the line is the order of magnitude of the resolution, not a promise. */
  private static final int ASSUMED_SAMPLE_RATE = 48000;

  private static final int CURVE_SAMPLES = 180;

  private final Paint curvePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint renderPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint gridPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint axisPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint markerPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final Path targetPath = new Path();
  private final Path renderPath = new Path();

  private final float density;
  private final float padLeft;
  private final float padRight;
  private final float padTop;
  private final float padBottom;

  private ParametricEq.Section[] sections;
  private int markedSlot = -1;

  EqCurveView(Context context) {
    super(context);
    density = getResources().getDisplayMetrics().density;
    padLeft   = 26 * density;
    padRight  = 6 * density;
    padTop    = 5 * density;
    padBottom = 13 * density;

    float labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f,
        getResources().getDisplayMetrics());

    // Every line is @color/foreground at some alpha, so the plot reads as a monochrome sketch that
    // belongs to the dialog rather than a second accent colour. Derived here rather than named in
    // colors.xml because the app has a values-night palette: a literal would need a second copy per
    // configuration, and a copy is a thing that can be forgotten. Foreground already tracks the
    // theme, so this follows it into dark mode by construction. The alphas give near-identical
    // contrast against either background, dark-on-light or light-on-dark.
    int fg = context.getColor(R.color.foreground);

    curvePaint.setStyle(Paint.Style.STROKE);
    curvePaint.setStrokeWidth(2 * density);
    curvePaint.setStrokeJoin(Paint.Join.ROUND);
    curvePaint.setStrokeCap(Paint.Cap.ROUND);
    curvePaint.setColor(fg);

    renderPaint.setStyle(Paint.Style.STROKE);
    renderPaint.setStrokeWidth(1 * density);
    renderPaint.setColor(withAlpha(fg, 0x80));

    gridPaint.setStyle(Paint.Style.STROKE);
    gridPaint.setStrokeWidth(1 * density);
    gridPaint.setColor(withAlpha(fg, 0x24));

    axisPaint.setStyle(Paint.Style.STROKE);
    axisPaint.setStrokeWidth(1 * density);
    axisPaint.setColor(withAlpha(fg, 0x4D));

    markerPaint.setStyle(Paint.Style.STROKE);
    markerPaint.setStrokeWidth(1.5f * density);
    markerPaint.setColor(withAlpha(fg, 0x66));

    labelPaint.setColor(withAlpha(fg, 0x99));
    labelPaint.setTextSize(labelSize);
  }

  /** Replace the drawn sections. {@code null} means the equalizer is off, drawn as a flat line. */
  void setSections(ParametricEq.Section[] sections) {
    this.sections = sections;
    invalidate();
  }

  /** Mark one section's centre frequency, or {@code -1} for none. Set while a section's frequency
   *  and Q controls are open, so the plot says where on the spectrum that band is sitting; the
   *  other four would only be clutter. */
  void setMarkedSlot(int slot) {
    if (markedSlot == slot) return;
    markedSlot = slot;
    invalidate();
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    super.onMeasure(widthSpec, heightSpec);
    setMeasuredDimension(getMeasuredWidth(), Math.round(112 * density));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float left = padLeft;
    float right = getWidth() - padRight;
    float top = padTop;
    float bottom = getHeight() - padBottom;
    if (right <= left || bottom <= top) return;
    float plotWidth = right - left;
    float plotHeight = bottom - top;

    drawGrid(canvas, left, right, top, bottom, plotWidth, plotHeight);
    drawRendered(canvas, left, top, plotWidth, plotHeight);
    drawTarget(canvas, left, top, plotWidth, plotHeight);
    drawMarker(canvas, left, top, bottom, plotWidth);
  }

  private void drawGrid(Canvas canvas, float left, float right, float top, float bottom,
                        float plotWidth, float plotHeight) {
    for (int db = -12; db <= 12; db += 6) {
      if (db == 0) continue;
      float y = yFor(db, top, plotHeight);
      canvas.drawLine(left, y, right, y, gridPaint);
    }
    float zero = yFor(0, top, plotHeight);
    canvas.drawLine(left, zero, right, zero, axisPaint);

    float ascent = -labelPaint.ascent();
    canvas.drawText("+12", 0, yFor(12, top, plotHeight) + ascent / 2f, labelPaint);
    canvas.drawText("-12", 0, yFor(-12, top, plotHeight) + ascent / 2f, labelPaint);

    drawFreqTick(canvas, 100, "100", left, top, bottom, plotWidth);
    drawFreqTick(canvas, 1000, "1k", left, top, bottom, plotWidth);
    drawFreqTick(canvas, 10000, "10k", left, top, bottom, plotWidth);
  }

  private void drawFreqTick(Canvas canvas, double hz, String label, float left, float top,
                            float bottom, float plotWidth) {
    float x = xFor(hz, left, plotWidth);
    canvas.drawLine(x, top, x, bottom, gridPaint);
    float w = labelPaint.measureText(label);
    canvas.drawText(label, x - w / 2f, bottom + (-labelPaint.ascent()) + 2 * density, labelPaint);
  }

  private void drawTarget(Canvas canvas, float left, float top, float plotWidth, float plotHeight) {
    targetPath.rewind();
    for (int i = 0; i <= CURVE_SAMPLES; i++) {
      double hz = F_LO * Math.exp(LOG_SPAN * i / (double) CURVE_SAMPLES);
      float x = xFor(hz, left, plotWidth);
      float y = yFor(ParametricEq.responseDb(sections, hz), top, plotHeight);
      if (i == 0) targetPath.moveTo(x, y); else targetPath.lineTo(x, y);
    }
    canvas.drawPath(targetPath, curvePaint);
  }

  /**
   * The stepped line: the band grid after the effect's own bin rounding. Bands whose cutoffs round
   * to the same bin carry no bins at all and are skipped, which is exactly why the low end of this
   * line is coarse — there are simply not enough bins down there to hold the grid.
   */
  private void drawRendered(Canvas canvas, float left, float top,
                            float plotWidth, float plotHeight) {
    int blockSize = ParametricEq.blockSizeFor(ASSUMED_SAMPLE_RATE);
    double binHz = ASSUMED_SAMPLE_RATE / (double) blockSize;
    renderPath.rewind();
    boolean started = false;
    int prevBin = -1;
    for (int b = 0; b < ParametricEq.BAND_COUNT; b++) {
      int stopBin = ParametricEq.bandUpperBin(b, ASSUMED_SAMPLE_RATE, blockSize);
      int startBin = prevBin + 1;
      if (stopBin > prevBin) prevBin = stopBin;
      if (startBin > stopBin) continue;                      // collapsed: below the bin spacing
      double fLo = Math.max(F_LO, startBin * binHz);
      double fHi = Math.min(F_HI, (stopBin + 1) * binHz);
      if (fHi <= fLo) continue;
      float y = yFor(ParametricEq.responseDb(sections, ParametricEq.bandCenterHz(b)), top, plotHeight);
      float x0 = xFor(fLo, left, plotWidth);
      float x1 = xFor(fHi, left, plotWidth);
      if (!started) { renderPath.moveTo(x0, y); started = true; } else { renderPath.lineTo(x0, y); }
      renderPath.lineTo(x1, y);
    }
    if (started) canvas.drawPath(renderPath, renderPaint);
  }

  /** Full-height rule at the marked section's centre frequency. Deliberately stronger than the
   *  decade gridlines so it reads as "this is the band you are editing" rather than as scale. */
  private void drawMarker(Canvas canvas, float left, float top, float bottom, float plotWidth) {
    if (sections == null || markedSlot < 0 || markedSlot >= sections.length) return;
    ParametricEq.Section s = sections[markedSlot];
    if (s == null || s.freqHz <= 0) return;
    float x = xFor(s.freqHz, left, plotWidth);
    canvas.drawLine(x, top, x, bottom, markerPaint);
  }

  private static int withAlpha(int color, int alpha) {
    return (color & 0x00FFFFFF) | (alpha << 24);
  }

  private static float xFor(double hz, float left, float plotWidth) {
    double clamped = Math.max(F_LO, Math.min(F_HI, hz));
    return left + (float) (Math.log(clamped / F_LO) / LOG_SPAN * plotWidth);
  }

  private static float yFor(double db, float top, float plotHeight) {
    double clamped = Math.max(-DB_RANGE, Math.min(DB_RANGE, db));
    return top + (float) ((DB_RANGE - clamped) / (2 * DB_RANGE) * plotHeight);
  }
}
