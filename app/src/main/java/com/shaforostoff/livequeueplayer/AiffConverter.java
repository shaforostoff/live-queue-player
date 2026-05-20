package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Converts AIFF/AIFC audio to an in-memory WAV byte array so Android's
 * MediaPlayer / MediaExtractor / MediaMetadataRetriever can handle the file.
 *
 * Supported variants:
 *   - AIFF  (big-endian PCM)
 *   - AIFC  compression types: NONE, twos (big-endian PCM), sowt (little-endian PCM)
 *
 * Files larger than MAX_BYTES are truncated to the first MAX_BYTES of PCM data.
 */
final class AiffConverter {

    static final long MAX_BYTES = 100L * 1024 * 1024; // 100 MB

    static boolean isAiff(Context ctx, Uri uri) {
        String mime = ctx.getContentResolver().getType(uri);
        if (mime != null) {
            return mime.equalsIgnoreCase("audio/aiff") || mime.equalsIgnoreCase("audio/x-aiff");
        }
        String path = uri.getPath();
        if (path == null) return false;
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = path.substring(dot + 1).toLowerCase();
        return ext.equals("aiff") || ext.equals("aif");
    }

    /**
     * Reads an AIFF/AIFC stream and returns a WAV-formatted byte array.
     * PCM data is capped at MAX_BYTES; the WAV header reflects the actual decoded length.
     */
    static byte[] convert(InputStream in) throws IOException {
        // Cap the read so we never OOM on huge files; headers are tiny (<1 KB).
        long readLimit = MAX_BYTES + 4096;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[65536];
        int n;
        long total = 0;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
            total += n;
            if (total >= readLimit) break;
        }
        return convertBytes(buf.toByteArray());
    }

    // -------------------------------------------------------------------------

    private static byte[] convertBytes(byte[] src) throws IOException {
        if (src.length < 12) throw new IOException("AIFF: file too small");

        // FORM chunk header
        if (src[0] != 'F' || src[1] != 'O' || src[2] != 'R' || src[3] != 'M')
            throw new IOException("AIFF: missing FORM chunk");

        boolean isAifc;
        if (src[8] == 'A' && src[9] == 'I' && src[10] == 'F' && src[11] == 'F') {
            isAifc = false;
        } else if (src[8] == 'A' && src[9] == 'I' && src[10] == 'F' && src[11] == 'C') {
            isAifc = true;
        } else {
            throw new IOException("AIFF: not an AIFF/AIFC file");
        }

        // Parse chunks
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int ssndStart = -1;
        int ssndSize = 0;
        boolean littleEndian = false; // sowt

        int pos = 12;
        while (pos + 8 <= src.length) {
            String id = new String(src, pos, 4);
            int chunkSize = readInt32BE(src, pos + 4);
            int dataStart = pos + 8;

            if (id.equals("COMM")) {
                channels = readInt16BE(src, dataStart);
                // sampleFrames at dataStart+2 (4 bytes) — not needed
                bitsPerSample = readInt16BE(src, dataStart + 6);
                sampleRate = (int) readExtended80(src, dataStart + 8);
                if (isAifc) {
                    // compression type is a 4-byte OSType at dataStart+18
                    if (dataStart + 22 <= src.length) {
                        String comprType = new String(src, dataStart + 18, 4);
                        switch (comprType) {
                            case "NONE":
                            case "twos":
                                littleEndian = false;
                                break;
                            case "sowt":
                                littleEndian = true;
                                break;
                            default:
                                throw new IOException("AIFC: unsupported compression: " + comprType);
                        }
                    }
                }
            } else if (id.equals("SSND")) {
                // 4-byte offset into sample data, 4-byte block size, then PCM
                int ssndOffset = readInt32BE(src, dataStart);
                ssndStart = dataStart + 8 + ssndOffset;
                ssndSize = chunkSize - 8 - ssndOffset;
            }

            // chunks are word-aligned (even size)
            pos = dataStart + chunkSize + (chunkSize & 1);
        }

        if (channels == 0 || sampleRate == 0 || bitsPerSample == 0)
            throw new IOException("AIFF: COMM chunk not found or incomplete");
        if (ssndStart < 0)
            throw new IOException("AIFF: SSND chunk not found");

        // Cap PCM data at MAX_BYTES (whole-sample boundary)
        int bytesPerSample = bitsPerSample / 8;
        int frameSize = channels * bytesPerSample;
        long maxPcm = MAX_BYTES - 44; // leave room for WAV header
        // align to frame boundary
        maxPcm = (maxPcm / frameSize) * frameSize;

        int available = src.length - ssndStart;
        int pcmLength = (int) Math.min(Math.min(ssndSize, available), Math.min(maxPcm, Integer.MAX_VALUE));
        if (pcmLength < 0) pcmLength = 0;

        byte[] wav = new byte[44 + pcmLength];

        // WAV header
        writeAscii(wav, 0, "RIFF");
        writeInt32LE(wav, 4, 36 + pcmLength);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeInt32LE(wav, 16, 16);
        writeInt16LE(wav, 20, 1); // PCM
        writeInt16LE(wav, 22, channels);
        writeInt32LE(wav, 24, sampleRate);
        int byteRate = sampleRate * channels * bytesPerSample;
        writeInt32LE(wav, 28, byteRate);
        writeInt16LE(wav, 32, channels * bytesPerSample);
        writeInt16LE(wav, 34, bitsPerSample);
        writeAscii(wav, 36, "data");
        writeInt32LE(wav, 40, pcmLength);

        // PCM data — convert endianness / signedness as needed
        if (littleEndian) {
            // sowt AIFC: already little-endian, copy as-is
            System.arraycopy(src, ssndStart, wav, 44, pcmLength);
        } else if (bitsPerSample == 8) {
            // AIFF 8-bit is signed; WAV 8-bit is unsigned
            for (int i = 0; i < pcmLength; i++) {
                wav[44 + i] = (byte) ((src[ssndStart + i] & 0xFF) ^ 0x80);
            }
        } else if (bitsPerSample == 16) {
            for (int i = 0; i < pcmLength; i += 2) {
                wav[44 + i]     = src[ssndStart + i + 1];
                wav[44 + i + 1] = src[ssndStart + i];
            }
        } else if (bitsPerSample == 24) {
            for (int i = 0; i < pcmLength; i += 3) {
                wav[44 + i]     = src[ssndStart + i + 2];
                wav[44 + i + 1] = src[ssndStart + i + 1];
                wav[44 + i + 2] = src[ssndStart + i];
            }
        } else if (bitsPerSample == 32) {
            for (int i = 0; i < pcmLength; i += 4) {
                wav[44 + i]     = src[ssndStart + i + 3];
                wav[44 + i + 1] = src[ssndStart + i + 2];
                wav[44 + i + 2] = src[ssndStart + i + 1];
                wav[44 + i + 3] = src[ssndStart + i];
            }
        } else {
            throw new IOException("AIFF: unsupported bit depth: " + bitsPerSample);
        }

        return wav;
    }

    // ---- helpers ----

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
        b[off]     = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void writeInt32LE(byte[] b, int off, int v) {
        b[off]     = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
