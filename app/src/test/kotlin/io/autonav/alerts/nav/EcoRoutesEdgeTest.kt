// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN.md §8.3a edge cases beyond `EcoRoutesTest`: how [EcoRoutes.pick] reads odd Routes API replies (no
 * labels, a missing field, an empty list: "Fallback, never a dead end"), the traffic word and delay at their
 * boundaries, the first-queue distance, and Google's polyline decoding on small known vectors.
 */
class EcoRoutesEdgeTest {
    private fun pick(json: String) = EcoRoutes.pick(JSONObject(json))
    private fun routes(json: String) = pick(json) as EcoRoutes.Result.Routes

    @Test fun unavailableReasonsAreTheWordsThePreviewShows() {
        assertEquals(EcoRoutes.Result.Unavailable("no route there"), pick("{}"))
        assertEquals(EcoRoutes.Result.Unavailable("no route token from Google"), pick("""{"routes":[]}"""))
        assertEquals(EcoRoutes.Result.Unavailable("no route token from Google"), pick("""{"routes":[{"routeToken":"","duration":"5s"}]}"""))
        // A token but no readable duration is no route either.
        assertEquals(EcoRoutes.Result.Unavailable("no route token from Google"), pick("""{"routes":[{"routeToken":"t","duration":"soon"}]}"""))
    }

    @Test fun withNoLabelsTheFirstRouteIsBoth() {
        val r = routes("""{"routes":[{"routeToken":"a","duration":"100s","distanceMeters":1000},{"routeToken":"b","duration":"90s","distanceMeters":900}]}""")
        assertTrue(r.same)
        assertEquals("a", r.efficient.token)
        // Today's code fills fastest with the same route when [same] (the `Routes` KDoc says null); the preview reads `same`.
        assertEquals(r.efficient, r.fastest)
    }

    @Test fun anEcoRouteWithoutADefaultLabel() {
        // The first route stands in for the default; when that is the eco route itself, one row.
        val ecoFirst = routes("""{"routes":[{"routeLabels":["FUEL_EFFICIENT"],"routeToken":"eco","duration":"100s"},{"routeToken":"x","duration":"90s"}]}""")
        assertTrue(ecoFirst.same)
        assertEquals("eco", ecoFirst.efficient.token)
        val ecoSecond = routes("""{"routes":[{"routeToken":"x","duration":"90s"},{"routeLabels":["FUEL_EFFICIENT"],"routeToken":"eco","duration":"100s"}]}""")
        assertFalse(ecoSecond.same)
        assertEquals("x", ecoSecond.fastest!!.token)
        assertEquals("eco", ecoSecond.efficient.token)
    }

    @Test fun aBrokenFastestRouteLeavesOnlyTheEcoRow() {
        val r = routes("""{"routes":[{"routeLabels":["DEFAULT_ROUTE"],"routeToken":"fast"},
            {"routeLabels":["FUEL_EFFICIENT"],"routeToken":"eco","duration":"100s"}]}""")
        assertFalse(r.same)
        assertNull(r.fastest)
        assertEquals("eco", r.efficient.token)
    }

    @Test fun fieldsAreReadLeniently() {
        val r = routes("""{"routes":[{"routeLabels":["DEFAULT_ROUTE","FUEL_EFFICIENT"],"routeToken":"t","duration":"1234.7s","staticDuration":"bad"}]}""")
        // Fractional seconds are cut; a missing distance is 0; an unreadable traffic-free time is none.
        assertEquals(EcoRoutes.Route("t", 1234, 0, null, null), r.efficient)
        assertEquals(EcoRoutes.Traffic.UNKNOWN, r.efficient.traffic)
        assertEquals(0, r.efficient.delayMinutes)
        assertNull(r.efficient.minutesToFirstJam)
    }

    @Test fun trafficBoundaries() {
        fun t(seconds: Int, free: Int) = EcoRoutes.Route("t", seconds, 1, free).traffic
        // Under 3 minutes is light whatever the share.
        assertEquals(EcoRoutes.Traffic.LIGHT, t(1179, 1000))
        assertEquals(EcoRoutes.Traffic.SOME, t(1180, 1000))
        // Under 10% is light however many minutes.
        assertEquals(EcoRoutes.Traffic.LIGHT, t(7900, 7200))
        assertEquals(EcoRoutes.Traffic.SOME, t(7920, 7200))
        // 25% exactly is heavy.
        assertEquals(EcoRoutes.Traffic.SOME, t(2499, 2000))
        assertEquals(EcoRoutes.Traffic.HEAVY, t(2500, 2000))
        // Faster than the traffic-free time is no delay; a zero traffic-free time is no share.
        assertEquals(EcoRoutes.Traffic.LIGHT, t(900, 1000))
        assertEquals(EcoRoutes.Traffic.LIGHT, t(600, 0))
    }

    @Test fun delayRoundsToTheNearestMinute() {
        fun d(seconds: Int, free: Int?) = EcoRoutes.Route("t", seconds, 1, free).delayMinutes
        assertEquals(0, d(1029, 1000))
        assertEquals(1, d(1030, 1000))
        assertEquals(1, d(1089, 1000))
        assertEquals(2, d(1090, 1000))
        assertEquals(0, d(900, 1000))
        assertEquals(0, d(5000, null))
    }

    private val polyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    private fun jamRoute(intervals: String, encoded: String = polyline, meters: Int = 1_000_000) = routes(
        """{"routes":[{"routeLabels":["DEFAULT_ROUTE","FUEL_EFFICIENT"],"routeToken":"x","duration":"3600s","distanceMeters":$meters,
        "polyline":{"encodedPolyline":"$encoded"},"travelAdvisory":{"speedReadingIntervals":[$intervals]}}]}""",
    ).efficient

    @Test fun firstJamAtTheStartIsZeroAndTheEarliestJamCounts() {
        val atStart = jamRoute("""{"startPolylinePointIndex":0,"endPolylinePointIndex":1,"speed":"TRAFFIC_JAM"}""")
        assertEquals(0, atStart.firstJamMeters)
        assertEquals(0, atStart.minutesToFirstJam)
        // Listed out of order, the lowest start index is the first queue; SLOW stretches don't count.
        val later = jamRoute("""{"startPolylinePointIndex":2,"speed":"TRAFFIC_JAM"},{"startPolylinePointIndex":0,"speed":"SLOW"},
            {"startPolylinePointIndex":1,"speed":"TRAFFIC_JAM"}""")
        assertEquals(jamRoute("""{"startPolylinePointIndex":1,"speed":"TRAFFIC_JAM"}""").firstJamMeters, later.firstJamMeters)
        assertTrue(later.firstJamMeters!! > 200_000)
    }

    @Test fun aJamPastTheLastPointIsTheWholeLineAndNoLineIsNoJam() {
        val whole = jamRoute("""{"startPolylinePointIndex":2,"speed":"TRAFFIC_JAM"}""").firstJamMeters!!
        assertEquals(whole, jamRoute("""{"startPolylinePointIndex":99,"speed":"TRAFFIC_JAM"}""").firstJamMeters)
        // Only SLOW: no queue.
        assertNull(jamRoute("""{"startPolylinePointIndex":1,"speed":"SLOW"}""").firstJamMeters)
        // A polyline of one point can't place it.
        assertNull(jamRoute("""{"startPolylinePointIndex":1,"speed":"TRAFFIC_JAM"}""", encoded = "_p~iF~ps|U").firstJamMeters)
        // A zero-length route has no minutes to the queue.
        assertNull(jamRoute("""{"startPolylinePointIndex":1,"speed":"TRAFFIC_JAM"}""", meters = 0).minutesToFirstJam)
    }

    @Test fun decodesSmallKnownVectors() {
        assertEquals(emptyList<Pair<Double, Double>>(), EcoRoutes.decode(""))
        assertEquals(listOf(0.0 to 0.0), EcoRoutes.decode("??"))
        // Zig-zag sign: -1e-5 is a single chunk '@', +1e-5 is 'A'.
        assertEquals(listOf(-0.00001 to 0.00001), EcoRoutes.decode("@A"))
        val first = EcoRoutes.decode("_p~iF~ps|U").single()
        assertEquals(38.5, first.first, 1e-9)
        assertEquals(-120.2, first.second, 1e-9)
        // Points are deltas: a second "??" repeats the first point.
        assertEquals(listOf(38.5 to -120.2, 38.5 to -120.2), EcoRoutes.decode("_p~iF~ps|U??"))
    }

    @Test fun aLatitudeWithoutItsLongitudeIsDropped() {
        assertEquals(emptyList<Pair<Double, Double>>(), EcoRoutes.decode("_p~iF"))
        assertEquals(1, EcoRoutes.decode("_p~iF~ps|U_ulL").size)
    }
}
