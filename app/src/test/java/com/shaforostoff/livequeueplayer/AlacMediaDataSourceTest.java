package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/**
 * {@link AlacMediaDataSource} decodes packets on demand. ALAC is lossless, so the oracle is exact:
 * the PCM ffmpeg decodes from the same fixture ({@code ffmpeg -i f.m4a -f s16le - | md5sum}, s24le
 * for the 24-bit one). The fixtures were made with ffmpeg's ALAC encoder; see the commit message.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 33)
public class AlacMediaDataSourceTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void sequentialRead_matchesFfmpeg_indexAtEnd() throws Exception {
        assertDecodes("stereo16_moov_last.m4a", 441_000, "f27b74f4bc293cabbf8a02d184f7f920", 2, 16, 44_100);
    }

    @Test
    public void sequentialRead_matchesFfmpeg_indexFirst() throws Exception {
        assertDecodes("stereo16_faststart.m4a", 441_000, "f27b74f4bc293cabbf8a02d184f7f920", 2, 16, 44_100);
    }

    @Test
    public void sequentialRead_matchesFfmpeg_24bitHiRes() throws Exception {
        assertDecodes("mono24.m4a", 432_000, "8006f25347f5c0c536120649b7b3eed0", 1, 24, 96_000);
    }

    @Test
    public void randomReads_matchTheSequentialStream() throws Exception {
        for (String name : new String[] {"stereo16_moov_last.m4a", "mono24.m4a"}) {
            byte[] whole = readAll(open(name));
            AlacMediaDataSource src = open(name);
            Random random = new Random(7);
            for (int i = 0; i < 300; i++) {
                int pos = random.nextInt(whole.length);
                int size = 1 + random.nextInt(70_000);   // spans several packets at times
                byte[] got = new byte[size];
                int n = src.readAt(pos, got, 0, size);
                int expected = Math.min(size, whole.length - pos);
                assertEquals(name + " read at " + pos, expected, n);
                assertArrayEquals(name + " bytes at " + pos,
                        Arrays.copyOfRange(whole, pos, pos + n), Arrays.copyOf(got, n));
            }
            src.close();
        }
    }

    @Test
    public void readPastTheEnd_isEndOfStream() throws Exception {
        AlacMediaDataSource src = open("stereo16_faststart.m4a");
        long size = src.getSize();
        assertEquals(-1, src.readAt(size, new byte[16], 0, 16));
        assertEquals(4, src.readAt(size - 4, new byte[16], 0, 16));
        src.close();
    }

    @Test
    public void seekableFile_isReadInPlace() throws Exception {
        AlacMediaDataSource src = open("stereo16_moov_last.m4a");
        src.getSize();
        assertEquals("no copy for a file that seeks", 0, stagedFiles());
        src.close();
        assertEquals("closed reads as empty", -1, src.readAt(0, new byte[4], 0, 4));
    }

    /** A provider whose descriptor cannot be read in place: decode from a staged copy instead. */
    @Test
    public void unseekableProvider_decodesFromACopy_andCloseDeletesIt() throws Exception {
        SliceProvider.file = copyFixture("stereo16_moov_last.m4a");
        Robolectric.setupContentProvider(SliceProvider.class, SliceProvider.AUTHORITY);
        AlacMediaDataSource src = new AlacMediaDataSource(context,
                Uri.parse("content://" + SliceProvider.AUTHORITY + "/track.m4a"));
        byte[] wav = readAll(src);
        assertEquals(1, stagedFiles());
        assertEquals("f27b74f4bc293cabbf8a02d184f7f920", md5(Arrays.copyOfRange(wav, 44, wav.length)));
        src.close();
        assertEquals(0, stagedFiles());
    }

    @Test
    public void staleStagedFiles_areDeletedAtStartup() throws Exception {
        assertTrue(new File(context.getCacheDir(), "alac123.m4a").createNewFile());
        AlacMediaDataSource.deleteStaleStagedFiles(context);
        assertEquals(0, stagedFiles());
    }

    @Test
    public void notAlac_readsAsEmpty() throws Exception {
        File junk = new File(context.getCacheDir(), "junk.m4a");
        try (OutputStream out = new FileOutputStream(junk)) {
            out.write("definitely not an mp4 file".getBytes(StandardCharsets.US_ASCII));
        }
        AlacMediaDataSource src = new AlacMediaDataSource(context, Uri.fromFile(junk));
        assertEquals(0, src.getSize());
        assertEquals(-1, src.readAt(0, new byte[4], 0, 4));
        assertEquals("the staged copy is not left behind", 0, stagedFiles());
    }

    // ---------------------------------------------------------------------------------------------

    private void assertDecodes(String name, int pcmBytes, String md5, int channels, int bits, int rate)
            throws Exception {
        AlacMediaDataSource src = open(name);
        byte[] wav = readAll(src);
        src.close();
        assertEquals(44 + pcmBytes, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(channels, le16(wav, 22));
        assertEquals(rate, le32(wav, 24));
        assertEquals(bits, le16(wav, 34));
        assertEquals(pcmBytes, le32(wav, 40));
        assertEquals(md5, md5(Arrays.copyOfRange(wav, 44, wav.length)));
    }

    private AlacMediaDataSource open(String resource) throws IOException {
        return new AlacMediaDataSource(context, Uri.fromFile(copyFixture(resource)));
    }

    private File copyFixture(String resource) throws IOException {
        File file = new File(context.getFilesDir(), resource);
        try (InputStream in = getClass().getResourceAsStream("/alac/" + resource);
             OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return file;
    }

    /** Reads the whole stream front to back in MediaPlayer-sized chunks. */
    private static byte[] readAll(AlacMediaDataSource src) {
        byte[] out = new byte[(int) src.getSize()];
        int pos = 0;
        while (pos < out.length) {
            int n = src.readAt(pos, out, pos, Math.min(16_384, out.length - pos));
            if (n <= 0) break;
            pos += n;
        }
        assertEquals("the whole stream reads", out.length, pos);
        return out;
    }

    private int stagedFiles() {
        File[] files = context.getCacheDir().listFiles((d, n) -> n.startsWith("alac") && n.endsWith(".m4a"));
        return files == null ? 0 : files.length;
    }

    /**
     * Serves {@link #file} as a slice (an asset-style descriptor with a declared length), which
     * {@code openFileDescriptor} refuses as "Not a whole file" while {@code openInputStream} still
     * streams it. Robolectric backs createPipe() with a seekable file, so a real pipe is not
     * available here; either way the data source cannot read in place and must stage a copy.
     */
    public static class SliceProvider extends ContentProvider {
        static final String AUTHORITY = "alac.test.slice";
        static File file;

        @Override
        public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            return new AssetFileDescriptor(pfd, 0, file.length());
        }

        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri uri) { return "audio/mp4"; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] b, int off) {
        return le16(b, off) | le16(b, off + 2) << 16;
    }

    private static String md5(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
