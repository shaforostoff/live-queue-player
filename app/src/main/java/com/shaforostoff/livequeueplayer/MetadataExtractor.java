package com.shaforostoff.livequeueplayer;

import android.content.ContentResolver;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class MetadataExtractor {

    private static final Pattern FILENAME_YEAR_PATTERN =
            Pattern.compile("(^|\\D)((?:19|20)\\d{2})(?=\\D|$)");
    private static final ThreadLocal<Matcher> FILENAME_YEAR_MATCHER = new ThreadLocal<Matcher>() {
        @Override protected Matcher initialValue() { return FILENAME_YEAR_PATTERN.matcher(""); }
    };
    private static final Pattern BPM_PATTERN = Pattern.compile("\\d{1,3}");
    private static final Pattern YEAR_IN_STRING_PATTERN = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern DATE_IN_COMMENT_PATTERN =
            Pattern.compile("((?:19|20)\\d{2}).(\\d{2}).(\\d{2})");
    private static final Pattern REPLAYGAIN_DB_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern LEADING_YEAR_PATTERN = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern NUMERIC_GENRE_PATTERN = Pattern.compile("\\(\\d+\\)");
    private static final float FALLBACK_REPLAY_GAIN = 1.0f;

    static class TagEntry {
        String date;
        String genre;
        String artist;
        String title;
        int bpm = -1;
    }

    // Cache keys are stored in a shortened, reversible form to save RAM (the cache can hold the
    // whole library). SAF document URIs all share this long prefix, collapsed to "ct://".
    private static final String SAF_TREE_PREFIX = "content://com.android.externalstorage.documents/tree/";
    private static final String SAF_TREE_PREFIX_SHORT = "ct://";

    private final ContentResolver contentResolver;
    private final Map<String, TagEntry> tagCache = Collections.synchronizedMap(new HashMap<>());
    /** Library roots whose full recursive tag scan has already been started this session. */
    private final Set<String> scannedRoots = Collections.synchronizedSet(new HashSet<>());
    /** Canonicalizes repeated genre/artist strings so equal values share one instance (saves RAM). */
    private final ConcurrentHashMap<String, String> valuePool = new ConcurrentHashMap<>();

    List<Map.Entry<String, TagEntry>> snapshotCacheEntries() {
        synchronized (tagCache) {
            return new ArrayList<>(tagCache.entrySet());
        }
    }

    void clearCache() {
        tagCache.clear();
        scannedRoots.clear();
        valuePool.clear();
    }

    /** Returns a canonical (shared) instance for {@code s}, so duplicate values aren't kept N times. */
    private String internValue(String s) {
        if (s == null || s.isEmpty()) return s;   // "" is an already-shared literal; don't pool it
        return valuePool.computeIfAbsent(s, k -> k);
    }

    /**
     * Atomically claims {@code rootKey} for a recursive tag scan. Returns {@code true} the first
     * time a given root is seen (caller should scan), {@code false} on later calls (already
     * scanned/claimed this session) so the expensive whole-tree walk is skipped.
     */
    boolean claimRootScan(String rootKey) {
        return rootKey != null && scannedRoots.add(rootKey);
    }

    /**
     * Maps a track URI to its (shortened) cache key. Losslessly reversible by {@link #keyToUri}:
     * the long SAF tree prefix becomes {@code ct://}, and {@code %20} becomes a space (a space in a
     * canonical URI only ever originates from {@code %20}, so this round-trips exactly). All other
     * percent-escapes are left verbatim so the original URI can be reconstructed for playback.
     */
    static String uriToKey(Uri uri) {
        String s = uri.toString();
        if (s.startsWith(SAF_TREE_PREFIX)) {
            s = SAF_TREE_PREFIX_SHORT + s.substring(SAF_TREE_PREFIX.length());
        }
        return s.replace("%20", " ");
    }

    /** Inverse of {@link #uriToKey}: rebuilds the exact canonical URI string from a cache key. */
    static String keyToUri(String key) {
        String s = key.replace(" ", "%20");
        if (s.startsWith(SAF_TREE_PREFIX_SHORT)) {
            s = SAF_TREE_PREFIX + s.substring(SAF_TREE_PREFIX_SHORT.length());
        }
        return s;
    }

    private TagEntry getOrCreate(String key) {
        return tagCache.computeIfAbsent(key, k -> new TagEntry());
    }

    MetadataExtractor(ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
    }

    boolean isAllTagsCached(Uri uri) {
        if (uri == null) return false;
        TagEntry e = tagCache.get(uriToKey(uri));
        return e != null && e.date != null && e.genre != null && e.artist != null && e.title != null && e.bpm >= 0;
    }

    /** True if {@code uri} is present in the cache, i.e. the file was enumerated this session. */
    boolean containsUri(Uri uri) {
        return uri != null && tagCache.containsKey(uriToKey(uri));
    }

    static String extractYearFromFileName(String fileName) {
        if (fileName == null || fileName.length() == 0) return "";
        Matcher matcher = FILENAME_YEAR_MATCHER.get().reset(fileName);
        if (!matcher.find()) return "";
        String year = matcher.group(2);
        return year == null ? "" : year;
    }

    TagEntry readSortTags(Uri uri) {
        if (uri == null) {
            TagEntry e = new TagEntry(); e.date = ""; e.genre = ""; e.bpm = 0; return e;
        }
        String key = uriToKey(uri);
        TagEntry e = tagCache.get(key);
        if (e != null && e.date != null && e.genre != null && e.title != null && e.bpm >= 0) return e;
        e = getOrCreate(key);
        String ext = getExtension(uri);
        switch (ext) {
            case "mp3":
                fillFromId3(uri, e);
                if (e.date == null || e.genre == null || e.bpm < 0)
                    fillFromRetriever(uri, e);
                break;
            case "flac":
                fillFromFlac(uri, e);
                if (e.date == null || e.genre == null || e.bpm < 0)
                    fillFromRetriever(uri, e);
                break;
            case "m4a": case "mp4": case "aac": case "alac":
                fillSortTagsFromMp4(uri, e);
                break;
            case "aif": case "aiff":
                fillFromAiff(uri, e);
                break;
            default:
                fillFromRetriever(uri, e);
                if (e.date == null || e.genre == null || e.bpm < 0) fillFromId3(uri, e);
                if (e.genre == null || e.bpm < 0) fillFromFlac(uri, e);
                fillSortTagsFromMp4(uri, e);
                break;
        }
        if (e.genre != null) e.genre = normalizeGenreValue(e.genre);
        if (e.date == null) e.date = "";
        if (e.genre == null) e.genre = "";
        if (e.artist == null) e.artist = "";
        if (e.title == null) e.title = "";
        if (e.bpm < 0) e.bpm = 0;
        // genre/artist repeat heavily across a library; share one instance per distinct value.
        e.genre  = internValue(e.genre);
        e.artist = internValue(e.artist);
        return e;
    }

    private static String getExtension(Uri uri) {
        String path = uri.getPath();
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    String readLyricsTag(Uri uri) {
        if (uri == null) return "";
        String extracted = readLyricsTagFromId3(uri);
        if (extracted.length() == 0) extracted = readLyricsTagFromFlac(uri);
        if (extracted.length() == 0) extracted = readLyricsTagFromMp4(uri);
        return normalizeLyricsValue(extracted);
    }

    float readReplayGain(Uri uri) {
        if (uri == null) return FALLBACK_REPLAY_GAIN;
        float gain;
        switch (getExtension(uri)) {
            case "mp3":
                gain = readReplayGainFromId3(uri);
                break;
            case "flac":
                gain = readReplayGainFromFlac(uri);
                break;
            case "m4a": case "mp4": case "aac": case "alac":
                gain = readReplayGainFromMp4(uri);
                break;
            default:
                gain = readReplayGainFromId3(uri);
                if (gain < 0f) gain = readReplayGainFromFlac(uri);
                if (gain < 0f) gain = readReplayGainFromMp4(uri);
                break;
        }
        return gain > 0f ? gain : FALLBACK_REPLAY_GAIN;
    }

    private void fillFromRetriever(Uri uri, TagEntry e) {
        // Use ParcelFileDescriptor so this works for both file:// and content:// (SAF) URIs.
        // setDataSource(Context, Uri) silently fails on many devices for SAF document URIs.
        ParcelFileDescriptor pfd = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            pfd = contentResolver.openFileDescriptor(uri, "r");
            if (pfd == null) return;
            retriever.setDataSource(pfd.getFileDescriptor());

            if (e.date == null) {
                String year = null;
                // Key 8 = METADATA_KEY_DATE (M4A files store YYYY-MM-DD here)
                String dateKey8 = retriever.extractMetadata(8);
                if (dateKey8 != null && LEADING_YEAR_PATTERN.matcher(dateKey8).lookingAt()) {
                    year = dateKey8;
                }
                // Key 17 = METADATA_KEY_YEAR (available since API 10)
                if (year == null) year = nonEmpty(retriever.extractMetadata(17));
                // Key 10 = METADATA_KEY_DATE (alternative)
                if (year == null) {
                    String dateKey10 = retriever.extractMetadata(10);
                    if (dateKey10 != null && YEAR_IN_STRING_PATTERN.matcher(dateKey10).find()) year = dateKey10;
                }
                if (year != null) e.date = normalizeDateValue(year);
            }
            if (e.genre == null) e.genre = nonEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));
            if (e.artist == null) e.artist = nonEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (e.title == null) e.title = nonEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------------------------- ID3v2

    private static final String SORT_FRAMES = "TDRC TYER TCON TPE1 TIT2 TBPM COMM";

    private void fillFromId3(Uri uri, TagEntry e) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream != null) fillFromId3Stream(stream, e);
        } catch (Exception ignored) {
        }
    }

    private static void fillFromId3Stream(InputStream stream, TagEntry e) throws IOException {
        boolean needDate = e.date == null;
        String tdrc = null, tyer = null, comm = null;
        for (Id3Frame f : readId3Frames(stream, SORT_FRAMES)) {
            switch (f.id) {
                case "TDRC": if (tdrc == null) tdrc = nonEmpty(normalizeDateValue(f.text())); break;
                case "TYER": if (tyer == null) tyer = nonEmpty(normalizeDateValue(f.text())); break;
                case "TCON": if (e.genre == null) e.genre = nonEmpty(f.text()); break;
                case "TPE1": if (e.artist == null) e.artist = nonEmpty(f.text()); break;
                case "TIT2": if (e.title == null) e.title = nonEmpty(f.text()); break;
                case "TBPM": {
                    String v = f.text();
                    if (e.bpm < 0 && !v.isEmpty()) e.bpm = parseBpmValue(v);
                    break;
                }
                case "COMM": if (comm == null) comm = decodeUsltText(f.body); break; // first only
            }
        }
        if (needDate) e.date = tdrc != null ? tdrc : tyer;
        applyCommentDate(e, comm);
    }

    private String readLyricsTagFromId3(Uri uri) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return "";
            for (Id3Frame f : readId3Frames(stream, "USLT")) {
                String lyrics = decodeUsltText(f.body);
                if (!lyrics.isEmpty()) return lyrics;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private float readReplayGainFromId3(Uri uri) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return -1f;
            for (Id3Frame f : readId3Frames(stream, "TXXX")) {
                float parsed = parseReplayGainLinear(decodeId3UserText(f.body, "REPLAYGAIN_TRACK_GAIN"));
                if (parsed > 0f) return parsed;
            }
        } catch (Exception ignored) {
        }
        return -1f;
    }

    private void fillFromAiff(Uri uri, TagEntry e) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return;

            byte[] formHeader = new byte[12];
            if (!readFully(stream, formHeader, 12)) return;
            if (!frameIdIs(formHeader, 0, "FORM")) return;
            if (!frameIdIs(formHeader, 8, "AIFF") && !frameIdIs(formHeader, 8, "AIFC")) return;

            byte[] chunkHeader = new byte[8];
            while (readFully(stream, chunkHeader, 8)) {
                long chunkSize = decodeUnsignedInt(chunkHeader, 4);
                // The ID3 reader streams frame by frame from here, so the chunk needs no buffering.
                if (frameIdIs(chunkHeader, 0, "ID3 ") && chunkSize >= 10) {
                    fillFromId3Stream(stream, e);
                    return;
                }
                if (!skipFully(stream, chunkSize + (chunkSize & 1))) return;
            }
        } catch (Exception ignored) {
        }
    }

    /** One ID3v2 frame: its id and raw body. */
    private static final class Id3Frame {
        final String id;
        final byte[] body;

        Id3Frame(String id, byte[] body) {
            this.id = id;
            this.body = body;
        }

        String text() {
            return decodeId3Text(body);
        }
    }

    /**
     * The frames of the ID3v2.3/2.4 tag at the start of {@code stream} whose id is listed in
     * {@code wanted}, in tag order. Frames are read one at a time and the unwanted ones skipped
     * unread, so a tag carrying megabytes of cover art costs no more than the text frames asked for.
     */
    private static List<Id3Frame> readId3Frames(InputStream stream, String wanted) throws IOException {
        List<Id3Frame> frames = new ArrayList<>();
        byte[] header = new byte[10];
        if (!readFully(stream, header, 10) || !frameIdIs(header, 0, "ID3")) return frames;
        int majorVersion = header[3] & 0xFF;
        // v2.2 has 3-character ids and 6-byte frame headers; it was never parsed correctly before
        // either (its frames never matched), so it is simply not supported.
        if (majorVersion < 3 || majorVersion > 4) return frames;
        long remaining = decodeSyncSafeInt(header, 6);

        if ((header[5] & 0x40) != 0) {               // extended header
            if (remaining < 4 || !readFully(stream, header, 4)) return frames;
            remaining -= 4;
            // v2.4 counts the size field itself; v2.3 does not.
            long rest = majorVersion == 4 ? decodeSyncSafeInt(header, 0) - 4L : decodeInt(header, 0) & 0xFFFFFFFFL;
            if (rest < 0 || rest > remaining || !skipFully(stream, rest)) return frames;
            remaining -= rest;
        }

        while (remaining >= 10 && readFully(stream, header, 10)) {
            remaining -= 10;
            if (header[0] == 0) break;                // padding
            int size = majorVersion == 4 ? decodeSyncSafeInt(header, 4) : decodeInt(header, 4);
            if (size <= 0 || size > remaining) break;
            remaining -= size;
            String id = new String(header, 0, 4, StandardCharsets.ISO_8859_1);
            if (wanted.contains(id) && size <= MAX_VALUE_BYTES) {
                byte[] body = new byte[size];
                if (!readFully(stream, body, size)) break;
                frames.add(new Id3Frame(id, body));
            } else if (!skipFully(stream, size)) {
                break;
            }
        }
        return frames;
    }

    private static String id3Charset(int encoding) {
        switch (encoding) {
            case 1:  return "UTF-16";
            case 2:  return "UTF-16BE";
            case 3:  return "UTF-8";
            default: return "ISO-8859-1";
        }
    }

    /** A text frame's first value (v2.4 separates several with NULs), trimmed. */
    private static String decodeId3Text(byte[] body) {
        if (body.length <= 1) return "";
        try {
            String value = new String(body, 1, body.length - 1, id3Charset(body[0] & 0xFF));
            int nullTerminator = value.indexOf('\u0000');
            if (nullTerminator >= 0) value = value.substring(0, nullTerminator);
            return value.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** A TXXX frame's value when its description is {@code targetDescription}, else "". */
    private static String decodeId3UserText(byte[] body, String targetDescription) {
        if (body.length <= 1) return "";
        try {
            String decoded = new String(body, 1, body.length - 1, id3Charset(body[0] & 0xFF));
            int nullTerminator = decoded.indexOf('\u0000');
            if (nullTerminator <= 0) return "";
            String description = decoded.substring(0, nullTerminator).trim();
            if (!description.equalsIgnoreCase(targetDescription)) return "";
            return decoded.substring(nullTerminator + 1).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** The text of a USLT or COMM frame: encoding, language, NUL-terminated description, text. */
    private static String decodeUsltText(byte[] body) {
        if (body.length <= 4) return "";
        int encoding = body[0] & 0xFF;
        int terminatorLength = encoding == 1 || encoding == 2 ? 2 : 1;
        int textStart = findTextTerminator(body, 4, body.length, terminatorLength) + terminatorLength;
        if (textStart >= body.length) return "";
        try {
            return new String(body, textStart, body.length - textStart, id3Charset(encoding)).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int findTextTerminator(byte[] data, int start, int end, int terminatorLength) {
        if (terminatorLength <= 1) {
            for (int i = start; i < end; i++) {
                if (data[i] == 0) return i;
            }
            return end;
        }
        for (int i = start; i + 1 < end; i += 2) {
            if (data[i] == 0 && data[i + 1] == 0) return i;
        }
        return end;
    }

    // ------------------------------------------------------------------------- FLAC / Vorbis

    private void fillFromFlac(Uri uri, TagEntry e) {
        Map<String, String> c = readVorbisComments(uri);
        if (c.isEmpty()) return;
        if (e.date == null) {
            String v = nonEmpty(c.get("DATE"));
            if (v != null) e.date = normalizeDateValue(v);
            applyCommentDate(e, c.get("COMMENT"));
        }
        if (e.genre == null) e.genre = nonEmpty(c.get("GENRE"));
        if (e.artist == null) e.artist = nonEmpty(c.get("ARTIST"));
        if (e.title == null) e.title = nonEmpty(c.get("TITLE"));
        if (e.bpm < 0) {
            String v = nonEmpty(c.get("BPM"));
            if (v == null) v = nonEmpty(c.get("TEMPO"));
            if (v != null) e.bpm = parseBpmValue(v);
        }
    }

    private String readLyricsTagFromFlac(Uri uri) {
        Map<String, String> c = readVorbisComments(uri);
        for (String key : new String[]{"LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS"}) {
            String lyrics = nonEmpty(c.get(key));
            if (lyrics != null) return lyrics;
        }
        return "";
    }

    private float readReplayGainFromFlac(Uri uri) {
        return parseReplayGainLinear(readVorbisComments(uri).get("REPLAYGAIN_TRACK_GAIN"));
    }

    /**
     * Every comment of a FLAC file's VORBIS_COMMENT block, keyed by upper-cased field name; the
     * first occurrence of a field wins. Empty when the file is not FLAC or has no comments.
     */
    private Map<String, String> readVorbisComments(Uri uri) {
        Map<String, String> out = new HashMap<>();
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return out;
            byte[] header = new byte[4];
            if (!readFully(stream, header, 4) || !frameIdIs(header, 0, "fLaC")) return out;
            boolean isLastBlock = false;
            while (!isLastBlock) {
                if (!readFully(stream, header, 4)) return out;
                isLastBlock = (header[0] & 0x80) != 0;
                int blockType = header[0] & 0x7F;
                int blockLength = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                if (blockType != 4) {
                    if (!skipFully(stream, blockLength)) return out;
                    continue;
                }
                byte[] data = new byte[blockLength];
                if (!readFully(stream, data, blockLength)) return out;
                parseVorbisComments(data, out);
                return out;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void parseVorbisComments(byte[] data, Map<String, String> out) {
        int vendorLength = decodeLittleEndianInt(data, 0);
        if (vendorLength < 0) return;
        int offset = 4 + vendorLength;
        int commentCount = decodeLittleEndianInt(data, offset);
        if (commentCount < 0) return;
        offset += 4;
        for (int i = 0; i < commentCount; i++) {
            int commentLength = decodeLittleEndianInt(data, offset);
            offset += 4;
            if (commentLength < 0 || offset + commentLength > data.length) return;
            String comment = new String(data, offset, commentLength, StandardCharsets.UTF_8);
            offset += commentLength;
            int equals = comment.indexOf('=');
            if (equals <= 0) continue;
            out.putIfAbsent(comment.substring(0, equals).toUpperCase(Locale.ROOT),
                    comment.substring(equals + 1).trim());
        }
    }

    /** Little-endian int at {@code offset}, or -1 when it runs past the end (or before the start). */
    private static int decodeLittleEndianInt(byte[] data, int offset) {
        if (offset < 0 || offset + 3 >= data.length) return -1;
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    // ------------------------------------------------------------------------------------ MP4

    private static final int ATOM_MOOV = 0x6D6F6F76, ATOM_UDTA = 0x75647461, ATOM_META = 0x6D657461,
            ATOM_ILST = 0x696C7374, ATOM_TRAK = 0x7472616B, ATOM_MDIA = 0x6D646961,
            ATOM_MINF = 0x6D696E66, ATOM_STBL = 0x7374626C, ATOM_EDTS = 0x65647473,
            ATOM_MVEX = 0x6D766578, ATOM_DATA = 0x64617461, ATOM_MEAN = 0x6D65616E,
            ATOM_NAME = 0x6E616D65, ATOM_FREEFORM = 0x2D2D2D2D;
    private static final int ITEM_DATE = 0xA9646179, ITEM_GENRE = 0xA967656E, ITEM_ARTIST = 0xA9415254,
            ITEM_TITLE = 0xA96E616D, ITEM_TEMPO = 0x746D706F, ITEM_COMMENT = 0xA9636D74,
            ITEM_LYRICS = 0xA96C7972;

    private static final String ITUNES = "com.apple.iTunes";
    private static final String HYDROGENAUDIO = "org.hydrogenaudio.replaygain";

    /**
     * The {@code ilst} items this class reads, collected in one walk of the file: standard items by
     * atom type, freeform ({@code ----}) items under "mean/NAME" with the name upper-cased (names
     * are matched case-insensitively). Only non-empty values are kept; the first one wins.
     */
    private static final class Mp4Tags {
        final Map<Integer, String> items = new HashMap<>();
        final Map<String, String> freeform = new HashMap<>();

        String item(int type) {
            return items.get(type);
        }

        String freeform(String mean, String name) {
            return freeform.get(mean + "/" + name.toUpperCase(Locale.ROOT));
        }
    }

    private void fillSortTagsFromMp4(Uri uri, TagEntry e) {
        if (e.date != null && e.genre != null && e.title != null && e.bpm >= 0) return;
        Mp4Tags t = readMp4Tags(uri);
        if (e.date == null) {
            String date = t.item(ITEM_DATE);
            if (date != null) e.date = normalizeDateValue(date);
        }
        if (e.genre == null) e.genre = t.item(ITEM_GENRE);
        if (e.artist == null) e.artist = t.item(ITEM_ARTIST);
        if (e.title == null) e.title = t.item(ITEM_TITLE);
        if (e.bpm < 0) {
            String bpm = t.item(ITEM_TEMPO);
            if (bpm == null) bpm = t.freeform(ITUNES, "BPM");
            if (bpm != null) e.bpm = parseBpmValue(bpm);
        }
        applyCommentDate(e, t.item(ITEM_COMMENT));
    }

    private String readLyricsTagFromMp4(Uri uri) {
        Mp4Tags t = readMp4Tags(uri);
        String lyrics = t.item(ITEM_LYRICS);
        if (lyrics == null) lyrics = t.freeform(ITUNES, "LYRICS");
        return lyrics != null ? lyrics : "";
    }

    private float readReplayGainFromMp4(Uri uri) {
        Mp4Tags t = readMp4Tags(uri);
        String gain = t.freeform(ITUNES, "REPLAYGAIN_TRACK_GAIN");
        if (gain == null) gain = t.freeform(HYDROGENAUDIO, "TRACK_GAIN");
        if (gain == null) gain = t.freeform(HYDROGENAUDIO, "REPLAYGAIN_TRACK_GAIN");
        return parseReplayGainLinear(gain);
    }

    /** Walks the file once; on a read error or malformed atom, returns whatever was found so far. */
    private Mp4Tags readMp4Tags(Uri uri) {
        Mp4Tags tags = new Mp4Tags();
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream != null) walkMp4(stream, Long.MAX_VALUE, false, tags);
        } catch (Exception ignored) {
        }
        return tags;
    }

    /** Header of one MP4 atom: its type and payload size (header excluded). */
    private static final class Atom {
        final byte[] buf = new byte[8];
        int type;
        long headerSize;
        long payload;
    }

    /**
     * Reads the next atom header from a container with {@code available} bytes left. False at the
     * end of the container, at EOF, or on a size that cannot fit; the caller then stops walking.
     */
    private static boolean readAtom(InputStream stream, long available, Atom a) throws IOException {
        if (available < 8 || !readFully(stream, a.buf, 8)) return false;
        long size = decodeUnsignedInt(a.buf, 0);
        a.type = decodeInt(a.buf, 4);
        a.headerSize = 8;
        if (size == 1) {                      // 64-bit size follows the type
            if (available < 16 || !readFully(stream, a.buf, 8)) return false;
            size = decodeLong(a.buf, 0);
            a.headerSize = 16;
        } else if (size == 0) {               // extends to the end of its container
            size = available;
        }
        if (size < a.headerSize || size > available) return false;
        a.payload = size - a.headerSize;
        return true;
    }

    private static void walkMp4(InputStream stream, long available, boolean inIlst, Mp4Tags tags)
            throws IOException {
        Atom a = new Atom();
        while (readAtom(stream, available, a)) {
            available -= a.headerSize + a.payload;
            long payload = a.payload;
            if (inIlst && a.type == ATOM_FREEFORM) {
                readFreeformItem(stream, payload, tags);
            } else if (inIlst && isWantedItem(a.type)) {
                String value = readItemData(stream, payload, a.type == ITEM_TEMPO);
                if (!value.isEmpty()) tags.items.putIfAbsent(a.type, value);
            } else if (a.type == ATOM_META && payload >= 4) {
                if (!skipFully(stream, 4)) return;      // FullBox version/flags
                walkMp4(stream, payload - 4, false, tags);
            } else if (a.type != ATOM_META && isMp4ContainerAtom(a.type)) {
                walkMp4(stream, payload, a.type == ATOM_ILST, tags);
            } else if (!skipFully(stream, payload)) {
                return;
            }
        }
    }

    private static boolean isWantedItem(int type) {
        return type == ITEM_DATE || type == ITEM_GENRE || type == ITEM_ARTIST || type == ITEM_TITLE
                || type == ITEM_TEMPO || type == ITEM_COMMENT || type == ITEM_LYRICS;
    }

    private static boolean isMp4ContainerAtom(int type) {
        return type == ATOM_MOOV || type == ATOM_UDTA || type == ATOM_META || type == ATOM_ILST
                || type == ATOM_TRAK || type == ATOM_MDIA || type == ATOM_MINF || type == ATOM_STBL
                || type == ATOM_EDTS || type == ATOM_MVEX;
    }

    /** The first {@code data} atom of a standard item, decoded; the whole item is consumed. */
    private static String readItemData(InputStream stream, long itemPayload, boolean tempo)
            throws IOException {
        Atom a = new Atom();
        String value = "";
        long available = itemPayload;
        while (value.isEmpty() && readAtom(stream, available, a)) {
            available -= a.headerSize + a.payload;
            byte[] bytes = a.type == ATOM_DATA ? readPayload(stream, a.payload) : null;
            if (bytes == null) {
                if (a.type == ATOM_DATA || !skipFully(stream, a.payload)) return value;
                continue;
            }
            if (bytes.length >= 8) {    // 4-byte type indicator + 4-byte locale precede the value
                value = tempo ? decodeMp4Tempo(bytes) : decodeMp4Text(bytes, 8);
            }
        }
        skipFully(stream, available);
        return value;
    }

    /** A {@code ----} item: its {@code mean}, {@code name} and {@code data} children. */
    private static void readFreeformItem(InputStream stream, long itemPayload, Mp4Tags tags)
            throws IOException {
        Atom a = new Atom();
        String mean = "", name = "", data = "";
        long available = itemPayload;
        while (readAtom(stream, available, a)) {
            available -= a.headerSize + a.payload;
            boolean wanted = a.type == ATOM_MEAN || a.type == ATOM_NAME || a.type == ATOM_DATA;
            byte[] bytes = wanted ? readPayload(stream, a.payload) : null;
            if (bytes == null) {
                if (wanted || !skipFully(stream, a.payload)) return;
                continue;
            }
            // mean/name carry 4 bytes of version/flags; data carries type indicator + locale.
            if (a.type == ATOM_MEAN) mean = decodeMp4Text(bytes, 4);
            else if (a.type == ATOM_NAME) name = decodeMp4Text(bytes, 4);
            else data = decodeMp4Text(bytes, 8);
        }
        skipFully(stream, available);
        if (!data.isEmpty()) tags.freeform.putIfAbsent(mean + "/" + name.toUpperCase(Locale.ROOT), data);
    }

    /** The payload; empty when it is too large to be a tag value (it is skipped), null at EOF. */
    private static byte[] readPayload(InputStream stream, long size) throws IOException {
        if (size > MAX_VALUE_BYTES) return skipFully(stream, size) ? new byte[0] : null;
        byte[] bytes = new byte[(int) size];
        return readFully(stream, bytes, bytes.length) ? bytes : null;
    }

    private static String decodeMp4Text(byte[] data, int offset) {
        if (data.length <= offset) return "";
        return new String(data, offset, data.length - offset, StandardCharsets.UTF_8).trim();
    }

    /** {@code tmpo} is a big-endian integer after the 8-byte data header, not text. */
    private static String decodeMp4Tempo(byte[] data) {
        int n = data.length - 8;
        if (n == 1) return Integer.toString(data[8] & 0xFF);
        if (n >= 2) {
            int value = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
            if (value > 0) return Integer.toString(value);
        }
        return "";
    }

    // -------------------------------------------------------------------------------- shared

    /** Largest single tag value read into memory; anything bigger (cover art) is skipped. */
    private static final int MAX_VALUE_BYTES = 2 * 1024 * 1024;

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * Fills in a full date from a comment like "... 1936.05.12 ..." when the tags gave none, or only
     * a bare year — old 78 transfers often carry the recording date only in the comment.
     */
    private static void applyCommentDate(TagEntry e, String comment) {
        if (comment == null || comment.isEmpty()) return;
        if (e.date != null && (e.date.isEmpty() || e.date.length() >= 5)) return;
        Matcher m = DATE_IN_COMMENT_PATTERN.matcher(comment);
        if (m.find()) e.date = m.group(1) + "-" + m.group(2) + "-" + m.group(3);
    }

    private static boolean skipFully(InputStream stream, long bytesToSkip) throws IOException {
        long remaining = bytesToSkip;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped <= 0) {
                if (stream.read() < 0) return false;
                skipped = 1;
            }
            remaining -= skipped;
        }
        return true;
    }

    private static boolean readFully(InputStream stream, byte[] buffer, int size) throws IOException {
        int readTotal = 0;
        while (readTotal < size) {
            int read = stream.read(buffer, readTotal, size - readTotal);
            if (read < 0) return false;
            readTotal += read;
        }
        return true;
    }

    private static int decodeSyncSafeInt(byte[] data, int offset) {
        return ((data[offset] & 0x7F) << 21)
                | ((data[offset + 1] & 0x7F) << 14)
                | ((data[offset + 2] & 0x7F) << 7)
                | (data[offset + 3] & 0x7F);
    }

    private static int decodeInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static long decodeUnsignedInt(byte[] data, int offset) {
        return decodeInt(data, offset) & 0xFFFFFFFFL;
    }

    private static long decodeLong(byte[] data, int offset) {
        return (decodeUnsignedInt(data, offset) << 32) | decodeUnsignedInt(data, offset + 4);
    }

    private static boolean frameIdIs(byte[] data, int offset, String id) {
        for (int i = 0; i < id.length(); i++) {
            if (data[offset + i] != (byte) id.charAt(i)) return false;
        }
        return true;
    }

    private static String normalizeDateValue(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        int tPos = trimmed.indexOf('T');
        if (tPos > 0) trimmed = trimmed.substring(0, tPos);
        return trimmed;
    }

    private static String normalizeGenreValue(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        // ID3 TCON may contain numeric code in parentheses; keep user-friendly values only.
        if (NUMERIC_GENRE_PATTERN.matcher(trimmed).matches()) return "";
        return trimmed;
    }

    private static String normalizeLyricsValue(String value) {
        if (value == null) return "";
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static int parseBpmValue(String value) {
        if (value == null) return 0;
        Matcher matcher = BPM_PATTERN.matcher(value);
        if (!matcher.find()) return 0;
        try {
            int parsed = Integer.parseInt(matcher.group());
            if (parsed <= 0 || parsed > 400) return 0;
            return parsed;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static float parseReplayGainLinear(String value) {
        if (value == null) return -1f;
        Matcher matcher = REPLAYGAIN_DB_PATTERN.matcher(value);
        if (!matcher.find()) return -1f;
        try {
            float db = Float.parseFloat(matcher.group());
            float linear = (float) Math.pow(10d, db / 20d);
            if (Float.isNaN(linear) || Float.isInfinite(linear) || linear <= 0f) return -1f;
            return Math.min(1f, linear);
        } catch (Exception ignored) {
            return -1f;
        }
    }
}
