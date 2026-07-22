# Testing track-boundary & timing/race behaviour

Two automated test layers, **both running on your PC (plain JVM — no device, no emulator, no ADB)**,
target the hardest-to-reproduce bugs in the player: what happens when something (skip, pause, queue
edit, prepare finishing/failing, audio-focus loss, stop) lands *at or near a track boundary*.

| | Step 3 — model fuzzing | Step 2 — Robolectric integration |
|---|---|---|
| Under test | a pure-Java **model** of the boundary state machine | the **real `Service`** boundary code |
| Framework | none (plain JUnit) | Robolectric fakes Android on the JVM |
| Coverage | thousands of random event orderings | hand-picked boundary interleavings |
| Finds | orderings you never thought to write | real-`Service` sequencing regressions |
| Speed | ~6M event-applications in a few seconds | seconds (after first-run download) |

They are complementary: the **fuzzer proposes** a suspicious ordering; you **dispose** of it by
reproducing it against the real `Service` in a Step-2 test. Both assert the *same* invariants.

A third layer, **Step 5 — debug runtime assertions**, ships those same invariants inside the app
(debug builds only) so they also fire during real on-device / chaos / monkey runs. See the Step 5
section below.

> Not implemented here: Step 1 (instrumented / UI Automator) and Step 4 (seeded ADB chaos harness) —
> they run *on* the phone over USB and are tracked separately.

---

## How to run

First run downloads Robolectric (~one-time, needs network); afterwards everything is offline.

```bash
# Everything (both steps)
./gradlew testDebugUnitTest

# Just the model fuzzer (Step 3)
./gradlew testDebugUnitTest --tests "com.shaforostoff.livequeueplayer.boundary.TrackBoundaryFuzzTest"

# Just the Robolectric integration (Step 2)
./gradlew testDebugUnitTest --tests "com.shaforostoff.livequeueplayer.TrackBoundaryTest"
```

HTML report on failure: `app/build/reports/tests/testDebugUnitTest/index.html`.

---

## The boundary event matrix

The window a player is vulnerable in runs from `onMediaPlayerComplete` →
`onMediaPlayerReset` (releases the old engine) → `playEntryFromPlaylist` → new `prepare()`
(**seconds** for ALAC, since decode happens inside `prepare()`) → `prepared` → `start()`. Every
event that can arrive during that window is a test case:

| Event | Production path |
|---|---|
| natural end | `onMediaPlayerComplete` → `playNextEntry` |
| SKIP | `playNextEntry` mid-advance |
| PLAY / PAUSE / PLAY_PAUSE | `setState` → `pendingPlayIntent` defer while unprepared |
| SET_PENDING / CLEAR_PENDING queue edit | atomic clear+append vs. an interleaving completion |
| CLEAR_PLAYED | index renumbering under a live track |
| APPEND | append to a queue whose last track is ending |
| prepare finished / **failed** | `run()` tail / `playOrDestroy` retry-once |
| audio-focus loss / gain | `pausedForFocusLoss` |
| STOP (fade) racing the natural end | `sFadeOutInProgress` guard in `onMediaPlayerComplete` |
| last track ends | `playNextEntry` false → stop |

## The invariants (the shared spec)

Both layers check these after **every** event:

1. `0 ≤ playlistPosition ≤ playlist.size()`
2. `-1 ≤ sCurrentIndex < size` (and `sCurrentIndex == -1` exactly when stopped)
3. **During active playback, `playlistPosition == sCurrentIndex + 1`** — the strong one. It holds
   continuously (every queue edit that moves one moves the other), so any advance that skips or
   double-counts a track trips it immediately.
4. `sIsPlaying` ⇒ there is a live, non-released engine on a real track (`sCurrentIndex ≥ 0`).
5. A released engine is never left "started"; no engine ⇒ nothing playing.

---

## Step 3 — model fuzzing

Files (pure Java, no `android.*`, package `…​.boundary`):
- `PlayerModel.java` — a faithful, line-referenced transcription of the `Service` + `AudioPlayer`
  boundary logic (`apply(Op)`), plus the invariant spec (`checkInvariants()`).
- `TrackBoundaryFuzzTest.java` — a `Random(seed)` loop: 20 000 episodes × up to 300 events, invariants
  checked after each. On failure it prints the **seed + full event trace** that reproduces it.

**Reading a failure.** You get something like:

```
INVARIANT VIOLATED: playlistPosition must be currentIndex + 1 during playback
  seed  = 0
  state = size=0 pos=0 idx=-1 ... started=true
  trace = seed(1) -> SEEK -> APPEND(2) -> ... -> KILL
```

Replay it by dropping the `@Ignore` on `replay()` and pasting the trace as `apply(...)` calls — it
becomes a permanent regression test.

**The mutation self-tests matter.** `mutationSelfTest_catchesBrokenClearPlayed` and
`…​catchesBrokenAdvance` inject a deliberate bug and assert the fuzzer *catches* it. If they ever pass
silently, the invariants have lost their teeth and the main test is worthless — treat their failure
as seriously as any other.

> This layer already earned its keep: transcribing the model surfaced that `AudioPlayer.isPlaying()`
> short-circuits on `released` (a torn-down engine is never "started"), and that `APPEND` leaves
> `sHasPendingTracks` intentionally stale (playback correctness reads `position < size` directly, not
> the flag) — so that flag is *not* asserted as a hard invariant.

**Scope / caveats.**
- The model mirrors the **active-playback** machine, where the races live. It does **not** model the
  persisted-store resume paths (`playFromQueueStore` / `playFromQueueIndex`); when playback stops, an
  episode re-seeds with a fresh start instead.
- The model is a hand-written mirror. Its value is (a) an executable spec, (b) a regression net when
  the code changes, (c) the oracle Step 2 reuses. **When you change the boundary logic in `Service`
  or `AudioPlayer`, update `PlayerModel.apply()` to match** — a divergence there is the point, not a
  nuisance.

**Adding an event.** Add it to `PlayerModel.Event`, implement the transition in `apply()` (with a
`// Service.java:NNN` breadcrumb), and list it in `applicableOps()` guarded by the same
reachability the real dispatcher applies.

---

## Step 2 — Robolectric on a paused looper

File: `TrackBoundaryTest.java` (package `com.shaforostoff.livequeueplayer`, so it can call the
package-private boundary entry points).

**The seam.** `Service` creates its audio engine through `protected PlaybackEngine
createPlaybackEngine(Uri)`; production returns a real `AudioPlayer`, the test returns a `FakeEngine`
with no threads, no `MediaPlayer`, no file I/O. `isRemoteHostSession()` is overridden to keep the
tests off the real `App`/Bluetooth bridge. Nothing else in `Service` changed — the real sequencing,
queue mutation, statics, notifications, media session and foreground promotion all run for real
under Robolectric.

**Why a paused looper.** Robolectric's main `Looper` defaults to PAUSED: posted work
(`onTrackDurationResolved`, progress ticks) stays queued until the test calls
`shadowOf(Looper.getMainLooper()).idle()`. So "did the late duration report land before or after the
skip?" is a decision the test makes, not a race — that's what makes these deterministic.

**How events are driven.**
- Transport / queue edits go through the **real** `onStartCommand` intents (self-intents with a
  `Launcher.TYPE` byte; `file://` URIs so `generate()` is a no-op copy). Real dispatch code runs.
- `onMediaPlayerComplete`, `onAudioFocusLoss/Resume`, `onFadeOutComplete` are called directly.
- Prepare finish/failure is driven via `FakeEngine.simulatePrepared*/simulatePrepareFailure`, which
  mirror `AudioPlayer.run()`'s tail (report duration on the looper, apply the deferred play/pause).
- Assertions read observable statics plus `playlistPosition`/`playlist` via reflection (private
  fields — if you rename them, the reflection helper fails loudly telling you so).

**Adding a case.** Copy one of the `@Test` methods, arrange the interleaving with `startQueue`,
`prepareCurrent`, `sendSelf(...)`, `service.onMediaPlayerComplete()`, and `drainLooper()`, then call
`invariants()` after each step plus any case-specific asserts. Pair a fuzzer-found trace here to
confirm it reproduces (or doesn't) against the real `Service`.

---

## Step 5 — debug runtime assertions (on-device tripwire)

The same invariants, embedded in `Service` itself and checked at runtime in **debug builds only**, so
a violation crashes *loudly at the point of corruption* with a stack trace — instead of surfacing
tracks later as mystery silence — during ordinary debug use, monkey runs, or a Step-4 chaos run.

- `assertBoundaryInvariants()` is called from `sendPlaybackStateBroadcast()` — the state-committed
  chokepoint, hit at the end of every boundary mutation and on each progress tick — guarded by
  `if (BuildConfig.DEBUG && !destroyed)`.
- A second precondition in `playEntryFromPlaylist()` names an out-of-range advance instead of letting
  it throw a bare `IndexOutOfBoundsException`.

**Zero release cost.** `BuildConfig.DEBUG` is a compile-time constant, so in release the guarded
blocks are dead code and R8 strips the assert methods entirely (release compiles verified).

**Enabling `BuildConfig`.** These asserts need `android.buildFeatures.buildConfig = true` (added to
`app/build.gradle`), because AGP 8 does not generate `BuildConfig` by default.

**How they're verified without a device.** Robolectric runs the *debug* variant, so `BuildConfig.DEBUG`
is true under the Step-2 tests — every Step-2 scenario exercises these live asserts on the JVM. That
is how they are proven not to false-positive.

> **This layer already caught a real latent bug.** Turned on, the tripwire fired during teardown:
> `onDestroy()` cleared the playlist but left `playlistPosition`/`audioPlayer` non-reset, so a
> duration report still queued from `prepare()` (`onTrackDurationResolved`) republished against a
> cleared queue — and would have run `hwListener.setTrackMetadata()` against an already-released
> `MediaSession`. The identity guard was *meant* to drop that stale report but couldn't, because the
> field wasn't nulled. Fixed by nulling `audioPlayer` in `onDestroy()`; pinned by
> `staleDurationReportAfterDestroy_isDroppedNotRepublished` in the Step-2 suite.

If you add or reorder boundary state mutations, keep the debug asserts honest: they must hold at every
`sendPlaybackStateBroadcast()` call. Genuinely-transient teardown states belong behind the
`destroyed` guard, not asserted.

## When you touch the boundary code

1. Update `PlayerModel.apply()` to mirror the change; run Step 3 — a new invariant violation is a
   design bug or a spec you need to revise.
2. Add/adjust a Step-2 case for the concrete scenario.
3. Keep the Step-5 asserts holding at every `sendPlaybackStateBroadcast()`; put real teardown-only
   transients behind the `destroyed` guard.
4. `./gradlew testDebugUnitTest` must be green, **including the mutation self-tests**.
