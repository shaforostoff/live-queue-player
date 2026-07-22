package com.shaforostoff.livequeueplayer.boundary;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Step 3 — model-based fuzzing of the track-boundary state machine.
 *
 * <p>Pure JVM, no Android, no device. Runs with {@code ./gradlew testDebugUnitTest}. Each "episode"
 * seeds a random queue then applies a random, reachable event sequence to {@link PlayerModel},
 * checking {@link PlayerModel#checkInvariants()} after every single event. A {@code Random(seed)}
 * drives everything, so any failure prints a seed + event log that reproduces it exactly — paste it
 * into {@link #replay} (or a new {@code @Test}) to pin it as a permanent regression.
 */
public class TrackBoundaryFuzzTest {

    private static final int EPISODES = 20_000;   // distinct seeds
    private static final int STEPS_PER_EPISODE = 300;
    private static final int MAX_QUEUE = 6;

    @Test
    public void invariantsHoldUnderEveryEventOrdering() {
        for (int seed = 0; seed < EPISODES; seed++) {
            String failure = runEpisode(seed, STEPS_PER_EPISODE, false, false);
            if (failure != null) fail(failure);
        }
    }

    /**
     * Proves the invariants are not vacuous: with a deliberately-broken clearPlayed (it forgets to
     * shift playlistPosition), the fuzzer must discover an ordering that trips an invariant. If this
     * test ever passes silently, the harness has lost its teeth and the real test above is worthless.
     */
    @Test
    public void mutationSelfTest_catchesBrokenClearPlayed() {
        boolean caught = false;
        for (int seed = 0; seed < 2_000 && !caught; seed++) {
            if (runEpisode(seed, STEPS_PER_EPISODE, false, true) != null) caught = true;
        }
        assertTrue("fuzzer failed to catch a broken clearPlayedQueue — invariants have no teeth", caught);
    }

    @Test
    public void mutationSelfTest_catchesBrokenAdvance() {
        // A playEntry that skips a track breaks the monotonic-advance invariant immediately.
        assertTrue("fuzzer failed to catch a skip-advance bug",
                runEpisode(0, STEPS_PER_EPISODE, true, false) != null);
    }

    /**
     * Runs one episode. Returns null on success, or a reproducing description on invariant violation.
     */
    private String runEpisode(int seed, int steps, boolean bugAdvance, boolean bugClearPlayed) {
        Random rng = new Random(seed);
        PlayerModel m = new PlayerModel();
        m.bugSkipAdvance = bugAdvance;
        m.bugClearPlayedForgetPos = bugClearPlayed;
        List<String> log = new ArrayList<>();

        int initial = 1 + rng.nextInt(MAX_QUEUE);
        log.add("seed(" + initial + ")");
        try {
            m.seed(initial);
            m.checkInvariants();
        } catch (AssertionError e) {
            return describe(seed, log, m, e);
        }

        for (int step = 0; step < steps; step++) {
            if (!m.hasPlayer) {
                // Playback stopped; re-seed with a fresh external start intent to keep exploring.
                int n = 1 + rng.nextInt(MAX_QUEUE);
                log.add("seed(" + n + ")");
                try { m.seed(n); m.checkInvariants(); }
                catch (AssertionError e) { return describe(seed, log, m, e); }
                continue;
            }
            List<PlayerModel.Op> ops = m.applicableOps(rng);
            if (ops.isEmpty()) continue;
            PlayerModel.Op op = ops.get(rng.nextInt(ops.size()));
            log.add(op.toString());
            try {
                m.apply(op);
                m.checkInvariants();
            } catch (AssertionError e) {
                return describe(seed, log, m, e);
            }
        }
        return null;
    }

    private static String describe(int seed, List<String> log, PlayerModel m, AssertionError e) {
        return "\nINVARIANT VIOLATED: " + e.getMessage()
                + "\n  seed  = " + seed
                + "\n  state = " + m.snapshot()
                + "\n  trace = " + String.join(" -> ", log);
    }

    /**
     * Template for pinning a discovered ordering as a permanent regression test. Replace the ops with
     * the {@code trace} the fuzzer printed, drop the {@code @org.junit.Ignore}, and it fails until fixed.
     */
    @org.junit.Ignore("template — fill in with a reproducing trace when the fuzzer finds one")
    @Test
    public void replay() {
        PlayerModel m = new PlayerModel();
        m.seed(3);
        m.apply(new PlayerModel.Op(PlayerModel.Event.SET_PENDING, 0));
        m.apply(new PlayerModel.Op(PlayerModel.Event.COMPLETION, 0));
        m.checkInvariants();
    }
}
