#!/usr/bin/env bash
# Copyright 2026 Damien Dye
# SPDX-License-Identifier: Apache-2.0
#
# Scripted bench drives (TESTING.md §3 step 5, "Scenarios"). Each one runs tools/drive.py with the
# realism options, drives the car screen through the debug BenchReceiver where the scenario needs it,
# and prints the TESTING.md rows and the debug-nav / alert-debug lines to look for (§7). The test
# cameras are invented, on real roads near the A61 bench route (Clay Cross – Stretton – Higham) so
# the OSM limits still apply: tools/scenarios/<name>.gpx, plus wrong-way.csv for the both-ways camera.
# Import them on the phone's POIs tab first (the script pushes them to the emulator's Download folder).
#
#   tools/scenario.sh parked-then-go   # A16, M20: park 60 s with noise, preview, Go, drive 4.9 km
#   tools/scenario.sh bend-camera      # A17: a 90° bend with a camera 80 m past the apex, 30 then 50 mph, --noise 5
#   tools/scenario.sh wrong-way        # A18: a ONE_WAY camera driven against its bearing, then a BOTH camera; then back
#   tools/scenario.sh crawl            # A19: 3 mph and 6 mph past a camera
#   tools/scenario.sh handover         # C7: car feed on (DHU on default_sensors), dropping out 5 s every 60 s
#
# Needs the bench up (tools/bench.sh all; "car default_sensors" for handover) with the car screen on
# AutoNavAlerts, and a debug build for the BENCH broadcasts. SCENARIO_DRY_RUN=1 prints the commands
# instead of running them. drive.py's output goes to $TMPDIR/autonav-bench/drive.log, its pid to drive.pid.
set -euo pipefail
cd "$(dirname "$0")/.."

ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
ADB=$ANDROID_HOME/platform-tools/adb
LOGDIR=${TMPDIR:-/tmp}/autonav-bench
mkdir -p "$LOGDIR"
DRY=${SCENARIO_DRY_RUN:-}
PKG=uk.damiendye.autonav

# The A61 south of Clay Cross (single carriageway, 30/40/50 mph), lon,lat. CLAY is on the A61 just south
# of the Holmgate Road junction; MID is 1.7 km south on the straight; HIGHAM is at the Higham end (4.9 km).
CLAY="-1.41555,53.16479"
MID="-1.41305,53.14987"
HIGHAM="-1.40781,53.12542"
# The bend: A61 southbound from the north end of the straight, left onto the B6014 Morton Road, east to Stretton Road.
BEND_FROM="-1.41446,53.15729"
BEND_TO="-1.40246,53.14066"

run() { if [ -n "$DRY" ]; then echo "+ $*" >&2; else "$@"; fi; }
pause() { echo "… ${2:-waiting} $1 s"; [ -n "$DRY" ] || sleep "$1"; }
bench() { echo "+ bench: $*"; run "$ADB" -e shell am broadcast -a $PKG.BENCH -p $PKG "$@"; }

# drive.py in the background, logging like bench.sh does; wait_drive blocks until the lap is done.
drive() {
  echo "+ drive.py $*"
  [ -n "$DRY" ] && return
  nohup python3 -u tools/drive.py "$@" >>"$LOGDIR/drive.log" 2>&1 &
  echo $! >"$LOGDIR/drive.pid"
}
wait_drive() {
  [ -n "$DRY" ] && return
  local pid; pid=$(cat "$LOGDIR/drive.pid")
  # A signal makes "wait" return early (the trap below then stops drive.py); keep waiting while it's alive.
  while kill -0 "$pid" 2>/dev/null; do wait "$pid" 2>/dev/null || true; done
  rm -f "$LOGDIR/drive.pid"
}
stop_drive() { if [ -f "$LOGDIR/drive.pid" ]; then kill "$(cat "$LOGDIR/drive.pid")" 2>/dev/null || true; rm -f "$LOGDIR/drive.pid"; fi; }
trap stop_drive EXIT
trap 'stop_drive; exit 130' INT TERM   # Ctrl+C or kill stops drive.py too

cameras() {
  # Imported through the debug bench hook (BenchReceiver --es import), from the app's own files/bench/
  # folder: a debug app can't read /sdcard/Download without a storage grant, but run-as can write there.
  # Re-running replaces the set of the same name. Course/heading = direction of travel (TESTING.md §4).
  echo "Cameras: tools/scenarios/$1.gpx$([ -f "tools/scenarios/$1.csv" ] && echo " and $1.csv") → imported by the bench hook"
  run "$ADB" -e shell run-as $PKG mkdir -p files/bench
  for f in "$1.gpx" "$1.csv"; do
    [ -f "tools/scenarios/$f" ] || continue
    run "$ADB" -e push "tools/scenarios/$f" "/data/local/tmp/$f" >/dev/null
    run "$ADB" -e shell run-as $PKG cp "/data/local/tmp/$f" "files/bench/$f"
    bench --es import "$f" >/dev/null   # a .csv uses columns lat,lon,name,speed,heading,dir
    pause 3 "importing $f for"
  done
  [ -n "$DRY" ] || "$ADB" -e logcat -d -s Bench:I 2>/dev/null | grep "Import " | tail -2
}

# Line counts of the two debug logs, so the end of the run can show just this scenario's lines.
log_count() { { "$ADB" -e shell run-as $PKG cat "files/diag/$1" 2>/dev/null || true; } | wc -l; }
log_mark() { [ -n "$DRY" ] && return; NAV0=$(log_count debug-nav.log); ALERT0=$(log_count alert-debug.log); }
log_show() {  # $1 = file, $2 = line count at the start, $3 = grep -E pattern
  [ -n "$DRY" ] && return
  echo "--- $1 since the start of the scenario, lines matching: $3"
  "$ADB" -e shell run-as $PKG cat "files/diag/$1" 2>/dev/null | tail -n +"$(($2 + 1))" | grep -E "$3" || echo "(nothing yet; the last 15 s block may not be written: wait and re-run the cat)"
}
how_to_read() {
  cat <<EOF
Read the logs (§7):
  $ADB -e shell run-as $PKG cat files/diag/debug-nav.log
  $ADB -e shell run-as $PKG cat files/diag/alert-debug.log
  tools/bench.sh dhu screenshot /tmp/car.png      # the car screen (markers, arrows, dimmed cameras)
EOF
}

parked_then_go() {
  cat <<EOF
== parked-then-go (TESTING.md A16, M20): park 60 s with 4 m of wander on the A61 at Clay Cross, route to
   Higham (preview showing 15 s), press Go, then drive the 4.9 km at 30 mph (about 6 minutes) past 3 cameras.
Pass when:
  M20  debug-nav: no "Guidance stop" for the whole drive (5 minutes); "watchdog skip (preview)" while the
       preview is up, then "watchdog skip (guiding)" once moving; "eco re-ask skip (grace)" in the first
       minute with EV routes on
  A16  alert-debug: while parked, no "stage" lines and no "suppressed reason=notMoving" for the 3 cameras
       passed later; debug-nav: engine speed under 0.5 m/s and "heading src=none" while parked; once moving,
       every camera gets "stage FIRST", "stage SECOND", "stage PASSING" and a "PASSED poi=…" line with all three
EOF
  cameras parked-then-go
  log_mark
  drive --via "$CLAY;$HIGHAM" --mph 30 --hold 60 --noise 4 --once
  pause 10 "parked; letting the app see the parked fix for"
  bench --es geo "53.12542,-1.40781"            # route preview to the Higham end of the drive
  pause 15 "preview showing for"
  bench --ez go true                             # Go: guidance starts while still parked
  echo "… driving (about 6 minutes; drive.py log: $LOGDIR/drive.log)"
  wait_drive
  log_show debug-nav.log "${NAV0:-0}" 'Guidance (start|stop)|watchdog|eco re-ask|heading src=|Location source'
  log_show alert-debug.log "${ALERT0:-0}" 'stage |suppressed|PASSED|enter|leave'
  how_to_read
}

bend_camera() {
  cat <<EOF
== bend-camera (TESTING.md A17): A61 southbound, left onto the B6014 Morton Road at Stretton (191° → 106°
   over about 120 m) with a camera 80 m past the apex (course 139), at 30 mph then at 50 mph, --noise 5.
Pass when, per pass:
  A17  alert-debug: one "enter" for the camera, three stages ("stage FIRST" not late=true, "stage SECOND",
       "stage PASSING"), and at most ONE verdict change for it ("hide"/"unhide" or a "suppressed reason=" change);
       the "PASSED poi=…" line lists all three stages spoken
EOF
  cameras bend-camera
  log_mark
  for mph in 30 50; do
    echo "… pass at $mph mph (2.4 km)"
    drive --via "$BEND_FROM;$BEND_TO" --mph "$mph" --noise 5 --once
    wait_drive
    pause 20 "letting the camera's state clear and the log block flush for"
  done
  log_show alert-debug.log "${ALERT0:-0}" 'enter|leave|stage |suppressed|hide|unhide|PASSED'
  how_to_read
}

wrong_way() {
  cat <<EOF
== wrong-way (TESTING.md A18): A61 southbound past a ONE_WAY camera that faces NORTH (course 354) — against
   its bearing — then past a BOTH camera (wrong-way.csv, heading 177, Dir both); then the same 1.7 km back
   north, which passes the BOTH camera again and the ONE_WAY camera the right way (the HARD RULE control).
Pass when:
  A18  southbound: the ONE_WAY camera gives NO "stage" line, "suppressed reason=direction …" with the heading
       and bearing figures, and is drawn DIMMED with its arrow (tools/bench.sh dhu screenshot /tmp/car.png);
       the BOTH camera gets its three stages
       northbound: the BOTH camera alerts again, and the ONE_WAY camera now gets three stages and a
       "PASSED poi=…" with all three spoken (V-table "Direction HARD RULE")
EOF
  cameras wrong-way
  log_mark
  echo "… southbound (against the ONE_WAY camera), 30 mph"
  drive --via "$CLAY;$MID" --mph 30 --once
  pause 60 "take the DHU screenshot while the car is near the first camera: waiting"
  wait_drive
  pause 20 "letting the state clear and the log block flush for"
  echo "… northbound (the right way), 30 mph"
  drive --via "$MID;$CLAY" --mph 30 --once
  wait_drive
  pause 20 "flushing for"
  log_show alert-debug.log "${ALERT0:-0}" 'enter|stage |suppressed|hide|unhide|PASSED'
  how_to_read
}

crawl() {
  cat <<EOF
== crawl (TESTING.md A19): A61 southbound at 30 mph, crawling from 100 m before a camera to 60 m past it,
   first at 3 mph (1.3 m/s, under the 2 m/s moving gate) then at 6 mph (2.7 m/s, over it).
Pass when, in both runs:
  A19  alert-debug: "stage FIRST" and "stage SECOND" at their metre floors (242 m and 142 m, reached at 30 mph
       before the crawl), then "stage PASSING" WHILE crawling (d ≤ 91 m), once the camera was ahead and valid;
       the "PASSED poi=…" line lists all three. No "suppressed reason=notMoving" that costs the PASSING.
EOF
  cameras crawl
  log_mark
  for mph in 3 6; do
    echo "… run at $mph mph through the crawl (about 2 min at 30 mph + the crawl)"
    drive --via "$CLAY;$MID" --mph 30 --crawl-mph "$mph" --from "-1.41338,53.15358" --to "-1.41317,53.15214" --once
    wait_drive
    pause 20 "letting the state clear and the log block flush for"
  done
  log_show alert-debug.log "${ALERT0:-0}" 'enter|stage |suppressed|PASSED'
  how_to_read
}

handover() {
  cat <<EOF
== handover (TESTING.md C7, C3a): A61 northbound from Higham to Clay Cross at 40 mph with the car feed on
   (speed and location to the head unit's sensor console, in lockstep with the emulator's GPS), the car feed
   dropping out for 5 s every 60 s. Needs the DHU on the sensors profile: tools/bench.sh car default_sensors.
Pass when:
  C7   debug-nav: "Location source car→phone" within 2 s of each dropout and "phone→car" when it returns, each
       with "track reset reason=handover"; the speed panel and the alerts never stall (the 3 cameras all get
       their stages in alert-debug)
  C3a  the phone stays at "Requested 10 Hz" as the backup; More → GPS says location from car between dropouts
EOF
  [ -n "$DRY" ] || [ -f "$LOGDIR/dhu.in" ] || { echo "The head unit isn't running: tools/bench.sh car default_sensors"; exit 1; }
  cameras handover
  log_mark
  drive --via "$HIGHAM;$CLAY" --mph 40 --car-speed --car-location --car-dropout 5,60 --once
  echo "… driving (about 4.5 minutes, 4 dropouts)"
  wait_drive
  log_show debug-nav.log "${NAV0:-0}" 'Location source|track reset|CarData|gap ms='
  log_show alert-debug.log "${ALERT0:-0}" 'stage |PASSED'
  how_to_read
}

case "${1:-}" in
  parked-then-go) parked_then_go ;;
  bend-camera) bend_camera ;;
  wrong-way) wrong_way ;;
  crawl) crawl ;;
  handover) handover ;;
  *) sed -n '12,16p' "$0"; exit 1 ;;
esac
