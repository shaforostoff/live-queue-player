package com.shaforostoff.livequeueplayer;

/**
 * Fuzzy filename search using bigram (Sørensen-Dice) similarity.
 *
 * containsFuzzy(filename, query) splits both strings into words (runs of
 * letter/digit characters, including non-ASCII letters such as ñ, á, …),
 * then for each query word checks whether any word in the filename is similar
 * enough:
 *   - words shorter than 4 chars  → exact case-insensitive match required
 *   - words 4+ chars              → bigram Dice coefficient >= 0.8
 *
 * Returns the fraction of query words that matched (0.0–1.0).
 *
 * Avoids substring allocations by working with (String, start, end) slices
 * throughout. The two small scratch buffers are allocated once per top-level
 * call and reused for every word-pair comparison.
 */
public final class FuzzySearch {

    // a–z (0–25) + 0–9 (26–35)
    private static final int ALPHA_SIZE      = 36;
    private static final int BIGRAM_BUF_SIZE = ALPHA_SIZE * ALPHA_SIZE; // 1 296
    // Upper bound on distinct bigrams we'll dirty per word; handles words up
    // to ~128 chars before falling back to a full clear.
    private static final int MAX_DIRTY       = 128;
    private static final float SIMILARITY_THRESHOLD = 0.75f;
    private static final int SHORT_WORD_MAX  = 3; // < 4 chars → direct compare

    private FuzzySearch() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Symmetric bigram Dice similarity after normalising both strings:
     * strips parenthesised substrings and punctuation, then applies
     * diceSimilarity directly to the cleaned strings.
     *
     * @return Dice coefficient in [0.0, 1.0]; 0.0 if either string is null/empty.
     */
    public static float matchFuzzy(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return 0.0f;
        String pa = preprocess(a);
        String pb = preprocess(b);
        if (pa.isEmpty() || pb.isEmpty()) return 0.0f;
        int[] bigramBuf = new int[BIGRAM_BUF_SIZE];
        int[] dirtyBuf  = new int[MAX_DIRTY];
        return diceSimilarity(pa, 0, pa.length(), pb, 0, pb.length(), bigramBuf, dirtyBuf);
    }

    /**
     * @return fraction of query words found in filename, in [0.0, 1.0].
     *         Returns 1.0 for an empty/null query; 0.0 for an empty/null filename.
     */
    public static float containsFuzzy(String filename, String query) {
        if (query    == null || query.isEmpty())    return 1.0f;
        if (filename == null || filename.isEmpty()) return 0.0f;

        // One allocation per call; shared across all word comparisons below.
        int[] bigramBuf = new int[BIGRAM_BUF_SIZE];
        int[] dirtyBuf  = new int[MAX_DIRTY];

        int totalWords   = 0;
        int matchedWords = 0;

        final int qLen = query.length();
        int wStart = -1;

        for (int i = 0; i <= qLen; i++) {
            final boolean wordChar = i < qLen && isWordChar(query.charAt(i));
            if (wordChar) {
                if (wStart < 0) wStart = i;
            } else if (wStart >= 0) {
                totalWords++;
                if (wordExistsIn(filename, query, wStart, i, bigramBuf, dirtyBuf))
                    matchedWords++;
                wStart = -1;
            }
        }

        return totalWords == 0 ? 1.0f : (float) matchedWords / totalWords;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Returns true if the word needle[nStart,nEnd) matches any word in text. */
    private static boolean wordExistsIn(String text, String needle,
                                        int nStart, int nEnd,
                                        int[] bigramBuf, int[] dirtyBuf) {
        final int tLen = text.length();
        int wStart = -1;

        for (int i = 0; i <= tLen; i++) {
            final boolean wordChar = i < tLen && isWordChar(text.charAt(i));
            if (wordChar) {
                if (wStart < 0) wStart = i;
            } else if (wStart >= 0) {
                if (wordsSimilar(text, wStart, i, needle, nStart, nEnd, bigramBuf, dirtyBuf))
                    return true;
                wStart = -1;
            }
        }
        return false;
    }

    /**
     * Compares word slice a[aStart,aEnd) against b[bStart,bEnd).
     * Short words (< 4 chars) use direct case-insensitive equality;
     * longer words use bigram Dice similarity >= SIMILARITY_THRESHOLD.
     */
    private static boolean wordsSimilar(String a, int aStart, int aEnd,
                                        String b, int bStart, int bEnd,
                                        int[] bigramBuf, int[] dirtyBuf) {
        final int bLen = bEnd - bStart;
        if (bLen <= SHORT_WORD_MAX) {
            if (aEnd - aStart != bLen) return false;
            for (int i = 0; i < bLen; i++) {
                if (Character.toLowerCase(a.charAt(aStart + i))
                        != Character.toLowerCase(b.charAt(bStart + i)))
                    return false;
            }
            return true;
        }
        return diceSimilarity(a, aStart, aEnd, b, bStart, bEnd, bigramBuf, dirtyBuf)
               >= SIMILARITY_THRESHOLD;
    }

    /**
     * Sørensen-Dice coefficient on character bigrams (ASCII alphanumeric only).
     * Non-alphanumeric characters are skipped; their positions don't contribute
     * to either bigram count.
     *
     * Uses bigramBuf as a count table and dirtyBuf to track which slots were
     * written, so we restore the buffer to all-zeros without a full clear.
     */
    private static float diceSimilarity(String a, int aStart, int aEnd,
                                        String b, int bStart, int bEnd,
                                        int[] bigramBuf, int[] dirtyBuf) {
        int dirtyCount  = 0;
        int bBigramCount = 0;
        boolean overflowed = false;

        // Build bigram frequency table for b.
        for (int i = bStart; i < bEnd - 1; i++) {
            final int c1 = charIdx(b.charAt(i));
            final int c2 = charIdx(b.charAt(i + 1));
            if (c1 < 0 || c2 < 0) continue;
            bBigramCount++;
            final int idx = c1 * ALPHA_SIZE + c2;
            if (bigramBuf[idx] == 0) {
                if (dirtyCount < MAX_DIRTY) dirtyBuf[dirtyCount++] = idx;
                else overflowed = true;
            }
            bigramBuf[idx]++;
        }

        // Count bigrams shared with a.
        int aBigramCount = 0;
        int common = 0;
        for (int i = aStart; i < aEnd - 1; i++) {
            final int c1 = charIdx(a.charAt(i));
            final int c2 = charIdx(a.charAt(i + 1));
            if (c1 < 0 || c2 < 0) continue;
            aBigramCount++;
            final int idx = c1 * ALPHA_SIZE + c2;
            if (bigramBuf[idx] > 0) {
                common++;
                bigramBuf[idx]--;
            }
        }

        // Restore bigramBuf to zero.
        if (overflowed) {
            java.util.Arrays.fill(bigramBuf, 0);
        } else {
            for (int i = 0; i < dirtyCount; i++)
                bigramBuf[dirtyBuf[i]] = 0;
        }

        final int total = aBigramCount + bBigramCount;
        return total == 0 ? 0.0f : (2.0f * common) / total;
    }

    /** Maps a character to its index in the 36-symbol alphabet, or -1. */
    private static int charIdx(char c) {
        if (c >= 'A' && c <= 'Z') c = (char) (c - 'A' + 'a');
        if (c >= 'a' && c <= 'z') return c - 'a';
        if (c >= '0' && c <= '9') return 26 + (c - '0');
        // Spanish accented letters → base ASCII
        switch (c) {
            case 'á': case 'Á': return 'a' - 'a';
            case 'é': case 'É': return 'e' - 'a';
            case 'í': case 'Í': return 'i' - 'a';
            case 'ó': case 'Ó': return 'o' - 'a';
            case 'ú': case 'Ú': case 'ü': case 'Ü': return 'u' - 'a';
            case 'ñ': case 'Ñ': return 'n' - 'a';
            default:  return -1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** Removes parenthesised substrings and strips punctuation characters. */
    private static String preprocess(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int depth = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; continue; }
            if (c == ')') { if (depth > 0) depth--; continue; }
            if (depth > 0) continue;
            if (!isPunct(c)) sb.append(c);
        }
        return sb.toString().trim();
    }

    private static boolean isPunct(char c) {
        switch (c) {
            case ',': case '.': case '!': case '?': case '-': case '_':
            case ':': case ';': case '\'': case '"': case '/': case '\\':
            case '&': case '*': case '+': case '=': case '#': case '@':
            case '%': case '^': case '~': case '|':
                return true;
            default:
                return false;
        }
    }
}
