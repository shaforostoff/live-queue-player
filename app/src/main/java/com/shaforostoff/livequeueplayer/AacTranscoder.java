package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Re-encodes a track to AAC-LC in an .m4a, for a Bluetooth send that trades quality for time: a
 * FLAC is cut to a fifth or so. Decoding goes through {@link PcmDecoder}, so whatever plays here
 * (ALAC and AIFF included) converts; the tags are carried over by {@link Mp4TagWriter}.
 *
 * <p>The AAC encoder takes at most 48 kHz, so hi-res input is halved (or quartered) on the way by
 * a half-band low-pass. Other rates above 48 kHz, and more than two channels, are not converted.
 */
final class AacTranscoder {

    private AacTranscoder() {}

    static final int BITRATE_PER_CHANNEL = 64_000; // 128 kbit/s stereo
    /**
     * A track is worth converting above this average rate. Measured from the file size, so tags
     * and cover art count too; the margin over the 128 kbit/s target keeps a 128 kbit/s MP3 with
     * a big cover from being re-encoded at its own rate, which would cost quality and save nothing.
     */
    static final int WORTH_ABOVE_BPS = 144_000;
    /** Embedded art bigger than this is left out: it would eat into what compressing saves. */
    private static final int MAX_COVER_BYTES = 256 * 1024;
    private static final long TIMEOUT_US = 10_000;

    interface Progress {
        /** {@code percent} of the track done; false to give up. */
        boolean step(int percent);
    }

    /** The track's duration in ms, or -1 if unknown. Any thread but the UI; it opens the file. */
    static long durationMs(Context context, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return -1;
            r.setDataSource(pfd.getFileDescriptor());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d.trim()) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** Whether a file of {@code size} bytes lasting {@code durationMs} would come out smaller. */
    static boolean worthCompressing(long size, long durationMs) {
        return size > 0 && durationMs > 0 && size * 8_000 / durationMs > WORTH_ABOVE_BPS;
    }

    /** About how big the AAC copy of a stereo track of {@code durationMs} comes out. */
    static long estimatedSize(long durationMs) {
        // The bitrate, plus ~2% for the container's sample tables.
        return durationMs * (2L * BITRATE_PER_CHANNEL) / 8_000 * 102 / 100;
    }

    /**
     * Writes {@code uri} as AAC to {@code out}. False, with {@code out} gone, if the track can't be
     * converted here, or {@code progress} gave up.
     */
    static boolean transcode(Context context, Uri uri, File out, Progress progress) {
        PcmDecoder decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean ok = false;
        try {
            decoder = new PcmDecoder(context, uri);
            int channels = decoder.channelCount;
            int factor = decimationFor(decoder.sampleRate);
            if (channels < 1 || channels > 2 || factor == 0) return false;
            int rate = decoder.sampleRate / factor;

            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_PER_CHANNEL * channels);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            ok = encode(decoder, new PcmSource(decoder, factor), encoder, muxer, rate, channels, progress);
            muxer.stop(); // throws if nothing was written
            if (ok) Mp4TagWriter.write(out, readTags(context, uri));
        } catch (Exception e) {
            ok = false;
        } finally {
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) { }
                encoder.release();
            }
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (decoder != null) decoder.close();
            //noinspection ResultOfMethodCallIgnored
            if (!ok) out.delete();
        }
        return ok;
    }

    private static boolean encode(PcmDecoder decoder, PcmSource source, MediaCodec encoder, MediaMuxer muxer,
                                  int rate, int channels, Progress progress) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] pcm = new short[4096 * channels];
        int track = -1;
        long frames = 0;
        boolean inputDone = false;
        int lastPercent = -1;
        while (true) {
            if (!inputDone) {
                int in = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (in >= 0) {
                    ByteBuffer buf = encoder.getInputBuffer(in);
                    buf.clear();
                    buf.order(ByteOrder.LITTLE_ENDIAN);
                    int room = Math.min(buf.remaining() / 2, pcm.length) / channels * channels;
                    int n = source.read(pcm, room);
                    long ptsUs = frames * 1_000_000L / rate;
                    if (n < 0) {
                        encoder.queueInputBuffer(in, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        buf.asShortBuffer().put(pcm, 0, n);
                        encoder.queueInputBuffer(in, 0, n * 2, ptsUs, 0);
                        frames += n / channels;
                    }
                }
            }
            int out = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track >= 0) throw new IOException("format changed twice");
                track = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
            } else if (out >= 0) {
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && info.size > 0) {
                    if (track < 0) throw new IOException("data before format");
                    ByteBuffer data = encoder.getOutputBuffer(out);
                    data.position(info.offset).limit(info.offset + info.size);
                    muxer.writeSampleData(track, data, info);
                }
                encoder.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return track >= 0;
            }
            int percent = decoder.durationUs > 0
                    ? (int) Math.min(99, decoder.positionUs * 100 / decoder.durationUs) : 0;
            if (percent != lastPercent) {
                lastPercent = percent;
                if (!progress.step(percent)) return false;
            }
        }
    }

    /** 1 for a rate the encoder takes, 2/4/8 to bring a hi-res one down to it, 0 if neither. */
    static int decimationFor(int rate) {
        for (int factor = 1; factor <= 8; factor *= 2) {
            if (rate % factor != 0) return 0;
            switch (rate / factor) {
                case 8000: case 11025: case 12000: case 16000: case 22050: case 24000:
                case 32000: case 44100: case 48000:
                    return factor;
            }
        }
        return 0;
    }

    /** The decoder's PCM as interleaved 16-bit samples, decimated by {@code factor}. */
    private static final class PcmSource {
        private final PcmDecoder decoder;
        private final int channels;
        private final HalfBand[] stages;
        private final byte[] bytes = new byte[16 * 1024];
        private int byteFill;
        private short[] ready = new short[0];
        private int readyPos, readyLen;
        private boolean ended;

        PcmSource(PcmDecoder decoder, int factor) {
            this.decoder = decoder;
            this.channels = decoder.channelCount;
            int n = Integer.numberOfTrailingZeros(factor);
            stages = new HalfBand[n];
            for (int i = 0; i < n; i++) stages[i] = new HalfBand(channels);
        }

        /** Fills up to {@code max} samples (whole frames); -1 once the track is done. */
        int read(short[] dst, int max) throws IOException {
            int n = 0;
            while (n < max) {
                if (readyPos == readyLen && !refill()) break;
                int take = Math.min(max - n, readyLen - readyPos);
                System.arraycopy(ready, readyPos, dst, n, take);
                readyPos += take;
                n += take;
            }
            return n == 0 && ended ? -1 : n;
        }

        /** Decodes the next stretch into {@link #ready}; false at the end of the track. */
        private boolean refill() throws IOException {
            while (!ended) {
                int got = decoder.read(bytes, byteFill, bytes.length - byteFill);
                if (got < 0) {
                    ended = true;
                    break;
                }
                if (got == 0) continue;
                byteFill += got;
                short[] pcm = toShorts();
                if (pcm.length == 0) continue;
                for (HalfBand stage : stages) pcm = stage.process(pcm);
                if (pcm.length == 0) continue;
                ready = pcm;
                readyPos = 0;
                readyLen = pcm.length;
                return true;
            }
            return false;
        }

        /** The whole frames in {@link #bytes} as 16-bit samples; a partial frame stays for later. */
        private short[] toShorts() throws IOException {
            int encoding = decoder.pcmEncoding;
            int width;
            switch (encoding) {
                case AudioFormat.ENCODING_PCM_8BIT: width = 1; break;
                case AudioFormat.ENCODING_PCM_16BIT: width = 2; break;
                case 21 /* ENCODING_PCM_24BIT_PACKED */: width = 3; break;
                case 22 /* ENCODING_PCM_32BIT */: case AudioFormat.ENCODING_PCM_FLOAT: width = 4; break;
                default: throw new IOException("pcm encoding " + encoding);
            }
            int frameBytes = width * channels;
            int count = byteFill / frameBytes * channels;
            short[] out = new short[count];
            for (int i = 0, p = 0; i < count; i++, p += width) {
                switch (width) {
                    case 1: out[i] = (short) (((bytes[p] & 0xFF) - 128) << 8); break;
                    case 2: out[i] = (short) ((bytes[p] & 0xFF) | (bytes[p + 1] << 8)); break;
                    case 3: out[i] = (short) ((bytes[p + 1] & 0xFF) | (bytes[p + 2] << 8)); break;
                    default: {
                        int v = (bytes[p] & 0xFF) | ((bytes[p + 1] & 0xFF) << 8)
                                | ((bytes[p + 2] & 0xFF) << 16) | (bytes[p + 3] << 24);
                        out[i] = encoding == AudioFormat.ENCODING_PCM_FLOAT
                                ? clamp(Float.intBitsToFloat(v) * 32768f) : (short) (v >> 16);
                    }
                }
            }
            int used = count / channels * frameBytes;
            System.arraycopy(bytes, used, bytes, 0, byteFill - used);
            byteFill -= used;
            return out;
        }
    }

    /**
     * Halves the sample rate of interleaved 16-bit PCM: a 47-tap Blackman-windowed half-band
     * low-pass, then every other frame. It is flat to ~17 kHz at a 44.1 kHz output — about where
     * the AAC encoder cuts at this bitrate — and what it lets through above that folds back above
     * it too.
     */
    static final class HalfBand {
        private static final int TAPS = 47;
        private static final float[] H = new float[TAPS];

        static {
            int m = TAPS / 2;
            double sum = 0;
            double[] h = new double[TAPS];
            for (int n = 0; n < TAPS; n++) {
                int k = n - m;
                double sinc = k == 0 ? 0.5 : Math.sin(Math.PI * k / 2) / (Math.PI * k);
                double window = 0.42 - 0.5 * Math.cos(2 * Math.PI * n / (TAPS - 1))
                        + 0.08 * Math.cos(4 * Math.PI * n / (TAPS - 1));
                h[n] = sinc * window;
                sum += h[n];
            }
            for (int n = 0; n < TAPS; n++) H[n] = (float) (h[n] / sum);
        }

        private final int channels;
        // Per channel: the last TAPS-1 input samples, then this call's.
        private float[][] history;
        private final int[] fill;

        HalfBand(int channels) {
            this.channels = channels;
            history = new float[channels][TAPS - 1];
            fill = new int[channels];
            for (int c = 0; c < channels; c++) fill[c] = TAPS - 1; // starts on silence
        }

        short[] process(short[] in) {
            int frames = in.length / channels;
            int capacity = TAPS - 1 + frames + 1;
            if (history[0].length < capacity) {
                float[][] grown = new float[channels][capacity];
                for (int c = 0; c < channels; c++) System.arraycopy(history[c], 0, grown[c], 0, fill[c]);
                history = grown;
            }
            for (int c = 0; c < channels; c++) {
                float[] h = history[c];
                int f = fill[c];
                for (int i = 0; i < frames; i++) h[f++] = in[i * channels + c];
                fill[c] = f;
            }
            int available = fill[0] - (TAPS - 1);   // frames whose filter window is complete
            int outFrames = available / 2;
            short[] out = new short[outFrames * channels];
            for (int c = 0; c < channels; c++) {
                float[] h = history[c];
                for (int o = 0; o < outFrames; o++) {
                    int base = o * 2;
                    float acc = H[TAPS / 2] * h[base + TAPS / 2];
                    for (int k = 1; k <= TAPS / 2; k += 2) {  // the even taps off centre are zero
                        acc += H[TAPS / 2 + k] * (h[base + TAPS / 2 + k] + h[base + TAPS / 2 - k]);
                    }
                    out[o * channels + c] = clamp(acc);
                }
                int consumed = outFrames * 2;
                System.arraycopy(h, consumed, h, 0, fill[c] - consumed);
                fill[c] -= consumed;
            }
            return out;
        }
    }

    private static short clamp(float v) {
        int r = Math.round(v);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, r));
    }

    /** The source's tags, as far as they can be read; their absence is no reason not to convert. */
    static Mp4TagWriter.Tags readTags(Context context, Uri uri) {
        Mp4TagWriter.Tags t = new Mp4TagWriter.Tags();
        // A throwaway extractor: its cache is the library's, and this file isn't in it.
        MetadataExtractor extractor = new MetadataExtractor(context.getContentResolver());
        MetadataExtractor.TagEntry e = extractor.readSortTags(uri);
        t.title = e.title;
        t.artist = e.artist;
        t.genre = e.genre;
        t.date = e.date;
        t.bpm = e.bpm;
        t.lyrics = extractor.readLyricsTag(uri);
        float gain = extractor.readReplayGain(uri);
        if (gain > 0f && gain != 1.0f) {
            t.replayGainTrack = String.format(Locale.ROOT, "%.2f dB", 20 * Math.log10(gain));
        }

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                r.setDataSource(pfd.getFileDescriptor());
                if (isBlank(t.title)) t.title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                if (isBlank(t.artist)) t.artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (isBlank(t.genre)) t.genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
                t.album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                t.albumArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                t.composer = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER);
                int[] track = numberOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
                t.track = track[0];
                t.trackTotal = track[1];
                int[] disc = numberOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER));
                t.disc = disc[0];
                t.discTotal = disc[1];
                byte[] cover = r.getEmbeddedPicture();
                if (cover != null && cover.length <= MAX_COVER_BYTES) t.cover = cover;
            }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return t;
    }

    /** "3" or "3/12" as {3, 0} or {3, 12}; {0, 0} if it isn't a number. */
    static int[] numberOf(String s) {
        int[] r = new int[2];
        if (s == null) return r;
        String[] parts = s.trim().split("/", 2);
        try {
            r[0] = Integer.parseInt(parts[0].trim());
            if (parts.length > 1) r[1] = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ignored) {
        }
        if (r[0] < 0 || r[0] > 0xFFFF) r[0] = 0;
        if (r[1] < 0 || r[1] > 0xFFFF) r[1] = 0;
        return r;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
