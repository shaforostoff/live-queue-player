package com.shaforostoff.livequeueplayer;

import android.content.Context;

/**
 * Common abstraction over the equalizer backends an {@link AudioPlayer} can attach to a session.
 * Two implementations exist, selected by API level: {@link EqualizerController} (graphic, all API
 * levels) and {@link DynamicsEqController} (parametric, API 28+). Both keep the live effect in sync
 * with the persisted settings and degrade to a silent no-op on unsupported devices.
 */
interface EqController {

  /** Re-read persisted settings and push them to the live effect. */
  void applySettings(Context context);

  /** Release the underlying native effect. */
  void release();
}
