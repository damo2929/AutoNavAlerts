// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.os.SystemClock
import android.util.Log
import io.autonav.alerts.core.alerts.AlertEngine
import io.autonav.alerts.core.alerts.AlertEvent
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.AlertPoi
import io.autonav.alerts.core.alerts.AlertStage
import io.autonav.alerts.core.alerts.AlertTemplateRenderer
import io.autonav.alerts.core.alerts.AlertTraceLog
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.alerts.RouteMatcher
import io.autonav.alerts.core.alerts.TemplateValues
import io.autonav.alerts.core.alerts.TraceInfo
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.alerts.Verdict
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.osm.RoadAheadMatcher
import io.autonav.alerts.core.poi.Grid
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.data.PoiCategoryEntity
import io.autonav.alerts.data.PoiEntity
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.toStage
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.nav.AdaptiveLocationSource
import io.autonav.alerts.nav.GuidanceController
import io.autonav.alerts.osm.SpeedLimitRepository
import io.autonav.alerts.settings.AppSettings
import io.autonav.alerts.settings.SettingsStore
import io.autonav.alerts.util.view
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * A POI with its set/category resolved, ready for the engine, markers and messages. Built once
 * per cache reload by [AlertCoordinator.resolve], never per fix: [alert] is the engine's view of
 * it (stages already resolved against the lead-time slider, §11.1), [iconId] the marker's icon
 * (§7.1), [alertsOn] whether the set and category have alerts on (§4.2).
 */
data class ResolvedPoi(
    val poi: PoiEntity,
    val set: PoiSetEntity,
    val category: PoiCategoryEntity?,
    val iconId: String,
    val alert: AlertPoi,
    val alertsOn: Boolean,
) {
    /** `{category}` in a template: the category's name, or the set's when the POI has none (§7.2). */
    val categoryName: String get() = category?.name ?: set.name
    // The three stage templates (§7.2): the category's where it has one, else the set's.
    val firstTemplate get() = category?.firstTemplate ?: set.firstTemplate
    val secondTemplate get() = category?.secondTemplate ?: set.secondTemplate
    val passingTemplate get() = category?.passingTemplate ?: set.passingTemplate
}

/**
 * What the car UI shows for an alert (the banner, §12.1; the host `Alert` fallback; the heads-up
 * notification, §12.2; the cluster step, §12.4): one per engine event, [kind] the stage, [display]
 * the short text, [spoken] the words the TTS gets, [distanceM] the distance at the event (metres,
 * 0 for a zone event with none), [timeMs] epoch ms when it was made. The dispatcher passes it on
 * as is; `NavSession` and the banner read it on the main thread.
 */
data class ActiveAlert(
    val poiId: Long,
    val kind: AlertKind,
    val display: String,
    val spoken: String,
    val iconId: String,
    val distanceM: Double,
    /** In the POI's own unit, [speedUnit]; [limitShown] gives it in the display units. */
    val speedLimit: Int?,
    val setId: Long,
    val timeMs: Long,
    val speedUnit: SpeedUnit = SpeedUnit.MPH,
) {
    /** The limit in the display units (DESIGN.md §8.4 "Units"). */
    fun limitShown(imperial: Boolean): Int? = speedLimit?.let { speedUnit.toDisplay(it, imperial) }
}

/**
 * What the marker layer and the screens read of the engine, published as one value per change
 * (DESIGN.md §8.1, §11 step 10, §15.5): [nearby] the POIs in the cache (for markers; updated when
 * the cell changes or data changes); [facingAway] the POIs on our road that point the other way,
 * never alerted, drawn dimmed; [verdicts] every candidate's verdict from the last fix, the marker
 * layer's one source of truth; [active] the alert being shown, cleared 12 s after its event when
 * nothing new fires (the banner's 8 s plus slack); [lastFix] the last fix the engine ran on, for
 * the session's once-a-second limit lookahead and the snapshot.
 */
data class MarkerState(
    val nearby: List<ResolvedPoi> = emptyList(),
    val facingAway: Set<Long> = emptySet(),
    val verdicts: Map<Long, Verdict> = emptyMap(),
    val active: ActiveAlert? = null,
    val lastFix: Fix? = null,
)

/**
 * Runs the alert engine on every adaptive fix (DESIGN.md §10–12), keeps the 5×5 cell cache, and
 * publishes active alerts for the dispatcher and the per-fix marker redraw. One engine for the
 * whole session: settings change it in place, never recreate it (§11 step 9). Mute only silences
 * speech; the candidates keep their real `soundOn` so the passing card still shows (§12.3).
 *
 * What it does, per fix: reloads the candidates when the car enters a new grid cell (§4.3),
 * places the car on the route (`GuidanceController.locateCar`, §11 step 4), runs the core
 * `AlertEngine` with the right route matcher (the SDK's route, else the OSM road ahead, else the
 * straight corridor), publishes the verdicts for the marker layer (§8.1), feeds the live
 * distances to the speech queue's staleness rule, renders each event's templates (§7.2: only
 * `{category} {speed} {units} {distance} {direction}`) and hands it to [AlertDispatcher].
 *
 * Rules upheld here (AGENTS.md §3.2; DESIGN.md §11, §11.1, §12.3): the three stages come from
 * the one global lead-time slider ([resolve], [leadStages]: N s and N/2 s, floors as the set has
 * them); the engine's events are never dropped and PASSING is never dropped (every event is
 * dispatched; the dispatcher and NavTts serialise speech, never this class); mute, set mute and
 * a report's quiet spot only clear `speak`, the engine and the cards are unchanged.
 *
 * Lifecycle: a Hilt singleton. `NavSession` calls [start] with `App.appScope` when the car
 * session is created and [stop] when it ends. The fix collector is one coroutine on that scope
 * with `conflate()`, so fixes are handled one at a time and in order (§2 "one thread for the
 * engine"); the engine itself is guarded by [engineLock] because [stop] and a reload touch it
 * from other threads. Room reads in [reload] run on the collector (Dispatchers.Default via the
 * repository's suspend functions). The StateFlows are read on the main thread by the screens.
 */
@Singleton
class AlertCoordinator @Inject constructor(
    private val location: AdaptiveLocationSource,
    private val repo: PoiRepository,
    private val settings: SettingsStore,
    private val guidance: GuidanceController,
    private val dispatcher: AlertDispatcher,
    private val carData: io.autonav.alerts.nav.CarDataSource,
    private val eventLog: EventLog,
    private val tts: NavTts,
    private val speedLimits: SpeedLimitRepository,
) {
    /** The cache by POI id, for rendering an event and for the trace's names. */
    @Volatile private var byId: Map<Long, ResolvedPoi> = emptyMap()

    /** The trace writes to alert-debug; its PASSED summary needs the set name and the current fix. */
    val trace = AlertTraceLog(
        info = { id -> byId[id]?.let { TraceInfo(it.set.name, it.poi.bearing, it.poi.directionType) } },
        fix = { _markers.value.lastFix },
        sink = { tag, msg -> eventLog.record(EventLog.Channel.ALERT, tag, msg) },
    )
    /** The engine's fixed numbers (§11: 45°, 3 km lookahead, 5 km zone gap); `overspeedPct` is updated in place. */
    private val engineConfig = AlertEngine.Config(overspeedPct = io.autonav.alerts.settings.DEFAULT_OVERSPEED)
    private val engine = AlertEngine(engineConfig, trace)
    /** The engine isn't thread-safe: fixes run on the collector, stop() on the caller's thread. */
    private val engineLock = Any()
    /**
     * How far the candidates reach (DESIGN.md §4.3, §11): the engine looks 3 km ahead and a zone
     * looks up to 5 km for its next camera, so the cells loaded must cover the larger of the two.
     */
    private val reachM = maxOf(engineConfig.maxLookaheadM, engineConfig.zoneGapM)
    /** Bumped by every invalidation, so a reload that started before one doesn't undo it. */
    private val cacheGen = java.util.concurrent.atomic.AtomicInteger()
    /** The collector started by [start]; null when stopped. */
    private var job: Job? = null

    /** The grid cell the cache was built around; MIN_VALUE forces a reload on the next fix. */
    /** POIs reported here moments ago (§8.5): kept out of the candidates until the car is [QUIET_UNTIL_M] away. */
    @Volatile private var cacheCentre: Long = Long.MIN_VALUE
    private val quiet: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var cache: List<ResolvedPoi> = emptyList()
    /** The cache's alerting POIs as the engine takes them, built once per reload, not per fix. */
    @Volatile private var alertable: List<AlertPoi> = emptyList()
    /** The latest settings, kept by the collector so a fix doesn't re-read DataStore on every fix. */
    @Volatile private var latest: AppSettings? = null
    /** The global lead-time slider (§11.1), applied when the cache is resolved. */
    @Volatile private var leadSeconds: Int = io.autonav.alerts.settings.DEFAULT_ALERT_LEAD_SECONDS

    private val _markers = MutableStateFlow(MarkerState())
    /** The engine's output for the marker layer and the screens (§8.1); the flows below are views of its fields. */
    val markers: StateFlow<MarkerState> = _markers
    /** POIs in the cache (for markers). Updated when the cell changes or data changes. */
    val nearby: StateFlow<List<ResolvedPoi>> = markers.view { it.nearby }
    /** POIs on our road that point the other way: never alerted, drawn dimmed (DESIGN.md §8.1). */
    val facingAway: StateFlow<Set<Long>> = markers.view { it.facingAway }
    /** Every candidate's verdict from the last fix (§8.1, §11 step 10): the marker layer's one source of truth. */
    val verdicts: StateFlow<Map<Long, Verdict>> = markers.view { it.verdicts }
    /** The alert being shown; cleared 12 s after its event when nothing new fires (the banner's 8 s plus slack). */
    val active: StateFlow<ActiveAlert?> = markers.view { it.active }
    /** The last fix the engine ran on, for the session's once-a-second limit lookahead and the snapshot. */
    val lastFix: StateFlow<Fix?> = markers.view { it.lastFix }

    /** Sets muted from the car alert's action, until the given time. */
    private val setMutes = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * Starts the location source and the collectors (repository changes, settings, fixes) on
     * [scope]. Idempotent. `NavSession` passes `App.appScope`; the session's end calls [stop].
     */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        tts.spokenCallback = { poiId, kind, spoken, delayMs, outcome -> trace.onSpoken(poiId, kind, spoken, delayMs, outcome) }
        location.start()
        job = scope.launch {
            launch { repo.changes.collect { invalidateCache() } }
            launch {
                settings.settings.collect {
                    val prev = latest
                    latest = it
                    guidance.simulationSpeed = it.simulationSpeed
                    // The one setting covers car speed and car GPS (§10.6, §10.7).
                    carData.enabled = it.speedSource == io.autonav.alerts.settings.SpeedSource.CAR_WHEN_AVAILABLE
                    if (it.overspeedPct != engine.overspeedPct) {
                        engine.overspeedPct = it.overspeedPct
                        alertsLog("overspeedPct=${"%.3f".format(it.overspeedPct)}")
                    }
                    if (prev == null || prev.alertLeadSeconds != it.alertLeadSeconds) {
                        leadSeconds = it.alertLeadSeconds
                        invalidateCache() // re-resolve the candidates on the next fix
                        alertsLog("lead=${it.alertLeadSeconds} s (1st ${it.alertLeadSeconds} s, 2nd ${it.alertLeadSeconds / 2.0} s)")
                    }
                    val muted = it.muted(System.currentTimeMillis())
                    eventLog.onChange("alerts.mute", muted, EventLog.Channel.ALERT, "Alerts") {
                        "mute global=$muted voiceOn=${it.voiceOn} until=${it.mutedUntilMs}"
                    }
                    // Turning mute (or voice off) on stops what is being said and what waits (§12.3).
                    val wasMuted = prev?.muted(System.currentTimeMillis()) == true
                    if (muted && !wasMuted && prev != null) tts.muteNow()
                }
            }
            // One fix at a time; fixes that arrive meanwhile are dropped except the newest, so a
            // reload (Room queries) is never cancelled half-way by the next fix.
            location.fixes.conflate().collect { fix -> onFix(fix) }
        }
    }

    /**
     * Session end: cancels the collectors, stops the location source, resets the engine's per-POI
     * state and the trace. The cache itself is kept, so the next session starts with markers.
     */
    fun stop() {
        job?.cancel()
        job = null
        location.stop()
        // A fix may still be mid-way through the engine on another thread: reset under the same lock.
        synchronized(engineLock) { engine.reset() }
        trace.reset()
        tts.spokenCallback = null
    }

    /**
     * A report just made here (DESIGN.md §8.5): don't alert on its own marker while we're still
     * at it. It's quiet until we're [QUIET_UNTIL_M] away, so it alerts normally on the next pass.
     */
    fun quietHere(poiId: Long) {
        quiet += poiId
        alertsLog("quiet poi=$poiId")
    }

    /** The car alert's "Mute 30 min" for one set (§12.1 fallback `Alert`): speech only, the cards still show (§12.3). */
    fun muteSet(setId: Long, minutes: Int = 30) {
        setMutes[setId] = System.currentTimeMillis() + minutes * 60_000L
        alertsLog("mute set=$setId minutes=$minutes")
    }

    /** An `Alerts …` line in alert-debug (§15.5). */
    private fun alertsLog(msg: String) = eventLog.record(EventLog.Channel.ALERT, "Alerts", msg)

    /**
     * One fix through the engine (§11), on the collector. Reloads the cache when the cell changed,
     * runs the engine under [engineLock], publishes verdicts and facing-away sets only when they
     * differ (no churn for the marker layer), then renders and dispatches every event, highest
     * priority first, with `speak` from sound-on, global mute and set mute.
     */
    private suspend fun onFix(fix: Fix) {
        _markers.update { it.copy(lastFix = fix) }
        val cell = Grid.cellOf(fix.lat, fix.lon)
        if (cell != cacheCentre) reload(cell, fix.lat)
        val s = latest ?: settings.current().also { latest = it }
        val now = System.currentTimeMillis()
        // Mute (timed) and the Voice switch both silence speech only (§12.3).
        val globallyMuted = s.muted(now)
        quiet.removeAll { id -> byId[id]?.let { Geo.distanceM(fix.lat, fix.lon, it.poi.lat, it.poi.lon) > QUIET_UNTIL_M } == true }
        // Mute never changes the candidates (§12.3): PASSING still fires and the card still shows.
        val candidates = if (quiet.isEmpty()) alertable else alertable.filter { it.id !in quiet }
        val (events, verdicts, away) = synchronized(engineLock) {
            guidance.locateCar(fix.lat, fix.lon, fix.heading)
            Triple(
                runCatching { engine.onFix(fix, candidates, matcherFor(fix)) }
                    .onFailure { Log.e(TAG, "engine", it) }.getOrDefault(emptyList()),
                engine.classify(),
                engine.facingAway(),
            )
        }
        // One value for both, and none when neither changed (no churn for the marker layer).
        _markers.update { it.copy(verdicts = verdicts, facingAway = away) }
        // The speech staleness rule needs the live distance to the cameras being alerted on (§12.3).
        for ((id, v) in verdicts) if (v == Verdict.AHEAD_OK) byId[id]?.let { r ->
            tts.onDistance(id, Geo.distanceM(fix.lat, fix.lon, r.poi.lat, r.poi.lon))
        }
        if (events.isEmpty()) {
            _markers.value.active?.let { a -> if (now - a.timeMs > 12_000) _markers.update { it.copy(active = null) } }
            return
        }
        val renderer = AlertTemplateRenderer(s.units)
        // Several events in one fix (a 1st and 2nd crossed together, a zone event with a stage): all are
        // dispatched, PASSING first by priority; nothing is dropped here (§11 step 7).
        for (e in events.sortedByDescending { it.priority }) {
            val r = byId[e.poiId]
            val alert = render(renderer, e, r, fix) ?: continue
            _markers.update { it.copy(active = alert) }
            Log.i(TAG, "${e.kind} poi=${e.poiId} at ${e.distanceM.toInt()} m, ${(fix.speedMps / Geo.MPS_PER_MPH).toInt()} mph: ${alert.display}")
            val setMuted = r?.let { (setMutes[it.set.id] ?: 0) > now } ?: false
            val speak = (r?.alert?.soundOn ?: true) && !globallyMuted && !setMuted
            dispatcher.dispatch(alert, e, speak = speak, chime = s.chimeOn)
        }
    }

    /**
     * The event's display and spoken text from its stage template (§7.2) and the POI's values; zone
     * events use the fixed zone templates (§11 step 8). Null for kinds the engine never emits. An
     * over-limit stage gets the "you are over the limit" suffix in speech only (§11 step 9).
     */
    private fun render(renderer: AlertTemplateRenderer, e: AlertEvent, r: ResolvedPoi?, fix: Fix): ActiveAlert? {
        val now = System.currentTimeMillis()
        val template = when (e.kind) {
            AlertKind.FIRST -> r?.firstTemplate
            AlertKind.SECOND -> r?.secondTemplate
            AlertKind.PASSING -> r?.passingTemplate
            AlertKind.ZONE_ENTER -> AlertTemplateRenderer.ZONE_ENTER
            AlertKind.ZONE_EXIT -> AlertTemplateRenderer.ZONE_EXIT
            AlertKind.OVERSPEED -> AlertTemplateRenderer.ZONE_OVERSPEED
            AlertKind.LIMIT_CHANGE -> null // never emitted by the engine; spoken by NavSession (§8.4a)
            AlertKind.GUIDANCE -> null // never emitted by the engine; spoken by DirectionsSpeaker (§12.3)
        } ?: return null
        val speedUnit = r?.poi?.speedUnit ?: SpeedUnit.MPH
        val values = TemplateValues(
            category = r?.categoryName, setName = r?.set?.name,
            speed = r?.poi?.speedLimit, speedUnit = speedUnit,
            distanceM = e.distanceM.takeIf { it > 0 },
            direction = r?.let { directionWord(fix, it.poi) },
        )
        val out = renderer.render(template, values).let {
            if (e.overspeed && e.kind != AlertKind.OVERSPEED) it.copy(spoken = it.spoken + ", " + AlertTemplateRenderer.OVERSPEED_SUFFIX) else it
        }
        return ActiveAlert(
            poiId = e.poiId, kind = e.kind, display = out.display, spoken = out.spoken,
            iconId = r?.iconId ?: io.autonav.alerts.data.IconRepository.CAMERA_AVERAGE,
            distanceM = e.distanceM, speedLimit = r?.poi?.speedLimit, setId = r?.set?.id ?: -1, timeMs = now,
            speedUnit = speedUnit,
        )
    }

    /** Forces a reload on the next fix (data changed, sets toggled, the slider moved). Any thread. */
    private fun invalidateCache() {
        cacheGen.incrementAndGet()
        cacheCentre = Long.MIN_VALUE
    }

    /**
     * Rebuilds the candidates around [cell] (§4.3): every POI in the cells within [reachM], resolved
     * against its set and category and the current lead seconds. On the collector; Room on IO.
     */
    private suspend fun reload(cell: Long, lat: Double) {
        val gen = cacheGen.get()
        val (rows, cols) = Grid.reachCells(lat, reachM)
        val pois = repo.poisInCells(Grid.neighbourhood(cell, rows, cols))
        val sets = repo.sets()
        val cats = repo.categories()
        val lead = leadSeconds
        val resolved = pois.mapNotNull { p -> resolve(p, sets[p.setId] ?: return@mapNotNull null, p.categoryId?.let { cats[it] }, lead) }
        cache = resolved
        byId = resolved.associateBy { it.poi.id }
        alertable = resolved.filter { it.alertsOn }.map { it.alert }
        // POIs that left the candidates (sets changed, data replaced) lose their approach state (§11 step 7).
        synchronized(engineLock) { engine.forgetExcept(alertable.map { it.id }) }
        // Only now: if this is interrupted, or invalidated meanwhile, the next fix reloads again.
        if (cacheGen.get() == gen) cacheCentre = cell
        trace.prune(byId.keys)
        _markers.update { it.copy(nearby = resolved) }
        alertsLog("cache n=${resolved.size} alertable=${alertable.size} cell=$cell lead=$lead s")
    }

    /**
     * The engine's state as text (DESIGN.md §15.5): the last fix, the feed stats, the cache, the
     * route state, the mutes, the five nearest POIs with their verdicts, and the TTS queue.
     */
    fun snapshot(): String = buildString {
        val mk = _markers.value
        val fix = mk.lastFix
        val st = location.stats.value
        val now = System.currentTimeMillis()
        if (fix == null) append("fix: none yet\n") else {
            append("fix: lat=${"%.5f".format(fix.lat)} lon=${"%.5f".format(fix.lon)} spd=${"%.1f".format(fix.speedMps)} m/s (${fix.speedSource})")
            append(" hdg=${fix.heading?.roundToInt() ?: "-"}±${fix.headingAccuracyDeg?.roundToInt() ?: "-"} (${fix.headingSource})")
            append(" src=${fix.locationSource} acc=${fix.accuracyM?.roundToInt() ?: "-"} age=${SystemClock.elapsedRealtime() - fix.timeMs} ms\n")
        }
        append("stats: req ${st.requestedHz} Hz act ${"%.1f".format(st.actualHz)} Hz speed ${"%.0f".format(st.speedMph)} mph (${st.speedSrc}, car=${st.speedFromCar})")
        append(" dial=${st.dialSpeedMph?.let { "%.0f".format(it) } ?: "-"} heading ${st.headingSrc} chord=${st.chordM?.roundToInt() ?: "-"} gpsBrg=${st.gpsBrgDeg?.roundToInt() ?: "-"} lastReset=${st.lastReset ?: "-"}\n")
        append("cache: n=${cache.size} alertable=${alertable.size} cell=$cacheCentre lead=$leadSeconds s overspeed=${"%.3f".format(engine.overspeedPct)}\n")
        append("route: guiding=${guidance.guiding.value} destination=${guidance.destination.value?.title ?: "-"} zone=${engine.isInZone}\n")
        val s = latest
        val activeSetMutes = setMutes.filterValues { it > now }.keys
        append("mutes: global=${s?.muted(now) ?: "?"} sets=$activeSetMutes quiet=$quiet\n")
        mk.active?.let { append("active: ${it.kind} poi=${it.poiId} \"${it.display}\" ${now - it.timeMs} ms ago\n") }
        if (fix != null) {
            val verdicts = mk.verdicts
            val nearest = cache.sortedBy { Geo.distanceM(fix.lat, fix.lon, it.poi.lat, it.poi.lon) }.take(5)
            append("nearest:\n")
            for (r in nearest) {
                val d = Geo.distanceM(fix.lat, fix.lon, r.poi.lat, r.poi.lon)
                val toPoi = Geo.bearingDeg(fix.lat, fix.lon, r.poi.lat, r.poi.lon)
                val off = fix.heading?.let { Geo.absAngleDiffDeg(toPoi, it).roundToInt() }
                append("  poi=${r.poi.id} set=\"${r.set.name}\" d=${d.roundToInt()} brgTo=${toPoi.roundToInt()} offAxis=${off ?: "-"}")
                append(" camBrg=${r.poi.bearing?.roundToInt() ?: "-"} dir=${r.poi.directionType} verdict=${verdicts[r.poi.id] ?: if (r.alertsOn) "-" else "alertsOff"}")
                if (r.poi.id in mk.facingAway) append(" dimmed")
                append('\n')
            }
        }
        append(tts.snapshot())
    }

    /**
     * The free-drive road ahead and when it was walked (DESIGN.md §11 step 4): once a second, and at
     * once when the heading has turned [ROAD_TURN_DEG] since the walk (a turn at a junction), though
     * no more than every [ROAD_TURN_MIN_MS] so a wandering heading can't re-walk on every fix.
     *
     * Collector-only state (no lock needed): when the road ahead was last walked and the heading then.
     */
    private var road: Pair<Long, RoadAheadMatcher?> = 0L to null
    private var roadHeading = 0.0

    /**
     * What the engine measures cameras against: the SDK's route while the car is on it; otherwise the
     * road ahead followed through the OSM pieces, so a camera on a road beside ours stays silent; past
     * that road, or with none under the car, "no route" and the engine's straight corridor (§11 step 4).
     */
    private fun matcherFor(fix: Fix): RouteMatcher {
        val now = System.currentTimeMillis()
        val heading = fix.heading
        val since = now - road.first
        if (heading != null && (since >= ROAD_EVERY_MS || (since >= ROAD_TURN_MIN_MS && Geo.absAngleDiffDeg(heading, roadHeading) >= ROAD_TURN_DEG))) {
            val m = speedLimits.roadAhead(fix.lat, fix.lon, heading, ROAD_AHEAD_M)?.let { RoadAheadMatcher(it) }
            road = now to m
            roadHeading = heading
            eventLog.onChange("alerts.roadAhead", m != null, EventLog.Channel.ALERT, "Alerts") { "roadAhead ${if (m != null) "on" else "off (no OSM road under the car)"}" }
        }
        val freeDrive = road.second
        return RouteMatcher { lat, lon ->
            guidance.routeMatcher.distanceToRouteM(lat, lon) ?: freeDrive?.distanceToRouteM(lat, lon)
        }
    }

    companion object {
        private const val TAG = "AlertCoordinator"
        /** A just-reported POI is quiet until the car is this far from it (DESIGN.md §8.5). */
        private const val QUIET_UNTIL_M = 300.0

        /**
         * A POI's engine view (§4.2 resolution: the category overrides the set for sound, alerts, icon
         * and zone role; the stages are the set's, with the slider's seconds in TIME mode, §11.1). The
         * limit is converted to m/s here once, so the engine never sees units. Pure; JVM-tested.
         */
        fun resolve(p: PoiEntity, set: PoiSetEntity, cat: PoiCategoryEntity?, leadSeconds: Int? = null): ResolvedPoi {
            val soundOn = cat?.soundEnabled ?: set.soundEnabled
            val alertsOn = set.enabled && set.alertsEnabled && (cat?.alertsEnabled ?: true)
            val limitMps = p.speedLimit?.let { if (p.speedUnit == SpeedUnit.KMH) it / 3.6 else it * Geo.MPS_PER_MPH }
            val (first, second) = leadStages(set.firstAlert.toStage(), set.secondAlert.toStage(), leadSeconds)
            return ResolvedPoi(
                poi = p, set = set, category = cat,
                iconId = cat?.iconId ?: set.defaultIconId,
                alert = AlertPoi(
                    id = p.id, lat = p.lat, lon = p.lon, directionType = p.directionType, bearing = p.bearing,
                    speedLimitMps = limitMps, zoneRole = cat?.zoneRole ?: io.autonav.alerts.core.poi.ZoneRole.NONE,
                    first = first, second = second, soundOn = soundOn,
                    sideToleranceM = if (isMobileSite(set, cat)) MOBILE_SIDE_M else null,
                ),
                alertsOn = alertsOn,
            )
        }

        /**
         * The global lead-time slider (DESIGN.md §11.1): in TIME mode the 1st stage gets N s and the
         * 2nd N/2 s; FIXED stages and every floor (`metres`) are left as the set has them. A null
         * [leadSeconds] leaves both untouched.
         */
        fun leadStages(first: AlertStage, second: AlertStage, leadSeconds: Int?): Pair<AlertStage, AlertStage> {
            if (leadSeconds == null) return first to second
            return first.lead(leadSeconds.toDouble()) to second.lead(leadSeconds / 2.0)
        }

        /** The stage with the slider's [seconds] in TIME mode; a FIXED stage as it is. */
        private fun AlertStage.lead(seconds: Double) = if (mode == DistanceMode.TIME) copy(seconds = seconds) else this

        /** Mobile van sites may be marked up to this far beside the road (DESIGN.md §11 step 4). */
        private const val MOBILE_SIDE_M = 100.0
        /** How far the free-drive road ahead is followed, and how often (DESIGN.md §11 step 4). */
        private const val ROAD_AHEAD_M = 2_000.0
        private const val ROAD_EVERY_MS = 1_000L
        private const val ROAD_TURN_DEG = 10.0
        private const val ROAD_TURN_MIN_MS = 250L

        /**
         * PGW mobile and pending-mobile sites (any format), and mobile camera reports.
         *
         * Recognised by the PGW category code, the file stem of the set's format, the category name, or the
         * pending-mobile source, so every import route marks the same sets mobile (§11 step 4).
         */
        fun isMobileSite(set: PoiSetEntity, cat: PoiCategoryEntity?): Boolean {
            val code = cat?.sourceCode?.lowercase().orEmpty()
            val stem = set.format?.substringAfter(':', "")?.lowercase().orEmpty()
            return set.source == io.autonav.alerts.data.PoiSourceType.PGW_PENDING ||
                code == "mobile" || code == "pmobile" || stem.startsWith("mobile") || stem.startsWith("pmobile") ||
                cat?.name?.contains("mobile", ignoreCase = true) == true
        }

        /** Speech is silenced at [now] (epoch ms): the timed mute, or the Voice switch off (§12.3). The cards are unaffected. Internal for `AlertWordsTest`. */
        internal fun AppSettings.muted(now: Long) = mutedUntilMs > now || !voiceOn

        /** `{direction}` (§7.2): "ahead" within 25° either side of the heading, else left or right; "ahead" with no heading. Internal for `AlertWordsTest`. */
        internal fun directionWord(fix: Fix, poi: PoiEntity): String {
            val h = fix.heading ?: return "ahead"
            val d = Geo.angleDiffDeg(Geo.bearingDeg(fix.lat, fix.lon, poi.lat, poi.lon), h)
            return when {
                d > 25 -> "on your right"
                d < -25 -> "on your left"
                else -> "ahead"
            }
        }

        /** A short distance for cards and rows: the renderer's [AlertTemplateRenderer.shortDistance] (§7.2) by the display units. */
        fun distanceLabel(m: Double, imperial: Boolean): String =
            AlertTemplateRenderer(if (imperial) Units.IMPERIAL else Units.METRIC).shortDistance(m)
    }
}
