package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Decodes compressed audio to raw PCM using MediaCodec + MediaExtractor.
 * All methods are synchronized so the write thread and stop thread don't race.
 */
final class PcmDecoder {
    /** Output-format probe budget; × the 5 ms dequeue timeout, so ~1 s worst case. */
    private static final int PRIME_ITERATIONS = 200;

    private final MediaExtractor extractor = new MediaExtractor();
    private final MediaCodec codec;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
    private boolean sawInputEOS;
    boolean sawOutputEOS;
    private volatile boolean closed;
    /** Output buffer we hold across reads until fully drained, or -1 when none. */
    private int pendingOutIdx = -1;
    private int pendingPos;
    private int pendingEnd;
    /** Rate/layout of the PCM the codec emits — the container's values are only the starting guess. */
    volatile int sampleRate;
    volatile int channelCount;
    final long durationUs;
    volatile long positionUs;

    PcmDecoder(Context context, Uri uri) throws IOException {
        if (AiffConverter.isAiff(context, uri)) {
            extractor.setDataSource(new AiffMediaDataSource(context, uri));
        } else if (AlacMediaDataSource.shouldUseFor(context, uri)) {
            // ALAC has no platform decoder here; present it as in-memory PCM/WAV, like AudioPlayer does.
            extractor.setDataSource(new AlacMediaDataSource(context, uri));
        } else {
            extractor.setDataSource(context, uri, null);
        }
        int audioIdx = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioIdx = i;
                break;
            }
        }
        if (audioIdx < 0) throw new IOException("no audio track");
        extractor.selectTrack(audioIdx);
        MediaFormat fmt = extractor.getTrackFormat(audioIdx);
        // Starting guess only; prime() replaces these with the codec's actual output format below.
        sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        channelCount = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        durationUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();
        try {
            prime();
        } catch (Exception e) {
            close(); // don't leak the codec instance — the caller just drops us
            throw new IOException("codec priming failed", e);
        }
    }

    /**
     * Pump the codec until it publishes its output format, and adopt that.
     *
     * <p>The container can lie about the PCM the decoder produces. HE-AAC declares the SBR core
     * config — half the real sample rate — and HE-AAC v2 declares mono for parametric stereo; with
     * implicit SBR signalling the decoder only reports the true rate after its first frame. The
     * caller configures its AudioTrack from these fields before the first read, so learn them here:
     * otherwise a 44.1 kHz HE-AAC track drives a 22.05 kHz sink and plays at half speed.
     *
     * <p>Gives up quietly after {@link #PRIME_ITERATIONS}; the container's values then stand, and a
     * later format change is still picked up by {@link #read}.
     */
    private void prime() {
        for (int i = 0; i < PRIME_ITERATIONS && !sawOutputEOS; i++) {
            feedInput();
            int outIdx = codec.dequeueOutputBuffer(bufInfo, 5_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                adoptOutputFormat();
                return;
            }
            if (outIdx >= 0) {
                // The format change should precede any buffer, but don't bet the rate on that.
                adoptOutputFormat();
                if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                    codec.releaseOutputBuffer(outIdx, false);
                } else {
                    hold(outIdx);
                }
                return;
            }
        }
    }

    /** Take sample rate / channel count from the codec's current output format, if it has one. */
    private void adoptOutputFormat() {
        MediaFormat out;
        try {
            out = codec.getOutputFormat();
        } catch (Exception ignored) {
            return; // not published yet on this device's codec
        }
        if (out == null) return;
        if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            int rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (rate > 0) sampleRate = rate;
        }
        if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            int channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (channels > 0) channelCount = channels;
        }
    }

    /** Queue one access unit, or the end-of-stream marker once the extractor runs dry. */
    private void feedInput() {
        if (sawInputEOS) return;
        int inIdx = codec.dequeueInputBuffer(0);
        if (inIdx < 0) return;
        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
        int n = extractor.readSampleData(inBuf, 0);
        if (n < 0) {
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            sawInputEOS = true;
        } else {
            positionUs = extractor.getSampleTime();
            codec.queueInputBuffer(inIdx, 0, n, positionUs, 0);
            extractor.advance();
        }
    }

    /** Returns bytes decoded into buf, 0 if codec not ready yet, -1 on EOS/error. */
    synchronized int read(byte[] buf, int offset, int length) {
        if (closed || sawOutputEOS) return -1;

        if (pendingOutIdx >= 0) return drainPending(buf, offset, length);

        feedInput();

        int outIdx = codec.dequeueOutputBuffer(bufInfo, 5_000);
        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            adoptOutputFormat(); // arrives before the buffers it applies to, so the caller stays in step
            return 0;
        }
        if (outIdx >= 0) {
            if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                sawOutputEOS = true;
                codec.releaseOutputBuffer(outIdx, false);
                return -1;
            }
            hold(outIdx);
            return drainPending(buf, offset, length);
        }
        return 0;
    }

    /**
     * Take ownership of a decoded output buffer. Its extent is copied out of {@link #bufInfo}, which
     * the next dequeue overwrites.
     */
    private void hold(int outIdx) {
        pendingOutIdx = outIdx;
        pendingPos = bufInfo.offset;
        pendingEnd = bufInfo.offset + bufInfo.size;
    }

    /**
     * Copy as much of the held buffer as fits, releasing it only once fully consumed — a decoded
     * frame can be larger than the caller's buffer (HE-AAC emits 2048 samples per frame), and
     * dropping the tail would both glitch the audio and desync the reported position.
     */
    private int drainPending(byte[] buf, int offset, int length) {
        int available = Math.min(pendingEnd - pendingPos, length);
        ByteBuffer outBuf = codec.getOutputBuffer(pendingOutIdx);
        outBuf.position(pendingPos);
        outBuf.get(buf, offset, available);
        pendingPos += available;
        if (pendingPos >= pendingEnd) {
            codec.releaseOutputBuffer(pendingOutIdx, false);
            pendingOutIdx = -1;
        }
        return available;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try { codec.stop(); } catch (Exception ignored) {}
        codec.release();
        extractor.release();
    }
}
