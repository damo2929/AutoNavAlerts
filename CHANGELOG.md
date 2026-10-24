# Change log

What changed for the person using the app, by day. Each entry names the DESIGN.md section that holds the rule, where there is one; the commit history has the rest. Newest first.

## 2026-10-24 — `dcd5f02`, `a84061d`

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
