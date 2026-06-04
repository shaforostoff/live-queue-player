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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    } catch (Exception e) {
      return false;
    } finally {
      extractor.release();
    }
    return false;
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

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int[] dest = new int[1024 * 24 * 3]; // one ALAC frame, max 24bps (matches upstream demo)
        long cap = AiffConverter.MAX_BYTES - 44; // leave room for the WAV header
        while (pcm.size() < cap) {
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
    try (InputStream in = context.getContentResolver().openInputStream(uri);
         OutputStream out = new FileOutputStream(dest)) {
      if (in == null) throw new IOException("Cannot open: " + uri);
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

  /** Prepends a 44-byte little-endian PCM WAV header to {@code pcm}. */
  private static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
    int bytesPerSample = bitsPerSample / 8;
    byte[] wav = new byte[44 + pcm.length];
    writeAscii(wav, 0, "RIFF");
    writeInt32LE(wav, 4, 36 + pcm.length);
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
    writeInt32LE(wav, 40, pcm.length);
    System.arraycopy(pcm, 0, wav, 44, pcm.length);
    return wav;
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
