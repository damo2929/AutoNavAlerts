# Outstanding issues

What is known to be wrong, undecided, unverified or unbuilt, as of 2026-10-24 22:30. Each line says where the detail lives (DESIGN.md §16 rows, CHECKPOINT.md sections, TESTING.md rows). Fixed items move to CHANGELOG.md; this file only lists what is still open.

## 1. Bugs seen, not yet explained or fixed

| # | What | Where it stands | Detail |
|---|---|---|---|
| B1 | **The Navigation SDK dropped its own guidance camera** on the Banbury bench drive (00:32:30–00:34:58): the map froze and the car drove off the top, with nothing of ours in the logs | Covered by the follow watchdog, which now re-follows within ~10 s in guidance; the cause is unknown. A screenshot monitor (`followmon.py`) runs on the bench to timestamp the next drop | CHECKPOINT §4f; DESIGN §8 "Follow watchdog" |
| B2 | **Route cleared on drive-off** (first real-car drive, XPENG G6, 2026-10-12): a route set while parked cleared within seconds of moving | Candidates designed and logged (`Guidance stop caller=`); needs a real-car drive with the logs to confirm which | DESIGN §16 row 22 |
| B3 | **"That should have said Gatso variable"** (owner, 2026-10-23 22:39, poi 4169): the spoken category for a variable-limit Gatso | Not looked at | CHECKPOINT §4f |
| B4 | The fork test in the limit-change walk keys on OSM drawing direction, so a two-way branch drawn towards the junction isn't seen as a fork and its limit can be announced | Needs a oneway flag in the limit file (data rebuild) and a purely geometric test; owner's call | DESIGN §16 row 28 |
| B6 | Restoring a settings backup saves a new PGW login but doesn't call `PgwClient.forgetSession()`, so in a running process the old login's in-memory session cookies survive until restart (the Cameras tab's own Save does call it) | Found by the comment pass, 2026-10-24; `SettingsBackup.restore` | DESIGN §13.4, §6.1 |
| B7 | `SettingsStore.updatePgw` reads then writes instead of one DataStore transaction, so two concurrent writers could lose a change; harmless today (unique work) | Found by the comment pass, 2026-10-24 | DESIGN §6.2 |
| B5 | After Google's X the SDK moves its camera a moment later; the settle re-follows and the watchdog put it back, with one `watchdog miss` logged each time | Pre-existing, self-healing; no visible jump since the settle re-follows | DESIGN §8 "Ending (or starting) a route keeps the view" |

## 2. Waiting on the owner's decision

| # | Question | Detail |
|---|---|---|
| D1 | Oneway data in the limit file for the fork test (B4): rebuild the OSM limit download with a oneway flag? | DESIGN §16 row 28 |
| D2 | Our POI alerts in the host's turn card: no custom field exists; car `Alert` + heads-up is the fallback | DESIGN §16 row 3 |
| D3 | Submitting camera reports to PocketGPSWorld automatically: the camera-ID lookup endpoint, the success text and whether automated sending is allowed are unknown; review-first with "Open in browser" meanwhile | DESIGN §16 row 19 |
| D4 | Camera alerts in the cluster's media card: unknown whether the cluster takes a `MediaSession` from a non-media app | DESIGN §16 row 27 |

## 3. Not yet verified

### On the bench (Desktop Head Unit)

| # | What | Detail |
|---|---|---|
| V1 | **Passing alert at 1 s ahead** on a real pass: the next `Trace stage PASSING … expectedD=` line in `alert-debug` should show ~25 m at 55 mph (unit test passes) | CHECKPOINT §4f; TESTING A3 |
| V2 | No-shake burst (U2) after tonight's view changes, on the road map while parked | TESTING U2 |
| V3 | The in-car checklist rows not yet ticked: media ducking and split screen, one-tap Report with a voice comment, car speed and car GPS from the DHU sensors, recents delete, swipe-to-delete and the set editor rename | CHECKPOINT §4; TESTING §6 |
| V4 | A fresh install with no key: the car screen asks for the key, no crash; terms accepted once survive an update | TESTING S5, S6; CHECKPOINT §4 items 1–2 |

### In the car (real drive)

| # | What | Detail |
|---|---|---|
| C1 | Everything in TESTING §6.12 (the real-car protocol): the first drive's bugs (DESIGN §16 rows 22, 24, 25) were designed and built but not yet driven again | TESTING §6.12 |
| C2 | The MG4's car-screen size for a DHU profile: the `Map surface W×H dpi=` line from a real MG4 drive | CHECKPOINT §4f |
| C3 | Phone GPS at the phone's own rate (1 Hz asked, faster accepted) on the owner's phone: the More → GPS row's actual rate | DESIGN §10.2; TESTING X7 |
| C4 | Phone Reports tab: Review and **Send** submits a real report to PocketGPSWorld, so only deliberately | CHECKPOINT §4 item 5 |

## 4. Known limits of the platform (nothing to fix on our side yet)

| # | What | Detail |
|---|---|---|
| L1 | Live incident reporting (crashes, police, roadworks) is not offered to apps on Android Auto; the SDK APIs exist on `NavigationView` only. A quarterly GitHub Actions check watches for it | DESIGN §16 row 21 |
| L2 | The SDK's own on-map controls are dead on Android Auto (no touch reaches the SDK view); turned off, ours instead | DESIGN §16 row 23 |
| L3 | OPPO/ColorOS drops the app's logcat on the owner's phone; evidence comes from the two file logs and the drive recorder | DESIGN §16 row 25 |
| L4 | The cluster shows only the trip's turn card, never a map, and only on cars that support cluster navigation | DESIGN §16 row 26 |
| L5 | Navigation SDK access needs Google's approval and is billed per trip; Places autocomplete is billed per session | DESIGN §16 rows 1, 12 |
| L6 | Google Maps recents: no live API; our own recents and Maps shares are always current | DESIGN §16 rows 13, 14 |

## 5. Not yet built

| # | What | Detail |
|---|---|---|
| N1 | Home Assistant people finder (consent, freshness, HA reachability from the car) | DESIGN §17; §16 rows 15–17 |
| N2 | EV chargers (Electroverse has no public API; Places + Open Charge Map and a hand-off meanwhile) | DESIGN §18; §16 row 20 |
| N3 | PGW automation hardening: terms, site changes, captcha (`PgwConfig`, manual import fallback) | DESIGN §16 row 6 |
| N4 | iGO TYPE code 11 (RedSpeed) is a best guess; confirm against package 288 | DESIGN §16 row 5 |
| N9 | **Car screens designed richer than built** (DESIGN §8, §8.2, §8.4, as-built notes dated 2026-10-24): Recents as a place list with markers, distance and origin badge, delete with Undo; a Favourite action on place detail; a results list with map markers; a per-set alerts toggle on POI layers; the speed pill's amber/red thresholds as settings; RadarDroid/Nokia importers (§5.3) | DESIGN §8, §8.2, §8.4, §5.3 |
| N8 | **Heads-up alert notification extras** designed in DESIGN §12.2 but not built: a content intent that opens our car screen and a Mute action; the notification id is `20_000 + poi.id % 10_000`, so two POIs exactly 10,000 ids apart would share one | DESIGN §12.2 |
| N7 | **Phone app items designed in DESIGN §13 but not built**: the set editor's category list, 50-POI map preview and re-import/export actions (item 5); default stage modes for new sets (item 9); the Places card (item 10: Google Maps import, Data Portability sync, hidden places); the speech record ring on the phone's Diagnostics (it is on the car's More → Alerts) | DESIGN §13 |
| N6 | **POI alerts on the driver's screen (cluster)**: designed in DESIGN §12.4 (the alert as an 8 s `Step` in the `Trip`), not built; `TripPublisher` sends the route's steps only | DESIGN §12.4 |
| N5 | Google Play **internal testing** is live (2026-10-24 22:10, release 1 `0.0.2-2026-10-24`). Release 2 (version code 2, native debug symbols) published 22:28 without the Console warning. Not yet verified: installing it from Play on the owner's phone (the phone's Play Store gave a 404 right after the invite was accepted; expected to be propagation), and that Android Auto then keeps it in **Customise Launcher** across a reboot | DESIGN §1.3 Distribution, §13.3 "Google Play", §16 row 10 |
