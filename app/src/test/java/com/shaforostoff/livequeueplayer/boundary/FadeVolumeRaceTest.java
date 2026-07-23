package com.shaforostoff.livequeueplayer.boundary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Regression for 5da8d45 (AudioPlayer half) — "Fix fade-out volume race leaving playback silent".
 *
 * <p>The fade ramp runs on a detached thread doing token-check-then-setVolume, while
 * cancelFadeOutAndResume() runs on the main thread doing token-bump-then-setVolume(baseGain). Before
 * the fix these were not serialized, so a resume could land between the fade thread's check and its
 * write: the resume restored baseGain, then the fade thread overwrote it with its next near-zero step
 * — playback ran but was inaudible. The fix wraps both critical sections in a shared {@code fadeLock}
 * and re-checks the token under it, so the ramp bails once the resume has bumped the token.
 *
 * <p>This is a pure-JVM model of that protocol at critical-section granularity (a data race can't be
 * reproduced deterministically, but the locking invariant can): with the lock, whichever section runs
 * last leaves the correct value, and once cancel bumps the token every later ramp step bails — so the
 * final volume after a resume is always baseGain. The mutation test models the pre-fix code (the
 * token check and the volume write are separable, and a cancel can interleave between them) and proves
 * that ordering can leave the player silent — i.e. this test actually detects the bug the fix removed.
 */
public class FadeVolumeRaceTest {

    private static final float BASE_GAIN = 0.8f;
    private static final int STEPS = 40;

    /** One primitive action in the interleaving. */
    private enum Kind { STEP, CHECK, WRITE, CANCEL }
    private static final class Action {
        final Kind kind; final int i;
        Action(Kind kind, int i) { this.kind = kind; this.i = i; }
    }

    /** Volume of ramp step i (i = STEPS → baseGain … i = 0 → silent), matching AudioPlayer.fadeOutAndStop. */
    private static float stepVolume(int i) {
        float t = i / (float) STEPS;
        return (t == 0f) ? 0f : (float) (BASE_GAIN * Math.pow(10.0, -40.0 * (1.0 - t) / 20.0));
    }

    @Test
    public void resumeAlwaysEndsAtBaseGain_withFadeLock() {
        for (int seed = 0; seed < 5_000; seed++) {
            float finalVolume = run(seed, /*buggy=*/false);
            assertEquals("seed " + seed + ": a resume must leave the track at baseGain, not silent",
                    BASE_GAIN, finalVolume, 1e-6f);
        }
    }

    @Test
    public void mutationSelfTest_preFixProtocolCanLeaveSilent() {
        boolean sawSilent = false;
        for (int seed = 0; seed < 5_000 && !sawSilent; seed++) {
            float finalVolume = run(seed, /*buggy=*/true);
            if (finalVolume < BASE_GAIN - 1e-4f) sawSilent = true; // resume clobbered by a stale step
        }
        assertTrue("pre-fix protocol must be able to leave playback silent — else this test has no teeth",
                sawSilent);
    }

    /**
     * Execute one interleaving: the fade ramp's actions with a single resume (CANCEL) spliced in at a
     * seed-chosen point, under mutually-exclusive critical sections. Returns the final volume.
     */
    private float run(int seed, boolean buggy) {
        Random rng = new Random(seed);

        // Fade-thread actions, newest token captured at fade start.
        List<Action> fade = new ArrayList<>();
        for (int i = STEPS; i >= 0; i--) {
            if (buggy) {                       // pre-fix: check and write are separable
                fade.add(new Action(Kind.CHECK, i));
                fade.add(new Action(Kind.WRITE, i));
            } else {                           // fixed: check+write are one atomic section
                fade.add(new Action(Kind.STEP, i));
            }
        }
        // Splice exactly one CANCEL (the main-thread resume) at a random position.
        int cancelAt = rng.nextInt(fade.size() + 1);
        List<Action> ops = new ArrayList<>(fade.subList(0, cancelAt));
        ops.add(new Action(Kind.CANCEL, -1));
        ops.addAll(fade.subList(cancelAt, fade.size()));

        // Shared state (all mutations happen under the conceptual fadeLock, i.e. serially here).
        int fadeToken = 0;
        final int myToken = fadeToken;   // token captured by the fade thread at start
        float volume = BASE_GAIN;
        boolean checkPassed = false;     // buggy-mode carry between CHECK and WRITE

        for (Action a : ops) {
            switch (a.kind) {
                case STEP -> {                 // fixed: atomic check-then-write
                    if (myToken == fadeToken) volume = stepVolume(a.i);
                }
                case CHECK -> checkPassed = (myToken == fadeToken);   // buggy: decision taken early…
                case WRITE -> { if (checkPassed) volume = stepVolume(a.i); } // …acted on late, token unre-checked
                case CANCEL -> { fadeToken++; volume = BASE_GAIN; }  // resume restores baseGain
            }
        }
        return volume;
    }
}
