# Checkpoint: session 2 (2026-10-12, afternoon); session 1 notes below

> Read this first when resuming. Then read [DESIGN.md](DESIGN.md) (the source of truth), [TESTING.md](TESTING.md) (the bench guide) and [docs/SETTINGS.md](docs/SETTINGS.md).
> The code is AI-assisted (Claude). Commits are by `Damien Dye <damien@damiendye.uk>`. **Never use the croit email in this repo.**

## 1. Where things stand

- **Repo:** `git@github.com:damo2929/AutoNavAlerts.git`, branch `main`, last commit `7fd7bb6` (session 3 notes in §4b). The working tree was clean at checkpoint.
- **Build:** `./gradlew :app:assembleDebug` succeeds, lint passes, and the `:core` JVM tests pass: **75 tests, 0 failures, 3 skipped**. The 3 skipped are the optional real-file tests, which only run when `PGW_IGO_FILE`, `PGW_ZIP` or `PGW_OV2_ZIP` is set.
- **Version:** v0.1.0 debug.

**Test bench on this PC:**
- Android SDK in `~/Android/Sdk`.
- `auto-bench` emulator: Android 16 Google Play image, 2 vCPUs, `-gpu host -feature -Vulkan`.
- The DHU is installed, and its `libcxx`/`libcxxabi` libraries are installed.
- **The emulator was left running** (`emulator-5554`) with the latest APK installed.
- Restart it with `tools/bench.sh phone`, then `tools/bench.sh install`.

## 2. What the user has tested and confirmed (on the emulator)

| Area | Result |
|---|---|
| Setup tab: permissions | ✓ per-permission ✓/✗ lines, "All permissions allowed" |
| PocketGPSWorld login (Cameras tab) | ✓ "Logged in." |
| PGW RSS feed | ✓ Latest 24.101 with the release notes |
| PGW sync, **TomTom OV2 "UK speed zoned complete" (package 1)** | ✓ "Imported 19826 cameras in 33 sets", one set per file |
| PGW-supplied icons per set (`_X2.bmp`) | ✓ e.g. SPECS 20/30/40… signs shown on the POIs tab |
| Swipe-left to delete a set | Built and installed. **Not explicitly confirmed by the user yet.** |
| Manual import of `/sdcard/Download/POIs.zip` (8 tram/metro OV2 files + bmp icons) | ✓ **8 sets, names from the files, each with its icon** (user: "the import worked, all 8 sets are showing, good") |

## 3. Verified by me (logs, UI dumps, tests), not yet by the user

- **The iGO download option** (package 288, consolidated by type): it imported 19,826 cameras in 5 sets with the correct counts. The user then switched to OV2, which is now the default.
- **Real-file checks:**
  - The iGO single-file importer read all 17,310 cameras plus 2,516 pending mobile sites, with 0 errors.
  - The OV2 parser read 33 files, 19,826 cameras.
  - Cross-checking OV2 against iGO: `R-` is both directions in 99.9% of cases, and the compass point is within 22.5° of iGO's heading for 98%.
- **The GitHub Actions quarterly SDK check**:
  - Run by hand: no Android Auto support yet, so no issue was opened.
  - Test-mode runs created issue #1 and then commented on it, rather than opening a duplicate.
  - The test issue was deleted afterwards.

## 4. Not yet tested (needs doing next)

1. **Navigation terms:** accept once on Setup and confirm it stays accepted after an app update. The SDK lost the earlier acceptance; the fix records it in the app's own settings.
2. **Maps API key:** the user has to enter their own key in Setup. Without it, the map, search and navigation don't work.
3. **The whole car screen**, using the Desktop Head Unit. **Blocked on the emulator:** Play says Android Auto "isn't compatible with your device" (the emulator only has a stub). Options:
   - (a) the user plugs in their **real phone**, with USB debugging on, and we use TESTING.md set-up A, or
   - (b) `adb pull` Android Auto from their phone and `adb install-multiple` it onto the emulator. It may still refuse to run.
   - Don't fetch Android Auto from third-party APK sites.
4. The TESTING.md §6 checklist on the car screen:
   - map and guidance, the speed panel
   - alerts (3 stages, the compulsory 100 yd passing alert, direction checks)
   - media ducking and split screen
   - one-tap Report with a voice comment
   - car speed and car GPS (DHU `-c config/default_sensors.ini`)
   - recents delete
5. **Phone Reports tab review and Send.** Send submits a **real** report to PGW, so only press it deliberately.
6. Confirm swipe-to-delete works, and the set editor rename.

## 4a. Session 2 (2026-10-12, DamiensLaptop) — open items

- **Bench rebuilt on DamiensLaptop**: JDK 21 in `~/.local/jdk` (not on PATH; set `JAVA_HOME`), Android SDK in `~/Android/Sdk`, `auto-bench` emulator, `gcloud` in `~/.local/google-cloud-sdk`.
- **Shared signing key** (DESIGN.md §13.3): every build is signed with it. It's in the **private** repo `damo2929/autonavalerts-signing`; on any other PC run
  `git clone git@github.com:damo2929/autonavalerts-signing.git ~/.config/autonavalerts` before building. SHA-1 `6C:48:A1:E5:4D:22:D5:0E:D5:C5:6E:E4:86:9E:E5:FF:AC:9D:2C:0F`.
- **To do — one-time Google registration** so "Create a key with my Google account" works for every user:
  1. ✓ Done 2026-10-12: project **`autonavalerts-app-6c5c7b`** created with `gcloud`, and Cloud Resource Manager, Cloud Billing, Service Usage and API Keys enabled. (No billing on this project: it only registers the app.) The maintainer's own Maps key lives in a separate project, **`damien-maps-b17139`**, billed to their billing account "Billing" (`010E61-042750-D625BF`). The key ("AutoNavAlerts io.autonav.alerts") is restricted to `io.autonav.alerts` with the shared SHA-1 and the old laptop debug SHA-1 `40:B4:…:74:29`, and to the Navigation SDK, Maps SDK for Android and Places API (New). It is saved in the app on the emulator and is never committed; read it back with `gcloud services api-keys get-key-string`.
  2. ✓ Done 2026-10-12 (in Chrome, by Claude at the user's request) — but in **`damien-maps-b17139`**, not `autonavalerts-app-6c5c7b`: Google Auth Platform configured (app name AutoNavAlerts, External, **Testing**, support/contact email the outlook address, test user the outlook address), scopes `cloud-platform` (sensitive) and `dataportability.maps.aliased_places` (restricted), and an **Android OAuth client** "AutoNavAlerts Android (shared signing key)" for `io.autonav.alerts` + the SHA-1 above. Google says a new client can take minutes to hours to take effect. Calls made with that sign-in are billed/quota'd to the client's project, so **Cloud Resource Manager, Cloud Billing, Service Usage, API Keys and Data Portability** were enabled there with `gcloud services enable` (first try on the phone failed with "Cloud Resource Manager API has not been used in project 893733140226"). To move it to the dedicated project later: repeat the same four pages there, enable those five APIs, and delete the client here.
  3. Before sharing beyond 100 users: Google verification of that scope.
  4. ✓ **First real user run, 2026-10-12 19:31 on the user's phone (CPH2791, Android 16, release build):** the button signed in, found `damien-maps-b17139` by its label and created the key "AutoNavAlerts · io.autonav.alerts · 6C:48:A1:E5" in it. The app then **crashed** when the Navigation SDK started: R8 had shrunk the SDK (`IllegalAccessException: …navigation.internal.amv.k is not accessible from jv6`). Fixed with `-keep` rules for `com.google.android.libraries.navigation.**` and `com.google.android.gms.maps.**` in `app/proguard-rules.pro`; the debug build (the bench) never shrinks, which is why it was only seen on the phone.
- ✓ The emulator's app was uninstalled and reinstalled with the shared-key build (user's OK, 2026-10-12). The Maps key, terms and PGW login were re-entered afterwards; cameras re-downloaded (33 sets, ~19,800 cameras).
- Phone app: Setup tab moved into Settings (4 tabs now); camera sets moved to the Cameras tab, batched by type; POIs tab is manual imports only.

- **Head unit on the emulator works (2026-10-12):** Android Auto 17.9 copied from the user's OPPO phone onto the emulator (TESTING.md §3). AutoNavAlerts runs on the DHU with the map, speed panel, camera icons and alerts, driven by `tools/drive.py` around London. PGW cameras are downloaded on the emulator (login done by the user).
- **Bugs found on the car screen and fixed (not yet committed):**
  - The car map crashed on open: `NavigationViewForAuto` takes no child views; the speed panel now sits beside it in a `FrameLayout`.
  - The car map crashed in a fresh process ("API key not found"): the map now waits for `applyApiKey()`.
  - The phone app crashed on a fresh install with no key: no Navigation SDK call before a key is set.
  - The car speed permission was never asked for: now in Settings → Permissions.
  - After a big GPS jump the map stopped following: `MapSurface.keepFollowing` follows again, then rebuilds the map.
- **Alert banner tested on the bench (2026-10-12):** our own banner at the bottom centre of the map, clear of the speed panel and the map buttons, with the set icon, text, distance · limit and a progress bar (DESIGN.md §12.1). Not yet checked: a tap dismissing it (`SurfaceCallback.onClick`).
- **Fixed and tested on the bench, A61 Sheffield → Derby (session 2, afternoon):**
  - **No alerts heading south:** the emulator's GPS reports bearing 0 on every fix and the app trusted it, so every camera on a road not heading north failed the direction check. The heading now comes from the track unless the GPS bearing agrees within 60° (`AdaptiveLocationSource`, DESIGN.md §11). After the fix, every mobile site and the fixed camera on the first 22 km of the southbound A61 gave 1st, 2nd and passing alerts with their set icons.
  - **"My reports now" seconds after tapping Report:** a fresh report's own marker is now quiet until you're 300 m from it (`AlertCoordinator.quietHere`, DESIGN.md §8.5). The user's report on the A61 saved correctly (position, heading 164°, related camera, recording; transcription fails on the emulator).
- **Built, unit-tested, not yet seen on the bench:**
  - **Real average-speed zones** (DESIGN.md §11 step 8): a zone starts only with a same-limit camera ahead in your direction; it ends at the last camera; a different-limit zone ahead doesn't count; a jump over 1 km ends it (fixes the stuck "over the limit" after a jump). Zone alerts carry the last zone camera, so they show its set icon (e.g. SPECS 50) and its limit: "Average speed zone, 50 mph limit, you are over the limit". 4 new `AlertEngineTest` cases; all 18 pass. Bench test: the A52 Brian Clough Way leg (TESTING.md §3 step 5, A9–A9c); the sim was stopped before it ran.
  - **Satellite view and the right-hand map buttons** (DESIGN.md §8): a Satellite / Map pill at the top right with +, −, recenter under it (our own views; the host strip keeps only Pan). Seen in place on the DHU; **taps not yet confirmed** — nothing logged "Tap at" yet. If the host doesn't pass taps through, the buttons do nothing (More → Satellite map still works). Check X9–X13.
- **Settings backup and restore (DESIGN.md §13.4), built, not yet run:** Settings → Backup → **Save settings backup…** writes a plain-text JSON file (settings, Maps key, PGW login, each set's settings by name) through the system file picker; **Restore from backup…** checks the file, shows what's in it, and on confirm puts back the settings, key (then `keyChanged`), login and matching sets' settings. Navigation terms aren't restored. Compiles; the emulator had been shut down by then, so TESTING.md S8–S11 are still to do.
- **Contacts' addresses (DESIGN.md §8.3), built, not yet run:** optional `READ_CONTACTS` with its own **Allow contacts** button; car Search lists up to 3 matching contact addresses above Places (6 in the new contacts-only search from the **Contacts** row); picking one locates it with Places Text Search (UK postcode match, else the only result, else "Which address?"), cached in Room `contact_geocode` (DB v4, `MIGRATION_3_4`, hashed key, 90 days); recents titled "Name (Home)". `ContactMatch` (core) has 4 tests. Bench checks: TESTING.md M13–M19.
- **Bench session 3 (2026-10-12, 14:10–14:36), tested and fixed:**
  - **Contacts** (M13–M16 pass): Allow contacts button; "mum" lists Mum (Home) above Places; picking it located the address by postcode and opened the route preview "Mum (Home) · 25 min · 20 mi"; the second search shows the cached distance (10.0 mi); the cache row is a 64-char hash. Not yet: M17 (contacts-only list), M18, M19, and the recent "Mum (Home)" (needs **Go**).
  - **Crash fixed:** the route preview crashed for **every** destination ("All rows must have either a distance or duration span"): the row now uses `DurationSpan`/`DistanceSpan`, and a missing route shows "Couldn't find a route there".
  - **Settings backup** (S8, S9 pass): the file saved through the picker; **bug fixed**: `poiSets` was written as one string (Android's `JSONObject.put` doesn't convert a Kotlin List), now a `JSONArray` (34 sets). Restore showed the right preview and put back units, satellite, key, login and 34 sets.
  - **Top-right buttons always shown** (`Action.FLAG_IS_PERSISTENT`); the satellite/zoom column always below them (it was under the strip right after start).
  - **Speed panel** moved to the bottom right.
  - **GPS always 10 Hz** (logcat "Requested 10 Hz"); the rate controls are gone from Settings.
  - **Traffic** always on and visible on both map types: zoom capped at 17 (the road map draws no traffic in towns at 17.5, none anywhere at 18).
  - **Debug bench hook** (`src/debug`, DUMP-protected): `adb -e shell am broadcast -a io.autonav.alerts.BENCH -p io.autonav.alerts --es q <text> | --es geo <lat,lon> | --es contact <name> | --es zoom <delta>` (TESTING.md §3). The DHU's `dpad`/`rotate` never moved focus into our screens, and its window can't be clicked from a script.
  - Seen, not fixed: after an app restart with **no** GPS movement the car map sits at 0,0 (grey) until the next fix; real phones get fixes all the time, and 10 Hz makes it short.
- **OpenStreetMap speed limits from Geofabrik (DESIGN.md §8.4a), session 3:** the Overpass version (DB v5 tiles, "Download around me", auto-fill) is removed; DB v6 drops its tables. Settings → Speed limits → Download fetches the roads `.dbf`/`.shp` from Geofabrik's England/Scotland/Wales zips by range request in a Wi-Fi foreground worker and writes `files/osm/osm_limits.bin`. **Tested on the emulator:** whole of GB in under 3 minutes, 1,284,103 roads, 28 MB, data from 2026-10-11; the card shows the stats; the emulator's DB is v6 with no OSM tables (TESTING.md X15, X16). Then added **Download on Wi-Fi only** (on by default; off lets it use mobile data, tested with Wi-Fi off: X15a) and fixed Cancel, which had let the blocking reads carry on (now stops within 0.1 s, old file kept). Not yet seen: the grey-dot limit on the car panel (X17), Delete (X18). Debug: `--es osm download|download-any|cancel|delete`. The phone's GPS rate card was removed (user's request).
- **RAM/CPU pass (session 3, 15:30–15:50), built and bench-tested:** Northern Ireland added from Geofabrik's Ireland file, kept to NI's border (`NorthernIreland`, OSM relation 156393 simplified to 50 m, plus a whole-5-mph check); UK download = 1,326,619 roads, 29 MB. Download is disk-backed (Java heap peak ~13 MB for the whole app); lookups use `LimitIndex` (one tile, plain arrays, no per-fix allocation); bench: grey-dot 30 on Abbeydale Rd area. The worker runs without a foreground service when Android refuses one (job started in the background). Alerts: fixes are `conflate`d instead of `collectLatest` (a 10 Hz fix could cancel the cache reload and leave old POIs with the new cell marked loaded: a missed-alert bug), settings are kept from the collector, the candidate list is built once per reload. Markers: no per-POI allocation, sort only past 200, marker images cached. Imports: picked zips streamed to a temp file, zip entries read lazily (`ZipEntries.read` returns a closeable lazy map), zip file names listed from the directory only. Not done: parsers still build a full row list per file (largest PGW file ~4k rows, a few MB). Bench: A61 drive alerts (red light 1st/2nd/passing) OK. Map buttons now anchored to the right edge from the start (they sat top-left before the host reported the visible area).
- **Zoom fix (16:07):** zooming out while following was undone by the SDK's follow camera (its own zoom). + / − and pinch now keep following at the chosen zoom (`FollowMyLocationOptions.setZoomLevel`, 3–17); recenter goes back to 17. **Traffic fix (17:30):** the SDK's own follow camera drew closer than the cap (reported 17, showed no traffic in Banbury), so the map now always follows at an explicit zoom, 17 by default; cap lowered 17.5 → 17 (no traffic in towns at 17.5). Bench: zoom held at 15 for 36 s of driving with the car centred (TESTING.md X11). Not committed.
- **Route options (16:12):** Avoid tolls (default **on**) and Energy-efficient routes (EV, default off) on phone Settings → Routing and car More → Route options (DESIGN.md §8.3a). With EV on, the preview offers **Fastest** and **Energy-efficient (EV)** (Routes API `DEFAULT_ROUTE` / `FUEL_EFFICIENT`, `emissionType ELECTRIC`, each a route token the Navigation SDK follows) and the driver picks; `EcoRoutesTest` (app JVM tests, real org.json) covers the picking; re-asked on reroute at most once a minute; falls back to the fastest route with a note. Create a key now enables `routes.googleapis.com` and adds it to existing keys. Bench: fallback path seen (emulator key lacks the Routes API → 403 → fastest route with the note). Not yet seen: an actual EV route (needs the Routes API on the key, X22). **Avoid unpaved roads: not possible** — no such option in the Navigation SDK or the Routes API (user asked for it always on). Not committed.
- **Home and Work from Google (16:20, DESIGN.md §8.2a):** phone Settings → Home and Work: **Get from my Google account** (Data Portability API `maps.aliased_places`, in-app Google sign-in, one-time access; the Takeout import was removed at the user's request, 17:07); stored as HOME/WORK places, first on the car's empty search. `LabeledPlacesTest` (3) passes. Bench (before the Takeout import was removed): parsing and saving (Home with position, Work by address lookup) and the car search rows seen (X25). **Not yet run: the Google-account path (X26)** — needs the maintainer's Cloud project for the app's OAuth client to have `dataportability.googleapis.com` enabled, the `…/auth/dataportability.maps.aliased_places` scope (restricted) on the consent screen, and the user's account as a test user (with the pending OAuth registration steps). Not committed.
- **Route preview (16:30–16:40):** with two routes, the selected one is highlighted before Go (green tick icon vs empty circle, "✓" before its name, "Go · Energy-efficient"/"Go · Fastest"); a per-row Go isn't allowed by Android Auto (route rows: 0 actions). Each row shows traffic from Google's with-traffic vs traffic-free times ("some traffic +12 min") and the **projected** first queue from `TRAFFIC_ON_POLYLINE` ("queue in ~67 min" / "no queues expected"). Energy-efficient routing falls back to the phone's last known position right after a restart. The Routes API was turned on and added to the key in `damien-maps-b17139` (user approved, gcloud). Google returned one route (fastest = most efficient) on every UK trip tried; the debug `--ez tworoutes true` shows two rows for checking. App JVM tests: 9 pass. Not committed.
- **Alert timing (16:50):** defaults are now time-based at the current speed: 1st **15 s** (min 242 m), 2nd **7 s** (min 142 m), passing 100 yd (DESIGN.md §11.1). DB v7 (`MIGRATION_6_7`) moves sets still on the old defaults (30 s/457 m, FIXED 274 m) to these; changed sets are kept. Core tests 98 pass. Not committed.
- **Speed panel, GPU, banner (17:00–17:15):** the limit sign is **always** next to the speed (held 30 s / 300 m through gaps, nearest road with no heading, "–" in a grey ring when unknown). Alert banner: static content in a hardware layer, progress bar animated on the render thread (no redraws while shown). Parked map renders ~57 fps from the SDK's GLTextureView; not our overlays or follow mode (measured). Random-road check: 822/822 lookups right (along, reverse, standing). Debug commands `--ez views|overlays|follow`, `--ei osmcheck`. Banbury → Teddington drive running (X29). Not committed.
- **Traffic, banner, wrong-way cameras (17:15–18:10):** Google traffic wasn't drawn on the road map at start-up: the SDK's follow camera zoomed closer than the cap, and 17.5 itself shows no traffic in towns. The map now always follows at an explicit zoom (**17**, cap 17; + / − and recenter keep it) — DESIGN.md §8. The alert banner's icon and text are centred together (§12.1). **Cameras on our road that point the other way are no longer drawn** (`AlertEngine.facingAway`, §8.1; they never alerted, by the direction hard rule; new core test). A pending mobile site is a ONE_WAY westbound camera that the user passed eastbound — that's why it was silent. Bench drives Banbury → Teddington / Chipping Norton at 60 and 40 mph: all OSM limits followed the roads; **Bloxham shows 30 because OpenStreetMap still has it at 30** (PGW's camera there, #79, says 20); Oxfordshire's 20 mph orders aren't in OSM, and bulk import needs the OSM import process + licence (not done). Open: the banner was full width after a fresh start (covering the speed panel) — not fixed.
- **Phone (18:15–19:35):** release build installed on the user's phone (CPH2791, Android 16). Android Auto had developer mode and **Unknown sources** already on; the Customise Launcher list hadn't picked the app up before the phone disconnected — check it. **Google registration done in Chrome** (section 4a step 2 above) and the five APIs enabled; the button then created the user's key, and the **release build crashed** in the Navigation SDK (R8) — fixed with keep rules in `app/proguard-rules.pro` (step 4). The phone now runs with its key saved; it still needs PGW login, the speed-limit download and an Android Auto run in the car.
- **Open questions for the user:** should the speed panel warn (amber/red) on a camera or zone limit too, not only on Google's speeding signal? (At 48 mph past a 40 camera it stayed white.) Which button did they tap earlier when "it didn't snap the camera and prompt for a comment"? It works on the current build.

## 4b. Session 3 (2026-10-12 evening, PC `fedora`) — Android Auto on the user's phone

- **This PC:** no `~/.local/jdk` (the system JDK builds fine); the signing repo was missing, so debug builds were signed with the default debug key and wouldn't install over the phone's release build. **Cloned `autonavalerts-signing` into `~/.config/autonavalerts`**; builds now carry SHA-1 `6C:48:A1:E5…`.
- **User report:** AutoNavAlerts doesn't show in Android Auto, so it can't be used in the car.
- **Checked on the phone (CPH2791, AA 17.9.664004):** AA developer mode on, **Unknown sources on**, Application Mode "Developer". The installed APK is correct (car descriptor `template`, NAVIGATION `CarAppService`, minCarApiLevel 1), and the service resolves for `androidx.car.app.CarAppService` alongside Spotify, WhatsApp and Electroverse. But **Customise Launcher on the phone doesn't list it** (neither shown nor hidden), with the release build or with the shared-key debug build (now installed on the phone, `DEBUGGABLE`). AA writes no `GH.*` logs on this OPPO, even with Force debug logging on (switched back off).
- **DHU connected to the phone** (AA ⋮ → Start head unit server, `adb -s 3B15AB0075X00000 forward tcp:5277 tcp:5277`, DHU `config/bench.ini`; `bench.sh car` only picks the emulator): **AutoNavAlerts runs on it from the phone**: car map, Satellite pill, zoom, recenter, speed panel with limit, and its icon at the top of the rail. So the app and the phone work. The user confirmed it was in the DHU's app grid and widgets. **Afterwards the phone's Customise Launcher listed AutoNavAlerts** (shown, after "All vehicle apps"): Android Auto only adds a newly installed sideloaded app to its launcher during a projection session, so the car hadn't offered it yet. **Next:** check it now appears in the real car (wired or wireless). If it ever goes missing again, connect the DHU to the phone once (steps above).
- **Home and Work from Google — session 3 evening (not committed):** fixed the phone sign-in ("Incremental auth is not allowed": the Data Portability scope is now requested alone, `setOptOutIncludingGrantedScopes(true)`, play-services-auth 21.3.0 → **22.0.0**); reset-on-409 via `authorization:reset` then re-ask; Android's own account chooser with the account remembered in settings (`googleAccount`, in the backup); the debug build keeps Google's export in `files/debug/`. **Result: Google's export has no Home/Work for this account under any plausible scope** (DESIGN.md §8.2a finding). `tools/dataportability-check.py` calls the API directly with a Desktop OAuth client (`~/.config/autonavalerts/desktop-oauth.json`, created 2026-10-12 in `damien-maps-b17139`, private); scopes `maps.commute_settings` and `maps.commute_routes` were added to that consent screen for the test. **Side effect to know:** while removing the script's grant on the user's Google account (myaccount.google.com → Linked apps), a click removed **all** AutoNavAlerts grants there, including the Cloud one behind **Create a key** (the key itself is fine; the button would just ask for consent again). **Tested on the phone 2026-10-12 ~21:50 (build of commit 2dd8c5e):** press 1 → Android's account chooser → Google consent → export → card shows **Account: <outlook address>**, **Use a different account**, and "No Home or Work found" (TESTING X26a ✓); press 2 → no chooser, remembered account, export runs again straight away (X26b ✓). The 409 → reset → re-ask path (X26c) didn't trigger (Google allowed the second export), so it's validated only by the script's `reset` call, not in the app.
- **Banner fix (not yet seen on the bench):** the banner was full width after a fresh start because its size was only set in `applyPadding()`, which returned until the map and visible area existed. `MapSurface.layoutOverlays()` now places the speed panel, banner and buttons without the map, using the whole surface until the host reports a visible area, and runs as soon as the views are created. **Tested on the DHU from the phone:** after a reinstall (fresh car session, map still near 0,0), the debug `--es banner <text>` showed the banner bottom centre at its normal width, clear of the speed panel and the right-hand buttons. (Quote multi-word text for the device shell: `--es banner "'Speed camera'"`.) The bench hook's `Log.i` lines don't appear in logcat on this OPPO.

### State of the bench at the end of session 2
| Part | State |
|---|---|
| Emulator `auto-bench` (emulator-5554) | **Shut down** by the user at 13:28 (clean, snapshot saved); start it with `tools/bench.sh phone`. The backup/restore build isn't installed yet. When last running: AutoNavAlerts (shared-key debug build with the banner, heading fix, zone rules, satellite toggle and map-button column, **installed**) with the maintainer's Maps key, terms accepted, PGW logged in, cameras downloaded, all permissions incl. car speed (granted with `pm grant`). |
| Android Auto on the emulator | 17.9.664004 copied from the OPPO CPH2791 (serial `3B15AB0075X00000`). Developer mode on, Unknown sources on, head-unit server started, welcome screen and notification access done. |
| DHU | Not running (it closed with the emulator). Was running with the `bench` profile; log `/tmp/autonav-bench/dhu.log`, commands via `tools/bench.sh dhu …`. |
| GPS drive | Stopped by the user ("stop the sim") 22.5 km into the A61, near Chesterfield. The chained A52 zone leg was cancelled. |
| Routes | The A61 and A52 waypoints are in TESTING.md §3 step 5 (road coordinates only, no PGW data). The 21-camera London loop's waypoints were built from the camera database in the session scratch folder (not in the repo; PGW data); rebuild them with the method in §3 step 5. |

### Resuming the bench (in order)
```bash
export JAVA_HOME=~/.local/jdk/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:~/Android/Sdk/platform-tools:$PATH ANDROID_HOME=~/Android/Sdk
tools/bench.sh phone                                   # if the emulator isn't running
./gradlew :app:assembleDebug && adb -e install -r app/build/outputs/apk/debug/app-debug.apk
# Android Auto settings → ⋮ → Start head unit server (after an emulator restart)
tools/bench.sh car                                     # DHU; the last nav app (ours) reopens
nohup python3 -u tools/drive.py --via "<lon,lat;…>" --limits --once > /tmp/autonav-bench/drive.log 2>&1 &   # routes: TESTING.md §3 step 5
adb -e logcat -v time -s AlertCoordinator:I MapSurface:I AndroidRuntime:E > /tmp/autonav-bench/alerts.log &  # every alert, tap and visible-area change
tools/bench.sh dhu screenshot /tmp/car.png             # look at the car screen
```
First checks after resuming: the A52 zone leg (TESTING.md A9–A9c), the map taps (X10–X12, A2b — needs the user to click the DHU window), then M12 and S5–S7. Don't `pkill -f` a pattern that appears in your own command line (it kills the shell running it); kill by PID from `/tmp/autonav-bench/drive.pid`.

## 5. Decisions and rules the user has set (don't undo these)

- **Never a built-in or fallback Maps key.** Each user's Maps use is billed to their own Google account; the maintainer never pays for other users.
- **Maps key: users must not need technical steps.** It has to happen through the API ("Create a key with my Google account"); the guided browser steps are only a fallback. The maintainer does the one-time Google registration.
- **Signing key is shared through the private repo `damo2929/autonavalerts-signing`**, never this public repo.
- **Kotlin only.** Build Android Auto and the Navigation SDK through the Car App Library. All phone and car UI comes from the templates.
- **Update DESIGN.md first** for design changes. The user wanted a full design before code; see memory `design-doc-before-code`.
- **Apache 2.0**, with an SPDX header in every source file. README says the code is AI-assisted.
- **Licensing:**
  - PGW data is never put in the repo or the app. Each user signs in with their **own** subscription, and the data stays in private storage that is excluded from backup.
  - PGW `_X2` icons are © GerryC: credited in the app, and the licence text is saved with the icons.
- **PGW:**
  - Default download is **TomTom OV2, "UK speed zoned complete" (package 1)**. iGO (package 288) can be chosen on the Cameras tab.
  - **Every file in a download becomes its own set. Never merge** (see memory `pgw-one-set-per-file`). The same applies to manual zip imports.
  - Sync is automatic: a daily RSS conditional GET, and when a new release appears the app logs in and downloads. A swap is held while you're navigating. Free POI files are loaded by hand only.
- **Imports:** **no set-name prompt**. Names come from the file names, and icons from matching `.bmp`/`.png` files in the zip.
- **Camera reports:**
  - **One tap** on the car captures position, direction, the best known limit and a voice comment.
  - **Review and Send happen on the phone only, never while driving.**
  - No auto-send.
- **GPS rate:** always 10 Hz (user's choice, 2026-10-12; was 1 Hz below 10 mph rising to 10 Hz at 50 mph). **Car speed and car GPS** are used when the car provides them.
- **Over-limit warning:** a slider from −5% to +20%, **default −0.1%**.
- **HARD RULE: a camera's direction matters.** A camera alerts only when it faces your direction of travel (its bearing and one-way/both-ways type from the PGW/OV2 data, and the same for reports and average-speed zones). Never drop, loosen or bypass the direction check, never default a camera to "any direction" when the data gives one, and keep the car's heading reliable (track heading, not a stale GPS bearing). Any change to alerts, importers, reports or zones must keep and test this.
- **HARD RULE: every on-screen button on the car map is on the right-hand side only** (user, 2026-10-12): the Satellite/Map pill, zoom +/−, recenter, and anything added later go down the right edge, below the action strip; the speed panel is bottom-right. Nothing of ours on the left. Check every layout change on the bench with a screenshot.
- **Alert templates:** only `{category} {speed} {units} {distance} {direction}`. There's no name, set or road.
- **Navigation terms:** accept once and they're remembered.
- **Emulator:** **2 vCPUs**. Don't change SELinux booleans; `selinuxuser_execheap` stays off.
- **The quarterly SDK check must stay free:** public repo, standard Linux runner, 10-minute cap.

## 6. Known issues and notes

| Item | Notes |
|---|---|
| Google speed limit | The Navigation SDK only exposes it while you're **over** it (via `SpeedingListener` %). Otherwise the speed panel shows the camera's limit, marked with a blue dot. |
| Incident reporting on Android Auto | Not offered to apps by the Navigation SDK (it's phone-only). It's auto-detected by reflection, and the quarterly workflow `.github/workflows/nav-sdk-feature-watch.yml` checks for it. |
| Emulator GPU | `-gpu host` with Vulkan crashes in AMD Mesa 26.2 (`radeonsi si_resource_from_memobj`). SwiftShader is blocked by SELinux `execheap`. Use `-gpu host -feature -Vulkan`; `bench.sh` does this by default. |
| PGW zip reading | Must use `ZipFile` (the central directory). `ZipInputStream` stops after the first entry of PGW zips. See `ZipEntries.kt`. |
| Imported free POI sets | Alerts are **on** by default, so tram stops will speak, in any direction (no direction or speed data). The user may want new file imports to default to "map only". This was offered but not decided. |
| POIs tab, first load | Slow-ish with 33+ icons on the emulator. Could move icon loading to the background. |
| Electroverse | No public charger API (partner only). The user has an Octopus **developer account**: check whether its GraphQL schema has charger locations (DESIGN.md §18). |
| Not built yet | Home Assistant people finder (§17), Google Maps history import and the share receiver (§8.2), EV chargers (§18), phone "Send to car", background alerts during free drive, per-category override UI, saved CSV profiles, instrumented tests, Baseline Profile, power measurement. |

## 7. How to resume

```bash
cd ~/sources/AutoNavAlerts
git pull
./gradlew :core:test :app:assembleDebug       # build + unit tests
tools/bench.sh phone                           # start emulator (2 vCPUs, safe GPU mode) if not running
tools/bench.sh install                         # install the debug APK
tools/check-nav-sdk-features.sh --latest       # optional: Navigation SDK feature watch
```
Real-data checks use the user's own downloads, which are never committed:
```bash
PGW_OV2_ZIP=/path/to/ov2.zip PGW_ZIP=/path/to/igo.zip ./gradlew :core:test --no-configuration-cache
```
Useful logs:
```bash
adb -s emulator-5554 logcat -v brief PgwClient:I PgwImport:I WM-WorkerWrapper:I AndroidRuntime:E '*:S'
```

**Suggested next session:**
1. The user enters the Maps API key and accepts the terms, and we confirm both stick.
2. Decide on the car-screen path: real phone (recommended) or Android Auto copied from the phone.
3. Work through the TESTING.md §6 car checklist, fixing issues as they come up.
4. Then continue with the remaining build phases (§6 above), updating DESIGN.md first for each one.

## 8. Commit history (this session)
`71a4105` initial → `39bdf2a` README → `4004080` bench/settings/−0.1% → `7825cb0` car speed → `e6e2264` car GPS → `c87627d` one-tap reports → `eb0b8cd` `1643b9c` incident watch → `d893dfc` `afc5c47` `1e71667` quarterly workflow → `713179c` bench script → `aa4bd11` terms once / permissions → `69fd4e3` zip fix → `79892e3` swipe delete → `88a7a3d` iGO per type → `1dea909` OV2 + format choice → `dcd82c6` one set per file → `1c128c3` PGW icons → `7b7b645` manual import naming and icons.
