# AutoNavAlerts

AutoNavAlerts is a navigation app for Android Auto.
It shows a Google map on your car's screen and guides you turn by turn.
On top of that it warns you about speed cameras and other points of interest ahead.

A point of interest (POI) is a place marked on the map, such as a speed camera.
The first POI source is PocketGPSWorld, a UK speed camera database you subscribe to yourself.

The app is written in Kotlin.
It is built on Google's Navigation SDK (software development kit) and the Android for Cars App Library.

> **AI-assisted code.**
> This project is written with the help of Claude, Anthropic's AI assistant, through Claude Code.
> A person reviews, tests and is responsible for the code.
> Commits with AI-generated changes carry a `Co-Authored-By: Claude` line.

## What is in this document

1. [Where the project stands](#1-where-the-project-stands)
2. [Which document to read](#2-which-document-to-read)
3. [What the app does](#3-what-the-app-does)
4. [What is waiting on Google](#4-what-is-waiting-on-google)
5. [What is planned but not built](#5-what-is-planned-but-not-built)
6. [Getting the app as a tester](#6-getting-the-app-as-a-tester)
7. [Building the app yourself](#7-building-the-app-yourself)
8. [Your Google Maps key](#8-your-google-maps-key)
9. [Signing](#9-signing)
10. [Testing without a car](#10-testing-without-a-car)
11. [How the code is laid out](#11-how-the-code-is-laid-out)
12. [Data and licensing](#12-data-and-licensing)

## 1. Where the project stands

The app is on Google Play's **internal testing track**.
Testers install it from the Play Store by invitation.

It has been driven in a real car once (an XPENG G6, 2026-10-12) and tested many times on the bench.
The bench is a Desktop Head Unit on a computer, with a phone or an emulator.

The core logic has 214 unit tests. The Android side has 30 more.

It is a test build, so please expect some problems.

## 2. Which document to read

| You want to | Read |
|---|---|
| Install it on your phone and car as a tester | [docs/INSTALLATION.md](docs/INSTALLATION.md) |
| Understand every setting | [docs/SETTINGS.md](docs/SETTINGS.md) |
| Know what changed, by day | [CHANGELOG.md](CHANGELOG.md) |
| See open bugs and what is not built yet | [ISSUES.md](ISSUES.md) |
| Test it without a car | [TESTING.md](TESTING.md) |
| Continue the work where it stopped | [CHECKPOINT.md](CHECKPOINT.md) |
| Change the code | [AGENTS.md](AGENTS.md), then [DESIGN.md](DESIGN.md) |

DESIGN.md is the source of truth for how the app behaves.
Please read it before changing anything, and write design changes there first.
Its Appendix B lists what is built and what is not.

## 3. What the app does

### Navigation on the car screen

- A Google map with live traffic. You can pan, zoom and recenter.
- A **Satellite / Map** button at the top right. Zoom in, zoom out and recenter sit in a column under it.
- Search for a place. Your recent places show before you type.
- Drive to a contact's address. Type or say a name, and the contact's addresses appear above the search results.
  Only the address you pick is sent to Google.
- A route preview with the arrival time and traffic.
- Turn-by-turn guidance. The turn cards also show in Android Auto's side rail and on the car's driver display.

### Works alongside music

- The app uses only Android Auto's navigation screens.
- So music keeps playing in split screen.
- When the app speaks, the music gets quieter. It does not stop.

### Camera and POI alerts

- Import your own POI files: iGO TXT, KML, GPX, TomTom OV2 or CSV. They can be zipped.
  (CSV is a plain text table. OV2 is TomTom's POI file format.)
- Each set gets its own icon and its own spoken text. You can preview it and press **Test speak**.
- Three alert stages for each camera:
  - a 1st alert, some seconds ahead of the camera at your current speed
  - a 2nd alert, at half that time
  - a passing alert 1 second before the camera, never less than 20 yards away. This one always plays while sound is on.

*[Image: three markers on a road ahead of a car, labelled 1st, 2nd, passing]*

- Alerts know which way a camera faces.
  A camera facing the other way, or on a road beside yours, does not alert.
  Such cameras are drawn dimmed, so you can see why.

*[Image: two cameras on a road, one facing the car (bright), one facing away (dimmed)]*

- Average speed zones are recognised only when they are real: there must be another camera with the same limit ahead.
  The zone ends at its last camera.
- If you are over the limit, the alert says so and names the limit.
- Alerts appear as a banner at the bottom of the car map. Tap it to dismiss.
  When another app fills the screen, the alert comes as a navigation notification instead.

### Map extras

- POI markers are redrawn on every position update.
- A speed panel shows your speed and the road's limit.
  Google only reveals its limit while you are over it.
  Otherwise the panel shows the limit of the camera you are being warned about, marked with a dot.
- UK speed limits from OpenStreetMap fill in the panel when Google and the cameras give none (a grey dot).
  This is one download of about 1.1 GB, over Wi-Fi by default. The phone keeps about 29 MB.
  Nothing is looked up online while you drive.

### Route options

- **Avoid tolls** is on by default.
- **Energy-efficient routes** for electric cars (EV). The preview offers the fastest route and Google's energy-saving route side by side.
  This uses Google's Routes API on your own key. If it is not available, the fastest route is used.

### Speed and position from the car

- When the car shares them through Android Auto, the app uses the car's own speed and GPS position.
  (GPS is the satellite positioning system.)
- The speed panel then matches your dashboard.
- Alerts use the car's measured speed and position. The phone's GPS stays ready as a backup.
- The phone's GPS is read at the rate the phone offers, never forced faster.
  The car's speed and GPS are read as fast as the car provides them.

### PocketGPSWorld cameras, with your own subscription

- Choose the **TomTom OV2** download (the default: direction, camera make, roadworks cameras and camera IDs) or **iGO** (exact direction in degrees).
- Every file in the download becomes its own set. Sets are never merged.
  With TomTom OV2 that is one set per camera type and speed, for example "PGW fixed cameras · 30 mph".
- The app checks PocketGPSWorld's update feed once a day and downloads a new release by itself.
- New cameras are never swapped in while you are navigating.

### Report a camera with one tap

- Tap **Report** in the car. The app saves your position, direction and the best known speed limit.
- Then it records a short spoken comment, which is turned into text.
- After the drive, the phone asks you to check your reports.
- **You review and send reports only in the phone app, never while driving.**

### Phone app

Four tabs:

- **POIs**: the POI files you imported
- **Cameras**: your PocketGPSWorld login and sync, and every camera set grouped by type
- **Reports**: the reports you captured in the car
- **Settings**: setup (your own Maps key, permissions, navigation terms), units, voice, chime, the over-limit slider, the speed panel, backups and diagnostics

Every setting is explained in [docs/SETTINGS.md](docs/SETTINGS.md).

## 4. What is waiting on Google

**Live incident reporting** on the car screen (crashes, congestion, police).
Google Maps has it. Google's Navigation SDK does not offer it to other apps on Android Auto yet.
A quarterly check on GitHub watches for it; the feature is added when the SDK offers it.
A check runs every 3 months on GitHub and opens an issue when that happens.

Technical detail: run the check yourself with `tools/check-nav-sdk-features.sh --latest`.

## 5. What is planned but not built

- A public Google Play listing. Today the app is on the internal testing track by invitation.
- A Home Assistant people finder: navigate to a family member's live location (DESIGN.md §17).
- Importing your Google Maps history (DESIGN.md §8.2).
- EV chargers from Google Places and Open Charge Map, with a link to Electroverse (DESIGN.md §18).
- Alerts in the background with no car connected, a phone "Send to car" option, and per-category settings.

The full list of what is designed but not built is in [ISSUES.md](ISSUES.md).

## 6. Getting the app as a tester

You do not need to build anything.

1. Send the maintainer the Google account your phone's Play Store uses.
2. The maintainer adds it to the Play tester list.
3. Install from Google Play through the link in [docs/INSTALLATION.md](docs/INSTALLATION.md).

That guide takes you from nothing to a working car screen.

## 7. Building the app yourself

This section is for developers.

You need:

- a Java runtime to start Gradle. Gradle downloads JDK 21 itself.
- the Android SDK, with platform 37.0, build-tools 37 and platform-tools.
  Put its path in `local.properties` as `sdk.dir`. That file is never committed.

```bash
echo "sdk.dir=$HOME/Android/Sdk" >> local.properties
./gradlew :core:test :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:assembleDebug  # builds app/build/outputs/apk/debug/app-debug.apk
```

You can test the importer against your own PocketGPSWorld download without committing it:

```bash
PGW_IGO_FILE=/path/to/pocketgps_uk_sc.txt ./gradlew :core:test --tests '*realPgwFileIfProvided'
```

### Installing a build you made

A build you install with a cable is a **developer build**. Android Auto treats it differently from a Play install.

1. For a phone you will drive with, build the release and install it with the Play Store recorded as the installer:
   ```bash
   ./gradlew :app:assembleRelease
   adb install -r -i com.android.vending app/build/outputs/apk/release/app-release.apk
   ```
   (adb is the Android Debug Bridge, Google's command-line tool for phones.)
   The debug build (`adb install app/build/outputs/apk/debug/app-debug.apk`) is for the bench. It is larger and has test hooks.
2. In the phone app's **Settings** tab: add your Maps key, press **Allow** for the permissions, then **Allow all the time**, and accept Google's navigation terms.
3. In Android Auto's settings, tap the version number repeatedly to turn on developer mode. Then turn on **Unknown sources**.
   A developer build is not kept in Android Auto's Customise Launcher list. It still appears on the car.
   A Play install needs none of this.
4. Have a settings backup? Settings → Backup → **Restore from backup…** brings back your key, PocketGPSWorld login and settings.
   The backup file is plain text with your key and password in it. Please keep it private.
5. Add cameras: enter your PocketGPSWorld login on the **Cameras** tab and press **Sync now**, or import a POI file on the **POIs** tab.

## 8. Your Google Maps key

Google Maps use is billed to the person using it.
So each user brings their own Google Maps Platform key.
There is no built-in key, and no way to build one in.

The key needs three services turned on: the **Navigation SDK** (Google must approve access), the **Maps SDK for Android** and the **Places API (New)**.
It is restricted to the app's package name, `uk.damiendye.autonav`, and the build's signing fingerprint.

You get the key in the phone app's **Settings** tab after installing:

- Press **Create a key with my Google account**. The app makes the key in your own Google Cloud account.
  It creates one project called "AutoNavAlerts" (reused next time), links your billing account, turns on the three services, and makes one key only this app can use.
  You see this list before it starts.
- Or paste a key you already have.

The key is stored encrypted on the phone and applied at run time. No rebuild is needed.

**Billing.** Google Maps needs a Cloud billing account. A card from your Google payments profile can be used.
Google may charge for use beyond its free monthly allowance.
An app cannot create a billing account. If you have none, the app opens the page where you add one.

**If the button is not available for your copy of the app**, it needs a one-time registration by the maintainer.
Use **Set it up yourself in Google Cloud (step by step)** instead.
It opens each Google Cloud page for you and ends with **Paste key from clipboard**.

Detail: [docs/SETTINGS.md §2](docs/SETTINGS.md).

## 9. Signing

All official builds are signed with one shared key.
So Google sign-in and restricted Maps keys work for every official build.
The key lives in a private repository, never here.

A build you make without it is signed with your own debug key.
It works, but your Maps key must list your build's fingerprint (its SHA-1).
`apksigner verify --print-certs <apk>` shows it.

Technical detail: DESIGN.md §13.3.

## 10. Testing without a car

Use Android Auto's **Desktop Head Unit** on a computer, and the app's **Simulate** drive.
[TESTING.md](TESTING.md) has the full bench set-up, test data and a checklist for every feature.

In short:

```bash
sudo dnf install libcxx libcxxabi          # Fedora: libraries the head unit needs
adb forward tcp:5277 tcp:5277              # after "Start head unit server" on the phone
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

On the emulator bench, `tools/bench.sh` starts and drives the head unit, and `tools/drive.py` moves the emulator's GPS along real roads.
The emulator ships only a stub of Android Auto. TESTING.md §3 shows how to copy the real one from a phone.

## 11. How the code is laid out

| Module | What it holds |
|---|---|
| `:core` | Plain Kotlin logic, tested on the JVM: geo maths, the grid index, importers, the alert engine, templates, the PocketGPSWorld feed and page parsing |
| `:app` | The Android app: car screens (Car App Library), the Navigation SDK map and guidance, the Room database, WorkManager jobs, and the Compose phone screens |

Every source file starts with a comment that names its DESIGN.md section, the thread it runs on and the rule it upholds.
DESIGN.md §3 maps every class to its job, and §2 has diagrams of how the parts fit together.

Main versions: AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.1, Car App Library 1.7.0, Navigation SDK 8.0.0.
Android: minSdk 26, targetSdk 36, compileSdk 37.

## 12. Data and licensing

- The code is licensed under the **Apache License 2.0**. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
- **PocketGPSWorld data is never included in this repository or in the app.**
  - Each user signs in with their own subscription and gets their own watermarked copy, under PocketGPSWorld's licence.
  - That copy stays in private app storage and is excluded from backups.
  - Test fixtures are made up, and `.gitignore` blocks PocketGPSWorld files.
- Google Maps, the Navigation SDK and Places are used under Google's terms, with your own key.

---

First published: 2026-10-11
Last updated: 2026-10-24
Pictures: not added yet. The lines in square brackets mark where they go.
