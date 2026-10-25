# Change log

What changed for the person using the app, by day. Each entry names the DESIGN.md section that holds the rule, where there is one; the commit history has the rest. Newest first.

## 2026-10-25 — the reduction pass and the test tier (uncommitted at the time of writing)

- **No change to what you see or hear.** A second pass of reuse and plainer Kotlin took the code from 10,971 to about 10,330 code lines (11,816 before the reuse pass: −13% over both). The settings file, logs, notifications and screens are the same. The owner was told again that 50% is not reachable with every function kept, and chose this ceiling plus the removal of code no user could reach (owner, 2026-10-25).
- **Removed because nobody could reach it** (owner, 2026-10-25): the stand-by code for Google's incident reporting on the car, which the Navigation SDK does not offer on Android Auto (a quarterly check still watches for it; DESIGN §16 row 21); the unused favourite and undo calls in the places search (favourites from older data still show; §8.2); an unused guidance stop reason (§9.3); an unused speech shutdown (§12.3).
- **Many more automatic tests**, including tests that run Android code on the computer (Robolectric): the car screens' templates, the database, the camera-download jobs, notifications, the map views and speech. They pin today's behaviour so later changes can't drift from the design (DESIGN §15.1).

## 2026-10-25 — the reuse pass (`1311634`)

- **No change to what you see or hear.** The code was made smaller by reusing what was already there: 11,816 → 10,971 code lines (comments not counted, −7%), across 70 files; the behaviour, settings file, logs and screens are the same. The 50% cut the owner first asked for is not reachable without removing features; the owner chose the reuse pass only (DESIGN §3, the regenerated class map).
- **Mute could no longer wedge speech** (found in passing while NavTts was simplified): muting while a *direction* was being spoken used to leave the speech queue waiting for ever, so every later alert and direction was silent until the app restarted. Now mute stops alerts, leaves the direction to finish, and the queue moves on (DESIGN §12.3).

## 2026-10-24 — `dcd5f02`, `a84061d`

- **README, docs/INSTALLATION.md and docs/SETTINGS.md rewritten in Easy Read** (owner: "update all documentation using the croit-easy-read skill"): short lines, one idea per sentence, plain words with every abbreviation expanded, a contents list, labelled "Technical detail" pointers to DESIGN.md, image placeholders on the harder ideas, and a first-published / last-updated footer. Facts were kept, and the stale ones fixed on the way: the GPS rate (1 Hz asked, not 10 Hz), the 3 s car-to-phone fallback with the two-fix return, pending mobile site alerts on by default, one set per file for the default OV2 download, a single "Announce speed limit changes ahead" row, and the test status (on Play's internal testing track, 214 + 30 unit tests). DESIGN.md, TESTING.md, AGENTS.md, CHECKPOINT.md and ISSUES.md keep their precise wording (owner's choice).
- **Design document made to match the code** (owner: "the design doc must match the code"): §3's class map is regenerated from the real source tree (the old one listed planned names such as PoiAlertEngine, AutoMapRenderer and NavForegroundService); §2, §9.3 and §10.5 now say how the car session keeps location and alerts alive (there is no foreground service of our own); §12.4's POI alert on the cluster is marked designed-not-built. **Twelve Mermaid diagrams** added, drawn from the code's class names and call paths: components and the fix-to-speech sequence (§2), Room entities (§4.1), the staging swap (§4.4), the import pipeline (§5), the PGW sync (§6.1), the car screen graph and the map surface (§8), the location feeds (§10), the alert engine per candidate (§11), dispatch (§12) and the speech scheduler (§12.3). GitHub renders them in place.
- **Source comments for contributors**: every Kotlin file carries a KDoc naming its DESIGN.md section, thread and the hard rule it upholds, with the why behind the numbers; comments only, no code change (AGENTS.md §4).
- **AutoNavAlerts is on Google Play's internal testing track** (22:10): testers whose Google account is on the list install it from the Play Store through the link in [docs/INSTALLATION.md](docs/INSTALLATION.md), with no adb, no developer mode and no Unknown sources. Play App Signing uses the shared key, so the SHA-1 and your Maps key are unchanged (§1.3 Distribution, §13.3 "Google Play"). Release 1 is `0.0.2-2026-10-24`; release 2 adds native debug symbols to the bundle and bumps the version code (§13.3).
- **Installing on a phone for the car:** `adb install -r -i com.android.vending …` (the Play Store recorded as installer). The phone's Customise Launcher does not keep a sideloaded Car App Library app, and Google says Unknown sources doesn't cover such apps, so a Google Play internal testing release was chosen as the route, and went live the same evening (above; §1.3 Distribution, §16 row 10). README, docs/SETTINGS.md and TESTING.md updated; `tools/bench.sh install` uses the same flag.
- **Application ID is now `uk.damiendye.autonav`** (was `io.autonav.alerts`), for the Google Play listing (§13.3). Android sees it as a new app: install it, restore your settings backup, then uninstall the old one. Keys made by **Create a key with my Google account** are made again for the new package; a pasted key needs the new package in its Android restriction.
- **New [docs/INSTALLATION.md](docs/INSTALLATION.md)**: the test user's guide from the Releases APK to a working car screen, with the install flag, phone setup, Android Auto's Customise Launcher check, cameras and limits, and how to send the diagnostics logs.
- **The car view is the same with and without a route**, and it is held: tilted, heading up, at the zoom you chose, the car at one spot. The zoom is locked while the map follows the car, so the Navigation SDK's guidance auto-zoom can't change it; it unlocks while you have panned or while the route preview shows its overview (§8 "One view with and without a route").
- **The zoom you pick with + / − lasts the whole car session**, route or no route; recenter only puts the car back (§8).
- **The follow watchdog works during guidance too** and judges "in place" by where the car is drawn (inside the map at the locked zoom), after the SDK dropped its own guidance camera once on a bench drive (§8 "Follow watchdog").
- **Passing alert 1 s before the camera at the current speed**, never less than 20 yd, measured along the track (§11.1). It was a fixed 20 yd, which fell between fixes at 55 mph.
- **Phone GPS at the rate the phone offers**, never forced faster; car GPS and speed stay at the fastest rate the car provides (§10.2). It was a forced 10 Hz.
- **The car is centred across the map panel** the host shows our map in (full width, or either side of the media panel), and the host's cards (route preview, turn card, trip card) float over the map without pushing it (§8 "Where the car sits").
- **Our buttons and the car are back in place after the app restarts under a connected head unit**: the host's last visible areas are kept per surface size and restored (§8 "Visible and stable areas").
- Camera markers use the supplied icon only, no speed badge, so the direction arrow's head stays clear (§7). Markers appear as soon as they are anywhere on the screen, within 3 km of the car (§8.1).
- Limit-change announcements stop at a fork in the road instead of guessing the branch (§8.4a). Free drive re-walks the road ahead after a 10° turn (§11 step 4).
- Pending mobile camera sites alert by default (§5.2).
- Google sign-in for creating a Maps key no longer trips "incremental auth is not allowed" (§13.1).
- Bench: `tools/bench.sh reload` loads a new build cleanly under a connected head unit.

## 2026-10-23 — `ea055aa` … `06669ea`

- **We speak the directions ourselves**, in the phone's default TTS voice, with the SDK's voice off; one voice for directions and alerts (§12.3).
- **Car screen like Google Maps**: the car arrow fixed in place, templates re-sent only on change, our buttons on the right whatever the driver's side (§8).
- **Free drive measures cameras against the road ahead** (OpenStreetMap connectivity walk), not a straight line, so cameras on a nearby parallel road don't alert (§11 step 4).
- The next speed-limit change is marked on the map (the number on a light-grey square) and announced from its own position (§8.4a).
- Google's derived limit snaps to a real limit, from a fresh update only (§8.4).
- Route matching locates the car once per fix (§11 step 4).
- Settings → Connecting to a car: car set-up and head-unit debugging steps.
- Code-review fixes across speech, alerts, location, the car session and guidance.

## 2026-10-13 — `e852d2d` … `ec7b899`

- **Diagnostics**: two always-on 15-second logs on the phone (`debug-nav.log`, `alert-debug.log`), a drive recorder and replay, Settings → Diagnostics (§15.5).
- **Alert engine rebuilt** from the first real-car drive: verdicts per camera, the trace, the corridor, the trigger ratchet, the tracked latch, state hygiene (§11).
- **Hard rules**: nothing on the car screen that doesn't work, nothing shown twice, one cancel (Google's X), no shake (§1.3, §8).
- Lead-time slider for the 1st and 2nd alerts (seconds at the current speed); limit-changes-ahead switch (§11.1, §13).
- The SDK's own on-map controls are turned off (dead on Android Auto); our speed panel, banner and buttons instead (§8).
- Markers: emphasis from the engine's verdict; cameras facing away drawn dimmed with their arrow, never hidden (§8.1).
- Speech: the scheduler, interruption handling, staleness by distance; limit changes spoken as their own kind (§12.3).
- More screen: live GPS and Alerts rows; Recents reload; Settings tab tidied; About shows the build.
- Bench realism for `drive.py` (noise, dropouts, car lag), recording/replay, scripted scenarios, `--es import`.
- Code shrink: dead code deleted, duplicated helpers merged.

## 2026-10-12 — `523b417` … `3e88a58`

- **UK speed limits from OpenStreetMap** (one Geofabrik download, no live lookups) shown next to the speed when Google and the cameras have none (§8.4a).
- EV-efficient and toll-avoiding routing; time-based alerts; traffic layer always on; the alert banner placed from the start (§8, §11).
- Home and Work from Google tried and removed (sign-in and API cost); the Google Cloud pieces cleaned up.
- About shows the git commit the app was built from.
- Design written for the first real-car drive's findings (§16 rows 22–27).

## 2026-10-11 — `dea99f6` … `71a4105` (first build)

- Android Auto navigation with direction-aware POI alerts, three stages, the direction hard rule (§11).
- Car speed and car GPS from the head unit when the car provides them (§10).
- PocketGPSWorld sync: TomTom OV2 download with direction, make and camera ids; one set per file, never merged; the download's own icons per set (§5.2, §6).
- Manual import of iGO, OV2, KML, GPX, CSV and zips; names from the files; swipe-left to delete a set (§5).
- One-tap camera reports in the car with a spoken comment; review and send on the phone only (§8.5).
- Setup: accept the navigation terms once; per-permission status.
- Quarterly GitHub Actions watch for Navigation SDK incident reporting on Android Auto (free runner, 10-minute cap).
- Test bench: emulator + Desktop Head Unit scripts, `drive.py`, settings docs, CHECKPOINT.md.
