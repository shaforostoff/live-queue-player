package com.shaforostoff.livequeueplayer;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.beatofthedrum.alacdecoder.AlacContext;
import com.beatofthedrum.alacdecoder.AlacUtils;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Plays ALAC (Apple Lossless) {@code .m4a} files on devices whose platform has no ALAC decoder
 * (some Android builds ship one and {@link android.media.MediaPlayer} plays ALAC natively; others,
 * e.g. the Xperia 10 V, ship none). The bundled pure-Java decoder in
 * {@code com.beatofthedrum.alacdecoder} (BSD-licensed) decodes the file to PCM, which we present as
 * a WAV stream through {@link MediaDataSource} — like {@link AiffMediaDataSource} presents AIFF as
 * WAV — so the rest of the pipeline (ReplayGain, fade, equalizer, seek, completion) is unaffected.
 *
 * <p>The WAV is never built: each {@link #readAt} decodes just the ALAC packets (4096 samples each,
 * typically) that cover the requested bytes, and keeps only the last one. It used to decode the
 * whole track into one array up front — ~10 MB per minute of CD audio, ~35 MB per minute of
 * 24-bit/96 kHz, held for as long as the track was loaded — under a 100 MB cap that cut hi-res
 * tracks off after about three minutes, and prepare() waited for the whole decode.
 *
 * <p>ALAC and AAC share the {@code .m4a} extension, so detection is by codec MIME, not extension.
 * The file is opened lazily, on the first {@link #getSize()}/{@link #readAt} call, which MediaPlayer
 * issues during {@code prepare()} on AudioPlayer's background thread, keeping it off the main thread.
 */
final class AlacMediaDataSource extends MediaDataSource {

  private static final String MIME_ALAC = "audio/alac";
  private static final int WAV_HEADER_BYTES = 44;
  /** Name prefix of the staged copies in the cache dir; see {@link #deleteStaleStagedFiles}. */
  private static final String STAGED_PREFIX = "alac";

  /** Device-wide and immutable; cached so we don't rescan the codec list per track. */
  private static volatile Boolean sPlatformHasAlac;

  private final Context context;
  private final Uri uri;
  // All state below is guarded by this; MediaPlayer and MediaExtractor read from their own threads.
  private boolean opened;
  /** A copy of the compressed file in the cache dir, made only when the provider's descriptor
   *  cannot seek (see {@link #openInPlace}); null when the file is read where it is. */
  private File staged;
  private AlacContext ac;
  private int bytesPerSample;
  /** PCM byte offset (after the header) at which each packet starts, plus the total at the end;
   *  null when the file could not be opened, which reads as an empty stream. */
  private long[] packetPcmStart;
  private final byte[] header = new byte[WAV_HEADER_BYTES];
  private int[] decodeBuffer;
  private byte[] packetPcm;      // the decoded PCM of packetInBuffer
  private int packetInBuffer = -1;
  /** The packet the decoder reads next without a seek: sequential playback never seeks. */
  private int nextPacket;

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
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      if (in == null) return false;
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

  private synchronized void ensureOpen() {
    if (opened) return;
    opened = true;
    try {
      open();
    } catch (Exception | OutOfMemoryError e) {
      // Leave an empty stream so MediaPlayer's prepare() fails and the existing retry/skip path in
      // Service handles it.
      releaseDecoder();
      packetPcmStart = null;
    }
  }

  private void open() throws IOException {
    ac = openInPlace();
    if (ac == null) {
      // Not a seekable file (a provider that streams through a pipe): decode from a copy instead.
      staged = File.createTempFile(STAGED_PREFIX, ".m4a", context.getCacheDir());
      copyToFile(context, uri, staged);
      ac = AlacUtils.AlacOpenFileInput(staged.getAbsolutePath());
    }
    if (ac.error) throw new IOException(ac.error_message);
    int channels      = AlacUtils.AlacGetNumChannels(ac);
    int sampleRate    = AlacUtils.AlacGetSampleRate(ac);
    int bitsPerSample = AlacUtils.AlacGetBitsPerSample(ac);
    bytesPerSample    = AlacUtils.AlacGetBytesPerSample(ac);

    // The packet table gives every packet's length up front, so each one's place in the WAV is
    // known without decoding anything.
    int packets = AlacUtils.AlacGetNumPackets(ac);
    if (packets == 0) throw new IOException("no ALAC packets");
    long bytesPerFrame = (long) channels * bytesPerSample;
    long[] starts = new long[packets + 1];
    long largest = 0;
    for (int i = 0; i < packets; i++) {
      long bytes = AlacUtils.AlacGetPacketSamples(ac, i) * bytesPerFrame;
      if (bytes < 0) throw new IOException("bad packet length");
      starts[i + 1] = starts[i] + bytes;
      largest = Math.max(largest, bytes);
    }
    long pcmLen = starts[packets];
    // The header's sizes are 32-bit; that is still over three hours of 24-bit/96 kHz stereo.
    if (pcmLen > Integer.MAX_VALUE - 36) throw new IOException("too long for a WAV header");
    writeWavHeader(header, sampleRate, channels, bitsPerSample, (int) pcmLen);
    decodeBuffer = new int[1024 * 24 * 3]; // one ALAC frame, max 24bps (matches upstream demo)
    packetPcm = new byte[(int) largest];
    packetPcmStart = starts;
  }

  /**
   * Decode straight from the provider's file descriptor. Local documents (internal storage, the SD
   * card) come back as a real file, which seeks like any other, so the track needs no copy: the
   * copy of a 20-40 MB track used to be the bulk of prepare(), and its space in the cache dir. Null
   * when the descriptor is a pipe or the decoder cannot use it, so the caller stages a copy.
   */
  private AlacContext openInPlace() {
    ParcelFileDescriptor pfd;
    try {
      pfd = context.getContentResolver().openFileDescriptor(uri, "r");
    } catch (Exception e) {
      return null;
    }
    if (pfd == null) return null;
    if (pfd.getStatSize() < 0) { // not a regular file: a pipe or a socket, which cannot seek
      try {
        pfd.close();
      } catch (IOException ignored) {
      }
      return null;
    }
    // Closed with the decoder (AlacCloseFile), which closes the descriptor with it.
    AlacContext opened = AlacUtils.AlacOpenFileInput(new ParcelFileDescriptor.AutoCloseInputStream(pfd));
    if (opened.error) {
      AlacUtils.AlacCloseFile(opened);
      return null;
    }
    return opened;
  }

  /** Index of the packet holding PCM byte {@code pcmPos}; skips packets that decode to nothing. */
  private int packetAt(long pcmPos) {
    long[] starts = packetPcmStart;
    int lo = 0, hi = starts.length - 2; // last packet index
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (starts[mid] <= pcmPos) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  /** Decode {@code packet} into {@link #packetPcm}, at exactly the length the table promises. */
  private boolean loadPacket(int packet) {
    if (packet == packetInBuffer) return true;
    packetInBuffer = -1;
    try {
      if (packet != nextPacket && !AlacUtils.AlacSeekToPacket(ac, packet)) return false;
      int bytes = AlacUtils.AlacUnpackSamples(ac, decodeBuffer);
      nextPacket = packet + 1;
      if (bytes <= 0) return false;
      int expected = (int) (packetPcmStart[packet + 1] - packetPcmStart[packet]);
      // A packet that decodes to more or less than its table entry is clipped or padded, so every
      // later byte stays where the header and the table put it.
      int got = Math.min(bytes, expected);
      writePcm(packetPcm, 0, bytesPerSample, decodeBuffer, got);
      if (got < expected) Arrays.fill(packetPcm, got, expected, (byte) 0);
    } catch (RuntimeException e) {
      // A corrupt packet can throw from deep in the decoder; on MediaPlayer's thread that would
      // kill the process.
      nextPacket = -1; // force a seek next time
      return false;
    }
    packetInBuffer = packet;
    return true;
  }

  private void releaseDecoder() {
    if (ac != null) {
      AlacUtils.AlacCloseFile(ac);
      ac = null;
    }
    if (staged != null) {
      //noinspection ResultOfMethodCallIgnored
      staged.delete();
      staged = null;
    }
    packetPcm = null;
    decodeBuffer = null;
    packetInBuffer = -1;
  }

  /**
   * Delete staged copies left by a process that died with a track loaded (close() never ran).
   * Call only at process start, when no data source can be open.
   */
  static void deleteStaleStagedFiles(Context context) {
    File[] stale = context.getCacheDir().listFiles(
        (dir, name) -> name.startsWith(STAGED_PREFIX) && name.endsWith(".m4a"));
    if (stale == null) return;
    for (File f : stale) {
      //noinspection ResultOfMethodCallIgnored
      f.delete();
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
   * demo's {@code format_samples}, writing into {@code out} from {@code pos} and returning the new
   * position. {@code count} is a byte count; 16-bit packs two bytes per int, 8-/24-bit one byte per
   * int.
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
  public synchronized int readAt(long position, byte[] buffer, int offset, int size) {
    ensureOpen();
    long[] starts = packetPcmStart;
    if (starts == null || ac == null) return -1;
    long total = WAV_HEADER_BYTES + starts[starts.length - 1];
    if (position >= total) return -1;
    int done = 0;
    while (done < size && position < total) {
      int n;
      if (position < WAV_HEADER_BYTES) {
        n = (int) Math.min(size - done, WAV_HEADER_BYTES - position);
        System.arraycopy(header, (int) position, buffer, offset + done, n);
      } else {
        long pcmPos = position - WAV_HEADER_BYTES;
        int packet = packetAt(pcmPos);
        if (!loadPacket(packet)) break;
        int within = (int) (pcmPos - starts[packet]);
        n = (int) Math.min(size - done, starts[packet + 1] - starts[packet] - within);
        System.arraycopy(packetPcm, within, buffer, offset + done, n);
      }
      done += n;
      position += n;
    }
    // A packet that will not decode ends the stream there, as an undecodable tail did before.
    return done > 0 ? done : -1;
  }

  @Override
  public synchronized long getSize() {
    ensureOpen();
    long[] starts = packetPcmStart;
    return starts == null ? 0 : WAV_HEADER_BYTES + starts[starts.length - 1];
  }

  @Override
  public synchronized void close() {
    opened = true; // a read after close() finds nothing rather than reopening
    packetPcmStart = null;
    releaseDecoder();
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
