# AutoNavAlerts

An Android Auto navigation app built on the Google Navigation SDK. It's meant to stand in for Google Maps on the head unit, with a direction-aware POI alert system on top. PocketGPSWorld speed cameras are the first POI source.

> **AI-assisted code:** this project is written with the help of **Claude** (Anthropic's AI assistant, via Claude Code). A human reviews, tests and is responsible for the code. Commits that include AI-generated changes are marked with a `Co-Authored-By: Claude` trailer.

## Status
Early development. The full design is in [DESIGN.md](DESIGN.md); read it before changing anything, and record design changes there first.

## Features (planned)
- Google Maps–style navigation on Android Auto: search, route preview, turn-by-turn guidance, lanes, traffic, the speed panel and the cluster
- Works alongside media in Android Auto's split-screen layout
- Generic POI alerts: import any POI file (iGO TXT, KML, GPX, OV2, CSV wizard), with a custom icon and spoken text per set, and three alert stages including a compulsory 100 yd passing alert
- PocketGPSWorld camera database, synced automatically when their RSS feed announces a new release (requires **your own** subscription)
- Report cameras to PocketGPSWorld from the car screen
- Speed-aware GPS rate: 1 Hz below 10 mph, rising to 10 Hz at 50 mph
- Recents (including Google Maps history imports), contacts' addresses, and a Home Assistant people finder

## Project layout
| Module | What |
|---|---|
| `:core` | Plain Kotlin/JVM logic (geo maths, grid, importers, GPS-rate maths, PGW feed parsing); unit-tested on the JVM |
| `:app` | Android app: Car App Library, Navigation SDK, Room, WorkManager, Compose |

## Building
Requirements:
- A Java runtime to start Gradle. Gradle downloads JDK 21 for the build itself.
- The Android SDK, with platform 37.0 and build-tools 37, and its location set as `sdk.dir` in `local.properties`.
- A Google Maps Platform API key with the **Navigation SDK** and **Places API (New)** enabled.

```bash
echo "MAPS_API_KEY=your-key" >> local.properties   # never committed
./gradlew :core:test          # JVM unit tests
./gradlew :app:assembleDebug  # debug APK
```

To test without a car, use the Android Auto **Desktop Head Unit**. Turn on Android Auto developer mode on the phone, enable "Unknown sources" and "Start head unit server", then:

```bash
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

## Data and licensing
- The code is licensed under the **Apache License 2.0**; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
- **PocketGPSWorld data is never included in this repository or the app.** Each user downloads their own copy with their own subscription, under PocketGPSWorld's licence. Test fixtures are made up.
- Google Maps, the Navigation SDK and Places are used under Google's terms.
