package com.shaforostoff.livequeueplayer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Writes iTunes-style tags ({@code moov/udta/meta/ilst}) into an MP4 file that has none, such as
 * one {@link android.media.MediaMuxer} just wrote: it can't write tags itself. Pure Java, so it
 * runs on the JVM in a test.
 *
 * <p>The file is rewritten with a bigger {@code moov}. When that sits before {@code mdat} (the
 * muxer reserves room for it up front), every chunk offset in {@code stco}/{@code co64} moves by
 * the growth, and is patched to match.
 */
final class Mp4TagWriter {

    private Mp4TagWriter() {}

    /** The tags to write; null or empty values are left out. */
    static final class Tags {
        String title, artist, album, albumArtist, composer, genre, date, lyrics, comment;
        int track, trackTotal, disc, discTotal, bpm;
        String replayGainTrack, replayGainAlbum; // as written in the source, e.g. "-6.50 dB"
        byte[] cover;

        boolean isEmpty() {
            return items().length == 0;
        }

        private byte[] items() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            text(out, "©nam", title);
            text(out, "©ART", artist);
            text(out, "©alb", album);
            text(out, "aART", albumArtist);
            text(out, "©wrt", composer);
            text(out, "©gen", genre);
            text(out, "©day", date);
            text(out, "©cmt", comment);
            text(out, "©lyr", lyrics);
            if (track > 0) pair(out, "trkn", track, trackTotal, true);
            if (disc > 0) pair(out, "disk", disc, discTotal, false);
            if (bpm > 0 && bpm <= 0xFFFF) item(out, "tmpo", 21, new byte[]{(byte) (bpm >> 8), (byte) bpm});
            if (cover != null && cover.length > 0) item(out, "covr", isPng(cover) ? 14 : 13, cover);
            freeform(out, "REPLAYGAIN_TRACK_GAIN", replayGainTrack);
            freeform(out, "REPLAYGAIN_ALBUM_GAIN", replayGainAlbum);
            return out.toByteArray();
        }
    }

    /**
     * Rewrites {@code file} with {@code tags} in it. Throws, leaving the file as it was, if it isn't
     * an MP4 this understands.
     */
    static void write(File file, Tags tags) throws IOException {
        byte[] items = tags.items();
        if (items.length == 0) return;

        // Top-level boxes: where moov is, and whether any media data follows it.
        long length = file.length();
        long moovAt = -1, moovSize = 0;
        boolean dataAfterMoov = false;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long at = 0;
            byte[] head = new byte[16];
            while (at + 8 <= length) {
                raf.seek(at);
                raf.readFully(head, 0, 8);
                long size = u32(head, 0);
                int headerSize = 8;
                if (size == 1) {
                    raf.readFully(head, 8, 8);
                    size = u64(head, 8);
                    headerSize = 16;
                } else if (size == 0) {
                    size = length - at;
                }
                if (size < headerSize || at + size > length) throw new IOException("bad box at " + at);
                String type = type(head, 4);
                if (type.equals("moov")) {
                    if (moovAt >= 0) throw new IOException("two moov boxes");
                    if (headerSize != 8 || size > Integer.MAX_VALUE) throw new IOException("huge moov");
                    moovAt = at;
                    moovSize = size;
                } else if (type.equals("mdat") && moovAt >= 0) {
                    dataAfterMoov = true;
                }
                at += size;
            }
        }
        if (moovAt < 0) throw new IOException("no moov");

        byte[] moov = new byte[(int) moovSize];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(moovAt);
            raf.readFully(moov);
        }
        byte[] tagged = withIlst(moov, items);
        long growth = tagged.length - moov.length;
        if (dataAfterMoov) shiftChunkOffsets(tagged, 8, tagged.length, growth);

        File tmp = new File(file.getPath() + ".tag");
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(tmp)) {
            copy(in, out, moovAt);
            out.write(tagged);
            skip(in, moovSize);
            copy(in, out, length - moovAt - moovSize);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename failed");
        }
    }

    /** {@code moov} with the ilst added: into its udta if it has one, else in a new udta. */
    private static byte[] withIlst(byte[] moov, byte[] items) throws IOException {
        byte[] meta = metaBox(items);
        ByteArrayOutputStream out = new ByteArrayOutputStream(moov.length + meta.length + 8);
        out.write(moov, 0, 8);
        boolean merged = false;
        for (int at = 8; at < moov.length; ) {
            int size = (int) Math.min(u32(moov, at), Integer.MAX_VALUE);
            if (size < 8 || (long) at + size > moov.length) throw new IOException("bad box in moov");
            String type = type(moov, at + 4);
            if (type.equals("udta") && !merged) {
                // Keep what it holds but any meta (none from a muxer), and add ours.
                ByteArrayOutputStream udta = new ByteArrayOutputStream();
                for (int c = at + 8; c < at + size; ) {
                    int childSize = (int) Math.min(u32(moov, c), Integer.MAX_VALUE);
                    if (childSize < 8 || (long) c + childSize > (long) at + size) throw new IOException("bad box in udta");
                    if (!type(moov, c + 4).equals("meta")) udta.write(moov, c, childSize);
                    c += childSize;
                }
                udta.write(meta);
                writeBox(out, "udta", udta.toByteArray());
                merged = true;
            } else {
                out.write(moov, at, size);
            }
            at += size;
        }
        if (!merged) writeBox(out, "udta", meta);
        byte[] result = out.toByteArray();
        putU32(result, 0, result.length);
        return result;
    }

    private static byte[] metaBox(byte[] items) throws IOException {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        meta.write(new byte[4]); // FullBox version and flags
        ByteArrayOutputStream hdlr = new ByteArrayOutputStream();
        hdlr.write(new byte[8]); // version/flags, pre_defined
        hdlr.write(ascii("mdir"));
        hdlr.write(ascii("appl"));
        hdlr.write(new byte[9]); // reserved, and an empty name
        writeBox(meta, "hdlr", hdlr.toByteArray());
        writeBox(meta, "ilst", items);
        ByteArrayOutputStream box = new ByteArrayOutputStream();
        writeBox(box, "meta", meta.toByteArray());
        return box.toByteArray();
    }

    /** Adds {@code delta} to every chunk offset in the boxes of {@code buf[from, to)}. */
    private static void shiftChunkOffsets(byte[] buf, int from, int to, long delta) throws IOException {
        for (int at = from; at + 8 <= to; ) {
            long size = u32(buf, at);
            if (size < 8 || at + size > to) throw new IOException("bad box");
            String type = type(buf, at + 4);
            int end = (int) (at + size);
            switch (type) {
                case "trak": case "mdia": case "minf": case "stbl":
                    shiftChunkOffsets(buf, at + 8, end, delta);
                    break;
                case "stco": {
                    long count = u32(buf, at + 12);
                    if (at + 16 + count * 4 > end) throw new IOException("bad stco");
                    for (int i = 0; i < count; i++) {
                        int p = at + 16 + i * 4;
                        long shifted = u32(buf, p) + delta;
                        if (shifted > 0xFFFFFFFFL) throw new IOException("stco overflow");
                        putU32(buf, p, shifted);
                    }
                    break;
                }
                case "co64": {
                    long count = u32(buf, at + 12);
                    if (at + 16 + count * 8 > end) throw new IOException("bad co64");
                    for (int i = 0; i < count; i++) {
                        int p = at + 16 + i * 8;
                        long shifted = u64(buf, p) + delta;
                        putU32(buf, p, shifted >>> 32);
                        putU32(buf, p + 4, shifted);
                    }
                    break;
                }
            }
            at = end;
        }
    }

    // -- ilst items -----------------------------------------------------------------------------

    private static void text(ByteArrayOutputStream out, String type, String value) {
        if (value == null || value.trim().isEmpty()) return;
        item(out, type, 1, value.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** trkn and disk: number and total as 16-bit values; trkn carries two more padding bytes. */
    private static void pair(ByteArrayOutputStream out, String type, int number, int total, boolean trailer) {
        byte[] v = new byte[trailer ? 8 : 6];
        v[2] = (byte) (number >> 8);
        v[3] = (byte) number;
        v[4] = (byte) (Math.max(total, 0) >> 8);
        v[5] = (byte) Math.max(total, 0);
        item(out, type, 0, v);
    }

    /** {@code ----} item: mean "com.apple.iTunes", name, and a UTF-8 value. */
    private static void freeform(ByteArrayOutputStream out, String name, String value) {
        if (value == null || value.trim().isEmpty()) return;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] mean = concat(new byte[4], ascii("com.apple.iTunes"));
        byte[] nameBytes = concat(new byte[4], ascii(name));
        writeBox(body, "mean", mean);
        writeBox(body, "name", nameBytes);
        writeBox(body, "data", dataPayload(1, value.trim().getBytes(StandardCharsets.UTF_8)));
        writeBox(out, "----", body.toByteArray());
    }

    private static void item(ByteArrayOutputStream out, String type, int dataType, byte[] value) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        writeBox(data, "data", dataPayload(dataType, value));
        writeBox(out, type, data.toByteArray());
    }

    /** A data atom's payload: its type indicator, a zero locale, then the value. */
    private static byte[] dataPayload(int dataType, byte[] value) {
        byte[] p = new byte[8 + value.length];
        putU32(p, 0, dataType);
        System.arraycopy(value, 0, p, 8, value.length);
        return p;
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    // -- bytes ----------------------------------------------------------------------------------

    private static void writeBox(ByteArrayOutputStream out, String type, byte[] payload) {
        byte[] head = new byte[8];
        putU32(head, 0, 8L + payload.length);
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(t, 0, head, 4, 4);
        out.write(head, 0, 8);
        out.write(payload, 0, payload.length);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static String type(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.ISO_8859_1);
    }

    private static long u32(byte[] b, int at) {
        return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int at) {
        return (u32(b, at) << 32) | u32(b, at + 4);
    }

    private static void putU32(byte[] b, int at, long v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static void copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (count > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, count));
            if (n < 0) throw new IOException("short file");
            out.write(buf, 0, n);
            count -= n;
        }
    }

    private static void skip(InputStream in, long count) throws IOException {
        while (count > 0) {
            long n = in.skip(count);
            if (n <= 0) {
                if (in.read() < 0) throw new IOException("short file");
                n = 1;
            }
            count -= n;
        }
    }
}
