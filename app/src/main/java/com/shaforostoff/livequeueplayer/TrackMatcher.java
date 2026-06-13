package com.shaforostoff.livequeueplayer;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stateless track-matching logic used when resolving incoming remote track
 * requests against the local library (REMOTE_RECEIVE mode). Every method is a
 * pure utility operating on its arguments — no Android Activity state — so the
 * matching strategy can be reasoned about and tested in isolation.
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
            if (tag.title == null || !tag.title.equalsIgnoreCase(title)) continue;
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

    static String[] parentHints(List<BluetoothQueueBridge.TrackRequest> requests) {
        String[] hints = new String[requests.size()];
        for (int i = 0; i < hints.length; i++) hints[i] = parentFolderFromPath(requests.get(i).path);
        return hints;
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

    static void applyExtFallbackMatch(String childNoExt, Uri childUri,
            List<BluetoothQueueBridge.TrackRequest> requests, Uri[] hintMatches, Uri[] nameMatches, Uri[] extMatches) {
        for (int i = 0; i < requests.size(); i++) {
            if (hintMatches[i] != null || nameMatches[i] != null || extMatches[i] != null) continue;
            if (childNoExt.equalsIgnoreCase(stripExtension(requests.get(i).file))) {
                extMatches[i] = childUri;
            }
        }
    }

    static List<Uri> mergeMatchResults(Uri[] hintMatches, Uri[] nameMatches, Uri[] extMatches) {
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
