package com.shaforostoff.livequeueplayer;

import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless track-matching logic used when resolving incoming remote track
 * requests against the local library (REMOTE_RECEIVE mode). Every method is a
 * pure utility operating on its arguments — no Android Activity state — so the
 * matching strategy can be reasoned about and tested in isolation.
 *
 * Names and tags arrive over the wire in whatever Unicode form the sending
 * device's library uses, which need not match this device's, so every
 * comparison here is form-insensitive (see {@link TextNormalizer}).
 */
final class TrackMatcher {

    private TrackMatcher() {}

    private static final float FUZZY_TITLE_THRESHOLD = 0.8f;

    /** Outcome of a successful tag-cache lookup. */
    static final class TagMatch {
        final Uri uri;
        final boolean exact;   // true = exact title match, false = fuzzy title match
        final String label;    // human-readable "title · artist · date"

        TagMatch(Uri uri, boolean exact, String label) {
            this.uri = uri;
            this.exact = exact;
            this.label = label;
        }
    }

    /**
     * Finds the best library entry whose tags match the requested title/artist.
     * Pass 1 prefers an exact (case-insensitive) title match, breaking ties by
     * the best fuzzy artist score. Pass 2 falls back to fuzzy title matching,
     * scoring title and artist together. Returns {@code null} when nothing
     * clears the fuzzy threshold.
     */
    static TagMatch findInTagCacheByTitleAndArtist(String title, String artist,
            List<Map.Entry<String, MetadataExtractor.TagEntry>> snapshot) {
        // Pass 1: exact title match (case-insensitive), pick candidate with best fuzzy artist score
        Uri bestExact = null;
        MetadataExtractor.TagEntry bestExactTag = null;
        float bestExactArtist = -1f;
        for (Map.Entry<String, MetadataExtractor.TagEntry> e : snapshot) {
            MetadataExtractor.TagEntry tag = e.getValue();
            if (tag.title == null || !TextNormalizer.equalsIgnoreCase(tag.title, title)) continue;
            float a = FuzzySearch.matchFuzzy(tag.artist, artist);
            if (bestExact == null || a > bestExactArtist) {
                bestExact = Uri.parse(MetadataExtractor.keyToUri(e.getKey()));
                bestExactTag = tag;
                bestExactArtist = a;
            }
        }
        if (bestExact != null) {
            return new TagMatch(bestExact, true, formatTagLabel(bestExactTag));
        }

        // Pass 2: fuzzy title match, pick candidate with highest combined score
        Uri bestFuzzy = null;
        MetadataExtractor.TagEntry bestFuzzyTag = null;
        float bestScore = 0f;
        for (Map.Entry<String, MetadataExtractor.TagEntry> e : snapshot) {
            MetadataExtractor.TagEntry tag = e.getValue();
            if (tag.title == null) continue;
            float t = FuzzySearch.matchFuzzy(tag.title, title);
            if (t < FUZZY_TITLE_THRESHOLD) continue;
            float combined = t * 0.7f + FuzzySearch.matchFuzzy(tag.artist, artist) * 0.3f;
            if (combined > bestScore) {
                bestScore = combined;
                bestFuzzy = Uri.parse(MetadataExtractor.keyToUri(e.getKey()));
                bestFuzzyTag = tag;
            }
        }
        if (bestFuzzy != null) {
            return new TagMatch(bestFuzzy, false, formatTagLabel(bestFuzzyTag));
        }
        return null;
    }

    static String formatTagLabel(MetadataExtractor.TagEntry tag) {
        StringBuilder sb = new StringBuilder(tag.title);
        if (tag.artist != null && !tag.artist.isEmpty()) sb.append(" · ").append(tag.artist);
        if (tag.date   != null && !tag.date.isEmpty())   sb.append(" · ").append(tag.date);
        return sb.toString();
    }

    /** Returns the immediate parent folder name from a '/'-separated path, or "" if none. */
    static String parentFolderFromPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "";
        int prevSlash = path.lastIndexOf('/', lastSlash - 1);
        return prevSlash >= 0 ? path.substring(prevSlash + 1, lastSlash)
                              : path.substring(0, lastSlash);
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Lookup key under which two names are equal exactly when {@link TextNormalizer#equalsIgnoreCase}
     * calls them equal: the composed form, folded per char the way {@link String#equalsIgnoreCase}
     * compares (lower case of the upper case).
     */
    private static String matchKey(String name) {
        String composed = TextNormalizer.compose(name);
        char[] folded = new char[composed.length()];
        for (int i = 0; i < folded.length; i++) {
            folded[i] = Character.toLowerCase(Character.toUpperCase(composed.charAt(i)));
        }
        return new String(folded);
    }

    /**
     * Matches library files, one at a time via {@link #match}, against a batch of requests by file
     * name. Shared by the SAF tree walk, the file walk and the in-memory tag-cache lookup. A match
     * whose parent folder equals the request's parent folder outranks a plain name match, which
     * outranks a match on the name with the extension stripped; within a rank the first file wins.
     * {@link #result} gives one URI per request (null where unmatched).
     *
     * <p>The requests are indexed by name up front, so each library file costs two map lookups no
     * matter how many requests are pending — a library of tens of thousands of files is walked
     * against every request in the batch.
     */
    static final class Accumulator {
        private final String[] hints;
        private final Uri[] hintMatches;
        private final Uri[] nameMatches;
        private final Uri[] extMatches;
        private final Map<String, List<Integer>> byName = new HashMap<>();
        private final Map<String, List<Integer>> byStem = new HashMap<>();

        Accumulator(List<BluetoothQueueBridge.TrackRequest> requests) {
            int n = requests.size();
            hints = new String[n];
            hintMatches = new Uri[n];
            nameMatches = new Uri[n];
            extMatches  = new Uri[n];
            for (int i = 0; i < n; i++) {
                BluetoothQueueBridge.TrackRequest request = requests.get(i);
                hints[i] = parentFolderFromPath(request.path);
                index(byName, matchKey(request.file), i);
                index(byStem, matchKey(stripExtension(request.file)), i);
            }
        }

        private static void index(Map<String, List<Integer>> map, String key, int request) {
            List<Integer> list = map.get(key);
            if (list == null) map.put(key, list = new ArrayList<>(1));
            list.add(request);
        }

        void match(String childName, String dirName, Uri childUri) {
            List<Integer> named = byName.get(matchKey(childName));
            if (named != null) {
                for (int i : named) {
                    if (hintMatches[i] != null) continue;
                    String hint = hints[i];
                    if (hint.length() > 0 && TextNormalizer.equalsIgnoreCase(dirName, hint)) hintMatches[i] = childUri;
                    else if (nameMatches[i] == null) nameMatches[i] = childUri;
                }
            }
            List<Integer> stemmed = byStem.get(matchKey(stripExtension(childName)));
            if (stemmed != null) {
                for (int i : stemmed) {
                    if (hintMatches[i] == null && nameMatches[i] == null && extMatches[i] == null) {
                        extMatches[i] = childUri;
                    }
                }
            }
        }

        List<Uri> result() {
            List<Uri> results = new ArrayList<>(hintMatches.length);
            for (int i = 0; i < hintMatches.length; i++) {
                Uri r = hintMatches[i];
                if (r == null) r = nameMatches[i];
                if (r == null) r = extMatches[i];
                results.add(r);
            }
            return results;
        }
    }
}
