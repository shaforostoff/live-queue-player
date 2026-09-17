package com.shaforostoff.livequeueplayer;

/**
 * The native BPM analysis, or a stub that politely declines.
 *
 * <p>The real analysis is built for arm64-v8a only; every other ABI links a stub whose
 * {@link #nativeAvailable()} returns false. The stub is why the library still loads there — an APK
 * with no {@code lib/<abi>/} entry is uninstallable on that ABI, so the alternative to a stub is not
 * "no BPM on arm32", it is "no app on arm32". Callers ask {@link #isSupported()} and do nothing when
 * it is false; see {@code app/src/main/cpp/CMakeLists.txt}.
 *
 * <p>A session is one track: {@link #nativeOpen} it, feed it 16-bit PCM as the decoder produces it,
 * {@link #nativeFinish} it, and {@link #nativeClose} it from a {@code finally}. Handles are never
 * reused, so closing twice — or using a handle after close — is a lookup miss rather than a
 * use-after-free. Analysis runs on the calling thread; run tracks in parallel, not one track across
 * threads.
 *
 * <p>{@link #nativeCancel} and {@link #nativeProgress} are safe from any thread at any time,
 * including while another thread is inside {@code nativeFinish} — which is the point of them, since
 * that call is the multi-second stretch nothing else can interrupt.
 */
final class BpmNative {

    /** Seconds of audio to analyse at most. See {@code nativeOpen} in bpm_jni.cpp. */
    static final int MAX_ANALYSIS_SECONDS = 240;

    /** {@code nativeFinish} returned this because the session was cancelled, not because it failed. */
    static final int RESULT_CANCELLED = -1;

    /** Indices into the optional {@code detail} array handed to {@link #nativeFinish}. */
    static final int DETAIL_LENGTH = 5;
    static final int DETAIL_BPM = 0;
    static final int DETAIL_BEAT_BPM = 1;
    static final int DETAIL_CONFIDENCE = 2;
    static final int DETAIL_RHYTHM = 3;
    static final int DETAIL_DURATION = 4;

    /** bpmcore's rhythm_class enum, for the debug log. Indexed by {@link #DETAIL_RHYTHM}. */
    private static final String[] RHYTHM_NAMES = { "Tango", "Vals", "Milonga", "Reggae", "Other" };

    private static final boolean SUPPORTED;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("bpmnative");
            ok = nativeAvailable();
        } catch (Throwable ignored) {
            // A missing or half-built .so must cost the BPM feature, not the app. Throwable
            // rather than Exception: the failure here is UnsatisfiedLinkError, which is an Error.
        }
        SUPPORTED = ok;
    }

    /** True only where the real analysis is linked — i.e. arm64-v8a with the library present. */
    static boolean isSupported() {
        return SUPPORTED;
    }

    static String rhythmName(int cls) {
        return cls >= 0 && cls < RHYTHM_NAMES.length ? RHYTHM_NAMES[cls] : "Other";
    }

    private static native boolean nativeAvailable();

    /**
     * pffft's SIMD width: 4 where it reached NEON, 1 where it silently fell back to scalar
     * (a sixfold slowdown that reports itself no other way), 0 from the stub.
     */
    static native int nativeSimdSize();

    /** Returns a session handle, or 0 if the arguments are unusable or the buffer could not be had. */
    static native long nativeOpen(int sampleRate, int channels, int maxSeconds);

    /**
     * Feeds interleaved little-endian 16-bit PCM. Returns false once no more is wanted — the
     * duration cap was reached, the session was cancelled, or the handle is stale.
     */
    static native boolean nativeAddPcm16(long handle, byte[] pcm, int offset, int length);

    /**
     * Analyses what was fed. Returns a BPM in 1..400, 0 where no tempo could be measured, or
     * {@link #RESULT_CANCELLED}. {@code detail} may be null, or an array of at least
     * {@link #DETAIL_LENGTH} doubles to receive the full analysis. Does not free the handle.
     */
    static native int nativeFinish(long handle, double[] detail);

    /** Asks the session to stop. Safe from any thread, including during {@code nativeFinish}. */
    static native void nativeCancel(long handle);

    /** Frees the session. Idempotent, and safe to call on a handle that never opened. */
    static native void nativeClose(long handle);

    /** Analysis progress, 0..1. Polled rather than pushed, so there is no callback into Java. */
    static native float nativeProgress(long handle);

    /** Sessions currently open. For a debug-only leak assertion; always 0 when nothing is running. */
    static native int nativeOpenSessions();

    private BpmNative() {}
}
