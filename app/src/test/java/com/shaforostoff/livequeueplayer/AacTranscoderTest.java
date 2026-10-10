package com.shaforostoff.livequeueplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The pure parts of {@link AacTranscoder}: which rates convert, and the hi-res decimator. */
public class AacTranscoderTest {

    @Test
    public void ratesTheEncoderTakesPassAndHiResIsBroughtDown() {
        assertEquals(1, AacTranscoder.decimationFor(44100));
        assertEquals(1, AacTranscoder.decimationFor(48000));
        assertEquals(2, AacTranscoder.decimationFor(88200));
        assertEquals(2, AacTranscoder.decimationFor(96000));
        assertEquals(4, AacTranscoder.decimationFor(176400));
        assertEquals(4, AacTranscoder.decimationFor(192000));
        assertEquals(8, AacTranscoder.decimationFor(384000));
        assertEquals(2, AacTranscoder.decimationFor(64000));   // to 32 kHz
        assertEquals(0, AacTranscoder.decimationFor(50000));
        assertEquals(0, AacTranscoder.decimationFor(37800));
    }

    @Test
    public void onlyTracksAboveTheThresholdAreWorthCompressing() {
        long minute = 60_000;
        assertTrue(AacTranscoder.worthCompressing(30L * 1024 * 1024, 4 * minute));   // a FLAC
        assertTrue(AacTranscoder.worthCompressing(320_000 / 8 * 240, 4 * minute));   // 320k MP3
        assertFalse(AacTranscoder.worthCompressing(128_000 / 8 * 240, 4 * minute));  // 128k MP3
        assertFalse(AacTranscoder.worthCompressing(1_000_000, -1));                  // unknown length
    }

    @Test
    public void trackNumbersParseWithOrWithoutATotal() {
        assertArrayEquals(new int[]{3, 0}, AacTranscoder.numberOf("3"));
        assertArrayEquals(new int[]{3, 12}, AacTranscoder.numberOf(" 3 / 12"));
        assertArrayEquals(new int[]{0, 0}, AacTranscoder.numberOf("A1"));
        assertArrayEquals(new int[]{0, 0}, AacTranscoder.numberOf(null));
    }

    @Test
    public void halvingKeepsTheAudibleBandAndStopsWhatWouldFoldIntoIt() {
        // Stereo at 96 kHz: 1 kHz on the left, 40 kHz (which would fold to 8 kHz) on the right.
        int rate = 96_000, frames = rate / 2;
        short[] in = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            in[2 * i] = (short) (16_000 * Math.sin(2 * Math.PI * 1_000 * i / rate));
            in[2 * i + 1] = (short) (16_000 * Math.sin(2 * Math.PI * 40_000 * i / rate));
        }
        AacTranscoder.HalfBand stage = new AacTranscoder.HalfBand(2);
        // In uneven pieces, as decoder buffers come.
        short[] out = new short[0];
        for (int at = 0; at < in.length; ) {
            int n = Math.min(in.length - at, 2 * (777 + at % 300));
            short[] piece = java.util.Arrays.copyOfRange(in, at, at + n);
            short[] got = stage.process(piece);
            short[] grown = java.util.Arrays.copyOf(out, out.length + got.length);
            System.arraycopy(got, 0, grown, out.length, got.length);
            out = grown;
            at += n;
        }
        assertTrue(Math.abs(out.length / 2 - frames / 2) <= 24);

        // Past the filter's start-up, the 1 kHz tone is whole and the 40 kHz one gone.
        double left = 0, right = 0;
        int counted = 0;
        for (int i = 100; i < out.length / 2; i++) {
            left = Math.max(left, Math.abs(out[2 * i]));
            right = Math.max(right, Math.abs(out[2 * i + 1]));
            counted++;
        }
        assertTrue(counted > 20_000);
        assertEquals(16_000, left, 160);       // within 0.1 dB
        assertTrue("40 kHz leaked: " + right, right < 16);  // below -60 dB
    }
}
