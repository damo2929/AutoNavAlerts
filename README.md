# AutoNavAlerts

An Android Auto navigation app built on the Google Navigation SDK. It's meant to stand in for Google Maps on the head unit, with a direction-aware POI alert system on top. PocketGPSWorld speed cameras are the first POI source.

> **AI-assisted code:** this project is written with the help of **Claude** (Anthropic's AI assistant, via Claude Code; the Claude Fable and Claude Opus models). A human reviews, tests and is responsible for the code. Commits that include AI-generated changes are marked with a `Co-Authored-By: Claude` trailer.

## Status
**First test build (v0.1.0).** It builds and lint passes, and the `:core` logic has 61 unit tests. It **hasn't been run on a real car or the Desktop Head Unit yet**, so expect rough edges.

- **Resuming work:** [CHECKPOINT.md](CHECKPOINT.md) has the current state, what has been tested, and next steps.
- **Testing without a car:** [TESTING.md](TESTING.md) is the complete bench guide (Desktop Head Unit, simulated drives, test data, checklist).
- **Every setting explained:** [docs/SETTINGS.md](docs/SETTINGS.md).

The full design is in [DESIGN.md](DESIGN.md). Read it before changing anything, and record design changes there first. [DESIGN.md Appendix B](DESIGN.md#appendix-b--implementation-status-first-test-build-2026-10-11) tracks what's built and what isn't.

## Features

### In the test build
- **Navigation on the car screen**
  - Google map with traffic and pan, zoom and recenter. A **Satellite / Map** toggle sits at the top right, with zoom in, zoom out and recenter in a column under it
  - Places search, with recents shown before you type
  - **Drive to a contact's address**: type or say a name ("Mum", "Dave Smith") and the contact's Home/Work/other addresses appear above the Places results; or open **Contacts** in Search. Optional contacts permission; only the address you pick is sent to Google
  - Route preview with ETA and traffic
  - Turn-by-turn guidance, with turn cards in Android Auto's rail and on the cluster
- **Lives alongside media**: uses only Android Auto navigation templates, so music keeps playing in split-screen and alert speech lowers the volume rather than pausing it
- **POI alerts**
  - Import iGO TXT, KML, GPX, OV2 or CSV files (with column mapping), zipped or not
  - Per-set icon (built-in or your own image) and spoken text, with a live preview and **Test speak**
  - Three alert stages: a 1st and 2nd at distances you set, and a **compulsory passing alert 1 s before the camera** (at least 20 yd) whenever sound is on
  - Direction-aware: cameras facing the other way, or on a parallel road, don't alert
  - Average-speed zones (only real ones: a zone needs another camera with the same limit ahead; it ends at the last camera) and over-limit warnings that name the zone's limit and show its set icon
  - Shown as a banner at the bottom of the car map (tap to dismiss), or as a navigation notification when another app fills the screen
- **Map extras**
  - POI markers redraw on every GPS update
  - A speed panel shows your speed and the limit. Google's limit is only known while you're over it, so otherwise the panel shows the nearest camera's limit, marked with a dot.
  - **OpenStreetMap speed limits for the UK**, downloaded once from Geofabrik (about 1.1 GB fetched, Wi-Fi only by default; 29 MB kept on the phone), fill in the panel's limit when Google and the cameras have none (grey dot). Nothing is looked up online while driving.
- **Route options**: avoid tolls (on by default) and **energy-efficient routes for electric cars**: the preview offers the fastest and Google's eco-friendly route side by side and you pick (Routes API on your own key; falls back to the fastest route)
- **Uses the car's own speed and GPS** from Android Auto when the car shares them. The speed panel matches your dashboard; alerts use the car's measured speed and GPS position (the phone's GPS drops to a 1 Hz backup). Falls back to the phone.
- **GPS at the phone's own rate**: the phone's GPS is taken at the rate it offers (never forced faster), and the car's own GPS and speed at the fastest rate the car provides
- **PocketGPSWorld**, with **your own** subscription
  - Choose **TomTom OV2** (default: direction, camera make, roadworks cameras, PGW camera IDs) or **iGO** (exact direction in degrees). Every file in the download becomes its own set (never merged). For TomTom OV2 that's one set per type and speed, e.g. "PGW fixed cameras · 30 mph".
  - Checks their RSS feed daily and logs in to download a new release by itself
  - Never swaps in new cameras while you're navigating
- **Report a camera with one tap** in the car. It saves your position, direction and the best-known speed limit, then records an optional **spoken comment** (speech-to-text). Reports are buffered, and after the drive the phone asks you to check them. **Review and Send happen only in the phone app, never while driving.**
- **Recents** that you can delete from the car screen
- **Phone app tabs**
  - POIs: the POI files you've imported
  - Cameras: PocketGPSWorld login and sync, and every camera set, grouped by camera type
  - Reports
  - Settings: setup (your own **Maps API key**, permissions, navigation terms), units, voice, chime, over-limit warning slider (−5% to +20%, default −0.1%), speed panel, GPS curve, simulate speed (debug builds). All documented in [docs/SETTINGS.md](docs/SETTINGS.md).

### Waiting on Google
- **Live incident reporting** on the car screen (crashes, congestion, police…). Google Maps has it, but Google's Navigation SDK doesn't offer it to apps on Android Auto yet. The app detects it automatically if a future SDK adds it. A quarterly GitHub Actions check opens an issue when that happens. Run it locally with `tools/check-nav-sdk-features.sh --latest`.

### Planned (designed, not built yet)
- Home Assistant people finder: navigate to family members' live locations, e.g. children or relatives living with dementia (DESIGN.md §17)
- Google Maps history imports (§8.2)
- EV chargers via Google Places and Open Charge Map, with a hand-off to Electroverse (§18)
- Background alerts during free drive with no route set, a phone "Send to car" option, and per-category overrides

## Getting started

### Build
Requirements:
- **A Java runtime** to start Gradle. Gradle downloads JDK 21 itself (`gradle/gradle-daemon-jvm.properties`).
- **The Android SDK**, with platform **37.0**, build-tools 37 and platform-tools. Set its path as `sdk.dir` in `local.properties`.

```bash
echo "sdk.dir=$HOME/Android/Sdk" >> local.properties   # local.properties is never committed
./gradlew :core:test          # JVM unit tests
./gradlew :app:assembleDebug  # → app/build/outputs/apk/debug/app-debug.apk
```

You can test the importer against your own PocketGPSWorld download without committing it:
```bash
PGW_IGO_FILE=/path/to/pocketgps_uk_sc.txt ./gradlew :core:test --tests '*realPgwFileIfProvided'
```

### Google Maps API key
You need a Google Maps Platform key with the **Navigation SDK** (access must be approved by Google), **Maps SDK for Android** and **Places API (New)** enabled. Restrict it to the package `io.autonav.alerts` and your build's signing SHA-1, and to those three APIs.

**Get the key in the phone app's Settings tab after installing**: press **Create a key with my Google account** to have the app make one in your own Google Cloud account, or paste one you already have. It's stored encrypted on the device and applied at run time, so no rebuild is needed and each user can bring their own key. There's no built-in key and no way to build one in: everyone's Maps use is billed to their own Google account.

- **What the button makes**, in your Google Cloud account: one project ("AutoNavAlerts", reused next time), linked to your billing account; the three APIs turned on; one key that only this app can use. You're shown this list before it starts. Details: [docs/SETTINGS.md §2.1](docs/SETTINGS.md).
- **Billing:** Google Maps needs a Cloud **billing account** (a card from your Google payments profile can be used). Google may charge for use beyond its free allowance. Billing accounts can't be created by an app; if you have none, the app opens the page to add one.
- **If the button isn't available** for your copy of the app (it needs a one-time Google registration by the maintainer), **Set it up yourself in Google Cloud (step by step)** does the same in your browser, with an **Open** button for each page and **Paste key from clipboard** at the end.

### Signing
Official builds are all signed with one shared key, so Google sign-in and restricted Maps keys work for every build. It's kept in a **private** repo, never here. Builds made without it are signed with your own debug key; they work, but a Maps key must list your SHA-1 (`apksigner verify --print-certs <apk>` shows it). See DESIGN.md §13.3.

### Install and set up
1. `adb install app/build/outputs/apk/debug/app-debug.apk`
   (for a phone you'll drive with, build and install the release: `./gradlew :app:assembleRelease` and `adb install -r app/build/outputs/apk/release/app-release.apk`; it's smaller and has no bench hooks)
2. In the phone app's **Settings** tab (the app opens there until setup is done): add the Maps API key, press **Allow** (location, notifications, microphone and car speed), then **Allow all the time** (recommended), and accept Google's navigation terms (available once the key is saved).
3. In Android Auto: tap the version number repeatedly to turn on developer mode, then enable **Unknown sources**. This app is sideloaded. Pick AutoNavAlerts as your navigation app.
4. **Already have a settings backup?** Settings → Backup → **Restore from backup…** brings back your Maps key, PocketGPSWorld login and settings (accept the navigation terms again afterwards). **Save settings backup…** makes one; it's plain text with your key and password in it, so keep it private.
5. Add cameras: either enter your PocketGPSWorld login on the **Cameras** tab and press **Sync now**, or import a POI file on the **POIs** tab.

### Test without a car
Use the Android Auto **Desktop Head Unit** and the **Simulate** drive. See **[TESTING.md](TESTING.md)** for the full step-by-step bench set-up (phone or emulator), test data, and a checklist covering every feature. In short:

```bash
sudo dnf install libcxx libcxxabi          # Fedora: libraries the DHU needs
adb forward tcp:5277 tcp:5277              # after "Start head unit server" on the phone
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

On the emulator bench, `tools/bench.sh` starts and drives the head unit (`bench.sh car`, `bench.sh dhu screenshot <file>`, `bench.sh dhu dpad …`), and `tools/drive.py` drives the emulator's GPS along real roads (default: a loop around central London). With `--limits` it follows each road's posted limit from OpenStreetMap, taking turns under, at and over it. The emulator only has a stub of Android Auto; TESTING.md §3 shows how to copy the real one from your phone.

## Project layout
| Module | What |
|---|---|
| `:core` | Plain Kotlin/JVM logic (in `core/src/main/kotlin`), unit-tested on the JVM: geo maths, grid index, importers, alert engine, templates, PocketGPSWorld feed and page parsing |
| `:app` | Android app (Kotlin, in `app/src/main/kotlin`): Car App Library screens, Navigation SDK map and guidance, Room, WorkManager (PocketGPSWorld sync), Compose phone UI |

Main versions: AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.1, Car App Library 1.7.0, Navigation SDK 8.0.0. minSdk 26, targetSdk 36, compileSdk 37.

## Data and licensing
- The code is licensed under the **Apache License 2.0**; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
- **PocketGPSWorld data is never included in this repository or the app.**
  - Each user signs in with their **own** subscription and gets their own watermarked copy, under PocketGPSWorld's licence.
  - That copy is kept in private app storage and excluded from backups.
  - Test fixtures are made up, and `.gitignore` blocks PocketGPSWorld files.
- Google Maps, the Navigation SDK and Places are used under Google's terms, with your own API key.
