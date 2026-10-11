# Checkpoint: end of session 1 (2026-10-11, late evening)

> Read this first when resuming. Then read [DESIGN.md](DESIGN.md) (the source of truth), [TESTING.md](TESTING.md) (the bench guide) and [docs/SETTINGS.md](docs/SETTINGS.md).
> The code is AI-assisted (Claude). Commits are by `Damien Dye <damien@damiendye.uk>`. **Never use the croit email in this repo.**

## 1. Where things stand

- **Repo:** `git@github.com:damo2929/AutoNavAlerts.git`, branch `main`, last commit `7b7b645`. The working tree was clean at checkpoint.
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

## 5. Decisions and rules the user has set (don't undo these)

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
- **GPS rate:** 1 Hz below 10 mph, rising in a straight line to 10 Hz at 50 mph. **Car speed and car GPS** are used when the car provides them.
- **Over-limit warning:** a slider from −5% to +20%, **default −0.1%**.
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
| Not built yet | Home Assistant people finder (§17), contacts' addresses (§8.3), Google Maps history import and the share receiver (§8.2), EV chargers (§18), phone "Send to car", background alerts during free drive, per-category override UI, saved CSV profiles, instrumented tests, Baseline Profile, power measurement. |

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
