package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Tags written into an untagged MP4 read back through this app's own tag reader, and the audio
 * is still where the sample tables say. The fixtures are half a second of AAC from ffmpeg, one
 * with moov before mdat (so every chunk offset moves) and one with it after.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class Mp4TagWriterTest {

    @Test
    public void tagsReadBackWithMoovBeforeTheData() throws Exception {
        roundTrip("moov-first.m4a");
    }

    @Test
    public void tagsReadBackWithMoovAfterTheData() throws Exception {
        roundTrip("moov-last.m4a");
    }

    private void roundTrip(String fixture) throws Exception {
        File file = copyOf(fixture);
        byte[] before = Files.readAllBytes(file.toPath());
        List<byte[]> chunksBefore = chunks(before);

        Mp4TagWriter.Tags tags = new Mp4TagWriter.Tags();
        tags.title = "Mañana";
        tags.artist = "Somebody";
        tags.album = "Album";
        tags.genre = "Techno";
        tags.date = "2021-04-02";
        tags.bpm = 128;
        tags.track = 3;
        tags.trackTotal = 12;
        tags.lyrics = "la la";
        tags.replayGainTrack = "-6.02 dB";
        tags.cover = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2};
        Mp4TagWriter.write(file, tags);

        MetadataExtractor reader = new MetadataExtractor(RuntimeEnvironment.getApplication().getContentResolver());
        Uri uri = Uri.fromFile(file);
        MetadataExtractor.TagEntry e = reader.readSortTags(uri);
        assertEquals("Mañana", e.title);
        assertEquals("Somebody", e.artist);
        assertEquals("Techno", e.genre);
        assertEquals("2021-04-02", e.date);
        assertEquals(128, e.bpm);
        assertEquals("la la", reader.readLyricsTag(uri));
        assertEquals(0.5f, reader.readReplayGain(uri), 0.001f);

        // Every chunk the sample tables point at is the same bytes it was before.
        byte[] after = Files.readAllBytes(file.toPath());
        List<byte[]> chunksAfter = chunks(after);
        assertEquals(chunksBefore.size(), chunksAfter.size());
        for (int i = 0; i < chunksBefore.size(); i++) assertArrayEquals(chunksBefore.get(i), chunksAfter.get(i));
    }

    /**
     * The first 64 bytes at each stco offset. Found by scanning for "stco": the fixtures are tiny,
     * and the audio in them happens not to contain the word.
     */
    private static List<byte[]> chunks(byte[] file) {
        List<byte[]> out = new ArrayList<>();
        byte[] stco = "stco".getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 4; i + 12 <= file.length; i++) {
            if (file[i] != stco[0] || file[i + 1] != stco[1] || file[i + 2] != stco[2] || file[i + 3] != stco[3]) continue;
            ByteBuffer b = ByteBuffer.wrap(file);
            int count = b.getInt(i + 8);
            for (int c = 0; c < count; c++) {
                int offset = b.getInt(i + 12 + c * 4);
                byte[] chunk = new byte[Math.min(64, file.length - offset)];
                System.arraycopy(file, offset, chunk, 0, chunk.length);
                out.add(chunk);
            }
        }
        if (out.isEmpty()) throw new AssertionError("no stco");
        return out;
    }

    private File copyOf(String fixture) throws Exception {
        File file = File.createTempFile("tag", ".m4a");
        try (InputStream in = getClass().getResourceAsStream("/mp4/" + fixture)) {
            Files.copy(in, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }
}
