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

Two more layers run **on the phone over USB**: **Step 5 — debug runtime assertions** ships those same
invariants inside the app (debug builds only), and **Step 4 — the on-device chaos harness** drives the
real app to force track boundaries and fire events at them, using Step 5 as its detector. See their
sections below.

> Not implemented here: Step 1 (instrumented / UI Automator), tracked separately.

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

The on-device chaos harnesses (Step 4) are separate — they need the phone(s):

```bash
./gradlew assembleDebug
./gradlew installDebug

scripts/boundary-chaos.sh [SEED] [ITERATIONS]              # single phone
scripts/bluetooth-boundary-chaos.sh [SEED] [ITERATIONS]   # two phones over Bluetooth
```

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

**Regression tests for specific fixes.** Both halves of 5da8d45 ("fade-out volume race leaving
playback silent") are pinned here:
- `TrackBoundaryTest.newTrackDuringFade_replacesFadingPlayer_notAppended` — the Service half: a track
  started while the current player is fading must *replace* it (keyed off the authoritative
  `isFadeOutInProgress()`), not be appended after the faded-to-silent one.
- `boundary/FadeVolumeRaceTest` — the AudioPlayer half, as a pure-JVM model of the fade-vs-resume
  locking protocol: with the shared `fadeLock` a resume always ends at `baseGain`; its mutation
  self-test proves the pre-fix (unsynchronized) protocol could end silent, so the test has teeth.

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

## Step 4 — on-device chaos harness (boundary on demand)

`scripts/boundary-chaos.sh` drives the **real app on a USB-connected phone**, forcing track
boundaries on demand and firing boundary-relevant events at them. Detection is delegated to the
Step-5 tripwire: a violation crashes the debug build with `Boundary invariant violated` in logcat,
which the script watches for (plus any `FATAL EXCEPTION` and process death). Every command and
scenario is echoed into logcat via `log -t`, so a failing run's tail is a self-contained repro, and
the run is seeded — same `SEED` → same sequence.

**The app-side hook (`Service`, debug builds only).** A `BroadcastReceiver` on the `CHAOS_ACTION`,
registered under `BuildConfig.DEBUG` and stripped from release, turns am-friendly string commands
into the *exact* intents the UI sends (`SET_PENDING_QUEUE`, `STOP`, `PLAY`, `SEEK`, …) and dispatches
them through the real `Service` — so the harness exercises production code paths, not shims. `am`
cannot send the byte / `Uri[]` extras those intents use, which is why the in-app translator exists.

Commands: `status`, `set_fade <1..10>`, `seek_lead <ms>` (seek to `ms` before the end — the boundary
generator), `stop`/`resume`/`play`/`pause`/`play_pause`/`skip`/`kill`, `add_below` (insert a track
directly below the current one), `reorder <from> <to>` (within the pending sublist), `play_index <n>`
(jump to a random track), `remove_pending <k>` (remove a below-current track), `clear_played`.
`status` also reports the live gain (`vol=`/`base=`) and the queue ids above and below the current
track (`aboveIds=`/`pendingIds=`). Drive one by hand with:

```bash
adb shell am broadcast -a com.shaforostoff.livequeueplayer.CHAOS \
    -p com.shaforostoff.livequeueplayer --es cmd seek_lead --ei arg 1500
```

**Scenarios (seeded mix), all fired close to a forced boundary:**
- **Stop → Resume**, swept across every fade length (1–10s) and three resume timings — *during* the
  fade, *as it ends*, and *after* a full stop. Because the fade can outlast the track's natural end,
  this is what stresses `onMediaPlayerComplete`'s fade-vs-completion guard and the resume paths.
- **Add-below-current** — a track inserted directly under the playing one via the real
  `SET_PENDING_QUEUE` path, optionally letting the boundary fire so playback advances into it.
- **Reorder** — moving a pending track at the boundary (also via `SET_PENDING_QUEUE`).
- **Play-random** — jump to a random queue track at the boundary (real `PLAY_FROM_QUEUE_INDEX`).
- **Remove-below** — remove a random pending track at the boundary. (Removing a random track *above*
  the current one is a receiver-side op reachable only over the remote link — see the Bluetooth
  harness; the Service has no single-above-remove intent.)

After every stop→resume the harness also runs a **silent-playback check** (`vol` vs `base` from
`status`) — the on-device regression detector for 5da8d45: if a resume ends with the track playing
but near-zero gain, it fails with the reproducing seed.

**Why "boundary on demand" works.** `seek_lead` seeks to ~1–2.5s before the end, so a boundary
arrives in ~1s instead of minutes. Sub-100ms placement isn't achievable over ADB, but it isn't
needed: the fade (up to 10s) *widens* the race window enormously — with a 10s fade and ~1s to the
end, completion fires ~9s inside the fade every time. (`Service.seekTo` clamps to `duration − 1000`,
so the minimum effective lead is ~1s — a real app behavior, tested as-is.)

**Verified.** Runs on the connected Xperia 10 V (Android 15) forced 50 boundaries over 33 iterations
(seeds 1, 42), advancing the current track through several tracks, with zero violations/crashes.

**Prereqs & caveats.**
- Debug APK installed (`./gradlew :app:assembleDebug && adb install -r …`); screen on; a queue
  already loaded in the app (the harness bootstraps the Service with `am startservice` and starts
  playback from the persisted queue, but it can't load a folder for you — do that once in the UI).
- **It operates on your live persisted queue.** Resume-after-stop reloads from the persisted playback
  offset, so each such cycle advances the offset and shrinks the effective queue. For long runs, load
  a disposable test queue rather than your real one.
- It sweeps the **fade-out preference** (1–10s). The script restores it toward the default (5s) via a
  final command, but if interrupted, reset it in Settings.
- The chaos receiver is **exported in debug builds** so `adb` can reach it — fine for a test device,
  and absent from release entirely.

## Step 4 (Bluetooth) — two-phone chaos harness

`scripts/bluetooth-boundary-chaos.sh` drives the **remote-queue feature across two USB-connected
phones**: a **receiver** (remote_receive — hosts playback) and a **sender** (remote_send — controls
it over classic Bluetooth). It forces boundaries on the receiver and makes the sender fire **real
remote commands** that arrive over the BT link right at the receiver's boundary — the genuine race,
where the BT read thread delivers a command onto the receiver's main thread while its Service is
auto-advancing. Detection is again the receiver's Step-5 tripwire (+ FATAL EXCEPTION / process death
on **both** phones).

**App-side hooks (debug only).** The sender gets a relay in `App` (`CHAOS_BT` receiver) that emits
real remote-queue JSON over the **live app-scoped socket** — the exact path the send-mode UI uses.
The receiver reuses its `Service` `CHAOS` hook, with `status` extended to report the BT link state
and the pending tracks' stable ids (`bt=…`, `pendingIds=[…]`) so the harness can target `move_track`
/ `remove_track`. On the receiver, `stop_playback`/`resume_playback` map to the *same*
`stopPlaybackWithFadeout()` / `cancelFadeOutAndContinue()` paths as the local Stop/Resume — so this
reuses all of Step 5.

**Scenarios (seeded), each at a forced boundary:**
- **Remote Stop → Resume** across every fade length (1–10s) × resume-timing (during / at-end / after).
- **Remote move-below-current** — `move_track` to just under the playing track (reorder / "appears
  below current"), via the receiver's real `SET_PENDING_QUEUE` sync.
- **Remote remove** of a random track **above or below** the current one (`remove_track` by id — the
  receiver resolves it by position, so either side works).
- **Remote play-random** — Stop (play_track is honored only when stopped), then remote-jump to a
  random queue track by id.

After remote stop→resume the receiver's `vol`/`base` are checked for the same **silent-playback
regression** (5da8d45) as the single-phone harness.

**Prerequisite the harness can't script.** The two-phone link needs the UI: the queue-host activity
is non-exported (so `adb` can't launch the remote modes) and BT pairing/device-selection is
interactive. Establish it once by hand — **phone1: remote receive; phone2: remote send → connect** —
and load/start a queue on phone1. The harness auto-detects roles by model (override with
`RECV_SERIAL=` / `SEND_SERIAL=`), then **verifies the link is up and refuses to run without it**
(reinstalling the debug APK drops the link, so re-establish after any reinstall).

**Status.** App hooks built and verified responding on both phones (Xperia 10 V / A15 receiver,
XQ-BQ52 / A13 sender); the harness resolves roles, bootstraps receiver playback, and gates on a live
link. A full seeded run requires the manually-established BT connection described above.

## When you touch the boundary code

1. Update `PlayerModel.apply()` to mirror the change; run Step 3 — a new invariant violation is a
   design bug or a spec you need to revise.
2. Add/adjust a Step-2 case for the concrete scenario.
3. Keep the Step-5 asserts holding at every `sendPlaybackStateBroadcast()`; put real teardown-only
   transients behind the `destroyed` guard.
4. `./gradlew testDebugUnitTest` must be green, **including the mutation self-tests**.
5. For device-level confidence, run `scripts/boundary-chaos.sh` on a phone with a disposable queue.
