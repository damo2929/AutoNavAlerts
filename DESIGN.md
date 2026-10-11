# AutoNavAlerts — Design

> Status: **Draft for review**. No code is written until this document is approved. Any later design change is made here first.

---

## 1. Goals, non-goals, decisions, prerequisites

### 1.1 Goals
- An Android Auto navigation app that can **stand in for Google Maps on the head unit**:
  - Google map tiles, traffic, place search, route preview with alternatives, and turn-by-turn guidance with lane info, voice and the cluster/HUD.
- A **generic POI alert system**:
  - Any POI file can be imported.
  - Each import set has its own icon and spoken alert text.
  - Alerts check direction, so only POIs facing your direction of travel fire.
  - PocketGPSWorld (PGW) speed cameras are the first source, synced automatically.
- POI alerts go out **the same way as nav instructions**:
  - navigation-guidance audio that lowers the media volume
  - a navigation heads-up card
  - the cluster
- **Lives alongside media**: Android Auto's split-screen layout shows the media card next to our map, and our alerts and turn info still appear when media is full screen.
- **Uses as little power as possible**, with the GPS rate following speed.
- **Navigate to contacts**: drive to any postal address saved in the phone's contacts (§8.3).
- **Speed and speed limit on screen at all times**: your current speed next to the road's speed limit as Google Maps reports it. The display turns amber or red when you're over the limit (§8.4).
- **People finder (Home Assistant)**: navigate to the live location of people tracked in your Home Assistant, such as a child or a relative living with dementia. The route updates as they move (§17).

### 1.2 Non-goals (v1)
- Full navigation UI on the phone. The phone is for settings and import only.
- Android Automotive OS (built-in car systems, as opposed to phone projection). The design doesn't rule it out but isn't aimed at it.
- Offline maps or routing. Map tiles and routing come from the Navigation SDK and need a connection, though POIs work offline.
- Google Play publication. The app is sideloaded.
- Sharing POI data between users. Each user brings their own data, as PGW licensing requires.

### 1.3 Decisions made
| Topic | Decision | Reason |
|---|---|---|
| Licence | **Apache License 2.0** (`LICENSE`, `NOTICE`, SPDX header in each source file) | Open-source licence chosen by the owner. PGW data is never part of the project (§6.4). |
| Language | Kotlin only | Car App Library and Navigation SDK are JVM-only. A cross-platform engine adds power drain. |
| Map/routing/search | Google Navigation SDK + Places API (New) | Closest to the real Google Maps; supports Android Auto directly |
| POI data | Generic framework. **PGW is the only automatic source** (RSS-triggered sync). **Free and other POI files are loaded by hand only**, with no automatic fetching or URL subscriptions. | Not limited to speed cameras; you decide when third-party data changes |
| PGW format | iGO TXT as the main format | Carries direction type and bearing |
| Distribution | Sideload (ADB + Android Auto "Unknown sources") | Personal use |
| Phone UI | Settings, import and editing only | All driving use happens on the car screen |
| People finder | Home Assistant REST + WebSocket API, `person.*` / `device_tracker.*` entities, long-lived access token (v1) | HA already brings together the family's phone, tracker-tag and companion-app locations; no new tracking setup needed |
| GPS rate | 1 Hz below 10 mph, rising in a straight line to 10 Hz at 50 mph, capped there | Alerts fire at accurate distances at speed while saving power when slow |

### 1.4 Prerequisites
1. Google Cloud project with billing; **Navigation SDK** (access approval required) and **Places API (New)** enabled. API key restricted to the app package and signing SHA-1. **Each user enters their own key in the phone app (Setup tab) after installing.** It's stored encrypted with the Keystore and applied at run time with `NavigationApi.setApiKey()` and `Places.initializeWithNewPlacesApiEnabled()`. A key in `local.properties` (`MAPS_API_KEY`) is optional, and is only a build-time fallback.
2. An active PocketGPSWorld subscription.
3. A phone with Android Auto developer mode and "Unknown sources" enabled, and/or the Desktop Head Unit (DHU) on the dev machine.
4. *(People finder, optional)* A Home Assistant instance the phone can reach away from home (Nabu Casa Cloud, or your own HTTPS domain or VPN), with the people to find set up as `person` entities fed by the HA Companion app or another tracker. You create a long-lived access token for a HA user who can read those entities.
5. Dev machine (set up in phase 1):
   - Android SDK at `~/Android/Sdk`: platform **37.0** (compileSdk), build-tools 37, platform-tools, and the DHU (`extras/google/auto`).
   - No system JDK is needed. Gradle downloads JDK 21 itself through `gradle/gradle-daemon-jvm.properties` and the foojay toolchain resolver. Bytecode targets Java 17.
   - Versions: AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Gradle 9.8.1, Car App 1.7.0, Navigation SDK 8.0.0. minSdk 26, targetSdk 36.

---

## 2. System architecture

```
┌──────────────────────────── Phone (single APK, one process) ────────────────────────────┐
│                                                                                          │
│  Phone UI (Compose)            Background                                                │
│  ┌──────────────────┐          ┌────────────────────┐     ┌───────────────────────────┐  │
│  │ Onboarding/T&Cs  │          │ PgwSyncWorker      │     │ NavForegroundService      │  │
│  │ POI sets / import│──import─▶│ (WorkManager)      │     │ (type=location)           │  │
│  │ Icon / template  │          └─────────┬──────────┘     │ keeps Navigator, location │  │
│  │ PGW account      │                    │ zip            │ and alert engine running  │  │
│  │ Settings         │                    ▼                └────────────┬──────────────┘  │
│  └────────┬─────────┘          ┌────────────────────┐                  │                 │
│           │ SAF file           │ Importers          │                  │                 │
│           └───────────────────▶│ iGO/RD/KML/GPX/    │                  │                 │
│                                │ OV2/CSV            │                  │                 │
│                                └─────────┬──────────┘                  │                 │
│                                          ▼ staging → atomic swap       │                 │
│                                ┌────────────────────┐                  │                 │
│                                │ Room DB            │                  │                 │
│                                │ PoiSet/Category/Poi│                  │                 │
│                                └─────────┬──────────┘                  │                 │
│                                          ▼                             │                 │
│                                ┌────────────────────┐   fixes   ┌──────┴─────────────┐   │
│                                │ PoiGridCache       │◀──────────│ AdaptiveLocation   │   │
│                                │ (in-memory cells)  │           │ Controller (FLP,   │   │
│                                └─────────┬──────────┘           │ 1–10 Hz)           │   │
│                                          ▼                      └────────────────────┘   │
│                                ┌────────────────────┐                                    │
│                                │ PoiAlertEngine     │──AlertEvent──┐                     │
│                                └────────────────────┘              ▼                     │
│                                                       ┌────────────────────────┐         │
│  Google Navigation SDK                                │ AlertDispatcher        │         │
│  ┌──────────────────────────────┐                     │  • car Alert (fg)      │         │
│  │ Navigator (routing/guidance) │──TurnByTurn───┐     │  • nav HUN (bg)        │         │
│  │ RoadSnappedLocationProvider  │               │     │  • TTS (nav audio)     │         │
│  │ NavigationViewForAuto (map)  │               │     │  • cluster/trip msg    │         │
│  │ Voice guidance               │               │     └───────────┬────────────┘         │
│  └──────────────┬───────────────┘               │                 │                      │
│                 │ VirtualDisplay/Presentation   ▼                 │                      │
│  Car App Library (androidx.car.app)                               │                      │
│  ┌────────────────────────────────────────────────────────────────┴─────────────────┐   │
│  │ NavCarAppService → NavSession → Screens (NavigationTemplate family)              │   │
│  │ AutoMapRenderer (SurfaceCallback) · PoiMarkerLayer · NavigationManager · AppMgr  │   │
│  └───────────────────────────────────────────┬──────────────────────────────────────┘   │
└──────────────────────────────────────────────┼──────────────────────────────────────────┘
                                               │ Android Auto projection protocol
                                  ┌────────────▼─────────────┐
                                  │ Head unit (Android Auto) │
                                  │ map surface, templates,  │
                                  │ split screen with media, │
                                  │ heads-up, cluster        │
                                  └──────────────────────────┘
```

**Process model:** one APK and one process. The car service and phone UI share the same Hilt singletons: `NavigatorHolder`, `PoiRepository`, `AdaptiveLocationController`, `PoiAlertEngine` and `AlertDispatcher`. The foreground service owns how long guidance and alerts live, so they survive the car screen being hidden behind media.

**Threads:** all work uses coroutines.
- `Dispatchers.IO` for Room and network.
- One `alertScope` thread for the engine, so there's no contention and processing stays in order.
- The main thread only for the Car App Library and map calls.

---

## 3. Package layout and each class's job

**Two Gradle modules**:
- **`:core`**: plain Kotlin/JVM with no Android dependencies. It holds the logic that has to be right and is easy to unit-test on the JVM: geo maths, the grid, importers, the adaptive GPS rate, the PGW feed parser, alert maths and template rendering.
- **`:app`**: the Android parts (Car App Library, Navigation SDK, Room, WorkManager, Compose), which call into `:core`.

The package names below are unchanged; classes marked as pure logic live in `:core` under `io.autonav.alerts.core.*`.

```
app/src/main/kotlin/io/autonav/alerts/
  App.kt                         Hilt application; creates notification channels
  car/
    NavCarAppService.kt          CarAppService; host validator (allow-all in debug, known hosts in release)
    NavSession.kt                Session; creates the first screen; handles ACTION_NAVIGATE / geo: intents
    screens/HomeScreen.kt        NavigationTemplate in browse/free-drive mode
    screens/SearchScreen.kt      SearchTemplate + Places autocomplete
    screens/PlaceListScreen.kt   PlaceListNavigationTemplate of results
    screens/RoutePreviewScreen.kt RoutePreviewNavigationTemplate with alternatives
    screens/NavigatingScreen.kt  NavigationTemplate with RoutingInfo + TravelEstimate
    screens/ArrivalScreen.kt     Arrival message
    screens/PoiLayersScreen.kt   ListTemplate of set toggles (map / alerts)
    CarIconFactory.kt            Icon id → CarIcon (cached)
  nav/
    NavigatorHolder.kt           Lazy NavigationApi.getNavigator; T&Cs state; Simulator in debug
    GuidanceController.kt        Set destinations, choose a route, start/stop guidance, reroute events
    TurnByTurnMapper.kt          NavInfo → RoutingInfo/Step/Maneuver/Lane, TravelEstimate
    TripPublisher.kt             Calls NavigationManager.navigationStarted/updateTrip/navigationEnded
    AdaptiveLocationController.kt Speed-based fused location request (§10)
    RouteGeometry.kt             Current route polyline for route matching (§11)
    NavForegroundService.kt      Foreground service (type=location); ongoing notification
  map/
    AutoMapRenderer.kt           SurfaceCallback → VirtualDisplay → Presentation → NavigationViewForAuto
    MapPaddingController.kt      Visible/stable area → GoogleMap.setPadding
    SpeedPanel.kt                Current speed + road speed limit overlay (§8.4)
    PoiMarkerLayer.kt            Redraws on every GPS fix: cache + viewport → marker diff → GoogleMap markers (§8.1)
  poi/
    model/ (PoiSet, PoiCategory, Poi, DirectionType, AlertDistanceMode, CsvMappingProfile)
    db/ (AppDatabase, PoiSetDao, PoiCategoryDao, PoiDao, Converters)
    Grid.kt                      Grid-cell maths (§4.3)
    PoiRepository.kt             CRUD, viewport query, staging + swap
    PoiGridCache.kt              In-memory cells around the vehicle
    importers/ (PoiImporter, IgoTxtImporter, RadarDroidTxtImporter, NokiaTxtImporter,
                KmlImporter, GpxImporter, Ov2Importer, CsvImporter, ZipUnpacker, ImportRunner)
    sources/PgwClient.kt         Login + download (§6)
    sources/PgwUpdateFeed.kt     speed-camera-rss.php reader → PgwRelease (version, date, summary)
    sources/PgwFeedCheckWorker.kt Daily conditional GET of the feed; queues PgwSyncWorker on a new version
    sources/PgwSyncWorker.kt     WorkManager job: login + download + import
    reports/CameraReportRepository.kt  Queue of camera reports (Room) + local "My reports" POI set (§8.5)
    reports/PgwReportSubmitter.kt      Logged-in POST to PGW's Submit Speed Cameras form
    reports/ReportSubmitWorker.kt      Sends queued reports (after the drive, or right away if set)
    sources/CredentialStore.kt   Keystore-encrypted credentials
  places/
    PlaceRepository.kt           Recents + favourites (Room); merge, dedupe, delete, hide (§8.2)
    GoogleMapsImporter.kt        Takeout / on-device Timeline / Saved Places files → places
    DataPortabilitySync.kt       Optional: Google Data Portability API (myactivity.maps, saved places)
    ContactsSource.kt            ContactsContract postal addresses → geocoded places, with a cache (§8.3)
    MapsShareReceiver.kt         Captures places shared from Google Maps (share sheet / maps links)
  ha/
    HaServerStore.kt             HA base URL + Keystore-encrypted long-lived token(s); several servers supported
    HaClient.kt                  REST (/api/states, /api/services) + WebSocket (auth, subscribe_entities)
    TrackedPeopleRepository.kt   Live StateFlow<List<TrackedPerson>> from the chosen entities
    LiveTargetController.kt      Navigating to a moving person: re-route rules, freshness, location-update requests (§17.4)
  alerts/
    AlertTemplateRenderer.kt     Template + POI + context → text (§7)
    PoiAlertEngine.kt            The maths (§11)
    AlertDispatcher.kt           Routes events to the right output (§12)
    NavTts.kt                    TextToSpeech + audio focus + queue
    AlertNotificationBuilder.kt  Nav-category heads-up with CarAppExtender
  icons/
    IconRepository.kt            Built-in + user icons; bitmap cache in two sizes
  phone/
    MainActivity.kt + Compose screens (§13)
  settings/
    SettingsStore.kt             DataStore (units, voice, thresholds, GPS rate curve)
```

---

## 4. Data model

### 4.1 Room entities

```kotlin
@Entity(tableName = "poi_set")
data class PoiSet(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val source: PoiSource,                 // PGW | FILE | REPORT ("My reports", §8.5)
  val format: PoiFormat,                 // IGO_TXT | RADARDROID_TXT | NOKIA_TXT | KML | GPX | OV2 | CSV
  val csvProfileId: Long?,               // when format == CSV
  val enabled: Boolean = true,
  val showOnMap: Boolean = true,
  val alertsEnabled: Boolean = true,
  val defaultIconId: String,             // "builtin:camera_fixed" | "user:<uuid>"
  val defaultAlertTemplate: String,      // 1st alert, e.g. "{category} ahead, {speed} limit"
  val secondAlertTemplate: String,       // e.g. "{category}, {distance}"
  val passingAlertTemplate: String,      // e.g. "{category} now"
  val soundEnabled: Boolean = true,      // spoken alerts for this set; turning it on makes the passing alert compulsory
  @Embedded(prefix = "a1_") val firstAlert: AlertStage,   // §11.1
  @Embedded(prefix = "a2_") val secondAlert: AlertStage,  // §11.1
  val minZoom: Float = 11f,
  val state: SetState = SetState.ACTIVE, // ACTIVE | STAGING
  val replacesSetId: Long? = null,       // for staging sets
  val poiCount: Int = 0,
  val lastUpdated: Long
)

@Entity(tableName = "poi_category",
  foreignKeys = [ForeignKey(PoiSet::class, ["id"], ["setId"], onDelete = CASCADE)],
  indices = [Index("setId"), Index(value = ["setId", "sourceCode"], unique = true)])
data class PoiCategory(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val setId: Long,
  val sourceCode: String,                // e.g. iGO TYPE "1", or KML folder name
  val name: String,                      // "Fixed camera"
  val iconId: String?,                   // null = use set default
  val alertTemplate: String?,            // null = use set default (1st alert)
  val secondAlertTemplate: String?,      // null = use set default
  val passingAlertTemplate: String?,     // null = use set default
  val alertsEnabled: Boolean = true,
  val soundEnabled: Boolean? = null,     // null = use set's setting
  @Embedded(prefix = "a1_") val firstAlert: AlertStage?,  // null = use set's setting
  @Embedded(prefix = "a2_") val secondAlert: AlertStage?, // null = use set's setting
  val zoneRole: ZoneRole = ZoneRole.NONE // NONE | ZONE_START | ZONE_END (average speed)
)

@Entity(tableName = "poi",
  foreignKeys = [ForeignKey(PoiSet::class, ["id"], ["setId"], onDelete = CASCADE)],
  indices = [Index(value = ["setId", "gridCell"]), Index("gridCell")])
data class Poi(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val setId: Long,
  val categoryId: Long?,
  val lat: Double,
  val lon: Double,
  val name: String?,
  val speedLimit: Int?,                  // in the source's units, see speedUnit
  val speedUnit: SpeedUnit?,             // MPH | KMH
  val directionType: DirectionType,      // ANY | ONE_WAY | BOTH
  val bearing: Float?,                   // degrees true, 0–359.x, the direction of travel the POI applies to
  val iconOverride: String?,
  val alertOverride: String?,
  val extra: String?,                    // JSON of unmapped source fields
  val gridCell: Long,                    // §4.3
  val hiddenByReport: Boolean = false    // "Camera not there" reported; cleared when the next PGW release still has it (§8.5)
)

/** Settings for one configurable alert stage. Distances are stored in metres and edited in the user's units (yards/miles or metres/km). */
data class AlertStage(
  val enabled: Boolean,                  // 1st: always true; 2nd: can be turned off
  val mode: AlertDistanceMode,           // FIXED | TIME
  val metres: Int,                       // FIXED distance, or the minimum distance for TIME
  val seconds: Int?                      // TIME: seconds ahead at current speed
)

@Entity(tableName = "place",
  indices = [Index(value = ["placeId"], unique = true), Index("lastUsedAt")])
data class Place(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val placeId: String?,                  // Google Place ID when known (used to dedupe)
  val name: String, val address: String?,
  val lat: Double, val lon: Double,
  val kind: PlaceKind,                   // RECENT | FAVOURITE | HOME | WORK
  val origin: PlaceOrigin,               // APP | GMAPS_TAKEOUT | GMAPS_TIMELINE | GMAPS_SAVED | GMAPS_PORTABILITY | GMAPS_SHARE
  val lastUsedAt: Long, val useCount: Int = 1
)

/** Tombstone: a Google Maps entry the user deleted. Stops re-imports from bringing it back. */
@Entity(tableName = "place_hidden", primaryKeys = ["key"])
data class HiddenPlace(val key: String /* placeId, or "lat5,lon5,name" hash */, val hiddenAt: Long)

@Entity(tableName = "camera_report", indices = [Index("status")])
data class CameraReport(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val kind: ReportKind,          // MOBILE_SEEN | NEW_FIXED | NEW_AVERAGE | NEW_RED_LIGHT | NEW_RED_LIGHT_SPEED |
                                 // CAMERA_GONE | WRONG_LIMIT | WRONG_DIRECTION | WRONG_TYPE
  val lat: Double, val lon: Double,    // snapshot taken the moment Report was tapped
  val heading: Float?, val speedMps: Float?, val accuracyM: Float?,
  val road: String?,                   // Navigation SDK road name, if known
  val speedLimit: Int?, val speedUnit: SpeedUnit?,
  val directionType: DirectionType,    // default ONE_WAY using `heading`
  val relatedPoiId: Long?,             // for CAMERA_GONE / WRONG_* reports about an existing camera
  val comment: String?,                // added on the phone only
  val createdAt: Long,
  val status: ReportStatus,            // DRAFT | QUEUED | SENDING | SENT | FAILED | DISCARDED
  val lastError: String?
)

@Entity(tableName = "csv_profile")
data class CsvMappingProfile(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val delimiter: Char, val hasHeader: Boolean, val charset: String,
  val latCol: Int, val lonCol: Int, val nameCol: Int?, val speedCol: Int?,
  val bearingCol: Int?, val dirTypeCol: Int?, val categoryCol: Int?,
  val dirTypeMap: String?,               // JSON: source value → DirectionType
  val speedUnit: SpeedUnit
)
```

**Meaning of `bearing`:** the direction of travel the POI applies to, in degrees clockwise from true north. A camera facing north-bound traffic has bearing ≈ 0, whichever way the camera itself points. Each importer converts to this meaning (§5).

### 4.2 Icon and template resolution
For an alert or marker about POI *p* in set *s* with category *c*:

```
icon     = p.iconOverride     ?: c?.iconId        ?: s.defaultIconId
template = p.alertOverride    ?: c?.alertTemplate ?: s.defaultAlertTemplate   // 2nd/passing templates use the same order
alertsOn = s.enabled && s.alertsEnabled && (c?.alertsEnabled ?: true)
soundOn  = alertsOn && (c?.soundEnabled ?: s.soundEnabled) && !globalMute && !setMute
stage1   = c?.firstAlert  ?: s.firstAlert
stage2   = c?.secondAlert ?: s.secondAlert
passing  = soundOn          // 100 yd passing alert: compulsory whenever sound is on, and has no settings (§11.1)
```

### 4.3 Grid-cell scheme
- Cell size is **0.01°** of latitude by 0.01° of longitude. That's about 1.11 km north–south everywhere, and about 0.69 km east–west at 52°N (UK).
- `row = floor((lat + 90) / 0.01)`, `col = floor((lon + 180) / 0.01)`, `gridCell = row * 36_000 + col`. This fits in a Long and has no collisions.
- **Viewport query**: list the cells covering the visible bounds. If there are more than 400 cells (zoomed out), skip the query; `minZoom` usually prevents this anyway. Then `SELECT … WHERE gridCell IN (:cells) AND setId IN (:enabledSets)`.
- **Alert cache**: `PoiGridCache` holds the 5×5 block of cells around the vehicle (roughly ±2.5 km N–S and ±1.7 km E–W in the UK). It reloads only when the vehicle moves into a new cell, and the reload runs on IO in the background. The engine's per-fix work never touches Room.

### 4.4 Staging and atomic swap
1. Import creates a `PoiSet(state = STAGING, replacesSetId = old?)` and streams rows into it in batches of 2,000 in a single transaction per batch.
2. On success, one transaction does the swap:
   - Copy the user's icon and template customisations from the old set's categories to the new ones, matched on `sourceCode`. Copy set-level settings too.
   - Delete the old set, which cascades to its POIs.
   - Mark the new set ACTIVE.
3. On failure the staging set is deleted, and the old set is never touched.
4. At startup, any leftover STAGING sets are deleted.
5. After the swap, `PoiRepository` emits a `setsChanged` Flow. `PoiGridCache` and `PoiMarkerLayer` reload.

---

## 5. POI importer spec

**Free and third-party POI files are loaded by hand only.** You pick a file on the phone, through the Storage Access Framework or by sharing a file to the app. There's **no automatic download, scheduled refresh or URL subscription** for any source other than PGW (§6). Updating a free set means re-importing it by hand (§13 → set detail → **Re-import**), which keeps your icon, template and alert settings (§4.4).

### 5.1 Common interface
```kotlin
interface PoiImporter {
  val format: PoiFormat
  fun sniff(head: ByteArray, fileName: String): Boolean   // auto-detect
  suspend fun import(input: InputStream, ctx: ImportContext): Flow<ImportRow>
}
data class ImportRow(val lat: Double, val lon: Double, val name: String?, val categoryCode: String?,
                     val speed: Int?, val speedUnit: SpeedUnit?, val dirType: DirectionType,
                     val bearing: Float?, val extra: Map<String, String>)
```
- `ImportRunner` handles the shared steps:
  - unzipping and choosing the files inside
  - character set detection (UTF-8 first, then Windows-1252)
  - validation: lat −90..90, lon −180..180, and not (0,0)
  - batching, progress reporting and staging
  - an error report: counts of good rows, skipped rows and why, and the first 20 bad lines
- An import **fails as a whole** if more than 20% of rows are bad or there are no good rows. Otherwise it succeeds with warnings.

### 5.2 iGO TXT (main format, PGW) — **checked against real download v24.101 (07 Oct 26)**
**What's in the package**: `idPackage=61`, "UK - Single file with all cameras", with "Include pMobile files" ticked. The zip contains:

| File | Contents |
|---|---|
| `pocketgps_uk_sc.txt` | Main database, 17,310 cameras |
| `pocketgps_uk_pmobile.txt` | *Pending* mobile sites submitted but **not yet verified** by PGW, 2,516 |
| `Release.txt` | `Version 24.101 (07 Oct 26)`, the installed-version source of truth (cross-checked against the RSS feed, §6.2) |
| `Licence.txt`, `_Need_Help.htm` | Licence terms (§6.4) and help; not imported |

- **Format as found**:
  - Comma-separated, ASCII, LF line endings (Release.txt uses CRLF; handle both).
  - Header `X,Y,Type,Speed,DirType,Direction`; matching ignores case.
  - Coordinates have 5 decimal places.
- **Watermark row**: the **last line** of each file is `0.72185,51.51508,Copyright PocketGPSWorld.com,,`. It's a copyright and watermark row with a non-numeric Type, and the importer **must skip it**. Generally, any row whose Type isn't an integer is skipped quietly; it isn't counted as an error.
- **Empty fields are normal**. In `sc`, 163 rows (mostly mobile sites) have empty DirType/Direction, and some rows have an empty Speed. They're valid rows, not errors.

| Column | Field | Values found | Mapping |
|---|---|---|---|
| X | lon | −8…+2 | `lon` |
| Y | lat | 49…61 | `lat` |
| Type | category | `1, 3, 4, 5, 11` (see below) | `categoryCode` |
| Speed | limit, **mph** | `20,30,40,50,60,70`, `0`, empty | 0 or empty → `null`; unit MPH for UK packages |
| DirType | direction type | `1`, `2`, empty (**no 0** in the data) | 1 → ONE_WAY; 2 → BOTH; empty or 0 → ANY |
| Direction | **direction of travel**, degrees | 1–360, empty | **360 → 0**; empty → `null` (forces ANY) |

- **The meaning of Direction is settled**: PGW's own submission form labels this field **"Heading / 'Direction of Travel'"**. So `bearing` is the heading of the traffic the camera enforces, which matches our meaning (§4.1). There's no invert switch by default; it stays as a hidden debug option only.
- **Type codes**: PGW's iGO export uses its own codes, which are **different from the submission form's codes** (§8.5).

| Code | Count (sc) | Category | Notes |
|---|---|---|---|
| 1 | 3,932 | Fixed camera (Gatso, Truvelo, Monitron, Vector…) | |
| 3 | 778 | Red light camera | |
| 4 | 3,396 | Average speed camera (SPECS) | **Start, middle and end aren't marked.** All SPECS points are type 4. Zones are worked out from chains of points (§11 step 8). |
| 5 | 8,888 | Mobile camera site | Every row in `pmobile` is type 5 |
| 11 | 316 | Red light + speed (RedSpeed) | **Best guess.** Mostly 30 mph urban junctions. Confirm by downloading package 288 "Consolidated by type" once, comparing positions, and updating the table. |

- Codes not in the table become a category named "Type N".
- **pMobile set**: imported as a **separate PoiSet** "PGW pending mobile sites", alerts **off by default** and a lighter icon. PGW says these sites "have not been checked for accuracy", so they could otherwise cause false alerts. They can be turned on in the POI sets screen.
- **Fixtures**: PGW's licence forbids copying the data. Each download is also **watermarked to the member**. So **no real PGW data goes into the repo or tests**. The fixtures are made-up files that copy the structure exactly: the header, empty fields, Direction=360, the copyright last row, and pMobile.

### 5.3 RadarDroid TXT / Nokia SpeedCam TXT
- Similar layouts, with the exact columns confirmed from a sample file. Both get their own importer with a column table, sharing code with iGO through `DelimitedCameraImporter(columns: ColumnSpec)`.

### 5.4 KML (NDrive or generic)
- Stream-parse with XmlPullParser.
- Each `Placemark/Point/coordinates` gives lon,lat. The name comes from `<name>`. The category is the enclosing `<Folder><name>`, or the `styleUrl` if there's no folder.
- Bearing, if present, comes from `<ExtendedData><Data name="heading|bearing|direction">` or `<Style><IconStyle><heading>`. With a bearing, the direction type is ONE_WAY unless an extended `dirtype` or "both" in the description says otherwise. With no bearing it's ANY.
- Speed comes from extended data `speed`, or a regex on the name or description: `(\d{2,3})\s*(mph|km/?h)?`.

### 5.5 GPX
- Each `<wpt lat lon>` gives one POI. The name comes from `<name>` and the category from `<type>`, or else `<sym>`.
- Bearing from `<course>` (GPX 1.0) or `<extensions>` keys `course|heading|bearing`. Speed is regex-matched as in KML.

### 5.6 TomTom OV2 (binary, no direction)
- Records are read until the end of the file:
  - **type 2** (POI): `int32 len, int32 lon×100000, int32 lat×100000, zero-terminated name`
  - **type 1** (skipper block): skipped using its length
  - **type 0** (deleted): skipped
- All values are little-endian.
- The direction type is always ANY. Speed is regex-matched from the name, for example `[SPECS] 50`.

### 5.7 Generic CSV (wizard)
- The phone wizard:
  1. Pick the file.
  2. Auto-detect the delimiter (`,` `;` tab `|`) and whether there's a header.
  3. Show the first 10 rows in a table.
  4. Choose a column from a dropdown for each field (lat, lon required; name, speed, bearing, direction type, category optional).
  5. Map direction-type values, with presets: `0/1/2`, `any/one/both`, `A/O/B`.
  6. Choose the speed unit.
  7. Live preview of 5 parsed POIs with their direction arrows.
  8. Save as a `CsvMappingProfile`, which can be reused on re-imports.
- With a bearing column and no direction-type column, the direction type defaults to ONE_WAY. With neither, it's ANY.

### 5.8 Zip handling
- `ZipUnpacker` lists the entries. If exactly one entry matches the chosen format, it's used. If several match, the user ticks which to import: one set each, or all merged into one set.
- For PGW, the expected file name pattern lives in `PgwConfig`.
- Zip-slip protection: entries are never written outside a temp directory. Total unpacked size is capped at 200 MB.

---

## 6. PGW sync

### 6.1 Flow
0. **Update check (RSS)**: `PgwUpdateFeed` reads `https://www.pocketgpsworld.com/speed-camera-rss.php` (§6.2). The download in steps 1–4 only runs when the feed shows a **newer database version** than the one installed, or when you press **Sync now** or **Force re-download**.
1. `CredentialStore`: the username and password are encrypted with AES-GCM, using a key in the Android Keystore with no user authentication required, so it works in the background. The encrypted data is kept in DataStore.
2. `PgwClient`, using OkHttp with an in-memory cookie jar, a 30 s timeout and a User-Agent naming the app:
   1. **Login**: checked on 2026-10-11. The site is PHP-Nuke. A plain form POST to `https://www.pocketgpsworld.com/modules.php?name=Your_Account` (`application/x-www-form-urlencoded`) with:
      `username=<u>&user_password=<p>&op=login&redirect=&mode=&f=&t=`
      - There's **no CSRF token and no captcha** on the login form. Its hidden fields are empty constants.
      - A login counts as successful if a session cookie is set and the response contains "logout" / "My Account" and **not** the `user_password` field.
      - The session cookie's name and how long it lasts are recorded in phase 1. The cookie jar is persisted in encrypted storage so a weekly sync can usually skip logging in. If any request gets the login form back, the app logs in again once and retries.
   2. **Generate the download**: POST to `modules.php?name=Cameras` with
      `op=DownloadPackage&idPackage=61&getPmob=yes&getFrance=no&getSwiss=no`
      - These are the same fields the site's own **"Download Same Format (Current Version)"** button sends.
      - `System=iGO` and `voicePack=noVoice` are only used on the page to fill the package list. They're sent anyway to match the browser.
      - Other regions use the `idPackage` values found: EUR 155, AUS/NZ 156, AFRICA 157, USA 158, ASIA 159, CANADA 193, plus the "consolidated by type" variants 288–294. A region picker in the phone app is left to v2.
   3. **Find the link**: the response contains a link matching `/temp/users/pgpsw-speed-cams-[A-Za-z0-9]+-\((\d+)-(\d+)\)\.zip`, which includes the version, for example `pgpsw-speed-cams-tETDF-(24-101).zip`. The version in the file name is checked against the RSS release.
   4. **Download**: GET the temp URL. **No cookie is needed**: it was fetched without authentication and returned `200 application/zip`, 216 KB. It's streamed to `cacheDir/pgw/<version>.zip` with checks on Content-Type `application/zip`, a size over 50 KB, and that the expected entries are inside. The zip is **deleted after import**.
3. `ImportRunner` runs the iGO importer into staging and swaps it in (§4.4). On a re-sync, existing categories keep the user's icon and template choices.
4. Save `lastSyncAt`, `lastSyncResult`, `poiCount` and **`installedVersion`** (from the feed item that triggered the sync). Post a quiet notification with the result, including the feed's own summary, for example "PGW database updated to 24.101: 20 static and mobile cameras added/removed/modified across the databases (12,345 cameras)".

- **Speed and load**: one login (if needed), one POST and one GET of about 220 KB, about once a week.

All URLs, form field names and selectors live in **`PgwConfig`** (a single Kotlin object). The first real login session is used to fill it in.

### 6.2 Update detection and scheduling (RSS feed)
- **The feed** (`speed-camera-rss.php`, RSS 2.0, public, no login needed):
  - Channel: "PocketGPSWorld.com Speed Camera database updates".
  - One `<item>` per database release:
    - `<title>`: version and date, for example `version 24.101:07 Oct 26`
    - `<description>`: a summary of changes, for example "This release sees 20 static and mobile speed cameras added/removed/modified…"
    - `<link>`: the cameras page
  - **The feed has no camera data at all**: no coordinates, types, limits, directions, or per-camera added/removed lists. It's only used to detect a release, and the cameras still come from the logged-in download (§6.1).
  - New releases come out roughly weekly.
- **`PgwUpdateFeed`**: a small XmlPullParser reader.
  - Parses each item into `PgwRelease(version: String, major: Int, minor: Int, date: LocalDate?, summary: String, guid/link)`.
  - The version comes from the regex `version\s+(\d+)\.(\d+)`. The date comes from the part after the colon (`dd MMM yy`, English locale).
  - The newest release is the one with the highest (major, minor). The feed's order isn't relied on.
  - If parsing fails, the whole item's hash is used as the version key, so any change counts as a new release.
- **`PgwFeedCheckWorker`**: a `PeriodicWorkRequest` running **every 24 h** (flex 4 h) that needs any network connection. The feed is tiny, so mobile data is fine.
  - It does a conditional GET: `If-None-Match` with the stored `ETag` and `If-Modified-Since` with the stored `Last-Modified`. Most checks get a **304** reply and cost almost nothing.
  - When the newest version is greater than `installedVersion`: save `pendingRelease`, then queue a one-time **`PgwSyncWorker`** straight away (constraints: **any network connection**, battery not low, unique-work policy KEEP).
  - The whole update is about 220 KB, so it runs on mobile data too, and the new database arrives within minutes of the release. An optional **Wi-Fi only** setting (off by default) can hold it back.
  - First run, with no installed version: queue a sync right away.
- **Safety net**: `PgwSyncWorker` also runs if no sync has succeeded in **14 days**, even if the feed hasn't changed. This covers a feed that has stopped updating or a missed item.
- **Backoff**: the feed check retries with backoff on network errors, capped at the next periodic run. The download uses exponential backoff from 1 h, up to 3 attempts. After that `pendingRelease` is kept and retried on the next feed check.
- **Sync now** checks the feed and, if a newer version exists, downloads it. **Force re-download** downloads without checking the version. Both only need a network connection.
- The phone's PGW screen shows: the installed version and date, the latest version in the feed with its summary, the last feed check, and the last download result.
- **While driving**: the download and import into staging go ahead in the background (one small request, no effect on the car screen). Only the **swap** into the live set is held back until guidance ends or the car disconnects, so the cameras don't change mid-journey. If nothing is being guided, the swap happens straight away.

### 6.2a Fully automatic update (no user action)
When the feed shows a new release, the app does the whole update **by itself**, with no prompt, no tap and no browser:

```
PgwFeedCheckWorker (daily, conditional GET)
  └─ new version > installedVersion?
       └─ PgwSyncWorker (queued at once, any network)
            1. Load the saved cookie jar → GET Cameras page; still logged in?
               └─ no → log in with the saved credentials (Keystore, §6.1 step 1), once
            2. POST op=DownloadPackage (idPackage=61, getPmob=yes) → find the temp zip link
            3. GET the zip (no cookie needed) → check it → Release.txt version == feed version?
            4. Import into staging (sc + pMobile sets) → swap now, or after the drive
            5. installedVersion = new version; quiet notification with the release summary
```

- Credentials are entered **once**, in the phone app's PGW account screen, and then used without asking for every update. They're only sent to `www.pocketgpsworld.com` over HTTPS.
- **You're only asked to do something** when it can't be done automatically: wrong password or an expired subscription (`AUTH_FAILED` or no download link on the page), or a site change (`PARSE_FAILED`). Each gets one notification with a "Fix" action that opens the PGW account screen. Until then the old data stays in use.
- **Expired subscription**: if you're logged in but the download page has no package form or link, the status is `SUBSCRIPTION_REQUIRED` and you're notified once.

### 6.3 Failure handling
| Failure | Behaviour |
|---|---|
| Wrong credentials | Mark `AUTH_FAILED` and stop scheduling until the credentials are re-entered; notify once |
| Site layout changed (selectors miss) | `PARSE_FAILED`; keep the old data; notify the user to use manual import |
| Captcha / 2FA detected | Same as above, plus a "Log in via browser" hint |
| Network/timeout | Retry with backoff |
| RSS feed unreachable / changed format | The feed check fails quietly. The 14-day safety net still syncs. Diagnostics show "Update feed: last OK N days ago" |
| Feed shows a new version but the download has the same content | Accepted as done: `installedVersion` is updated, and the swap is skipped if the content hash matches |
| Import fails validation | Staging set discarded; old data kept |

### 6.4 Terms-of-use risk
PGW's terms may forbid automated downloads. To limit the risk:
- The app logs in only as the user, and **only when the public RSS feed shows a new release** (about weekly), or at most every 14 days. It never redistributes the data.
- The daily feed check is an anonymous conditional GET of a public feed that PGW provides for update notifications.
- Auto-sync can be switched off, and manual import is always available.

- **What the licence says** (`Licence.txt` in each download):
  - The data is licensed for **individual use**.
  - It may not be "copied, redistributed… incorporated… in any application… without prior written authorisation".
  - It may not be stored "on any platform accessible to anyone apart from the purchaser".
  - Each download is **watermarked** so it can be traced to the member.
- **How we comply: each user uses their own account.** The app **never ships, hosts or shares PGW data**. Every user must enter **their own PGW subscriber login**, and the app downloads **that user's own watermarked copy** for their device only. That meets the licence's main terms:
  - "individual use": one subscriber, one copy, on their own phone
  - no copying or redistribution: no data leaves the device, and no shared server or cache exists
  - "only the purchaser": private app storage, no backup, no export
  - the per-member watermark stays traceable to the account that downloaded it
- **The app enforces this**: there's no built-in or default PGW account, credentials can't be shared, and PGW data is never exported or synced between devices. If a subscription lapses, auto-sync stops (`SUBSCRIPTION_REQUIRED`) and nothing more is downloaded.
- **What that means for the design**:
  - The PGW data lives only in the app's **private storage** on your phone.
  - It's **excluded from Android backup** (`dataExtractionRules` / `fullBackupContent` exclude `poi.db` rows for PGW sets; in practice the whole POI DB is excluded and re-synced after a restore).
  - There's **no export** of PGW sets, they're never sent to any server, never bundled in the APK, and never put in the repo or test fixtures.
  - **Personal, non-commercial use only.** The one clause the own-account model doesn't settle directly is "incorporated… in any application… without prior written authorisation", because the app converts the file into its own database. **Low priority** for personal use. A short email to PGW confirming that a subscriber may use their own download in a self-built app removes the doubt, and is **needed before the app is shared with anyone else** (§16).

The user accepts this risk for personal use.

---

## 7. Icons and alert templates

### 7.1 Icons
- **Built-in pack** (vector drawables): `camera_fixed`, `camera_mobile`, `camera_average`, `camera_redlight`, `camera_redlight_speed`, `camera_variable`, `school`, `hazard`, `fuel`, `ev_charge`, `parking`, `toilet`, `pin_red/blue/green/yellow`, `star`.
- **User icons**: a PNG or SVG picked through the Storage Access Framework. It's copied to `filesDir/icons/<uuid>.png` (SVG is drawn once to a 256 px PNG) and its id is `user:<uuid>`.
- **Rendering**: `IconRepository` makes and caches two bitmaps per icon:
  - **map marker**: 48 dp. When the POI has a speed limit, it gets a speed-limit badge (red circle with the number). Small badges are cached per (icon, speed).
  - **CarIcon**: 64 dp, for alerts, list rows and the notification.
- Cache: an LRU of 64 entries.

### 7.2 Template placeholders
| Placeholder | Value |
|---|---|
| `{category}` | Category name, or the set name if the POI has no category |
| `{speed}` | Speed limit converted to the user's units (number only) |
| `{units}` | "mph" / "kilometres per hour" (spoken form) |
| `{distance}` | Distance spoken in the user's units, rounded: "400 yards", "a quarter of a mile", "300 metres" |
| `{direction}` | "ahead" / "on your left" / "on your right", from the bearing to the POI relative to your heading |

- **Rendering rules**:
  - When a placeholder's value is missing, any sentence piece that depends on it is dropped. For example `"{category} ahead, {speed} limit"` with no speed becomes "Fixed camera ahead".
  - Blank spaces and commas left behind are tidied up.
- Each POI has three alert templates, one per stage (§11.1):
  - **1st**: `{category} ahead, {speed} limit`
  - **2nd**: `{category}, {distance}`
  - **Passing** (100 yd): `{category} now`
- Zone enter/leave have their own templates: "Entering average speed zone, {speed} limit" and "Leaving average speed zone".
- The display text and the spoken text come from the same template. Display uses short units ("30 mph") and speech uses full words.

---

## 8. Car UI

All screens use **NavigationTemplate-family** templates, so the map always stays on screen and split-screen with media keeps working.

| Screen | Template | Content |
|---|---|---|
| Home / free-drive | `NavigationTemplate` (no RoutingInfo) | Map, speed panel (§8.4), the next POI ahead (as a `Message` "Camera 0.6 mi") |
| Search | `SearchTemplate` | Places autocomplete (debounced 300 ms, biased to your location); Home/Work, favourites and recents (including Google Maps imports) when the box is empty (§8.2) |
| Recents | `PlaceListNavigationTemplate` | Recents and favourites with markers; delete action on each row, plus Clear all (§8.2) |
| Place detail | `MapWithContentTemplate` / `PaneTemplate` | Navigate · Favourite · Remove from recents |
| Contacts | `SearchTemplate` → `PlaceListNavigationTemplate` | Contacts with a postal address, found by name (voice or typing); one row per address (Home/Work/Other) (§8.3) |
| Results | `PlaceListNavigationTemplate` | Up to 6 places with distance and a marker on the map |
| Route preview | `RoutePreviewNavigationTemplate` | Up to 3 alternative routes (ETA, distance, traffic delay); "Go" |
| Navigating | `NavigationTemplate` + `RoutingInfo` + `TravelEstimate` | Maneuver icon, distance, road, lanes, next step; ETA, remaining time and distance |
| Arrival | `NavigationTemplate` + `MessageInfo` | Destination name, "Done" |
| POI layers | `ListTemplate` (pushed; map stays behind) | Per set: toggle map, toggle alerts; global "Mute alerts for 30 min" |
| **People** | `PlaceListNavigationTemplate` | Tracked HA people with live markers, distance, "updated 2 min ago", and accuracy; tap → Find (§17.3) |
| **Report** (one tap) | `MessageTemplate` "Listening…" with Done / Skip | One tap saves the report, then an optional spoken comment from the car mic; Undo alert (§8.5). No review or Send on the car. |
| More | `ListTemplate` | Recents · POI layers · Mute alerts 30 min · Diagnostics (debug) |

**Action strip** (up to 4): Search · **Report** · People · More. While navigating, People is replaced by Stop navigation, and People moves to More. Recents are also shown in Search's empty state, so they're still one tap away. Mute is also available directly from each alert's own **Mute** action (§12.1). The People action only appears when a HA server is set up.
**Map action strip**: `Action.PAN` · zoom in · zoom out · recenter.

**Visible and stable areas**: `MapPaddingController` reacts to `onVisibleAreaChanged` and `onStableAreaChanged` and turns the inset rectangles into `GoogleMap.setPadding`:
- The stable area sets the camera target, so the car marker stays steady.
- The visible area keeps markers out from under the cards.

This is what keeps the car marker and route clear of the turn card, the rail and the split-screen media panel.

**Gestures**:
- `onScroll` pans and enters pan mode, which turns camera-follow off.
- `onScale` zooms.
- `onFling` gives a short animated pan.
- `onClick` on a marker shows the POI's details in a short-lived `Message`.
- Recenter turns camera-follow back on.

### 8.1 POI marker redraw (on every GPS update)
`PoiMarkerLayer` redraws on **every fix** from `AdaptiveLocationController`, so it runs at the same speed-based 1–10 Hz rate. It also redraws on camera idle (the user panned or zoomed) and on `setsChanged`.

Each redraw:
1. **Pick the POIs to show**: POIs from `PoiGridCache` (in-memory, with no Room access per fix) in sets with `showOnMap`, inside the current visible bounds plus a 10% margin, and at or above each set's `minZoom`. While the user is panning, the bounds come from the camera, and a Room viewport query fills in cells outside the cache once the camera goes idle.
2. **Rank and cap**: sort by distance to the vehicle and keep the closest **200**.
3. **Work out each marker's state**:
   - **icon**: §4.2 resolution
   - **badge**: the speed limit
   - **emphasis**:
     - **ACTIVE**: the POI the alert engine is currently alerting on. Shown 1.3× larger, with a pulse ring and drawn on top.
     - **AHEAD**: the POI passes the ahead-of-you and direction checks (§11 steps 2–3). Full colour.
     - **OTHER**: shown at 60% opacity.
   - **rotation**: ONE_WAY/BOTH POIs show a small direction arrow that rotates with the map bearing.
4. **Diff against what's drawn**, using a map of `poiId → (Marker, lastState)`:
   - newly visible → `addMarker`, using a recycled marker from a pool of 50 when one is free
   - no longer visible → `remove()` and return it to the pool
   - still visible → change only what differs, using `setIcon`, `setAlpha`, `setRotation` or `setZIndex`. If nothing changed, no map call is made.
5. All map calls are batched into **one main-thread post per fix**. If the previous redraw hasn't finished when the next fix arrives, the older one is dropped (conflated `StateFlow`) and only the newest is drawn.

**Cost**: per fix, the work is a distance sort over at most a few hundred in-memory POIs plus a handful of changed markers. In steady driving, usually only emphasis changes, affecting 0–3 markers. Icon bitmaps come from the `IconRepository` LRU, with variants per (icon, badge, emphasis) pre-made, so a redraw never creates a bitmap.

### 8.2 Recent and favourite places (including Google Maps history)

**Where entries come from** (all merged into one `place` table):

| Source | How | Gets | Automatic? |
|---|---|---|---|
| Our own app | Every destination you navigate to, and every search result you pick | Recents | Yes |
| **Google Maps share** | Share a place from Google Maps → "AutoNavAlerts". The share target reads the `maps.app.goo.gl` / `google.com/maps` link, follows the short-link redirect, and gets the place ID, or coordinates from the `!3d…!4d…` / `@lat,lon` parts, then fills in details through Places | Recents (with "Navigate now" if the car is connected) | Each time you share |
| **Google Takeout** | Import the zip on the phone: `My Activity/Maps/MyActivity.json` (the "Searched for" / "Directions to" / "Viewed" entries, coordinates taken from their URLs) and `Maps (your places)/Saved Places.json` (GeoJSON) | Recents (by time), Favourites, Home/Work | No, you import it by hand |
| **On-device Timeline export** | Android Settings → Location → Timeline → Export → `Timeline.json`. We read the `visit` segments (top candidate place ID + location) | Recents: places you actually visited, most recent first, for the last N months (default 3) | No, you import it by hand |
| **Google Data Portability API** (optional) | OAuth with your Google account, using the `myactivity.maps` and `maps` saved-place scopes. A background job (weekly, Wi-Fi) runs an archive job, downloads the result, and runs the same parser as Takeout | Same as Takeout | **Yes**, but it needs a Google Cloud OAuth client set up with you as a test user. Only available in the EEA/UK/Switzerland. |

- There is **no API for reading Google Maps' recents live**. Takeout, Timeline and the Data Portability API are the only official exports. That means imported history is only as fresh as the last import or sync. Recents from our own app and shared places are always current.
- **Merging and duplicates**: the match is on `placeId`. Without one, two entries count as the same if they're within 30 m of each other and have similar names (case-folded, with Levenshtein ≤ 3). On a match, `lastUsedAt` takes the newest value, `useCount` is added together, and HOME/WORK/FAVOURITE win over RECENT.
- Anything with a key in `place_hidden` is skipped (see deleting, below).
- **Limits**: up to 200 recents are kept, oldest removed first. Favourites have no limit.

**Car screen use**:
- With the search box empty, the `SearchTemplate` shows **Home**, **Work**, then favourites and recents ordered by `lastUsedAt`. The car's list limit applies, usually 6 rows while driving, with more when parked (`ConstraintManager.CONTENT_LIMIT_TYPE_LIST`).
- Each row shows the name, distance and a small origin badge (a "G" for Google Maps imports).
- Recents are also on the HomeScreen action strip as **"Recents"**, which opens `RecentsScreen`: a `PlaceListNavigationTemplate` showing markers on the map.

**Deleting from the car screen**:
- Each row in `RecentsScreen` has a **secondary action** (`Row.addAction`, car API ≥ 6) with a delete icon. Tapping it removes the row straight away and shows a 5 s `Alert` with **Undo**.
- On car API < 6, tapping a row opens `PlaceDetailScreen` (`MapWithContentTemplate`/`PaneTemplate` with the map still showing). It has three actions: **Navigate**, **Favourite / Unfavourite**, **Remove from recents**.
- There's also a **"Clear all recents"** action at the bottom of the list, with a confirmation step.
- These are all a couple of taps, and the Car App Library already blocks text entry while driving.
- **Deleting a Google-sourced entry** writes a `HiddenPlace` tombstone, so the next Takeout, Timeline or Data Portability import doesn't bring it back.
- **We can't delete it inside Google Maps**, because there's no write API. The phone app's Places screen has a "Manage in Google My Activity" link for that.
- Home/Work/Favourites can't be removed from the recents action. Removing them needs Unfavourite or the phone app.

### 8.3 Navigating to contacts' addresses
- **Permission**: `READ_CONTACTS`, asked for in onboarding as an optional step. Without it the feature is hidden.
- **Source**: `ContactsContract.CommonDataKinds.StructuredPostal`. Each contact's postal addresses (type HOME/WORK/OTHER/custom label) come with their display name and photo thumbnail. Read-only; the app never changes your contacts.
- **Geocoding**: an address is turned into a location the first time you pick it, never for the whole address book up front, using Places Text Search (or the Geocoding API) on the formatted address with location bias.
  - The result `(placeId, lat, lon)` is cached in Room table `contact_geocode`, keyed by SHA-256(normalised address) so the address itself isn't stored twice. Entries older than 90 days are refreshed.
  - If geocoding gives several matches or a poor-quality one, the matches are shown in a short list to choose from.
- **Car UI**:
  - **Search**: typing or speaking a name ("Mum", "Dave Smith") in the `SearchTemplate` shows matching contacts **above** Places results. A contact row shows its name, address type and distance, if the address has been located before.
  - **Contacts**: a list in the Search screen's empty state (under Home/Work/favourites) with a "Contacts" row that opens a contact search. The full address book isn't listed, because of the car's list limits.
  - Picking a contact address → geocode (if needed) → RoutePreview. Contacts with several addresses show one row per address.
- **Assistant**: "Hey Google, navigate to Mum's house" usually reaches us as `ACTION_NAVIGATE` with an address that Assistant has already looked up (§9.1). If it only sends a name, we look it up against contacts first and then fall back to Places.
- **Recents**: a contact destination is stored as a recent with its name, for example "Mum (Home)". It can be deleted like any other recent (§8.2).
- **Privacy**: contact data is only read on the device, and only the address you picked is sent to Google for geocoding.

### 8.4 Speed panel: current speed and the road's speed limit
- **What it shows**: always on the map, in the corner of the stable area away from the turn card and media panel (bottom-left on landscape, bottom on portrait):
  - **Current speed** in your units (mph/km/h), from the smoothed speed in `AdaptiveLocationController` (§10.3). It updates on every fix, up to 10 Hz, with the number rounded and steadied to avoid flicker.
  - **Road speed limit as Google Maps reports it**, in a UK/EU-style red-ringed sign (or US style, chosen by region).
  - **Colour**: normal; **amber** when over the limit by the minor threshold; **red** when over by the major threshold. The thresholds default to 5% and 10% and are settings.
- **Where the limit comes from**:
  1. **Main source: the Navigation SDK's own speedometer and speed-limit control.** It's turned on with `setSpeedometerEnabled(true)` on the navigation view. The SDK's `SpeedAlertOptions` (minor/major percentages and the time before an alert) and `SpeedingListener.onSpeedingUpdated(percentageAboveLimit, severity)` drive the amber/red state, so the colours match Google Maps. This is Google's road speed-limit data, the same as Google Maps.
  2. **To check in phase 2**: whether `NavigationViewForAuto` shows the speedometer in **free-drive** as well as during guidance, and whether the speed-limit number itself is available to us (the API surface varies by SDK version).
     - If the SDK control works on Auto, we use it.
     - If it only works during guidance, or not on Auto, we draw our own `SpeedPanel`, a View inside the `Presentation` overlaid on the map. Its limit comes from whatever value the SDK exposes, and its amber/red state still comes from `SpeedingListener` (which gives the percentage over the limit, so a limit can be worked out from it while you're over).
  3. **Fallback when Google has no limit for the road**: if a POI set has an active average-speed zone or a nearby camera with a limit, that limit is shown with a small camera icon so it isn't mistaken for Google's figure. Otherwise only your speed is shown.
- **Overspeed audio**: off by default. Google Maps doesn't speak speed warnings, and our POI overspeed warnings (§11 step 9) stay separate. There's an optional chime when you go into the red state.
- **Power**: the panel only redraws when the shown speed or state changes, and it piggybacks on fixes that are already coming in.

### 8.5 Report a camera menu

**Goal**: report a camera while driving with **one tap** and no reading, then check the details calmly after the drive and send them to PocketGPSWorld's **Submit Speed Cameras** form (`modules.php?name=Cameras&op=SubmitCamera`, login needed). PGW checks the reports, and verified new cameras earn subscription rewards.

**Car flow (one button)**:
1. Tap **Report** (⚠) on the action strip. **Straight away, without any menu**, the app saves:
   - **position**: from car GPS if fresh, otherwise the phone (§10.7)
   - **direction of travel**: the heading at the moment of the tap, saved as one-way in that direction
   - **speed limit**: Google's limit if it's known at that moment, otherwise the nearest camera's limit, otherwise none. The source is recorded as GOOGLE, CAMERA or NONE.
     - Google's limit is only exposed by the Navigation SDK **while you're over it** (`SpeedingListener` gives the percentage over the limit, §8.4), so it's often not available. It's confirmed or set at review.
   - **speed, time, road** and the nearest camera already in the database (for "not there" or "seen again")
2. A **voice comment** prompt appears: "Report saved. Say a comment after the tone." A tone plays, then:
   - **Recording**: up to **15 s** from the car's microphone with `CarAudioRecord` (car API 5+, `RECORD_AUDIO`). It stops after **2 s of silence**, or when you tap **Done**. **Skip** saves without a comment.
   - **No microphone** (car API < 5, or the permission isn't granted): the prompt is skipped. The report is still saved, and you can add a comment at review.
3. You're back on the map with a 5 s **Undo** alert.
   - Total: **one tap**, no typing and no reading.
   - The report **type** isn't asked for in the car. It's guessed from the spoken comment ("mobile", "van" → Mobile; "average", "specs" → Average speed; "red light" → Red light; "not there", "gone" → Camera not there; otherwise Not sure) and confirmed at review.

**Speech to text**:
- The recording (16 kHz PCM, saved as a private WAV file) is transcribed **on the phone** with Android's `SpeechRecognizer`, using `EXTRA_AUDIO_SOURCE` on Android 13+, ideally on-device.
- Transcription runs in the background once the recording finishes.
- If it fails or isn't available, the audio is kept. At review you can play it back, retry transcription, or type the comment.
- Audio files are deleted once the report is sent or discarded.

**Buffering and end-of-drive review**:
- Reports are **buffered as drafts** on the phone. **Nothing is ever sent from the car screen or while driving.**
- **End of drive**: when guidance ends at the destination, or the car disconnects (the car session closes), and there are drafts, the phone shows a notification: **"3 camera reports to check"**. Tapping it opens the **Reports** tab.
- **Final review and submission happen only in the phone app** (Reports tab). The head unit never shows the review screen, the edit controls or a Send button; it only captures.
- **Review on the phone**: for each draft you can:
  - **check the map pin** and adjust it with fine nudges (move 10 m N/S/E/W)
  - **change the type**
  - **confirm or change the speed limit** (with its source shown: "from Google" / "from camera data" / "unknown")
  - **change the direction**: heading (±5°/±45° steps or "reverse"), plus **One way / Both ways**
  - **edit the comment** (the transcript), with **Play recording**
  - then press **Send**. There's also **Send all checked**.
- **Never while driving**: Send is disabled, and the submit worker refuses to run, while the phone is connected to Android Auto (`CarConnection` type ≠ NOT_CONNECTED) or has moved faster than 5 mph in the last 2 minutes. A queued send waits and retries once you've stopped.
- There's **no auto-send**. Every report is checked by you before it goes to PGW.
- **`PgwReportSubmitter`**: re-uses the `PgwClient` login and cookies (§6.1).
  1. GET `modules.php?name=Cameras&op=SubmitCamera` while logged in. Pick out the per-user hidden fields `POISubmitted`, `email`, `userid`, `Country` and `MAX_FILE_SIZE` from the page. **Never hard-code or log them.**
  2. Build a `multipart/form-data` POST to `modules.php?name=Cameras` that matches the browser's **"Send Entry"** button (`SendEntry`). **The fields were checked on 2026-10-11; nothing has been submitted yet:**

| PGW field | Type | Our value |
|---|---|---|
| `Action` | select | `1` Add New Camera · `2` Change existing camera · `3` Remove existing camera · `4` **Mobile Seen Again** |
| `idCam` | text | PGW camera ID, needed for Action 2/3/4. Our `Poi.extra` must keep the PGW camera ID, **but the iGO export has no IDs**, see below. |
| `Latitude`, `Longitude` | text | Location saved when Report was tapped, 5 decimal places |
| `Type` | select | `0` Not sure · `1` Gatso (rear) · `2` Mobile · `6` Truvelo (front) · `8` Monitron · `88` Vector-SR · `3` Temporary (roadworks) · `5` Red light · `10` RedSpeed · `4` Specs · `888` Specs start · `777` Specs middle · `999` Specs end · `12` Video |
| `Speed` | select (**code, not mph**) | `0` Unknown · `7` Variable · `1`=20 · `14`=25 · `2`=30 · `15`=35 · `3`=40 · `16`=45 · `4`=50 · `17`=55 · `5`=60 · `18`=65 · `6`=70 · `8`=80 · `9`=90 · `10`=100 · `11`=110 · `12`=120 · `13`=130 · `19`=140 · `20`=160 |
| `Heading` | text | **Direction of travel** in degrees (our saved heading) |
| `Direction` | select | `0` Single direction · `2` Reversible (both) |
| `UsrComments` | textarea | Your comment + "Reported via AutoNavAlerts; heading 274°; ±6 m; road A61" |
| `mapCountry2` | select | `0` United Kingdom (`1` Ireland), from the location |
| `userphoto1/2` | file | Not used in v1 |

  - **Mapping our report types**:
    - MOBILE_SEEN with no known camera → Action 1, Type 2. With a known camera → Action 4 + `idCam`, as PGW asks: "report ALL active mobile camera sites even if they are already known… Select 'Mobile Seen Again'… enter the Camera ID".
    - NEW_FIXED → Action 1, Type 0 (Not sure), unless you pick Gatso/Truvelo on the phone.
    - NEW_AVERAGE → Type 4.
    - NEW_RED_LIGHT → Type 5.
    - NEW_RED_LIGHT_SPEED → Type 10.
    - CAMERA_GONE → Action 3.
    - WRONG_* → Action 2, with the details in the comment.
  - **Camera IDs**: the iGO export doesn't contain PGW camera IDs. So for Action 2/3/4 the app tries the form's "AutoFill Current Values" lookup by position. Which endpoint that uses gets confirmed in phase 1, without submitting anything. If no ID can be found, the report is sent as **Action 1 with a comment** ("Mobile seen again at the known site at lat,lon") and you're shown the browser fallback option.
  - **PGW's tips are built in**: don't report warning signs or Traffic Master cameras (shown as a hint in the phone review screen), use the Comments box, and only report mobiles when a unit is actually there.
  3. Treat the response as success if it contains PGW's thank-you or confirmation text (recorded on the first real submission, which you'll do by hand). Otherwise FAILED, with the response saved for diagnostics (without personal fields).
- **Fallback** when the form can't be automated (fields changed, captcha): **"Open in browser"** opens the SubmitCamera page with the report details copied to the clipboard and shown on screen, so you can paste them.
- Status SENT/FAILED shows in the Reports screen. Failed reports retry with backoff (3 attempts), then are left FAILED and offered for manual sending.

**Privacy**: a report only contains the camera location and the details above. Nothing is sent until you have reviewed it and pressed Send on the phone, and never while driving. Recordings stay in private app storage and are deleted once the report is sent or discarded.

---

## 9. Replacing Google Maps on the head unit

### 9.1 Default navigation app
- The manifest declares `androidx.car.app.category.NAVIGATION`, so Android Auto lists the app as a navigation app.
- The user picks it in Android Auto → Settings → Default navigation. On some versions, launching it once makes it the active nav app.
- `NavSession.onNewIntent` handles `CarContext.ACTION_NAVIGATE` with `geo:lat,lon`, `geo:0,0?q=address` and `google.navigation:q=…`. They're resolved through Places Text Search, or used directly for coordinates, and then go to the RoutePreview screen. Assistant's "navigate to …" and links from messaging and calendar apps all come in this way.
- Phone: the manifest also registers an intent filter for `geo:`/`google.navigation:` VIEW intents. A "Send to car" share target queues the destination for the next car session, or starts it right away if a session is connected.

### 9.2 Split screen with media (Coolwalk)
- On Android Auto's split layout the nav app gets the large map area, and media and calls get cards. Navigation-category apps on NavigationTemplate get this without any extra code.
- We must:
  - keep important content inside the visible area (§8)
  - not show full-screen non-map templates while driving, and keep the list screens short
  - support the narrow and portrait head-unit layouts, which happen automatically through the templates; use the padding rules to keep the marker in the stable area

### 9.3 Background guidance
- Once guidance starts, `NavForegroundService` starts with `foregroundServiceType="location"` and an ongoing notification that shows the next turn.
- `TripPublisher` calls `NavigationManager.navigationStarted()` and then `updateTrip(Trip)` on each TurnByTurn update. When another app is full screen, Android Auto shows our turn info in its rail and widget, and on the cluster.
- `NavigationManagerCallback.onStopNavigation` (the user stopped from the system UI) → stop guidance and stop the service.
- `onAutoDriveEnabled` (a DHU test hook) → start the simulator.
- In free-drive with no route, the service still runs while the car session is connected, so POI alerts keep working. It stops when the session is destroyed and nothing is being guided.

### 9.4 Audio coexistence
See §12.3. Media is **ducked, never paused**.

---

## 10. Location strategy

### 10.1 Two feeds
| Feed | Source | Used for |
|---|---|---|
| Road-snapped | Navigation SDK `RoadSnappedLocationProvider` | Map car marker, guidance |
| Raw adaptive | `FusedLocationProviderClient`, `PRIORITY_HIGH_ACCURACY`, speed-based interval | POI alert engine |

Raw fixes are used for alerts because the SDK's road-snapped rate isn't under our control. The alert maths need a rate we choose so trigger distances are accurate.

### 10.2 Rate formula
```
v = smoothed speed in mph
if v < 10:           hz = 1
elif v >= 50:        hz = 10
else:                hz = 1 + 9 * (v - 10) / 40          // 10 mph → 1 Hz, 30 → 5.5, 50 → 10
step      = clamp(round(hz), 1, 10)                       // whole-Hz steps
intervalMs = 1000 / step                                  // 1000, 500, 333, 250, 200, 167, 143, 125, 111, 100
```
The `10`, `50` and `10 Hz` values are settings (`gpsMinMph`, `gpsMaxMph`, `gpsMaxHz`) with these defaults.

### 10.3 Smoothing and changing the request
- **Speed smoothing**: the median of the last 3 `Location.speed` values when `hasSpeed()`. Otherwise speed is worked out from distance over time between fixes, ignoring fixes with accuracy > 30 m.
- **Changes are held**: the request is only re-sent when `step` differs from the current request's step **and** that new step has held continuously for **2 s**. Speeding up uses the same 2 s hold (there's no faster path) to keep things simple and predictable.
- **Re-sending**: `removeLocationUpdates` then `requestLocationUpdates` with `LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, intervalMs).setMinUpdateIntervalMillis(intervalMs).setMaxUpdateDelayMillis(0).setWaitForAccurateLocation(false)`. Fixes come in on the `alertScope` thread's Looper.
- **Stopped or under 10 mph**: always 1 Hz. There's no lower rate.

### 10.4 Chip limits
- Many phone GPS chips cap at 1–5 Hz. If the requested rate is higher than the chip delivers, FLP just delivers what it can, and we accept that.
- The controller measures the **actual** rate (a moving average of the time between fixes over 10 s) and shows it:
  - in a debug overlay on the car screen in debug builds
  - in a phone diagnostics screen
- If the actual rate is below 50% of the requested rate for 60 s, the request is lowered to `ceil(actualHz)`. This saves FLP work, and the request is raised again if speed rises and another step change happens.
- If the head unit offers car GPS (some projected setups), FLP may use it without us doing anything. We don't depend on this.

### 10.5 Lifecycle
Runs while the foreground service is up (§9.3). It stops when the car session ends and there's no guidance, or when every set has alerts turned off. In that last case it does nothing until a set's alerts are turned back on.

---

### 10.6 Car speed from the head unit
Android Auto can pass the **car's own speed** to the app. The Car App Library reads it through `CarHardwareManager.carInfo.addSpeedListener` (car API level 3+, permission `com.google.android.gms.permission.CAR_SPEED`). The `Speed` object it returns has:
- **raw speed**: the car's measured road speed
- **display speed**: what the car's own speedometer shows, usually a few percent high
- **speedometer unit**: the car's mph/km/h setting

**Setting:** **Speed and location source** = **Car when available** (default) / **Phone only**. The same setting covers car GPS (§10.7).

**What each speed is used for** (when the car's speed is *fresh*, i.e. reported in the last 3 s):
| Use | Speed used | Why |
|---|---|---|
| Speed panel number | **Car display speed** | Matches the dashboard exactly |
| Alert engine (TIME-mode trigger distance, over-limit check) | **Car raw speed** | Accurate in tunnels, under trees and at low speed, and doesn't lag |
| Adaptive GPS rate (§10.2) | **Car raw speed**, applied as soon as each car update arrives | Reacts as the car speeds up, without waiting for GPS smoothing |
| Position and heading | **Phone GPS**, unchanged | The car only gives speed. Car GPS (`CarSensors.addCarHardwareLocationListener`) is a possible later source |

**Fallback:**
- If the car doesn't provide speed (the value status isn't SUCCESS), or nothing has arrived for 3 s, the phone's smoothed GPS speed is used, exactly as before.
- The car screen's **More → GPS** row shows which source is in use.

**Simulation:** during a debug **Simulate** drive the simulated speed is used, and car speed is ignored. The DHU only sends speed with `config/default_sensors.ini`.

### 10.7 Car GPS for location
The car's GPS position is read through `CarHardwareManager.carSensors.addCarHardwareLocationListener(UPDATE_RATE_FASTEST, …)` (car API 3+, uses the existing fine-location permission). It's controlled by the same setting as speed, renamed **Speed and location source**: **Car when available** (default) / **Phone only**.

- **Primary feed:** while the car's location is **fresh** (a fix in the last 2 s, with status SUCCESS), car fixes feed the alert engine, the per-fix marker redraw, the report snapshot and the free-drive camera checks. Phone fixes are ignored during that time.
- **Phone as backup:** while car GPS is fresh, the phone's fused-location request drops to **1 Hz** to save power. If the car stops reporting for 2 s, phone fixes take over straight away, and the adaptive 1–10 Hz rate (§10.2) is restored.
- **Rate:** the car decides how often it reports. We ask for its fastest rate. The adaptive rate only applies to the phone.
- **Not changed:** the **map's** car marker and turn-by-turn routing come from the Navigation SDK, which uses the phone's location services. The SDK has no way to feed it the car's GPS. On many cars, Android Auto already passes the car's GPS into the phone's location services anyway.
- **Diagnostics:** **More → GPS** shows "Location from car / phone" and "Speed from car / phone".
- **Simulation:** during a debug Simulate drive, simulated positions are used and car GPS is ignored. The DHU provides car location with `config/default_sensors.ini`.

## 11. Alert engine maths

Inputs per fix: position *P*, smoothed speed *v* (m/s), heading *h*, plus the route polyline if one is set.

- **Heading**: `Location.bearing` when `hasBearing()` and *v* > 2 m/s. Otherwise the bearing between the last two fixes more than 5 m apart. Below 2 m/s the last good heading is kept, and no **new** alerts start until the speed is over 2 m/s again.

For each candidate *q* in the cached cells, with the set's and category's alerts on and not `hiddenByReport`:

1. **Distance**: *d* = haversine(*P*, *q*). Skip if *d* > `maxLookahead`, which is the trigger distance + 200 m, capped at 3 km.
2. **Ahead of you**: β = bearing(*P*→*q*). Keep if |angleDiff(β, *h*)| ≤ **30°**. Within 150 m this widens to 45° to cope with bends.
3. **Direction check**:
   - ANY or no bearing: passes
   - ONE_WAY: passes if |angleDiff(*q*.bearing, *h*)| ≤ **45°**
   - BOTH: passes if that's ≤ 45° **or** |angleDiff(*q*.bearing + 180, *h*)| ≤ 45°
4. **Route matching** (only with a route): the cross-track distance from *q* to the route polyline ahead of you (the next 3 km of segments). If it's more than **50 m**, drop the POI, which removes cameras on parallel roads. Without a route, a **lateral offset** check is used instead: *d*·sin(angleDiff(β, *h*)) must be ≤ 40 m.
5. **Alert stages**: three per POI, defined in §11.1. Each fires once, when *d* ≤ that stage's trigger distance.
6. **Missed-passing safety net**: if a POI that passed steps 2–4 suddenly goes from ahead to behind (|angleDiff(β, *h*)| > 90°) within 150 m, and its passing alert hasn't fired yet, the passing alert fires right away. This covers a GPS jump or slow fixes that skip over the 100 yd mark. When sound is on the passing alert can't be missed.
7. **Deduplication**: per POI it keeps `{firstFired, secondFired, passingFired, closestD}`. The state is cleared once *d* is past its lowest point by more than 100 m (you've passed it) or the POI is more than 2 km behind. That way the same POI re-alerts on a later pass but not twice on one approach.
   - **Rate limit**: at most 1 new spoken 1st/2nd alert every 3 s. Anything closer than the last one jumps the queue.
   - **The passing alert is never rate-limited, dropped or merged.**
   - If one fix crosses several thresholds at once (because of high speed or a late cell load), only the closest stage is spoken. For example, crossing the 1st and 2nd together speaks the 2nd. The skipped stages are marked as fired.
8. **Average-speed zones**:
   - **With marked start and end** (sources that tag ZONE_START/ZONE_END):
     - Going past a ZONE_START POI that passes the checks starts zone mode, with the limit taken from that POI.
     - While in the zone, the overspeed check runs on each fix.
     - A ZONE_END POI ends it with a "Leaving zone" alert.
   - **PGW (all SPECS points are type 4, with no start or end)**: zones are worked out from **chains of points**.
     - When you pass a type-4 POI that passes the checks while you aren't in a zone, zone mode starts ("Entering average speed zone, {speed} limit"), using that POI's limit.
     - Each later type-4 POI you pass (one that passes the direction check) keeps the zone going and updates the limit if it changes.
     - The zone **ends** when no type-4 POI that passes the direction check is ahead within **5 km**, or none has been passed for **5 km** (SPECS points are usually ≤ 2–3 km apart). Then you hear "Leaving average speed zone".
     - The 5 km gap is a setting.
     - Optional pre-processing at import: `ZoneChainer` groups type-4 points by nearest neighbour (≤ 5 km, bearing within ±30°) into `zoneId` chains and marks the ends of each chain. That improves the "entering" alert at the first camera, but the engine also works without it.
   - The zone also ends after 30 km or 30 min without an end POI.
9. **Overspeed**: if the POI or zone has a limit and *v* > limit × (1 + `overspeedPct`). It's set with a **slider from −5% to +20% in 0.1% steps**, defaulting to **−0.1%** (so the warning triggers just below the limit, at 99.9% of it), the alert gets an "you are over the limit" suffix. In a zone, a repeat overspeed chime can play at most every 15 s.

### 11.1 Alert stages
| Stage | Settings | Default | Can be turned off? |
|---|---|---|---|
| **1st alert** | Mode FIXED (distance) or TIME (seconds ahead at current speed, with a minimum distance) | TIME: 30 s, min 500 yd (457 m) | No. Its distance can be set, but the stage stays (turn the whole set's alerts off to remove it). |
| **2nd alert** | Turned on/off; mode FIXED or TIME; distance or seconds and minimum | On, FIXED 300 yd (274 m) | Yes |
| **Passing alert** | None: fixed at **100 yd (91.44 m)** | 100 yd | **No. Compulsory whenever sound is on for the POI** (`soundOn`, §4.2). With sound off it's skipped and only the visual alerts run. |

- **Where it's set**: per set, with per-category overrides, in the phone's set detail screen (§13). The settings screen holds the defaults for new sets. Distances are entered in the user's units: yards/miles in imperial, metres/km in metric. The passing distance always shows as "100 yd (91 m)".
- **Trigger distance**: FIXED `D = metres`. TIME `D = max(metres, v × seconds)`; for example 30 s at 70 mph (31.3 m/s) is 939 m.
- **Validation** (in the editor, and again at runtime):
  - D(2nd) must be at least 100 yd + 50 m (≈ 142 m)
  - D(1st) must be at least D(2nd) + 100 m
  - In TIME mode the rule is checked at the current speed every fix. If the times shrink the gap, the 2nd distance is held to D(1st) − 100 m and the passing distance stays 91.44 m.
- **What each stage does**:

| Stage | Visual | Speech | Car `Alert` / notification |
|---|---|---|---|
| 1st | Car `Alert` with progress bar starting | 1st template | New alert, id = POI |
| 2nd | Same alert updated, progress continues | 2nd template | Replaces the 1st (same id) |
| Passing | Alert updated to "Now", ACTIVE marker pulses (§8.1) | Passing template (with chime first if the chime is on) | Replaces; dismissed 3 s after the POI is behind you |

**Output**: `AlertEvent(poi, set, category, kind = FIRST|SECOND|PASSING|ZONE_ENTER|ZONE_EXIT|OVERSPEED, distanceM, iconId, displayText, spokenText, priority)`.

**Cost**: the 5×5 cell cache holds about 10–200 POIs in the UK. Each fix does about 200 haversine and bearing calculations, well under 0.1 ms. At 10 Hz the CPU cost is negligible.

---

## 12. Alert dispatch

`AlertDispatcher` takes `AlertEvent`s on a channel and decides where each one goes based on what's showing:

| State | Visual | Speech | Cluster |
|---|---|---|---|
| Our car screen in foreground | `AppManager.showAlert(Alert)` | NavTts | Trip update (if guiding) |
| Car connected, another app full screen (e.g. media) | Nav heads-up notification | NavTts | Trip update (if guiding) |
| Phone only (no car) | Phone heads-up notification | NavTts | — |

Which state applies comes from the `Screen` lifecycle (STARTED = foreground) plus `CarConnection.type`.

### 12.1 Car `Alert` (car-app API ≥ 5)
- `Alert.Builder(alertId = poi.id.hashCode(), title = CarText(displayText), durationMillis = 8000)`
- `.setIcon(carIcon)`
- `.setSubtitle("0.4 mi · 30 mph")`
- `.addAction(Dismiss)` and `.addAction(Mute this set 30 min)`. For camera POIs, **Mute** is swapped for **Report** (§8.5), and mute stays on the action strip (More).
- `.setCallback`, which handles cancel and dismiss
- The SECOND and PASSING alerts for the same POI replace the earlier one through the same `alertId`, using `AppManager.dismissAlert(id)` then show.
- Template shortcut: the free-drive `Message` text is also updated to show the alert.

### 12.2 Navigation heads-up notification (background)
- Channel `poi_alerts`, `IMPORTANCE_HIGH`, with no sound because TTS does the speaking. Vibration is off.
- Built with `NotificationCompat.Builder`:
  - `.setCategory(CATEGORY_NAVIGATION)`
  - `.setSmallIcon(...)`, `.setLargeIcon(iconBitmap)`
  - `.setContentTitle(displayText)`, `.setContentText(distance)`
  - `.setOnlyAlertOnce(false)`, `.setTimeoutAfter(10_000)`
- `.extend(CarAppExtender.Builder()`:
  - `.setImportance(IMPORTANCE_HIGH)`
  - `.setSmallIcon`, `.setLargeIcon`
  - `.setContentTitle`, `.setContentText`
  - `.setContentIntent(CarPendingIntent → open our car screen)`
  - `.addAction(Mute)`
- Notification id = `poi.id`, so SECOND and PASSING alerts update the existing notification instead of stacking.
- Android Auto shows navigation-category heads-up notifications from the active nav app the same way it shows turn notifications.

### 12.3 TTS on the navigation audio channel
- `NavTts` sets up one `TextToSpeech` engine, with locale from settings, and warms it up when the car connects.
- Audio attributes: `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, `CONTENT_TYPE_SPEECH`. On Android Auto this routes to the car's navigation audio stream, the same one turn prompts use.
- Focus: `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` is requested just before speaking and **abandoned in `onDone`/`onError`**. Media apps duck and don't pause.
- **Queueing behind SDK voice**:
  - The Navigation SDK speaks turn prompts itself.
  - We register an `AudioManager.AudioPlaybackCallback` and treat any active `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` player that isn't ours as "SDK speaking".
  - While the SDK is speaking, our utterance waits for up to 6 s, then plays.
  - If a newer, closer alert comes in, the waiting one is replaced.
  - Our own utterances play first come, first served, with SECOND/OVERSPEED jumping the queue.
  - **PASSING** has top priority. It doesn't wait the 6 s behind SDK voice; it waits at most 1 s, then plays. It replaces any of our own queued speech for that POI, and is never dropped.
- Optional chime before speech (setting): a short earcon played through the same attributes in the same focus window.
- Mute: global and per-set mute stop speech only; visual alerts still show. While muted `soundOn` is false, so the passing alert is skipped too. The compulsory rule means "always when sound is on", not "even when muted". A "Silent mode" setting turns off visuals as well.

### 12.4 Cluster
While guiding, `TripPublisher` puts the active POI alert into the `Trip` for 8 s, for example as a short `Step` cue "Camera 0.4 mi", and then puts the normal step back. Clusters that don't support this ignore it. Car cluster support varies by vehicle, so this is best effort.

---

## 13. Phone app screens (Compose, Material 3)

1. **Onboarding / Setup tab** (first run, in order). Step 0 is the **Google Maps API key** field, which saves the key encrypted and re-initialises the Navigation SDK and Places:
   1. Location (fine) → background location ("Allow all the time", with an explanation of why)
   2. Notifications
   3. Contacts (optional, for navigating to contacts' addresses)
   4. Navigation SDK Terms & Conditions dialog (`NavigationApi.showTermsAndConditionsDialog`)
   5. Android Auto setup checklist (developer mode, unknown sources), with deep links
2. **Reports**: the end-of-drive review (§8.5). Per report: type (guessed from the comment), position nudges, speed limit with its source, direction (heading ± and One way / Both), comment transcript with Play recording, then Send / Send all / Save / Discard / Browser. Send is disabled while connected to the car or moving.
3. **POI sets** (home): a card per set showing the name, source, count, last updated, and switches for map and alerts. FAB: Import file / Add PGW.
4. **Import flow**: pick file → auto-detect format (shown, can be overridden) → the CSV wizard if needed (§5.7) → name the set → choose the default icon and template → progress → result report.
5. **Set detail**:
   - Default icon, and **Alerts** settings:
     - **1st alert**: mode (distance / seconds ahead), value, minimum distance, template
     - **2nd alert**: on/off switch, mode, value, minimum distance, template
     - **Passing alert (100 yd)**: shown read-only as "Always on while sound is enabled", with its own template
     - **Sound** switch
     - checks from §11.1 shown inline as you type

   - The category list: each category's icon and template override, alerts switch, and zone role.
   - A map preview of 50 sample POIs with direction arrows.
   - Actions: re-import, delete, export settings.
6. **Icon picker**: grid of built-in icons and user icons, plus "Add from file".
7. **Template editor**:
   - text field with placeholder chips
   - live preview against a sample POI (display and spoken versions)
   - **Test speak**, which uses the real NavTts so the audio route can be checked
8. **PGW account**: username and password, "Test login", auto-sync switch, last sync status, **Sync now**, and a terms-of-use risk note.
9. **Settings**:
   - Units: mph/km/h and miles/km.
   - Alerts: voice on/off, chime on/off, overspeed %, and the default 1st and 2nd alert stages for new sets.
   - GPS: the rate curve (min mph, max mph, max Hz).
   - Speed panel: show/hide, sign style (UK/EU / US / automatic), the minor and major over-limit thresholds, and the red-state chime.
   - TTS voice and locale.
10. **Places**:
   - Home/Work, favourites and recents, each with its source. Delete by swiping, with undo.
   - **Import from Google Maps**: Takeout zip, or Timeline.json, with a months-back setting.
   - **Google account sync** (Data Portability API): connect/disconnect, last sync, Sync now.
   - "Hidden places" list (tombstones), where entries can be restored.
   - "Manage in Google My Activity" link.
   - **Contacts**: permission status; "Clear contact location cache".
11. **Diagnostics**: requested vs actual GPS rate, current cell and number of cached POIs, the last 20 alert events, and an export-logs button.

---

## 14. Power budget

| Consumer | Strategy | Expected impact |
|---|---|---|
| GPS (alerts) | Speed-based 1–10 Hz (§10); lowered to what the chip actually delivers | Main extra cost compared with Google Maps, mostly at motorway speeds |
| GPS (map) | Navigation SDK's own feed; no duplicate request from us | Same as Google Maps |
| CPU (alerts) | Event-driven; in-memory 5×5 cell cache; no Room per fix | Negligible |
| Map drawing | Navigation SDK; POI markers redrawn on **every GPS fix** (1–10 Hz, §8.1) as a diff, ≤ 200, existing markers changed rather than re-created, unchanged markers left alone | Small to moderate at 10 Hz; measured on its own in the power test |
| Network | Places only on search; PGW: a daily conditional GET of the feed (usually a 304) plus about 220 KB per release (about weekly), on any network | Negligible |
| TTS | Engine stays warm while connected; focus only while speaking | Negligible |
| Startup | R8 code shrinking, Baseline Profile, Room DB opened in the background | Faster cold start |
| Wakeups | No timers or polling; WorkManager only for sync | — |

**How it's measured**:
1. A 30-minute simulated drive on the DHU, using the Navigation SDK Simulator with a fixed route that mixes town (≤ 30 mph) and motorway (≥ 50 mph) driving.
2. Battery Historian (`adb bugreport`) and Android Studio Energy Profiler, comparing (a) our app with alerts on, (b) our app with alerts off, and (c) Google Maps on the same route.
3. Town and motorway segments are reported separately to show what the 10 Hz mode costs.

**Target**: (a) within 10% of (c) overall.

---

## 15. Testing strategy

### 15.1 Unit tests (JVM, `src/test`)
- **Importers**: **made-up** fixture files for each format. No real PGW data, because of the licence (§5.2). They cover:
  - iGO: the copyright row at the end skipped; empty DirType/Direction → ANY; Direction 360 → 0; empty Speed → null; the pMobile file going into its own set; unknown type → "Type N"
  - valid input
  - CRLF line endings, BOM and Windows-1252
  - comment lines, bad rows, and the 20% failure rule
  - OV2 skipper blocks
  - KML heading in each of its possible places
  - CSV profiles with each direction-type preset
- **Grid**: cell maths at the equator, 52°N, the antimeridian and near the poles. The viewport cell list, and the 400-cell cap.
- **Template renderer**: every placeholder; the rules for dropping missing pieces; spoken vs display units; the order overrides apply in.
- **AdaptiveLocationController** (fake clock + fake FLP):
  - 0 and 9.9 mph → 1 Hz, and 10 mph → 1 Hz
  - 30 mph → 5.5 Hz, rounded to step 6 (5.5 rounds half up to 6)
  - 50 and 80 mph → 10 Hz
  - ±1 mph jitter at a step boundary → no re-request within 2 s
  - a steady new step for 2 s → exactly one re-request
  - the actual-rate drop rule
- **PoiAlertEngine**, with synthetic tracks built by a track generator:
  - straight approach at 30 and 70 mph (trigger distance in TIME and FIXED modes)
  - passing the POI and then coming back on a second pass (dedup clears)
  - stages: 1st, 2nd and passing fire in order at their distances in FIXED and TIME modes. With the 2nd turned off, 1st → passing. With sound off, no passing alert. With sound on, the passing alert always fires, even when the 3 s rate limit is active.
  - a missed passing: a GPS jump from 120 m ahead to 30 m behind still fires PASSING once
  - several thresholds crossed in one fix → only the closest stage is spoken
  - validation: a 2nd distance below 142 m is rejected; TIME mode shrinking the gap holds the 2nd distance down
  - opposite direction against ONE_WAY (no alert), BOTH (alert), ANY (alert)
  - a parallel road 60 m away with a route (dropped) and without a route (lateral offset check)
  - a bend within 150 m (the wider 45° check)
  - an average-speed zone start → overspeed → end, and the 30 km timeout
  - heading lost below 2 m/s
- **PoiMarkerLayer diff** (fake map): the first fix adds N markers; a fix with no change makes no map calls; one POI's emphasis changing makes exactly one `setIcon`/`setZIndex`; leaving the viewport removes the marker and returns it to the pool; a burst of fixes faster than the redraws only draws the latest.
- **Google Maps importers**: fixture files covering:
  - MyActivity.json: searched-for, directions and viewed entries, plus URLs with and without coordinates
  - Saved Places.json (GeoJSON)
  - Timeline.json visit segments
  - share links: a short link (mocked redirect), `@lat,lon`, `!3d!4d` and `place_id`
- **PlaceRepository**: duplicate merging by placeId and by distance with a similar name; HOME/FAV winning over RECENT; tombstones blocking a re-import; the 200 limit; undo after delete.
- **ContactsSource**: a fake contacts provider (several addresses, custom labels, no address); name matching in search; the geocode cache hit, miss and 90-day expiry; choosing between several matches.
- **SpeedPanel**: unit conversion, rounding and steadying; the normal/amber/red thresholds; the fallback to a POI limit with its icon; "no limit" showing speed only.
- **PgwUpdateFeed**: a fixture of the real feed → releases in order; "version 24.101:07 Oct 26" → (24, 101, 2026-10-07); a list in mixed order still finds the newest; a missing date is accepted.
- **CameraReportRepository**: one-tap capture saves the position, heading and limit with its source; undo removes the report, its marker and its audio; type guessed from the comment ("van" → Mobile, "not there" → Camera gone); review edits update or remove the My reports marker; "not there" hides the related camera; DrivingState blocks Send while connected or moving.
- **TurnByTurnMapper**: sample NavInfo → expected RoutingInfo.

### 15.2 Instrumented tests (`src/androidTest`)
- Room: staging import, atomic swap keeping the user's customisations, cleanup of leftover staging sets at startup, cascade delete.
- **Automatic update end to end** (MockWebServer): feed shows new version → PgwSyncWorker runs with **no user action**; expired cookie → logs in once and continues; wrong password → AUTH_FAILED + one notification, old data kept; logged in but no package → SUBSCRIPTION_REQUIRED; Release.txt version ≠ feed version → warning but import goes ahead; during guidance → staging done, swap held until guidance ends.
- PgwFeedCheckWorker against **MockWebServer**: 304 → nothing queued; 200 with the same version → nothing queued; 200 with a newer version → PgwSyncWorker queued exactly once; an unparseable title → hash fallback; feed down → the 14-day safety net still queues a sync.
- PgwReportSubmitter against **MockWebServer**: form fields picked out → POST body mapped correctly for each report type; logged-out → login and retry; captcha page → FAILED with the browser fallback offered.
- PgwClient against **MockWebServer**: login success, wrong password, changed layout, captcha page, and download streaming.
- NavTts audio focus: a fake media player holding focus gets `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`, not a full `LOSS`, and focus is abandoned after `onDone`.
- Notification: the built notification has CATEGORY_NAVIGATION and a CarAppExtender with the expected fields.

### 15.3 DHU end-to-end checklist

> The full procedure is in [TESTING.md](TESTING.md). **Bench simulation:** while the debug **Simulate** drive is running, `AdaptiveLocationSource` ignores the phone's real GPS and takes positions from the Navigation SDK's `RoadSnappedLocationProvider` (the simulated car). So alerts, the GPS rate and the speed panel can all be tested at a desk. Free-drive tests need a moving phone GPS: use the emulator's route playback or a mock-location app.
Setup: phone with Android Auto developer mode → "Start head unit server"; `adb forward tcp:5277 tcp:5277`; `desktop-head-unit` (with `--config` set to a split-screen 1920×720 profile and a portrait profile).

1. Launch the app on the DHU: the map appears and the T&Cs were already accepted on the phone.
2. Start media (Spotify or the DHU's media sample). **Check**: split screen with the media card, and the car marker is not under the card.
3. Import a fixture POI set with known POIs along a test route.
4. Search for a destination → results → preview with 2 or more alternatives → Go. **Check**: turn card, lanes, ETA, and the rail shows the next turn when media is full screen.
5. Run the Simulator along the route (`simulateLocationsAlongExistingRoute`). **Check** at each POI:
   - the marker is shown
   - `showAlert` fires while our app is in the foreground
   - the nav heads-up fires while media is full screen
   - TTS ducks the music, which resumes at full volume afterwards
   - an alert waiting behind SDK voice plays after the prompt finishes
6. **Check**: a ONE_WAY POI facing the other way does not alert, and a POI on a parallel road does not alert.
7. Average-speed zone: enter, overspeed, leave.
8. **Check**: markers update on every fix. The ACTIVE marker is emphasised during its alert, POIs ahead turn full colour, and passed POIs fade. The diagnostics overlay shows the time per redraw and how many markers changed.
9. The diagnostics overlay shows the requested Hz moving through 1 → 10 as simulated speed passes 10 → 50 mph.
10. Contacts: search for a contact by name, pick their Home address, check the route preview. The speed panel shows the simulated speed, Google's limit in both free-drive and guidance, and turns amber/red when the simulator goes over the limit.
11. Recents: import a Takeout fixture → the Google-sourced recents appear in Search and Recents. Delete one from the car screen → it's gone, and Undo brings it back. Delete it again, then re-import → it doesn't come back. Share a place from Google Maps on the phone → it appears at the top of the recents.
12. `adb shell am start -a android.intent.action.VIEW -d "geo:0,0?q=Leeds"` while connected → route preview opens.
13. Report: while the simulator is driving, tap Report → Mobile camera → 30. Check the Undo alert, that the report's position matches where Report was tapped, and that a "My reports" marker appears and alerts on a second pass. Use the Report action on a camera alert → Not there → that camera's alerts stop. After disconnecting, the phone notification lists the drafts.
14. Stop navigation from the system UI → the foreground service stops if no car session is connected.

### 15.4 Real-world
- A drive past known PGW cameras in both directions, to confirm the meaning of DIRECTION (§5.2).
- A battery comparison drive (§14).

---

## 16. Open questions and risks

| # | Item | Risk / question | Plan |
|---|---|---|---|
| 1 | Navigation SDK access | Access needs approval from Google; billed per trip | Apply early. Phases 1–2 can use the free Navigation SDK trial/dev quota if granted. |
| 2 | Navigation SDK + custom TTS | SDK voice and ours sharing the guidance stream | Queue our speech behind SDK playback (§12.3); confirm on a real car. |
| 3 | Our POI alerts in the turn card | Android Auto has no "custom alert" field in RoutingInfo | Use car `Alert` + nav heads-up; cluster through the trip cue (best effort). |
| 4 | ~~PGW DIRECTION meaning~~ **Resolved** | PGW's form labels it "Heading / 'Direction of Travel'" | Used as direction of travel (§5.2) |
| 4a | PGW data licence | Mostly covered: each user signs in with their own subscription and gets their own watermarked copy, with no redistribution (§6.4). Remaining doubt: the "incorporated in any application" clause | Low priority for personal use; email PGW before sharing the app with others |
| 5 | iGO TYPE codes (mostly resolved) | Real file has 1, 3, 4, 5, 11; 11 is a best guess (RedSpeed); SPECS start/end not marked | Confirm 11 using package 288 once; SPECS zones worked out from chains of points (§11 step 8); unknown codes become "Type N". |
| 6 | PGW automation | Terms of use; site changes; captcha | Everything in `PgwConfig`; manual import fallback; switch to turn it off. |
| 7 | 10 Hz GPS on phones | Many chips cap below 10 Hz | Measure the actual rate; lower the request to match (§10.4). |
| 8 | Background location | Android 14+ foreground-service rules; OEM battery killers | `foregroundServiceType=location`, started while the car UI is visible; onboarding asks to ignore battery optimisation. |
| 9 | Default nav app selection | Android Auto UI for choosing the default varies by version | Document the steps; handle `ACTION_NAVIGATE` either way. |
| 10 | Sideload restrictions | Android Auto may hide unknown-source apps without developer mode | Documented in onboarding; switch to Play internal testing later if needed. |
| 11 | Car-app API level on older head units | `Alert` needs API ≥ 5 | Fall back to the nav heads-up notification on lower levels. |
| 12 | Places API cost | Autocomplete is billed per session | Use session tokens, a 300 ms debounce, and a minimum of 3 characters. |
| 13 | Google Maps recents | No live API; Takeout and Timeline are manual; Data Portability needs an OAuth client, test-user setup and the EEA/UK | Our own recents and Maps shares are always current; imports fill in the history; Data Portability sync is optional (§8.2) |
| 14 | Deleting in Google Maps | No write API | Tombstones locally; link out to My Activity |
| 15 | People-finder privacy and consent | Tracking vulnerable people is sensitive; misuse is possible | Only entities you explicitly choose; the HA user's own permissions apply; nothing stored or logged beyond the live session; consent notice (§17.6) |
| 16 | Stale or poor location from the target | Phone off, no signal, battery saver, coarse fixes | Freshness and accuracy shown at all times; a request-location-update push; the "last known" state is made clear (§17.4) |
| 17 | HA reachable from the car | Home internet down or no remote access set up | Cloud/remote URL first, then a local URL as fallback; clear error on the car screen |
| 18 | Speed limit on Auto | SDK speedometer may not show on `NavigationViewForAuto` or in free-drive; the limit number may not be exposed | Check in phase 2; fall back to our own `SpeedPanel` (§8.4) |
| 19 | Submitting reports to PGW | Fields now known (§8.5); still open: the camera-ID lookup endpoint, the success text, and whether automated sending is allowed; it may change, have a captcha, or terms may not allow automated sending | Fields go in `PgwConfig` in phase 1; review-first by default; "Open in browser" fallback (§8.5) |
| 20 | Electroverse data access | No public API; the web map's private GraphQL is behind bot protection | Don't use it. Ask Octopus for partner or API access; use Places + Open Charge Map, plus a hand-off to the Electroverse app, in the meantime (§18) |
| 21 | Live incident reporting on Android Auto (crashes, congestion, police, roadworks…) | Google Maps has it, but the Navigation SDK doesn't offer it to apps on Android Auto: "real-time disruptions are not available on Android Auto". As of SDK 8.0.0 the APIs exist on `NavigationView` only, and Google fixed the panel "appearing in Android Auto projections" in Apr 2025. Open request: googlemaps/flutter-navigation-sdk#726. | **Watched.** `IncidentReporting.kt` looks the APIs up by reflection on `NavigationViewForAuto`. If a future SDK adds them, Google's report button appears on the car map, plus "Report to Google Maps" in More, with no code change. A **quarterly GitHub Actions workflow** (`.github/workflows/nav-sdk-feature-watch.yml`, 1 Jan/Apr/Jul/Oct) runs `tools/check-nav-sdk-features.sh --latest` and opens an issue when Android Auto gains the APIs. It can also be run by hand from the Actions tab, or locally. |

---

## 17. Home Assistant integration: people finder

> **Purpose**: to quickly find and drive to a person who may need help, such as a child who hasn't come home, or a relative living with dementia who has wandered off. The app shows where Home Assistant says they are and gives turn-by-turn directions that keep updating as they move.

### 17.1 Data source
- Home Assistant `person.<name>` entities. Their `latitude`, `longitude`, `gps_accuracy`, `source` and `last_updated` come from the device trackers linked to the person: the HA Companion app, Life360, Google/Apple Find-My-type trackers or GPS tags.
- Raw `device_tracker.<id>` entities are also supported. That covers dedicated GPS trackers with no `person` set up, such as dementia wearables that report into HA.
- Extra details, when available:
  - battery level (`sensor.<device>_battery_level`, linked through the device registry)
  - the HA zone they're in (`state`: home / not_home / zone name)
  - speed and course, if the tracker reports them
- **Several HA servers** are supported (your own plus a relative's), each with its own token. "Other Home Assistant accounts" in the request means people on any server you've added.

### 17.2 Connection and authentication
- **v1: long-lived access token.** You create it in HA (Profile → Security), ideally for a dedicated HA user with access only to what's needed, and paste it into the phone app. It's stored with the same Keystore encryption as PGW credentials (§6.1).
- **Later: HA OAuth login** (an authorisation code flow with a `client_id` URL page we host), so no token has to be pasted. This is deferred because it needs a hosted client page.
- URLs: an external URL (Nabu Casa `*.ui.nabu.casa`, or your own HTTPS domain), plus an optional internal URL used when on home Wi-Fi. Self-signed certificates are allowed only through a "trust this certificate" fingerprint pin.
- `HaClient`:
  - **REST**: `GET /api/states/<entity>` for the first load. `POST /api/services/notify/mobile_app_<device>` for location requests (§17.4).
  - **WebSocket** `/api/websocket`: `auth`, then `subscribe_entities` filtered to the chosen entity ids, which gives pushed updates with no polling. It reconnects with backoff (1 s → 60 s) and pings every 30 s.
  - The WebSocket is open **only while the People screen is showing or you're navigating to a person**. Otherwise it's closed, which keeps it off the battery budget.

### 17.3 Car UI
- **People screen** (`PlaceListNavigationTemplate`):
  - one row per tracked person: name or photo icon, distance, zone / "Not home", **"updated N min ago"**, ±accuracy, battery %
  - live markers on the map, which move as updates come in
  - shown in order of distance
  - rows whose location is more than 15 min old get a "Last known" label; more than 60 min old, a red "Location may be out of date" label
- Tapping a row opens **PersonDetailScreen** (`MapWithContentTemplate`), with these actions:
  - **Find**: navigate, following the person as they move
  - **Request update**: a push to their device (§17.4)
  - **Navigate to last known**: a normal, fixed destination
- During Find, the NavigatingScreen's destination row shows "→ Mum · updated 40 s ago". A moving person-marker sits on the route, and the heading shows if their tracker reports course.
- Voice prompts during Find (on the guidance channel, §12.3):
  - "Mum has moved, updating route", when a re-route happens
  - "Mum is 200 yards ahead", within 200 yd
  - "Mum's location is out of date", when the location goes stale
- Arrival: once within the person's GPS accuracy plus 25 m, the Arrival screen says "You're near Mum. Last reported 1 min ago, accurate to ±10 m". It stays on the screen with a "Keep following" option, because they may still be moving.

### 17.4 Navigating to a moving target (`LiveTargetController`)
- When you start Find, `Navigator.setDestinations` is called with the person's current position.
- **Re-route** when:
  - a new update has moved the target more than **max(150 m, accuracy)** from the current destination, **and** it's been at least **30 s** since the last re-route
  - or, more urgently: the target moved more than 500 m (no wait)
- A re-route keeps the current guidance without a preview: `setDestinations` and `startGuidance` are called again. Their location jitter doesn't cause re-routes, because of the accuracy threshold.
- **Freshness**: on start and then every 2 min while in Find, if `last_updated` is more than 2 min old **and** the tracker is a HA Companion device, the app sends `notify.mobile_app_<device>` with `message: "request_location_update"`. That's the Companion app's documented command for asking the phone to report its location now. It's rate-limited to once every 2 min per device, because iOS limits background updates too.
  - If that device supports it (Android Companion), an optional setting can also send `command_high_accuracy_mode` (turn on) while you're finding them, and turn it off again when Find ends.
  - Both need a HA user allowed to call notify services, and the options are off by default.
- **Accuracy**: if the reported accuracy is more than 200 m, the route still goes to the reported point, and the car screen shows "approximate location (±N m)".
- **Ending Find**: you stop, you arrive and then dismiss, or the person goes stale for more than 60 min. In the stale case the app asks whether to keep going to the last known location. Ending Find closes the WebSocket and stops the location-update requests.

### 17.5 Phone app
- **Home Assistant** screen:
  - add a server (name, external/internal URL, token, **Test connection**)
  - pick the people/trackers to show from that server's `person.*` / `device_tracker.*` entities (unticked by default)
  - per person: display name, icon or photo (the HA `entity_picture` by default), and which device to send location requests to
  - switches: location requests, high-accuracy mode
- The phone app also gets a simple **"Find…"** entry that sends the person to the car, the same way as "Send to car" (§9.1).

### 17.6 Privacy, safety and consent
- Only the entities you **explicitly choose** are read. The HA user and token you set up limit what can be read, and HA's own permissions apply.
- Locations are **kept in memory only** for the live session. They aren't written to Room, logs or crash reports; diagnostics show only "update received" and how old it is. No location data leaves the phone except in the requests to your own HA server.
- First-time use shows a consent notice: the people being located should be aware and consent, or a carer or guardian should decide this for them. The app is a help to families and **not a replacement for emergency services**. The People screen shows "In an emergency call 999/112".
- People-finder use isn't kept in recents unless you choose to save the place.

### 17.7 Tests
- Unit tests:
  - HA state JSON → `TrackedPerson` parsing, including a missing GPS fix, a zone-only state and the `device_tracker` source
  - freshness labels
  - `LiveTargetController` re-route rules (small jitter → no re-route; 200 m move after 30 s → re-route; 600 m move → immediate re-route; accuracy threshold)
  - request-update rate limiting
- Instrumented: `HaClient` against a MockWebServer WebSocket (auth OK, auth invalid, subscribe and updates, disconnect then reconnect with backoff).
- DHU: a scripted fake HA server moves a "person" along a path. Check the People list, Find, the re-route announcements, the stale warning and arrival while still moving.

---

## 18. EV chargers (Octopus Electroverse and others)

> **Goal**: find EV chargers near you, along the route or near the destination, filtered for your car, see whether they're free, then navigate to one, all from the car screen.

### 18.1 Electroverse: what was found (2026-10-11)
- **There's no public developer API.** Octopus Electroverse **licenses** its network and data to partners (OEM navigation, fleets, apps), now including Chargetrip routing. Access is a commercial or partner agreement.
- The electroverse.com web map uses a **private GraphQL proxy** (`/api/proxy/graphql`) behind a **bot-protection challenge script**. **We will not use it**: it's an internal, protected endpoint, and calling it from the app would mean getting around their protection and breaking their terms.
- **What we can do legitimately**:
  1. **Ask Octopus for partner or developer access**, for personal or non-commercial use of the Electroverse API. If they grant it, `ElectroverseProvider` is turned on (§18.3), with their API key and their terms.
  2. **Hand off to the Electroverse app** to start and pay for a charge. Our app navigates to the charger, and a **"Charge with Electroverse"** action opens the Electroverse app on the phone. It uses a deep link if Octopus documents one; otherwise it just launches the app, with the charger's name and address on the clipboard. This needs no API.
  3. Use **other legal charger data** for finding chargers in the meantime (§18.2).

### 18.2 Charger data providers
One `ChargerProvider` interface lets sources be added or turned off. Results are merged and duplicates removed (same site within 40 m with a similar name/operator, keeping the richest record).

| Provider | Data | Access | Notes |
|---|---|---|---|
| **Google Places API (New)** (default) | Nearby/Text Search with `includedTypes=["electric_vehicle_charging_station"]`. `evChargeOptions`: connector count, and per connector type the max kW, count, and (for some networks) **live available / out of service counts** with an update time | Our existing Maps Platform key | `evChargeOptions` is billed at the **Enterprise + Atmosphere** SKU, so requests use a narrow field mask, a 5-min cache and debouncing |
| **Open Charge Map** | Free, community data: sites, connectors, kW, operator, access notes | Free API key (registered by the user) | Open-data licence: **attribution shown** in the app ("Data © Open Charge Map contributors"). No live status. |
| **Electroverse** (turned off until access is granted) | Their network, compatibility with the Electroverse card, pricing and live status | **Partner or API agreement with Octopus** | Implemented only against official docs; the stub returns nothing until it's set up |

- **Vehicle profile** (phone app): connector types (CCS2, Type 2, CHAdeMO), max DC kW, and optional battery size. It filters and ranks the results, for example hiding CHAdeMO-only sites from a CCS2 car.
- **Ranking**: available now > compatible > faster (≥ 50 kW first when you ask for rapid) > closer. Along a route, it's ranked by **detour time** (via the Navigation SDK's route ETA) rather than straight-line distance.

### 18.3 Car UI
- **Action**: "Chargers" sits in the **More** menu and in Search's empty state. Typing or saying "charger", "EV charger" or "rapid charger" in Search shows charger results above places.
- **ChargersScreen** (`PlaceListNavigationTemplate`):
  - **Tabs**: Nearby · Along route (only while navigating) · Near destination.
  - **Rows**: name/operator, distance or detour ("+4 min"), fastest compatible kW, "3 of 6 available" (with how old the status is), and the connector icons.
  - **Map**: pins coloured by speed, matching the Electroverse colours: slow ≤ 7 kW, fast 8–49, rapid 50–149, ultra-rapid 150+.
  - **Filter chips** (a small `GridTemplate`): Rapid only · Available now · All connectors.
- **ChargerDetailScreen** (`MapWithContentTemplate`): connectors with kW and status, operator, and opening and access notes. Actions:
  - **Navigate**: normal guidance to the site's entrance coordinates.
  - **Add as stop**: a waypoint before the destination, using `Navigator.setDestinations` with several waypoints.
  - **Charge with Electroverse**: the hand-off from §18.1.
- **Low-battery suggestion**: not in v1. Android Auto doesn't give a projection app the car's battery level, so it would need the car's own API or a manual entry.
- **Chargers as a map layer** (optional switch in POI layers): live charger pins inside the viewport, refreshed when the map stops moving, at most 50, at zoom ≥ 12. Unlike imported POIs, these aren't stored in Room. They're kept in an in-memory cache for 5 min.

### 18.4 Phone app
- **EV** settings: the vehicle profile, provider switches, the Open Charge Map API key, and an Electroverse access-key field (hidden until it's turned on). There's also "Request Electroverse API access", which opens Octopus's partner contact page.

### 18.5 Tests
- **Unit**: Places `evChargeOptions` JSON → `Charger` (fixtures based on the documented shape); Open Charge Map JSON → `Charger`; duplicate merging between providers; filtering by vehicle profile; ranking (available, compatible, kW, detour); speed-band colours.
- **Instrumented**: both providers against MockWebServer, including the Places field mask and cache.
- **DHU**: search "rapid charger" → list → detail → Navigate / Add as stop; "Charge with Electroverse" hands off to the app (if it's installed).

---

## Appendix A — Build phases (after approval)
1. Skeleton, toolchain and manifest; install the DHU. (The iGO format was already checked against PGW v24.101 on 2026-10-11, §5.2; type 11 still to confirm using package 288.)
2. Car map (AutoMapRenderer, padding, gestures).
3. Search → preview → guidance → trip publishing.
4. Replacing Google Maps: intents, foreground service, audio coexistence.
5. POI data model, importers, CSV wizard, PGW sync.
6. POI overlay and the layers screen.
7. Adaptive location, the alert engine and dispatch.
8. Phone app screens.
9. Home Assistant people finder (§17).
10. EV chargers (§18): Places + Open Charge Map providers, car screens, Electroverse hand-off; Electroverse API once access is granted.
11. Power measurement and tuning; real drive.


## Appendix B — Implementation status (first test build, 2026-10-11)

**In the test APK** (`app/build/outputs/apk/debug/app-debug.apk`):
- **Car**:
  - Navigation SDK map on the car surface, with padding from the visible/stable areas, pan/zoom/recenter, traffic and follow mode.
  - Search (Places autocomplete + text search, recents in the empty state), route preview with ETA and traffic, and guidance.
  - Turn-by-turn feed → `RoutingInfo`, `TravelEstimate` and `Trip` (cluster and rail), stop and arrival.
  - Debug **Simulate** button and DHU auto-drive.
- **POIs**:
  - Room model with staging and atomic swap.
  - Importers for iGO TXT (checked against PGW v24.101), KML, GPX, OV2 and CSV (column mapping), with zip support.
  - Per-set icon (built-in or from a file), three templates with a live preview and Test speak, and 1st/2nd stage settings with validation.
  - PGW sets get type-based icons and SPECS zone points.
- **Alerts**:
  - Adaptive 1–10 Hz GPS, the 5×5 cell cache, and the full `AlertEngine`: 3 stages including the compulsory 100 yd passing alert, direction and parallel-road checks, missed-passing safety net, average-speed chains, overspeed.
  - Car `Alert` with Dismiss/Mute, a navigation heads-up notification when the app is in the background, and TTS on the guidance channel with ducking and queueing behind SDK voice.
- **Car speed and GPS** (§10.6–10.7): the speed panel uses the car's display speed, and the alert engine and GPS rate use its raw speed. Car GPS is the primary position feed for alerts, with the phone as a 1 Hz backup. Both fall back to the phone and can be switched off in Settings.
- **Map extras**: markers redrawn as a diff on every fix (ACTIVE/AHEAD/OTHER), and the speed panel, which shows Google's limit worked out from `SpeedingListener` while speeding, or a camera limit.
- **PGW**:
  - Encrypted login, the daily RSS conditional GET, an automatic download on a new release, a held swap during guidance, notifications, Sync now / Force re-download.
  - Report menu (type → speed → Undo), "My reports" set (mobiles expire after 24 h), "Not there" hide, and phone review with Send / Discard / Open in browser.
- **Phone**: Setup (Maps key, permissions, Navigation terms, Android Auto help), POI sets, Cameras (PGW), Reports, Settings (units, voice, chime, overspeed %, speed panel, GPS curve).
- **Tests**: 61 JVM unit tests in `:core`, plus the optional real-file check against a local PGW download.

**Not yet built** (later phases):
- Home Assistant people finder (§17), contacts' addresses (§8.3), Google Maps history imports and the share receiver (§8.2), EV chargers (§18).
- Phone "Send to car" share target, a foreground service for background free-drive, and per-category overrides in the UI.
- Saved CSV profiles, the RadarDroid/Nokia importers, `ZoneChainer` pre-processing, PGW camera-ID lookup and the region picker.
- Instrumented tests, a Baseline Profile and power measurement.
