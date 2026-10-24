# AGENTS.md — how to work in this repo

This file is for AI agents and anyone else picking the project up cold. It says where the information lives and what you must check before and after changing anything.

## 1. Read in this order

| Read | Why | When |
|---|---|---|
| [CHECKPOINT.md](CHECKPOINT.md) | Where the work stands right now: last commit, what's tested, what's in flight, the bench state, and the owner's standing rules (§5) | **Always first** when resuming |
| [DESIGN.md](DESIGN.md) | **The source of truth.** Every behaviour, rule, data format, threshold and decision. Nothing is built that isn't designed here first | Before any change; again before you commit |
| [TESTING.md](TESTING.md) | The bench: Desktop Head Unit, emulator, `tools/drive.py`, scenarios, the debug broadcast hooks, the full checklist (§6) and the real-car protocol (§6.12) | Before testing or claiming something works |
| [docs/SETTINGS.md](docs/SETTINGS.md) | Every user-facing setting explained | When touching settings, the phone UI or the backup file |
| [docs/INSTALLATION.md](docs/INSTALLATION.md) | The tester's guide: APK from Releases, `adb install -r -i com.android.vending`, phone setup, Android Auto, the car, sending logs | When the install, setup or Android Auto steps change, or a tester reports a setup problem |
| [README.md](README.md) | The public overview, build steps, licensing | For orientation and when user-facing behaviour changes |
| [CHANGELOG.md](CHANGELOG.md) | What changed for the user, by day, with the DESIGN.md section for each | When reporting a change; add a line for anything the user will notice |
| [ISSUES.md](ISSUES.md) | Outstanding bugs, decisions waiting on the owner, what isn't verified yet, what isn't built | When picking the next thing up, and to move an item out when it's done |

Do not rely on a summary of these files from memory or from an earlier session. The design changes on most working days and the dated amendments ("owner, 2026-10-12") override older text in the same section.

## 2. Finding things

**By topic in DESIGN.md** (section numbers are stable; search for `## N.` or `### N.N`):

| Topic | Section |
|---|---|
| Goals, decisions table, prerequisites (Maps key rules) | §1 |
| Architecture, threads, process model | §2 |
| Package layout: which class does what, `:core` vs `:app` | §3 |
| Room entities, icon/template resolution, grid cells, staging swap | §4 |
| Importers (iGO, OV2, KML, GPX, CSV, zip) and the PGW file formats | §5 |
| PocketGPSWorld sync, RSS feed, licence handling | §6 |
| Icons and alert templates | §7 |
| Car UI: screens, action strip, map buttons, markers, follow watchdog, satellite, contacts, route options, speed panel, OSM limits, camera reports | §8 |
| Replacing Google Maps: intents, background guidance, `stopGuidance` | §9 |
| Location: GPS at the phone's own rate, car speed/GPS, `FixEstimator` heading and speed | §10 |
| **Alert engine maths and the direction hard rule** | §11 |
| Alert stages and their defaults | §11.1 |
| Alert dispatch, banner, notification, speech policy, cluster | §12 |
| Phone app, Maps key creation, signing key, settings backup | §13 |
| Power budget | §14 |
| Testing strategy, diagnostics logs, drive recorder, replay | §15 |
| Open questions and the first real-car drive bugs | §16 |
| Not yet built: Home Assistant people finder, EV chargers | §17, §18 |
| Build phases and implementation status | Appendix A, B |

**In the code:**
- `core/src/main/kotlin/io/autonav/alerts/core/` is pure JVM logic, unit-tested: geo maths, importers, `AlertEngine`, `FixEstimator`, `SpeechScheduler`, `FollowWatchdog`, `EcoReaskPolicy`, `RoutePolyline`, `LimitIndex`/`LimitLookahead`, `BatchLog`, `DriveLog`.
- `app/src/main/kotlin/io/autonav/alerts/` is the Android side: `car/` (Car App Library session and screens), `nav/` (Navigation SDK, guidance, location sources), `map/` (surface, markers, speed panel, banner), `alerts/` (coordinator, dispatcher, TTS), `poi/` (Room, importers, PGW), `phone/` (Compose), `diag/` (event logs, drive recorder).
- `app/src/debug/` holds the bench-only `BenchReceiver`; release builds don't have it.
- `tools/` has the bench scripts: `bench.sh`, `drive.py`, `scenario.sh`, `scenarios/*.gpx`, `check-nav-sdk-features.sh`.
- DESIGN.md §3 maps every class to its job. If a class isn't listed there, check whether it should be before adding it.

**Finding out what happened on a drive:** the two always-on logs on the phone, `files/diag/debug-nav.log` and `files/diag/alert-debug.log` (DESIGN.md §15.5, TESTING.md §7). logcat is only a mirror and the owner's phone drops it.

## 3. Mandatory: cross-check DESIGN.md on every change

DESIGN.md is not documentation of the code. It is the specification the code must match, and it carries rules the owner has set that must never be broken. **Before you change code and again before you report a change as done, re-read the DESIGN.md sections that cover what you touched and confirm the change keeps every mandatory element.** If the design and the code disagree, the design wins unless the owner says otherwise.

### 3.1 Hard rules (owner-set; never drop, loosen, bypass or default around)

1. **Camera direction** (DESIGN.md §1.3, §11 step 3; CHECKPOINT.md §5). A camera alerts only when it faces the car's direction of travel: its bearing within 45° of the car's heading **or** of the bearing from the car to the camera, and it must still be ahead. ONE_WAY/BOTH semantics are fixed. A camera with a bearing in its data is never treated as "any direction". Opposite-facing cameras are drawn dimmed with their arrow, never hidden, and never alert. The heading must stay reliable (track heading, not a stale GPS bearing). Any change to alerts, importers, reports, zones, heading or markers must keep this and keep its tests passing (`camera_30DegBend_directionPassesViaBearingToCamera`, `oppositeCameraStillRejectedOnABend`, the wrong-way scenario).
2. **Nothing is shown on the car screen that doesn't work, and no function is shown twice** (§1.3, §8; owner, 2026-10-13). No touch reaches the Navigation SDK's view on Android Auto, so its own ETA card, header, recenter, speedometer, trip bar and limit icon are dead and must be turned off and hidden; ours exist only where they take a tap or show live data. One element per job, so the screen estate is kept: one stop (Google's X on the host's travel-estimate card; we add no stop action), one turn card, one recenter, one speed/limit display, one alert surface on the map. After any change to the car map or its screens, take a DHU screenshot during guidance (`tools/bench.sh dhu screenshot`) and check it yourself, before reporting done. Don't wait to be told.
3. **No shake on the car screen** (§1.3, §8; owner, 2026-10-13). A template is re-sent (`invalidate()`) and a view of ours redrawn only when visible content changed, never on a timer with unchanged content: the host animates every refresh, so a 1 Hz re-send of an identical template made the More screen judder. Live rows compare their lines first. Check any screen with a ticker or collector by a burst of DHU screenshots (consecutive frames identical while nothing changes, TESTING.md U2).
4. **Every on-screen button on the car map is on the right-hand side only** (§8). Satellite pill, zoom, recenter, anything added later: down the right edge under the action strip. Speed panel bottom-right. Nothing of ours on the left. Check every layout change with a DHU screenshot.
5. **Never a built-in or fallback Maps API key** (§1.4). Each user's Maps use is billed to their own Google account. No manifest key, no `local.properties` key, no SDK call before `applyApiKey()`.
6. **PocketGPSWorld data never enters the repo, the app build, tests or fixtures** (§5.2, §6.4). Each user downloads their own watermarked copy with their own login. Fixtures are made up.
7. **Signing key stays in the private repo** `damo2929/autonavalerts-signing`, never here (§13.3). **The Google Play developer account's details** (owner account, account ID, Console links, tester emails) stay out of this repo too (CHECKPOINT §5; owner, 2026-10-24).
8. **Review and Send of camera reports happen on the phone only, never in the car or while driving.** One tap captures in the car; no auto-send (§8.5).
9. **Every file in a PGW or zip download becomes its own set; never merge.** No set-name prompt on import; names from file names, icons from matching `.bmp`/`.png` (§5, §5.2a).
10. **Kotlin only**; Android Auto and the Navigation SDK through the Car App Library; all car UI from navigation-family templates (§1.3, §8).
11. **Design first.** Any design change is written into DESIGN.md before the code changes, with the date and "owner" where it was the owner's decision.
12. **Apache 2.0 with an SPDX header in every source file**; commits by `Damien Dye <damien@damiendye.uk>`, never the croit address.

### 3.2 Other fixed behaviours to preserve

- Phone GPS taken at the rate the phone offers, never forced faster (asked at 1 Hz, any faster fix accepted; owner, 2026-10-24); car speed and car GPS at `UPDATE_RATE_FASTEST` when the car provides them (§10).
- Three alert stages: 1st and 2nd from the global lead-time slider (N s and N/2 s at current speed, floors 242 m and 142 m), and the **compulsory passing alert 1 s before the camera at the current speed, never less than 20 yd, whenever sound is on**; the trigger ratchets and never shrinks on an approach; events are never dropped, PASSING is never dropped (§11, §11.1, §12.3).
- Average-speed zones only start with a same-limit camera ahead and end at the last camera (§11 step 8).
- Over-limit slider −5% to +20%, default −0.1% (§11 step 9).
- Alert templates use only `{category} {speed} {units} {distance} {direction}` (§7.2).
- Media is ducked, never paused (§12.3). Mute silences speech only; cards still show.
- Traffic layer always on; map zoom capped at 17 (§8).
- Speed limit always shown next to the speed: Google, then camera/zone, then OpenStreetMap (one bulk Geofabrik download, never live lookups), else "–" (§8.4, §8.4a).
- Navigation terms accepted once and remembered. PGW swap never happens while navigating (§6.2).
- Big data streamed from disk, never held whole in RAM (§1.3 Memory).
- The quarterly SDK feature-watch workflow stays free: public repo, standard runner, 10-minute cap.
- Emulator: 2 vCPUs, `-gpu host -feature -Vulkan`, never change SELinux booleans.

### 3.3 The cross-check procedure

1. Identify every DESIGN.md section your change touches (use the table in §2 of this file and `grep -n` for the class or setting name).
2. Read those sections in full, including dated amendments and the §16 rows that mention the area.
3. List the mandatory elements they contain. Compare against your diff line by line.
4. Run the relevant tests: `./gradlew :core:test` always; `:app:testDebugUnitTest` for app JVM tests; the matching TESTING.md checklist rows on the bench when the change is visible on the car screen or affects alerts.
5. If your change alters behaviour, update DESIGN.md **first** (the section, the decisions table in §1.3 if it's a decision, Appendix B status), then TESTING.md's checklist, then docs/SETTINGS.md and README.md if the user sees it.
6. Record anything learned on the bench or in the car in CHECKPOINT.md with the date.
7. In your report, say which DESIGN.md sections you checked and confirm the hard rules in §3.1 still hold. If you could not verify one, say so explicitly rather than implying it passed.

Interface contracts between parallel agents (when CHECKPOINT.md lists them, e.g. §4c) are mandatory elements too: add, don't rename; keep the listed signatures.

## 4. Working conventions

- Build: `./gradlew :core:test :app:assembleDebug`. JDK 21 is downloaded by Gradle; the Android SDK is at `~/Android/Sdk` (`sdk.dir` in `local.properties`, never committed).
- Bench: `tools/bench.sh phone` (emulator), `tools/bench.sh install`, `tools/bench.sh car` (DHU), `tools/bench.sh reload` (a new build while the DHU is up: stops the head unit and app, installs, starts again; never a bare `adb install -r` under a connected car screen, TESTING.md §3 step 4), `tools/drive.py` for a GPS drive, `tools/scenario.sh <name>` for scripted cases, `tools/bench.sh dhu screenshot <file>` to look. Debug broadcasts are in TESTING.md §3 step 5a.
- Debug builds only have `Simulate`, the bench hooks and `run-as` log access. The owner's phone runs a release build; check release-only paths (R8 keep rules, §14) when touching the Navigation SDK or Maps classes.
- Don't `pkill -f` a pattern that matches your own shell; kill by PID from `/tmp/autonav-bench/*.pid`.
- Don't push, commit or create issues unless asked. Commit messages end with the `Co-Authored-By: Claude` trailer the README mentions.
- Keep this file, CHECKPOINT.md and DESIGN.md in step: if you add a rule or a pointer here, it must already exist in DESIGN.md or CHECKPOINT.md §5. This file points; it never originates design.
