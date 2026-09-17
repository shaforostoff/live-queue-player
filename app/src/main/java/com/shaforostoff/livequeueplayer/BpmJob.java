package com.shaforostoff.livequeueplayer;

/**
 * One track's analysis, from the scanner's side: a flag it can raise and a hook the engine
 * registers while it is holding something worth interrupting.
 *
 * <p>The indirection through {@link #setCanceller} is what lets cancellation reach *into* a native
 * analysis. Between decoded chunks the flag alone is enough, but the final analysis pass runs for
 * seconds inside one call, and only a native cancel gets it to stop. The engine registers that
 * cancel while it owns a session and clears it afterwards, so nothing here ever names a native
 * handle — which is also what lets a fake engine stand in for the real one in a JVM test.
 */
final class BpmJob {

    private volatile boolean cancelled;
    private volatile Runnable canceller;

    boolean isCancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
        Runnable r = canceller;
        if (r != null) r.run();
    }

    /**
     * Registers what to run if this job is cancelled, or null to clear it. Runs {@code r}
     * immediately when the job was already cancelled — otherwise a cancel landing between the
     * check at the top of a measurement and this call would be silently lost.
     */
    void setCanceller(Runnable r) {
        canceller = r;
        if (r != null && cancelled) r.run();
    }
}
