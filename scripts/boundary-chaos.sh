#!/usr/bin/env bash
#
# Step 4 — on-device track-boundary chaos harness.
#
# Drives the REAL app on a USB-connected phone via ADB, forcing track boundaries on demand
# (seek-to-near-end) and firing boundary-relevant events — Stop/Resume around the fade, add-a-track-
# directly-below-current, reorder — at/near those boundaries. It relies on the app's debug-only chaos
# receiver (Service.java, BuildConfig.DEBUG) to translate commands into the exact intents the UI sends.
#
# Detection is delegated to the Step-5 debug tripwire: any boundary-invariant violation crashes the
# debug build with "Boundary invariant violated" in logcat, which this script watches for (plus any
# FATAL EXCEPTION and process death). Every command and scenario is echoed into logcat, so a failing
# run's tail is a self-contained repro. Re-run with the same seed to reproduce.
#
# Prereqs: debug APK installed; a queue already loaded in the app (>= a few tracks); screen on.
# Usage:   scripts/boundary-chaos.sh [SEED] [ITERATIONS]
set -u

PKG=com.shaforostoff.livequeueplayer
CHAOS="$PKG.CHAOS"
SEED="${1:-1}"
ITERS="${2:-40}"
FADES=(1 3 5 8 10)                 # fade-out lengths to sweep (seconds); app max is 10
LOG="$(mktemp -t lqp-chaos-XXXX.log)"

# --- adb plumbing ----------------------------------------------------------------------------------
c() { # chaos command: c <cmd> [arg] [arg2]
  adb shell am broadcast -a "$CHAOS" -p "$PKG" --es cmd "$1" \
      ${2:+--ei arg "$2"} ${3:+--ei arg2 "$3"} >/dev/null 2>&1
}
dbg()      { adb shell log -p i -t LqpHarness "$*" >/dev/null 2>&1; }   # annotate the logcat trace
mkey()     { adb shell input keyevent "$1" >/dev/null 2>&1; }
app_pid()  { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }
sleep_ms() { sleep "$(awk "BEGIN{print $1/1000}")"; }

# --- status readback -------------------------------------------------------------------------------
S_PLAYING=""; S_DUR=0; S_IDX=-1; S_SIZE=0; S_PENDING=""; S_HASPLAYER=""; S_VOL=-1; S_BASE=-1
read_status() {
  c status
  local line=""
  for _ in 1 2 3 4 5 6; do
    sleep_ms 350
    line="$(adb logcat -d -s LqpChaos:I 2>/dev/null | grep 'STATUS' | tail -1 | tr -d '\r')"
    [ -n "$line" ] && break
  done
  S_PLAYING=$(sed -n 's/.* playing=\([a-z]*\).*/\1/p'   <<<"$line")
  S_PENDING=$(sed -n 's/.* pending=\([a-z]*\).*/\1/p'   <<<"$line")
  S_HASPLAYER=$(sed -n 's/.* hasPlayer=\([a-z]*\).*/\1/p' <<<"$line")
  S_DUR=$(sed -n  's/.* dur=\(-\{0,1\}[0-9]*\).*/\1/p'  <<<"$line")
  S_IDX=$(sed -n  's/.* idx=\(-\{0,1\}[0-9]*\).*/\1/p'  <<<"$line")
  S_SIZE=$(sed -n 's/.* size=\([0-9]*\).*/\1/p'         <<<"$line")
  S_VOL=$(sed -n  's/.* vol=\(-\{0,1\}[0-9.]*\).*/\1/p' <<<"$line"); S_VOL=${S_VOL:--1}
  S_BASE=$(sed -n 's/.* base=\(-\{0,1\}[0-9.]*\).*/\1/p' <<<"$line"); S_BASE=${S_BASE:--1}
  S_DUR=${S_DUR:-0}; S_IDX=${S_IDX:--1}; S_SIZE=${S_SIZE:-0}
}

# Detect the 5da8d45 "silent playback" regression: playing, but the live gain sits well below the
# track's base gain (a resume clobbered by a stale fade step). Returns 1 (fail) if silent.
check_not_silent() {
  read_status
  awk -v p="$S_PLAYING" -v d="$S_DUR" -v v="$S_VOL" -v b="$S_BASE" \
    'BEGIN{ if(p=="true" && d>0 && b>0 && v>=0 && v < b*0.5){ exit 1 } exit 0 }'
}

ensure_playing() {
  local try
  for try in 1 2 3; do
    read_status
    if [ "$S_PLAYING" = "true" ] && [ "$S_DUR" -gt 0 ]; then return 0; fi
    c play; mkey 126; sleep_ms 2000          # Launcher.PLAY (playFromQueueStore) + MEDIA_PLAY fallback
  done
  read_status
  [ "$S_PLAYING" = "true" ] && [ "$S_DUR" -gt 0 ]
}

# --- health / detection ----------------------------------------------------------------------------
BASE_VIOL=0; BASE_FATAL=0
count() { grep -c "$1" "$LOG" 2>/dev/null | tr -d '\r'; }
health_check() {
  local viol fatal; viol=$(count "Boundary invariant violated"); fatal=$(count "FATAL EXCEPTION")
  if [ "${viol:-0}" -gt "$BASE_VIOL" ] || [ "${fatal:-0}" -gt "$BASE_FATAL" ] || [ -z "$(app_pid)" ]; then
    echo
    echo "################  FAILURE DETECTED  ################"
    [ "${viol:-0}"  -gt "$BASE_VIOL" ]  && echo "  Step-5 boundary invariant violated"
    [ "${fatal:-0}" -gt "$BASE_FATAL" ] && echo "  app crashed (FATAL EXCEPTION)"
    [ -z "$(app_pid)" ] && echo "  app process died"
    echo "  reproduce with: scripts/boundary-chaos.sh $SEED $ITERS"
    echo "--- logcat tail (scenario + commands + failure) ---"
    grep -E "LqpHarness: SCENARIO|LqpChaos: CMD|Boundary invariant violated|FATAL EXCEPTION|AndroidRuntime" "$LOG" | tail -50
    echo "  full log: $LOG"
    return 1
  fi
  return 0
}

# --- scenarios -------------------------------------------------------------------------------------

# Stop pressed close to the boundary, then Resume — swept across fade length and resume timing so the
# fade (up to 10s) outlasts the natural end, exercising onMediaPlayerComplete's fade guard + resume.
scen_stop_resume() {
  local fade=$1 lead=$2 stopWaitMs=$3 resumeWaitMs=$4
  ensure_playing || return 0
  # Operate on a RANDOM track each run (not always the first). PLAY_FROM_QUEUE_INDEX also stamps the
  # persisted playback offset, so a resume-after-stop (playFromQueueStore) lands on this track too.
  read_status
  local n=$(( RANDOM % (S_SIZE > 0 ? S_SIZE : 1) ))
  dbg "SCENARIO stop_resume fade=${fade}s lead=${lead}ms stopWait=${stopWaitMs}ms resumeWait=${resumeWaitMs}ms track=$n/$S_SIZE"
  c play_index "$n"
  local t; for t in 1 2 3 4 5; do sleep_ms 900; read_status; [ "$S_DUR" -gt 0 ] && break; done
  c set_fade "$fade"
  c seek_lead "$lead"          # boundary arrives in ~max(1000,lead) ms (app clamps seek to dur-1000)
  sleep_ms "$stopWaitMs"       # press Stop close to the boundary
  c stop
  sleep_ms "$resumeWaitMs"     # Resume during the fade / as it ends / after full stop
  c resume
  sleep_ms 1500
}

# A track added directly below the current one, close to the boundary; optionally let the boundary
# fire so playback advances into the freshly-inserted track.
scen_add_below() {
  local lead=$1 letComplete=$2
  dbg "SCENARIO add_below lead=${lead}ms letComplete=${letComplete}"
  ensure_playing || return 0
  c seek_lead "$lead"
  sleep_ms $(( lead > 1200 ? lead - 700 : 500 ))   # get close to the boundary
  c add_below
  [ "$letComplete" = 1 ] && sleep_ms 2500          # allow auto-advance into the inserted track
  sleep_ms 1000
}

# Reorder the pending tracks close to the boundary (ensures >=2 pending first).
scen_reorder() {
  local lead=$1
  dbg "SCENARIO reorder lead=${lead}ms"
  ensure_playing || return 0
  read_status
  if [ $(( S_SIZE - S_IDX - 1 )) -lt 2 ]; then c add_below; c add_below; sleep_ms 800; fi
  c seek_lead "$lead"
  sleep_ms 500
  c reorder 0 1                # move first pending track down one slot, right at the boundary
  sleep_ms 1500
}

# Jump to a random track in the queue, right at a boundary (real PLAY_FROM_QUEUE_INDEX).
scen_play_random() {
  local lead=$1
  ensure_playing || return 0
  read_status
  local n=$(( RANDOM % (S_SIZE > 0 ? S_SIZE : 1) ))
  dbg "SCENARIO play_random lead=${lead}ms index=$n of $S_SIZE"
  c seek_lead "$lead"
  sleep_ms 500
  c play_index "$n"            # replaces current playback with a random track
  sleep_ms 2000
}

# Remove a random pending (below-current) track at the boundary. (Removing a random track ABOVE the
# current one is a receiver-side op only reachable over the remote link — see bluetooth-boundary-chaos.sh.)
scen_remove_below() {
  local lead=$1
  ensure_playing || return 0
  read_status
  local pend=$(( S_SIZE - S_IDX - 1 ))
  if [ "$pend" -lt 1 ]; then c add_below; c add_below; sleep_ms 800; read_status; pend=$(( S_SIZE - S_IDX - 1 )); fi
  [ "$pend" -lt 1 ] && return 0
  local k=$(( RANDOM % pend ))
  dbg "SCENARIO remove_below lead=${lead}ms k=$k of $pend pending"
  c seek_lead "$lead"
  sleep_ms 500
  c remove_pending "$k"
  sleep_ms 1500
}

report_silent() {
  echo
  echo "################  FAILURE DETECTED  ################"
  echo "  SILENT PLAYBACK after resume (regression of 5da8d45): playing but vol=$S_VOL << base=$S_BASE"
  echo "  reproduce: scripts/boundary-chaos.sh $SEED $ITERS"
  grep -E "LqpHarness: SCENARIO|LqpChaos: (CMD|STATUS)" "$LOG" | tail -25
  echo "  full log: $LOG"
}

# --- main ------------------------------------------------------------------------------------------
if [ -z "$(adb get-state 2>/dev/null)" ]; then echo "No device via adb. Connect the phone."; exit 2; fi
if ! adb shell pm list packages 2>/dev/null | grep -q "$PKG"; then echo "$PKG not installed."; exit 2; fi

echo "Boundary chaos: seed=$SEED iterations=$ITERS  (log: $LOG)"
adb shell am startservice -n "$PKG/.Service" >/dev/null 2>&1   # ensure Service (+ chaos receiver) is up
sleep_ms 800
adb logcat -c
adb logcat -v time > "$LOG" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null' EXIT
sleep_ms 500

if ! ensure_playing; then
  echo "Could not start playback. Open the app, load a folder/queue (>= 3 tracks), press play, re-run."
  exit 2
fi
read_status
echo "Playing: idx=$S_IDX size=$S_SIZE dur=${S_DUR}ms"
[ "$S_SIZE" -lt 3 ] && echo "WARNING: only $S_SIZE tracks queued; load more for broader coverage."

RANDOM=$SEED
for ((i=1; i<=ITERS; i++)); do
  fade=${FADES[$(( RANDOM % ${#FADES[@]} ))]}
  lead=$(( 1000 + RANDOM % 1500 ))          # 1.0s .. 2.5s before end
  pick=$(( RANDOM % 6 ))
  case $pick in
    0|1) # stop-near-boundary then resume; sweep resume timing vs the fade window
      stopWait=$(( 250 + RANDOM % 800 ))
      r=$(( RANDOM % 3 ))
      if   [ $r -eq 0 ]; then resumeWait=$(( fade * 300 ));          desc="resume-during-fade"
      elif [ $r -eq 1 ]; then resumeWait=$(( fade * 1000 ));         desc="resume-at-fade-end"
      else                    resumeWait=$(( fade * 1000 + 1500 ));  desc="resume-after-stop"; fi
      echo "[$i/$ITERS] stop_resume fade=${fade}s lead=${lead}ms $desc"
      scen_stop_resume "$fade" "$lead" "$stopWait" "$resumeWait"
      check_not_silent || { report_silent; exit 1; } ;;
    2)
      lc=$(( RANDOM % 2 ))
      echo "[$i/$ITERS] add_below lead=${lead}ms letComplete=$lc"
      scen_add_below "$lead" "$lc" ;;
    3)
      echo "[$i/$ITERS] reorder lead=${lead}ms"
      scen_reorder "$lead" ;;
    4)
      echo "[$i/$ITERS] play_random lead=${lead}ms"
      scen_play_random "$lead" ;;
    5)
      echo "[$i/$ITERS] remove_below lead=${lead}ms"
      scen_remove_below "$lead" ;;
  esac
  health_check || exit 1
done

echo
echo "PASS: $ITERS iterations, no boundary-invariant violations / crashes (seed $SEED)."
echo "full log: $LOG"
