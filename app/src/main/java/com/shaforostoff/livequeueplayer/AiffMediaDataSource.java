package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.MediaDataSource;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * Presents an AIFF/AIFC file to MediaPlayer / MediaExtractor as a WAV stream, converting on the fly.
 *
 * <p>Only the chunk headers are read up front. Each {@link #readAt} serves the synthesized 44-byte
 * WAV header from memory and the PCM straight from the file at the matching offset, byte-swapped
 * (AIFF is big-endian, WAV little-endian) in a small scratch buffer. Nothing is held beyond that
 * buffer, so a track of any length plays — the old whole-file conversion peaked at ~2.3x the file
 * size in heap and cut tracks off at 100 MB — and opening the source no longer reads the file.
 *
 * <p>Supported variants: AIFF (big-endian PCM) and AIFC compression types NONE, twos (big-endian
 * PCM) and sowt (little-endian PCM), at 8, 16, 24 or 32 bits.
 */
final class AiffMediaDataSource extends MediaDataSource {

    private static final int WAV_HEADER_SIZE = 44;
    /** The WAV size fields are unsigned 32-bit; RIFF size = data size + 36. */
    private static final long MAX_WAV_PCM = 0xFFFFFFFFL - 36;

    private final FileInputStream in;
    private final FileChannel channel;
    private final byte[] wavHeader = new byte[WAV_HEADER_SIZE];
    /** File offset of the first PCM byte. */
    private final long pcmStart;
    /** PCM bytes served, a whole number of frames. */
    private final long pcmLength;
    private final int bytesPerSample;
    private final boolean littleEndian;
    private ByteBuffer scratch = ByteBuffer.allocate(0);

    // Extension-based on purpose: a ContentResolver.getType() query would block the main thread.
    static boolean isAiff(Uri uri) {
        String seg = uri.getLastPathSegment();
        if (seg == null) seg = uri.getPath();
        if (seg == null) return false;
        String lower = seg.toLowerCase(Locale.ROOT);
        return lower.endsWith(".aiff") || lower.endsWith(".aif");
    }

    AiffMediaDataSource(Context ctx, Uri uri) throws IOException {
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Cannot open: " + uri);
        in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        channel = in.getChannel();
        try {
            long fileSize = channel.size();
            byte[] head = new byte[22];
            if (read(head, 12, 0) < 12) throw new IOException("AIFF: file too small");
            if (!fourCc(head, 0, "FORM")) throw new IOException("AIFF: missing FORM chunk");
            boolean isAifc = fourCc(head, 8, "AIFC");
            if (!isAifc && !fourCc(head, 8, "AIFF")) throw new IOException("AIFF: not an AIFF/AIFC file");

            int channels = 0, sampleRate = 0, bitsPerSample = 0;
            boolean sowt = false;
            long ssndStart = -1, ssndSize = 0;
            // Walk the chunk headers only; sizes are unsigned, so pos strictly advances.
            for (long pos = 12; read(head, 8, pos) == 8; ) {
                long chunkSize = readInt32BE(head, 4) & 0xFFFFFFFFL;
                long dataStart = pos + 8;
                if (fourCc(head, 0, "COMM")) {
                    int need = isAifc ? 22 : 18;
                    if (read(head, need, dataStart) < need) throw new IOException("AIFF: truncated COMM chunk");
                    channels = readInt16BE(head, 0);
                    // sampleFrames at +2 (4 bytes) — not needed
                    bitsPerSample = readInt16BE(head, 6);
                    sampleRate = (int) readExtended80(head, 8);
                    if (isAifc) {
                        String compression = new String(head, 18, 4, java.nio.charset.StandardCharsets.US_ASCII);
                        switch (compression) {
                            case "NONE": case "twos": sowt = false; break;
                            case "sowt": sowt = true; break;
                            default: throw new IOException("AIFC: unsupported compression: " + compression);
                        }
                    }
                } else if (fourCc(head, 0, "SSND")) {
                    // 4-byte offset into the sample data, 4-byte block size, then PCM
                    if (read(head, 4, dataStart) < 4) throw new IOException("AIFF: truncated SSND chunk");
                    long offset = readInt32BE(head, 0) & 0xFFFFFFFFL;
                    ssndStart = dataStart + 8 + offset;
                    ssndSize = chunkSize - 8 - offset;
                }
                pos = dataStart + chunkSize + (chunkSize & 1); // chunks are word-aligned
            }

            if (channels <= 0 || sampleRate <= 0 || bitsPerSample == 0)
                throw new IOException("AIFF: COMM chunk not found or incomplete");
            if (ssndStart < 0) throw new IOException("AIFF: SSND chunk not found");
            if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32)
                throw new IOException("AIFF: unsupported bit depth: " + bitsPerSample);

            bytesPerSample = bitsPerSample / 8;
            littleEndian = sowt;
            int frameSize = channels * bytesPerSample;
            // A truncated file ends early (and possibly mid-frame): serve only the whole frames it has.
            long length = Math.min(Math.min(ssndSize, fileSize - ssndStart), MAX_WAV_PCM);
            pcmLength = Math.max(0, length - length % frameSize);
            pcmStart = ssndStart;

            writeAscii(wavHeader, 0, "RIFF");
            writeInt32LE(wavHeader, 4, (int) (36 + pcmLength));
            writeAscii(wavHeader, 8, "WAVE");
            writeAscii(wavHeader, 12, "fmt ");
            writeInt32LE(wavHeader, 16, 16);
            writeInt16LE(wavHeader, 20, 1); // PCM
            writeInt16LE(wavHeader, 22, channels);
            writeInt32LE(wavHeader, 24, sampleRate);
            writeInt32LE(wavHeader, 28, sampleRate * frameSize); // byte rate
            writeInt16LE(wavHeader, 32, frameSize);              // block align
            writeInt16LE(wavHeader, 34, bitsPerSample);
            writeAscii(wavHeader, 36, "data");
            writeInt32LE(wavHeader, 40, (int) pcmLength);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        long total = WAV_HEADER_SIZE + pcmLength;
        if (position >= total) return -1;
        size = (int) Math.min(size, total - position);
        int done = 0;
        if (position < WAV_HEADER_SIZE) {
            done = (int) Math.min(size, WAV_HEADER_SIZE - position);
            System.arraycopy(wavHeader, (int) position, buffer, offset, done);
        }
        if (done == size) return done;

        // Byte-swapping works on whole samples, so read from the start of the sample holding the
        // first wanted byte through the end of the sample holding the last one.
        long pcmPos = position + done - WAV_HEADER_SIZE;
        long from = pcmPos - pcmPos % bytesPerSample;
        long end = pcmPos + (size - done);
        long to = Math.min(pcmLength, end + (bytesPerSample - end % bytesPerSample) % bytesPerSample);
        int len = (int) (to - from);
        if (scratch.capacity() < len) scratch = ByteBuffer.allocate(len);
        byte[] b = scratch.array();
        int got = read(b, len, pcmStart + from);
        got -= got % bytesPerSample;
        int skip = (int) (pcmPos - from);
        if (got <= skip) return done > 0 ? done : -1; // the file shrank under us
        toWav(b, got);
        int n = Math.min(size - done, got - skip);
        System.arraycopy(b, skip, buffer, offset + done, n);
        return done + n;
    }

    /** AIFF sample bytes to WAV's: reverse big-endian samples; 8-bit flips signed to unsigned. */
    private void toWav(byte[] b, int len) {
        if (bytesPerSample == 1) {
            for (int i = 0; i < len; i++) b[i] ^= (byte) 0x80;
        } else if (!littleEndian) {
            for (int i = 0; i < len; i += bytesPerSample) {
                for (int lo = i, hi = i + bytesPerSample - 1; lo < hi; lo++, hi--) {
                    byte t = b[lo]; b[lo] = b[hi]; b[hi] = t;
                }
            }
        }
    }

    @Override
    public long getSize() {
        return WAV_HEADER_SIZE + pcmLength;
    }

    @Override
    public synchronized void close() throws IOException {
        in.close();
    }

    /** Positional read of up to {@code len} bytes into {@code dst}; returns the count (short at EOF). */
    private int read(byte[] dst, int len, long position) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(dst, 0, len);
        while (buf.hasRemaining()) {
            int n = channel.read(buf, position + buf.position());
            if (n < 0) break;
        }
        return buf.position();
    }

    // ---- helpers ----

    private static boolean fourCc(byte[] b, int off, String id) {
        return b[off] == id.charAt(0) && b[off + 1] == id.charAt(1)
            && b[off + 2] == id.charAt(2) && b[off + 3] == id.charAt(3);
    }

    private static int readInt16BE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readInt32BE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** Reads an IEEE 754 80-bit extended float (big-endian) and returns it as a long. */
    private static long readExtended80(byte[] b, int off) {
        int exponent = ((b[off] & 0x7F) << 8) | (b[off + 1] & 0xFF);
        long mantissa = 0;
        for (int i = 0; i < 8; i++) {
            mantissa = (mantissa << 8) | (b[off + 2 + i] & 0xFF);
        }
        if (exponent == 0 && mantissa == 0) return 0;
        int shift = exponent - 16383 - 63;
        if (shift > 0) return mantissa << shift;
        if (shift < 0) return mantissa >>> (-shift);
        return mantissa;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void writeInt16LE(byte[] b, int off, int v) {
        b[off]     = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static void writeInt32LE(byte[] b, int off, int v) {
        b[off]     = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }
}
