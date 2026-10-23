# Testing AutoNavAlerts on a bench (no car needed)

This guide covers testing the whole app at a desk. Android Auto's **Desktop Head Unit (DHU)** stands in for the car screen, and the Navigation SDK's **Simulate** drive moves the car along a route so the camera alerts fire.

There are two bench set-ups:

| Set-up | You need | Good for |
|---|---|---|
| **A. Real phone + DHU** (recommended) | An Android phone (Android 10+) with Google Play, a USB cable, a Linux/macOS/Windows PC | Everything: real GPS, real Android Auto, speech and audio ducking with music |
| **B. Emulator + DHU** | A PC with hardware virtualisation (KVM on Linux) | Testing without a spare phone, and **free-drive** testing using the emulator's route playback |

> **Safety:** a debug build talks to real services. **Sync now** downloads from PocketGPSWorld with your login, and **Send** on the Reports tab submits a **real** camera report to PocketGPSWorld. When testing reports, use **Discard** or **Open in browser** unless you mean to submit.

---

## 1. One-time PC set-up

### 1.1 Android SDK
If you built the project, you already have it at `~/Android/Sdk` (see README). You also need the DHU package:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
  "platform-tools" "extras;google;auto"
export PATH=$ANDROID_HOME/platform-tools:$PATH
```

### 1.2 DHU libraries (Linux)
On Linux the DHU needs the LLVM C++ runtime. On **Fedora**:
```bash
sudo dnf install libcxx libcxxabi
```
On Debian/Ubuntu: `sudo apt install libc++1 libc++abi1`.

Check that nothing is missing:
```bash
ldd $ANDROID_HOME/extras/google/auto/desktop-head-unit | grep "not found"   # should print nothing
```

### 1.3 Build the debug APK
```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```
Use a **debug** build for bench testing. Only debug builds have the **Simulate** button and the Bench testing settings.

---

## 2. Set-up A: real phone + DHU

### 2.1 Phone: developer options and USB debugging
1. **Settings → About phone →** tap **Build number** 7 times.
2. **Settings → System → Developer options →** turn on **USB debugging**.
3. Connect the phone by USB and accept the "Allow USB debugging?" prompt.
4. Check it's visible: `adb devices` should list the phone as `device`.

### 2.2 Phone: Android Auto developer mode
1. Open **Android Auto settings**. On Android 10+ it's built in: **Settings → Connected devices → Connection preferences → Android Auto** (the path varies by phone; search Settings for "Android Auto").
2. Scroll to the bottom and tap **Version** about 10 times, then accept the prompt. Developer mode is now on.
3. Open the **⋮ menu → Developer settings** and turn on **Unknown sources**. That's required because AutoNavAlerts is sideloaded rather than installed from Play.
4. In the same **⋮ menu**, tap **Start head unit server**. A notification confirms it's running. Do this again after each phone reboot.

### 2.3 Install and set up the app
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Open **AutoNavAlerts** on the phone. It opens on **Settings** until setup is done; complete the setup sections at the top:
1. **Google Maps API key** → **Create a key with my Google account** (or paste a key → **Save key**). It needs the Navigation SDK and Places API (New).
2. **Permissions → Allow**, then **Allow all the time**.
3. **Navigation terms → Review and accept**.

Optional, for camera tests (see §4):
4. **Cameras** tab: enter your PocketGPSWorld login → **Save and test** → **Sync now**.

### 2.4 Start the DHU
```bash
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```
A car screen window opens. Tap the app launcher (the grid icon) and choose **AutoNavAlerts**. If it isn't listed, check that **Unknown sources** is on (§2.2).

Different screen layouts, using the configs in `$ANDROID_HOME/extras/google/auto/config/`:
```bash
cd $ANDROID_HOME/extras/google/auto
./desktop-head-unit -c config/default_1080p.ini     # 1920×1080 widescreen (split-screen with media)
./desktop-head-unit -c config/default_720p.ini      # common 1280×720 head unit
./desktop-head-unit -c config/default.ini           # 800×480 small screen
./desktop-head-unit -c config/default_sensors.ini   # car sensors: speed, location, night mode
./desktop-head-unit -c config/rotary.ini            # rotary-controller car (no touch)
```
To test the **instrument cluster**, copy a config and set `instrumentcluster = true` in its `[general]` section, then start the DHU with that file. A second window shows the cluster.

The terminal the DHU runs in is also a console: type `help` there for its commands (day/night mode, driving restrictions, key presses and more).

---

## 3. Set-up B: emulator + DHU

1. Install a **Google Play** system image and create an emulator:
   ```bash
   SM=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
   $SM --sdk_root=$ANDROID_HOME "emulator" "system-images;android-36;google_apis_playstore;x86_64"
   $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n auto-bench \
     -k "system-images;android-36;google_apis_playstore;x86_64" -d pixel_8
   $ANDROID_HOME/emulator/emulator -avd auto-bench
   ```
   On Linux, KVM must be available (`ls /dev/kvm`).
2. In the emulator: sign in to Google Play and install **Android Auto**. Then do §2.2 steps 2–4 (developer mode, Unknown sources, Start head unit server).
   - Recent emulator images only ship a stub of Android Auto, and Play says it "isn't compatible". Copy it from a real phone instead (USB debugging on, both devices listed by `adb devices`; the emulator translates ARM code):
     ```bash
     mkdir -p /tmp/aa && cd /tmp/aa
     for f in $(adb -s <phone-serial> shell pm path com.google.android.projection.gearhead | sed 's/package://'); do adb -s <phone-serial> pull "$f" .; done
     adb -s emulator-5554 install-multiple -r -d *.apk
     ```
     Don't fetch it from third-party APK sites.
   - **Check** it took: `adb -s emulator-5554 shell dumpsys package com.google.android.projection.gearhead | grep versionName` shows the phone's version (e.g. `17.9.664004-release`), not `…-stub`. A phone's split set is usually `base.apk`, `split_config.arm64_v8a.apk`, a language split and a density split; install them all together.
   - **Android Auto's settings** have no launcher icon on the emulator. Open them with:
     `adb -s emulator-5554 shell am start -n com.google.android.projection.gearhead/.companion.settings.DefaultSettingsActivity`
   - **Developer mode:** scroll to the bottom, tap **Version** about 10 times, then **OK** on "Allow development settings?". The **⋮** menu then has **Developer settings**, **Start head unit server** and **Quit developer mode**.
   - **Developer settings → Unknown sources** on (the tick box near the bottom of the list), or our sideloaded app won't appear on the car.
   - **⋮ → Start head unit server**. A notification "Android Auto Developer · Head unit server running" stays while it runs, and the emulator listens on port 5277.
   - **First connection only:** when the DHU first connects, the emulator shows **"Welcome to Android Auto"**: tap **Continue**. Android Auto then waits for **notification access** ("Notification read, reply & control"). Turn it on for Android Auto there, or with
     `adb -s emulator-5554 shell cmd notification allow_listener 'com.google.android.projection.gearhead/com.google.android.gearhead.notifications.SharedNotificationListenerManager$ListenerService'`
     then go back. The car screen then shows Android Auto (Google Maps first, until you pick AutoNavAlerts).
3. Install the APK and do the app set-up (§2.3), using `adb -e install -r …`. On the emulator also grant car speed if you skip the prompt: `adb -e shell pm grant io.autonav.alerts com.google.android.gms.permission.CAR_SPEED`.
4. Start the DHU with `tools/bench.sh car` (it does the `adb forward` and keeps the DHU's console open). The default `bench` profile is the 1080p screen with touch **and** a rotary controller, so it can be driven from scripts:
   - `tools/bench.sh dhu screenshot /tmp/car.png` saves the car screen (GNOME blocks desktop screenshots)
   - `tools/bench.sh dhu dpad up|down|left|right|click|back` moves and presses the rotary focus (the DHU has no tap command)
   - Restarting the DHU (`bench.sh stop` is the whole bench; to restart only the DHU, kill it and run `bench.sh car` again) reopens the last navigation app.
   - **How it works:** the DHU reads console commands from its standard input and **quits when that input ends**, which is what happens to any program started in the background. `bench.sh car` therefore runs it as `tail -f /tmp/autonav-bench/dhu.in | ./desktop-head-unit -c config/bench.ini` in its own process group (`setsid`), writes the group id to `/tmp/autonav-bench/dhu.pid`, and `bench.sh dhu <command>` appends a line to `dhu.in`. `bench.sh stop` kills the whole group. The DHU's output goes to `/tmp/autonav-bench/dhu.log`; a good start ends with `connected.` and `SSL negotiation finished successfully`.
   - `config/bench.ini` is made fresh on each start from the DHU's `default_1080p.ini` with `controller = true`. Pass another profile name to use it as is: `tools/bench.sh car default_sensors` (car speed and GPS, §6.4a), `default_720p`, `default`, `rotary`.
   - **Screenshots** are 1920×1080 PNGs of the car screen only (no window frame). The DHU writes the file a moment after the command; wait about 2 s before reading it.
   - **Rotary focus** is invisible until the first `dpad` press. With touch also on, the focus ring may not show at all; if you can't see where it is, restart the DHU instead of guessing.
5. **Moving the car along real roads:** `tools/drive.py` drives the emulator's GPS along a road route at a steady speed, looping until stopped (Ctrl+C, or kill it).
   - **Options:** `--via "lon,lat;lon,lat;…"` waypoints (**longitude first**; default: a ~20 km loop around central London: Trafalgar Square, Tower Bridge, the City, King's Cross, Baker Street, Hyde Park Corner, Buckingham Palace), `--mph` (default 30), `--hz` positions a second (default 5), `--once` to stop after one lap, `--serial` (default `emulator-5554`, or `$ANDROID_SERIAL`).
   - **Driving under, at and over the limit (`--limits`):** the posted limit of each road comes from OpenStreetMap (`maxspeed`, one Overpass query over the route's bounding box, retried if Overpass is busy). The car takes turns **under** (limit − 6 mph, at least 10), **at** (the limit) and **over** (limit × 1.1 + 4 mph), changing every `--stint` metres (default 1500); `--pattern` sets the order (default `under,at,over`, e.g. `--pattern over,at`). Only roads within 20 m running the car's way count (not side roads at a junction); with no known limit it keeps the last one. Speed changes ramp at about 1.5 m/s². Each change is logged: `13:03:57   0.0 km  53.37854,-1.46344  limit 30  under -> 24 mph`.
   - **Sheffield → Derby on the A61** (58 km: 41.7 km A61, 15.6 km where it runs with the A38): `--via "-1.46344,53.37854;-1.47367,53.32638;-1.44422,53.27323;-1.42256,53.25212;-1.42389,53.22839;-1.41853,53.20159;-1.41658,53.17270;-1.41308,53.15087;-1.40781,53.12542;-1.39192,53.09387;-1.45958,52.94868;-1.46574,52.93094" --limits --once`. Waypoints were taken from the A61's OSM geometry; a waypoint on the wrong carriageway makes OSRM loop round (check the legs' lengths). It passes 12 cameras in their direction and no SPECS.
   - **A real average-speed zone, A52 Brian Clough Way, Derby (50 mph SPECS):** `--via "-1.4100,52.9120;-1.4700,52.9270;-1.4100,52.9125" --limits --pattern over,at --stint 5000 --once` drives west through cameras 206409 → 206408 → 206407 over the limit, then back east through 204312 → 204303 → 204313 at it.
   - **Route:** fetched once at start from the public OSRM demo router (`router.project-osrm.org`), driving profile, full geometry. Only the waypoints are sent. It's a free demo service: fine for a bench, not for heavy use. "Routing failed" means OSRM couldn't route between the points (usually a waypoint off the road network).
   - **Positions:** every `mph × 0.447 / hz` metres along the route, sent with `adb emu geo fix <lon> <lat> 20 12 <knots>` (altitude 20 m, 12 satellites, speed in knots). The emulator takes no heading. Its `gps` location reports `bear=0.0` (north) on every fix, while the fused one has a bearing worked out from the movement (`dumpsys location`). The app now ignores a GPS bearing more than 60° from the track it has moved along (DESIGN.md §11); before that, every camera on a road heading away from north failed the direction check on the bench. `adb emu geo nmea` is accepted but **ignored** by current emulator images, so NMEA can't add a heading.
   - **Check it's moving:** `adb -e shell dumpsys location | grep 'last location=Location\[fused'` should show the position changing and `vel=` near your speed in m/s (13.4 at 30 mph).
   - **Running it in the background:** `nohup python3 -u tools/drive.py … > /tmp/autonav-bench/drive.log 2>&1 &` (the `-u` makes its one-line summary appear in the log straight away).
   - **Passing real cameras:** the camera database is in the app's private storage. On a debug build copy it out with `adb -e exec-out run-as io.autonav.alerts cat databases/poi.db > poi.db` (also `poi.db-wal` and `poi.db-shm`, or recent changes are missing), and keep it **out of the repo** (PGW data). For each chosen camera, add a waypoint about **120 m before it** (opposite its `bearing`) and one **on it**, so OSRM drives through it in the enforced direction; then check the route really passes them (within 25 m, heading within 45° of the bearing). A loop built this way on 2026-10-12 passed 21 London cameras: the Tower Bridge 20 mph SPECS pair, fixed, mobile, pending and red-light sites.
   - **Jumps:** starting a new route far from the last position makes the car "teleport". The car map used to stay stuck where it was; it now follows again on its own within about 5–10 s (DESIGN.md §8, follow watchdog). A jump of more than 1 km between fixes now ends any average-speed zone (DESIGN.md §11 step 8).
   - **Realism options** (a clean centreline at a steady rate is the one thing a real phone never gives you):
     - `--noise M`: position wander of about M metres, drifting slowly like a real receiver (AR(1)), not white jitter. `--noise 6` is a fair phone on a dashboard.
     - `--speed-noise P`: the reported speed varies by ±P%.
     - `--dropout S,EVERY`: no fixes for S seconds every EVERY seconds (a tunnel, a bridge).
     - `--rate-jitter`: irregular fix spacing around `--hz`, as the fused provider delivers it.
     - `--hold N`: sit at the start point for N seconds before moving (a parked car; add `--noise` for parked wander).
     - `--stop-at "lon,lat" --for N`: stop at that point on the route for N seconds, then carry on.
     - `--crawl-mph N --from "lon,lat" --to "lon,lat"`: crawl at N mph between those two points (queueing past a camera), the normal `--mph` elsewhere.
     - `--car-speed` / `--car-location` (with the DHU started on the `default_sensors` profile, §6.4a): feeds the DHU's sensor console in lockstep with the emulator's GPS, so the app gets a real car feed and a real car↔phone handover. `--car-lag MS` delays the car feed; `--car-noise M` gives it its own wander. `--car-dropout S,EVERY` drops only the car feed (the `handover` scenario uses it; plain `--dropout` drops the phone's fixes); `--car-only` feeds the head unit and not the emulator.
     - `--record out.csv`: writes every fix sent, in the app's drive-recording layout.
     - `--replay file (at X× the reported speed is scaled by X too, so motion and the time-based alert distances agree).csv [--speed X]`: drives the emulator from a phone **drive recording** (Settings → Diagnostics, docs/SETTINGS.md §1.8) at real time or X×. Only position and speed can be injected; bearing and accuracy are covered by the JVM replay (G10).
     - `--bearing` (**experimental**): tries to send a heading through the emulator's gRPC `setGps`; falls back to `geo fix` (no heading) if the image doesn't take it.
   - **Scenarios:** `tools/scenario.sh <name>` imports the scenario's test cameras through the bench hook (`--es import`, step 5a), runs a scripted drive with the options above and says what to look for in the logs (§7):
     | Scenario | What it does | Pass when |
     |---|---|---|
     | `parked-then-go` | Parks 60 s with noise, routes somewhere (preview), presses Go (`--ez go true`), then drives | No `Guidance stop` in `debug-nav` for 5 minutes; `watchdog skip (preview)` then `skip (guiding)` |
     | `bend-camera` | An ~85° bend over ~120 m (B6014 at Stretton) with a camera 80 m past the apex, at 30 then 50 mph, `--noise 5` | One `enter`, three stages, at most one verdict change per pass |
     | `wrong-way` | A ONE_WAY camera driven against its bearing, then a BOTH camera | ONE_WAY: no stage, drawn dimmed with its arrow; BOTH: alerts |
     | `crawl` | 3 mph and 6 mph past a camera (either side of the 2 m/s moving gate) | PASSING fires in both, once the camera was ahead and valid |
     | `handover` | Car feed on, dropping out 5 s every 60 s | `Location source car→phone` within 2 s of each dropout, and back when it returns |
5a. **Driving the car screen from adb (debug builds):** the DHU's window can't be clicked from a script and its `dpad`/`dpad rotate` commands don't move focus into the app's screens, so debug builds have a bench hook, `BenchReceiver` (in `app/src/debug`, so release builds don't have it; protected by the DUMP permission, which only adb's shell holds). Always add `-p io.autonav.alerts` (Android only delivers explicit broadcasts to manifest receivers):
   - `adb -e shell am broadcast -a io.autonav.alerts.BENCH -p io.autonav.alerts --es q "Mum"`: opens Search with "Mum" typed in (the same navigate intent Assistant sends)
   - `… --es geo "53.38,-1.47"`: route preview to that point
   - `… --es contact "Mum"`: as if the first matching contact row was tapped (locate, cache, route preview); logcat `Bench: Contact Mum (Home): Found(…)`
   - `… --es zoom -2`: like tapping − twice; logcat `Bench: Zoom now 15.5`
   - `… --es banner "'Speed camera'"`: a test alert banner for 8 s; logcat `Bench: Banner at x=… y=… W×H in W×H`
   - `… --ez go true`: presses **Go** on the route preview that's showing (so a script can start guidance)
   - `… --ez more true` / `--ez layers true` / `--ez search true`: opens More / More → POI layers (each set with its map icon) / the empty Search screen, for screenshots
   - `… --ez engine true`: writes the alert engine's snapshot to `alert-debug` and logcat (the same as Settings → Diagnostics → Engine now: last fix, stats, cache, route state, mutes, the 5 nearest POIs with their verdicts, the speech queue)
   - `… --ez diag true`: prints the last 100 lines of each debug log to logcat
   - `… --es import "<file>"`: imports a POI file from the app's own `files/bench/` folder (a debug app can't read `/sdcard/Download` without a storage grant). Put it there first: `adb -e push f.gpx /data/local/tmp/ && adb -e shell run-as io.autonav.alerts mkdir -p files/bench && adb -e shell run-as io.autonav.alerts cp /data/local/tmp/f.gpx files/bench/`. A `.csv` uses the scenario layout `lat,lon,name,speed,heading,dir` (override with `--es csvcols "0,1,2,3,4,5"`). Re-importing a file replaces its set. logcat `Bench: Import f.gpx: 3 imported, 0 skipped, set=36`. `tools/scenario.sh` imports its cameras this way, so no phone-side import is needed
   - The DHU's `screenshot` command shows the result. Taps by a person on the DHU window work as normal.
6. **Or with the emulator's own controls:** open the emulator's **Extended controls (⋯) → Location → Routes**, plan a route (or load a GPX/KML under **Single points → Load GPX/KML**), set the playback speed, and press **Play route**. The phone's GPS then moves along it, so camera alerts work in **free drive**, without the Simulate button.

---

## 4. Test data: getting POIs on your test route
Alerts only fire for POIs that are **on the road you're driving** (within 40 m of your path in free drive, widening to 12° off-axis beyond 192 m so bends don't lose the 1st alert, or 50 m of the route ahead when navigating) and that **face your direction of travel**. Use one of these:

| Option | How | Notes |
|---|---|---|
| **PocketGPSWorld data** | Cameras tab → **Sync now**. Plan a test route along a road you know has cameras (e.g. a SPECS average-speed stretch, or a road with fixed cameras). | The most realistic. Needs your subscription. |
| **Report-then-replay** | Start a Simulate drive (§5), and at a few points tap **Report → Fixed → 30**. Then simulate the **same route again**. | The **My reports** set now has POIs exactly on the route, facing your direction. This also tests the report flow. Mobile reports expire after 24 h; use **Fixed**. |
| **A hand-made GPX file** | Make a file like the one below and import it on the **POIs** tab | You control everything. Put each point on the **centre of the road** and set `course` to the **direction of travel** (0 = north, 90 = east). Get coordinates by long-pressing the road in any map app. |

```xml
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="bench-test">
  <!-- Replace with points on your test route. course = direction of travel in degrees. -->
  <wpt lat="53.80000" lon="-1.55000"><name>Test camera 30</name><course>90</course><type>Fixed</type></wpt>
  <wpt lat="53.80100" lon="-1.54000"><name>Test camera 40</name><course>90</course><type>Fixed</type></wpt>
  <wpt lat="53.80200" lon="-1.53000"><name>Both ways 30</name><type>Fixed</type></wpt>
</gpx>
```
- A number in `<name>` such as "30" becomes the speed limit.
- A point without `<course>` applies in **any** direction.
- For a "both directions" point, use a CSV with a Dir column (`both`) instead.

---

## 5. Running a simulated drive
1. On the phone: **Settings → Bench testing → Simulate speed**. **1×** (real time) is the most realistic for alerts. Use 2× or 4× for long routes.
2. On the DHU: **Search** (magnifier) → type a destination a few miles away → pick a result.
3. On the route preview, press **Simulate** (top right) instead of **Go**.
4. The car drives the route. The turn cards update, the voice speaks, markers update, and POI alerts fire as you pass them.
5. Press the **X** on the host's travel-estimate card (bottom-left) to end the drive early; on arrival guidance ends by itself with a toast. There is no stop action of ours. On the DHU, Android Auto's own "auto drive" developer option also starts a simulation if a route is set.

> While simulating, the alert engine uses the **simulated** position instead of the phone's GPS. The phone itself stays still.

---

## 6. Test checklist
Work through these and tick off each expected result. "DHU" means the car screen and "phone" means the AutoNavAlerts phone app.

### 6.1 Setup and error states
| # | Steps | Expected |
|---|---|---|
| S1 | Phone Settings → Google Maps API key: **Remove** the API key, then open the app on the DHU | The DHU map area shows "Finish setup on your phone: Add your Google Maps API key…" |
| S2 | Add the key back. Then, in a fresh install, open the DHU before accepting the terms | "…accept the navigation terms" |
| S3 | Accept the terms, then reopen the app on the DHU | The map loads and follows your position, with traffic shown |
| S4 | Enter a wrong key | "The Maps API key isn't authorised for the Navigation SDK" |
| S5 | Fresh install (no key saved), open the phone app | No crash. It opens on Settings; **Review and accept** (terms) is greyed out with "Add your Google Maps API key above first". |
| S6 | Fresh install with no key, open the app on the DHU | No crash. The car screen asks you to add the key on the phone. |
| S7 | Settings → Permissions → **Allow** | The prompts include car speed; the "Car speed" line turns ✓ |
| S8 | Settings → Backup → **Save settings backup…** → save to Downloads | Toast "Settings backup saved". `adb -e shell cat /sdcard/Download/autonavalerts-settings-*.json` shows `"app": "AutoNavAlerts"`, `"format": 1`, the settings, `secrets` with the Maps key and PGW login, and one entry per camera set. (Don't paste its output anywhere: it has the key.) |
| S9 | Change a few settings (e.g. Satellite map on, Units km/h), then **Restore from backup…** → pick the file | A dialog lists the backup's date, "Maps API key: included", "PocketGPSWorld login: included (name)", and "N sets, N match". **Cancel** changes nothing; **Restore** puts the settings back and says "Restored settings, Maps key, PGW login and N camera sets." |
| S10 | Restore a file that isn't a backup (any other JSON or text file) | "This isn't an AutoNavAlerts settings backup"; nothing changes |
| S11 | Fresh install (uninstall/reinstall) → Restore → accept terms → Cameras → download → Restore again | The key and PGW login come back on the first restore (the car map works once terms are accepted); after the download the second restore matches every PGW set |

### 6.2 Map, search and guidance
| # | Steps | Expected |
|---|---|---|
| M1 | Drag on the map, then tap **recenter** (⊕) | It pans, then snaps back to following the car, tilted |
| M2 | **Zoom +/−**, then a pinch gesture on the DHU | It zooms in and out |
| M3 | **Search** with the box empty | Recent places are listed (after M5) |
| M4 | Type 3+ letters | Suggestions appear with distances, nearest first |
| M5 | Pick a result → preview → **Go** | The preview shows the time, distance and traffic. After Go: a turn card with the manoeuvre icon, distance, road and next step, plus the ETA |
| M6 | During guidance, open a media app (§6.5) | The turn info shows in Android Auto's rail/widget |
| M7 | With the cluster config (§2.4) | The cluster shows the next turn |
| M8 | Simulate to the end of the route | Guidance ends by itself: a toast "You have arrived at <name>", the turn card and the host's travel-estimate card go, the strip goes back to Search · Report · Recents · More; `debug-nav`: `Guidance arrived "<name>"` then `stop caller=ARRIVED` (DESIGN.md §8 "Arrival") |
| M9 | **Recents** (clock icon) → the delete icon on a row (car API 6+) | The row is removed. On older hosts, tap the row → **Remove**. |
| M10 | **Recents → Clear all → Yes** | All recents are removed; favourites stay |
| M11 | Switch DHU day/night (DHU console `help` lists the command) | The map switches between day and night styles |
| M13 | Phone Settings → Permissions → **Allow contacts**; add a contact with a Home address (`adb -e shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact -e name "Test Mum" -e postal "10 Downing Street, London SW1A 2AA"`, then Save) | The Contacts line turns ✓ and **Clear contact locations** appears |
| M14 | Car → Search → type "test" (or "mum") | "Test Mum" is the first row (contact icon, "Home · 10 Downing Street, London SW1A 2AA"), above the Places results |
| M15 | Pick the contact row | Route preview to 10 Downing Street titled "Test Mum (Home)"; Recents now has "Test Mum (Home)" with the address |
| M16 | Search "test" again | The contact row now shows its distance (the location is cached); picking it doesn't search Places again |
| M17 | Car → Search (empty) → **Contacts** → type a name | Contacts only, hint "Search your contacts", no Places results |
| M18 | A contact address that matches several places with no postcode match (e.g. "High Street") | The list shows "Which address for <name>?" rows; picking one routes there and remembers it |
| M19 | Revoke contacts in Android settings, then search | No contact rows and no Contacts entry; Places search as before |
| M12 | During a free drive, restart `tools/drive.py` with a route a few km away | The car map catches up with the car within about 10 s (`debug-nav`: `Map refollow d=…`, and `rebuild d=…` only on the third miss) |
| M20 | Park (`tools/drive.py --hold 60 --noise 4`), Search → a destination → leave the preview showing for 15 s → **Go** → the car moves | The route **stays** for the whole drive. `debug-nav` shows `watchdog skip (preview)` while the preview is up, then `watchdog skip (guiding)` once moving, and `eco re-ask skip (grace)` (with EV routes on) in the first minute; no `Guidance stop` |
| M21 | While guiding, tap the **X** on the host's travel-estimate card; separately, start a route in **Google Maps** on the DHU (the host stops ours) | Both: the card goes, the action strip goes back to Search · Report · Recents · More; `debug-nav`: `Session hostStopNavigation` then `Guidance stop caller=HOST`. No toast. |
|     | *Scripted taps on the DHU land ~35 px low near the bottom of the screen: aim the X at `tap 148 985`, not its drawn centre (1014). Verified 2026-10-23 21:44: `Session hostStopNavigation` → `Guidance stop caller=HOST`.* | |
| M22 | Route preview → **Back** without pressing Go; again with **Back** while the preview is still loading | `debug-nav`: `preview abandoned` (and `route request cancelled` for the loading case); no route is left drawn and there's no travel-estimate card |
| M22a | While guiding, Search → another destination → preview → **Back** | The guided route comes back (same destination, the host's card keeps its X); `debug-nav`: `preview abandoned (restored guided route) dest=<the guided one>` then `routeStatus=OK (restore)`. Repeat with **Go** instead: guidance switches to the new destination |
| M23 | EV routes on with the Routes API on the key (debug: `--ez ev true --ez tworoutes true`), Go on the energy-efficient route, then drive off it | Within the first 60 s: `eco re-ask skip (grace)`; after the SDK's own reroute: `eco re-ask Ask … RouteStatus OK`. The route **never clears** during the re-ask. |
| M24 | Arrive (M8) with a camera report drafted on the way | The arrival toast, then the phone's "N camera reports to check" notification; nothing left on the car screen to clear |
| M25 | Route → **Go** → the card's **X**, then open **Search** (empty) and **More → Recents** | Both list the destination you just went to; `debug-nav`: `Places remember "<title>"` before the preview closed |
| M26 | **Voice destination:** tap the **mic** on the search box and say a town (or Assistant: "navigate to <town>") | "Searching…", then a results list or the route preview, or "No results for …". Never a silent empty list. `debug-nav`: `Search submitted/voice len=…` then `Places textSearch n=… status=…`, or `NavigateIntent …` for Assistant |
| M27 | **Contacts list:** Search (empty) → **Contacts** | A pickable list of contacts with addresses, recently used first then A–Z, within the host's list limit, each row "Name — Home · 12 High St · 3.2 mi". Tapping one locates it and opens the route preview. The last row, **Search contacts…**, opens the typed search (M17). |

| U1 | **Hard rule, after every UI change:** `tools/bench.sh dhu screenshot` during guidance (route → Go → driving) | **Nothing dead on screen, nothing shown twice** (one stop: Google's X on the host's travel-estimate card, no Stop/Done on the strip; one turn card, one recenter, one speed/limit, one alert surface). None of Google's own map controls is drawn: no bottom-left ETA card with an X, no header, recenter, speedometer, trip bar or limit icon (`debug-nav`: `Map sdkControls off=[…six…] hid=[…]`); only the host's turn card and travel-estimate card, our speed panel, the banner while an alert shows, and our Satellite/+/−/recenter column, each of which works. |
| U2 | **Hard rule, any screen with a ticker or a collector:** open it with the car parked (`--ez more true`, the home map, Alerts, GPS) and take six screenshots 0.7 s apart (`for i in 1 2 3 4 5 6; do tools/bench.sh dhu screenshot /tmp/s$i.png; sleep 0.7; done`), then diff consecutive pairs | **Every pair identical** (no rows drawn twice or shifted; `debug-nav` shows no refresh churn). A judder once a second means a template is being re-sent with unchanged content: fix it, don't accept it |
| U3 | Guidance on the DHU (route → Go) | **Turn card** in Google's teal with the road only ("Main Rd / A61"), next step below it; **one white speed pill** bottom-right (limit ring left, speed right, speed amber/red when Google says speeding); **zoom pill** (+ over −) and round recenter under Satellite, all on the right. Tap each half of the pill and recenter: `debug-nav` `Map tap … hit=zoomIn` / `zoomOut` / `recenter` |
| U4 | `tools/bench.sh dhu night`, then `day` | The map and our buttons follow: dark map with dark grey buttons and white icons by night, light map with white buttons by day; `debug-nav` `Map dark=true` / `false`. Nothing redraws while it holds still (U2) |
| U5 | Screenshot free drive, then the same spot in guidance, then guidance with `--es banner "'Speed camera'"` | The car arrow is centred across the map at the **same pixel (±10 px)** in free drive and guidance, and sits **above** the alert banner, never under it (DESIGN.md §8 "Where the car sits") |
| U6 | During guidance, `adb logcat -c`, wait 10 s, `adb logcat -d \| grep -c 'Host received new template'` | **About 1 per 10 s** while the shown distance and minutes stay the same (was 11 before 2026-10-23); more only as the cards' text changes |

### 6.3 POI alerts (simulate past test POIs from §4)
| # | Steps | Expected |
|---|---|---|
| A1 | Drive towards a camera facing your direction | **1st alert** 15 s ahead at your current speed (at least 242 m / 265 yd; e.g. ~470 m at 70 mph), **2nd alert** 7.5 s ahead (at least 142 m / 155 yd; ~235 m at 70 mph), **passing alert** at 100 yd. `alert-debug`: `stage FIRST poi=… d=… spd=… expectedD=…` with `d` matching speed × seconds, then `Tts … spoken`. Each is a card on the DHU and a spoken alert. |
| A2 | Look at the alert | A banner at the **bottom centre** of the map, clear of the speed panel, that fades in without sliding: the POI icon, the text from the set's template, the distance and the limit, and a progress bar |
| A2a | Let the 2nd and passing alerts fire for the same camera | The same banner updates in place; it goes after 8 s (5 s for passing) |
| A2b | Tap the banner | It's dismissed |
| A3 | Set editor: change the 1st alert to "Distance 800 yd", save, drive again | The 1st alert now fires at about 800 yd |
| A4 | Set editor: switch the **2nd alert** off | Only the 1st and passing alerts fire |
| A5 | Set editor: turn **Sound** off | Cards only. **No** speech, and no passing alert. |
| A6 | Simulate the **opposite** direction past a one-way camera | **No** alert |
| A7 | A camera on a parallel road or slip road | **No** alert |
| A8 | Two cameras close together at 2×–4× speed | Both passing alerts are spoken; neither is skipped |
| A9 | Average-speed (SPECS) stretch from PGW data, e.g. the A52 Brian Clough Way leg (§3 step 5) | "Entering average speed zone, 50 mph limit" at the **first** camera, then "Leaving average speed zone" **at the last** camera (not 5 km later). Zone alerts show the set's own icon (SPECS 50) and the limit in the subtitle. |
| A9a | Drive over the limit inside the zone (`--pattern over`) | "Average speed zone, 50 mph limit, you are over the limit", at most every 15 s; the speed panel shows the zone's limit |
| A9b | Pass a single SPECS camera, or join a zone part-way and pass only its last camera | **No** zone starts (no camera ahead to time you against) |
| A9c | Inside a zone, restart `tools/drive.py` a few km away (a jump) | The zone ends silently; no more over-the-limit alerts |
| A9d | Drive a southbound or westbound road on the emulator | Cameras still alert. The emulator's GPS reports bearing 0 on every fix; the app now uses the track instead (DESIGN.md §11). Before this fix, nothing alerted unless you were heading roughly north. |
| A10 | Over-limit: with the default **−0.1%**, drive at or just over a camera's limit (higher simulate speed) | "…you are over the limit" is added to the spoken alert |
| A11 | Settings → **Voice** off | Cards still show, **including the passing card**, with no speech; `alert-debug` has the stages with `spoken=false reason=mute` |
| A12 | DHU **More → Mute alerts for 30 min** | Silent cards, the passing card included. **Unmute** restores speech. |
| A13 | Before the map has loaded (e.g. right after opening the app), an alert fires | Android Auto's own alert card is used instead, with **Dismiss** and **Mute 30 min**; **Mute 30 min** silences only that set |
| A14 | **More → POI layers**: switch a set off | Its markers disappear and its alerts stop |
| A15 | Tap Report, then keep driving past the spot | **No** "My reports now" alert on this pass (it's quiet until you're 300 m away); on a later pass it alerts normally |
| A16 | `tools/scenario.sh parked-then-go` (60 s parked with noise, then drive past cameras) | While parked: `alert-debug` has no `stage` lines and no `suppressed reason=notMoving` for the cameras passed later; the engine speed stays under 0.5 m/s and the heading goes to none (`heading src=none`). Once moving, every camera gets its three stages. |
| A17 | `tools/scenario.sh bend-camera` (camera 80 m past a 90° bend, 30 and 50 mph, `--noise 5`) | Per pass: one `enter`, three stages (FIRST not `late=true`), and **at most one** verdict change for that camera |
| A18 | `tools/scenario.sh wrong-way` | ONE_WAY camera driven against its bearing: **no** alert, no `stage`, drawn **dimmed with its arrow** (DHU screenshot), `suppressed reason=direction` with the heading/bearing figures. The BOTH camera on the same road alerts both ways. |
| A19 | `tools/scenario.sh crawl` (3 and 6 mph past a camera) | **PASSING fires in both** once the camera has been ahead-and-valid; the 1st and 2nd fire at their metre floors |
| A20 | A straight road at 30 mph with `--noise 6` | All three stages still fire, and there's no `suppressed reason=direction` for a camera facing our way on a straight road |
| A21 | Settings → Alerts → **Alert lead time** 30 s, drive at 50 mph | FIRST at about 30 s ahead (≈ 670 m, `expectedD` agrees), SECOND at about 15 s (≈ 335 m); back at 15 s, A1's distances return |
| A22 | Two cameras facing our way **less than 200 m apart** | **Four** stage events (FIRST and SECOND for the second camera may be clamped together), all `spoken=true` within 8 s; nothing `dropped` or lost in `Tts` |
| A23 | Mute (More → Mute, or Voice off), then pass a camera | The **passing card** still shows; nothing is spoken (`spoken=false reason=mute`) |
| A24 | Markers on the DHU, zoomed in on a mix of cameras (screenshot) | Every camera **with a bearing** shows an arrow pointing the way the data file gives (ONE_WAY single-headed, BOTH double-headed, rotating with the map); cameras **without** a bearing have none |
| A25 | **One voice** (DESIGN.md §12.3): a guided drive past a camera | The directions and the alerts are the **same voice**, the phone's default TTS voice (change it in Android's text-to-speech settings and both change). Google's own voice is silent: `debug-nav` `Directions start`, then `Directions say kind=prepare … text="In 300 yards, turn …"` and `kind=now`; `alert-debug` `Tts ready … voice=<the phone's default>` and `Tts start GUIDANCE`; no new folder under `Android/data/io.autonav.alerts/testdata/voice/` |
| A26 | **Directions timing**: town (30 mph) and fast road (60–70 mph) turns; a long straight; a reroute; More → Mute; a camera PASSING at a turn; TTS disabled (Android Settings → text-to-speech: no engine, or the engine's app disabled) | Town: "In 300 yards, …" then the instruction about 5 s before the turn; fast road: "In half a mile, …"; a step over 3 km starts with "Continue for N miles"; nothing while rerouting; Mute silences the cameras, not the directions; PASSING is spoken first and the direction straight after (or dropped as stale once past); arrival says "You have arrived". With TTS broken: `Directions fallback sdkVoice reason=` and Google's own voice speaks the directions |

### 6.4 Map extras
| # | Steps | Expected |
|---|---|---|
| X1 | Zoom to 11 or closer near POIs | Markers with icons and speed badges. Up to 200, nearest first. |
| X2 | Watch markers during the drive | Cameras **ahead** facing you are full colour, others faded, and the one being alerted is **larger with a red halo**. They update on every GPS fix. |
| X3 | Speed panel (bottom-right corner) | Your speed in mph or km/h, updating smoothly. It's the car's speedometer figure when the car provides speed (§6.4a). |
| X4 | Go over the road limit | The panel turns **amber** (5% over) or **red** (10% over), and Google's limit sign appears (it stays for 60 s) |
| X5 | During a camera alert, under the limit | The camera's limit appears with a small **blue dot** |
| X6 | Settings → Speed panel off | The panel disappears |
| X7 | **More → GPS**, parked and moving | "Requested 10 Hz" at every speed (logcat `AdaptiveLocation: Requested 10 Hz`, once at start). "Actual" shows what's delivered (about 5 Hz with `tools/drive.py`'s default `--hz 5`). |
| X8 | Phone Settings | There is no GPS rate card; the car's More → GPS row shows 10 Hz requested |
| X9 | Look at the top right of the car map | A dark **Satellite** pill in the corner, and under it a column of round **+**, **−** and **recenter** buttons. Nothing at the bottom right (the host's map strip keeps only Pan, shown for rotary/touchpad cars). |
| X10 | Tap **Satellite** | The map switches to satellite imagery with road names and the pill says **Map**; tap again to switch back. logcat shows "Tap at x,y". The phone's Settings switch and More → Satellite map follow. |
| X11 | While driving, tap **−** a few times (or pinch out), wait 30 s, then **recenter** | The map stays at your zoom and keeps following the car (it doesn't zoom itself back in); recenter hands the zoom back to the SDK. Debug: `--es zoom -3`, then `--es zoom 0` every few seconds: logcat "Zoom now" stays the same |
| X12 | Tap the map so the action strip (search, report…) shows | The column isn't covered; it moves down while the strip shows (logcat "Visible area Rect(…)") |
| X14 | Road map while following, then **+** as far as it goes, then Satellite | Traffic colours (green/amber/red) on the roads at every zoom, and the same roads coloured on both map types; the map stops zooming in at 17 (debug: `adb -e shell am broadcast -a io.autonav.alerts.BENCH -p io.autonav.alerts --es zoom 3`, logcat "Zoom now 17.0") |
| X13 | More → **Satellite map** toggle | Same as X10, for cars without touch |
| X19 | Open the car map right after connecting (before touching anything), then show/hide the action strip | **Hard rule:** the Satellite pill, +, − and recenter are on the right edge under the action strip from the first frame; nothing of ours is on the left |
| X20 | More → **Route options**: Avoid tolls on (default), Energy-efficient off → route somewhere with a toll road on the fastest way (e.g. the M6 Toll) | The route goes round the toll road |
| X21 | Energy-efficient routes on, key **without** the Routes API (debug: `--ez ev true`, then `--es geo "52.9225,-1.4746"`) | The preview shows the fastest route and "Fastest route (energy-efficient routing isn't available: turn on the Routes API …)"; logcat `EcoRoutes: Routes API 403` |
| X22 | Same with the Routes API on the key (re-run Create a key) | The preview lists **Fastest** and **Energy-efficient (EV)** with their times and distances (or one "Fastest and most efficient" row), the energy-efficient one selected; logcat `Guidance: Route choices: …` |
| X23 | In that preview, select **Fastest**, then **Energy-efficient** again, then Go | The selected row has a green tick icon (the other an empty circle), each row shows its traffic ("light/busy/heavy +N min") and projected queues ("queue in N min" or "no queues"), with the route name on the title line beside the time; debug `--ez tworoutes true` shows two rows even when Google returns one route, and the button reads **Go · Fastest** / **Go · Energy-efficient** to match; the map's route changes with the selection; guidance follows the selected one; off-route on the energy-efficient route, a new energy-efficient route comes back within a minute |
| X27 | Speed panel parked on a road (no heading), and on open ground with no road | On the road its limit shows (e.g. 20 with the grey dot); with no road the sign shows "–" in a grey ring: the sign is always next to the speed |
| X28 | Random roads (debug: `--ei osmcheck 1000 --el seed 42`) | logcat `Bench: OSM check: N random road pieces; facing along N, facing back N, standing still N` (all equal). Bench 2026-10-12: 822 of 822 each way |
| X29 | Drive Banbury → Teddington (`tools/drive.py --via "-1.3398,52.0629;-0.3326,51.4247" --mph 60 --once`) | logcat `NavSession: Panel limit …` lines follow the roads (30/40 in towns, 70 on the M40, 50 on the A40/A4 sections…); "–" only where OSM has no limit, and never blinking at junctions |
| X30 | Same drive; watch the M40 south of J9 (pending mobile #15461, westbound only, 51.9676,-1.2447) and turn on map display for "PGW pending mobile sites" | The westbound-only camera isn't drawn while we drive the other way past it (and doesn't alert); cameras for our direction, and those on other roads, are still drawn. Core test `camerasForTheOtherWayOnOurRoadAreFacingAway` |
| X31 | Any camera alert on the car map | Banner at the bottom centre; icon and text centred together inside it |
| X32 | **Release build on a real phone** (`assembleRelease`, `adb install -r`): Settings → **Create a key with my Google account**, sign in with a test-user account | The key is created in the user's own project and "✓ Your key is saved" shows; the app keeps running afterwards (a shrunk Navigation SDK crashed here on 2026-10-12 before the ProGuard keep rules) |
| X15 | Settings → Speed limits → **Download speed limits for the UK** on mobile data only | "Waiting for Wi-Fi"; nothing downloads (on the emulator, `adb -e shell svc wifi enable` to let it start) |
| X16 | Same, on Wi-Fi (debug: `adb -e shell am broadcast -a io.autonav.alerts.BENCH -p io.autonav.alerts --es osm download`) | Notification and card show `Wales/Scotland/Northern Ireland/England: … · X of 1067 MB`; logcat `OsmLimits` "Wales done", "Scotland done", "Northern Ireland done", "England done", "Speed limits ready: 1326619 roads, 29 MB, data from 2026-10-11"; the card then says **Update speed limits** with the date, roads and size |
| X17 | Drive a road where Google gives no limit and no camera is near | The panel shows the OSM limit with a grey dot; turning **Show OpenStreetMap limits** off removes it |
| X15a | **Download on Wi-Fi only** off, then Download on mobile data only (emulator: `adb -e shell svc wifi disable`; debug: `--es osm download-any`) | It starts at once over mobile data; the note under the button warns about the allowance |
| X18 | **Delete downloaded limits**; Cancel during a download | The file goes and the card offers Download again; Cancel stops the job and the old file (if any) is unchanged |
| X33 | **More → GPS**, left open while `tools/drive.py` runs, then with the car parked | The row **updates once a second** without reopening: requested/actual Hz, engine speed and dial speed with their sources, heading and its source. Parked, with nothing changing, the list **holds still**: no once-a-second flicker of the rows (the template is only re-sent when a line changed) |
| X34 | `tools/drive.py --hz 5 --rate-jitter --dropout 3,45` | More → GPS "Actual" and the `debug-nav` `Location fix … hz=` samples agree with the real rate; each 3 s dropout gives a `Location gap ms=…` line of about 3000 |
| X35 | Limit change ahead on the A61 drive (OSM limits downloaded, X15–X17; `--limits`) | `alert-debug`: `limitAhead new=… d=… via=road` about **300–400 m** before each change the panel later shows, the **limit-change marker** on the map at the change (DHU screenshot: the new number in dark text on a light-grey square on the road ahead, no chevron on the speed panel; six frames 0.7 s apart while parked near a change are identical, U2), then `limitChanged 50→30` when the panel flips, and "Speed limit changes to thirty ahead" spoken **once** per change. With a route set, the lines say `via=route`. |

### 6.4a Car speed and car GPS (start the DHU with `-c config/default_sensors.ini`)
| # | Steps | Expected |
|---|---|---|
| C1 | DHU started with `default_sensors.ini`, no simulation running | **More → GPS** shows "Location from car · speed from car". The speed panel shows the speed the DHU reports. (Change the DHU's speed and location with its console; type `help` for the sensor commands.) |
| C2 | Settings → **Speed and location source → Phone only** | **More → GPS** shows "Location from phone · speed from phone" |
| C3 | DHU with the default config (no sensors) | Falls back: "Location from phone · speed from phone" |
| C3a | With car GPS in use, look at the log (`debug-nav` `Location …` lines, or `adb logcat -s AdaptiveLocation` on the emulator) | The phone stays at "Requested 10 Hz" as the backup. Quit the DHU sensor feed or switch to Phone only: within about 2 s phone fixes drive the alerts again (`Location source car→phone`, `track reset reason=handover`). |
| C3b | Set the DHU's car location onto a road with a test camera, then move it along the road | Alerts fire from the car's position |
| C4 | Car speed at 50+ mph (DHU sensor) | **More → GPS** shows "Requested 10 Hz" (it's always 10 Hz, whatever the speed) and "speed from car" matching the DHU's figure |
| C5 | During a **Simulate** drive | The simulated speed is used (car speed is ignored), so alerts follow the simulated car |
| C6 | Put the car's GPS **1 km from the phone's**: `tools/bench.sh dhu "location <lat> <lon> 5 20 0 0"` while `tools/drive.py` moves the emulator | **More → GPS** says location from car; the map keeps following the SDK's (phone) position with **no** refollow or rebuild: `debug-nav` `watchdog skip (sdk fix agrees)` |
| C7 | `tools/scenario.sh handover` (car feed dropping 5 s every 60 s) | `Location source car→phone` within 2 s of each dropout and `phone→car` when it returns, each with a `track reset reason=handover`; the speed panel and alerts never stall |

In a real car: compare the speed panel with your dashboard (they should match), and check **More → GPS** says "Speed from car".

### 6.5 Living alongside media
| # | Steps | Expected |
|---|---|---|
| D1 | On the DHU, start music (YouTube Music or Spotify on the phone) while navigating, on `default_1080p.ini` | Split screen: the map stays visible with a media card beside it. The car marker and turn card aren't covered. |
| D2 | A POI alert during music | The music **ducks** (gets quieter) during speech and **doesn't pause**, then returns to full volume |
| D3 | An alert at the same moment as a Google turn instruction | The turn instruction plays first. Our alert follows (passing alerts wait at most 1 s). |
| D4 | Make the media app full screen, then pass a camera | The alert appears as a **notification card** over the media screen, with speech |
| D5 | Narrow screen (`default.ini`) and portrait layouts | Everything stays usable; the speed panel stays inside the visible area |

### 6.6 Reporting cameras (capture in the car, review and send only on the phone)
Before testing, allow the **microphone** in the phone's Settings tab (Permissions). On the DHU, the car's microphone is your PC's microphone.

| # | Steps | Expected |
|---|---|---|
| R1 | During a Simulate drive, tap **Report** (⚠) once | There's **no menu**: a tone plays and the screen says "Listening…" |
| R2 | Say "mobile van in the lay-by", then stop talking | It stops after about 2 s of silence. You're back on the map with "Camera reported with your comment… Check it on your phone after the drive" and **Undo**. |
| R3 | Tap Report, then **Skip** (or say nothing for 6 s) | Saved without a comment |
| R4 | **Undo** within 5 s | The report and its marker are removed |
| R5 | Tap Report while over Google's limit (higher simulate speed) | At review the speed limit says *from Google* |
| R6 | Tap Report while a camera alert is showing, under the limit | The limit says *from camera data* |
| R7 | Stop the drive / disconnect the DHU | The phone shows "N camera reports to check". Tapping it opens the Reports tab. |
| R8 | Reports tab, while the DHU is still connected | **Send** is disabled, with the red "Disconnect from Android Auto first…" message |
| R9 | Disconnect, wait 2 min still. Check one report: type (pre-filled "Mobile" from "van"), nudge position, set the limit, rotate the direction and pick One way / Both, edit the comment, **Play recording** | All edits stick. The comment shows the transcript of what you said. |
| R10 | Set a report to **Not there** and **Save**, then simulate past the camera you'd passed | That camera no longer alerts |
| R11 | Report **Fixed**, review, then simulate the same road again | The "My reports" marker alerts in your direction |
| R12 | **Discard** / **Browser** | Discard removes it. Browser copies the details and opens PGW's submission page. |
| R13 | **Send** (only if you mean to submit a **real** report) | Status goes queued → sent. **This submits to PocketGPSWorld.** |

### 6.7 POI import and editing (phone)
| # | Steps | Expected |
|---|---|---|
| I1 | **Import file**: your PGW iGO `pocketgps_uk_sc.txt` | About 17,000 POIs, 0 bad rows |
| I2 | Import the GPX from §4, a KML file, an OV2 file, and a zip containing one of them | Each imports, with the right counts |
| I3 | Import a CSV and set the columns | The preview shows the first rows; the import reports its count |
| I4 | A file with >20% bad rows | "Import failed: N bad rows out of M"; no set is created |
| I5 | Set editor: 2nd alert at 100 yd | An error is shown and **Save** is disabled |
| I6 | Change a template and press **Test speak** | It speaks the sample text through the phone's navigation audio |
| I7 | **Choose** an icon, or **From file** | The icon updates on the card and on the map markers |
| I8 | **Delete set** | Asks to confirm; the set and its markers are gone |

### 6.8 PocketGPSWorld sync (phone)
| # | Steps | Expected |
|---|---|---|
| P1 | Cameras: login → **Save and test** | "Logged in." A wrong password gives "Login failed…" |
| P2 | **Sync now** | "Installed" shows the version (e.g. 24.101); two sets appear (cameras + pending mobile sites, the latter with alerts off). You get a notification with the release summary. |
| P3 | **Sync now** again | "Already up to date" |
| P4 | **Force re-download** | Downloads again; your icon and text choices on the sets are kept |
| P5 | Start a drive, then **Force re-download** | The sync waits until guidance ends and then completes |
| P6 | **Forget login**, then Sync | The buttons are disabled |

### 6.9 Navigation from other apps
| # | Steps | Expected |
|---|---|---|
| N1 | On the DHU, use the Assistant mic: "Navigate to *a place*" (with AutoNavAlerts chosen as the navigation app) | AutoNavAlerts opens the route preview or search for that place |

---

## 6.10 Navigation SDK feature watch
Google's live incident reporting isn't available to apps on Android Auto yet (DESIGN.md §16 #21). After updating the Navigation SDK, run:
```bash
tools/check-nav-sdk-features.sh            # the version in gradle/libs.versions.toml
tools/check-nav-sdk-features.sh --latest   # the newest published version
```
It exits with code 10 and says so if `NavigationViewForAuto` has gained the reporting APIs. The same check runs **quarterly on GitHub** (Actions → "Navigation SDK feature watch", 1 Jan/Apr/Jul/Oct, or **Run workflow** to run it now). It opens an issue on the repo when support appears. The app then enables them automatically: Google's report button on the car map, and **More → Report to Google Maps** during navigation. Test those on the DHU. The log line `IncidentReporting: Incident reporting not supported on Android Auto…` confirms it's still off.

## 6.11 Diagnostics (G)
The two debug logs are always on (docs/SETTINGS.md §1.8). `run-as` works on debug builds only; on a release build use Save or Share.

| # | Steps | Expected |
|---|---|---|
| G1 | Car screen open, parked, for a minute; then `adb -e shell run-as io.autonav.alerts cat files/diag/debug-nav.log` and `… alert-debug.log` | Both files grow in **15 s blocks**: headers `--- HH:MM:SS–HH:MM:SS fixes=N src=phone hz=… ---` 15 s apart, each with its fix count; one `App start build=<commit> …` line per process in each file |
| G2 | Phone Settings → **Diagnostics** | A status line per file (size, oldest entry); **Show last 200 lines** shows the end of each, matching the `run-as` output |
| G3 | **Save debug-nav…** / **Save alert-debug…** to Downloads | `adb -e shell ls /sdcard/Download/` lists `autonavalerts-debug-nav-<date>.log` and `autonavalerts-alert-debug-<date>.log`, complete up to the last flushed block |
| G4 | **Share** | The Android share sheet opens with the file attached (pick Drive or an email app and check it arrives) |
| G5 | **Clear** | Both status lines go to 0 bytes; the next block written starts a fresh file (`App start` isn't repeated; the first new header follows within 15 s) |
| G6 | During a drive: `adb -e shell am force-stop io.autonav.alerts`, reopen the app on the DHU | The files end at the last complete block (**at most 15 s** missing) and the next line is a new `App start`; nothing earlier is lost |
| G7 | `adb -e shell am broadcast -a io.autonav.alerts.BENCH -p io.autonav.alerts --ez engine true` near cameras | logcat and `alert-debug` show the snapshot: last fix, stats, cache, route state, mutes, the **5 nearest POIs** with distance, bearing-to, off-axis, camera bearing and verdict, and the speech queue |
| G8 | Settings → Diagnostics → **Drive recording** on, drive for a few minutes, then once more on another day | `files/diag/drive-YYYYMMDD-HHMM.csv` grows while driving (`t_ms,kind,src,used,lat,lon,…` rows for every fix, phone and car); only the newest **3** recordings are kept; **Save drive recording…** exports the latest |
| G9 | Pull a recording and `tools/drive.py --replay drive-….csv --speed 2` | The emulator drives the recorded track; the app's alerts fire at the same cameras as on the recorded drive |
| G10 | `DRIVE_CSV=/path/drive.csv DRIVE_POIS=/path/pois.csv ./gradlew :core:test --tests DriveReplayTest` | The test prints the verdict/stage timeline for the drive and **reproduces its stages** (same cameras, distances within the replay tolerance); without the env vars it's skipped |

## 6.12 Real-car re-test (V)
The bench can't produce a real receiver, a real head unit or Android Auto's audio handling, so each fix is only accepted after a drive in the car. Protocol:

| # | Step |
|---|---|
| V0 | Install the build. Settings → Diagnostics → **Clear**, **Drive recording** on. Phone on the dash with a clear view of the sky. Note the phone's clock against the car's. Pick a route with **at least 10 cameras**, including **3 or more ONE_WAY cameras passed in both directions**, and a gentle bend. |
| V1 | Drive it. At every anomaly (an alert that didn't speak, a marker that vanished, a route that cleared), **say the time aloud** or tap **Report**: Report taps land in the logs as timestamps you can find afterwards. |
| V2 | Afterwards, with the car off: Settings → Diagnostics → **Save** both logs and **Save drive recording…**; `adb pull` them from Downloads. Keep them **out of the repo** (they contain your route and PGW camera ids). |
| V3 | Build a POI CSV of the cameras you passed (from `poi.db`, §3 step 5, "Passing real cameras") and run **G10** with the recording; then read the logs against the table below. |

Acceptance, per bug. "Lines" are in `alert-debug` unless they're navigation lines (`debug-nav`).

| Bug | Lines to look for | Pass when |
|---|---|---|
| 1 Route cleared on drive-off | `debug-nav`: `Guidance stop caller=`, `Trip navigationEnded`, `App start`, `Session create/destroy`, `Location source` in the preceding 30 s, `watchdog …`, `eco re-ask …` | A 10-minute guided drive from parked shows **no** `Guidance stop` other than the `caller=HOST` from the card's X at the end; the watchdog only ever logs `skip (…)`; every `eco re-ask` is `skip` or `Ask … RouteStatus OK` |
| 2 Google's dead controls | `debug-nav`: `Map tap x,y hit=`, `Session create api=` | No ETA card, header, recenter, speedometer or progress bar drawn by the SDK; the **X** on the host's travel-estimate card ends the route, before and after arrival, logged `caller=HOST` |
| 3 Alert timing | `stage FIRST/SECOND/PASSING poi= d= spd= expectedD= late=`, `Tts start waited=`, `Tts done` | For ≥ 90% of FIRST/SECOND, \|d − expectedD\| ≤ speed × 0.5 s + 10 m; FIRST ≥ 0.9 × (lead s × speed, floor 242 m), SECOND ≥ 0.9 × (half lead × speed, floor 142 m), PASSING ≤ 91 m; no `late=true` on a straight approach; PASSING `waited` ≤ 1 s; stage → speech ≤ 1.5 s; G10 reproduces the distances |
| 4 Markers vanish | `hide/unhide` (dim) with `bearingDiff`/`headingAcc`, `suppressed reason=`, `verdict` changes, `debug-nav` `heading src=` | No camera drawn AHEAD within 300 m then dimmed or hidden without a stage unless its bearing mismatch is > 45°; heading accuracy ≤ 6° above 30 mph for ≥ 95% of fix samples; no heading-source flapping faster than 10 s; `track reset` only at handovers and gaps |
| 5 Alerts not spoken | `Tts queued/start/done/replaced/dropped/interrupted/stuck/error`, `spoken=` on each stage, the `PASSED` line per camera | Every stage has `spoken=true` (except under mute); no `dropped`, `stuck` or `error`; every `PASSED` line for a camera facing our way lists all three stages spoken; two cameras < 200 m apart give four stage events all spoken within 8 s |
| Direction HARD RULE | `PASSED … NOT ALERTED reason=direction(…)`, `stage` | Every ONE_WAY camera passed against its bearing: **zero** stage events, drawn dimmed with its arrow; the same camera passed the right way: three stages |
| Parked / stopped | `heading src=`, `stage`, `suppressed reason=notMoving` while stopped | Parked ≥ 2 min and every stop > 30 s: engine speed < 0.5 m/s, heading goes to none, no `stage` and no `notMoving` suppression for a camera that alerts correctly later |
| Speed sources | `debug-nav` `Location … speed src=`, More → GPS | Engine speed and dial speed within 5% of each other and of the dashboard |
| Limit changes ahead (Part D) | `limitAhead new= d= via=`, `limitChanged` | For ≥ 80% of changes, `limitChanged` follows its `limitAhead` within the predicted distance ± 60 m; spoken once each; OSM data errors (a 30 where the sign says 20) are noted, not counted |
| 7 Recents | `debug-nav` `Places remember "<title>"` | Every destination you went to is in Recents after the drive |

Bug 1 is fixed only when the drive shows which candidate fired, or none; bug 5 only when every `PASSED` summary confirms all three stages for every camera facing our way.

---

## 7. Logs and diagnostics
The record of what the app did is in **two files on the phone**, written in 15-second blocks (docs/SETTINGS.md §1.8):

| File | Holds |
|---|---|
| `files/diag/debug-nav.log` | Session create/destroy and host stops; guidance (`setDestination`, `routeStatus=`, `start`, `stop caller=`, `rerouting`, `routeChanged`, `ecoReask status=`, `arrived`); the map (`ready`, `surface`, `tap x,y hit=`, `followOff`, `recenter`, `refollow d=`, `rebuild d=`, `watchdog …`); location (`Location request hz`, `source car↔phone`, `heading src= hdg=`, `speed src=`, a `fix` sample every 5 s, `gap ms=`, `track reset reason=`); car data; `Panel limit n src=`; search (`Search submitted/voice len=…`, `Places textSearch n=… status=…`, `Places remember "<title>"`, `NavigateIntent …`); `Bench <command>` |
| `files/diag/alert-debug.log` | `Alerts cache n=`, mutes, quiet spots; the engine trace (`enter`/`leave`, `suppressed reason=direction\|offRoad\|notAhead\|notMoving\|noHeading\|muted\|quiet d= offAxis= hdg= camBrg=`, `stage KIND poi= set= d= spd= hdg= brg= expectedD= late= overspeed=`, `stateClear reason=`, `hide/unhide`, `zone enter/exit/overspeed`); `limitAhead`/`limitChanged`; `Dispatch shown via=`; `Tts queued / start waited= / done / replaced / dropped reason= / interrupted / error status= / stuck`; and the **`PASSED` line per camera** |

Reading them:
```bash
# debug builds (emulator or phone):
adb shell run-as io.autonav.alerts cat files/diag/debug-nav.log
adb shell run-as io.autonav.alerts cat files/diag/alert-debug.log
# release builds: Settings → Diagnostics → Save (or Share), then
adb pull /sdcard/Download/autonavalerts-debug-nav-<date>.log
```
Each 15 s block starts with `--- HH:MM:SS–HH:MM:SS fixes=N src=car/phone hz=… ---`. A header gap means the app wasn't running (look for the next `App start build=… model=… android=… pid=`); up to the last 15 s before a hard kill is lost. The previous rotation is `*.1.log`.

The line that answers "what happened at that camera" is the one `PASSED` line written as its state clears:
```
PASSED poi=206409 set="PGW fixed cameras · 30 mph" dir=ONE_WAY camBrg=90 ourHdg=92±5 approach: enter d=512 → FIRST d=466 spoken(+0.8 s) → SECOND d=218 spoken → PASSING d=88 spoken
PASSED poi=15461 set="PGW pending mobile sites" dir=ONE_WAY camBrg=270 ourHdg=88±4 NOT ALERTED reason=direction(178°) dimmed at d=240
```

**logcat is only a mirror** of these lines (tag `AutoNav`, plus the older component tags), handy on the emulator bench; some phones, the OPPO among them, drop app log lines entirely, so never rely on it from a car drive:
```bash
adb logcat -s AutoNav AlertCoordinator AdaptiveLocation NavSession Guidance NavigatorHolder MapSurface PgwFeedCheck AndroidRuntime
```
- `NavigatorHolder: Navigator error N`: 1 = key not authorised, 2 = terms, 3 = network, 4 = location permission
- `--ez diag true` (§3 step 5a) prints the last 100 lines of each file to logcat; `--ez engine true` writes the engine snapshot

For a full bug report add `adb bugreport autonav-bugreport.zip` (battery and GPS history) to the two saved files.

## 8. Troubleshooting
| Symptom | Fix |
|---|---|
| `desktop-head-unit: error while loading shared libraries: libc++.so.1` | Install the C++ runtime (§1.2) |
| Emulator window opens, then the emulator disappears (crash) | Check `coredumpctl list`. On **AMD GPUs with Mesa 26.x**, `-gpu host` crashes in `radeonsi si_resource_from_memobj`: start with **`-gpu host -feature -Vulkan`**, which `tools/bench.sh` does by default. |
| SELinux alert "preventing RenderThread from using the execheap access" | Caused by `-gpu swiftshader_indirect` (software rendering needs executable heap memory). Use `-gpu host -feature -Vulkan` instead. We **don't** recommend `setsebool selinuxuser_execheap 1`, because it weakens protection for every program you run. |
| DHU says it's waiting for the phone | On the phone: **⋮ → Start head unit server** (again after a reboot). Then run `adb forward tcp:5277 tcp:5277` again. Check `adb devices`. |
| AutoNavAlerts isn't in the DHU launcher | Android Auto developer settings → **Unknown sources** on. Reinstall the APK. |
| DHU log ends with `connected.` then the DHU quits | Its console input ended (it was started in the background without `bench.sh`). Use `tools/bench.sh car`, which keeps it open. |
| DHU log shows `Received ByeBye 1 from phone.` and the DHU quits | The head-unit server dropped the session (often after the previous DHU was killed mid-session). Force-stop Android Auto (`adb -e shell am force-stop com.google.android.projection.gearhead`), **Start head unit server** again, then `bench.sh car`. |
| Emulator shows "Welcome to Android Auto" / car screen stays on "Connect a vehicle" | First connection: tap **Continue**, allow Android Auto's notification access (§3 step 2), go back. |
| Play Store says Android Auto "isn't compatible with your device" (emulator) | Expected on current emulator images: copy it from a phone (§3 step 2) |
| Car screen: "AutoNavAlerts has encountered an unexpected error" | Read the cause with `adb -e logcat -b crash`. The host keeps showing this screen after a fix is installed: press **Exit** or restart the DHU. |
| Car map frozen while the speed panel still changes | Before the follow watchdog (DESIGN.md §8) this happened after a big GPS jump. Now it should recover in 5–10 s; `debug-nav` shows `Map refollow d=…` (and `rebuild d=…` on the third miss). If it doesn't, restart the DHU and report it with the log. |
| No app lines in logcat at all (OPPO/ColorOS and some other phones) | The phone drops app log output. Use the files instead: `adb shell run-as io.autonav.alerts cat files/diag/debug-nav.log` on a debug build, or Settings → Diagnostics → Save/Share on release (§7). |
| Speed panel shows 0 while the car moves | The GPS feed has no speed. `tools/drive.py` sends it; `adb emu geo fix <lon> <lat>` on its own doesn't. |
| Host log: `The car app does not have the required permission: …CAR_SPEED` | Phone Settings → Permissions → **Allow** (car speed), or `pm grant` it on the emulator |
| DHU shows "Finish setup on your phone" | Do what the message says in the setup sections of the phone's Settings tab (§2.3) |
| No **Simulate** button | You installed a release build; use `app-debug.apk` |
| The car moves but no alerts fire | Check that the set's **Alerts** are on, the POI is on the road and faces your direction (§4), and the log shows `Cache: N POIs` with N > 0. In free drive (no route) the phone's own GPS must be moving; use set-up B's route playback or a mock-location app. |
| Alerts show but nothing is spoken | Settings → **Voice** on. More → Unmute. The set's **Sound** on. Phone media volume up. Then check `alert-debug` for the stage's `Tts` lines: `dropped reason=`, `stuck` or `error status=` say which part of the speech path failed. |
| No turn cards (map only) | Make sure you pressed **Go** or **Simulate**, not just viewed the preview. Check the network connection. |
| Sync says "Subscription required" | Your PocketGPSWorld subscription has lapsed |
| Sync says "Site changed" | PocketGPSWorld changed their pages. Use **Open download page** and import by hand, and report it. |
