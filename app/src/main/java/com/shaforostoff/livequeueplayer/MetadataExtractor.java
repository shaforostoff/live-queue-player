package com.shaforostoff.livequeueplayer;

import android.content.ContentResolver;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class MetadataExtractor {

    private static final Pattern FILENAME_YEAR_PATTERN =
            Pattern.compile("(^|\\D)((?:19|20)\\d{2})(?=\\D|$)");
    private static final Pattern BPM_PATTERN = Pattern.compile("\\d{1,3}");
    private static final Pattern YEAR_IN_STRING_PATTERN = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern DATE_IN_COMMENT_PATTERN =
            Pattern.compile("((?:19|20)\\d{2}).(\\d{2}).(\\d{2})");
    private static final Pattern REPLAYGAIN_DB_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final float FALLBACK_REPLAY_GAIN = 1.0f;

    static class TagEntry {
        String date;
        String genre;
        String artist;
        int bpm = -1;
    }

    private final ContentResolver contentResolver;
    private final Map<String, TagEntry> tagCache = Collections.synchronizedMap(new HashMap<>());

    private TagEntry getOrCreate(String key) {
        return tagCache.computeIfAbsent(key, k -> new TagEntry());
    }

    MetadataExtractor(ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
    }

    @SuppressWarnings("unused")
    boolean isLyricsCached(Uri uri) {
        return false;
    }

    boolean isAllTagsCached(Uri uri) {
        if (uri == null) return false;
        TagEntry e = tagCache.get(uri.toString());
        return e != null && e.date != null && e.genre != null && e.artist != null && e.bpm >= 0;
    }

    static String extractYearFromFileName(String fileName) {
        if (fileName == null || fileName.length() == 0) return "";
        Matcher matcher = FILENAME_YEAR_PATTERN.matcher(fileName);
        if (!matcher.find()) return "";
        String year = matcher.group(2);
        return year == null ? "" : year;
    }

    TagEntry readSortTags(Uri uri) {
        if (uri == null) {
            TagEntry e = new TagEntry(); e.date = ""; e.genre = ""; e.bpm = 0; return e;
        }
        String key = uri.toString();
        TagEntry e = tagCache.get(key);
        if (e != null && e.date != null && e.genre != null && e.bpm >= 0) return e;
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
        if (e.bpm < 0) e.bpm = 0;
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
        float gain = -1f;
        String ext = getExtension(uri);
        switch (ext) {
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
                if (dateKey8 != null && dateKey8.length() >= 4) {
                    String firstFour = dateKey8.substring(0, 4);
                    if (firstFour.matches("\\d{4}") && (firstFour.startsWith("19") || firstFour.startsWith("20"))) {
                        year = dateKey8;
                    }
                }
                // Key 17 = METADATA_KEY_YEAR (available since API 10)
                if (year == null || year.isEmpty()) {
                    String yearKey17 = retriever.extractMetadata(17);
                    if (yearKey17 != null && !yearKey17.isEmpty()) year = yearKey17;
                }
                // Key 10 = METADATA_KEY_DATE (alternative)
                if (year == null || year.isEmpty()) {
                    String dateKey10 = retriever.extractMetadata(10);
                    if (dateKey10 != null && !dateKey10.isEmpty()) {
                        Matcher m = YEAR_IN_STRING_PATTERN.matcher(dateKey10);
                        if (m.find()) year = dateKey10;
                    }
                }
                if (year != null && !year.isEmpty()) e.date = normalizeDateValue(year);
            }

            if (e.genre == null) {
                String genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
                if (genre != null && !genre.isEmpty()) e.genre = genre;
            }
            if (e.artist == null) {
                String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (artist != null && !artist.isEmpty()) e.artist = artist;
            }
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        }
    }

    private void fillFromId3(Uri uri, TagEntry e) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return;

            byte[] header = new byte[10];
            if (!readFully(stream, header, 10)) return;
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return;

            int majorVersion = header[3] & 0xFF;
            if (majorVersion < 2 || majorVersion > 4) return;

            int tagSize = decodeSyncSafeInt(header, 6);
            if (tagSize <= 0 || tagSize > (2 * 1024 * 1024)) return;

            byte[] tagData = new byte[tagSize];
            if (!readFully(stream, tagData, tagSize)) return;

            int startOffset = 0;
            boolean hasExtendedHeader = (header[5] & 0x40) != 0;
            if (hasExtendedHeader && tagData.length >= 4) {
                startOffset = majorVersion == 4
                        ? decodeSyncSafeInt(tagData, 0)
                        : decodeInt(tagData, 0);
                if (startOffset < 0 || startOffset >= tagData.length) return;
            }

            boolean needDate = e.date == null;
            boolean needGenre = e.genre == null;
            boolean needArtist = e.artist == null;
            boolean needBpm = e.bpm < 0;
            String tdrc = null, tyer = null;

            for (int offset = startOffset; offset + 10 <= tagData.length; ) {
                String frameId = new String(tagData, offset, 4, "ISO-8859-1");
                if (isZeroFrameId(frameId)) break;

                int frameSize = majorVersion == 4
                        ? decodeSyncSafeInt(tagData, offset + 4)
                        : decodeInt(tagData, offset + 4);
                if (frameSize <= 0 || offset + 10 + frameSize > tagData.length) break;

                if (needDate && "TDRC".equals(frameId)) {
                    String v = decodeId3Text(tagData, offset + 10, frameSize);
                    if (v.length() > 0) tdrc = normalizeDateValue(v);
                } else if (needDate && "TYER".equals(frameId)) {
                    String v = decodeId3Text(tagData, offset + 10, frameSize);
                    if (v.length() > 0) tyer = normalizeDateValue(v);
                } else if (needGenre && "TCON".equals(frameId)) {
                    String v = decodeId3Text(tagData, offset + 10, frameSize);
                    if (v.length() > 0) { e.genre = v; needGenre = false; }
                } else if (needArtist && "TPE1".equals(frameId)) {
                    String v = decodeId3Text(tagData, offset + 10, frameSize);
                    if (v.length() > 0) { e.artist = v; needArtist = false; }
                } else if (needBpm && "TBPM".equals(frameId)) {
                    String v = decodeId3Text(tagData, offset + 10, frameSize);
                    if (v.length() > 0) { e.bpm = parseBpmValue(v); needBpm = false; }
                }

                if (!needDate && !needGenre && !needArtist && !needBpm) break;
                offset += 10 + frameSize;
            }

            if (needDate) {
                if (tdrc != null) e.date = tdrc;
                else if (tyer != null) e.date = tyer;
            }

            // If date is absent or a bare year, scan the already-loaded tag bytes for a COMM frame
            if (e.date == null || (e.date.length() > 0 && e.date.length() < 5)) {
                for (int offset = startOffset; offset + 10 <= tagData.length; ) {
                    String frameId = new String(tagData, offset, 4, "ISO-8859-1");
                    if (isZeroFrameId(frameId)) break;
                    int frameSize = majorVersion == 4
                            ? decodeSyncSafeInt(tagData, offset + 4)
                            : decodeInt(tagData, offset + 4);
                    if (frameSize <= 0 || offset + 10 + frameSize > tagData.length) break;
                    if ("COMM".equals(frameId)) {
                        String comm = decodeUsltText(tagData, offset + 10, frameSize);
                        if (!comm.isEmpty()) {
                            Matcher m = DATE_IN_COMMENT_PATTERN.matcher(comm);
                            if (m.find())
                                e.date = m.group(1) + "-" + m.group(2) + "-" + m.group(3);
                        }
                        break;
                    }
                    offset += 10 + frameSize;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String readLyricsTagFromId3(Uri uri) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return "";

            byte[] header = new byte[10];
            if (!readFully(stream, header, 10)) return "";
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return "";

            int majorVersion = header[3] & 0xFF;
            if (majorVersion < 2 || majorVersion > 4) return "";

            int tagSize = decodeSyncSafeInt(header, 6);
            if (tagSize <= 0 || tagSize > (2 * 1024 * 1024)) return "";

            byte[] tagData = new byte[tagSize];
            if (!readFully(stream, tagData, tagSize)) return "";

            int startOffset = 0;
            boolean hasExtendedHeader = (header[5] & 0x40) != 0;
            if (hasExtendedHeader && tagData.length >= 4) {
                startOffset = majorVersion == 4
                        ? decodeSyncSafeInt(tagData, 0)
                        : decodeInt(tagData, 0);
                if (startOffset < 0 || startOffset >= tagData.length) return "";
            }

            for (int offset = startOffset; offset + 10 <= tagData.length; ) {
                String frameId = new String(tagData, offset, 4, "ISO-8859-1");
                if (isZeroFrameId(frameId)) break;

                int frameSize = majorVersion == 4
                        ? decodeSyncSafeInt(tagData, offset + 4)
                        : decodeInt(tagData, offset + 4);
                if (frameSize <= 0 || offset + 10 + frameSize > tagData.length) break;

                if ("USLT".equals(frameId)) {
                    String lyrics = decodeUsltText(tagData, offset + 10, frameSize);
                    if (lyrics.length() > 0) return lyrics;
                }

                offset += 10 + frameSize;
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private void fillFromFlac(Uri uri, TagEntry e) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return;
            byte[] signature = new byte[4];
            if (!readFully(stream, signature, 4)) return;
            if (signature[0] != 'f' || signature[1] != 'L' || signature[2] != 'a' || signature[3] != 'C') return;
            boolean isLastBlock = false;
            while (!isLastBlock) {
                byte[] blockHeader = new byte[4];
                if (!readFully(stream, blockHeader, 4)) return;
                isLastBlock = (blockHeader[0] & 0x80) != 0;
                int blockType = blockHeader[0] & 0x7F;
                int blockLength = ((blockHeader[1] & 0xFF) << 16)
                        | ((blockHeader[2] & 0xFF) << 8)
                        | (blockHeader[3] & 0xFF);
                if (blockLength < 0 || blockLength > (16 * 1024 * 1024)) return;
                if (blockType != 4) { if (!skipFully(stream, blockLength)) return; continue; }
                byte[] commentData = new byte[blockLength];
                if (!readFully(stream, commentData, blockLength)) return;
                if (e.date == null) {
                    String v = readVorbisCommentValue(commentData, "DATE");
                    if (v.length() > 0) e.date = normalizeDateValue(v);
                    if (e.date == null || (e.date.length() > 0 && e.date.length() < 5)) {
                        String comm = readVorbisCommentValue(commentData, "COMMENT");
                        if (!comm.isEmpty()) {
                            Matcher m = DATE_IN_COMMENT_PATTERN.matcher(comm);
                            if (m.find())
                                e.date = m.group(1) + "-" + m.group(2) + "-" + m.group(3);
                        }
                    }
                }
                if (e.genre == null) {
                    String v = readVorbisCommentValue(commentData, "GENRE");
                    if (v.length() > 0) e.genre = v;
                }
                if (e.artist == null) {
                    String v = readVorbisCommentValue(commentData, "ARTIST");
                    if (v.length() > 0) e.artist = v;
                }
                if (e.bpm < 0) {
                    String v = readVorbisCommentValue(commentData, "BPM");
                    if (v.length() == 0) v = readVorbisCommentValue(commentData, "TEMPO");
                    if (v.length() > 0) e.bpm = parseBpmValue(v);
                }
                return;
            }
        } catch (Exception ignored) {
        }
    }

    private String readLyricsTagFromFlac(Uri uri) {
        String lyrics = readFlacVorbisComment(uri, "LYRICS");
        if (lyrics.length() > 0) return lyrics;
        lyrics = readFlacVorbisComment(uri, "UNSYNCEDLYRICS");
        if (lyrics.length() > 0) return lyrics;
        return readFlacVorbisComment(uri, "UNSYNCED LYRICS");
    }

    private String readFlacVorbisComment(Uri uri, String key) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return "";

            byte[] signature = new byte[4];
            if (!readFully(stream, signature, 4)) return "";
            if (signature[0] != 'f' || signature[1] != 'L' || signature[2] != 'a' || signature[3] != 'C') return "";

            boolean isLastBlock = false;
            while (!isLastBlock) {
                byte[] blockHeader = new byte[4];
                if (!readFully(stream, blockHeader, 4)) return "";

                isLastBlock = (blockHeader[0] & 0x80) != 0;
                int blockType = blockHeader[0] & 0x7F;
                int blockLength = ((blockHeader[1] & 0xFF) << 16)
                        | ((blockHeader[2] & 0xFF) << 8)
                        | (blockHeader[3] & 0xFF);
                if (blockLength < 0 || blockLength > (16 * 1024 * 1024)) return "";

                if (blockType != 4) {
                    if (!skipFully(stream, blockLength)) return "";
                    continue;
                }

                byte[] commentData = new byte[blockLength];
                if (!readFully(stream, commentData, blockLength)) return "";
                return readVorbisCommentValue(commentData, key);
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readVorbisCommentValue(byte[] data, String key) {
        if (data == null || data.length < 8) return "";
        int offset = 0;
        int vendorLength = decodeLittleEndianInt(data, offset);
        if (vendorLength < 0) return "";
        offset += 4;
        if (offset + vendorLength > data.length) return "";
        offset += vendorLength;
        if (offset + 4 > data.length) return "";

        int commentCount = decodeLittleEndianInt(data, offset);
        if (commentCount < 0) return "";
        offset += 4;
        for (int i = 0; i < commentCount; i++) {
            if (offset + 4 > data.length) return "";
            int commentLength = decodeLittleEndianInt(data, offset);
            offset += 4;
            if (commentLength < 0 || offset + commentLength > data.length) return "";

            String comment;
            try {
                comment = new String(data, offset, commentLength, "UTF-8");
            } catch (Exception ignored) {
                comment = "";
            }
            offset += commentLength;

            int equals = comment.indexOf('=');
            if (equals <= 0) continue;
            String commentKey = comment.substring(0, equals);
            if (commentKey.equalsIgnoreCase(key)) return comment.substring(equals + 1).trim();
        }
        return "";
    }

    private int decodeLittleEndianInt(byte[] data, int offset) {
        if (offset + 3 >= data.length) return -1;
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }


    private void fillSortTagsFromMp4(Uri uri, TagEntry e) {
        if (uri == null || (e.date != null && e.genre != null && e.bpm >= 0)) return;
        String[] bpmCandidates = new String[]{"", ""};
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return;
            readMp4SortTagValuesFromAtoms(stream, Long.MAX_VALUE, false, e, bpmCandidates);
        } catch (Exception ignored) {
        }
        if (e.bpm < 0) {
            String bpm = bpmCandidates[0].length() > 0 ? bpmCandidates[0] : bpmCandidates[1];
            if (bpm.length() > 0) e.bpm = parseBpmValue(bpm);
        }
        if (e.date == null || (e.date.length() > 0 && e.date.length() < 5)) {
            String comm = readMp4TagValue(uri, 0xA9636D74);
            if (!comm.isEmpty()) {
                Matcher m = DATE_IN_COMMENT_PATTERN.matcher(comm);
                if (m.find())
                    e.date = m.group(1) + "-" + m.group(2) + "-" + m.group(3);
            }
        }
    }

    private String readLyricsTagFromMp4(Uri uri) {
        String lyrics = readMp4TagValue(uri, 0xA96C7972);
        if (lyrics.length() > 0) return lyrics;
        return readMp4FreeformTagValue(uri, "com.apple.iTunes", "LYRICS");
    }

    private float readReplayGainFromId3(Uri uri) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return -1f;

            byte[] header = new byte[10];
            if (!readFully(stream, header, 10)) return -1f;
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return -1f;

            int majorVersion = header[3] & 0xFF;
            if (majorVersion < 2 || majorVersion > 4) return -1f;

            int tagSize = decodeSyncSafeInt(header, 6);
            if (tagSize <= 0 || tagSize > (2 * 1024 * 1024)) return -1f;

            byte[] tagData = new byte[tagSize];
            if (!readFully(stream, tagData, tagSize)) return -1f;

            int startOffset = 0;
            boolean hasExtendedHeader = (header[5] & 0x40) != 0;
            if (hasExtendedHeader && tagData.length >= 4) {
                startOffset = majorVersion == 4
                        ? decodeSyncSafeInt(tagData, 0)
                        : decodeInt(tagData, 0);
                if (startOffset < 0 || startOffset >= tagData.length) return -1f;
            }

            for (int offset = startOffset; offset + 10 <= tagData.length; ) {
                String frameId = new String(tagData, offset, 4, "ISO-8859-1");
                if (isZeroFrameId(frameId)) break;

                int frameSize = majorVersion == 4
                        ? decodeSyncSafeInt(tagData, offset + 4)
                        : decodeInt(tagData, offset + 4);
                if (frameSize <= 0 || offset + 10 + frameSize > tagData.length) break;

                if ("TXXX".equals(frameId)) {
                    String gain = decodeId3UserText(tagData, offset + 10, frameSize, "REPLAYGAIN_TRACK_GAIN");
                    if (gain.length() == 0) {
                        gain = decodeId3UserText(tagData, offset + 10, frameSize, "replaygain_track_gain");
                    }
                    float parsed = parseReplayGainLinear(gain);
                    if (parsed > 0f) return parsed;
                }

                offset += 10 + frameSize;
            }
            return -1f;
        } catch (Exception ignored) {
            return -1f;
        }
    }

    private float readReplayGainFromFlac(Uri uri) {
        String gain = readFlacVorbisComment(uri, "REPLAYGAIN_TRACK_GAIN");
        return parseReplayGainLinear(gain);
    }

    private float readReplayGainFromMp4(Uri uri) {
        String gain = readMp4FreeformTagValue(uri, "com.apple.iTunes", "replaygain_track_gain");
        if (gain.length() == 0) gain = readMp4FreeformTagValue(uri, "org.hydrogenaudio.replaygain", "track_gain");
        if (gain.length() == 0) gain = readMp4FreeformTagValue(uri, "org.hydrogenaudio.replaygain", "REPLAYGAIN_TRACK_GAIN");
        if (gain.length() == 0) gain = readMp4FreeformTagValue(uri, "com.apple.iTunes", "REPLAYGAIN_TRACK_GAIN");
        return parseReplayGainLinear(gain);
    }

    private String readMp4FreeformTagValue(Uri uri, String targetMean, String targetName) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return "";
            return readMp4FreeformTagValueFromAtoms(stream, Long.MAX_VALUE, false, false, targetMean, targetName);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readMp4TagValue(Uri uri, int targetAtomType) {
        try (InputStream stream = contentResolver.openInputStream(uri)) {
            if (stream == null) return "";
            return readMp4TagValueFromAtoms(stream, Long.MAX_VALUE, targetAtomType, false, false);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readMp4TagValueFromAtoms(InputStream stream,
                                            long maxBytes,
                                            int targetAtomType,
                                            boolean inMeta,
                                            boolean inIlst) throws java.io.IOException {
        long consumed = 0;
        while (consumed + 8 <= maxBytes) {
            byte[] header = new byte[8];
            if (!readFully(stream, header, 8)) return "";
            consumed += 8;

            long atomSize = decodeUnsignedInt(header, 0);
            int atomType = decodeAtomType(header, 4);
            long headerSize = 8;
            if (atomSize == 1) {
                byte[] ext = new byte[8];
                if (!readFully(stream, ext, 8)) return "";
                atomSize = decodeLong(ext, 0);
                consumed += 8;
                headerSize = 16;
            } else if (atomSize == 0) {
                atomSize = maxBytes - consumed + 8;
            }

            if (atomSize < headerSize || atomSize > (maxBytes - consumed + headerSize)) return "";
            long payloadSize = atomSize - headerSize;

            if (inIlst && atomType == targetAtomType) {
                String value = readMp4IlstDataAtom(stream, payloadSize, targetAtomType);
                if (value.length() > 0) return value;
            } else if (isMp4ContainerAtom(atomType)) {
                if (atomType == 0x6D657461) {
                    if (payloadSize < 4) {
                        if (!skipFully(stream, payloadSize)) return "";
                    } else {
                        if (!skipFully(stream, 4)) return "";
                        String value = readMp4TagValueFromAtoms(stream, payloadSize - 4, targetAtomType, true, false);
                        if (value.length() > 0) return value;
                    }
                } else {
                    String value = readMp4TagValueFromAtoms(stream, payloadSize, targetAtomType, inMeta, atomType == 0x696C7374);
                    if (value.length() > 0) return value;
                }
            } else {
                if (!skipFully(stream, payloadSize)) return "";
            }

            consumed += payloadSize;
            if (inMeta && atomType == 0x696C7374 && targetAtomType == 0x746D706F) {
                // Keep scanning inside this meta atom, other fields may follow.
            }
        }
        return "";
    }

    private void readMp4SortTagValuesFromAtoms(InputStream stream,
                                               long maxBytes,
                                               boolean inIlst,
                                               TagEntry e,
                                               String[] bpmCandidates) throws java.io.IOException {
        long consumed = 0;
        while (consumed + 8 <= maxBytes) {
            byte[] header = new byte[8];
            if (!readFully(stream, header, 8)) return;
            consumed += 8;

            long atomSize = decodeUnsignedInt(header, 0);
            int atomType = decodeAtomType(header, 4);
            long headerSize = 8;
            if (atomSize == 1) {
                byte[] ext = new byte[8];
                if (!readFully(stream, ext, 8)) return;
                atomSize = decodeLong(ext, 0);
                consumed += 8;
                headerSize = 16;
            } else if (atomSize == 0) {
                atomSize = maxBytes - consumed + 8;
            }

            if (atomSize < headerSize || atomSize > (maxBytes - consumed + headerSize)) return;
            long payloadSize = atomSize - headerSize;

            if (inIlst && atomType == 0xA9646179 && e.date == null) {
                String date = readMp4IlstDataAtom(stream, payloadSize, atomType);
                if (date.length() > 0) e.date = normalizeDateValue(date);
            } else if (inIlst && atomType == 0xA967656E && e.genre == null) {
                String genre = readMp4IlstDataAtom(stream, payloadSize, atomType);
                if (genre.length() > 0) e.genre = genre;
            } else if (inIlst && atomType == 0xA9415254 && e.artist == null) {
                String artist = readMp4IlstDataAtom(stream, payloadSize, atomType);
                if (artist.length() > 0) e.artist = artist;
            } else if (inIlst && atomType == 0x746D706F && bpmCandidates[0].length() == 0) {
                String bpm = readMp4IlstDataAtom(stream, payloadSize, atomType);
                if (bpm.length() > 0) bpmCandidates[0] = bpm;
            } else if (inIlst && atomType == 0x2D2D2D2D && bpmCandidates[1].length() == 0) {
                String bpm = readMp4FreeformIlstItem(stream, payloadSize, "com.apple.iTunes", "BPM");
                if (bpm.length() > 0) bpmCandidates[1] = bpm;
            } else if (isMp4ContainerAtom(atomType)) {
                if (atomType == 0x6D657461) {
                    if (payloadSize < 4) {
                        if (!skipFully(stream, payloadSize)) return;
                    } else {
                        if (!skipFully(stream, 4)) return;
                        readMp4SortTagValuesFromAtoms(stream, payloadSize - 4, false, e, bpmCandidates);
                    }
                } else {
                    readMp4SortTagValuesFromAtoms(stream, payloadSize, atomType == 0x696C7374, e, bpmCandidates);
                }
            } else {
                if (!skipFully(stream, payloadSize)) return;
            }

            consumed += payloadSize;
            if (e.date != null
                    && e.genre != null
                    && e.artist != null
                    && (e.bpm >= 0 || bpmCandidates[0].length() > 0 || bpmCandidates[1].length() > 0)) {
                long remaining = maxBytes - consumed;
                if (remaining > 0) skipFully(stream, remaining);
                return;
            }
        }
    }

    private String readMp4FreeformTagValueFromAtoms(InputStream stream,
                                                    long maxBytes,
                                                    boolean inMeta,
                                                    boolean inIlst,
                                                    String targetMean,
                                                    String targetName) throws java.io.IOException {
        long consumed = 0;
        while (consumed + 8 <= maxBytes) {
            byte[] header = new byte[8];
            if (!readFully(stream, header, 8)) return "";
            consumed += 8;

            long atomSize = decodeUnsignedInt(header, 0);
            int atomType = decodeAtomType(header, 4);
            long headerSize = 8;
            if (atomSize == 1) {
                byte[] ext = new byte[8];
                if (!readFully(stream, ext, 8)) return "";
                atomSize = decodeLong(ext, 0);
                consumed += 8;
                headerSize = 16;
            } else if (atomSize == 0) {
                atomSize = maxBytes - consumed + 8;
            }

            if (atomSize < headerSize || atomSize > (maxBytes - consumed + headerSize)) return "";
            long payloadSize = atomSize - headerSize;

            if (inIlst && atomType == 0x2D2D2D2D) {
                String value = readMp4FreeformIlstItem(stream, payloadSize, targetMean, targetName);
                if (value.length() > 0) return value;
            } else if (isMp4ContainerAtom(atomType)) {
                if (atomType == 0x6D657461) {
                    if (payloadSize < 4) {
                        if (!skipFully(stream, payloadSize)) return "";
                    } else {
                        if (!skipFully(stream, 4)) return "";
                        String value = readMp4FreeformTagValueFromAtoms(stream, payloadSize - 4, true, false, targetMean, targetName);
                        if (value.length() > 0) return value;
                    }
                } else {
                    String value = readMp4FreeformTagValueFromAtoms(stream, payloadSize, inMeta, atomType == 0x696C7374, targetMean, targetName);
                    if (value.length() > 0) return value;
                }
            } else {
                if (!skipFully(stream, payloadSize)) return "";
            }

            consumed += payloadSize;
        }
        return "";
    }

    private String readMp4FreeformIlstItem(InputStream stream,
                                           long itemPayloadSize,
                                           String targetMean,
                                           String targetName) throws java.io.IOException {
        long consumed = 0;
        String mean = "";
        String name = "";
        String data = "";

        while (consumed + 8 <= itemPayloadSize) {
            byte[] header = new byte[8];
            if (!readFully(stream, header, 8)) return "";
            consumed += 8;

            long atomSize = decodeUnsignedInt(header, 0);
            int atomType = decodeAtomType(header, 4);
            long headerSize = 8;
            if (atomSize == 1) {
                byte[] ext = new byte[8];
                if (!readFully(stream, ext, 8)) return "";
                atomSize = decodeLong(ext, 0);
                consumed += 8;
                headerSize = 16;
            }

            if (atomSize < headerSize || atomSize > (itemPayloadSize - consumed + headerSize)) return "";
            long payloadSize = atomSize - headerSize;

            if (payloadSize > Integer.MAX_VALUE) {
                if (!skipFully(stream, payloadSize)) return "";
                consumed += payloadSize;
                continue;
            }

            if (atomType == 0x6D65616E || atomType == 0x6E616D65) {
                if (payloadSize < 4) {
                    if (!skipFully(stream, payloadSize)) return "";
                } else {
                    byte[] bytes = new byte[(int) payloadSize];
                    if (!readFully(stream, bytes, (int) payloadSize)) return "";
                    String decoded = decodeMp4TextRange(bytes, 4, bytes.length - 4);
                    if (atomType == 0x6D65616E) mean = decoded; else name = decoded;
                }
            } else if (atomType == 0x64617461) {
                if (payloadSize < 8) {
                    if (!skipFully(stream, payloadSize)) return "";
                } else {
                    byte[] bytes = new byte[(int) payloadSize];
                    if (!readFully(stream, bytes, (int) payloadSize)) return "";
                    data = decodeMp4TextRange(bytes, 8, bytes.length - 8);
                }
            } else {
                if (!skipFully(stream, payloadSize)) return "";
            }

            consumed += payloadSize;
        }

        long remaining = itemPayloadSize - consumed;
        if (remaining > 0) skipFully(stream, remaining);

        if (mean.equals(targetMean) && name.equalsIgnoreCase(targetName)) return data.trim();
        return "";
    }

    private String readMp4IlstDataAtom(InputStream stream, long itemPayloadSize, int itemAtomType)
            throws java.io.IOException {
        long consumed = 0;
        while (consumed + 8 <= itemPayloadSize) {
            byte[] header = new byte[8];
            if (!readFully(stream, header, 8)) return "";
            consumed += 8;

            long atomSize = decodeUnsignedInt(header, 0);
            int atomType = decodeAtomType(header, 4);
            long headerSize = 8;
            if (atomSize == 1) {
                byte[] ext = new byte[8];
                if (!readFully(stream, ext, 8)) return "";
                atomSize = decodeLong(ext, 0);
                consumed += 8;
                headerSize = 16;
            }

            if (atomSize < headerSize || atomSize > (itemPayloadSize - consumed + headerSize)) return "";
            long payloadSize = atomSize - headerSize;

            if (atomType == 0x64617461) {
                if (payloadSize < 8) {
                    if (!skipFully(stream, payloadSize)) return "";
                } else {
                    byte[] info = new byte[8];
                    if (!readFully(stream, info, 8)) return "";
                    long dataSize = payloadSize - 8;
                    if (dataSize < 0 || dataSize > (2 * 1024 * 1024)) {
                        if (!skipFully(stream, dataSize)) return "";
                    } else {
                        byte[] value = new byte[(int) dataSize];
                        if (!readFully(stream, value, (int) dataSize)) return "";
                        if (itemAtomType == 0x746D706F) return decodeMp4Tempo(value);
                        return decodeMp4Text(value);
                    }
                }
            } else {
                if (!skipFully(stream, payloadSize)) return "";
            }

            consumed += payloadSize;
        }

        long remaining = itemPayloadSize - consumed;
        if (remaining > 0) skipFully(stream, remaining);
        return "";
    }

    private boolean isMp4ContainerAtom(int atomType) {
        return atomType == 0x6D6F6F76  // moov
                || atomType == 0x75647461 // udta
                || atomType == 0x6D657461 // meta
                || atomType == 0x696C7374 // ilst
                || atomType == 0x7472616B // trak
                || atomType == 0x6D646961 // mdia
                || atomType == 0x6D696E66 // minf
                || atomType == 0x7374626C // stbl
                || atomType == 0x65647473 // edts
                || atomType == 0x6D766578; // mvex
    }

    private long decodeUnsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (long) (data[offset + 3] & 0xFF);
    }

    private long decodeLong(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 56)
                | ((long) (data[offset + 1] & 0xFF) << 48)
                | ((long) (data[offset + 2] & 0xFF) << 40)
                | ((long) (data[offset + 3] & 0xFF) << 32)
                | ((long) (data[offset + 4] & 0xFF) << 24)
                | ((long) (data[offset + 5] & 0xFF) << 16)
                | ((long) (data[offset + 6] & 0xFF) << 8)
                | (long) (data[offset + 7] & 0xFF);
    }

    private int decodeAtomType(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private String decodeMp4Text(byte[] data) {
        return decodeMp4TextRange(data, 0, data != null ? data.length : 0);
    }

    private String decodeMp4TextRange(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length <= 0 || offset + length > data.length) return "";
        try {
            String value = new String(data, offset, length, "UTF-8").trim();
            if (value.length() > 0) return value;
            return new String(data, offset, length, "ISO-8859-1").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String decodeMp4Tempo(byte[] data) {
        if (data == null || data.length == 0) return "";
        if (data.length == 1) return Integer.toString(data[0] & 0xFF);
        if (data.length >= 2) {
            int value = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            if (value > 0) return Integer.toString(value);
        }
        return "";
    }

    private boolean skipFully(InputStream stream, long bytesToSkip) throws java.io.IOException {
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

    private boolean readFully(InputStream stream, byte[] buffer, int size) throws java.io.IOException {
        int readTotal = 0;
        while (readTotal < size) {
            int read = stream.read(buffer, readTotal, size - readTotal);
            if (read < 0) return false;
            readTotal += read;
        }
        return true;
    }

    private int decodeSyncSafeInt(byte[] data, int offset) {
        if (offset + 3 >= data.length) return -1;
        return ((data[offset] & 0x7F) << 21)
                | ((data[offset + 1] & 0x7F) << 14)
                | ((data[offset + 2] & 0x7F) << 7)
                | (data[offset + 3] & 0x7F);
    }

    private int decodeInt(byte[] data, int offset) {
        if (offset + 3 >= data.length) return -1;
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private boolean isZeroFrameId(String frameId) {
        for (int i = 0; i < frameId.length(); i++) {
            if (frameId.charAt(i) != 0) return false;
        }
        return true;
    }

    private String decodeId3Text(byte[] data, int offset, int length) {
        if (length <= 1 || offset + length > data.length) return "";

        int encoding = data[offset] & 0xFF;
        String charset;
        if (encoding == 1) charset = "UTF-16";
        else if (encoding == 2) charset = "UTF-16BE";
        else if (encoding == 3) charset = "UTF-8";
        else charset = "ISO-8859-1";

        try {
            String value = new String(data, offset + 1, length - 1, charset);
            int nullTerminator = value.indexOf(' ');
            if (nullTerminator >= 0) value = value.substring(0, nullTerminator);
            return value.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String decodeId3UserText(byte[] data,
                                     int offset,
                                     int length,
                                     String targetDescription) {
        if (data == null || targetDescription == null || length <= 1 || offset + length > data.length) return "";

        int encoding = data[offset] & 0xFF;
        String charset;
        if (encoding == 1) charset = "UTF-16";
        else if (encoding == 2) charset = "UTF-16BE";
        else if (encoding == 3) charset = "UTF-8";
        else charset = "ISO-8859-1";

        try {
            String decoded = new String(data, offset + 1, length - 1, charset);
            int nullTerminator = decoded.indexOf('\u0000');
            if (nullTerminator <= 0) return "";
            String description = decoded.substring(0, nullTerminator).trim();
            if (!description.equalsIgnoreCase(targetDescription)) return "";
            return decoded.substring(nullTerminator + 1).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeDateValue(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() == 0) return "";
        int tPos = trimmed.indexOf('T');
        if (tPos > 0) trimmed = trimmed.substring(0, tPos);
        return trimmed;
    }

    private String normalizeGenreValue(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() == 0) return "";
        // ID3 TCON may contain numeric code in parentheses; keep user-friendly values only.
        if (trimmed.matches("^\\(\\d+\\)$")) return "";
        return trimmed;
    }

    private String normalizeLyricsValue(String value) {
        if (value == null) return "";
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String decodeUsltText(byte[] data, int offset, int length) {
        if (data == null || length <= 4 || offset < 0 || offset + length > data.length) return "";

        int encoding = data[offset] & 0xFF;
        String charset;
        int terminatorLength;
        if (encoding == 1) { charset = "UTF-16"; terminatorLength = 2; }
        else if (encoding == 2) { charset = "UTF-16BE"; terminatorLength = 2; }
        else if (encoding == 3) { charset = "UTF-8"; terminatorLength = 1; }
        else { charset = "ISO-8859-1"; terminatorLength = 1; }

        int start = offset + 4;
        int end = offset + length;
        if (start >= end) return "";

        int descriptionEnd = findTextTerminator(data, start, end, terminatorLength);
        int lyricsStart = descriptionEnd + terminatorLength;
        if (lyricsStart >= end) return "";

        try {
            return new String(data, lyricsStart, end - lyricsStart, charset).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private int findTextTerminator(byte[] data, int start, int end, int terminatorLength) {
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

    private int parseBpmValue(String value) {
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

    private float parseReplayGainLinear(String value) {
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
