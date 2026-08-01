package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Both spellings of Spanish ñ must behave as one character: precomposed U+00F1, and decomposed
 * "n" + U+0303 (as written by macOS filesystems and iTunes-exported M3U playlists).
 *
 * Escapes rather than literal accented chars throughout, so which form each constant uses is
 * visible in the source and can't be silently rewritten by an editor.
 */
public class UnicodeFormTest {

    private static final char TILDE = '̃';           // combining tilde
    private static final String NFC_NINO = "Niño";   // Niño, precomposed
    private static final String NFD_NINO = "Niño";  // Niño, n + combining tilde
    private static final String NFC_ANO  = "Año";    // Año, precomposed
    private static final String NFD_ANO  = "Año";   // Año, decomposed

    @Test
    public void theTwoSpellingsAreDistinctToPlainJava() {
        // Guards the premise of everything below: without normalisation these are unrelated strings.
        assertNotEquals(NFC_NINO, NFD_NINO);
        assertFalse(NFC_NINO.equalsIgnoreCase(NFD_NINO));
        assertFalse(NFD_NINO.contains(NFC_NINO));
    }

    @Test
    public void composeMakesBothSpellingsIdentical() {
        assertEquals(TextNormalizer.compose(NFC_NINO), TextNormalizer.compose(NFD_NINO));
        assertEquals(NFC_NINO, TextNormalizer.compose(NFD_NINO));
    }

    @Test
    public void composeLeavesAsciiAndComposedTextUntouched() {
        String ascii = "Nino.mp3";
        assertSameInstance(ascii, TextNormalizer.compose(ascii));
        assertSameInstance(NFC_NINO, TextNormalizer.compose(NFC_NINO));
    }

    @Test
    public void equalsIgnoresFormAndOptionallyCase() {
        assertTrue(TextNormalizer.equals(NFC_NINO, NFD_NINO));
        assertFalse(TextNormalizer.equals(NFC_NINO, "niño"));          // case still matters
        assertTrue(TextNormalizer.equalsIgnoreCase(NFC_NINO, "niño"));
        assertTrue(TextNormalizer.equalsIgnoreCase("NIÑO", NFD_NINO)); // uppercase, other form
        assertTrue(TextNormalizer.equalsIgnoreCase("NIÑO", NFC_NINO));
        assertFalse(TextNormalizer.equalsIgnoreCase(NFC_NINO, "Nino"));     // ñ is not n
    }

    @Test
    public void equalsHandlesNulls() {
        assertTrue(TextNormalizer.equals(null, null));
        assertFalse(TextNormalizer.equals(null, NFC_NINO));
        assertFalse(TextNormalizer.equalsIgnoreCase(NFC_NINO, null));
    }

    @Test
    public void searchFindsEitherSpellingOfTheQueryInEitherSpellingOfTheText() {
        assertTrue(TextNormalizer.containsIgnoreCase("01 - " + NFD_NINO + ".mp3", "niño"));
        assertTrue(TextNormalizer.containsIgnoreCase("01 - " + NFC_NINO + ".mp3", "niño"));
        assertTrue(TextNormalizer.containsIgnoreCase(NFD_ANO + " nuevo", NFC_ANO));
        assertTrue(TextNormalizer.containsIgnoreCase(NFC_ANO + " nuevo", NFD_ANO));
        assertFalse(TextNormalizer.containsIgnoreCase(NFD_NINO, "nina"));
    }

    @Test
    public void bothSpellingsSortTogether() {
        String[] names = {"Nube", NFD_NINO, "Nada", NFC_NINO, "Nube"};
        Arrays.sort(names, TextNormalizer::compareIgnoreCase);
        assertEquals(0, TextNormalizer.compareIgnoreCase(NFC_NINO, NFD_NINO));
        // Nada, then both Niños adjacent (rather than in separate runs), then the two Nubes.
        assertEquals("Nada", names[0]);
        assertEquals("Nube", names[3]);
        assertEquals("Nube", names[4]);
        assertTrue(TextNormalizer.equals(names[1], NFC_NINO));
        assertTrue(TextNormalizer.equals(names[2], NFC_NINO));
    }

    @Test
    public void variantsCoverBothSpellingsAndKeepTheGivenOneFirst() {
        String[] fromComposed = TextNormalizer.variants(NFC_NINO + ".mp3");
        assertEquals(2, fromComposed.length);
        assertEquals(NFC_NINO + ".mp3", fromComposed[0]);
        assertEquals(NFD_NINO + ".mp3", fromComposed[1]);

        String[] fromDecomposed = TextNormalizer.variants(NFD_NINO + ".mp3");
        assertEquals(2, fromDecomposed.length);
        assertEquals(NFD_NINO + ".mp3", fromDecomposed[0]);
        assertEquals(NFC_NINO + ".mp3", fromDecomposed[1]);

        // ASCII has only one spelling, so callers do exactly one lookup as before.
        assertEquals(1, TextNormalizer.variants("Nino.mp3").length);
    }

    @Test
    public void combiningMarkDetection() {
        assertTrue(TextNormalizer.isCombiningMark(TILDE));
        assertTrue(TextNormalizer.isCombiningMark('́'));   // combining acute
        assertFalse(TextNormalizer.isCombiningMark('ñ'));
        assertFalse(TextNormalizer.isCombiningMark('n'));
    }

    // -- FuzzySearch ---------------------------------------------------------

    @Test
    public void fuzzyMatchScoresBothSpellingsAsTheSameTitle() {
        assertEquals(1.0f, FuzzySearch.matchFuzzy(NFC_NINO + " bonito", NFD_NINO + " bonito"), 0.0001f);
        assertEquals(FuzzySearch.matchFuzzy(NFC_NINO, NFC_NINO),
                     FuzzySearch.matchFuzzy(NFD_NINO, NFC_NINO), 0.0001f);
    }

    @Test
    public void fuzzyContainsDoesNotSplitWordsAtACombiningTilde() {
        // Decomposed "Niño" must stay one word: split at the tilde it would be "Ni" + "o".
        assertEquals(1.0f, FuzzySearch.containsFuzzy("01 " + NFD_NINO + " bonito.mp3", NFC_NINO), 0.0001f);
        assertEquals(1.0f, FuzzySearch.containsFuzzy("01 " + NFC_NINO + " bonito.mp3", NFD_NINO), 0.0001f);
    }

    @Test
    public void fuzzyShortWordsMatchAcrossSpellings() {
        // Short words take the direct-comparison path, where the decomposed form is one char longer.
        assertEquals(1.0f, FuzzySearch.containsFuzzy("El ñu corre.mp3", "ñu"), 0.0001f);
        assertEquals(1.0f, FuzzySearch.containsFuzzy("El ñu corre.mp3", "ñu"), 0.0001f);
        // Non-Latin short words still compare as themselves.
        assertEquals(0.0f, FuzzySearch.containsFuzzy("El ñu corre.mp3", "да"), 0.0001f);
    }

    private static void assertSameInstance(String expected, String actual) {
        assertTrue("expected the original instance back (no allocation)", expected == actual);
    }
}
