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
- **Uses as little power as it can** while always reading the GPS at 10 Hz (the user's choice, §10.2).
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
| Build stamp | `BuildConfig.GIT_COMMIT` = `git rev-parse --short=9 HEAD`, plus `-dirty` when the working tree has changes, set at configure time with `providers.exec` (configuration-cache safe); shown as "Build <commit> (debug)" in Settings → About (owner, 2026-10-12) | So a phone can be matched to a commit |
| Licence | **Apache License 2.0** (`LICENSE`, `NOTICE`, SPDX header in each source file) | Open-source licence chosen by the owner. PGW data is never part of the project (§6.4). |
| Language | Kotlin only | Car App Library and Navigation SDK are JVM-only. A cross-platform engine adds power drain. |
| Map/routing/search | Google Navigation SDK + Places API (New) | Closest to the real Google Maps; supports Android Auto directly |
| POI data | Generic framework. **PGW is the only automatic source** (RSS-triggered sync). **Free and other POI files are loaded by hand only**, with no automatic fetching or URL subscriptions. | Not limited to speed cameras; you decide when third-party data changes |
| PGW format | iGO TXT as the main format | Carries direction type and bearing |
| Distribution | Sideload (ADB + Android Auto "Unknown sources") | Personal use |
| Phone UI | Settings, import and editing only | All driving use happens on the car screen |
| People finder | Home Assistant REST + WebSocket API, `person.*` / `device_tracker.*` entities, long-lived access token (v1) | HA already brings together the family's phone, tracker-tag and companion-app locations; no new tracking setup needed |
| GPS rate | Always 10 Hz (§10.2) | Alerts fire at accurate distances and the map is always fresh; costs more battery when slow |
| **Camera direction (hard rule)** | A camera alerts only when it faces your direction of travel (§11 step 3). Never dropped, loosened or defaulted to "any direction" when the data gives one | Alerts for the other carriageway are wrong and noisy; set by the owner, 2026-10-12 |
| **Car-map buttons (hard rule)** | All our on-screen buttons on the car map are on the **right-hand side only** (§8) | One control column on the right; set by the owner, 2026-10-12 |
| Tolls | **Avoided by default** (setting, §8.3a) | The owner's choice |
| EV routing | Optional **energy-efficient routes** from Google's Routes API (eco-friendly route for an electric vehicle), followed by the Navigation SDK through a route token (§8.3a); falls back to the fastest route | The Navigation SDK has no energy option of its own |
| Speed limits | Google's when it gives one, else a camera's, else OpenStreetMap's from **one bulk download** (Geofabrik, the whole UK) kept on the phone (§8.4–8.4a). Never live OSM lookups | Google only gives apps the limit while you're speeding |
| Memory | Big data is streamed from disk, never held whole in RAM: zip entries are read when used (§5.2), the speed-limit download spills to disk and lookups load only the tiles around the car (§8.4a) | The phone also runs the map, Android Auto and media |

### 1.4 Prerequisites
1. Google Cloud project with billing; **Navigation SDK** (access approval required) and **Places API (New)** enabled. API key restricted to the app package and signing SHA-1. **Each user gets their own key in the phone app (Settings tab, setup sections) after installing: either the app creates it with the Google account on the phone (§13.1), or they paste one.** It's stored encrypted with the Keystore and applied at run time with `NavigationApi.setApiKey()` and `Places.initializeWithNewPlacesApiEnabled()`. **There is never a built-in or build-time key, and no fallback of any kind** (owner's rule): every user's Maps, Navigation and Places use is billed to their own Google account, never the maintainer's. The app works only once the user has their own key.
   - **Where it lives:** `SettingsStore` keeps it as a Keystore-encrypted secret; `mapsApiKey()` returns it, or `null` when it's missing or blank. Nothing else is consulted: there's no `com.google.android.geo.API_KEY` in the manifest, no `local.properties` entry, and the Secrets Gradle plugin (which copied every `local.properties` entry into `BuildConfig`) has been removed.
   - **No SDK call before the key:** without a manifest key, *any* Navigation SDK call made before `NavigationApi.setApiKey()` crashes the app on a background thread ("API key not found. Check that <meta-data android:name="com.google.android.geo.API_KEY" …/> is in the <application> element"). So every SDK entry point goes through `NavigatorHolder.applyApiKey()` first, and does nothing when it returns false:
     | Call | Where | Guard |
     |---|---|---|
     | `NavigationApi.areTermsAccepted` | phone Settings, `termsAccepted()` | `NavigatorHolder.sdkTermsAccepted()` = `applyApiKey() && areTermsAccepted()`; the app's own record of acceptance is checked first |
     | `NavigationApi.showTermsAndConditionsDialog` | phone Settings | button disabled until a key is saved ("Add your Google Maps API key above first") |
     | `NavigationApi.getNavigator` / `getNavigatorNoToS` | `ensureNavigator()` | `applyApiKey()` first; no key → `NavUnavailable.NO_API_KEY` |
     | `NavigationViewForAuto` (car map) | `MapSurface` | created only after `applyApiKey()` succeeds (§8) |
     | Places client | `placesClient()` | `applyApiKey()` first |
   - **Changing the key:** saving or removing it calls `NavigatorHolder.keyChanged()`, which forgets the applied key and cleans up the Navigator, so the next use re-applies the new one.
   - **Who pays:** Maps use is billed to the Cloud project that owns the key. Each user's key is in their own project (§13.1), so the maintainer is never billed for anyone else. The app's own registration project (§13.3) has no billing at all.
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
│                                └─────────┬──────────┘           │ 10 Hz)             │   │
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
    SettingsStore.kt             DataStore (units, voice, thresholds, map and PGW options)
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
  - Fixes are handled one at a time with `conflate()` (a fix arriving meanwhile replaces any waiting one), never `collectLatest`: at 10 Hz the next fix would cancel a reload half-way. The cell is marked loaded only after the new POIs are in, so an interrupted reload is retried on the next fix.
  - Per fix there's no DataStore read (the latest settings are kept by their collector) and no list copy: the engine's candidate list is built once per reload and only rebuilt per fix while a set is muted or a report is being kept quiet.

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

**No set name is asked for on import.** Names come from what's loaded: a single file is named after the file, and a **zip becomes one set per POI file inside it (never merged)**, each named after its file and using the zip's matching `.bmp`/`.png` as its icon. Re-importing a zip updates its sets, matched by file name. Sets can be renamed in the set editor.

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

### 5.2 iGO TXT (main format, PGW)

> **iGO option: package 288 "UK – Consolidated by type"** (with pending mobiles). Each file becomes **its own set**, and sets are matched by file on every sync, so per-set icon and alert choices are kept:
>
> | File | Set | Rows (v24.101) | Type codes |
> |---|---|---|---|
> | `pocketgps_uk_gatso.txt` | PGW fixed cameras | 4,248 | 1 (3,932), **11 (316)** |
> | `pocketgps_uk_mobile.txt` | PGW mobile camera sites | 8,888 | 5 |
> | `pocketgps_uk_redlight.txt` | PGW red light cameras | 778 | 3 |
> | `pocketgps_uk_specs.txt` | PGW average speed cameras (SPECS) | 3,396 | 4 (zone points) |
> | `pocketgps_uk_pmobile.txt` | PGW pending mobile sites (unverified), alerts off | 2,516 | 5 |
>
> - The total, 19,826, matches the single-file package 61.
> - **Type 11 is only in the fixed-camera file**, so it's a fixed-camera subtype, shown as "Fixed camera (type 11)". It isn't red light + speed as first guessed.
> - PGW sets from an older layout are removed on import.
> - `PgwSyncWorker.IMPORT_LAYOUT` forces a re-download when the import logic changes.
>
> The single-file notes below still describe the file format, which is identical in every file.
 — **checked against real download v24.101 (07 Oct 26)**
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
- **Reading the zip**: PGW's zips must be read through the zip's **central directory** (`java.util.zip.ZipFile`, `ZipEntries.kt`). Java's streaming `ZipInputStream` stops after the first entry (`_Need_Help.htm`) and never sees the camera files. Found on the first real sync, 2026-10-11.
- **Never all in memory**: `ZipEntries.read(file)` returns a closeable map whose values are read from the zip on disk **when used** and not kept, so icons, readmes and other camera files never sit in RAM together. A nested zip is streamed to a temp file. A file picked on the phone is streamed to a temp file first (not read into memory), and the import dialog's list of files in a zip comes from the zip's directory alone (`ZipEntries.names`), without inflating anything. Each entry is capped at 200 MB inflated.
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
| 11 | 316 | Fixed camera (type 11) | **Confirmed fixed**: package 288 puts all of them in `pocketgps_uk_gatso.txt` (fixed cameras). Exact subtype not stated by PGW. |

- Codes not in the table become a category named "Type N".
- **pMobile set**: imported as a **separate PoiSet** "PGW pending mobile sites", alerts **off by default** and a lighter icon. PGW says these sites "have not been checked for accuracy", so they could otherwise cause false alerts. They can be turned on in the POI sets screen.
- **Fixtures**: PGW's licence forbids copying the data. Each download is also **watermarked to the member**. So **no real PGW data goes into the repo or tests**. The fixtures are made-up files that copy the structure exactly: the header, empty fields, Direction=360, the copyright last row, and pMobile.

### 5.2a TomTom OV2 from PGW (default download)
The user chooses **TomTom OV2** (default) or **iGO** on the Cameras tab (`PgwDownload`). OV2 is TomTom OV2 → "UK – Speed Zoned Complete", **package 1**, `System=TomTom OV2`. The other OV2 package, "consolidated by type", is package 2.

- **Files**: one per type and speed, e.g. `pocketgps_uk_gatso_30.ov2`, `…_gatso_var.ov2`, `…_gatso_tba.ov2`, `…_redlight.ov2`, `…_specs_50.ov2`, `…_pmobile_70.ov2`. There's also a `.bmp` icon per file, which is ignored.
- **Every file becomes its own set. Files are never merged** (owner's rule). For example `gatso_30` → "PGW fixed cameras · 30 mph", `specs_var` → "PGW average speed cameras (SPECS) · variable limit", `redlight` → "PGW red light cameras".
  - v24.101 has **33 files / 33 sets**.
  - Each set keeps its own icon, alerts and sound across syncs, matched by file stem.
- **Icons supplied in the download are used.** Each file's `pocketgps_uk_<stem>_X2.bmp` (44 px, otherwise the 22 px `.bmp`) becomes that set's icon (`user:pgw_<stem>`), and category icons don't override it.
  - A sync refreshes a set's icon unless the user chose their own.
  - The `_X2` icons are **© GerryC, 2007**: free for non-commercial use, with credit and the licence included. The licence text is saved next to the icons, and the credit appears in the set editor and Settings → About ("Show icon licence").
- **Plain OV2 has no direction field, but PGW encodes it in each entry's name**: `<compass>-[R-][t]<TYPE>:<pgwId>[@<mph>]`, e.g. `E-R-MONITRON:3209@30`.
  - **compass** (N, NE, … NW) is the direction of travel. It's within 22.5° of iGO's exact degrees for 98% of cameras. It's missing for 49 entries, mostly pending mobiles, which are treated as ANY.
  - **`R-`** means reversible (BOTH). It matches iGO DirType 2 for 3,955 of 3,958 cameras.
  - **`t`** means a temporary (roadworks) camera.
  - **TYPE** is one of GATSO, MONITRON, TRUVELO, **REDSPEED** (316 entries: this is iGO type 11, so red light + speed), REDLIGHT, SPECS, MOBILE, pMOBILE.
  - **pgwId** is stored as `poi.sourceId` and sent as `idCam` when reporting a known camera (Action 2/3/4, §8.5).
  - **@mph**: `@0` means variable; a missing speed means it's still to be confirmed.
  - The copyright watermark entry in each file is skipped.
- Verified on v24.101: **19,826 cameras**, identical to iGO.
- The parser is `PgwOv2.kt` in `:core`, with tests and an optional `PGW_OV2_ZIP` real-file check.

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
- Zone enter/leave/over have their own templates: "Entering average speed zone, {speed} limit", "Leaving average speed zone" and "Average speed zone, {speed} limit, you are over the limit". {speed} is the zone's limit (§11 step 8).
- The display text and the spoken text come from the same template. Display uses short units ("30 mph") and speech uses full words.

---

## 8. Car UI

All screens use **NavigationTemplate-family** templates, so the map always stays on screen and split-screen with media keeps working.

| Screen | Template | Content |
|---|---|---|
| Home / free-drive | `NavigationTemplate` (no RoutingInfo) | Map, speed panel (§8.4), the next POI ahead (as a `Message` "Camera 0.6 mi") |
| Search | `SearchTemplate` | Places autocomplete (debounced 300 ms, biased to your location); favourites and recents (including Google Maps imports) when the box is empty (§8.2) |
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
| More | `ListTemplate` | Recents · POI layers · Mute alerts 30 min · Satellite map · Route options (avoid tolls, energy-efficient EV routes) · Diagnostics (debug) |

**Action strip** (up to 4): Search · **Report** · People · More. While navigating, People is replaced by Stop navigation, and People moves to More. Recents are also shown in Search's empty state, so they're still one tap away. Mute is in More ("Mute alerts for 30 min"); the alert banner on the map has no buttons (§12.1). The People action only appears when a HA server is set up. **All its actions are persistent** (`Action.FLAG_IS_PERSISTENT`), so the host never fades them out: the user wants the four top-right buttons always on screen.
**Hard rule: all our on-screen buttons are on the right-hand side only** (owner, 2026-10-12): the satellite toggle and the zoom/recenter column are anchored to the right edge (`Gravity.END`) from the moment they're added, under the action strip, and `applyPadding` only adjusts their right and top margins from the visible area. (Anchored from the left, they sat at the top-left until the host reported the visible area.) The speed panel is bottom-right; nothing of ours goes on the left.

**Map buttons**: down the right edge of the map, under the satellite toggle (top right): **zoom in · zoom out · recenter**, 56 dp dark circles 10 dp apart (`MapButtonView`), drawn on our surface and tapped through `onClick` (car API 5+). The host's map action strip keeps only `Action.PAN`, which hosts show for rotary/touchpad cars. Below car API 5 (no `onClick`) our buttons aren't drawn and the host strip has Pan · zoom in · zoom out · recenter as before.

**Visible and stable areas**: `MapPaddingController` reacts to `onVisibleAreaChanged` and `onStableAreaChanged` and turns the inset rectangles into `GoogleMap.setPadding`:
- The stable area sets the camera target, so the car marker stays steady.
- The visible area keeps markers out from under the cards.

This is what keeps the car marker and route clear of the turn card, the rail and the split-screen media panel.

**Map surface set-up** (`MapSurface`, created by `NavSession.onCreateScreen` with `applyKey = { deps.navigator().applyApiKey() }`):
- **Order of events:**
  1. The host calls `onSurfaceAvailable(container)`. `MapSurface` cancels any set-up still running and starts a new one on its own main-thread scope (`Dispatchers.Main.immediate`).
  2. The set-up waits for `applyKey()`. If there's no key it stops there: the surface stays empty and the home screen shows "Add your Google Maps API key in the phone app's Settings."
  3. `attach(container)`: releases any old map, creates a `VirtualDisplay` (`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`, the container's size and dpi) on the host's `Surface`, and a `Presentation` on that display.
  4. The `Presentation`'s content is a plain `FrameLayout` with three children, in drawing order: `NavigationViewForAuto` (match parent), the speed panel (§8.4, bottom end) and the alert banner (§12.1, bottom, centred by `applyPadding`). `NavigationViewForAuto` doesn't accept child views ("Adding child views is not supported"), so nothing is ever added to it directly.
  5. The view's `onCreate/onStart/onResume` run, the `Presentation` is shown, and `getMapAsync` turns on my-location, traffic and tilted follow, then calls `onMapReady` (markers and the camera-idle listener attach there).
- **Why wait for the key:** there is no manifest key (§1.4), and Android Auto can start the car session in a **fresh process** (after an update, a crash, or the phone killing the app) before the phone UI has ever set the key. Creating `NavigationViewForAuto` first crashes with "API key not found".
- **Tear-down:** `onSurfaceDestroyed` cancels the set-up and releases (view `onPause/onStop/onDestroy`, `Presentation.dismiss`, `VirtualDisplay.release`). `close()`, called from the session's `onDestroy`, also cancels the scope for good.
- **Follow watchdog** (`keepFollowing(lat, lon)`, called by `NavSession` on every fix):
  - **Why:** after a big jump in position (leaving a tunnel, a GPS glitch, or bench playback starting somewhere new) the SDK's map can stop following the car and stay where it was, while our speed panel and alerts carry on. Seen on the bench 2026-10-12 after a 3 km jump; the map stayed frozen until the car session restarted.
  - **Check:** only while follow mode is on (the user hasn't panned). It measures from the map camera's **target** to the car. The target isn't exactly the car (the padding and tilt put the car low on the screen), so the threshold is generous: **500 m** (`LOST_METRES`).
  - **Act**, at most every **5 s** (`REFOLLOW_INTERVAL_MS`): the first time, call `followMyLocation(TILTED)` again and log "Map is N m from the car: following again". If it's still lost at the next check, **rebuild the map** on the same surface (`attach` with the saved container) and log "Map still N m from the car: rebuilding it". The counter resets as soon as the map is within 500 m.
  - Tested on the bench: a 4 km jump logged "Map is 3771 m from the car: following again" and the map caught up within about 5 s.

**Gestures**:
- `onScroll` pans and enters pan mode, which turns camera-follow off.
- `onScale` zooms.
- `onFling` gives a short animated pan.
- `onClick` on the satellite toggle switches the map type; on the alert banner it dismisses the alert; on a marker it shows the POI's details in a short-lived `Message`.
- **Zooming keeps your zoom**: while the map follows the car, + / − (and a pinch) keep following but at the zoom you chose (`followMyLocation(TILTED, FollowMyLocationOptions.setZoomLevel(z))`, `z` between 3 and 17), so the SDK's own follow zoom can't pull it back in while you look along the route. Zoom from a panned map just zooms. The follow watchdog re-follows at your zoom too.
- Recenter turns camera-follow back on and hands the zoom back to the SDK (its automatic follow zoom).

**Traffic**: Google's live traffic layer is **always on** (the user's rule). It's turned on when the map is created and when the map type changes, and checked on every GPS fix (`keepFollowing`), so if guidance, a rebuild or the SDK ever turns it off it's back within one fix. There's no setting to turn it off.
- **Zoom cap 17** (`MapSurface.MAX_ZOOM`, `setMaxZoomPreference`): the road map (`MAP_TYPE_NORMAL`) **stops drawing traffic when zoomed in closer than 17**, while satellite/hybrid keeps it, and the follow camera's default zoom is 18, so the road map showed no traffic at all while following. Found on the bench, 2026-10-12, by stepping the zoom: 18 → none anywhere; 17.5 → none in towns (Banbury: no colour at 17.5, a red queue on Hennef Way at 17), only thin green on some motorways; 17, 16.5, 16 → traffic everywhere. It isn't the 3D buildings (turning them off changed nothing) or the SDK's map style (an empty style changed nothing). With the cap, the follow camera and the + button stop at 17, and traffic looks the same on both map types. The map **always follows at an explicit zoom** (17 until the user picks another; recenter goes back to 17): left to itself, the SDK's follow camera drew closer than the cap (it reported 17 but showed no traffic in Banbury), and with `setZoomLevel(17)` the same spot showed amber and red on Hennef Way.

**Satellite view** (`satelliteMap` setting, default off):
- A toggle sits in the **top-right corner of the map**, 16 px inside the visible area's top-right corner. The action strip is **persistent** (`Action.FLAG_IS_PERSISTENT` on all its actions), so it's always on screen and the column always sits **below** it: its top is the largest of the visible area's top, the stable area's top and 88 dp, plus 16 px. (The host sometimes reports a visible area that ignores the strip, right after the map starts; going by that alone put the toggle under the strip.) Each change is logged ("Visible area Rect(…)"). On the 1080p bench with the strips hidden the visible area is `Rect(12, 12 - 1180, 1044)`. It's a dark pill (150 × 52 dp) with the layers icon and the type a tap switches to: "Satellite" on the road map, "Map" on satellite.
- Satellite uses `GoogleMap.MAP_TYPE_HYBRID` (imagery with road names and the route); off is `MAP_TYPE_NORMAL`. Traffic, markers, the speed panel and the alert banner draw the same on both.
- The host decides where its map action strip goes (bottom right), so the toggle and the zoom/recenter column are our own views on the surface and their taps come through `onClick` (each tap is logged: "Tap at x,y"). For rotary-only cars, and in case a host doesn't pass taps through, the same switch is a **Satellite map** toggle row in More, and "Satellite map in the car" on the phone's Settings → Car screen. All three write the one setting, and the map follows the setting, so they always agree.

### 8.1 POI marker redraw (on every GPS update)
`PoiMarkerLayer` redraws on **every fix** (cheaply: the on-screen POIs are picked without allocating, a distance sort runs only when more than 200 are on screen, and each marker image is handed to the map once and reused) from `AdaptiveLocationController`, so it runs at up to the 10 Hz GPS rate. It also redraws on camera idle (the user panned or zoomed) and on `setsChanged`.

Each redraw:
1. **Pick the POIs to show**: POIs from `PoiGridCache` (in-memory, with no Room access per fix) in sets with `showOnMap`, inside the current visible bounds plus a 10% margin, and at or above each set's `minZoom`, **leaving out cameras on our road that point the other way** (2026-10-12): `AlertEngine.facingAway` lists the ONE_WAY/BOTH POIs whose direction doesn't match our heading (the §11 step 3 rule, 45°), that are on our road (on the route, or within the side allowance of our line: 40 m, 100 m for mobile sites) and straight ahead or behind (within 45°), within 3 km. These are never alerted, so showing them only misleads (found on the bench: a pending mobile site for westbound traffic shown on our eastbound road, silent as it should be). It works from the heading the engine last used, so nothing is hidden before the heading is known; cameras on other roads are still shown either way. While the user is panning, the bounds come from the camera, and a Room viewport query fills in cells outside the cache once the camera goes idle.
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
| **Google Data Portability API** (optional) | OAuth with your Google account, using the `myactivity.maps` and `maps` saved-place scopes (Home and Work: `maps.aliased_places`, built, §8.2a). A background job (weekly, Wi-Fi) runs an archive job, downloads the result, and runs the same parser as Takeout | Same as Takeout | **Yes**, but it needs a Google Cloud OAuth client set up with you as a test user. Only available in the EEA/UK/Switzerland. |

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
- Favourites can't be removed from the recents action. Removing them needs Unfavourite or the phone app.

### 8.2a Home and Work from your Google account — removed
**Decision (owner, 2026-10-12): removed completely.** The feature fetched the Home and Work set in Google Maps through Google's Data Portability API (`maps.aliased_places`, in-app Google sign-in, one-time or time-based access) and offered them as the first rows of the car's search. It was built and tested end to end, but **Google's export carries no Home or Work for the owner's account** under any plausible scope: `maps.aliased_places` gives an empty "My labeled places" file, `maps.commute_settings` only the commute mode, `maps.commute_routes` nothing — checked with direct API calls outside the app, fresh jobs, archive index complete and not truncated, while Google Maps shows both. Google's export catalogue lists a separate "Home and Work" object with no documented scope. The owner's rule stands: **Google Maps is the only acceptable source** (one Home and Work, the same in both apps), and a manual set-by-address fallback was declined, so with no working source the feature is out rather than half-working.

What was learned, kept for any future attempt: Data Portability scopes can't be added to an app's other grants (request them alone, `setOptOutIncludingGrantedScopes(true)`); a used one-time grant blocks any new consent ("Incremental auth is not allowed") until `POST …/v1/authorization:reset`; the consent page offers Only once / 30 days / 180 days, and a time-based grant allows repeated exports; Play services' `SELECT_ACCOUNT` result carries no email, so the account must be chosen with Android's own `AccountManager` chooser. The place table keeps no HOME/WORK rows (DB v8 deletes any), and `PlaceKind` is RECENT and FAVOURITE only.

### 8.3 Navigating to contacts' addresses
- **Permission**: `READ_CONTACTS` (in the manifest), **optional**. Settings → Permissions has a "Contacts (optional)" line and its own **Allow contacts** button (the main **Allow** only asks for the needed permissions). Without it the contact rows and the Contacts entry are hidden, and search works as before.
- **Source** (`ContactsSource`): `ContactsContract.CommonDataKinds.StructuredPostal` rows, read with the `ContentResolver`, each with its contact's `DISPLAY_NAME_PRIMARY`, the address type (HOME/WORK/OTHER or the custom label, via `StructuredPostal.getTypeLabel`) and `FORMATTED_ADDRESS`. Read-only; the app never changes your contacts. Addresses with a blank formatted address are skipped.
- **Matching a name**: case-insensitive; a contact matches when its display name **starts with** the query or **any word** in it does ("mum" → "Mum", "smith" → "Dave Smith"). From **2 characters** (Places needs 3). At most **3** addresses above the Places results (car lists are short) and **6** in the contacts-only search; names that start with the query first, then by name. It's a local query, so it runs on every keystroke with no delay.
- **Geocoding**: an address is turned into a location only when you pick it, never for the whole address book, using Places **Text Search** on the formatted address with a 50 km location bias.
  - **Choosing the result**: if the address has a **UK postcode** and one result's address has the same postcode (spaces and case ignored), that one is used. Otherwise, one result is used directly; several results are shown in the search list under "Which address?" to pick from; none gives "Couldn't find <name>'s address".
  - **Cache**: the chosen `(placeId, lat, lon, formatted address)` is stored in Room table **`contact_geocode`** (DB **v4**, migration `MIGRATION_3_4`), keyed by SHA-256 of the normalised address (lower case, single spaces), so the address text itself isn't stored. Entries older than **90 days** are looked up again. Settings → Permissions → **Clear contact locations** empties it. The table is in `poi.db`, so like everything else it's excluded from Android backup, and it isn't in the settings backup file (§13.4).
- **Car UI**:
  - **Search**: typing or speaking a name ("Mum", "Dave Smith") in the `SearchTemplate` shows matching contact addresses **above** the Places results. A contact row has the contact icon, the name as title, and "Home · 12 High Street, Sheffield" as text (plus the distance once the address has been located before).
  - **Contacts**: the Search screen's empty state (under favourites/recents) has a **Contacts** row that opens a contacts-only search (the same screen, with Places results off and the hint "Search your contacts"). The full address book isn't listed, because of the car's list limits.
  - Picking a contact address → geocode (cached or new) → RoutePreview. Contacts with several addresses show one row per address.
- **Assistant**: "Hey Google, navigate to Mum's house" usually reaches us as `ACTION_NAVIGATE` with an address Assistant has already looked up (§9.1). If it only sends a name, it opens Search with that name, where matching contacts are listed first, then Places.
- **Recents**: a contact destination is stored as a recent titled with the name and type, for example "Mum (Home)", with the formatted address. It can be deleted like any other recent (§8.2).
- **Privacy**: contact data is only read on the device, when you search. Only the address you pick is sent to Google (Places Text Search, billed to your Maps key like any search). Names, phone numbers and other addresses never leave the phone.

### 8.3a Route options: avoid tolls, energy-efficient routes for electric vehicles
**Where**: phone Settings → **Routing**, and car More → **Route options** (a list with a toggle for each). Both write the same settings, which apply to the next route (and to re-routes).

**Avoid tolls** (`avoidTolls`, default **on**, the owner's choice): every route avoids toll roads where there's a reasonable way round: `RoutingOptions.avoidTolls(true)` for the SDK's own routes, and `routeModifiers.avoidTolls: true` for energy-efficient ones. If the only way there is a toll road, Google still uses it.

**Energy-efficient routes (EV)** (`evEfficientRoutes`, default **off**): the route preview **offers both routes, the fastest and the energy-efficient one for an electric car, and the driver picks** which to use (owner's choice, 2026-10-12).

**How**: the Navigation SDK's own routing options only cover tolls, ferries, motorways and shorter-vs-faster; it has no energy option. It can, though, follow a route made elsewhere (`setDestinations(waypoints, CustomRoutesOptions(routeToken, DRIVING))`). So with the option on:
1. The app asks Google's **Routes API** (`POST https://routes.googleapis.com/directions/v2:computeRoutes`) for a route from the current position to the destination (the place ID when there is one, else its position), with `travelMode DRIVE`, `routingPreference TRAFFIC_AWARE_OPTIMAL`, `requestedReferenceRoutes ["FUEL_EFFICIENT"]`, `extraComputations ["TRAFFIC_ON_POLYLINE"]` and `routeModifiers.vehicleInfo.emissionType ELECTRIC` (plus `routeModifiers.avoidTolls` when Avoid tolls is on), and the field mask `routes.routeToken,routes.routeLabels,routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals`. The origin is the latest fix, or the phone's last known position right after a restart (no fix yet).
2. Google returns the default route (label `DEFAULT_ROUTE`) and its eco-friendly one (`FUEL_EFFICIENT`), each with a `routeToken`, time and distance; when the default is already the most efficient it returns one route with both labels.
3. **The preview lists both**: "Fastest" and "Energy-efficient (EV)", each with its time and distance (from the Routes API, so they compare like for like), or one row "Fastest and most efficient" when they're the same. The energy-efficient row is selected first (the option is on). Android Auto allows **no button on a route row** (the route preview's rows are "simple" rows, max 0 actions, and the template has one Navigate action), so a Go per route isn't possible; instead the **Go button names the selected route** ("Go · Energy-efficient" / "Go · Fastest") and the selected row is **highlighted before Go**: a green tick icon (vs an empty circle), on top of the host's own selection highlight.
   - **Traffic on each route**, so it's clear which is better: `duration` is with today's traffic and `staticDuration` without, so the delay is their difference. Under 3 min or under 10% of the traffic-free time → "light traffic", under 25% → "some traffic", else "heavy traffic", with the delay: e.g. title "1 hr 39 min · Energy-efficient", text "39 mi · heavy +12 min · queue in 20 min" (traffic "light" / "busy" / "heavy") (the card's width is set by the host, so the route's name sits on the title line to keep the text to fewer lines). The single-route preview keeps the SDK's own traffic level.
   - **Projected traffic** (owner: "we don't want to be on a route that in 20 minutes is going to get congested"): `TRAFFIC_AWARE_OPTIMAL` times each stretch for **when you'll reach it**, from live and historical (predicted) traffic, so a route that clogs up later already shows the delay. `TRAFFIC_ON_POLYLINE` also says where: the `speedReadingIntervals` (NORMAL / SLOW / TRAFFIC_JAM over polyline point ranges) are measured along the decoded polyline. Each row adds **"queue in N min"** for the first TRAFFIC_JAM stretch (its distance along the route × the route's time ÷ its length, a rough arrival time), or "No queues expected". Pick the row without the early queue; guidance then re-routes around new jams as usual. Selecting a row sets that route in the Navigation SDK (`setDestinations(waypoint, CustomRoutesOptions(token, DRIVING))`) so the map shows it; **Go** starts guidance on the selected one. The preview, the guidance and the turn feed are the SDK's as usual.
- **Key and billing**: the call uses the user's own Maps key (§1.4), with the Android app headers (`X-Android-Package`, `X-Android-Cert` = the signing SHA-1) its restriction needs, so it's billed to the user like the rest. **Create a key with my Google account** now also turns on the Routes API (`routes.googleapis.com`) and adds it to the key's API list; for an existing key, pressing it again adds it. The guided steps have a Routes API link too.
- **Fallback, never a dead end**: if the Routes API call fails (not turned on for the key, no network, no eco route there) the app routes normally and the preview says why: "Fastest route (energy-efficient routing isn't available: …)". With the option off, or when the call fails, the preview has its single SDK route as before.
- **Limits**: Google's eco-friendly routing doesn't cover every region; when there's no eco route the default route is used. If you leave the route, the Navigation SDK re-routes itself (fastest); if you chose the energy-efficient route, the app then asks the Routes API again for one from where you are, at most once a minute.
- **Not affected**: camera alerts, the direction rule (§11) and the speed panel work the same on any route.

**Avoid unpaved roads: not possible** (owner asked for it always on, 2026-10-12). Neither the Navigation SDK 8.0 (`RoutingOptions`: tolls, ferries, motorways, shorter/faster only) nor the Routes API (`routeModifiers`: tolls, motorways, ferries, indoor) has an unpaved or road-surface option, so Google's routing can't be told to avoid them. Recorded here so it isn't promised in the UI; revisit with each SDK release (the quarterly SDK check, TESTING.md §6.10).

**Bench** (2026-10-12): with the option on and a key without the Routes API, Google answers 403 and the preview shows the fastest route (tolls avoided) with "Fastest route (energy-efficient routing isn't available: turn on the Routes API for your Maps key in Settings)". The energy-efficient path itself needs the Routes API on the key (re-run **Create a key with my Google account**, which now adds it).

### 8.4 Speed panel: current speed and the road's speed limit
- **What it shows**: always on the map, in the **bottom-right corner** of the visible area (16 px in), clear of the turn card and the media panel (the user asked for bottom right, 2026-10-12; it was bottom left). The map buttons are in a column at the top right, so nothing else uses that corner:
  - **Current speed** in your units (mph/km/h), from the smoothed speed in `AdaptiveLocationController` (§10.3). It updates on every fix, up to 10 Hz, with the number rounded and steadied to avoid flicker.
  - **Road speed limit as Google Maps reports it**, in a UK/EU-style red-ringed sign (or US style, chosen by region).
  - **Colour**: normal; **amber** when over the limit by the minor threshold; **red** when over by the major threshold. The thresholds default to 5% and 10% and are settings.
- **Where the limit comes from**, in this order (the first that has one wins):
  1. **Google**, while you're over its limit: `SpeedingListener.onSpeedingUpdated(percentageAboveLimit, severity)` gives the percentage over, so the limit is worked out (speed ÷ (1 + %), rounded to 5) and kept for 60 s. Google exposes no limit at all below it, and `NavigationViewForAuto` has no speed-limit icon option (SDK 8.0.0). No marker.
  2. **Camera or zone**: the limit of the camera being alerted (last 20 s) or the average-speed zone you're in. Small **blue dot**.
  3. **OpenStreetMap** (§8.4a): the posted `maxspeed` of the road you're on, from data downloaded to the phone. Small **grey dot**.
  4. None known: the sign is **still shown**, with "–" in a grey ring, so the limit always sits next to the speed (owner's rule, 2026-10-12).
- **Always a limit next to the speed** (owner, 2026-10-12):
  - **Held through gaps**: the last limit shown stays for up to **30 s or 300 m** when the lookup briefly finds nothing (junctions, where road pieces meet, a camera alert ending), then the next source in the list takes over.
  - **Steady at junctions**: a side road within 20 m can match for a fix or two, so a **different OSM limit replaces the shown one only after 2 s or 40 m**; Google and camera limits switch at once.
  - **Standing still**: with no heading (parked, crawling), the OpenStreetMap lookup takes the nearest road piece within 20 m in either direction, instead of needing a heading.
- **Colour**: amber/red follow Google's `SpeedingListener` severity (5% / 10% over), as before. Being over a camera or OSM limit doesn't colour the panel.

### 8.4a OpenStreetMap speed limits (one download for the UK)
**Why**: Google gives apps the limit only while you're over it (§8.4); the Roads API speed limits are only for Asset Tracking licence holders. OpenStreetMap's `maxspeed` is free (ODbL) and well filled in on UK A and B roads.

**One bulk download, never live lookups** (the user's rules: "not keep hitting the OpenStreetMap API"; "bulk downloaded as a zip"; "the whole of the UK"):
- **Source**: Geofabrik's free shapefile extracts, rebuilt daily from OpenStreetMap: `https://download.geofabrik.de/europe/united-kingdom/{england,scotland,wales}-latest-free.shp.zip` for Great Britain, and `https://download.geofabrik.de/europe/ireland-and-northern-ireland-latest-free.shp.zip` for **Northern Ireland**. Geofabrik has no shapefile for the whole UK (that URL redirects to its home page) and none for Northern Ireland on its own.
- **Northern Ireland from the Ireland file**: that file also has the Republic, whose limits are km/h (50 km/h would read as 31 mph). Only roads whose midpoint is **inside Northern Ireland's border** are kept: `NorthernIreland.contains` (in `:core`) tests against OSM's Northern Ireland boundary (relation 156393, including its coastal waters), simplified to 50 m (about 1,500 points) and built into the app. It was fetched once when writing the code; the app never looks it up. As a second check, a road there is kept only if its limit is a whole multiple of 5 mph (UK signs), which drops a stray Republic road the 50 m simplification lets in at the border.
- **Only the roads are fetched**: each zip is 0.3–3 GB, but only its roads layer is needed. The app reads the zip's directory with HTTP range requests (Geofabrik supports them) and downloads just `gis_osm_roads_free_1.dbf` and `gis_osm_roads_free_1.shp`: about **1.07 GB** in all (England 49 + 646 MB, Scotland 6.5 + 112 MB, Wales 3.5 + 63 MB, Ireland and Northern Ireland 9 + 179 MB; October 2026).
- **Processed as it streams**, with no temporary copy: the `.dbf` (road attributes) is read first and keeps one byte per road (its limit in mph, 0 if none); then the `.shp` (shapes) is inflated and read record by record, and only roads with a limit are kept. Their lines are simplified (Douglas-Peucker, 3 m), cut into the 0.1° tiles and written to one compact file.
- **Result**: about **29 MB** in `files/osm/osm_limits.bin` (October 2026: 1,326,619 roads with a limit, 42,516 of them in Northern Ireland; Wales alone 81,000 roads, 1.8 MB). It's built as `osm_limits.tmp` with two temp files beside it (`.spill`, `.road_limits`), and renamed over the old file only when complete. It's excluded from Android backup with the other data (§6.4).
- **Limits**: the shapefile's `maxspeed` is whole km/h converted from the OSM tag (30 mph → 48); it's converted back to mph and rounded (48 → 30, 32 → 20, 96 → 60, 112 → 70). Values under 5 mph are dropped. Direction-specific limits (`maxspeed:forward/backward`) aren't in the extract, so each limit applies both ways (rare in the UK).
- **When**: only when the user presses **Download** (or **Update**) in Settings → Speed limits. It runs as a background job (WorkManager, with a progress notification and Cancel; **Wi-Fi only** by default, or over mobile data too if the user turns **Wi-Fi only** off), so it carries on with the app closed. Android refuses a foreground service when the job starts while the app is in the background (e.g. Wi-Fi came back later); it then runs as ordinary background work, with progress still in a notification, and WorkManager re-runs it if the system stops it. Cancel stops it within a fraction of a second (the progress callback checks `isStopped`, since the network reads block), and the old file is kept. It takes a few minutes on fast Wi-Fi. There's no automatic refresh; the card shows the data's date and suggests updating every few months. During an update the old file keeps working; the new one replaces it only when it's complete.
- **File** (`OsmLimitFile`, in `:core`): magic `ANSL`, version 1, the Geofabrik data date, then a sorted index of tiles (tile key, offset, length), then each tile's roads: limit (mph, 1 byte), point count (2 bytes), first point (2 × 4-byte micro-degrees), then each next point as a difference (2 × 2 bytes; a road whose step doesn't fit is split). Reading one tile is a binary search and one read.
- **Low memory by design**: the download never holds the country in RAM. Each road's limit goes to a temp file and is read back alongside the shapes; roads are spilled to a temp file as they arrive (only each tile's size is kept), and the final file is assembled in passes of at most 4 MB. On the bench the whole UK built with the app's Java heap peaking at about 13 MB.
- **Finding the limit for a fix** (`LimitIndex`, in `:core`): only the tiles within 400 m of the car are read (usually one; up to four near a corner), again when the car comes within 60 m of a tile not in memory. They're held as plain arrays (ends in micro-degrees, the limit, and a sorted index of 0.002° cells), so a lookup at 10 Hz allocates nothing. The limit is the one on the **nearest road piece within 20 m** that runs **within 30° of your heading** (either way), so side roads at a junction don't count. No piece → no OSM limit (never a guess).
- **Shown on**: the speed panel only (grey dot). Not used for alerts, the over-limit warning or reports, so OSM gaps or mistakes can't cause a wrong warning.
- **Settings → Speed limits**: **Show OpenStreetMap limits** (on); **Download on Wi-Fi only** (on; off allows mobile data, with a note that it's about 1.1 GB); **Download speed limits for the UK (about 1.1 GB)**, or **Update** once downloaded; progress ("England: roads 412 of 646 MB"); status ("OpenStreetMap data from 11 Oct 2026 · 1.3 million roads · 27 MB"); **Delete**; and the attribution "Speed limits © OpenStreetMap contributors (ODbL), extract by Geofabrik" with a link to openstreetmap.org/copyright. The same attribution is in About.
- **Replaced**: an earlier build fetched 0.1° tiles from the Overpass API as you drove (DB v5 tables `osm_limit`/`osm_tile`). It's gone; DB v6 drops those tables.

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
   - A "My reports" marker at that spot. It **doesn't alert while you're still there**: it's quiet until you're 300 m from it, then alerts normally on the next pass. (Without this, the app said "My reports now" seconds after the tap; bench, 2026-10-12.)
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

### 10.2 Rate: always 10 Hz
The phone's GPS is **always asked for 10 fixes a second** (`AdaptiveLocationSource.GPS_HZ`), at every speed, stopped or moving, and whether or not the car's GPS is in use. The user chose this on 2026-10-12 ("always ask for the GPS data at 10hz") over the earlier speed-based 1–10 Hz rate, to keep the car marker, alerts and the follow watchdog fresh at all times (a slow rate left the car map sitting at 0,0 after a restart until the next fix).
- **Request**: `LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, 100 ms).setMinUpdateIntervalMillis(100).setMaxUpdateDelayMillis(0).setWaitForAccurateLocation(false)`, sent once when location starts. Fixes come in on the `adaptive-location` thread's Looper.
- **Chip limits**: many phone GPS chips deliver 1–5 Hz at most; FLP then delivers what it can and that's accepted. The rate actually delivered (a moving average of the time between fixes) is shown in the car's **More → GPS** row. The request is never lowered.
- **Speed smoothing** (for speed and heading, not the rate): the median of the last 3 `Location.speed` values when `hasSpeed()`; otherwise distance over time between fixes, ignoring fixes with accuracy > 30 m.
- **Power**: 10 Hz all the time costs more battery than the old adaptive rate, mostly when slow or parked with the car screen open (§14). The car is normally charging the phone.
- The old rate curve (`RateCurve`, `AdaptiveRateController` in `:core`, and the stored `gpsMinMph`/`gpsMaxMph`/`gpsMaxHz` values) is no longer used by the app; the Settings controls for it are gone.

### 10.5 Lifecycle
Runs while the foreground service is up (§9.3). It stops when the car session ends and there's no guidance, or when every set has alerts turned off. In that last case it does nothing until a set's alerts are turned back on.

---

### 10.6 Car speed from the head unit
Android Auto can pass the **car's own speed** to the app. The Car App Library reads it through `CarHardwareManager.carInfo.addSpeedListener` (car API level 3+, permission `com.google.android.gms.permission.CAR_SPEED`). The permission is a **runtime** one, so declaring it in the manifest isn't enough: the phone's Settings → Permissions asks for it with the others (`PermState.CAR_SPEED`, shown as "Car speed (speed panel uses the car's own speed)"). Without it the host refuses the listener: the host's log shows `SecurityException: The car app does not have the required permission: com.google.android.gms.permission.CAR_SPEED` on each car session start, nothing crashes, and the phone's GPS speed is used. (Found on the bench 2026-10-12: before this the app never asked, so car speed never worked.) The `Speed` object it returns has:
- **raw speed**: the car's measured road speed
- **display speed**: what the car's own speedometer shows, usually a few percent high
- **speedometer unit**: the car's mph/km/h setting

**Setting:** **Speed and location source** = **Car when available** (default) / **Phone only**. The same setting covers car GPS (§10.7).

**What each speed is used for** (when the car's speed is *fresh*, i.e. reported in the last 3 s):
| Use | Speed used | Why |
|---|---|---|
| Speed panel number | **Car display speed** | Matches the dashboard exactly |
| Alert engine (TIME-mode trigger distance, over-limit check) | **Car raw speed** | Accurate in tunnels, under trees and at low speed, and doesn't lag |
| Position and heading | **Phone GPS**, unchanged | The car only gives speed. Car GPS (`CarSensors.addCarHardwareLocationListener`) is a possible later source |

**Fallback:**
- If the car doesn't provide speed (the value status isn't SUCCESS), or nothing has arrived for 3 s, the phone's smoothed GPS speed is used, exactly as before.
- The car screen's **More → GPS** row shows which source is in use.

**Simulation:** during a debug **Simulate** drive the simulated speed is used, and car speed is ignored. The DHU only sends speed with `config/default_sensors.ini`.

### 10.7 Car GPS for location
The car's GPS position is read through `CarHardwareManager.carSensors.addCarHardwareLocationListener(UPDATE_RATE_FASTEST, …)` (car API 3+, uses the existing fine-location permission). It's controlled by the same setting as speed, renamed **Speed and location source**: **Car when available** (default) / **Phone only**.

- **Primary feed:** while the car's location is **fresh** (a fix in the last 2 s, with status SUCCESS), car fixes feed the alert engine, the per-fix marker redraw, the report snapshot and the free-drive camera checks. Phone fixes are ignored during that time.
- **Phone as backup:** while car GPS is fresh, phone fixes are ignored for alerts (the phone keeps reading at 10 Hz, §10.2). If the car stops reporting for 2 s, phone fixes take over straight away.
- **Rate:** the car decides how often it reports. We ask for its fastest rate. The 10 Hz request applies to the phone.
- **Not changed:** the **map's** car marker and turn-by-turn routing come from the Navigation SDK, which uses the phone's location services. The SDK has no way to feed it the car's GPS. On many cars, Android Auto already passes the car's GPS into the phone's location services anyway.
- **Diagnostics:** **More → GPS** shows "Location from car / phone" and "Speed from car / phone".
- **Simulation:** during a debug Simulate drive, simulated positions are used and car GPS is ignored. The DHU provides car location with `config/default_sensors.ini`.

## 11. Alert engine maths

Inputs per fix: position *P*, smoothed speed *v* (m/s), heading *h*, plus the route polyline if one is set.

- **Heading**: the **track** is the bearing between the last two fixes more than 5 m apart. `Location.bearing` is used when `hasBearing()`, *v* > 2 m/s **and it's within 60° of the track**; otherwise the track is used. Some sources report a stale or fixed bearing: the emulator reports 0° (north) on every fix, which made every camera on a southbound road fail the direction check (found on the bench, A61 Sheffield → Derby, 2026-10-12). Below 2 m/s the last good heading is kept, and no **new** alerts start until the speed is over 2 m/s again.

For each candidate *q* in the cached cells, with the set's and category's alerts on and not `hiddenByReport`:

1. **Distance**: *d* = haversine(*P*, *q*). Skip if *d* > `maxLookahead`, which is the trigger distance + 200 m, capped at 3 km.
2. **Ahead of you**: β = bearing(*P*→*q*). Keep if |angleDiff(β, *h*)| ≤ **45°** (owner's choice, 2026-10-12; was 30°, widening to 45° only within 150 m). Parallel roads are still filtered by step 4.
3. **Direction check** (**hard rule**, §1.3: never dropped or loosened; a camera with a bearing in its data is never treated as "any direction"; tests cover both facing and opposite):
   - ANY or no bearing: passes
   - ONE_WAY: passes if |angleDiff(*q*.bearing, *h*)| ≤ **45°**
   - BOTH: passes if that's ≤ 45° **or** |angleDiff(*q*.bearing + 180, *h*)| ≤ 45°
4. **Route matching** (only with a route): the cross-track distance from *q* to the route polyline ahead of you (the next 3 km of segments). If it's more than **50 m**, drop the POI, which removes cameras on parallel roads. Without a route, a **lateral offset** check is used instead: *d*·sin(angleDiff(β, *h*)) must be ≤ 40 m.
   - **Mobile camera sites get 100 m** for both checks (PGW mobile and pending-mobile sets, and mobile reports): a site is marked where the van parks (bridge, lay-by, verge), often 50–100 m from the carriageway's centre. Found on the bench, 2026-10-12: a PGW mobile site on the M40 south of J11, 62 m off the route and facing the right way, was dropped by the 40 m check. Fixed, red-light and average-speed cameras keep 40/50 m so parallel roads stay filtered. The direction check (step 3) applies to all, unchanged.
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
     - **A zone is only real if it has a camera ahead.** When you pass a type-4 POI that passes the checks while you aren't in a zone, zone mode starts ("Entering average speed zone, {speed} limit") only if another type-4 POI **with the same limit** is ahead of you, facing your direction, within the gap (5 km). A lone SPECS point, or the last camera of a zone you joined part-way through, doesn't start a zone: there's nothing ahead to time you against.
     - Each later type-4 POI you pass (one that passes the direction check) keeps the zone going and updates the limit if it changes.
     - **Passing the last camera ends the zone.** When you pass a zone camera and no same-limit type-4 POI is ahead within the gap, you hear "Leaving average speed zone" there, not 5 km later.
     - The zone also **ends** when no type-4 POI that passes the direction check is ahead within **5 km**, or none has been passed for **5 km** (SPECS points are usually ≤ 2–3 km apart). Then you hear "Leaving average speed zone". Only same-limit points count as "ahead", so a nearby zone with another limit (common in cities, where SPECS 20 and SPECS 30 zones touch) can't keep a finished zone open.
     - **A jump ends the zone.** If two fixes in a row are more than 1 km apart (GPS lost in a tunnel, a bench `geo fix` jump), the zone ends with no alert: we can't know which road you're on now. Zone mode starts again at the next camera that passes the checks.
     - The 5 km gap is a setting.
     - Optional pre-processing at import: `ZoneChainer` groups type-4 points by nearest neighbour (≤ 5 km, bearing within ±30°) into `zoneId` chains and marks the ends of each chain. That improves the "entering" alert at the first camera, but the engine also works without it.
   - The zone also ends after 30 km or 30 min without an end POI.
   - **What a zone alert shows.** Zone alerts (enter, over the limit, leave) belong to the zone camera you last passed: its id, its set's icon (for PGW that's the set for the limit, e.g. "SPECS 50", so the icon itself shows the limit) and its limit. The over-the-limit text names the limit: "Average speed zone, 50 mph limit, you are over the limit" (spoken "…50 miles per hour limit…"). The banner's subtitle also gives the limit. Before this, zone alerts had no POI, so they showed the generic average-speed icon and no limit.
9. **Overspeed**: if the POI or zone has a limit and *v* > limit × (1 + `overspeedPct`). It's set with a **slider from −5% to +20% in 0.1% steps**, defaulting to **−0.1%** (so the warning triggers just below the limit, at 99.9% of it), the alert gets an "you are over the limit" suffix. In a zone, a repeat overspeed chime can play at most every 15 s.

### 11.1 Alert stages
| Stage | Settings | Default | Can be turned off? |
|---|---|---|---|
| **1st alert** | Mode FIXED (distance) or TIME (seconds ahead at current speed, with a minimum distance) | **TIME: 15 s** at the current speed, min 242 m (265 yd) | No. Its distance can be set, but the stage stays (turn the whole set's alerts off to remove it). |
| **2nd alert** | Turned on/off; mode FIXED or TIME; distance or seconds and minimum | On, **TIME: 7 s** at the current speed, min 142 m (155 yd) | Yes |
| **Passing alert** | None: fixed at **100 yd (91.44 m)** | 100 yd | **No. Compulsory whenever sound is on for the POI** (`soundOn`, §4.2). With sound off it's skipped and only the visual alerts run. |

- **Where it's set**: per set, with per-category overrides, in the phone's set detail screen (§13). The settings screen holds the defaults for new sets. Distances are entered in the user's units: yards/miles in imperial, metres/km in metric. The passing distance always shows as "100 yd (91 m)".
- **Trigger distance**: FIXED `D = metres`. TIME `D = max(metres, v × seconds)`, with *v* the **current speed** each fix, so the warning time stays the same at any speed: with the defaults (owner's choice, 2026-10-12: 1st 15 s, 2nd 7 s) 70 mph (31.3 m/s) gives 469 m and 219 m, 50 mph 335 m and 156 m, and at 30 mph the minimums (242 m, 142 m) apply, which keep the 2nd outside the 100 yd passing alert and the 1st 100 m before the 2nd.
- **Existing sets**: DB v7 (`MIGRATION_6_7`) moves sets still on the old defaults (1st TIME 30 s / 457 m, 2nd FIXED 274 m) to the new ones; sets the user changed are left alone.
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
| Our car screen in foreground | Alert banner on the map (§12.1) | NavTts | Trip update (if guiding) |
| Car connected, another app full screen (e.g. media) | Nav heads-up notification | NavTts | Trip update (if guiding) |
| Phone only (no car) | Phone heads-up notification | NavTts | — |

Which state applies comes from the `Screen` lifecycle (STARTED = foreground) plus `CarConnection.type`.

### 12.1 Alert banner on the map
The alert is drawn by us on the map surface, in the same `Presentation` as the speed panel (§8.4), not with Android Auto's `AppManager.showAlert`. The host places its alert card at the top beside the rail and slides it in from the side; the user wants it to **sit at the bottom**, steady.
- **Where:** bottom centre of the visible area, clear of the speed panel (bottom right). It appears and goes in place (a short fade, no slide).
- **What:** the set's icon, the alert text, a subtitle such as "140 yd · 20 mph", and a progress bar that runs for the alert's time (8 s, or 5 s when passing).
- **Updates:** the SECOND and PASSING alerts for the same POI update the banner in place.
- **Dismiss:** tap the banner (`SurfaceCallback.onClick`). There are no buttons on it: the map surface can't host them. **Mute** is in More on the action strip ("Mute alerts for 30 min").
- **Size and look** (`AlertBannerView`):
  - height **96 dp**; width up to **640 dp**, leaving **170 dp** clear on each side for the speed panel (two 64 dp boxes and their gaps), but never narrower than half the visible width
  - placed by `MapSurface.applyPadding` from the **visible** area: centred across it, 16 px above its bottom edge, so it moves with the rail, the turn card and the split-screen media panel
  - dark card (`#202124` at 92%), 16 dp corners; the icon (the set's icon, 128 px bitmap) and the text beside it are **centred together** in the card (2026-10-12; text left-aligned to each other, the block centred); title 26 dp white, ellipsised; subtitle 20 dp grey; progress bar 4 dp along the bottom (`#8AB4F8` on a dark track)
  - **GPU drawing** (2026-10-12): the card, icon and text are drawn once per alert (text shortened once, not per frame) into a **hardware layer**, a GPU texture freed when the banner hides; the progress bar is its own view whose `scaleX` is animated on the **render thread** (`ViewPropertyAnimator`), so nothing is redrawn while it runs. (Before, the whole banner was redrawn every 100 ms.)
  - **Map frame rate** (measured on the bench): the Navigation SDK draws the map into a `GLTextureView` and keeps rendering about 57 frames/s even when parked; hiding all our overlays or leaving follow mode doesn't change it, so it's the SDK's own (e.g. its location marker), not our code.
  - fade in and out over 150 ms; never slides
- **Timing:** shown for 8 s, or 5 s for the passing alert; each new stage restarts the time. The banner hides itself when the time runs out, or at once when tapped.
- **Identity:** the banner remembers the POI id it's showing. A new alert for the same POI (2nd stage, passing) updates the text in place with no fade; a different POI fades the new one in.
- **Threading:** `AlertDispatcher` calls `NavSession.showCarAlert` on the alert thread; the banner update is posted to the banner's own (main) thread. The tap hit-test in `MapSurface.onClick` compares the tap with the banner's bounds on the surface.
- **Tap delivery:** `SurfaceCallback.onClick` needs car API 5+ and a template that passes clicks to the surface. If a host doesn't deliver it, the banner still goes by itself after its time, and Mute is in More. **To confirm on the DHU** (TESTING.md A2b).
- **Fallback:** if the map surface isn't ready, the host `Alert` is used as before (`Alert.Builder(alertId = poi.id, title, 8000)`, icon, subtitle, Dismiss and Mute 30 min actions; car-app API ≥ 5).
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

The phone app has **four tabs: POIs · Cameras · Reports · Settings**. There's no separate Setup tab: the setup steps are the first sections of Settings, and the app opens on Settings while setup is unfinished (no Maps key, or the navigation terms not accepted), otherwise on POIs.

1. **Setup** (top of the Settings tab, in order). Step 0 is the **Google Maps API key**: **Create a key with my Google account** (§13.1), or a field to paste one. Either way the key is saved encrypted and the Navigation SDK and Places are re-initialised:
   1. Location (fine) → background location ("Allow all the time", with an explanation of why)
   2. Notifications
   3. Contacts (optional, for navigating to contacts' addresses)
   4. Navigation SDK Terms & Conditions dialog (`NavigationApi.showTermsAndConditionsDialog`)
   5. Android Auto setup checklist (developer mode, unknown sources), with deep links
2. **Reports**: the end-of-drive review (§8.5). Per report: type (guessed from the comment), position nudges, speed limit with its source, direction (heading ± and One way / Both), comment transcript with Play recording, then Send / Send all / Save / Discard / Browser. Send is disabled while connected to the car or moving.
3. **POI sets** (POIs tab): **only POI files you imported by hand** (source FILE). A card per set showing the name, source, count, last updated, and an on/off switch; swipe left to delete, tap to edit. Button: Import file. Camera sets never appear here.
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
8. **PGW account** (Cameras tab): username and password, "Test login", auto-sync switch, last sync status, **Sync now**, and a terms-of-use risk note.
   - **Camera sets**: every camera set lives here, not on the POIs tab: the PGW sets (source PGW and PGW_PENDING) and **My reports** (REPORT).
   - They're **batched by camera type**, taken from the set name before " · ": e.g. the OV2 download's "PGW fixed cameras · 20 mph", "· 30 mph"… form one **PGW fixed cameras** group. Groups are ordered by source (PGW, then PGW_PENDING, then REPORT) and by name within a source, so the PGW groups are alphabetical and pending mobile sites come last, then My reports. Sets inside a group are sorted by the number after "· " (20, 30 … 70 mph), then by name for those without one.
   - Each group shows its total cameras and number of sets, a **group switch** (turns every set in the group on or off; on while any set in it is on) and an expand arrow. Expanded, it lists the sets as on the POIs tab: switch, tap to edit, swipe left to delete.
   - A summary line above the groups gives the total, e.g. "19,826 cameras in 33 sets".
9. **Settings**:
   - Units: mph/km/h and miles/km.
   - Alerts: voice on/off, chime on/off, overspeed %, and the default 1st and 2nd alert stages for new sets.
   - Over-limit warning: −5% to +20% slider, default −0.1%.
   - Speed and location source: car when available / phone only (§10.6).
   - Speed panel: show/hide; **Satellite map in the car**.
   - Routing: **Avoid tolls** (on), **Energy-efficient routes (EV)** (off) (§8.3a).
   - **Speed limits (OpenStreetMap)** (§8.4a): show switch, **Download on Wi-Fi only**, Download/Update with progress and Cancel, the data's date, roads and size, Delete, and the attribution.
   - Backup: save / restore the settings file (§13.4).
   - Bench testing (debug builds): simulated-drive speed.
   - There's no GPS card: the rate is always 10 Hz (§10.2).
10. **Places**:
   - Favourites and recents, each with its source. Delete by swiping, with undo.
   - **Import from Google Maps**: Takeout zip, or Timeline.json, with a months-back setting.
   - **Google account sync** (Data Portability API): connect/disconnect, last sync, Sync now.
   - "Hidden places" list (tombstones), where entries can be restored.
   - "Manage in Google My Activity" link.
   - **Contacts**: permission status; "Clear contact location cache".
11. **Diagnostics**: requested vs actual GPS rate, current cell and number of cached POIs, the last 20 alert events, and an export-logs button.

### 13.1 Creating the Maps API key with the phone's Google account
The **Create a key with my Google account** button (Settings tab, Google Maps API key section) makes the user's own key in their own Google Cloud account, with no Cloud Console visit (except to add billing the first time).

**Sign-in**: Google Identity Services `AuthorizationClient.authorize()` with the scope `https://www.googleapis.com/auth/cloud-platform`. Android shows its own account picker and consent screen for the Google accounts on the phone. Only a short-lived **access token** is used; it's kept in memory for this one run and never stored. No password or refresh token is kept.

**Steps** (Google Cloud REST APIs over OkHttp, each shown as a progress line):
1. **Project**: reuse the user's project labelled `autonavalerts=maps-key` (Resource Manager v3 `projects:search`), or create `autonavalerts-<6 random chars>` ("AutoNavAlerts") with that label, and wait for the operation.
2. **Billing**: Maps Platform needs a billing account on the project. If the project already has billing, carry on. Otherwise list the user's **open** billing accounts (Cloud Billing API): one → link it; several → the user picks one; **none → stop** with "Add a billing account in Google Cloud, then press Try again" and a button that opens `console.cloud.google.com/billing/create`. **Billing accounts can't be created through the API**, because they need a payment method.
3. **Enable APIs** (Service Usage `services:batchEnable`, then wait): the **Navigation SDK**, **Maps SDK for Android** (`maps-android-backend.googleapis.com`) and **Places API (New)** (`places.googleapis.com`). The Navigation SDK is `navigationsdk.googleapis.com` (checked with `gcloud services list --available` on 2026-10-12); if that name ever stops working, the app searches the project's available services for the title "Navigation SDK".
4. **Key** (API Keys API v2): reuse a key in the project named "AutoNavAlerts · <package> · <first 11 characters of the SHA-1>" (e.g. "AutoNavAlerts · io.autonav.alerts · 6C:48:A1:E5"), skipping deleted keys, or create one, restricted to:
   - **Android app**: package `io.autonav.alerts` and the SHA-1 of the certificate **this installed APK** is signed with (read from `PackageManager`), so a debug build and a release build each get a key that works for them
   - **APIs**: only the three services above
   
   Then read the key string (`keys/…/keyString`).
5. Save it like a pasted key (Keystore-encrypted, §1.4), re-initialise the Navigation SDK and Places, and show "✓ Key created in project <id>".

**The calls**, in order (all with `Authorization: Bearer <token>`):

| Step | Method and URL | Notes |
|---|---|---|
| Find project | `GET cloudresourcemanager.googleapis.com/v3/projects:search?query=labels.autonavalerts:maps-key` | first ACTIVE match |
| Create project | `POST …/v3/projects` `{projectId, displayName: "AutoNavAlerts", labels}` | long-running; then `GET …/v3/projects/<id>` |
| Billing on? | `GET cloudbilling.googleapis.com/v1/projects/<id>/billingInfo` | `billingEnabled` |
| Billing accounts | `GET …/v1/billingAccounts` | only `open: true` ones count |
| Link billing | `PUT …/v1/projects/<id>/billingInfo` `{billingAccountName}` | |
| Navigation SDK name | `GET serviceusage.googleapis.com/v1/projects/<number>/services/navigationsdk.googleapis.com` | if that fails, page through `…/services?pageSize=200` for the title "Navigation SDK" |
| Enable APIs | `POST …/v1/projects/<number>/services:batchEnable` `{serviceIds: [3]}` | long-running |
| Find key | `GET apikeys.googleapis.com/v2/projects/<number>/locations/global/keys` | match on the display name |
| Create key | `POST …/v2/projects/<number>/locations/global/keys` `{displayName, restrictions}` | long-running; the result's `response.name` is the key |
| Key string | `GET …/v2/<key name>/keyString` | |

**Long-running operations** are polled every 2 s, up to 60 times (about 2 minutes); an operation that finishes with an `error` stops the run with its message. **Error messages** come from Google's JSON error; if its `details` carry a help link (e.g. "enable this API", "accept the terms"), that link becomes the error's **Open** button.

Before starting, a dialog says what will be made in the user's Google Cloud account (one project, three APIs, one restricted key, linked to their billing account) and that Google may charge for Maps use beyond its free allowance. Each step is safe to run again, so **Try again** after an error carries on from where it stopped.

**Sign-in flow:** `Identity.getAuthorizationClient(activity).authorize(request)`. If Google needs the user (account picker, consent), the result has a `PendingIntent`, launched with `StartIntentSenderForResult`; the token is then read with `getAuthorizationResultFromIntent`. If the user already granted the scope, the token comes back straight away with no screens.

**Errors**: the app's sign-in isn't registered (status `DEVELOPER_ERROR` (10), or a message containing `UNREGISTERED_ON_API_CONSOLE`, seen as status 8: no OAuth client for this package and SHA-1). The app says so, opens the guided set-up (§13.2), and **disables the Create button** for the rest of the visit instead of offering **Try again**: before this, every press showed the account picker again and failed the same way (the "it asks for the account multiple times" report). Other errors: the user cancelled sign-in; no billing account; billing is closed; an API needs Terms of Service accepted or access approved (shown with a button that opens that API's page in the Console for the project); network errors. Each one is shown in plain words, and pasting a key by hand is always still possible.

**One-time maintainer set-up** (in the Google Cloud project that owns the app's OAuth client, not the user's):
- an **OAuth client of type Android** for package `io.autonav.alerts` and the **shared signing key's SHA-1** (§13.3), so every build from every PC works with one client
- the **OAuth consent screen** with the `cloud-platform` scope. Until Google verifies the app, it stays in **Testing**, and only the Google accounts added as test users (up to 100) can sign in. Google treats this scope as sensitive, so it has to be verified before the app is shared widely (§16).
- **Cloud Resource Manager**, **Cloud Billing**, **Service Usage** and **API Keys** APIs enabled in that project, because API calls made with the app's OAuth token are counted against it

### 13.2 Guided key set-up in the browser (no registration needed)
Google only gives an access token to an app that's registered with an OAuth client, and there's no API to create that registration, so §13.1 only works once the maintainer has registered the app. The guided set-up works for **every** user without it: the user does the steps in Google Cloud themselves, in their browser and signed in to their own account, and the app does everything else it can.

Shown under **Create a key with my Google account** as **Set it up yourself in Google Cloud (step by step)**, collapsed, and opened by itself when §13.1 reports that sign-in isn't registered. Each step has an **Open** button that opens the page in the browser. The Console applies each page to the project it last had open, so the steps run in order:
1. **Create a project**: `console.cloud.google.com/projectcreate` (suggested name "AutoNavAlerts"). The Console switches to it.
2. **Link billing**: `console.cloud.google.com/billing/linkedaccount` (add a billing account first if there's none).
3. **Turn on the APIs**: one button each for the **Navigation SDK** (`apis/library/navigationsdk.googleapis.com`), **Maps SDK for Android** (`apis/library/maps-android-backend.googleapis.com`) and **Places API (New)** (`apis/library/places.googleapis.com`).
4. **Create the key**: `apis/credentials` → Create credentials → API key. Restrict it to **Android apps** with this app's package and SHA-1 (each with a **Copy** button; the SHA-1 is read from the installed APK), and to the three APIs.
5. **Copy the key** in the Console and press **Paste key from clipboard** in the app. The app checks it looks like a Google API key (`AIza` + 35 characters), saves it encrypted and re-initialises the Navigation SDK and Places (§1.4).

### 13.3 One signing key for every build, and the one-time registration
**Why**: Google matches the app's sign-in to its package name **and signing SHA-1**. Each PC's own debug key has a different SHA-1, so without a shared key every PC (and the release build) would need its own registration.

**The key**:
- One PKCS12 key, alias `autonavalerts`, RSA 4096, valid 30 years. SHA-1 **`6C:48:A1:E5:4D:22:D5:0E:D5:C5:6E:E4:86:9E:E5:FF:AC:9D:2C:0F`**.
- It's kept in the **private** GitHub repo `damo2929/autonavalerts-signing`, cloned to `~/.config/autonavalerts` on each PC (`autonavalerts-signing.jks` + `signing.properties` with the passwords; `storeFile` is relative to that file). **Never in this public repo**: anyone holding it can sign apps that Google treats as AutoNavAlerts.
- `app/build.gradle.kts` signs **debug and release** builds with it when `~/.config/autonavalerts/signing.properties` (or the file named by `AUTONAV_SIGNING`) exists, and otherwise falls back to the PC's own debug key. `signing.properties` holds `storeFile` (relative to the properties file, so the folder works wherever it's cloned), `storePassword`, `keyAlias` and `keyPassword`; Gradle loads it into a `signingConfigs.create("shared")`. The passwords are never in `local.properties` (which now holds only `sdk.dir`).
- **A new PC:** `git clone git@github.com:damo2929/autonavalerts-signing.git ~/.config/autonavalerts`, then build as normal. **Check** a build is signed with it: `apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk` must show SHA-1 `6c48a1e5…9d2c0f`. A different SHA-1 means the folder wasn't found and the PC's own debug key was used, so Google sign-in (§13.1) and the restricted Maps keys won't accept that build.
- Changing an installed app's signing key needs an uninstall first (Android refuses the update), which clears its data: the Maps key, the navigation-terms record, the PGW login, the camera database and reports. On the bench that means re-entering the Maps key and PGW login after the switch.
- **Maps keys and the signing key:** a key restricted to Android apps lists package + SHA-1 pairs. A key made by §13.1 lists the SHA-1 of the build that made it. The maintainer's own key (project `damien-maps-b17139`, see CHECKPOINT.md) lists both the shared SHA-1 and the old laptop debug SHA-1, so it worked across the switch. Remove the old one once no installed build uses it.

**One-time registration by the maintainer** (Google Cloud, done once for the app, never by users):
1. A Cloud project for the app: **`autonavalerts-app-6c5c7b`** ("AutoNavAlerts app", created 2026-10-12 with `gcloud`), with Cloud Resource Manager, Cloud Billing, Service Usage and API Keys enabled. It has no billing and needs none.
2. **Console only** (no API for personal accounts): the OAuth consent screen (External, Testing, the `cloud-platform` scope, test users), then an **Android OAuth client** for `io.autonav.alerts` + the SHA-1 above. **Done 2026-10-12**, but in the maintainer's Maps project **`damien-maps-b17139`** rather than the project in step 1 (it can be moved later): Google Auth Platform in Testing, External, scopes `cloud-platform` and `dataportability.maps.aliased_places`, the maintainer's account as the only test user so far, and the Android client "AutoNavAlerts Android (shared signing key)". **The same five APIs (step 1's four plus Data Portability) had to be enabled in that project too:** Google counts calls made with the app's sign-in against the project that owns the OAuth client, so the first run on a phone failed with "Cloud Resource Manager API has not been used in project 893733140226" until they were. Verified on a phone the same day: the button signed in and created the user's key with nothing else to do.
3. Beyond 100 users: Google's verification of the `cloud-platform` scope (needs a privacy policy page; can take weeks).

After that, users never set anything up: they press **Create a key with my Google account** (§13.1). The only thing they may still meet is adding a billing account, if they've never had one.

---

### 13.4 Settings backup file
**Goal**: one tap saves everything you set up by hand into a single JSON file you keep yourself, so a new phone or a reinstall (which wipes the app's data, §13.3) doesn't mean finding your Maps key and PGW login again.

**Where**: Settings tab → **Backup** card → **Save settings backup…** (and **Restore from backup…**, below). Android's file picker (`ActivityResultContracts.CreateDocument("application/json")`) opens with the name `autonavalerts-settings-YYYY-MM-DD.json`, so the user chooses where it goes (Downloads, Drive, a USB stick). The app never uploads it anywhere and keeps no copy. A toast says "Settings backup saved" or why it failed.

**Plain text, with a warning**: the file holds the **Maps API key and the PocketGPSWorld username and password in plain text** (the user asked for a readable JSON file). The card says so before the button: "The file includes your Maps API key and PocketGPSWorld password in plain text. Keep it somewhere private." Inside the app the secrets stay encrypted (`SecretBox`); they're only decrypted to write this file.

**What's in it** (`SettingsBackup.toJson`, format 1):
```json
{
  "app": "AutoNavAlerts", "format": 1,
  "createdAt": "2026-10-12T13:40:00Z", "appVersion": "0.1.0", "package": "io.autonav.alerts",
  "settings": {
    "units": "IMPERIAL", "voiceOn": true, "chimeOn": true, "overspeedPct": -0.001,
    "speedSource": "CAR_WHEN_AVAILABLE", "speedPanelOn": true, "satelliteMap": false,
    "osmLimitsOn": true, "osmWifiOnly": true, "avoidTolls": true, "evEfficientRoutes": false,
    "pgwAutoSync": true, "pgwWifiOnly": false, "pgwDownload": "TOMTOM_OV2"
  },
  "secrets": { "mapsApiKey": "AIza…", "pgwUsername": "…", "pgwPassword": "…" },
  "navTermsAcceptedAt": "2026-10-12T09:12:44Z",
  "poiSets": [
    { "name": "PGW fixed cameras · 30 mph", "source": "PGW", "enabled": true, "showOnMap": true,
      "alertsEnabled": true, "soundEnabled": true, "icon": "user:pgw_gatso_30", "minZoom": 11.0,
      "templates": { "first": "{category} ahead, {speed} limit", "second": "{category}, {distance}", "passing": "{category} now" },
      "firstAlert": { "enabled": true, "mode": "TIME", "metres": 457, "seconds": 30 },
      "secondAlert": { "enabled": true, "mode": "FIXED", "metres": 274, "seconds": null } }
  ]
}
```
- `secrets` leaves out anything not set (no empty strings). `navTermsAcceptedAt` is null if the terms were never accepted in the app.
- `poiSets` holds each **active** set's own settings (show, alerts, sound, icon, templates, alert stages, zoom), keyed by **name**, because set ids change when PGW data is downloaded again. It doesn't hold the POIs.
- **Not included**: the camera data and imported POI files (PGW data is never copied out of the app; imports come from your own files), camera reports and their recordings, recents/home/work/favourites, the PGW session cookies and sync state, "mute until", and the debug simulator speed.

**Restore**: Backup card → **Restore from backup…**.
1. Android's file picker opens (`OpenDocument`, types `application/json`, `text/plain` and `application/octet-stream`, because some file stores label `.json` files as one of those).
2. The file is read and checked: it must be JSON with `"app": "AutoNavAlerts"` and a `format` this version knows (1). Otherwise nothing changes and the card says why ("This isn't an AutoNavAlerts settings backup", "This backup was made by a newer version of the app").
3. A **confirm dialog** shows what the file holds before anything is changed: when it was made and by which version, "Maps API key: included / not included", "PocketGPSWorld login: included (user name) / not included", "Camera set settings: N sets, M of them match sets on this phone". Buttons: **Restore** and **Cancel**.
4. On **Restore**:
   - **Settings**: each value in `settings` replaces the current one. A missing or unreadable value (unknown enum name, out-of-range number) keeps the current value, so a hand-edited or older file still restores what it can. Over-limit is clamped to −5%…+20% as usual.
   - **Maps key**: saved if the file has one, and the Navigation SDK is told the key changed (`NavigatorHolder.keyChanged`, §1.4). A key in the file never removes the current one if the file has none.
   - **PGW login**: saved if the file has one (this clears the PGW session cookies, so the next download logs in afresh).
   - **Camera set settings**: matched to this phone's active sets **by name**. A match takes the file's show/alerts/sound switches, templates, alert stages and zoom, and its icon **if that icon exists on this phone** (a PGW icon comes back with the camera download; until then the set keeps its current icon). Sets in the file with no match are skipped.
   - **Navigation terms are not restored**: Google's terms have to be accepted in the app on each install (§13), so `navTermsAcceptedAt` is information only.
   - Mute-until, the debug simulator speed, PGW sync state and all data (cameras, imports, reports, places) are untouched.
5. A summary: "Restored settings, Maps key, PGW login and 31 camera sets. 2 sets weren't found: download the cameras first, then restore again." Restoring twice is safe; it just applies the same values again.

**Order on a new phone**: install → **Restore from backup** (key and PGW login come back) → accept the navigation terms → Cameras → download → **Restore from backup** again so the PGW sets get their alert settings.

## 14. Power budget

| Consumer | Strategy | Expected impact |
|---|---|---|
| GPS (alerts) | Always 10 Hz (§10.2), or what the chip actually delivers | Main extra cost compared with Google Maps, mostly at motorway speeds |
| GPS (map) | Navigation SDK's own feed; no duplicate request from us | Same as Google Maps |
| CPU (alerts) | Event-driven; in-memory 5×5 cell cache; no Room per fix | Negligible |
| Map drawing | Navigation SDK; POI markers redrawn on **every GPS fix** (up to 10 Hz, §8.1) as a diff, ≤ 200, existing markers changed rather than re-created, unchanged markers left alone | Small to moderate at 10 Hz; measured on its own in the power test |
| Network | Places only on search; PGW: a daily conditional GET of the feed (usually a 304) plus about 220 KB per release (about weekly), on any network | Negligible |
| TTS | Engine stays warm while connected; focus only while speaking | Negligible |
| Startup | R8 code shrinking (release builds; the Navigation SDK and Maps classes are kept whole by `app/proguard-rules.pro`, because the SDK creates some of its own classes by reflection and a shrunk SDK crashed on a phone the moment the key was saved, 2026-10-12), Baseline Profile, Room DB opened in the background | Faster cold start |
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

> The full procedure is in [TESTING.md](TESTING.md). **Bench simulation:** while the debug **Simulate** drive is running, `AdaptiveLocationSource` ignores the phone's real GPS and takes positions from the Navigation SDK's `RoadSnappedLocationProvider` (the simulated car). So alerts and the speed panel can be tested at a desk. Free-drive tests need a moving phone GPS: `tools/drive.py` (a road route from OSRM played through the emulator's GPS, with speed), the emulator's route playback, or a mock-location app.
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
| 20a | Creating the Maps key from the app (§13.1) | Needs an OAuth client set up once by the maintainer; the `cloud-platform` scope is sensitive, so the app is limited to 100 test users until Google verifies it; billing accounts can't be created by API; the API may need its terms accepted in the Console | Pasting a key by hand always works; billing and terms errors open the right Console page; the service name is confirmed and also looked up at run time if it changes |
| 20b | Android Auto on the emulator | Recent emulator images ship only a stub of Android Auto (`1.2.x-stub`, no head-unit server), and Play says the real one "isn't compatible". Without it the DHU has nothing to connect to. | Copy Android Auto from a real phone (`adb pull` its split APKs, `install-multiple` on the emulator; the emulator translates ARM code). Works with 17.9 on the API 36 image (2026-10-12). Never from third-party APK sites. TESTING.md §3. |
| 20c | Driving the DHU from scripts | The DHU console has no tap command, and GNOME blocks desktop screenshots | `bench.sh` uses a touch + rotary profile so `dpad` commands work, and the DHU's own `screenshot` command; restarting the DHU reopens the last nav app |
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
  - 10 Hz GPS (adaptive 1–10 Hz until 2026-10-12), the 5×5 cell cache, and the full `AlertEngine`: 3 stages including the compulsory 100 yd passing alert, direction and parallel-road checks, missed-passing safety net, average-speed chains, overspeed.
  - The alert banner at the bottom of the car map (§12.1; Android Auto's `Alert` card with Dismiss/Mute only before the map exists), a navigation heads-up notification when the app is in the background, and TTS on the guidance channel with ducking and queueing behind SDK voice.
- **Car speed and GPS** (§10.6–10.7): the speed panel uses the car's display speed, and the alert engine uses its raw speed. Car GPS is the primary position feed for alerts, with the phone (still at 10 Hz) as the backup. Both fall back to the phone and can be switched off in Settings.
- **Map extras**: markers redrawn as a diff on every fix (ACTIVE/AHEAD/OTHER), and the speed panel, which shows Google's limit worked out from `SpeedingListener` while speeding, or a camera limit.
- **PGW**:
  - Encrypted login, the daily RSS conditional GET, an automatic download on a new release, a held swap during guidance, notifications, Sync now / Force re-download.
  - Report menu (type → speed → Undo), "My reports" set (mobiles expire after 24 h), "Not there" hide, and phone review with Send / Discard / Open in browser.
- **Phone**: four tabs (§13): POIs (manually imported files), Cameras (PGW and report sets, grouped by type), Reports, and Settings, which starts with setup (Maps key, created automatically or by the guided steps; permissions including car speed; Navigation terms; Android Auto help) followed by units, voice, chime, overspeed %, speed panel and GPS curve.

**Updated 2026-10-12** (session 2, tested on the emulator + DHU with Android Auto copied from a phone):
- No built-in or fallback Maps key (§1.4); every Navigation SDK call waits for the user's key, on the phone and the car.
- Car map fixes: `FrameLayout` host for the map and overlays, map created only after the key is applied, and the follow watchdog (§8).
- Alerts on the car moved to our own banner at the bottom of the map (§12.1). Seen working on the DHU; tap-to-dismiss not yet confirmed.
- Heading: the GPS bearing is only used when it agrees with the track within 60° (§11). Found on the bench: the emulator's bearing is always 0, so nothing alerted on roads not heading north.
- Real average-speed zones: start only with a same-limit camera ahead, end at the last camera, ignore a different-limit zone, end on a 1 km jump; zone alerts carry the zone camera's set icon and limit (§11 step 8). Unit-tested; bench run still to do.
- A fresh report's own marker doesn't alert until you're 300 m away (§8.5).
- Satellite / Map toggle at the top right of the car map, with zoom in, zoom out and recenter in a column under it as our own views; also More → Satellite map and a phone setting (§8). Placement seen on the DHU; taps not yet confirmed.
- `tools/drive.py --limits`: follows OSM speed limits, under / at / over in turn (TESTING.md §3).
- Contacts' addresses in car Search, with a contacts-only search, geocode cache (DB v4) and an optional permission button (§8.3). Built; not yet run on the emulator.
- Settings backup and restore: a plain-text JSON file with the settings, Maps key, PGW login and each set's settings; restore checks it, previews it and asks first (§13.4). Built; not yet run on the emulator.
- Bench: `tools/bench.sh car` keeps the DHU running with a touch + rotary profile, `bench.sh dhu screenshot|dpad …` drives it, and `tools/drive.py` plays a road route through the emulator's GPS (TESTING.md §3).
- **Tests**: 83 JVM unit tests in `:core` (all pass), plus the optional real-file check against a local PGW download.

**Updated 2026-10-12** (session 3):
- OpenStreetMap speed limits for the whole UK from Geofabrik (§8.4a): the roads layers of England, Scotland, Wales and (kept to its border) the Ireland file, range-requested; Wi-Fi only by default with a switch for mobile data; DB v6 drops the earlier Overpass tables. Bench: the whole UK in 1–3 minutes, 29 MB; the grey-dot limit seen on the car panel.
- RAM and CPU pass: disk-backed speed-limit download and compact tile index (§8.4a); alert fixes conflated and the cache-reload race fixed (§4.3); cheaper marker redraws with cached marker images (§8.1); lazy zip reading and streamed imports (§5.2).
- Map buttons anchored to the right edge from the start (§8); the phone's GPS rate card removed.
- **Tests**: 98 JVM unit tests in `:core` (all pass).

**Not yet built** (later phases):
- Home Assistant people finder (§17), Google Maps history imports and the share receiver (§8.2), EV chargers (§18).
- Phone "Send to car" share target, a foreground service for background free-drive, and per-category overrides in the UI.
- Saved CSV profiles, the RadarDroid/Nokia importers, `ZoneChainer` pre-processing, PGW camera-ID lookup and the region picker.
- Instrumented tests, a Baseline Profile and power measurement.
