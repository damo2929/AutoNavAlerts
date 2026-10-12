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
5a. **Driving the car screen from adb (debug builds):** the DHU's window can't be clicked from a script and its `dpad`/`dpad rotate` commands don't move focus into the app's screens, so debug builds have a bench hook, `BenchReceiver` (in `app/src/debug`, so release builds don't have it; protected by the DUMP permission, which only adb's shell holds). Always add `-p io.autonav.alerts` (Android only delivers explicit broadcasts to manifest receivers):
   - `adb -e shell am broadcast -a io.autonav.alerts.BENCH -p io.autonav.alerts --es q "Mum"`: opens Search with "Mum" typed in (the same navigate intent Assistant sends)
   - `… --es geo "53.38,-1.47"`: route preview to that point
   - `… --es contact "Mum"`: as if the first matching contact row was tapped (locate, cache, route preview); logcat `Bench: Contact Mum (Home): Found(…)`
   - `… --es zoom -2`: like tapping − twice; logcat `Bench: Zoom now 15.5`
   - The DHU's `screenshot` command shows the result. Taps by a person on the DHU window work as normal.
6. **Or with the emulator's own controls:** open the emulator's **Extended controls (⋯) → Location → Routes**, plan a route (or load a GPX/KML under **Single points → Load GPX/KML**), set the playback speed, and press **Play route**. The phone's GPS then moves along it, so camera alerts work in **free drive**, without the Simulate button.

---

## 4. Test data: getting POIs on your test route
Alerts only fire for POIs that are **on the road you're driving** (within 40 m of your path in free drive, or 50 m of the route when navigating) and that **face your direction of travel**. Use one of these:

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
5. Press **Stop** on the action strip to end the drive. On the DHU, Android Auto's own "auto drive" developer option also starts a simulation if a route is set.

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
| M8 | Simulate to the end of the route | "You have arrived", with the destination name. **Stop** clears it. |
| M9 | **Recents** (clock icon) → the delete icon on a row (car API 6+) | The row is removed. On older hosts, tap the row → **Remove**. |
| M10 | **Recents → Clear all → Yes** | All recents are removed; Home/Work/favourites stay |
| M11 | Switch DHU day/night (DHU console `help` lists the command) | The map switches between day and night styles |
| M13 | Phone Settings → Permissions → **Allow contacts**; add a contact with a Home address (`adb -e shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact -e name "Test Mum" -e postal "10 Downing Street, London SW1A 2AA"`, then Save) | The Contacts line turns ✓ and **Clear contact locations** appears |
| M14 | Car → Search → type "test" (or "mum") | "Test Mum" is the first row (contact icon, "Home · 10 Downing Street, London SW1A 2AA"), above the Places results |
| M15 | Pick the contact row | Route preview to 10 Downing Street titled "Test Mum (Home)"; Recents now has "Test Mum (Home)" with the address |
| M16 | Search "test" again | The contact row now shows its distance (the location is cached); picking it doesn't search Places again |
| M17 | Car → Search (empty) → **Contacts** → type a name | Contacts only, hint "Search your contacts", no Places results |
| M18 | A contact address that matches several places with no postcode match (e.g. "High Street") | The list shows "Which address for <name>?" rows; picking one routes there and remembers it |
| M19 | Revoke contacts in Android settings, then search | No contact rows and no Contacts entry; Places search as before |
| M12 | During a free drive, restart `tools/drive.py` with a route a few km away | The car map catches up with the car within about 10 s ("Map is … m from the car: following again" in logcat) |

### 6.3 POI alerts (simulate past test POIs from §4)
| # | Steps | Expected |
|---|---|---|
| A1 | Drive towards a camera facing your direction | **1st alert** 15 s ahead at your current speed (at least 242 m / 265 yd; e.g. ~470 m at 70 mph), **2nd alert** 7 s ahead (at least 142 m / 155 yd; ~220 m at 70 mph), **passing alert** at 100 yd. logcat `AlertCoordinator: FIRST … at N m, S mph` shows the distance matching speed × seconds. Each is a card on the DHU and a spoken alert. |
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
| A11 | Settings → **Voice** off | Cards still show, with no speech |
| A12 | DHU **More → Mute alerts for 30 min** | Silent cards. **Unmute** restores speech. |
| A13 | Before the map has loaded (e.g. right after opening the app), an alert fires | Android Auto's own alert card is used instead, with **Dismiss** and **Mute 30 min**; **Mute 30 min** silences only that set |
| A14 | **More → POI layers**: switch a set off | Its markers disappear and its alerts stop |
| A15 | Tap Report, then keep driving past the spot | **No** "My reports now" alert on this pass (it's quiet until you're 300 m away); on a later pass it alerts normally |

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
| X25 | Car: open Search with nothing typed (debug: `--ez search true`) | **Home** (house icon) and **Work** (briefcase) are the first rows with their addresses; tapping one opens the route preview |
| X26 | **Get from my Google account** (UK account that is a test user of the app's OAuth consent screen) | Google's consent screen for "places you labeled on Maps", then "Google is preparing your places…", then "Home and Work updated…"; the card shows both addresses; a place without a position is found by its address (logcat `HomeWork: Labeled places: N found; saved [Home, Work]`) |
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

### 6.4a Car speed and car GPS (start the DHU with `-c config/default_sensors.ini`)
| # | Steps | Expected |
|---|---|---|
| C1 | DHU started with `default_sensors.ini`, no simulation running | **More → GPS** shows "Location from car · speed from car". The speed panel shows the speed the DHU reports. (Change the DHU's speed and location with its console; type `help` for the sensor commands.) |
| C2 | Settings → **Speed and location source → Phone only** | **More → GPS** shows "Location from phone · speed from phone" |
| C3 | DHU with the default config (no sensors) | Falls back: "Location from phone · speed from phone" |
| C3a | With car GPS in use, look at the log (`adb logcat -s AdaptiveLocation`) | The phone stays at "Requested 10 Hz" as the backup. Quit the DHU sensor feed or switch to Phone only: within about 2 s phone fixes drive the alerts again. |
| C3b | Set the DHU's car location onto a road with a test camera, then move it along the road | Alerts fire from the car's position |
| C4 | Car speed at 50+ mph (DHU sensor) | **More → GPS** "Requested" goes to 10 Hz within about 2 s, without waiting for GPS |
| C5 | During a **Simulate** drive | The simulated speed is used (car speed is ignored), so alerts follow the simulated car |

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

## 7. Logs and diagnostics
Live log, filtered to the app's components:
```bash
adb logcat -s AlertCoordinator AdaptiveLocation NavSession Guidance NavigatorHolder MapSurface PgwFeedCheck AndroidRuntime
```
- `AdaptiveLocation: Requested 10 Hz`: location started (it never changes rate now)
- `AlertCoordinator: Cache: N POIs around cell …`: POIs loaded near the car (should be > 0 near your test POIs)
- `NavigatorHolder: Navigator error N`: 1 = key not authorised, 2 = terms, 3 = network, 4 = location permission

To save logs for a bug report:
```bash
adb logcat -d > autonav-log.txt
adb bugreport autonav-bugreport.zip      # full report, including battery/GPS history
```

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
| Car map frozen while the speed panel still changes | Before the follow watchdog (DESIGN.md §8) this happened after a big GPS jump. Now it should recover in 5–10 s; logcat shows `MapSurface: Map is … m from the car`. If it doesn't, restart the DHU and report it. |
| Speed panel shows 0 while the car moves | The GPS feed has no speed. `tools/drive.py` sends it; `adb emu geo fix <lon> <lat>` on its own doesn't. |
| Host log: `The car app does not have the required permission: …CAR_SPEED` | Phone Settings → Permissions → **Allow** (car speed), or `pm grant` it on the emulator |
| DHU shows "Finish setup on your phone" | Do what the message says in the setup sections of the phone's Settings tab (§2.3) |
| No **Simulate** button | You installed a release build; use `app-debug.apk` |
| The car moves but no alerts fire | Check that the set's **Alerts** are on, the POI is on the road and faces your direction (§4), and the log shows `Cache: N POIs` with N > 0. In free drive (no route) the phone's own GPS must be moving; use set-up B's route playback or a mock-location app. |
| Alerts show but nothing is spoken | Settings → **Voice** on. More → Unmute. The set's **Sound** on. Phone media volume up. |
| No turn cards (map only) | Make sure you pressed **Go** or **Simulate**, not just viewed the preview. Check the network connection. |
| Sync says "Subscription required" | Your PocketGPSWorld subscription has lapsed |
| Sync says "Site changed" | PocketGPSWorld changed their pages. Use **Open download page** and import by hand, and report it. |
