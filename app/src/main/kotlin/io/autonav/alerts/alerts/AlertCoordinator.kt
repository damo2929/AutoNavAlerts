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
import io.autonav.alerts.core.alerts.AlertTrace
import io.autonav.alerts.core.alerts.ClearReason
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.alerts.RouteMatcher
import io.autonav.alerts.core.alerts.TemplateValues
import io.autonav.alerts.core.alerts.Verdict
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.osm.RoadAheadMatcher
import io.autonav.alerts.core.poi.DirectionType
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
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** A POI with its set/category resolved, ready for the engine, markers and messages. */
data class ResolvedPoi(
    val poi: PoiEntity,
    val set: PoiSetEntity,
    val category: PoiCategoryEntity?,
    val iconId: String,
    val alert: AlertPoi,
    val alertsOn: Boolean,
) {
    val categoryName: String get() = category?.name ?: set.name
    val firstTemplate get() = category?.firstTemplate ?: set.firstTemplate
    val secondTemplate get() = category?.secondTemplate ?: set.secondTemplate
    val passingTemplate get() = category?.passingTemplate ?: set.passingTemplate
}

/** What the car UI shows for an alert. */
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

/** What the trace needs to know about a POI it is writing about. */
data class TraceInfo(val setName: String, val bearing: Float?, val dirType: DirectionType)

/**
 * The engine's [AlertTrace] as `alert-debug.log` lines (DESIGN.md §11 step 10, §15.5): `Trace
 * enter/leave/suppressed/stage/spoken/stateClear/dim/undim`, and one `PASSED` passage summary per
 * camera when its state clears, assembled from what the trace saw on that approach. Pure Kotlin
 * (JVM-tested): [info] names the POI, [fix] gives the current fix, [sink] takes `(tag, msg)`.
 */
class AlertTraceLog(
    private val info: (Long) -> TraceInfo?,
    private val fix: () -> Fix?,
    private val sink: (tag: String, msg: String) -> Unit,
) : AlertTrace {
    private class StageRec(val kind: AlertKind, val d: Double) {
        var spoken: Boolean? = null
        var delayMs: Long? = null
        var outcome: String? = null
    }

    private class Passage {
        var enterD: Double? = null
        var enterHdg: Double? = null
        var enterAcc: Double? = null
        val stages = ArrayList<StageRec>()
        var lastSuppress: String? = null
        var dimmedAtD: Double? = null
        var lastVerdict = Verdict.OUT_OF_RANGE
        var lastD = 0.0
    }

    private val passages = HashMap<Long, Passage>()
    @Volatile var lastStage: String? = null
        private set
    @Volatile var lastSuppress: String? = null
        private set

    @Synchronized
    override fun verdict(
        poiId: Long, verdict: Verdict, d: Double, offAxisDeg: Double, headingDeg: Double?,
        poiBearing: Float?, dirType: DirectionType, lateralM: Double, allowedM: Double,
    ) {
        val p = passages.getOrPut(poiId) { Passage() }
        val set = info(poiId)?.setName ?: "?"
        if (verdict == Verdict.AHEAD_OK) {
            if (p.enterD == null) { p.enterD = d; p.enterHdg = headingDeg; p.enterAcc = fix()?.headingAccuracyDeg }
            sink("Trace", "enter poi=$poiId set=\"$set\" d=${d.toInt()} hdg=${deg(headingDeg)} camBrg=${deg(poiBearing?.toDouble())} dir=$dirType")
        } else {
            if (p.lastVerdict == Verdict.AHEAD_OK) sink("Trace", "leave poi=$poiId set=\"$set\" d=${d.toInt()} verdict=$verdict")
            val reason = reasonOf(verdict)
            val mismatch = mismatchDeg(poiBearing, dirType, headingDeg)
            val detail = if (verdict == Verdict.WRONG_DIRECTION && mismatch != null) "$reason(${mismatch.toInt()}°)" else reason
            p.lastSuppress = detail
            lastSuppress = "$detail poi=$poiId d=${d.toInt()}"
            sink(
                "Trace",
                "suppressed reason=$reason poi=$poiId set=\"$set\" d=${d.toInt()} offAxis=${deg(offAxisDeg)} hdg=${deg(headingDeg)}" +
                    " camBrg=${deg(poiBearing?.toDouble())} dir=$dirType lateral=${metres(lateralM)} allowed=${metres(allowedM)}",
            )
        }
        p.lastVerdict = verdict
        p.lastD = d
    }

    @Synchronized
    override fun stage(event: AlertEvent) {
        val f = fix()
        val i = info(event.poiId)
        val p = passages.getOrPut(event.poiId) { Passage() }
        val late = event.triggerM - event.distanceM
        val line = "stage ${event.kind} poi=${event.poiId} set=\"${i?.setName ?: "?"}\" d=${event.distanceM.toInt()}" +
            " spd=${f?.let { "%.1f".format(it.speedMps) } ?: "-"} hdg=${deg(f?.heading)} brg=${deg(i?.bearing?.toDouble())}" +
            " expectedD=${event.triggerM.toInt()} late=${late.toInt()} overspeed=${event.overspeed}"
        sink("Trace", line)
        lastStage = "${event.kind} poi=${event.poiId} d=${event.distanceM.toInt()}"
        p.stages += StageRec(event.kind, event.distanceM)
    }

    /** NavTts reported the outcome of a stage's speech (through the coordinator's callback). */
    @Synchronized
    fun onSpoken(poiId: Long, kind: AlertKind, spoken: Boolean, delayMs: Long?, outcome: String) {
        if (kind == AlertKind.LIMIT_CHANGE) {
            // A speed-limit change is its own thing, never a camera stage (owner, 2026-10-13).
            sink("Alerts", "limitChange spoken=$spoken delayMs=${delayMs ?: "-"} outcome=$outcome")
            return
        }
        // Directions are spoken by DirectionsSpeaker (§12.3), never a camera stage.
        if (kind == AlertKind.GUIDANCE) {
            sink("Alerts", "directions spoken=$spoken delayMs=${delayMs ?: "-"} outcome=$outcome")
            return
        }
        sink("Trace", "spoken $kind poi=$poiId spoken=$spoken delayMs=${delayMs ?: "-"} outcome=$outcome")
        val p = passages[poiId] ?: return
        val rec = p.stages.lastOrNull { it.kind == kind && it.spoken == null } ?: return
        rec.spoken = spoken
        rec.delayMs = delayMs
        rec.outcome = outcome
    }

    @Synchronized
    override fun cleared(poiId: Long, reason: ClearReason) {
        sink("Trace", "stateClear poi=$poiId reason=$reason")
        val p = passages.remove(poiId) ?: return
        val i = info(poiId)
        val f = fix()
        val hdg = p.enterHdg ?: f?.heading
        val acc = p.enterAcc ?: f?.headingAccuracyDeg
        val sb = StringBuilder()
        sb.append("poi=").append(poiId).append(" set=\"").append(i?.setName ?: "?").append('"')
        sb.append(" dir=").append(i?.dirType ?: "?").append(" camBrg=").append(deg(i?.bearing?.toDouble()))
        sb.append(" ourHdg=").append(deg(hdg))
        if (hdg != null && acc != null) sb.append('±').append(acc.roundToInt())
        sb.append(" clear=").append(reason).append(" approach: ")
        if (p.stages.isEmpty()) {
            sb.append("NOT ALERTED reason=").append(p.lastSuppress ?: if (p.enterD != null) "noStageReached" else "none")
            p.dimmedAtD?.let { sb.append(" dimmed at d=").append(it.toInt()) }
            p.enterD?.let { sb.append(" (entered d=").append(it.toInt()).append(')') }
        } else {
            sb.append("enter d=").append(p.enterD?.toInt()?.toString() ?: "-")
            for (s in p.stages) {
                sb.append(" → ").append(s.kind).append(" d=").append(s.d.toInt()).append(' ').append(spokenWord(s))
            }
        }
        sink("PASSED", sb.toString())
    }

    @Synchronized
    override fun dimmed(poiId: Long, dimmed: Boolean, mismatchDeg: Double, headingAccuracyDeg: Double) {
        sink("Trace", "${if (dimmed) "dim" else "undim"} poi=$poiId bearingDiff=${mismatchDeg.toInt()} headingAcc=${headingAccuracyDeg.toInt()}")
        if (dimmed) passages[poiId]?.let { it.dimmedAtD = it.lastD }
    }

    /** Forgets passages of POIs no longer among the candidates, so the map can't grow for the whole process. */
    @Synchronized
    fun prune(keep: Set<Long>) {
        passages.keys.retainAll(keep)
    }

    @Synchronized
    fun reset() = passages.clear()

    private fun spokenWord(s: StageRec): String = when {
        s.spoken == true -> "spoken" + (s.delayMs?.let { "(+${"%.1f".format(it / 1000.0)} s)" } ?: "")
        s.outcome == "MUTED" -> "muted"
        s.spoken == false -> "NOT spoken(${s.outcome})"
        else -> "unreported"
    }

    companion object {
        fun reasonOf(v: Verdict): String = when (v) {
            Verdict.WRONG_DIRECTION -> "direction"
            Verdict.OFF_ROAD -> "offRoad"
            Verdict.NOT_AHEAD -> "notAhead"
            Verdict.NOT_MOVING -> "notMoving"
            Verdict.NO_HEADING -> "noHeading"
            Verdict.OUT_OF_RANGE -> "outOfRange"
            Verdict.BEHIND -> "behind"
            Verdict.AHEAD_OK -> "ok"
        }

        /** The camera's bearing against our heading (both ways for BOTH), or null when either is unknown. */
        fun mismatchDeg(bearing: Float?, dirType: DirectionType, heading: Double?): Double? {
            val b = bearing?.toDouble() ?: return null
            val h = heading ?: return null
            return when (dirType) {
                DirectionType.ANY -> 0.0
                DirectionType.ONE_WAY -> Geo.absAngleDiffDeg(b, h)
                DirectionType.BOTH -> minOf(Geo.absAngleDiffDeg(b, h), Geo.absAngleDiffDeg(b + 180.0, h))
            }
        }

        private fun deg(v: Double?): String = if (v == null || v.isNaN()) "-" else v.roundToInt().toString()
        private fun metres(v: Double): String = if (v.isNaN()) "-" else v.roundToInt().toString()
    }
}

/**
 * Runs the alert engine on every adaptive fix (DESIGN.md §10–12), keeps the 5×5 cell cache, and
 * publishes active alerts for the dispatcher and the per-fix marker redraw. One engine for the
 * whole session: settings change it in place, never recreate it (§11 step 9). Mute only silences
 * speech; the candidates keep their real `soundOn` so the passing card still shows (§12.3).
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
    @Volatile private var byId: Map<Long, ResolvedPoi> = emptyMap()

    /** The trace writes to alert-debug; its PASSED summary needs the set name and the current fix. */
    val trace = AlertTraceLog(
        info = { id -> byId[id]?.let { TraceInfo(it.set.name, it.poi.bearing, it.poi.directionType) } },
        fix = { _lastFix.value },
        sink = { tag, msg -> eventLog.record(EventLog.Channel.ALERT, tag, msg) },
    )
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
    private var job: Job? = null

    @Volatile private var cacheCentre: Long = Long.MIN_VALUE
    private val quiet: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var cache: List<ResolvedPoi> = emptyList()
    /** The cache's alerting POIs as the engine takes them, built once per reload, not per fix. */
    @Volatile private var alertable: List<AlertPoi> = emptyList()
    /** The latest settings, kept by the collector so a fix doesn't re-read DataStore (10 Hz). */
    @Volatile private var latest: io.autonav.alerts.settings.AppSettings? = null
    /** The global lead-time slider (§11.1), applied when the cache is resolved. */
    @Volatile private var leadSeconds: Int = io.autonav.alerts.settings.DEFAULT_ALERT_LEAD_SECONDS

    private val _nearby = MutableStateFlow<List<ResolvedPoi>>(emptyList())
    /** POIs in the cache (for markers). Updated when the cell changes or data changes. */
    val nearby: StateFlow<List<ResolvedPoi>> = _nearby

    private val _facingAway = MutableStateFlow<Set<Long>>(emptySet())
    /** POIs on our road that point the other way: never alerted, drawn dimmed (DESIGN.md §8.1). */
    val facingAway: StateFlow<Set<Long>> = _facingAway

    private val _verdicts = MutableStateFlow<Map<Long, Verdict>>(emptyMap())
    /** Every candidate's verdict from the last fix (§8.1, §11 step 10): the marker layer's one source of truth. */
    val verdicts: StateFlow<Map<Long, Verdict>> = _verdicts

    private val _active = MutableStateFlow<ActiveAlert?>(null)
    val active: StateFlow<ActiveAlert?> = _active

    private val _lastFix = MutableStateFlow<Fix?>(null)
    val lastFix: StateFlow<Fix?> = _lastFix

    /** Sets muted from the car alert's action, until the given time. */
    private val setMutes = java.util.concurrent.ConcurrentHashMap<Long, Long>()

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
                    val muted = it.mutedUntilMs > System.currentTimeMillis() || !it.voiceOn
                    eventLog.onChange("alerts.mute", muted, EventLog.Channel.ALERT, "Alerts") {
                        "mute global=$muted voiceOn=${it.voiceOn} until=${it.mutedUntilMs}"
                    }
                    // Turning mute (or voice off) on stops what is being said and what waits (§12.3).
                    val wasMuted = prev != null && (prev.mutedUntilMs > System.currentTimeMillis() || !prev.voiceOn)
                    if (muted && !wasMuted && prev != null) tts.muteNow()
                }
            }
            // One fix at a time; fixes that arrive meanwhile are dropped except the newest, so a
            // reload (Room queries) is never cancelled half-way by the next fix at 10 Hz.
            location.fixes.conflate().collect { fix -> onFix(fix) }
        }
    }

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

    fun muteSet(setId: Long, minutes: Int = 30) {
        setMutes[setId] = System.currentTimeMillis() + minutes * 60_000L
        alertsLog("mute set=$setId minutes=$minutes")
    }

    private fun alertsLog(msg: String) = eventLog.record(EventLog.Channel.ALERT, "Alerts", msg)

    private suspend fun onFix(fix: Fix) {
        _lastFix.value = fix
        val cell = Grid.cellOf(fix.lat, fix.lon)
        if (cell != cacheCentre) reload(cell, fix.lat)
        val s = latest ?: settings.current().also { latest = it }
        val now = System.currentTimeMillis()
        val globallyMuted = s.mutedUntilMs > now || !s.voiceOn
        if (quiet.isNotEmpty()) cache.forEach { r ->
            if (r.poi.id in quiet && Geo.distanceM(fix.lat, fix.lon, r.poi.lat, r.poi.lon) > QUIET_UNTIL_M) quiet.remove(r.poi.id)
        }
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
        if (verdicts != _verdicts.value) _verdicts.value = verdicts
        if (away != _facingAway.value) _facingAway.value = away
        // The speech staleness rule needs the live distance to the cameras being alerted on (§12.3).
        for ((id, v) in verdicts) if (v == Verdict.AHEAD_OK) byId[id]?.let { r ->
            tts.onDistance(id, Geo.distanceM(fix.lat, fix.lon, r.poi.lat, r.poi.lon))
        }
        if (events.isEmpty()) {
            _active.value?.let { a -> if (now - a.timeMs > 12_000) _active.value = null }
            return
        }
        val renderer = AlertTemplateRenderer(s.units)
        for (e in events.sortedByDescending { it.priority }) {
            val r = byId[e.poiId]
            val alert = render(renderer, e, r, fix) ?: continue
            _active.value = alert
            Log.i(TAG, "${e.kind} poi=${e.poiId} at ${e.distanceM.toInt()} m, ${(fix.speedMps / Geo.MPS_PER_MPH).toInt()} mph: ${alert.display}")
            val setMuted = r?.let { (setMutes[it.set.id] ?: 0) > now } ?: false
            val speak = (r?.alert?.soundOn ?: true) && !globallyMuted && !setMuted
            dispatcher.dispatch(alert, e, speak = speak, chime = s.chimeOn)
        }
    }

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
        val values = TemplateValues(
            category = r?.categoryName, setName = r?.set?.name,
            speed = r?.poi?.speedLimit, speedUnit = r?.poi?.speedUnit ?: SpeedUnit.MPH,
            distanceM = e.distanceM.takeIf { it > 0 },
            direction = r?.let { directionWord(fix, it.poi) },
        )
        var out = renderer.render(template, values)
        if (e.overspeed && e.kind != AlertKind.OVERSPEED) {
            out = out.copy(display = out.display, spoken = out.spoken + ", " + AlertTemplateRenderer.OVERSPEED_SUFFIX)
        }
        return ActiveAlert(
            poiId = e.poiId, kind = e.kind, display = out.display, spoken = out.spoken,
            iconId = r?.iconId ?: io.autonav.alerts.data.IconRepository.CAMERA_AVERAGE,
            distanceM = e.distanceM, speedLimit = r?.poi?.speedLimit, setId = r?.set?.id ?: -1, timeMs = now,
            speedUnit = r?.poi?.speedUnit ?: SpeedUnit.MPH,
        )
    }

    private fun directionWord(fix: Fix, poi: PoiEntity): String {
        val h = fix.heading ?: return "ahead"
        val d = Geo.angleDiffDeg(Geo.bearingDeg(fix.lat, fix.lon, poi.lat, poi.lon), h)
        return when {
            d > 25 -> "on your right"
            d < -25 -> "on your left"
            else -> "ahead"
        }
    }

    private fun invalidateCache() {
        cacheGen.incrementAndGet()
        cacheCentre = Long.MIN_VALUE
    }

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
        _nearby.value = resolved
        Log.d(TAG, "Cache: ${resolved.size} POIs around cell $cell")
        alertsLog("cache n=${resolved.size} alertable=${alertable.size} cell=$cell lead=$lead s")
    }

    /**
     * The engine's state as text (DESIGN.md §15.5): the last fix, the feed stats, the cache, the
     * route state, the mutes, the five nearest POIs with their verdicts, and the TTS queue.
     */
    fun snapshot(): String {
        val sb = StringBuilder()
        val fix = _lastFix.value
        val st = location.stats.value
        val now = System.currentTimeMillis()
        if (fix == null) sb.append("fix: none yet\n") else {
            sb.append("fix: lat=${"%.5f".format(fix.lat)} lon=${"%.5f".format(fix.lon)} spd=${"%.1f".format(fix.speedMps)} m/s (${fix.speedSource})")
            sb.append(" hdg=${fix.heading?.roundToInt() ?: "-"}±${fix.headingAccuracyDeg?.roundToInt() ?: "-"} (${fix.headingSource})")
            sb.append(" src=${fix.locationSource} acc=${fix.accuracyM?.roundToInt() ?: "-"} age=${SystemClock.elapsedRealtime() - fix.timeMs} ms\n")
        }
        sb.append("stats: req ${st.requestedHz} Hz act ${"%.1f".format(st.actualHz)} Hz speed ${"%.0f".format(st.speedMph)} mph (${st.speedSrc}, car=${st.speedFromCar})")
        sb.append(" dial=${st.dialSpeedMph?.let { "%.0f".format(it) } ?: "-"} heading ${st.headingSrc} chord=${st.chordM?.roundToInt() ?: "-"} gpsBrg=${st.gpsBrgDeg?.roundToInt() ?: "-"} lastReset=${st.lastReset ?: "-"}\n")
        sb.append("cache: n=${cache.size} alertable=${alertable.size} cell=$cacheCentre lead=$leadSeconds s overspeed=${"%.3f".format(engine.overspeedPct)}\n")
        sb.append("route: guiding=${guidance.guiding.value} destination=${guidance.destination.value?.title ?: "-"} zone=${engine.isInZone}\n")
        val s = latest
        val activeSetMutes = setMutes.filterValues { it > now }.keys
        sb.append("mutes: global=${s?.let { it.mutedUntilMs > now || !it.voiceOn } ?: "?"} sets=$activeSetMutes quiet=$quiet\n")
        _active.value?.let { sb.append("active: ${it.kind} poi=${it.poiId} \"${it.display}\" ${now - it.timeMs} ms ago\n") }
        if (fix != null) {
            val verdicts = _verdicts.value
            val nearest = cache.sortedBy { Geo.distanceM(fix.lat, fix.lon, it.poi.lat, it.poi.lon) }.take(5)
            sb.append("nearest:\n")
            for (r in nearest) {
                val d = Geo.distanceM(fix.lat, fix.lon, r.poi.lat, r.poi.lon)
                val toPoi = Geo.bearingDeg(fix.lat, fix.lon, r.poi.lat, r.poi.lon)
                val off = fix.heading?.let { Geo.absAngleDiffDeg(toPoi, it).roundToInt() }
                sb.append("  poi=${r.poi.id} set=\"${r.set.name}\" d=${d.roundToInt()} brgTo=${toPoi.roundToInt()} offAxis=${off ?: "-"}")
                sb.append(" camBrg=${r.poi.bearing?.roundToInt() ?: "-"} dir=${r.poi.directionType} verdict=${verdicts[r.poi.id] ?: if (r.alertsOn) "-" else "alertsOff"}")
                if (r.poi.id in _facingAway.value) sb.append(" dimmed")
                sb.append('\n')
            }
        }
        sb.append(tts.snapshot())
        return sb.toString()
    }

    /**
     * The free-drive road ahead and when it was walked (DESIGN.md §11 step 4): once a second, and at
     * once when the heading has turned [ROAD_TURN_DEG] since the walk (a turn at a junction), though
     * no more than every [ROAD_TURN_MIN_MS] so a wandering heading can't re-walk on every fix.
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
        private const val QUIET_UNTIL_M = 300.0

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
            val f = if (first.mode == DistanceMode.TIME) first.copy(seconds = leadSeconds.toDouble()) else first
            val s = if (second.mode == DistanceMode.TIME) second.copy(seconds = leadSeconds / 2.0) else second
            return f to s
        }

        /** Mobile van sites may be marked up to this far beside the road (DESIGN.md §11 step 4). */
        private const val MOBILE_SIDE_M = 100.0
        /** How far the free-drive road ahead is followed, and how often (DESIGN.md §11 step 4). */
        private const val ROAD_AHEAD_M = 2_000.0
        private const val ROAD_EVERY_MS = 1_000L
        private const val ROAD_TURN_DEG = 10.0
        private const val ROAD_TURN_MIN_MS = 250L

        /** PGW mobile and pending-mobile sites (any format), and mobile camera reports. */
        fun isMobileSite(set: PoiSetEntity, cat: PoiCategoryEntity?): Boolean {
            val code = cat?.sourceCode?.lowercase().orEmpty()
            val stem = set.format?.substringAfter(':', "")?.lowercase().orEmpty()
            return set.source == io.autonav.alerts.data.PoiSourceType.PGW_PENDING ||
                code == "mobile" || code == "pmobile" || stem.startsWith("mobile") || stem.startsWith("pmobile") ||
                cat?.name?.contains("mobile", ignoreCase = true) == true
        }

        fun distanceLabel(m: Double, imperial: Boolean): String =
            if (imperial) {
                val yd = m / Geo.METRES_PER_YARD
                if (yd < 1000) "${(yd / 10).roundToInt() * 10} yd" else "%.1f mi".format(m / 1609.344)
            } else {
                if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)
            }
    }
}
