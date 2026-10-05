package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The streaming AIFF→WAV source must serve exactly the bytes the old whole-file conversion built,
 * whatever the read pattern MediaPlayer uses: reads that start or end mid-sample, straddle the
 * synthesized header, or run past the end. Files are built here byte by byte, so the expected WAV is
 * derived independently of the code under test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AiffMediaDataSourceTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final Context context = RuntimeEnvironment.getApplication();

  @Test
  public void sixteenBitStereoMatchesExpectedWavForEveryReadPattern() throws Exception {
    byte[] pcm = ramp(4 * 37);                 // 37 stereo 16-bit frames
    Uri uri = write(aiff(false, null, 2, 16, pcm, true));
    assertWav(uri, wav(2, 16, swap(pcm, 2)));
  }

  @Test
  public void twentyFourBitMono() throws Exception {
    byte[] pcm = ramp(3 * 41);
    Uri uri = write(aiff(false, null, 1, 24, pcm, false));
    assertWav(uri, wav(1, 24, swap(pcm, 3)));
  }

  @Test
  public void eightBitIsFlippedToUnsigned() throws Exception {
    byte[] pcm = ramp(50);
    byte[] expected = pcm.clone();
    for (int i = 0; i < expected.length; i++) expected[i] ^= (byte) 0x80;
    Uri uri = write(aiff(false, null, 1, 8, pcm, false));
    assertWav(uri, wav(1, 8, expected));
  }

  @Test
  public void aifcSowtIsAlreadyLittleEndian() throws Exception {
    byte[] pcm = ramp(4 * 20);
    Uri uri = write(aiff(true, "sowt", 2, 16, pcm, false));
    assertWav(uri, wav(2, 16, pcm));
  }

  @Test
  public void truncatedFileServesOnlyItsWholeFrames() throws Exception {
    byte[] pcm = ramp(4 * 30);
    byte[] file = aiff(false, null, 2, 16, pcm, false);
    // Drop the trailing ID3 chunk (24 bytes) and 10 frames plus 3 bytes: the file ends mid-frame.
    byte[] cut = java.util.Arrays.copyOf(file, file.length - 24 - 4 * 10 - 3);
    Uri uri = write(cut);
    byte[] kept = java.util.Arrays.copyOf(pcm, 4 * 19);
    assertWav(uri, wav(2, 16, swap(kept, 2)));
  }

  @Test
  public void rejectsUnsupportedCompression() throws Exception {
    Uri uri = write(aiff(true, "fl32", 2, 32, ramp(16), false));
    assertThrows(IOException.class, () -> new AiffMediaDataSource(context, uri));
  }

  @Test
  public void rejectsNonAiff() throws Exception {
    Uri uri = write("RIFF0000WAVEfmt ".getBytes());
    assertThrows(IOException.class, () -> new AiffMediaDataSource(context, uri));
  }

  // ---- assertions ----

  private void assertWav(Uri uri, byte[] expected) throws IOException {
    try (AiffMediaDataSource src = new AiffMediaDataSource(context, uri)) {
      assertEquals(expected.length, src.getSize());
      for (int chunk : new int[]{1, 2, 3, 5, 7, 44, 45, 64, 1000}) {
        assertArrayEquals("chunk " + chunk, expected, readAll(src, chunk));
      }
      // A read that straddles the header/PCM boundary at an odd offset, and one past the end.
      byte[] buf = new byte[10];
      int n = src.readAt(41, buf, 0, 10);
      byte[] want = java.util.Arrays.copyOfRange(expected, 41, Math.min(expected.length, 51));
      assertArrayEquals(want, java.util.Arrays.copyOf(buf, n));
      assertEquals(-1, src.readAt(expected.length, buf, 0, 10));
    }
  }

  private static byte[] readAll(AiffMediaDataSource src, int chunk) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[chunk + 3];
    long pos = 0;
    int n;
    while ((n = src.readAt(pos, buf, 3, chunk)) > 0) {
      out.write(buf, 3, n);
      pos += n;
    }
    return out.toByteArray();
  }

  // ---- fixtures ----

  private Uri write(byte[] bytes) throws IOException {
    File f = tmp.newFile("t.aiff");
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(bytes);
    }
    return Uri.fromFile(f);
  }

  private static byte[] ramp(int n) {
    byte[] b = new byte[n];
    for (int i = 0; i < n; i++) b[i] = (byte) (i * 7 + 1);
    return b;
  }

  private static byte[] swap(byte[] pcm, int bytesPerSample) {
    byte[] out = new byte[pcm.length];
    for (int i = 0; i < pcm.length; i += bytesPerSample)
      for (int j = 0; j < bytesPerSample; j++) out[i + j] = pcm[i + bytesPerSample - 1 - j];
    return out;
  }

  /** An AIFF/AIFC file, with an odd-sized chunk before COMM and an ID3 chunk after the sound data. */
  private static byte[] aiff(boolean aifc, String compression, int channels, int bits, byte[] pcm,
                             boolean ssndOffset) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write((aifc ? "AIFC" : "AIFF").getBytes());
    chunk(body, "ANNO", new byte[]{'h', 'i', '!'});      // odd size: exercises the pad byte
    ByteArrayOutputStream comm = new ByteArrayOutputStream();
    be16(comm, channels);
    be32(comm, pcm.length / (channels * bits / 8));
    be16(comm, bits);
    comm.write(new byte[]{0x40, 0x0E, (byte) 0xAC, 0x44, 0, 0, 0, 0, 0, 0}); // 44100 Hz
    if (aifc) comm.write(compression.getBytes());
    chunk(body, "COMM", comm.toByteArray());
    ByteArrayOutputStream ssnd = new ByteArrayOutputStream();
    int offset = ssndOffset ? 4 : 0;
    be32(ssnd, offset);
    be32(ssnd, 0);
    ssnd.write(new byte[offset]);
    ssnd.write(pcm);
    chunk(body, "SSND", ssnd.toByteArray());
    chunk(body, "ID3 ", new byte[16]);
    ByteArrayOutputStream file = new ByteArrayOutputStream();
    file.write("FORM".getBytes());
    be32(file, body.size());
    file.write(body.toByteArray());
    return file.toByteArray();
  }

  private static byte[] wav(int channels, int bits, byte[] pcm) {
    int frame = channels * bits / 8;
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + pcm.length)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVEfmt ".getBytes()).putInt(16)
        .putShort((short) 1).putShort((short) channels).putInt(44100).putInt(44100 * frame)
        .putShort((short) frame).putShort((short) bits).put("data".getBytes()).putInt(pcm.length)
        .put(pcm);
    return b.array();
  }

  private static void chunk(ByteArrayOutputStream out, String id, byte[] data) throws IOException {
    out.write(id.getBytes());
    be32(out, data.length);
    out.write(data);
    if ((data.length & 1) != 0) out.write(0);
  }

  private static void be16(ByteArrayOutputStream out, int v) {
    out.write(v >> 8);
    out.write(v);
  }

  private static void be32(ByteArrayOutputStream out, int v) {
    out.write(v >> 24);
    out.write(v >> 16);
    out.write(v >> 8);
    out.write(v);
  }
}
