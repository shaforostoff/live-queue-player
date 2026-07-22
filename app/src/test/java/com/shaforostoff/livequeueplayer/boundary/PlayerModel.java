package com.shaforostoff.livequeueplayer.boundary;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 3 — a pure-Java executable model of the track-boundary state machine.
 *
 * <p>This is NOT a copy of production code and pulls in no {@code android.*} classes, so it runs on
 * a plain JVM under JUnit ({@code ./gradlew testDebugUnitTest}) in milliseconds. It has two halves:
 *
 * <ul>
 *   <li>{@link #apply} — a <em>faithful transcription</em> of what {@code Service} + {@code
 *       AudioPlayer} actually do for each event, including their quirks. Every transition is
 *       annotated with the production method + line it mirrors. When you change the real code, mirror
 *       the change here.</li>
 *   <li>{@link #checkInvariants} — the <em>independent specification</em> of what must always be
 *       true. The fuzzer ({@link TrackBoundaryFuzzTest}) drives thousands of random event orderings
 *       through {@link #apply} and calls {@link #checkInvariants} after every step; a violation means
 *       the transcribed logic can reach a state the spec forbids.</li>
 * </ul>
 *
 * <p>Scope: this models the <b>active-playback</b> machine (a live {@code audioPlayer}), which is
 * where the boundary races live. It deliberately does not model the persisted-store resume paths
 * ({@code playFromQueueStore}/{@code playFromQueueIndex}); when playback stops, an episode re-seeds
 * with {@link #seed} (mirroring a fresh external start intent). See docs/testing-race-conditions.md.
 */
final class PlayerModel {

    // ---- Service-level state (mirrors the static s* fields + playlist/playlistPosition) ----
    int size;          // playlist.size()
    int position;      // playlistPosition  — index of the NEXT entry to play
    int currentIndex;  // sCurrentIndex     — index of the track currently loaded (-1 = none)
    boolean playing;   // sIsPlaying        — intended playing state
    boolean hasPending;// sHasPendingTracks
    boolean fadeOut;   // sFadeOutInProgress
    int retriedAt;     // retriedAtPosition — index granted a one-shot retry (-1 = none)

    // ---- Current engine (audioPlayer). Meaningful only while hasPlayer. ----
    boolean hasPlayer; // audioPlayer != null
    boolean prepared;  // AudioPlayer.prepared        — prepare() returned
    Boolean pending;   // AudioPlayer.pendingPlayIntent (null = none) — transport deferred until prepared
    boolean released;  // AudioPlayer.released         — engine torn down
    boolean started;   // native MediaPlayer actually playing (what isPlaying() reports)
    boolean playerFade;// AudioPlayer.fadeOutInProgress
    boolean pausedForFocus; // AudioPlayer.pausedForFocusLoss

    // ---- Mutation hooks: used only by the fuzzer's self-test to prove the invariants have teeth. ----
    boolean bugSkipAdvance;          // playEntry advances position by 2 (breaks monotonic advance)
    boolean bugClearPlayedForgetPos; // clearPlayed forgets to shift playlistPosition

    /** All events reachable at the boundary. Args (counts) travel alongside in {@link Op}. */
    enum Event {
        COMPLETION, SKIP, PLAY, PAUSE, PLAY_PAUSE,
        SET_PENDING, CLEAR_PENDING, CLEAR_PLAYED, APPEND,
        PREPARE_DONE, PREPARE_FAIL, FOCUS_LOSS, FOCUS_GAIN,
        STOP, FADE_DONE, SEEK, KILL
    }

    static final class Op {
        final Event event;
        final int arg; // track count for SET_PENDING / APPEND; unused otherwise
        Op(Event event, int arg) { this.event = event; this.arg = arg; }
        @Override public String toString() {
            return (event == Event.SET_PENDING || event == Event.APPEND) ? event + "(" + arg + ")" : event.toString();
        }
    }

    /** Start a fresh queue of {@code n} tracks and begin playing track 0 (mirrors the initial
     *  external start intent → playEntryFromPlaylist with audioPlayer == null). */
    void seed(int n) {
        size = n;
        position = 0;
        currentIndex = -1;
        playing = false;
        hasPending = false;
        fadeOut = false;
        retriedAt = -1;
        hasPlayer = false;
        playEntry();
    }

    // ---------------------------------------------------------------------------------------------
    // Transitions — each mirrors a production method. Line numbers are as of the current Service.java
    // / AudioPlayer.java; treat them as breadcrumbs, not guarantees.
    // ---------------------------------------------------------------------------------------------

    /** Service.playEntryFromPlaylist() (Service.java:362). Callers guarantee position < size. */
    private void playEntry() {
        currentIndex = position;                       // entry = playlist.get(playlistPosition)
        position += (bugSkipAdvance ? 2 : 1);          // playlistPosition++
        // fresh AudioPlayer (unprepared, not started, not released)
        hasPlayer = true;
        prepared = false;
        pending = null;
        released = false;
        started = false;
        playerFade = false;
        pausedForFocus = false;
        // notifyPlaybackState(true, currentIndex, uri) (Service.java:740)
        playing = true;
        hasPending = position < size;
    }

    /** onMediaPlayerReset() (Service.java:472) — release the old engine before a new one replaces it.
     *  releasePlayer() calls mediaPlayer.release(), so the engine can no longer be playing; isPlaying()
     *  short-circuits on {@code released}, hence the derived started/focus/fade flags fall to false. */
    private void releaseCurrent() {
        if (hasPlayer) { released = true; started = false; pausedForFocus = false; playerFade = false; }
    }

    /** onPlaybackStoppedKeepAlive() (Service.java:684) — stop but keep the service alive. */
    private void stopKeepAlive() {
        fadeOut = false;
        if (hasPlayer) { released = true; started = false; pausedForFocus = false; playerFade = false; hasPlayer = false; }
        size = 0;             // playlist.clear()
        position = 0;
        // notifyPlaybackState(false, -1, null)
        playing = false;
        currentIndex = -1;
        hasPending = false;   // position < size → 0 < 0
    }

    /** onMediaPlayerDestroy() → stopSelf() → onDestroy() (Service.java:481, 718). */
    private void destroy() { stopKeepAlive(); }

    /** AudioPlayer.setState() (AudioPlayer.java:177), reached via Service.setState (Service.java:453). */
    private void setStatePlayer(boolean play) {
        if (released) return;
        if (!prepared) { pending = play; return; }  // defer until prepare() finishes
        pausedForFocus = false;
        started = play;
    }

    /** audioPlayer.isPlaying() (AudioPlayer.java:166). */
    private boolean isPlaying() { return hasPlayer && !released && started; }

    /**
     * Apply one event. Each branch first re-checks the same guard the real dispatcher applies, so any
     * event is valid input — unreachable ones become no-ops exactly as onStart's switch would treat
     * them (e.g. PAUSE/SKIP are ignored while stopped because they are absent from the null branch).
     */
    void apply(Op op) {
        switch (op.event) {
            case COMPLETION -> { // onMediaPlayerComplete() (Service.java:604)
                if (!hasPlayer) return;
                if (fadeOut || playerFade) { stopKeepAlive(); return; } // Stop raced the natural end
                if (position < size) { releaseCurrent(); playEntry(); } // playNextEntry() true
                else stopKeepAlive();                                    // queue exhausted
            }
            case SKIP -> { // Launcher.SKIP → playNextEntry() (Service.java:228)
                if (!hasPlayer) return;
                if (position < size) { releaseCurrent(); playEntry(); }
                // else: playNextEntry() returns false and SKIP does nothing
            }
            case PLAY -> { // Launcher.PLAY (Service.java:216)
                if (!hasPlayer) return; // null branch routes to playFromQueueStore (out of model scope)
                if (playerFade) { fadeOut = false; playerFade = false; cancelFadeAndResume(); }
                setStatePlayer(true);
                playing = true; hasPending = position < size;  // notifyPlaybackState(true, ...)
            }
            case PAUSE -> { // Launcher.PAUSE (Service.java:224)
                if (!hasPlayer) return; // absent from the null branch → ignored while stopped
                setStatePlayer(false);
                playing = false; hasPending = position < size; // notifyPlaybackState(false, currentIndex, uri)
            }
            case PLAY_PAUSE -> { // Launcher.PLAY_PAUSE (Service.java:207)
                if (!hasPlayer) return;
                if (playerFade) { fadeOut = false; playerFade = false; cancelFadeAndResume(); }
                boolean shouldPlay = !isPlaying();
                setStatePlayer(shouldPlay);
                playing = shouldPlay; hasPending = position < size;
            }
            case SET_PENDING -> { // setPendingQueue() (Service.java:520)
                if (!hasPlayer) return;
                if (position < 0) position = 0;
                size = Math.min(size, position); // drop existing pending (keep current)
                size += op.arg;                  // append replacement pending set
                hasPending = position < size;
            }
            case CLEAR_PENDING -> { // clearPendingQueue() (Service.java:557)
                if (!hasPlayer) return;
                if (position < 0) position = 0;
                size = Math.min(size, position);
                hasPending = position < size;
            }
            case CLEAR_PLAYED -> { // clearPlayedQueue() (Service.java:571)
                if (!hasPlayer) return;
                int removeCount = currentIndex;
                if (removeCount <= 0) return;
                size -= removeCount;
                if (!bugClearPlayedForgetPos) position -= removeCount;
                if (position < 0) position = 0;
                retriedAt = -1;
                currentIndex = currentIndex - removeCount; // notifyPlaybackState(playing, currentIndex-rc, uri)
                hasPending = position < size;
            }
            case APPEND -> { // appendQueueFromIntent() (Service.java:501)
                if (!hasPlayer) return;
                size += op.arg;
                // NOTE (faithful): APPEND does NOT recompute sHasPendingTracks. The flag stays stale
                // until the next notify; playback correctness uses position<size directly, not the flag.
            }
            case PREPARE_DONE -> { // AudioPlayer.run() success tail (AudioPlayer.java:127)
                if (!hasPlayer || prepared || released) return;
                prepared = true;
                boolean play = (pending == null || pending); // honor deferred intent; default play
                setStatePlayer(play);
            }
            case PREPARE_FAIL -> { // AudioPlayer.run() catch → Service.playOrDestroy() (Service.java:438)
                if (!hasPlayer || prepared || released) return;
                int failed = position - 1;
                if (retriedAt != failed) {          // first failure here → retry once
                    retriedAt = failed;
                    position = failed;
                    releaseCurrent();
                    playEntry();
                } else {                            // already retried → give up on this track
                    retriedAt = -1;
                    if (position < size) { releaseCurrent(); playEntry(); }
                    else destroy();
                }
            }
            case FOCUS_LOSS -> { // onMainAudioFocusChange LOSS (AudioPlayer.java:400) → onAudioFocusLoss
                if (!hasPlayer || released || !started) return;
                started = false; pausedForFocus = true;
                playing = false;
            }
            case FOCUS_GAIN -> { // onMainAudioFocusChange GAIN (AudioPlayer.java:413) → onAudioFocusResume
                if (!hasPlayer || released || !pausedForFocus || started || playerFade) return;
                pausedForFocus = false; started = true;
                playing = true;
            }
            case STOP -> { // Launcher.STOP (Service.java:229) → fadeOutAndStop (async)
                if (!hasPlayer) return;
                fadeOut = true; playerFade = true;
            }
            case FADE_DONE -> { // fade thread finishes → onFadeOutComplete() (Service.java:623)
                if (!hasPlayer || !playerFade) return;
                playerFade = false;
                stopKeepAlive();
            }
            case SEEK -> { // Launcher.SEEK (Service.java:240) — progress-only; no boundary state change
                if (!hasPlayer) return;
            }
            case KILL -> stopKeepAlive(); // Launcher.KILL (Service.java:248) — valid stopped or playing
        }
    }

    /** AudioPlayer.cancelFadeOutAndResume() (AudioPlayer.java:209). */
    private void cancelFadeAndResume() {
        if (released) return;
        if (!started) started = true;
    }

    // ---------------------------------------------------------------------------------------------
    // The specification. Any violation is a state the transcribed logic must never reach.
    // ---------------------------------------------------------------------------------------------
    void checkInvariants() {
        req(position >= 0 && position <= size, "position out of range");
        req(currentIndex >= -1 && currentIndex < Math.max(size, 1) && (size != 0 || currentIndex == -1),
                "currentIndex out of range");

        // Strong structural invariant held throughout active playback: the next entry to play is
        // always exactly one past the one currently loaded.
        if (hasPlayer && currentIndex >= 0) {
            req(position == currentIndex + 1, "playlistPosition must be currentIndex + 1 during playback");
        }

        // "Playing" must correspond to a live, non-released engine sitting on a real track.
        if (playing) {
            req(hasPlayer, "playing with no engine");
            req(!released, "playing on a released engine");
            req(currentIndex >= 0, "playing with no current track");
        }

        // A torn-down engine must never be left in a started state, and no engine means nothing plays.
        if (released) req(!started, "started flag set on a released engine");
        if (!hasPlayer) { req(!playing, "playing with no engine"); req(!started, "started with no engine"); }
    }

    private static void req(boolean cond, String message) {
        if (!cond) throw new AssertionError(message);
    }

    /** Compact one-line snapshot for failure diagnostics. */
    String snapshot() {
        return "size=" + size + " pos=" + position + " idx=" + currentIndex
                + " playing=" + playing + " pending=" + hasPending + " fadeOut=" + fadeOut
                + " retriedAt=" + retriedAt + " | hasPlayer=" + hasPlayer + " prepared=" + prepared
                + " deferred=" + pending + " released=" + released + " started=" + started
                + " playerFade=" + playerFade + " pausedForFocus=" + pausedForFocus;
    }

    /** Events reachable from the current state, mirroring reality so the fuzzer feeds no impossible
     *  inputs (a completion only fires from a genuinely-playing engine, etc.). */
    List<Op> applicableOps(java.util.Random rng) {
        List<Op> ops = new ArrayList<>();
        if (!hasPlayer) return ops; // stopped: episode re-seeds instead (see fuzzer)
        // Transport / queue edits are always dispatchable while a player is live.
        ops.add(new Op(Event.SKIP, 0));
        ops.add(new Op(Event.PLAY, 0));
        ops.add(new Op(Event.PAUSE, 0));
        ops.add(new Op(Event.PLAY_PAUSE, 0));
        ops.add(new Op(Event.SET_PENDING, rng.nextInt(5)));
        ops.add(new Op(Event.CLEAR_PENDING, 0));
        ops.add(new Op(Event.CLEAR_PLAYED, 0));
        ops.add(new Op(Event.APPEND, 1 + rng.nextInt(4)));
        ops.add(new Op(Event.SEEK, 0));
        ops.add(new Op(Event.STOP, 0));
        ops.add(new Op(Event.KILL, 0));
        if (!prepared && !released) {           // prepare is still running
            ops.add(new Op(Event.PREPARE_DONE, 0));
            ops.add(new Op(Event.PREPARE_FAIL, 0));
        }
        if (prepared && started) ops.add(new Op(Event.COMPLETION, 0)); // only a playing engine can end
        if (started) ops.add(new Op(Event.FOCUS_LOSS, 0));
        if (pausedForFocus && !started && !playerFade) ops.add(new Op(Event.FOCUS_GAIN, 0));
        if (playerFade) ops.add(new Op(Event.FADE_DONE, 0));
        return ops;
    }
}
