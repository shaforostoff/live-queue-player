package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.AudioFormat;
import android.net.Uri;
import android.util.Log;

/**
 * Decodes a track with {@link PcmDecoder} and feeds it to the native analysis.
 *
 * <p>The audio is streamed rather than collected in Java first. The analysis needs the whole track
 * resident — it normalises its onset envelope by the track's overall level, which is not knowable
 * until the last sample — but the native collector downmixes and resamples on the way in, so what
 * it holds is bounded by the track's duration rather than its sample rate. Buffering the 16-bit
 * stream on the Java heap first would cost about 31 MB for a three minute stereo track, on top of
 * everything the collector holds anyway.
 */
final class NativeBpmEngine implements BpmEngine {

    private static final String TAG = "BpmEngine";

    /** Comfortably more than one decoded frame, so a read rarely returns a partial one. */
    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * Consecutive "codec not ready" reads before giving up. {@link PcmDecoder#read} uses a 5 ms
     * dequeue timeout, so this is about ten seconds of a decoder producing nothing at all — which
     * is a wedged codec, not a slow file.
     */
    private static final int MAX_IDLE_READS = 2000;

    private final Context context;

    NativeBpmEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean available() {
        return BpmNative.isSupported();
    }

    @Override
    public int measure(Uri uri, BpmJob job) {
        if (uri == null || !available()) return RESULT_UNAVAILABLE;
        if (job.isCancelled()) return RESULT_CANCELLED;

        final long startedAt = android.os.SystemClock.elapsedRealtime();
        PcmDecoder decoder = null;
        long handle = 0;
        try {
            decoder = new PcmDecoder(context, uri);

            // A codec publishing float PCM would otherwise be read as 16-bit and yield a
            // thoroughly plausible wrong answer. Skip rather than guess.
            if (decoder.pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) return RESULT_UNAVAILABLE;

            final int rate = decoder.sampleRate;
            final int channels = decoder.channelCount;
            if (rate <= 0 || channels < 1) return RESULT_UNAVAILABLE;

            handle = BpmNative.nativeOpen(rate, channels, BpmNative.MAX_ANALYSIS_SECONDS);
            if (handle == 0) return RESULT_UNAVAILABLE;

            final long session = handle;
            job.setCanceller(() -> BpmNative.nativeCancel(session));

            final byte[] buf = new byte[BUFFER_BYTES];
            int idleReads = 0;
            while (!job.isCancelled()) {
                final int n = decoder.read(buf, 0, buf.length);
                if (n < 0) break;                                   // end of stream, or a read error
                if (n == 0) {
                    if (++idleReads > MAX_IDLE_READS) return RESULT_UNAVAILABLE;
                    continue;
                }
                idleReads = 0;

                // HE-AAC with implicit SBR only reveals its true rate after the first frame, and
                // PcmDecoder adopts the change mid-stream. The analysis has already laid its frame
                // grid on the old rate by then, so the honest thing is to abandon the track.
                if (decoder.sampleRate != rate || decoder.channelCount != channels) {
                    return RESULT_UNAVAILABLE;
                }

                if (!BpmNative.nativeAddPcm16(handle, buf, 0, n)) break;   // duration cap reached
            }

            if (job.isCancelled()) return RESULT_CANCELLED;

            final double[] detail = BuildConfig.DEBUG ? new double[BpmNative.DETAIL_LENGTH] : null;
            final int bpm = BpmNative.nativeFinish(handle, detail);
            if (bpm == BpmNative.RESULT_CANCELLED) return RESULT_CANCELLED;

            if (BuildConfig.DEBUG && detail != null) {
                Log.d(TAG, String.format(java.util.Locale.US,
                        "%d BPM  beat=%.1f  %s conf=%.2f  audio=%.0fs  took=%dms  %s",
                        bpm, detail[BpmNative.DETAIL_BEAT_BPM],
                        BpmNative.rhythmName((int) detail[BpmNative.DETAIL_RHYTHM]),
                        detail[BpmNative.DETAIL_CONFIDENCE], detail[BpmNative.DETAIL_DURATION],
                        android.os.SystemClock.elapsedRealtime() - startedAt, uri.getLastPathSegment()));
            }
            return bpm;
        } catch (Throwable t) {
            // Throwable, not Exception: an OutOfMemoryError here must cost one track's tempo, and
            // an uncaught one on a worker thread would take the process and the playback service
            // with it. See the comment in App.java.
            if (BuildConfig.DEBUG) Log.d(TAG, "analysis failed for " + uri, t);
            return RESULT_UNAVAILABLE;
        } finally {
            job.setCanceller(null);
            if (handle != 0) BpmNative.nativeClose(handle);
            if (decoder != null) decoder.close();
        }
    }
}
