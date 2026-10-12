// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ZoneRole
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** How a stage's trigger distance is worked out (DESIGN.md §11.1). */
enum class DistanceMode { FIXED, TIME }

data class AlertStage(
    val enabled: Boolean,
    val mode: DistanceMode,
    /** FIXED: the distance. TIME: the minimum distance. */
    val metres: Int,
    /** TIME only: seconds ahead at the current speed. */
    val seconds: Int? = null,
) {
    fun triggerMetres(speedMps: Double): Double = when (mode) {
        DistanceMode.FIXED -> metres.toDouble()
        DistanceMode.TIME -> max(metres.toDouble(), speedMps * (seconds ?: 0))
    }

    companion object {
        val DEFAULT_FIRST = AlertStage(true, DistanceMode.TIME, metres = 457, seconds = 30) // 500 yd min
        val DEFAULT_SECOND = AlertStage(true, DistanceMode.FIXED, metres = 274) // 300 yd
    }
}

/** A POI as the engine sees it, with its effective (already resolved) alert settings. */
data class AlertPoi(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val directionType: DirectionType,
    val bearing: Float?,
    val speedLimitMps: Double?,
    val zoneRole: ZoneRole,
    val first: AlertStage,
    val second: AlertStage,
    /** Sound on for this POI: makes the 100 yd passing alert compulsory. */
    val soundOn: Boolean,
)

enum class AlertKind { FIRST, SECOND, PASSING, ZONE_ENTER, ZONE_EXIT, OVERSPEED }

data class AlertEvent(
    val poiId: Long,
    val kind: AlertKind,
    val distanceM: Double,
    val overspeed: Boolean,
    /** Priority for the speech queue: higher wins. PASSING is never dropped. */
    val priority: Int,
)

data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    /** Heading in degrees, or null when unknown. */
    val heading: Double?,
    val timeMs: Long,
)

/** Route geometry ahead of the vehicle, for parallel-road rejection. Null = free drive. */
fun interface RouteMatcher {
    /** Distance in metres from the point to the route ahead, or null if no route. */
    fun distanceToRouteM(lat: Double, lon: Double): Double?
}

/**
 * The POI alert maths (DESIGN.md §11). Pure and single-threaded: feed one [Fix] at a time with the
 * candidate POIs around the vehicle; it returns the alerts to dispatch.
 */
class AlertEngine(
    private val config: Config = Config(),
) {
    data class Config(
        val aheadDeg: Double = 30.0,
        val aheadDegNear: Double = 45.0,
        val nearM: Double = 150.0,
        val directionToleranceDeg: Double = 45.0,
        val routeToleranceM: Double = 50.0,
        val lateralToleranceM: Double = 40.0,
        val passingM: Double = PASSING_METRES,
        val minMovingMps: Double = 2.0,
        val maxLookaheadM: Double = 3_000.0,
        val rateLimitMs: Long = 3_000,
        /** Over-limit threshold as a fraction of the limit; −0.001 warns just before reaching it. */
        val overspeedPct: Double = -0.001,
        val zoneGapM: Double = 5_000.0,
        val zoneMaxM: Double = 30_000.0,
        val zoneMaxMs: Long = 30 * 60_000L,
        val zoneOverspeedRepeatMs: Long = 15_000,
        /** Two fixes further apart than this end the zone (§11 step 8). */
        val zoneJumpM: Double = 1_000.0,
        /** Category codes treated as unmarked average-speed chains (PGW: "4"). */
        val chainZoneRole: ZoneRole = ZoneRole.ZONE_POINT,
    )

    private class PoiState(
        var firstFired: Boolean = false,
        var secondFired: Boolean = false,
        var passingFired: Boolean = false,
        var closestM: Double = Double.MAX_VALUE,
        var wasAheadAndValid: Boolean = false,
    )

    private val states = HashMap<Long, PoiState>()
    private var lastHeading: Double? = null
    private var lastSpokenMs: Long = Long.MIN_VALUE / 2
    private var lastSpokenDistance: Double = Double.MAX_VALUE

    // Average-speed zone state
    private var inZone = false
    private var zoneLimitMps: Double? = null
    private var zoneStartLat = 0.0
    private var zoneStartLon = 0.0
    private var zoneStartMs = 0L
    private var lastZonePointLat = 0.0
    private var lastZonePointLon = 0.0
    private var lastZoneOverspeedMs = Long.MIN_VALUE / 2
    /** The zone camera last passed: zone alerts carry its id (icon and limit), or -1 if unknown. */
    private var zonePoiId = -1L
    private var prevLat = Double.NaN
    private var prevLon = Double.NaN
    // This fix's candidates and heading, for the zone checks in handleZonePass.
    private var fixCandidates: Collection<AlertPoi> = emptyList()
    private var fixHeading: Double? = null

    val isInZone: Boolean get() = inZone

    fun reset() {
        states.clear()
        lastHeading = null
        inZone = false
        zonePoiId = -1L
        prevLat = Double.NaN
        prevLon = Double.NaN
    }

    fun onFix(fix: Fix, candidates: Collection<AlertPoi>, route: RouteMatcher? = null): List<AlertEvent> {
        val heading = when {
            fix.heading != null && fix.speedMps > config.minMovingMps -> fix.heading.also { lastHeading = it }
            else -> lastHeading
        }
        val moving = fix.speedMps > config.minMovingMps
        val out = ArrayList<AlertEvent>()
        // A jump (GPS lost, or a bench geo fix) means we can't tell which road we're on: drop the zone.
        if (!prevLat.isNaN() && Geo.distanceM(prevLat, prevLon, fix.lat, fix.lon) > config.zoneJumpM) inZone = false
        prevLat = fix.lat
        prevLon = fix.lon
        fixCandidates = candidates
        fixHeading = heading

        for (poi in candidates) {
            val d = Geo.distanceM(fix.lat, fix.lon, poi.lat, poi.lon)
            val state = states.getOrPut(poi.id) { PoiState() }
            if (d < state.closestM) state.closestM = d

            // Passed it: clear state once we're > 100 m beyond the closest point, so a later pass re-alerts.
            if (d > state.closestM + 100.0) {
                states.remove(poi.id)
                continue
            }
            if (heading == null) continue
            val lookahead = minOf(config.maxLookaheadM, poi.first.triggerMetres(fix.speedMps) + 200.0)
            if (d > lookahead) continue

            val toPoi = Geo.bearingDeg(fix.lat, fix.lon, poi.lat, poi.lon)
            val offAxis = Geo.absAngleDiffDeg(toPoi, heading)
            val aheadLimit = if (d <= config.nearM) config.aheadDegNear else config.aheadDeg
            val ahead = offAxis <= aheadLimit || d < 15.0
            val directionOk = directionMatches(poi, heading)
            val onRoad = onOurRoad(poi, fix, d, offAxis, route)
            val valid = directionOk && onRoad

            // Missed-passing safety net (§11 step 6): was ahead & valid, now behind within 150 m.
            if (state.wasAheadAndValid && !ahead && offAxis > 90.0 && d <= 150.0 && poi.soundOn && !state.passingFired) {
                state.passingFired = true
                state.firstFired = true
                state.secondFired = true
                out += event(poi, AlertKind.PASSING, d, fix)
                handleZonePass(poi, fix, out)
                continue
            }
            state.wasAheadAndValid = ahead && valid
            if (!ahead || !valid || !moving) continue

            // Stages: only the closest crossed stage is spoken; skipped stages are marked fired.
            val crossedPassing = poi.soundOn && !state.passingFired && d <= config.passingM
            val secondTrigger = effectiveSecondTrigger(poi, fix.speedMps)
            val crossedSecond = poi.second.enabled && !state.secondFired && secondTrigger != null && d <= secondTrigger
            val crossedFirst = !state.firstFired && d <= poi.first.triggerMetres(fix.speedMps)

            when {
                crossedPassing -> {
                    state.passingFired = true; state.secondFired = true; state.firstFired = true
                    out += event(poi, AlertKind.PASSING, d, fix)
                    handleZonePass(poi, fix, out)
                }
                crossedSecond -> {
                    state.secondFired = true; state.firstFired = true
                    if (allowSpeech(fix.timeMs, d)) out += event(poi, AlertKind.SECOND, d, fix)
                }
                crossedFirst -> {
                    state.firstFired = true
                    if (allowSpeech(fix.timeMs, d)) out += event(poi, AlertKind.FIRST, d, fix)
                }
            }
            // Without sound, a zone point still counts as passed once we're very close.
            if (!poi.soundOn && d <= config.passingM && !state.passingFired) {
                state.passingFired = true
                handleZonePass(poi, fix, out)
            }
        }

        updateZone(fix, candidates, heading, out)
        return out
    }

    /** The 2nd stage, held at least 100 m inside the 1st (§11.1 validation, applied at runtime). */
    private fun effectiveSecondTrigger(poi: AlertPoi, speedMps: Double): Double? {
        if (!poi.second.enabled) return null
        val first = poi.first.triggerMetres(speedMps)
        val second = poi.second.triggerMetres(speedMps)
        return minOf(second, first - 100.0).coerceAtLeast(config.passingM + 50.0)
    }

    private fun directionMatches(poi: AlertPoi, heading: Double): Boolean {
        val b = poi.bearing?.toDouble() ?: return true
        return when (poi.directionType) {
            DirectionType.ANY -> true
            DirectionType.ONE_WAY -> Geo.absAngleDiffDeg(b, heading) <= config.directionToleranceDeg
            DirectionType.BOTH -> Geo.absAngleDiffDeg(b, heading) <= config.directionToleranceDeg ||
                Geo.absAngleDiffDeg(b + 180.0, heading) <= config.directionToleranceDeg
        }
    }

    private fun onOurRoad(poi: AlertPoi, fix: Fix, d: Double, offAxisDeg: Double, route: RouteMatcher?): Boolean {
        val routeDist = route?.distanceToRouteM(poi.lat, poi.lon)
        if (routeDist != null) return routeDist <= config.routeToleranceM
        val lateral = abs(d * sin(Math.toRadians(offAxisDeg)))
        return lateral <= config.lateralToleranceM
    }

    private fun allowSpeech(nowMs: Long, d: Double): Boolean {
        val ok = nowMs - lastSpokenMs >= config.rateLimitMs || d < lastSpokenDistance
        if (ok) {
            lastSpokenMs = nowMs
            lastSpokenDistance = d
        }
        return ok
    }

    private fun event(poi: AlertPoi, kind: AlertKind, d: Double, fix: Fix): AlertEvent {
        val limit = if (inZone) zoneLimitMps ?: poi.speedLimitMps else poi.speedLimitMps
        val over = limit != null && fix.speedMps > limit * (1 + config.overspeedPct)
        val priority = when (kind) {
            AlertKind.PASSING -> 100
            AlertKind.SECOND, AlertKind.OVERSPEED -> 50
            else -> 10
        }
        return AlertEvent(poi.id, kind, d, over, priority)
    }

    private fun handleZonePass(poi: AlertPoi, fix: Fix, out: MutableList<AlertEvent>) {
        when (poi.zoneRole) {
            ZoneRole.ZONE_START -> {
                if (!inZone) {
                    startZone(poi, fix)
                    out += AlertEvent(poi.id, AlertKind.ZONE_ENTER, 0.0, false, 60)
                } else {
                    zoneLimitMps = poi.speedLimitMps
                }
                zonePoiId = poi.id
                lastZonePointLat = poi.lat; lastZonePointLon = poi.lon
            }
            ZoneRole.ZONE_POINT -> {
                // A real zone has another camera ahead with the same limit to time you against (§11 step 8).
                val ahead = zonePointAhead(fix, poi.speedLimitMps, exclude = poi.id)
                if (!inZone) {
                    if (!ahead) return
                    startZone(poi, fix)
                    out += AlertEvent(poi.id, AlertKind.ZONE_ENTER, 0.0, false, 60)
                } else {
                    zoneLimitMps = poi.speedLimitMps
                }
                zonePoiId = poi.id
                lastZonePointLat = poi.lat; lastZonePointLon = poi.lon
                if (!ahead) {
                    // The last camera of the zone.
                    inZone = false
                    out += AlertEvent(poi.id, AlertKind.ZONE_EXIT, 0.0, false, 60)
                }
            }
            ZoneRole.ZONE_END -> if (inZone) {
                inZone = false
                out += AlertEvent(poi.id, AlertKind.ZONE_EXIT, 0.0, false, 60)
            }
            ZoneRole.NONE -> Unit
        }
    }

    private fun startZone(poi: AlertPoi, fix: Fix) {
        inZone = true
        zoneStartLat = fix.lat; zoneStartLon = fix.lon; zoneStartMs = fix.timeMs
        zoneLimitMps = poi.speedLimitMps
    }

    /** Is a zone point with this limit ahead of us, facing our way, within the zone gap? */
    private fun zonePointAhead(fix: Fix, limitMps: Double?, exclude: Long = -1): Boolean {
        val heading = fixHeading ?: return false
        return fixCandidates.any { p ->
            p.id != exclude && p.zoneRole == ZoneRole.ZONE_POINT && sameLimit(p.speedLimitMps, limitMps) &&
                Geo.distanceM(fix.lat, fix.lon, p.lat, p.lon).let { it > config.passingM && it <= config.zoneGapM } &&
                Geo.absAngleDiffDeg(Geo.bearingDeg(fix.lat, fix.lon, p.lat, p.lon), heading) <= 60.0 &&
                directionMatches(p, heading)
        }
    }

    private fun sameLimit(a: Double?, b: Double?) = a == null || b == null || abs(a - b) < 0.5

    private fun updateZone(fix: Fix, candidates: Collection<AlertPoi>, heading: Double?, out: MutableList<AlertEvent>) {
        if (!inZone) return
        val sinceLastPoint = Geo.distanceM(fix.lat, fix.lon, lastZonePointLat, lastZonePointLon)
        val fromStart = Geo.distanceM(fix.lat, fix.lon, zoneStartLat, zoneStartLon)
        val chainAhead = zonePointAhead(fix, zoneLimitMps)
        val endByGap = sinceLastPoint > config.zoneGapM && !chainAhead
        val endByLimit = fromStart > config.zoneMaxM || fix.timeMs - zoneStartMs > config.zoneMaxMs
        if (endByGap || endByLimit) {
            inZone = false
            out += AlertEvent(zonePoiId, AlertKind.ZONE_EXIT, 0.0, false, 60)
            return
        }
        val limit = zoneLimitMps ?: return
        if (fix.speedMps > limit * (1 + config.overspeedPct) && fix.timeMs - lastZoneOverspeedMs >= config.zoneOverspeedRepeatMs) {
            lastZoneOverspeedMs = fix.timeMs
            out += AlertEvent(zonePoiId, AlertKind.OVERSPEED, 0.0, true, 50)
        }
    }

    companion object {
        /** 100 yards: the compulsory passing alert (DESIGN.md §11.1). */
        const val PASSING_METRES = 91.44
    }
}
