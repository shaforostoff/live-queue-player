#!/usr/bin/env bash
#
# Step 4 (Bluetooth) — two-phone track-boundary chaos harness.
#
# Drives the real remote-queue feature across two USB-connected phones. The RECEIVER phone hosts
# playback (remote_receive mode); the SENDER phone (remote_send mode) controls it over classic
# Bluetooth. The harness forces boundaries on the receiver (seek-to-near-end) and makes the sender
# fire real remote commands — Stop/Resume, move-below-current (reorder), remove — that arrive over
# the BT link right at the receiver's track boundary.
#
# Detection is the receiver's Step-5 debug tripwire (a boundary-invariant violation crashes the
# debug build with "Boundary invariant violated" in logcat); the harness also watches for any
# FATAL EXCEPTION / process death on BOTH phones. Seeded => reproducible.
#
# App-side hooks (debug builds only): receiver Service `CHAOS` receiver (seek_lead/status/…), and
# the sender App `CHAOS_BT` relay that emits real remote-queue JSON over the live socket.
#
# PREREQUISITE (not scriptable — pairing + a non-exported UI): establish the link yourself first —
#   phone 1: open the app -> "remote receive"          (starts the BT server, hosts playback)
#   phone 2: open the app -> "remote send" -> connect to phone 1
# then load/start a queue on phone 1. The harness verifies the link and refuses to run without it.
#
# Usage:   scripts/bluetooth-boundary-chaos.sh [SEED] [ITERATIONS]
#   Device roles auto-detect by model (receiver=DC54, sender=BQ52); override with
#   RECV_SERIAL=... SEND_SERIAL=...  (or RECV_MODEL=... SEND_MODEL=...).
set -u

PKG=com.shaforostoff.livequeueplayer
CHAOS_RX="$PKG.CHAOS"      # receiver Service hook
CHAOS_TX="$PKG.CHAOS_BT"   # sender App relay
SEED="${1:-1}"; ITERS="${2:-30}"
FADES=(1 3 5 8 10)

serial_for_model() {
  local want="$1" s m
  for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
    m="$(adb -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
    case "$m" in *"$want"*) echo "$s"; return;; esac
  done
}
RECV="${RECV_SERIAL:-$(serial_for_model "${RECV_MODEL:-DC54}")}"
SEND="${SEND_SERIAL:-$(serial_for_model "${SEND_MODEL:-BQ52}")}"

RX_LOG="$(mktemp -t lqp-bt-recv-XXXX.log)"
TX_LOG="$(mktemp -t lqp-bt-send-XXXX.log)"

# --- plumbing --------------------------------------------------------------------------------------
rx() { adb -s "$RECV" shell am broadcast -a "$CHAOS_RX" -p "$PKG" --es cmd "$1" ${2:+--ei arg "$2"} >/dev/null 2>&1; }
tx() { adb -s "$SEND" shell am broadcast -a "$CHAOS_TX" -p "$PKG" --es cmd "$1" ${2:+--ei id "$2"} ${3:+--ei to "$3"} >/dev/null 2>&1; }
dbg()      { adb -s "$RECV" shell log -p i -t LqpHarness "$*" >/dev/null 2>&1
             adb -s "$SEND" shell log -p i -t LqpHarness "$*" >/dev/null 2>&1; }
sleep_ms() { sleep "$(awk "BEGIN{print $1/1000}")"; }
pid_of()   { adb -s "$1" shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }

# receiver playback + link state
RX_PLAYING=""; RX_DUR=0; RX_IDX=-1; RX_SIZE=0; RX_BT=""; RX_IDS=()
read_status() {
  rx status
  local line=""
  for _ in 1 2 3 4 5 6; do
    sleep_ms 350
    line="$(adb -s "$RECV" logcat -d -s LqpChaos:I 2>/dev/null | grep 'STATUS' | tail -1 | tr -d '\r')"
    [ -n "$line" ] && break
  done
  RX_PLAYING=$(sed -n 's/.* playing=\([a-z]*\).*/\1/p' <<<"$line")
  RX_DUR=$(sed -n  's/.* dur=\(-\{0,1\}[0-9]*\).*/\1/p' <<<"$line"); RX_DUR=${RX_DUR:-0}
  RX_IDX=$(sed -n  's/.* idx=\(-\{0,1\}[0-9]*\).*/\1/p' <<<"$line"); RX_IDX=${RX_IDX:--1}
  RX_SIZE=$(sed -n 's/.* size=\([0-9]*\).*/\1/p' <<<"$line"); RX_SIZE=${RX_SIZE:-0}
  RX_BT=$(sed -n 's/.* bt=\([^ ]*\).*/\1/p' <<<"$line")
  local ids; ids=$(sed -n 's/.*pendingIds=\[\([0-9,]*\)\].*/\1/p' <<<"$line")
  IFS=',' read -ra RX_IDS <<<"$ids"
}
# sender BT link state
TX_CONN=""
read_bt() {
  tx btstatus
  local line=""
  for _ in 1 2 3 4 5; do
    sleep_ms 350
    line="$(adb -s "$SEND" logcat -d -s LqpChaosBt:I 2>/dev/null | grep 'BTSTATUS' | tail -1 | tr -d '\r')"
    [ -n "$line" ] && break
  done
  TX_CONN=$(sed -n 's/.* connected=\([a-z]*\).*/\1/p' <<<"$line")
}

ensure_playing() {
  local t
  for t in 1 2 3 4; do
    read_status
    if [ "$RX_PLAYING" = "true" ] && [ "$RX_DUR" -gt 0 ]; then return 0; fi
    rx play; adb -s "$RECV" shell input keyevent 126 >/dev/null 2>&1; sleep_ms 3500
  done
  read_status
  [ "$RX_PLAYING" = "true" ] && [ "$RX_DUR" -gt 0 ]
}

BASE_RXV=0; BASE_RXF=0; BASE_TXF=0
cnt() { grep -c "$2" "$1" 2>/dev/null | tr -d '\r'; }
health_check() {
  local rxv rxf txf
  rxv=$(cnt "$RX_LOG" "Boundary invariant violated")
  rxf=$(cnt "$RX_LOG" "FATAL EXCEPTION")
  txf=$(cnt "$TX_LOG" "FATAL EXCEPTION")
  if [ "${rxv:-0}" -gt "$BASE_RXV" ] || [ "${rxf:-0}" -gt "$BASE_RXF" ] || [ "${txf:-0}" -gt "$BASE_TXF" ] \
     || [ -z "$(pid_of "$RECV")" ] || [ -z "$(pid_of "$SEND")" ]; then
    echo
    echo "################  FAILURE DETECTED  ################"
    [ "${rxv:-0}" -gt "$BASE_RXV" ] && echo "  receiver Step-5 boundary invariant violated"
    [ "${rxf:-0}" -gt "$BASE_RXF" ] && echo "  receiver crashed (FATAL EXCEPTION)"
    [ "${txf:-0}" -gt "$BASE_TXF" ] && echo "  sender crashed (FATAL EXCEPTION)"
    [ -z "$(pid_of "$RECV")" ] && echo "  receiver process died"
    [ -z "$(pid_of "$SEND")" ] && echo "  sender process died"
    echo "  reproduce: scripts/bluetooth-boundary-chaos.sh $SEED $ITERS"
    echo "--- receiver logcat tail ---"
    grep -E "LqpHarness: SCENARIO|LqpChaos: CMD|Boundary invariant violated|FATAL EXCEPTION" "$RX_LOG" | tail -40
    echo "--- sender logcat tail ---"
    grep -E "LqpHarness: SCENARIO|LqpChaosBt: (SEND|BTSTATUS)|FATAL EXCEPTION" "$TX_LOG" | tail -20
    echo "  logs: $RX_LOG  $TX_LOG"
    return 1
  fi
  return 0
}

# pick a pending track id further down the queue (so moving it below current is a real reorder)
pending_id() { local n=${#RX_IDS[@]}; [ "$n" -ge 3 ] && echo "${RX_IDS[2]}" || { [ "$n" -ge 1 ] && echo "${RX_IDS[$((n-1))]}"; }; }

# --- scenarios (all fired close to a forced boundary on the receiver) ------------------------------

# Remote Stop close to the boundary, then remote Resume — swept over fade length + resume timing.
scen_remote_stop_resume() {
  local fade=$1 lead=$2 stopWaitMs=$3 resumeWaitMs=$4
  ensure_playing || return 0
  # Start each run on a RANDOM track (also sets the resume-from-store offset, so the after-stop
  # remote resume lands on this track rather than always the first one).
  read_status
  local n=$(( RANDOM % (RX_SIZE > 0 ? RX_SIZE : 1) ))
  dbg "SCENARIO bt_stop_resume fade=${fade}s lead=${lead}ms stopWait=${stopWaitMs}ms resumeWait=${resumeWaitMs}ms track=$n/$RX_SIZE"
  rx play_index "$n"          # jump on the receiver (host owns playback)
  local t; for t in 1 2 3 4 5; do sleep_ms 900; read_status; [ "$RX_DUR" -gt 0 ] && break; done
  rx set_fade "$fade"
  rx seek_lead "$lead"        # boundary in ~max(1000,lead) ms
  sleep_ms "$stopWaitMs"
  tx stop_playback            # remote Stop arrives over BT close to the boundary
  sleep_ms "$resumeWaitMs"
  tx resume_playback          # remote Resume during the fade / as it ends / after full stop
  sleep_ms 1800
}

# Remote move-a-track-directly-below-current at the boundary (reorder / "appears below current").
scen_remote_move_below() {
  local lead=$1
  dbg "SCENARIO bt_move_below lead=${lead}ms"
  ensure_playing || return 0
  read_status
  local id; id=$(pending_id)
  [ -z "$id" ] && return 0
  rx seek_lead "$lead"
  sleep_ms 500
  tx move_track "$id" $(( RX_IDX + 1 ))   # to_position just below the current track
  sleep_ms 1800
}

# Remote remove of a pending track at the boundary.
scen_remote_remove() {
  local lead=$1
  dbg "SCENARIO bt_remove lead=${lead}ms"
  ensure_playing || return 0
  read_status
  local id; id=$(pending_id)
  [ -z "$id" ] && return 0
  rx seek_lead "$lead"
  sleep_ms 500
  tx remove_track "$id"
  sleep_ms 1800
}

# --- main ------------------------------------------------------------------------------------------
[ -z "$RECV" ] && { echo "Receiver device not found (set RECV_SERIAL=...)."; exit 2; }
[ -z "$SEND" ] && { echo "Sender device not found (set SEND_SERIAL=...)."; exit 2; }
[ "$RECV" = "$SEND" ] && { echo "Receiver and sender resolved to the same device; set RECV_SERIAL/SEND_SERIAL."; exit 2; }
echo "Receiver = $RECV   Sender = $SEND   (seed=$SEED iters=$ITERS)"
echo "logs: $RX_LOG  $TX_LOG"

adb -s "$RECV" shell am startservice -n "$PKG/.Service" >/dev/null 2>&1
sleep_ms 700
adb -s "$RECV" logcat -c; adb -s "$SEND" logcat -c
adb -s "$RECV" logcat -v time > "$RX_LOG" 2>&1 & RXPID=$!
adb -s "$SEND" logcat -v time > "$TX_LOG" 2>&1 & TXPID=$!
trap 'kill "$RXPID" "$TXPID" 2>/dev/null' EXIT
sleep_ms 500

ensure_playing || { echo "Receiver is not playing. Load/start a queue on it, then re-run."; exit 2; }
read_status; read_bt
echo "Receiver: idx=$RX_IDX size=$RX_SIZE dur=${RX_DUR}ms link[$RX_BT]   Sender: connected=$TX_CONN"
if [ "$TX_CONN" != "true" ] || [[ "$RX_BT" != *connected=true* ]]; then
  echo
  echo "Bluetooth link is DOWN. Establish it via the UI, then re-run:"
  echo "  phone1 ($RECV): app -> remote receive"
  echo "  phone2 ($SEND): app -> remote send -> connect to phone1"
  exit 2
fi

RANDOM=$SEED
for ((i=1; i<=ITERS; i++)); do
  fade=${FADES[$(( RANDOM % ${#FADES[@]} ))]}
  lead=$(( 1000 + RANDOM % 1500 ))
  case $(( RANDOM % 4 )) in
    0|1) # remote stop/resume — the primary scenario, sweeping fade + resume timing
      stopWait=$(( 250 + RANDOM % 800 ))
      case $(( RANDOM % 3 )) in
        0) resumeWait=$(( fade * 300 ));         d=resume-during-fade ;;
        1) resumeWait=$(( fade * 1000 ));        d=resume-at-fade-end ;;
        *) resumeWait=$(( fade * 1000 + 1500 )); d=resume-after-stop ;;
      esac
      echo "[$i/$ITERS] bt_stop_resume fade=${fade}s lead=${lead}ms $d"
      scen_remote_stop_resume "$fade" "$lead" "$stopWait" "$resumeWait" ;;
    2)
      echo "[$i/$ITERS] bt_move_below lead=${lead}ms"
      scen_remote_move_below "$lead" ;;
    3)
      echo "[$i/$ITERS] bt_remove lead=${lead}ms"
      scen_remote_remove "$lead" ;;
  esac
  health_check || exit 1
done

echo
echo "PASS: $ITERS iterations, no boundary-invariant violations / crashes (seed $SEED)."
echo "logs: $RX_LOG  $TX_LOG"
