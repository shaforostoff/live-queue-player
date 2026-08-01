package com.shaforostoff.livequeueplayer;

import java.text.Normalizer;

/**
 * Unicode-form-insensitive string helpers.
 *
 * Spanish ñ has two legal encodings: precomposed (U+00F1) and decomposed
 * ("n" + U+0303 combining tilde) — and the same holds for á/é/í/ó/ú/ü. Android
 * keyboards, Windows and Linux hand us the precomposed form; filenames written
 * on macOS, M3U lines exported by iTunes, and tags copied from either routinely
 * carry the decomposed one. The two are different char sequences, so
 * {@code equals}, {@code compareTo}, {@code contains} and {@code regionMatches}
 * all treat "Niño" and "Niño" as unrelated text. Every comparison of library
 * text — filenames, tags, playlist lines, search queries, names arriving from a
 * remote device — goes through here so both spellings behave as one name.
 *
 * Every method has an ASCII fast path: strings with no char above 0x7F (the
 * vast majority) take the plain String route, normalising nothing and
 * allocating nothing.
 */
final class TextNormalizer {

    private TextNormalizer() {}

    /** True when s has no char above 0x7F, i.e. no alternative encoding to consider. */
    private static boolean isAscii(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    /**
     * Precomposed (NFC) form of s — ñ as one char. Returns s itself when it is
     * ASCII or already composed, so the common case allocates nothing.
     */
    static String compose(String s) {
        if (s == null || isAscii(s)) return s;
        if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) return s;
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** True when c is a combining mark, i.e. the tilde/accent half of a decomposed letter. */
    static boolean isCombiningMark(char c) {
        if (c < 0x0300) return false;   // no combining marks below the combining-diacritics block
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /** Form-insensitive, case-sensitive equality. */
    static boolean equals(String a, String b) {
        if (a == null || b == null) return a == b;
        if (a.equals(b)) return true;
        if (isAscii(a) && isAscii(b)) return false;
        return compose(a).equals(compose(b));
    }

    /** Form-insensitive, case-insensitive equality. */
    static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) return a == b;
        if (a.equalsIgnoreCase(b)) return true;
        if (isAscii(a) && isAscii(b)) return false;
        return compose(a).equalsIgnoreCase(compose(b));
    }

    /** Form-insensitive, case-insensitive {@code value.contains(query)}. */
    static boolean containsIgnoreCase(String value, String query) {
        if (value == null || query == null) return false;
        if (containsIgnoreCaseExact(value, query)) return true;
        if (isAscii(value) && isAscii(query)) return false;
        return containsIgnoreCaseExact(compose(value), compose(query));
    }

    /** {@code value.contains(query)}, case-folded per char so nothing is allocated. */
    private static boolean containsIgnoreCaseExact(String value, String query) {
        int queryLen = query.length();
        if (queryLen == 0) return true;
        int max = value.length() - queryLen;
        for (int i = 0; i <= max; i++) {
            // regionMatches(ignoreCase=true, ...) folds case per char, allocating nothing.
            if (value.regionMatches(true, i, query, 0, queryLen)) return true;
        }
        return false;
    }

    /**
     * Form-insensitive, case-insensitive ordering — equivalent to comparing the
     * composed forms, so the two spellings of a name sort as one (rather than a
     * decomposed "Niño" landing among the plain n's and a precomposed one after
     * the z's).
     */
    static int compareIgnoreCase(String a, String b) {
        int c = a.compareToIgnoreCase(b);
        if (c == 0 || (isAscii(a) && isAscii(b))) return c;
        return compose(a).compareToIgnoreCase(compose(b));
    }

    /**
     * The spellings to try when probing a filesystem or SAF provider for a name built from text of
     * unknown origin (a playlist line, a persisted relative path, a name from a remote device):
     * the string as given first — so nothing changes for the common case — then its other canonical
     * form. Existence lookups are byte-exact, so a name that came in decomposed will not find a
     * precomposed file on disk without this. Single-element array for ASCII text.
     */
    static String[] variants(String s) {
        if (s == null || isAscii(s)) return new String[]{s};
        String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        if (nfc.equals(nfd)) return new String[]{s};        // nothing composes or decomposes
        if (s.equals(nfc)) return new String[]{s, nfd};
        if (s.equals(nfd)) return new String[]{s, nfc};
        return new String[]{s, nfc, nfd};                  // s in neither canonical form
    }
}
