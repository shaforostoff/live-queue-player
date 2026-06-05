package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.beatofthedrum.alacdecoder.AlacContext;
import com.beatofthedrum.alacdecoder.AlacUtils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Plays ALAC (Apple Lossless) {@code .m4a} files on devices whose platform has no ALAC decoder
 * (some Android builds ship one and {@link android.media.MediaPlayer} plays ALAC natively; others,
 * e.g. the Xperia 10 V, ship none). The bundled pure-Java decoder in
 * {@code com.beatofthedrum.alacdecoder} (BSD-licensed) decodes the file to PCM, which we present as
 * an in-memory WAV byte array through {@link MediaDataSource} — the same trick
 * {@link AiffMediaDataSource} uses, so the rest of the pipeline (ReplayGain, fade, equalizer, seek,
 * completion) is unaffected.
 *
 * <p>ALAC and AAC share the {@code .m4a} extension, so detection is by codec MIME, not extension.
 * Decoding is lazy — it runs on the first {@link #getSize()}/{@link #readAt} call, which MediaPlayer
 * issues during {@code prepare()} on AudioPlayer's background thread, keeping it off the main thread.
 */
final class AlacMediaDataSource extends MediaDataSource {

  private static final String MIME_ALAC = "audio/alac";

  /** Device-wide and immutable; cached so we don't rescan the codec list per track. */
  private static volatile Boolean sPlatformHasAlac;

  private final Context context;
  private final Uri uri;
  private byte[] wavData;       // built lazily on first access
  private boolean decoded;

  AlacMediaDataSource(Context context, Uri uri) {
    this.context = context;
    this.uri = uri;
  }

  /**
   * True only when the bundled decoder is needed: an MP4-family file whose codec is ALAC, on a
   * device with no system ALAC decoder. When the platform has one, returns false so MediaPlayer
   * decodes natively.
   */
  static boolean shouldUseFor(Context context, Uri uri) {
    String seg = uri.getLastPathSegment();
    if (seg == null) seg = uri.getPath();
    if (seg == null) return false;
    String lower = seg.toLowerCase(Locale.ROOT);
    if (!(lower.endsWith(".m4a") || lower.endsWith(".mp4"))) return false;

    if (platformHasAlacDecoder()) return false; // MediaPlayer can handle it natively
    return isAlacTrack(context, uri);
  }

  private static boolean platformHasAlacDecoder() {
    Boolean cached = sPlatformHasAlac;
    if (cached != null) return cached;
    boolean found = false;
    try {
      MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
      outer:
      for (MediaCodecInfo info : list.getCodecInfos()) {
        if (info.isEncoder()) continue;
        for (String type : info.getSupportedTypes()) {
          if (MIME_ALAC.equalsIgnoreCase(type)) { found = true; break outer; }
        }
      }
    } catch (Exception ignored) {
    }
    sPlatformHasAlac = found;
    return found;
  }

  private static boolean isAlacTrack(Context context, Uri uri) {
    MediaExtractor extractor = new MediaExtractor();
    try {
      extractor.setDataSource(context, uri, null);
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
        if (MIME_ALAC.equalsIgnoreCase(mime)) return true;
      }
    } catch (Exception ignored) {
      // fall through to the structural probe below
    } finally {
      extractor.release();
    }
    // Some extractors (notably MediaTek's) report ALAC tracks as audio/unknown, so the MIME probe
    // above misses them and the file would wrongly be handed to the native player, which then fails
    // with MEDIA_ERROR_UNSUPPORTED. Detect ALAC structurally instead, via the 'alac' sample entry.
    return containsAlacBox(context, uri);
  }

  /**
   * Structural ALAC detection: walks the MP4 box tree for an {@code alac} sample-entry box. Needed
   * because some extractors (notably MediaTek's) report ALAC tracks as {@code audio/unknown}, so the
   * MIME probe in {@link #isAlacTrack} misses them. Reads only the small {@code moov} container and
   * never the large {@code mdat} payload. Returns false on any malformed or unreadable input.
   */
  private static boolean containsAlacBox(Context context, Uri uri) {
    try (InputStream in = UriIo.open(context, uri)) {
      return scanBoxesForAlac(new DataInputStream(new BufferedInputStream(in)), Long.MAX_VALUE);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Reads ISO-BMFF boxes consuming up to {@code limit} bytes, recursing through the
   * moov/trak/mdia/minf/stbl/stsd container path. Returns true as soon as an {@code alac} box is seen.
   */
  private static boolean scanBoxesForAlac(DataInputStream dis, long limit) throws IOException {
    long consumed = 0;
    while (consumed + 8 <= limit) {
      long size;
      try {
        size = readU32(dis);
      } catch (EOFException eof) {
        return false;
      }
      byte[] fourcc = new byte[4];
      dis.readFully(fourcc);
      consumed += 8;
      String type = new String(fourcc, StandardCharsets.US_ASCII);

      long payload;
      if (size == 1) {                 // 64-bit largesize follows the type
        payload = readU64(dis) - 16;
        consumed += 8;
      } else if (size == 0) {          // box extends to the end of its parent
        payload = limit - consumed;
      } else {
        payload = size - 8;
      }
      if (payload < 0) return false;

      if ("alac".equals(type)) return true;
      if ("moov".equals(type)) return scanBoxesForAlac(dis, payload); // codec boxes live only here
      if (isAlacContainer(type)) {
        if (scanBoxesForAlac(dis, payload)) return true;
      } else if ("stsd".equals(type)) {
        skipFully(dis, 8);             // FullBox header: version/flags (4) + entry_count (4)
        if (scanBoxesForAlac(dis, payload - 8)) return true;
      } else {
        skipFully(dis, payload);       // ftyp, mdat, free, leaf boxes — skip
      }
      consumed += payload;
    }
    return false;
  }

  private static boolean isAlacContainer(String type) {
    return "trak".equals(type) || "mdia".equals(type) || "minf".equals(type) || "stbl".equals(type);
  }

  private static long readU32(DataInputStream dis) throws IOException {
    return ((long) dis.readUnsignedByte() << 24) | (dis.readUnsignedByte() << 16)
         | (dis.readUnsignedByte() << 8) | dis.readUnsignedByte();
  }

  private static long readU64(DataInputStream dis) throws IOException {
    return (readU32(dis) << 32) | readU32(dis);
  }

  private static void skipFully(DataInputStream dis, long n) throws IOException {
    while (n > 0) {
      long skipped = dis.skip(n);
      if (skipped <= 0) {
        if (dis.read() < 0) throw new EOFException();
        n -= 1;
      } else {
        n -= skipped;
      }
    }
  }

  private synchronized void ensureDecoded() {
    if (decoded) return;
    decoded = true;
    try {
      wavData = decodeToWav(context, uri);
    } catch (Exception | OutOfMemoryError e) {
      // Decode failed — leave an empty stream so MediaPlayer's prepare() fails and the existing
      // retry/skip path in Service handles it.
      wavData = new byte[0];
    }
  }

  private static byte[] decodeToWav(Context context, Uri uri) throws IOException {
    // The decoder needs a seekable FileInputStream, so stage the (compressed) file in the cache.
    File temp = File.createTempFile("alac", ".m4a", context.getCacheDir());
    try {
      copyToFile(context, uri, temp);

      AlacContext ac = AlacUtils.AlacOpenFileInput(temp.getAbsolutePath());
      try {
        if (ac.error) throw new IOException(ac.error_message);
        int channels      = AlacUtils.AlacGetNumChannels(ac);
        int sampleRate     = AlacUtils.AlacGetSampleRate(ac);
        int bytesPerSample = AlacUtils.AlacGetBytesPerSample(ac);
        int bitsPerSample  = AlacUtils.AlacGetBitsPerSample(ac);

        int[] dest = new int[1024 * 24 * 3]; // one ALAC frame, max 24bps (matches upstream demo)
        long pcmCap = AiffConverter.MAX_BYTES - 44; // leave room for the WAV header

        // The decoded size is known up front from the stream's frame count, so allocate the final
        // WAV buffer once and decode straight into it — no growing buffer and no extra copies, which
        // is what kept peak heap at ~3x the PCM size and OOM-ed low-RAM devices mid-track.
        int totalFrames = AlacUtils.AlacGetNumSamples(ac);
        if (totalFrames > 0) {
          int capacity = (int) Math.min((long) totalFrames * channels * bytesPerSample, pcmCap);
          byte[] wav = new byte[44 + capacity];
          int pos = 44;
          while (pos - 44 < capacity) {
            int bytes = AlacUtils.AlacUnpackSamples(ac, dest);
            if (bytes <= 0) break; // end of stream
            int room = capacity - (pos - 44);
            pos = writePcm(wav, pos, bytesPerSample, dest, Math.min(bytes, room));
          }
          int pcmLen = pos - 44;
          // The frame count is authoritative, so pcmLen normally equals capacity; trim only if the
          // stream ended short of it.
          if (pcmLen != capacity) wav = java.util.Arrays.copyOf(wav, pos);
          writeWavHeader(wav, sampleRate, channels, bitsPerSample, pcmLen);
          return wav;
        }

        // Frame count unavailable (corrupt sample index): fall back to a growing buffer.
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        while (pcm.size() < pcmCap) {
          int bytes = AlacUtils.AlacUnpackSamples(ac, dest);
          if (bytes <= 0) break; // end of stream
          appendLittleEndian(pcm, bytesPerSample, dest, bytes);
        }
        return wrapPcmAsWav(pcm.toByteArray(), sampleRate, channels, bitsPerSample);
      } finally {
        AlacUtils.AlacCloseFile(ac);
      }
    } finally {
      //noinspection ResultOfMethodCallIgnored
      temp.delete();
    }
  }

  private static void copyToFile(Context context, Uri uri, File dest) throws IOException {
    try (InputStream in = UriIo.open(context, uri);
         OutputStream out = new FileOutputStream(dest)) {
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }
  }

  /**
   * Converts the decoder's per-sample ints into little-endian PCM bytes, mirroring the upstream
   * demo's {@code format_samples}: {@code count} is a byte count; 16-bit packs two bytes per int,
   * 8-/24-bit one byte per int.
   */
  private static void appendLittleEndian(ByteArrayOutputStream out, int bytesPerSample, int[] src, int count) {
    switch (bytesPerSample) {
      case 2: { // 16-bit
        int s = 0;
        for (int i = 0; i < count; i += 2, s++) {
          int v = src[s];
          out.write(v & 0xFF);
          out.write((v >>> 8) & 0xFF);
        }
        break;
      }
      case 1: // 8-bit (decoder emits signed; WAV 8-bit is unsigned)
        for (int i = 0; i < count; i++) out.write((src[i] + 128) & 0xFF);
        break;
      default: // 24-bit (and any other): one byte per int
        for (int i = 0; i < count; i++) out.write(src[i] & 0xFF);
        break;
    }
  }

  /**
   * Same encoding as {@link #appendLittleEndian}, but writes straight into {@code out} starting at
   * {@code pos} and returns the new position — used by the pre-sized decode path that knows the
   * final buffer size up front and so needs no intermediate stream.
   */
  private static int writePcm(byte[] out, int pos, int bytesPerSample, int[] src, int count) {
    switch (bytesPerSample) {
      case 2: { // 16-bit
        for (int i = 0, s = 0; i < count; i += 2, s++) {
          int v = src[s];
          out[pos++] = (byte) (v & 0xFF);
          out[pos++] = (byte) ((v >>> 8) & 0xFF);
        }
        break;
      }
      case 1: // 8-bit (decoder emits signed; WAV 8-bit is unsigned)
        for (int i = 0; i < count; i++) out[pos++] = (byte) ((src[i] + 128) & 0xFF);
        break;
      default: // 24-bit (and any other): one byte per int
        for (int i = 0; i < count; i++) out[pos++] = (byte) (src[i] & 0xFF);
        break;
    }
    return pos;
  }

  /** Prepends a 44-byte little-endian PCM WAV header to {@code pcm}. */
  private static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
    byte[] wav = new byte[44 + pcm.length];
    writeWavHeader(wav, sampleRate, channels, bitsPerSample, pcm.length);
    System.arraycopy(pcm, 0, wav, 44, pcm.length);
    return wav;
  }

  /** Writes the 44-byte little-endian PCM WAV header into the first 44 bytes of {@code wav}. */
  private static void writeWavHeader(byte[] wav, int sampleRate, int channels, int bitsPerSample, int pcmLen) {
    int bytesPerSample = bitsPerSample / 8;
    writeAscii(wav, 0, "RIFF");
    writeInt32LE(wav, 4, 36 + pcmLen);
    writeAscii(wav, 8, "WAVE");
    writeAscii(wav, 12, "fmt ");
    writeInt32LE(wav, 16, 16);
    writeInt16LE(wav, 20, 1); // PCM
    writeInt16LE(wav, 22, channels);
    writeInt32LE(wav, 24, sampleRate);
    writeInt32LE(wav, 28, sampleRate * channels * bytesPerSample); // byte rate
    writeInt16LE(wav, 32, channels * bytesPerSample);              // block align
    writeInt16LE(wav, 34, bitsPerSample);
    writeAscii(wav, 36, "data");
    writeInt32LE(wav, 40, pcmLen);
  }

  @Override
  public int readAt(long position, byte[] buffer, int offset, int size) {
    ensureDecoded();
    if (position >= wavData.length) return -1;
    int available = (int) Math.min(size, wavData.length - position);
    System.arraycopy(wavData, (int) position, buffer, offset, available);
    return available;
  }

  @Override
  public long getSize() {
    ensureDecoded();
    return wavData.length;
  }

  @Override
  public void close() {
    // byte array; no native resources to release
  }

  // ---- WAV header helpers ----

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
