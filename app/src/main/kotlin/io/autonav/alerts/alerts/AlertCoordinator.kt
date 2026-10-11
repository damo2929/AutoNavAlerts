// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.util.Log
import io.autonav.alerts.core.alerts.AlertEngine
import io.autonav.alerts.core.alerts.AlertEvent
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.AlertPoi
import io.autonav.alerts.core.alerts.AlertTemplateRenderer
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.alerts.TemplateValues
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.Grid
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.data.PoiCategoryEntity
import io.autonav.alerts.data.PoiEntity
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.toStage
import io.autonav.alerts.nav.AdaptiveLocationSource
import io.autonav.alerts.nav.GuidanceController
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val speedLimit: Int?,
    val setId: Long,
    val timeMs: Long,
)

/**
 * Runs the alert engine on every adaptive fix (DESIGN.md §10–12), keeps the 5×5 cell cache, and
 * publishes active alerts for the dispatcher and the per-fix marker redraw.
 */
@Singleton
class AlertCoordinator @Inject constructor(
    private val location: AdaptiveLocationSource,
    private val repo: PoiRepository,
    private val settings: SettingsStore,
    private val guidance: GuidanceController,
    private val dispatcher: AlertDispatcher,
    private val carSpeed: io.autonav.alerts.nav.CarSpeedSource,
) {
    @Volatile private var engine = AlertEngine(AlertEngine.Config(overspeedPct = io.autonav.alerts.settings.DEFAULT_OVERSPEED))
    private var engineOverspeed = Double.NaN
    private var job: Job? = null

    @Volatile private var cacheCentre: Long = Long.MIN_VALUE
    @Volatile private var cache: List<ResolvedPoi> = emptyList()

    private val _nearby = MutableStateFlow<List<ResolvedPoi>>(emptyList())
    /** POIs in the cache (for markers). Updated when the cell changes or data changes. */
    val nearby: StateFlow<List<ResolvedPoi>> = _nearby

    private val _active = MutableStateFlow<ActiveAlert?>(null)
    val active: StateFlow<ActiveAlert?> = _active

    private val _lastFix = MutableStateFlow<Fix?>(null)
    val lastFix: StateFlow<Fix?> = _lastFix

    /** Sets muted from the car alert's action, until the given time. */
    private val setMutes = HashMap<Long, Long>()

    fun start(scope: CoroutineScope) {
        if (job != null) return
        location.start()
        job = scope.launch {
            launch { repo.changes.collect { cacheCentre = Long.MIN_VALUE } }
            launch {
                settings.settings.collect {
                    location.setCurve(it.rateCurve)
                    guidance.simulationSpeed = it.simulationSpeed
                    carSpeed.enabled = it.speedSource == io.autonav.alerts.settings.SpeedSource.CAR_WHEN_AVAILABLE
                    if (it.overspeedPct != engineOverspeed) {
                        engineOverspeed = it.overspeedPct
                        engine = AlertEngine(AlertEngine.Config(overspeedPct = it.overspeedPct))
                    }
                }
            }
            location.fixes.collectLatest { fix -> onFix(fix) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        location.stop()
        engine.reset()
    }

    fun muteSet(setId: Long, minutes: Int = 30) {
        setMutes[setId] = System.currentTimeMillis() + minutes * 60_000L
    }

    private suspend fun onFix(fix: Fix) {
        _lastFix.value = fix
        val cell = Grid.cellOf(fix.lat, fix.lon)
        if (cell != cacheCentre) reload(cell)
        val s = settings.current()
        val now = System.currentTimeMillis()
        val globallyMuted = s.mutedUntilMs > now || !s.voiceOn
        val candidates = cache.asSequence().filter { it.alertsOn }.map { r ->
            val muted = globallyMuted || (setMutes[r.set.id] ?: 0) > now
            if (muted) r.alert.copy(soundOn = false) else r.alert
        }.toList()
        val events = runCatching { engine.onFix(fix, candidates, guidance.routeMatcher) }
            .onFailure { Log.e(TAG, "engine", it) }.getOrDefault(emptyList())
        if (events.isEmpty()) {
            _active.value?.let { a -> if (now - a.timeMs > 12_000) _active.value = null }
            return
        }
        val renderer = AlertTemplateRenderer(s.units)
        val byId = cache.associateBy { it.poi.id }
        for (e in events.sortedByDescending { it.priority }) {
            val r = byId[e.poiId]
            val alert = render(renderer, e, r, fix) ?: continue
            _active.value = alert
            val speak = r?.let { it.alert.soundOn && !globallyMuted && (setMutes[it.set.id] ?: 0) <= now } ?: !globallyMuted
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
            AlertKind.OVERSPEED -> "Average speed zone, " + AlertTemplateRenderer.OVERSPEED_SUFFIX
        } ?: return null
        val values = TemplateValues(
            name = r?.poi?.name, category = r?.categoryName, set = r?.set?.name,
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

    private suspend fun reload(cell: Long) {
        cacheCentre = cell
        val pois = repo.poisInCells(Grid.neighbourhood(cell, 2))
        val sets = repo.sets()
        val cats = repo.categories()
        cache = pois.mapNotNull { p -> resolve(p, sets[p.setId] ?: return@mapNotNull null, p.categoryId?.let { cats[it] }) }
        _nearby.value = cache
        Log.d(TAG, "Cache: ${cache.size} POIs around cell $cell")
    }

    companion object {
        private const val TAG = "AlertCoordinator"

        fun resolve(p: PoiEntity, set: PoiSetEntity, cat: PoiCategoryEntity?): ResolvedPoi {
            val soundOn = cat?.soundEnabled ?: set.soundEnabled
            val alertsOn = set.enabled && set.alertsEnabled && (cat?.alertsEnabled ?: true)
            val limitMps = p.speedLimit?.let { if (p.speedUnit == SpeedUnit.KMH) it / 3.6 else it * Geo.MPS_PER_MPH }
            return ResolvedPoi(
                poi = p, set = set, category = cat,
                iconId = cat?.iconId ?: set.defaultIconId,
                alert = AlertPoi(
                    id = p.id, lat = p.lat, lon = p.lon, directionType = p.directionType, bearing = p.bearing,
                    speedLimitMps = limitMps, zoneRole = cat?.zoneRole ?: io.autonav.alerts.core.poi.ZoneRole.NONE,
                    first = set.firstAlert.toStage(), second = set.secondAlert.toStage(), soundOn = soundOn,
                ),
                alertsOn = alertsOn,
            )
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
