package com.shaforostoff.livequeueplayer;

/**
 * Fuzzy title/artist similarity using bigram (Sørensen-Dice) coefficients, for matching a remote
 * track request against the local tag cache.
 *
 * <p>Both inputs are composed first ({@link TextNormalizer#compose}) so a decomposed ñ ("n" +
 * combining tilde, as written by macOS/iTunes) compares identically to a precomposed one; any
 * combining mark that survives that is skipped when forming bigrams.
 */
public final class FuzzySearch {

    // a–z (0–25) + 0–9 (26–35)
    private static final int ALPHA_SIZE      = 36;
    private static final int BIGRAM_BUF_SIZE = ALPHA_SIZE * ALPHA_SIZE; // 1 296
    // Upper bound on distinct bigrams we'll dirty per word; handles words up
    // to ~128 chars before falling back to a full clear.
    private static final int MAX_DIRTY       = 128;

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
        String pa = preprocess(TextNormalizer.compose(a));
        String pb = preprocess(TextNormalizer.compose(b));
        if (pa.isEmpty() || pb.isEmpty()) return 0.0f;
        int[] bigramBuf = new int[BIGRAM_BUF_SIZE];
        int[] dirtyBuf  = new int[MAX_DIRTY];
        return diceSimilarity(pa, 0, pa.length(), pb, 0, pb.length(), bigramBuf, dirtyBuf);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

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

    /**
     * Maps a character to its index in the 36-symbol alphabet, or -1.
     * Combining marks land on -1 and are simply skipped, so a decomposed letter contributes its
     * base letter (inputs are composed first, so this only comes up for marks NFC can't compose).
     */
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
