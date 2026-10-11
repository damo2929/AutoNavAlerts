# AutoNavAlerts

An Android Auto navigation app built on the Google Navigation SDK. It's meant to stand in for Google Maps on the head unit, with a direction-aware POI alert system on top. PocketGPSWorld speed cameras are the first POI source.

> **AI-assisted code:** this project is written with the help of **Claude** (Anthropic's AI assistant, via Claude Code). A human reviews, tests and is responsible for the code. Commits that include AI-generated changes are marked with a `Co-Authored-By: Claude` trailer.

## Status
**First test build (v0.1.0).** It builds and lint passes, and the `:core` logic has 61 unit tests. It **hasn't been run on a real car or the Desktop Head Unit yet**, so expect rough edges.

- **Testing without a car:** [TESTING.md](TESTING.md) is the complete bench guide (Desktop Head Unit, simulated drives, test data, checklist).
- **Every setting explained:** [docs/SETTINGS.md](docs/SETTINGS.md).

The full design is in [DESIGN.md](DESIGN.md). Read it before changing anything, and record design changes there first. [DESIGN.md Appendix B](DESIGN.md#appendix-b--implementation-status-first-test-build-2026-10-11) tracks what's built and what isn't.

## Features

### In the test build
- **Navigation on the car screen**
  - Google map with traffic and pan, zoom and recenter
  - Places search, with recents shown before you type
  - Route preview with ETA and traffic
  - Turn-by-turn guidance, with turn cards in Android Auto's rail and on the cluster
- **Lives alongside media**: uses only Android Auto navigation templates, so music keeps playing in split-screen and alert speech lowers the volume rather than pausing it
- **POI alerts**
  - Import iGO TXT, KML, GPX, OV2 or CSV files (with column mapping), zipped or not
  - Per-set icon (built-in or your own image) and spoken text, with a live preview and **Test speak**
  - Three alert stages: a 1st and 2nd at distances you set, and a **compulsory 100 yd passing alert** whenever sound is on
  - Direction-aware: cameras facing the other way, or on a parallel road, don't alert
  - Average-speed zones and over-limit warnings
  - Shown as a card on the car screen, or as a navigation notification when another app fills the screen
- **Map extras**
  - POI markers redraw on every GPS update
  - A speed panel shows your speed and the limit. Google's limit is only known while you're over it, so otherwise the panel shows the nearest camera's limit, marked with a dot.
- **Uses the car's own speed and GPS** from Android Auto when the car shares them. The speed panel matches your dashboard; alerts use the car's measured speed and GPS position (the phone's GPS drops to a 1 Hz backup). Falls back to the phone.
- **Speed-aware GPS**: 1 Hz below 10 mph, rising in a straight line to 10 Hz at 50 mph
- **PocketGPSWorld**, with **your own** subscription
  - Checks their RSS feed daily and logs in to download a new release by itself
  - Never swaps in new cameras while you're navigating
- **Report a camera with one tap** in the car. It saves your position, direction and the best-known speed limit, then records an optional **spoken comment** (speech-to-text). Reports are buffered, and after the drive the phone asks you to check them. **Review and Send happen only in the phone app, never while driving.**
- **Recents** that you can delete from the car screen
- **Phone app tabs**
  - Setup: your own **Maps API key**, permissions, navigation terms
  - POI sets
  - Cameras: PocketGPSWorld login and sync
  - Reports
  - Settings: units, voice, chime, over-limit warning slider (−5% to +20%, default −0.1%), speed panel, GPS curve, simulate speed (debug builds). All documented in [docs/SETTINGS.md](docs/SETTINGS.md).

### Waiting on Google
- **Live incident reporting** on the car screen (crashes, congestion, police…). Google Maps has it, but Google's Navigation SDK doesn't offer it to apps on Android Auto yet. The app detects it automatically if a future SDK adds it. A quarterly GitHub Actions check opens an issue when that happens. Run it locally with `tools/check-nav-sdk-features.sh --latest`.

### Planned (designed, not built yet)
- Home Assistant people finder: navigate to family members' live locations, e.g. children or relatives living with dementia (DESIGN.md §17)
- Contacts' addresses (§8.3) and Google Maps history imports (§8.2)
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
You need a Google Maps Platform key with the **Navigation SDK** (access must be approved by Google) and **Places API (New)** enabled. Restrict it to the package `io.autonav.alerts` and your signing SHA-1.

**Enter the key in the phone app's Setup tab after installing.** It's stored encrypted on the device and applied at run time, so no rebuild is needed and each user can bring their own key. Alternatively, put `MAPS_API_KEY=...` in `local.properties` to build it in.

### Install and set up
1. `adb install app/build/outputs/apk/debug/app-debug.apk`
2. In the phone app's **Setup** tab: add the Maps API key, allow location ("all the time" is recommended) and notifications, and accept Google's navigation terms.
3. In Android Auto: tap the version number repeatedly to turn on developer mode, then enable **Unknown sources**. This app is sideloaded. Pick AutoNavAlerts as your navigation app.
4. Add cameras: either enter your PocketGPSWorld login on the **Cameras** tab and press **Sync now**, or import a POI file on the **POIs** tab.

### Test without a car
Use the Android Auto **Desktop Head Unit** and the **Simulate** drive. See **[TESTING.md](TESTING.md)** for the full step-by-step bench set-up (phone or emulator), test data, and a checklist covering every feature. In short:

```bash
sudo dnf install libcxx libcxxabi          # Fedora: libraries the DHU needs
adb forward tcp:5277 tcp:5277              # after "Start head unit server" on the phone
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

## Project layout
| Module | What |
|---|---|
| `:core` | Plain Kotlin/JVM logic (in `core/src/main/kotlin`), unit-tested on the JVM: geo maths, grid index, importers, adaptive GPS rate, alert engine, templates, PocketGPSWorld feed and page parsing |
| `:app` | Android app (Kotlin, in `app/src/main/kotlin`): Car App Library screens, Navigation SDK map and guidance, Room, WorkManager (PocketGPSWorld sync), Compose phone UI |

Main versions: AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.1, Car App Library 1.7.0, Navigation SDK 8.0.0. minSdk 26, targetSdk 36, compileSdk 37.

## Data and licensing
- The code is licensed under the **Apache License 2.0**; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
- **PocketGPSWorld data is never included in this repository or the app.**
  - Each user signs in with their **own** subscription and gets their own watermarked copy, under PocketGPSWorld's licence.
  - That copy is kept in private app storage and excluded from backups.
  - Test fixtures are made up, and `.gitignore` blocks PocketGPSWorld files.
- Google Maps, the Navigation SDK and Places are used under Google's terms, with your own API key.
