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
Open **AutoNavAlerts** on the phone and complete the **Setup** tab:
1. **Google Maps API key** → paste your key → **Save key**. It needs the Navigation SDK and Places API (New).
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
2. In the emulator: sign in to Google Play and install/update **Android Auto**. Then do §2.2 steps 2–4 (developer mode, Unknown sources, Start head unit server).
3. Install the APK and do the app set-up (§2.3), using `adb -e install -r …`.
4. Start the DHU exactly as in §2.4 (`adb forward tcp:5277 tcp:5277`, then `desktop-head-unit`).
5. **Moving the car without a route:** open the emulator's **Extended controls (⋯) → Location → Routes**, plan a route (or load a GPX/KML under **Single points → Load GPX/KML**), set the playback speed, and press **Play route**. The phone's GPS then moves along it, so camera alerts work in **free drive**, without the Simulate button.

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
1. On the phone: **Settings → Bench testing → Simulate speed**. **1×** (real time) is the most realistic for alerts and the GPS rate. Use 2× or 4× for long routes.
2. On the DHU: **Search** (magnifier) → type a destination a few miles away → pick a result.
3. On the route preview, press **Simulate** (top right) instead of **Go**.
4. The car drives the route. The turn cards update, the voice speaks, markers update, and POI alerts fire as you pass them.
5. Press **Stop** on the action strip to end the drive. On the DHU, Android Auto's own "auto drive" developer option also starts a simulation if a route is set.

> While simulating, the alert engine uses the **simulated** position instead of the phone's GPS. The phone itself stays still, and you'll see the GPS rate change with the simulated speed.

---

## 6. Test checklist
Work through these and tick off each expected result. "DHU" means the car screen and "phone" means the AutoNavAlerts phone app.

### 6.1 Setup and error states
| # | Steps | Expected |
|---|---|---|
| S1 | Phone Setup: **Remove** the API key, then open the app on the DHU | The DHU map area shows "Finish setup on your phone: Add your Google Maps API key…" |
| S2 | Add the key back. Then, in a fresh install, open the DHU before accepting the terms | "…accept the navigation terms" |
| S3 | Accept the terms, then reopen the app on the DHU | The map loads and follows your position, with traffic shown |
| S4 | Enter a wrong key | "The Maps API key isn't authorised for the Navigation SDK" |

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

### 6.3 POI alerts (simulate past test POIs from §4)
| # | Steps | Expected |
|---|---|---|
| A1 | Drive towards a camera facing your direction | **1st alert** at the 1st-stage distance (default: 30 s ahead, at least 500 yd), **2nd alert** at 300 yd, **passing alert** at 100 yd. Each is a card on the DHU and a spoken alert. |
| A2 | Look at the card | The POI icon, the text from the set's template, the distance and the limit; **Dismiss** and **Mute 30 min** buttons |
| A3 | Set editor: change the 1st alert to "Distance 800 yd", save, drive again | The 1st alert now fires at about 800 yd |
| A4 | Set editor: switch the **2nd alert** off | Only the 1st and passing alerts fire |
| A5 | Set editor: turn **Sound** off | Cards only. **No** speech, and no passing alert. |
| A6 | Simulate the **opposite** direction past a one-way camera | **No** alert |
| A7 | A camera on a parallel road or slip road | **No** alert |
| A8 | Two cameras close together at 2×–4× speed | Both passing alerts are spoken; neither is skipped |
| A9 | Average-speed (SPECS) stretch from PGW data | "Entering average speed zone, 50 limit" at the first camera, then "Leaving average speed zone" about 5 km after the last one |
| A10 | Over-limit: with the default **−0.1%**, drive at or just over a camera's limit (higher simulate speed) | "…you are over the limit" is added to the spoken alert |
| A11 | Settings → **Voice** off | Cards still show, with no speech |
| A12 | DHU **More → Mute alerts for 30 min** | Silent cards. **Unmute** restores speech. |
| A13 | **Mute 30 min** on one card | Only that set goes silent |
| A14 | **More → POI layers**: switch a set off | Its markers disappear and its alerts stop |

### 6.4 Map extras
| # | Steps | Expected |
|---|---|---|
| X1 | Zoom to 11 or closer near POIs | Markers with icons and speed badges. Up to 200, nearest first. |
| X2 | Watch markers during the drive | Cameras **ahead** facing you are full colour, others faded, and the one being alerted is **larger with a red halo**. They update on every GPS fix. |
| X3 | Speed panel (bottom corner) | Your speed in mph or km/h, updating smoothly. It's the car's speedometer figure when the car provides speed (§6.4a). |
| X4 | Go over the road limit | The panel turns **amber** (5% over) or **red** (10% over), and Google's limit sign appears (it stays for 60 s) |
| X5 | During a camera alert, under the limit | The camera's limit appears with a small **blue dot** |
| X6 | Settings → Speed panel off | The panel disappears |
| X7 | **More → GPS** at different simulated speeds | "Requested" is 1 Hz below 10 mph, rising to 10 Hz at 50 mph+ (e.g. about 6 Hz at 30 mph). "Actual" shows what's delivered. |
| X8 | Settings → GPS rate: "max 5 Hz", "full at 40" | At 40 mph+ the requested rate is 5 Hz |

### 6.4a Car speed and car GPS (start the DHU with `-c config/default_sensors.ini`)
| # | Steps | Expected |
|---|---|---|
| C1 | DHU started with `default_sensors.ini`, no simulation running | **More → GPS** shows "Location from car · speed from car". The speed panel shows the speed the DHU reports. (Change the DHU's speed and location with its console; type `help` for the sensor commands.) |
| C2 | Settings → **Speed and location source → Phone only** | **More → GPS** shows "Location from phone · speed from phone" |
| C3 | DHU with the default config (no sensors) | Falls back: "Location from phone · speed from phone" |
| C3a | With car GPS in use, look at the log (`adb logcat -s AdaptiveLocation`) | "Requested 1 Hz" for the phone (the backup rate). Quit the DHU sensor feed or switch to Phone only: within about 2 s the phone's adaptive rate returns. |
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
Before testing, allow the **microphone** on the phone's Setup tab. On the DHU, the car's microphone is your PC's microphone.

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

## 7. Logs and diagnostics
Live log, filtered to the app's components:
```bash
adb logcat -s AlertCoordinator AdaptiveLocation NavSession Guidance NavigatorHolder MapSurface PgwFeedCheck AndroidRuntime
```
- `AdaptiveLocation: Requested N Hz`: the GPS rate changes
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
| DHU says it's waiting for the phone | On the phone: **⋮ → Start head unit server** (again after a reboot). Then run `adb forward tcp:5277 tcp:5277` again. Check `adb devices`. |
| AutoNavAlerts isn't in the DHU launcher | Android Auto developer settings → **Unknown sources** on. Reinstall the APK. |
| DHU shows "Finish setup on your phone" | Do what the message says on the phone's Setup tab (§2.3) |
| No **Simulate** button | You installed a release build; use `app-debug.apk` |
| The car moves but no alerts fire | Check that the set's **Alerts** are on, the POI is on the road and faces your direction (§4), and the log shows `Cache: N POIs` with N > 0. In free drive (no route) the phone's own GPS must be moving; use set-up B's route playback or a mock-location app. |
| Alerts show but nothing is spoken | Settings → **Voice** on. More → Unmute. The set's **Sound** on. Phone media volume up. |
| No turn cards (map only) | Make sure you pressed **Go** or **Simulate**, not just viewed the preview. Check the network connection. |
| Sync says "Subscription required" | Your PocketGPSWorld subscription has lapsed |
| Sync says "Site changed" | PocketGPSWorld changed their pages. Use **Open download page** and import by hand, and report it. |
