// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ZoneRole
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** How a stage's trigger distance is worked out (DESIGN.md §11.1). */
enum class DistanceMode { FIXED, TIME }

/**
 * The settings of one alert stage (DESIGN.md §11.1, §4.1 `AlertStage`): the 1st and 2nd alerts.
 * The passing alert has no settings. Distances are metres; [seconds] is a lead time at the current
 * speed, so a TIME stage's distance is worked out again on every fix from the speed in m/s.
 */
data class AlertStage(
    val enabled: Boolean,
    val mode: DistanceMode,
    /** FIXED: the distance. TIME: the minimum distance. */
    val metres: Int,
    /** TIME only: seconds ahead at the current speed. Fractional, since the 2nd stage is half the 1st (7.5 s). */
    val seconds: Double? = null,
) {
    constructor(enabled: Boolean, mode: DistanceMode, metres: Int, seconds: Int?) :
        this(enabled, mode, metres, seconds?.toDouble())

    /**
     * The trigger distance in metres at [speedMps] (m/s): the fixed distance, or for TIME the larger
     * of the floor and speed × seconds (DESIGN.md §11.1 "Trigger distance"). Before the ratchet.
     */
    fun triggerMetres(speedMps: Double): Double = when (mode) {
        DistanceMode.FIXED -> metres.toDouble()
        DistanceMode.TIME -> max(metres.toDouble(), speedMps * (seconds ?: 0.0))
    }

    companion object {
        // Seconds ahead at the current speed (DESIGN.md §11.1): N and N/2. The minimums keep the 2nd
        // at 142 m and the 1st 100 m before the 2nd at low speed.
        val DEFAULT_FIRST = AlertStage(true, DistanceMode.TIME, metres = 242, seconds = 15.0)
        val DEFAULT_SECOND = AlertStage(true, DistanceMode.TIME, metres = 142, seconds = 7.5)
    }
}

/** A POI as the engine sees it, with its effective (already resolved) alert settings. */
data class AlertPoi(
    /** The app's POI id; events carry it back. */
    val id: Long,
    val lat: Double,
    val lon: Double,
    /** ANY, ONE_WAY or BOTH (DESIGN.md §4.1). A POI with a bearing is never treated as ANY by the engine. */
    val directionType: DirectionType,
    /** The direction of travel the camera enforces, degrees true, 0–359.x; null when the data has none (§4.1). */
    val bearing: Float?,
    /** The posted limit in m/s (already converted from the POI's unit), or null when unknown or variable. */
    val speedLimitMps: Double?,
    /** Average-speed zone part, if any (§11 step 8): ZONE_POINT for PGW SPECS points. */
    val zoneRole: ZoneRole,
    /** The resolved 1st and 2nd stages (category over set, the global lead-time slider applied, §4.2, §11.1). */
    val first: AlertStage,
    val second: AlertStage,
    /** Sound on for this POI: makes the passing alert (1 s ahead, ≥ 20 yd) compulsory. */
    val soundOn: Boolean,
    /**
     * How far to the side of the road it may be (route and lateral checks), when more than the
     * engine's usual 40/50 m: mobile camera sites are marked where the van parks (DESIGN.md §11 step 4).
     */
    val sideToleranceM: Double? = null,
)

/**
 * What an utterance or event is. The engine emits the first six for a camera; [LIMIT_CHANGE] is the
 * "speed limit changes to thirty ahead" announcement (DESIGN.md §8.4a): no camera, no POI, never a
 * stage, so it is never reported or shown as one (owner, 2026-10-13). [GUIDANCE] is a turn-by-turn
 * direction we speak ourselves (DESIGN.md §12.3), never a camera stage. The last two are made by
 * the app, not by this engine, and share the speech queue ([SpeechScheduler]).
 */
enum class AlertKind { FIRST, SECOND, PASSING, ZONE_ENTER, ZONE_EXIT, OVERSPEED, LIMIT_CHANGE, GUIDANCE }

/**
 * One alert to dispatch (DESIGN.md §11.1 "Output", §12): the app turns it into text, a banner, a
 * notification and speech. The engine never drops or rate-limits events (§11 step 7); the speech
 * queue decides what is said.
 */
data class AlertEvent(
    val poiId: Long,
    /** FIRST, SECOND, PASSING, or a zone event (ZONE_ENTER, ZONE_EXIT, OVERSPEED). */
    val kind: AlertKind,
    /** Straight-line distance to the POI on this fix, metres; 0 for zone events. */
    val distanceM: Double,
    /** Speed over limit × (1 + overspeedPct) at the moment of the event (§11 step 9): adds "you are over the limit". */
    val overspeed: Boolean,
    /** Priority for the speech queue: higher wins. PASSING is never dropped. */
    val priority: Int,
    /** The stage's effective trigger distance on this fix (after the ratchet and the 2nd-stage clamp). */
    val triggerM: Double = 0.0,
    /**
     * Speech staleness (DESIGN.md §12.3): don't speak this once the car is closer than this. FIRST → the
     * POI's effective 2nd trigger (or the passing distance when the 2nd is off); SECOND → the passing
     * distance; PASSING and zone events → null (never dropped).
     */
    val staleBelowM: Double? = null,
)

/** Where the heading in a [Fix] came from (DESIGN.md §10.6a). */
enum class HeadingSource { GPS, TRACK, BLEND, HELD, NONE, UNKNOWN }

/** Where the speed in a [Fix] came from (DESIGN.md §10.2, §10.6). [NONE]: nothing to go on yet (no reported speed and no displacement reference). */
enum class SpeedSource { CAR_RAW, GPS, DERIVED, SIMULATED, NONE }

/** Which feed the position in a [Fix] came from (DESIGN.md §10.1, §10.7). */
enum class LocationSource { PHONE, CAR, SIM }

/**
 * One position as the engine sees it (DESIGN.md §10, §11 "Inputs per fix"). Built by the app from
 * [io.autonav.alerts.core.location.FixEstimator]'s estimate; the extra fields are defaulted so
 * tests and the replay can build one from a position, speed, heading and time alone.
 */
data class Fix(
    /** Position, degrees. */
    val lat: Double,
    val lon: Double,
    /** The engine's speed, m/s: the car's raw speed when fresh, else GPS (§10.6). Every TIME distance comes from it. */
    val speedMps: Double,
    /** Heading in degrees, or null when unknown. */
    /**
     * Degrees true, [0, 360). Null when unknown: parked over 30 s, or nothing to go on yet (§10.6a).
     * The engine then judges nothing and starts no new alert.
     */
    val heading: Double?,
    /**
     * Milliseconds on the fix's own clock. The app uses the time since boot (`elapsedRealtimeNanos`,
     * §10.7), not the wall clock, so only differences between fixes mean anything.
     */
    val timeMs: Long,
    /** 1σ heading error estimate (§10.6a); null = unknown, treated as 10°. */
    val headingAccuracyDeg: Double? = null,
    val headingSource: HeadingSource = HeadingSource.UNKNOWN,
    val speedSource: SpeedSource = SpeedSource.GPS,
    /** The car's dial speed when there is one; the panel shows this, the engine uses [speedMps]. */
    val displaySpeedMps: Double? = null,
    val locationSource: LocationSource = LocationSource.PHONE,
    /** Horizontal position accuracy in metres, when known. */
    val accuracyM: Double? = null,
)

/** Route geometry ahead of the vehicle, for parallel-road rejection. Null = free drive. */
fun interface RouteMatcher {
    /** Distance in metres from the point to the route ahead, or null if no route. */
    fun distanceToRouteM(lat: Double, lon: Double): Double?
}

/**
 * The POI alert maths (DESIGN.md §11, the stages in §11.1, the speech policy that follows in
 * §12.3, the marker verdicts in §8.1). Pure and single-threaded: feed one [Fix] at a time with the
 * candidate POIs around the vehicle; it returns the alerts to dispatch. Every crossed stage is emitted
 * (there is no rate limit; speech is serialised by the app, §12.3). [trace] is optional diagnostics.
 *
 * No Android and no threads of its own: the app's `AlertCoordinator` owns one instance and calls
 * [onFix] one fix at a time from `AlertCoordinator`'s fix collector, never concurrently (§2 "Threads"), with the candidates from
 * `AlertCoordinator`'s cell cache (the cells within the 3 km lookahead and the 5 km zone gap, §4.3) and the route
 * matcher of the moment (`RoutePolyline` with a route, `RoadAheadMatcher` in free drive, §11 step 4).
 * [classify] and [facingAway] are read after each fix for the marker layer.
 *
 * Units: metres for every distance, m/s for speed, degrees true for headings and bearings, and
 * [Fix.timeMs] for time (the app's time since boot; only differences are used).
 *
 * **The camera direction rule is owner-set and is never dropped, loosened or defaulted around**
 * (§1.3, §11 step 3, AGENTS.md §3.1 rule 1): a camera alerts only when its stored bearing is within
 * 45° of the car's heading, or within 45° of the bearing from the car to the camera while the camera
 * is properly ahead; ONE_WAY and BOTH keep their meaning; a camera with a bearing is never treated
 * as ANY; an opposite-facing camera is dimmed, never hidden, and never alerts. [directionMismatch]
 * is the one place it is applied, [Config.directionToleranceDeg] cannot be raised above 45°, and
 * the tests `camera_30DegBend_directionPassesViaBearingToCamera` and
 * `oppositeCameraStillRejectedOnABend` (plus the wrong-way bench scenario) must keep passing after
 * any change here, in the importers, in the heading maths or in the markers.
 *
 * Other fixed behaviour (§11): the passing alert is compulsory whenever sound is on, 1 s ahead at
 * the current speed and never under 20 yd; triggers ratchet and never shrink on an approach;
 * average-speed zones start only with a same-limit camera ahead and end at the last camera.
 */
class AlertEngine(
    private val config: Config = Config(),
    private val trace: AlertTrace? = null,
) {
    data class Config(
        /** A camera counts as ahead within this of the direction of travel (DESIGN.md §11 step 2). */
        val aheadDeg: Double = 45.0,
        /** The direction rule's tolerance (§11 step 3, the hard rule). Never applied above 45°. */
        val directionToleranceDeg: Double = 45.0,
        /** With a route: a POI further than this from the route ahead is on another road (§11 step 4). */
        val routeToleranceM: Double = 50.0,
        /** Free drive: the side of the straight corridor, before it widens with distance (§11 step 4). */
        val lateralToleranceM: Double = 40.0,
        /** Free drive: the corridor widens to d·sin(this) beyond the lateral tolerance (§11 step 4). */
        val freeDriveFarDeg: Double = 12.0,
        val passingM: Double = PASSING_METRES,
        /** The passing alert comes this long before the camera at the current speed, never under [passingM] (§11.1). */
        val passingLeadS: Double = PASSING_LEAD_S,
        /** Moving above this; the moving flag drops again below [stoppedMps]. */
        val minMovingMps: Double = 2.0,
        val stoppedMps: Double = 1.0,
        /** PASSING still fires at this speed once the POI is tracked (§11 step 6). */
        val passingMinMps: Double = 0.5,
        /** The held heading is dropped after this long without movement (≥ [passingMinMps]). */
        val headingHoldMs: Long = 30_000,
        /** Nothing is judged beyond this (§11 step 1); a POI's own lookahead is its 1st trigger + 200 m, capped here. */
        val maxLookaheadM: Double = 3_000.0,
        /** A fired stage with no approach (d ≥ firedAtD − [staleApproachM]) for this long is forgotten. */
        val staleFiredMs: Long = 90_000,
        val staleApproachM: Double = 30.0,
        /** State is dropped once a POI is this far behind. */
        val behindClearM: Double = 2_000.0,
        /** State is dropped once a POI has been out of the candidates for this long. */
        val pruneMs: Long = 60_000,
        /** Over-limit threshold as a fraction of the limit; −0.001 warns just before reaching it. */
        val overspeedPct: Double = -0.001,
        /** Average-speed zones (§11 step 8): the gap within which the next same-limit camera must lie. */
        val zoneGapM: Double = 5_000.0,
        /** A zone ends by itself after 30 km or 30 min without an end camera (§11 step 8). */
        val zoneMaxM: Double = 30_000.0,
        val zoneMaxMs: Long = 30 * 60_000L,
        /** In a zone, the over-the-limit chime repeats at most this often (§11 step 9). */
        val zoneOverspeedRepeatMs: Long = 15_000,
        /** Two fixes further apart than this end the zone (§11 step 8). */
        val zoneJumpM: Double = 1_000.0,
    )

    /** Over-limit threshold (§11 step 9); updated in place, the engine keeps its state. */
    var overspeedPct: Double = config.overspeedPct

    private class PoiState {
        var firstFired = false
        var secondFired = false
        var passingFired = false
        var closestM = Double.MAX_VALUE
        /** Passed ahead + direction + corridor once this approach: stays valid until clearly lost (§11 step 4). */
        var tracked = false
        var firedAtMs = 0L
        var firedAtD = 0.0
        var lastSeenMs = 0L
        /** The ratcheted triggers: the highest speed seen since the POI came into its lookahead. */
        var firstTriggerM = 0.0
        var secondTriggerM = 0.0
        /** Facing-away dimming (§8.1), with hysteresis. */
        var hidden = false
        var verdict = Verdict.OUT_OF_RANGE
        /** The verdict last given to the trace (changes outside the lookahead aren't reported). */
        var traced = Verdict.OUT_OF_RANGE
        /** Inside its lookahead and not behind at some point this approach: only then is a clear worth a trace line. */
        var entered = false
        val fired get() = firstFired || secondFired || passingFired
    }

    private val states = HashMap<Long, PoiState>()
    private var lastHeading: Double? = null
    private var movingFlag = false
    private var lastMovedMs = Long.MIN_VALUE / 2
    private var lastFixMs = Long.MIN_VALUE
    private val dirTol = minOf(config.directionToleranceDeg, 45.0)
    private val sinFar = sin(Math.toRadians(config.freeDriveFarDeg))

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

    /** Whether an average-speed zone is open (§11 step 8); the panel shows the zone's limit while it is. */
    val isInZone: Boolean get() = inZone

    /** Forgets every POI's approach, the heading and the zone: a new drive or a simulated one starting. */
    fun reset() {
        states.clear()
        lastHeading = null
        movingFlag = false
        lastMovedMs = Long.MIN_VALUE / 2
        inZone = false
        zonePoiId = -1L
        prevLat = Double.NaN
        prevLon = Double.NaN
    }

    /**
     * One fix (DESIGN.md §11, the numbered steps). [candidates] are the POIs with alerts on around the
     * car; [route] measures a POI's distance to the road ahead, or null for the straight free-drive
     * corridor. Returns the events crossed on this fix, in candidate order; the zone events last.
     * Stage flags are set only when an event is emitted (§11 step 7), so nothing is lost silently.
     */
    fun onFix(fix: Fix, candidates: Collection<AlertPoi>, route: RouteMatcher? = null): List<AlertEvent> {
        val now = fix.timeMs
        if (fix.speedMps >= config.passingMinMps) lastMovedMs = now
        // Moving has hysteresis: on above 2 m/s, off below 1 m/s (§11 "Heading"), so a crawl doesn't flicker it.
        if (fix.speedMps > config.minMovingMps) movingFlag = true else if (fix.speedMps < config.stoppedMps) movingFlag = false
        val moving = movingFlag
        // The heading is taken while moving, held below that, and dropped after 30 s without movement.
        val heading: Double? = when {
            fix.heading != null && fix.speedMps > config.minMovingMps -> fix.heading.also { lastHeading = it }
            now - lastMovedMs > config.headingHoldMs -> null.also { lastHeading = null }
            else -> lastHeading
        }
        val out = ArrayList<AlertEvent>()
        // A jump (GPS lost, or a bench geo fix) means we can't tell which road we're on: drop the zone.
        if (!prevLat.isNaN() && Geo.distanceM(prevLat, prevLon, fix.lat, fix.lon) > config.zoneJumpM) inZone = false
        prevLat = fix.lat
        prevLon = fix.lon
        fixCandidates = candidates
        fixHeading = heading
        lastFixMs = now
        // The heading accuracy the dimming confidence uses: clamped 5–20°, 10° when unknown (§8.1).
        val headingAcc = (fix.headingAccuracyDeg ?: 10.0).coerceIn(5.0, 20.0)

        for (poi in candidates) {
            val d = Geo.distanceM(fix.lat, fix.lon, poi.lat, poi.lon)
            val state = states.getOrPut(poi.id) { PoiState() }
            state.lastSeenMs = now
            if (d < state.closestM) state.closestM = d

            // Passed it: reset once we're > 100 m beyond the closest point, so a later pass re-alerts.
            // 100 m past the closest point is "passed" (§11 step 7).
            if (d > state.closestM + 100.0) { resetApproach(poi.id, state, d, ClearReason.PASSED); continue }
            // Fired, but no approach since (parked jitter or a long stop): forget it so the real approach alerts.
            // Never once PASSING has fired: we reached the camera (e.g. waiting at a red-light camera's stop
            // line), so a stale clear would only say PASSING again on moving off; leaving it resets as passed.
            if (state.fired && !state.passingFired && now - state.firedAtMs >= config.staleFiredMs && d >= state.firedAtD - config.staleApproachM) {
                clear(poi.id, state, ClearReason.STALE); continue
            }
            val toPoi = Geo.bearingDeg(fix.lat, fix.lon, poi.lat, poi.lon)
            val offAxis = if (heading != null) Geo.absAngleDiffDeg(toPoi, heading) else Double.NaN
            // 2 km behind: the state is dropped (§11 step 7) so a later pass alerts again.
            if (heading != null && offAxis > 90.0 && d > config.behindClearM) { resetApproach(poi.id, state, d, ClearReason.BEHIND); continue }
            if (d > config.maxLookaheadM) { setVerdict(state, poi, Verdict.OUT_OF_RANGE, false, d, offAxis, heading, Double.NaN, Double.NaN); continue }

            // The ratchet (§11 step 5): the trigger never shrinks while the POI is in its lookahead.
            val firstNow = poi.first.triggerMetres(fix.speedMps)
            val lookahead = minOf(config.maxLookaheadM, max(state.firstTriggerM, firstNow) + 200.0)
            val inLookahead = d <= lookahead
            if (inLookahead) {
                state.firstTriggerM = max(state.firstTriggerM, firstNow)
                if (poi.second.enabled) state.secondTriggerM = max(state.secondTriggerM, poi.second.triggerMetres(fix.speedMps))
            }
            if (heading == null) { setVerdict(state, poi, Verdict.NO_HEADING, inLookahead, d, offAxis, null, Double.NaN, Double.NaN); continue }

            // Step 2: within 45° of the heading is "ahead"; under 15 m we are on top of it and the angle means
            // nothing, so it still counts as ahead (and never as behind) there (§11 steps 2 and 3).
            val aheadProper = offAxis <= config.aheadDeg
            val ahead = aheadProper || d < 15.0
            val behind = offAxis > 90.0 && d >= 15.0
            // The direction rule (§11 step 3, the hard rule): within 45° of the heading, or of the bearing
            // to the camera when it is properly ahead. Applied on every fix, tracked or not.
            val mismatch = directionMismatch(poi, heading, toPoi, aheadProper)
            val dirOk = mismatch <= dirTol
            val lateral: Double
            val allowed: Double
            val latchAllowed: Double
            val routeDist = route?.distanceToRouteM(poi.lat, poi.lon)
            // Step 4: the side allowance is the route's 50 m or free drive's 40 m, raised to the POI's own
            // (100 m for mobile sites, marked where the van parks).
            val sideTol = maxOf(if (routeDist != null) config.routeToleranceM else config.lateralToleranceM, poi.sideToleranceM ?: 0.0)
            if (routeDist != null) {
                lateral = routeDist; allowed = sideTol; latchAllowed = sideTol
            } else {
                // Free drive: the lateral offset from the straight line ahead, allowed up to max(side, d·sin 12°);
                // the tracked latch only lets go past twice that (§11 step 4, "Tracked latch").
                lateral = abs(d * sin(Math.toRadians(offAxis)))
                allowed = maxOf(sideTol, d * sinFar)
                latchAllowed = maxOf(sideTol, 2.0 * d * sinFar)
            }
            val onRoad = lateral <= allowed

            // Missed-passing safety net (§11 step 6): tracked, now behind within 150 m.
            // Step 6: a tracked camera that went from ahead to behind within 150 m without a PASSING (a GPS
            // jump, or slow fixes that skipped the 20 yd mark) gets its PASSING now. Sound on means it can't be missed.
            if (state.tracked && behind && d <= 150.0 && poi.soundOn && !state.passingFired) {
                state.tracked = false
                markFired(state, AlertKind.PASSING, now, d)
                out += emit(poi, AlertKind.PASSING, d, fix, config.passingM, null)
                handleZonePass(poi, fix, out)
                setVerdict(state, poi, Verdict.BEHIND, inLookahead, d, offAxis, heading, lateral, allowed)
                continue
            }
            // The tracked latch (§11 step 4): once ahead + direction + corridor have passed, a moment of heading
            // noise can't drop the POI between stages; only clearly behind, far outside the corridor, or a
            // direction off by more than 90° releases it.
            if (state.tracked && (behind || lateral > latchAllowed || mismatch > 90.0)) state.tracked = false
            if (!state.tracked && ahead && dirOk && onRoad) state.tracked = true

            val verdict = when {
                !state.tracked && behind -> Verdict.BEHIND
                !state.tracked && !ahead -> Verdict.NOT_AHEAD
                !state.tracked && !onRoad -> Verdict.OFF_ROAD
                !dirOk -> Verdict.WRONG_DIRECTION
                !moving -> Verdict.NOT_MOVING
                else -> Verdict.AHEAD_OK
            }
            updateHidden(poi, state, verdict, moving, behind, dirOk, heading, headingAcc)
            setVerdict(state, poi, verdict, inLookahead, d, offAxis, heading, lateral, allowed)
            if (!inLookahead) continue

            val canStage = verdict == Verdict.AHEAD_OK
            // PASSING may fire below the moving threshold, down to 0.5 m/s, once the POI is tracked (§11 step 6):
            // a crawl past a camera isn't silent.
            val canPass = (verdict == Verdict.AHEAD_OK || verdict == Verdict.NOT_MOVING) && fix.speedMps >= config.passingMinMps
            // Stages: only the closest crossed stage is spoken; skipped stages are marked fired.
            // Passing is measured along the track (§11.1): a camera beside the carriageway is announced
            // as the car draws level with it; straight ahead, along equals d.
            val along = if (offAxis <= 90.0) d * cos(Math.toRadians(offAxis)) else d
            // One second ahead at the current speed, never less than 20 yd (§11.1, owner 2026-10-24).
            val passingTrigger = max(config.passingM, fix.speedMps * config.passingLeadS)
            val crossedPassing = canPass && poi.soundOn && !state.passingFired && along <= passingTrigger
            val secondTrigger = effectiveSecondTrigger(poi, state)
            val crossedSecond = canStage && secondTrigger != null && !state.secondFired && d <= secondTrigger
            val crossedFirst = canStage && !state.firstFired && d <= state.firstTriggerM
            when {
                crossedPassing -> {
                    markFired(state, AlertKind.PASSING, now, d)
                    out += emit(poi, AlertKind.PASSING, d, fix, passingTrigger, null)
                    handleZonePass(poi, fix, out)
                }
                crossedSecond -> {
                    markFired(state, AlertKind.SECOND, now, d)
                    out += emit(poi, AlertKind.SECOND, d, fix, secondTrigger!!, passingTrigger)
                }
                crossedFirst -> {
                    markFired(state, AlertKind.FIRST, now, d)
                    out += emit(poi, AlertKind.FIRST, d, fix, state.firstTriggerM, secondTrigger ?: passingTrigger)
                }
            }
            // Without sound, a zone point still counts as passed once we're very close.
            // Sound off: no PASSING is spoken or shown, but the zone chain still needs to know we passed it.
            if (!poi.soundOn && canPass && along <= passingTrigger && !state.passingFired) {
                state.passingFired = true
                handleZonePass(poi, fix, out)
            }
        }

        updateZone(fix, candidates, heading, out)
        prune(now)
        return out
    }

    /**
     * Every candidate's verdict from the last [onFix] (DESIGN.md §8.1): the one source of truth for the
     * marker emphasis. POIs not seen on that fix are absent.
     */
    fun classify(): Map<Long, Verdict> {
        val out = HashMap<Long, Verdict>()
        for ((id, s) in states) if (s.lastSeenMs == lastFixMs) out[id] = s.verdict
        return out
    }

    /**
     * The POIs drawn dimmed (§8.1): on our road ahead, facing the other way with confidence (the
     * mismatch exceeds 45° + the heading accuracy, while moving), kept with hysteresis until the
     * direction rule passes again, the mismatch drops under 40°, or the POI is behind. Never alerted.
     */
    fun facingAway(): Set<Long> {
        var out: MutableSet<Long>? = null
        for ((id, s) in states) if (s.hidden) (out ?: HashSet<Long>().also { out = it }) += id
        return out ?: emptySet()
    }

    /** The old signature: the verdicts come from the last [onFix], so the arguments are no longer used. */
    @Deprecated("Use facingAway() after onFix", ReplaceWith("facingAway()"))
    @Suppress("UNUSED_PARAMETER")
    fun facingAway(fix: Fix, pois: Collection<AlertPoi>, route: RouteMatcher? = null): Set<Long> = facingAway()

    /** Drops the state of POIs that haven't been candidates for [Config.pruneMs] (§11 step 7). */
    fun prune(nowMs: Long) {
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value.lastSeenMs > config.pruneMs) {
                it.remove()
                if (e.value.entered) trace?.cleared(e.key, ClearReason.PRUNED)
            }
        }
    }

    /** Drops the state of every POI not in [ids] (the cache was reloaded, or sets changed). */
    fun forgetExcept(ids: Collection<Long>) {
        val keep = ids.toHashSet()
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                it.remove()
                if (e.value.entered) trace?.cleared(e.key, ClearReason.PRUNED)
            }
        }
    }

    private fun clear(id: Long, state: PoiState, reason: ClearReason) {
        states.remove(id)
        if (state.entered) trace?.cleared(id, reason)
    }

    /**
     * The approach is over (passed, or far behind): back to a fresh state anchored at the current
     * distance. The state object is kept, so a POI behind us doesn't "pass" again every 100 m.
     */
    private fun resetApproach(id: Long, state: PoiState, d: Double, reason: ClearReason) {
        if (state.entered) trace?.cleared(id, reason)
        state.firstFired = false; state.secondFired = false; state.passingFired = false
        state.tracked = false
        state.closestM = d
        state.firedAtMs = 0L; state.firedAtD = 0.0
        state.firstTriggerM = 0.0; state.secondTriggerM = 0.0
        state.hidden = false
        state.entered = false
    }

    private fun setVerdict(
        state: PoiState, poi: AlertPoi, verdict: Verdict, inLookahead: Boolean, d: Double, offAxis: Double,
        heading: Double?, lateral: Double, allowed: Double,
    ) {
        state.verdict = verdict
        if (inLookahead && verdict != Verdict.BEHIND) state.entered = true
        if (trace == null) return
        val report = if (inLookahead) verdict else Verdict.OUT_OF_RANGE
        if (report == state.traced) return
        state.traced = report
        trace.verdict(poi.id, report, d, offAxis, heading, poi.bearing, poi.directionType, lateral, allowed)
    }

    private fun updateHidden(
        poi: AlertPoi, state: PoiState, verdict: Verdict, moving: Boolean, behind: Boolean, dirOk: Boolean,
        heading: Double, headingAcc: Double,
    ) {
        if (poi.bearing == null || poi.directionType == DirectionType.ANY) return
        val mismatch = headingMismatch(poi, heading)
        val was = state.hidden
        // Dim only on a confident mismatch: beyond 45° plus the heading's own error, while moving (§8.1).
        // Undim with hysteresis once the mismatch is under 40°, the rule passes, or the POI is behind.
        if (!state.hidden) {
            if (verdict == Verdict.WRONG_DIRECTION && moving && mismatch > dirTol + headingAcc) state.hidden = true
        } else if (dirOk || mismatch < 40.0 || behind) {
            state.hidden = false
        }
        if (was != state.hidden) trace?.dimmed(poi.id, state.hidden, mismatch, headingAcc)
    }

    private fun markFired(state: PoiState, kind: AlertKind, nowMs: Long, d: Double) {
        when (kind) {
            AlertKind.PASSING -> { state.passingFired = true; state.secondFired = true; state.firstFired = true }
            AlertKind.SECOND -> { state.secondFired = true; state.firstFired = true }
            else -> state.firstFired = true
        }
        state.firedAtMs = nowMs
        state.firedAtD = d
    }

    /** The 2nd stage, held at least 100 m inside the 1st (§11.1 validation, applied at runtime). */
    private fun effectiveSecondTrigger(poi: AlertPoi, state: PoiState): Double? {
        if (!poi.second.enabled) return null
        return minOf(state.secondTriggerM, state.firstTriggerM - 100.0).coerceAtLeast(SECOND_FLOOR_M)
    }

    /**
     * Degrees by which the camera's direction misses ours, 0 for ANY or no bearing (§11 step 3): the
     * smallest, over the bearings it covers (both ways for BOTH), of the difference to our heading or,
     * when [betaAllowed] (the camera is properly ahead), to the bearing from us to the camera.
     */
    private fun directionMismatch(poi: AlertPoi, heading: Double, toPoi: Double, betaAllowed: Boolean): Double {
        val b = poi.bearing?.toDouble() ?: return 0.0
        fun miss(x: Double): Double {
            val h = Geo.absAngleDiffDeg(x, heading)
            return if (betaAllowed) minOf(h, Geo.absAngleDiffDeg(x, toPoi)) else h
        }
        return when (poi.directionType) {
            DirectionType.ANY -> 0.0
            DirectionType.ONE_WAY -> miss(b)
            DirectionType.BOTH -> minOf(miss(b), miss(b + 180.0))
        }
    }

    /** The camera's bearing against our heading alone (for the dimming confidence, §8.1). */
    private fun headingMismatch(poi: AlertPoi, heading: Double): Double {
        val b = poi.bearing?.toDouble() ?: return 0.0
        return when (poi.directionType) {
            DirectionType.ANY -> 0.0
            DirectionType.ONE_WAY -> Geo.absAngleDiffDeg(b, heading)
            DirectionType.BOTH -> minOf(Geo.absAngleDiffDeg(b, heading), Geo.absAngleDiffDeg(b + 180.0, heading))
        }
    }

    private fun emit(poi: AlertPoi, kind: AlertKind, d: Double, fix: Fix, triggerM: Double, staleBelowM: Double?): AlertEvent {
        // In a zone, its cameras are judged against the zone's limit; any other camera (a red-light camera
        // at 30 inside a 50 zone) against its own, and the zone's only when it has none.
        val limit = when {
            inZone && poi.zoneRole != ZoneRole.NONE -> zoneLimitMps ?: poi.speedLimitMps
            inZone -> poi.speedLimitMps ?: zoneLimitMps
            else -> poi.speedLimitMps
        }
        val over = limit != null && fix.speedMps > limit * (1 + overspeedPct)
        // Speech priorities (§12.3): PASSING 100 (never dropped), SECOND and OVERSPEED 50, FIRST 10.
        // Zone enter/exit are 60 and the app's GUIDANCE is 80, between a 2nd and a PASSING.
        val priority = when (kind) {
            AlertKind.PASSING -> 100
            AlertKind.SECOND, AlertKind.OVERSPEED -> 50
            else -> 10
        }
        return AlertEvent(poi.id, kind, d, over, priority, triggerM, staleBelowM).also { trace?.stage(it) }
    }

    private fun zoneEvent(poiId: Long, kind: AlertKind, over: Boolean = false): AlertEvent =
        AlertEvent(poiId, kind, 0.0, over, if (kind == AlertKind.OVERSPEED) 50 else 60).also { trace?.stage(it) }

    private fun handleZonePass(poi: AlertPoi, fix: Fix, out: MutableList<AlertEvent>) {
        when (poi.zoneRole) {
            ZoneRole.ZONE_START -> {
                if (!inZone) {
                    startZone(poi, fix)
                    out += zoneEvent(poi.id, AlertKind.ZONE_ENTER)
                } else {
                    // A camera with no limit (variable or unknown) keeps the zone's.
                    zoneLimitMps = poi.speedLimitMps ?: zoneLimitMps
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
                    out += zoneEvent(poi.id, AlertKind.ZONE_ENTER)
                } else {
                    // A camera with no limit (variable or unknown) keeps the zone's.
                    zoneLimitMps = poi.speedLimitMps ?: zoneLimitMps
                }
                zonePoiId = poi.id
                lastZonePointLat = poi.lat; lastZonePointLon = poi.lon
                if (!ahead) {
                    // The last camera of the zone.
                    inZone = false
                    out += zoneEvent(poi.id, AlertKind.ZONE_EXIT)
                }
            }
            ZoneRole.ZONE_END -> if (inZone) {
                inZone = false
                out += zoneEvent(poi.id, AlertKind.ZONE_EXIT)
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
            if (p.id == exclude || p.zoneRole != ZoneRole.ZONE_POINT || !sameLimit(p.speedLimitMps, limitMps)) return@any false
            val d = Geo.distanceM(fix.lat, fix.lon, p.lat, p.lon)
            // "Ahead" for the zone chain is looser than a stage's 45°: within 60° and inside the 5 km gap, and
            // the camera must still pass the direction rule (§11 step 8, "only same-limit points count").
            if (d <= config.passingM || d > config.zoneGapM) return@any false
            val toP = Geo.bearingDeg(fix.lat, fix.lon, p.lat, p.lon)
            val off = Geo.absAngleDiffDeg(toP, heading)
            off <= 60.0 && directionMismatch(p, heading, toP, off <= config.aheadDeg) <= dirTol
        }
    }

    // A variable or unknown limit (null) matches anything, so a "var" SPECS set stays in its chain.
    private fun sameLimit(a: Double?, b: Double?) = a == null || b == null || abs(a - b) < 0.5

    private fun updateZone(fix: Fix, candidates: Collection<AlertPoi>, heading: Double?, out: MutableList<AlertEvent>) {
        if (!inZone) return
        val sinceLastPoint = Geo.distanceM(fix.lat, fix.lon, lastZonePointLat, lastZonePointLon)
        val fromStart = Geo.distanceM(fix.lat, fix.lon, zoneStartLat, zoneStartLon)
        val chainAhead = zonePointAhead(fix, zoneLimitMps)
        // The zone ends when nothing same-limit is ahead within the gap and none has been passed for 5 km,
        // or after 30 km / 30 min (§11 step 8). A gap with a camera still ahead keeps it open.
        val endByGap = sinceLastPoint > config.zoneGapM && !chainAhead
        val endByLimit = fromStart > config.zoneMaxM || fix.timeMs - zoneStartMs > config.zoneMaxMs
        if (endByGap || endByLimit) {
            inZone = false
            out += zoneEvent(zonePoiId, AlertKind.ZONE_EXIT)
            return
        }
        val limit = zoneLimitMps ?: return
        if (fix.speedMps > limit * (1 + overspeedPct) && fix.timeMs - lastZoneOverspeedMs >= config.zoneOverspeedRepeatMs) {
            lastZoneOverspeedMs = fix.timeMs
            out += zoneEvent(zonePoiId, AlertKind.OVERSPEED, over = true)
        }
    }

    companion object {
        /** 20 yards: the compulsory passing alert (DESIGN.md §11.1; owner, 2026-10-23, was 100 yd). */
        const val PASSING_METRES = 18.288
        const val PASSING_LEAD_S = 1.0
        /** The 2nd alert is never closer than this (§11.1); fixed, not tied to the passing distance. */
        const val SECOND_FLOOR_M = 142.0
    }
}
