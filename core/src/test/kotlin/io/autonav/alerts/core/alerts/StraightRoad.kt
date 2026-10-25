// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.ZoneRole
import kotlin.math.cos

/**
 * Test-only flat road near 53°N for the engine tests (DESIGN.md §11, §15.1): positions in metres
 * north/east of the origin, a car driving due north along east = 0, and cameras built with FIXED
 * stages so the trigger distances are exact. The fixtures are made up; no PGW data (hard rule 6).
 */
object StraightRoad {
    const val BASE_LAT = 53.0
    const val METRES_PER_DEG_LAT = 111_195.0
    fun latAt(northM: Double) = BASE_LAT + northM / METRES_PER_DEG_LAT
    fun lonAt(eastM: Double) = eastM / (METRES_PER_DEG_LAT * cos(Math.toRadians(BASE_LAT)))

    /** 30 mph in m/s, the usual test speed: above the 2 m/s moving gate, below the 41 mph passing floor. */
    val MPS_30 = 30.0 * Geo.MPS_PER_MPH

    /** A camera [northM] up the road and [eastM] to the side; fixed 800 m / 300 m stages unless given. */
    fun camera(
        id: Long = 1, northM: Double = 500.0, eastM: Double = 0.0,
        dir: DirectionType = DirectionType.ONE_WAY, bearing: Float? = 0f,
        first: AlertStage = AlertStage(true, DistanceMode.FIXED, 800),
        second: AlertStage = AlertStage(true, DistanceMode.FIXED, 300),
        sound: Boolean = true, zone: ZoneRole = ZoneRole.NONE, limitMph: Int? = 30, sideToleranceM: Double? = null,
    ) = AlertPoi(id, latAt(northM), lonAt(eastM), dir, bearing, limitMph?.let { it * Geo.MPS_PER_MPH }, zone, first, second, sound, sideToleranceM)

    /** One fix at [northM] on the road, heading [heading], at [mps]. */
    fun fix(northM: Double, mps: Double = MPS_30, heading: Double? = 0.0, t: Long = 0, eastM: Double = 0.0, acc: Double? = null) =
        Fix(latAt(northM), lonAt(eastM), mps, heading, t, headingAccuracyDeg = acc)

    /** The route matcher that says every POI is on the route. */
    val ON_ROUTE = RouteMatcher { _, _ -> 0.0 }

    /** Drives north from [fromM] to [toM] at [mps], one fix per [stepMs], collecting events with the fix time of each. */
    fun drive(
        engine: AlertEngine, pois: List<AlertPoi>, fromM: Double, toM: Double, mps: Double = MPS_30,
        stepMs: Long = 200, startMs: Long = 0, route: RouteMatcher? = null, onFix: (Fix, List<AlertEvent>) -> Unit = { _, _ -> },
    ): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        var pos = fromM
        var t = startMs
        while (pos <= toM) {
            val f = fix(pos, mps, 0.0, t)
            val events = engine.onFix(f, pois, route)
            onFix(f, events)
            out += events
            pos += mps * stepMs / 1000.0
            t += stepMs
        }
        return out
    }

    fun List<AlertEvent>.kinds() = map { it.kind }
}
