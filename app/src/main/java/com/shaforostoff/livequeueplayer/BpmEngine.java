package com.shaforostoff.livequeueplayer;

import android.net.Uri;

/**
 * Measures one track's tempo. The real one decodes and runs the native analysis; a fake one stands
 * in for it on the JVM, where no {@code .so} can be loaded and the scheduling is what needs testing.
 */
interface BpmEngine {

    /** Analysed the audio and found no usable tempo. Worth remembering, so the file is not retried. */
    int RESULT_NO_TEMPO = 0;

    /** Stopped early. Nothing is known about this track; record nothing. */
    int RESULT_CANCELLED = -1;

    /**
     * Could not analyse at all — no decoder, an unreadable file, an exhausted codec. Distinct from
     * {@link #RESULT_NO_TEMPO} because this one should be retried when the folder is next opened,
     * and that one should not.
     */
    int RESULT_UNAVAILABLE = -2;

    /** False where the native analysis is not in this build — every ABI but arm64-v8a. */
    boolean available();

    /** A BPM in 1..400, or one of the {@code RESULT_*} constants above. */
    int measure(Uri uri, BpmJob job);
}
