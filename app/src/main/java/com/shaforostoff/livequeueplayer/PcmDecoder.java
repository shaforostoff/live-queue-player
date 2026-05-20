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
    private final MediaExtractor extractor = new MediaExtractor();
    private final MediaCodec codec;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
    private boolean sawInputEOS;
    boolean sawOutputEOS;
    private volatile boolean closed;
    final int sampleRate;
    final int channelCount;
    final long durationUs;
    volatile long positionUs;

    PcmDecoder(Context context, Uri uri) throws IOException {
        if (AiffConverter.isAiff(context, uri)) {
            extractor.setDataSource(new AiffMediaDataSource(context, uri));
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
    }

    /** Returns bytes decoded into buf, 0 if codec not ready yet, -1 on EOS/error. */
    synchronized int read(byte[] buf, int offset, int length) {
        if (closed || sawOutputEOS) return -1;

        if (!sawInputEOS) {
            int inIdx = codec.dequeueInputBuffer(0);
            if (inIdx >= 0) {
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
        }

        int outIdx = codec.dequeueOutputBuffer(bufInfo, 5_000);
        if (outIdx >= 0) {
            if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                sawOutputEOS = true;
                codec.releaseOutputBuffer(outIdx, false);
                return -1;
            }
            int available = Math.min(bufInfo.size, length);
            ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
            outBuf.position(bufInfo.offset);
            outBuf.get(buf, offset, available);
            codec.releaseOutputBuffer(outIdx, false);
            return available;
        }
        return 0;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try { codec.stop(); } catch (Exception ignored) {}
        codec.release();
        extractor.release();
    }
}
