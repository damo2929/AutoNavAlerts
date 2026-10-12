// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoRoutesTest {
    @Test fun offersTheFastestAndTheEfficientRoute() {
        val r = EcoRoutes.pick(JSONObject("""{"routes":[
            {"routeLabels":["DEFAULT_ROUTE"],"routeToken":"fast","duration":"3600s","distanceMeters":66000},
            {"routeLabels":["FUEL_EFFICIENT"],"routeToken":"eco","duration":"3900s","distanceMeters":61000}]}"""))
        r as EcoRoutes.Result.Routes
        assertFalse(r.same)
        assertEquals(EcoRoutes.Route("fast", 3600, 66000), r.fastest)
        assertEquals(EcoRoutes.Route("eco", 3900, 61000), r.efficient)
    }

    @Test fun oneRouteWhenTheFastestIsAlsoTheMostEfficient() {
        val r = EcoRoutes.pick(JSONObject("""{"routes":[
            {"routeLabels":["DEFAULT_ROUTE","FUEL_EFFICIENT"],"routeToken":"both","duration":"1200s","distanceMeters":20000}]}"""))
        r as EcoRoutes.Result.Routes
        assertTrue(r.same)
        assertEquals("both", r.efficient.token)
    }

    @Test fun trafficLevelFromTheDelay() {
        assertEquals(EcoRoutes.Traffic.LIGHT, EcoRoutes.Route("t", 3700, 1, 3600).traffic)   // +2 min
        assertEquals(EcoRoutes.Traffic.SOME, EcoRoutes.Route("t", 4200, 1, 3600).traffic)    // +10 min, 17%
        assertEquals(EcoRoutes.Traffic.HEAVY, EcoRoutes.Route("t", 4800, 1, 3600).traffic)   // +20 min, 33%
        assertEquals(EcoRoutes.Traffic.UNKNOWN, EcoRoutes.Route("t", 4800, 1, null).traffic)
        assertEquals(20, EcoRoutes.Route("t", 4800, 1, 3600).delayMinutes)
        val r = EcoRoutes.pick(JSONObject("""{"routes":[{"routeLabels":["DEFAULT_ROUTE","FUEL_EFFICIENT"],"routeToken":"x","duration":"4800s","staticDuration":"3600s","distanceMeters":5}]}""")) as EcoRoutes.Result.Routes
        assertEquals(3600, r.efficient.freeFlowSeconds)
    }

    @Test fun decodesGooglesPolyline() {
        // Google's documented example.
        val pts = EcoRoutes.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].first, 1e-5); assertEquals(-120.2, pts[0].second, 1e-5)
        assertEquals(43.252, pts[2].first, 1e-5); assertEquals(-126.453, pts[2].second, 1e-5)
    }

    @Test fun projectedQueueFromTheFirstJam() {
        val r = EcoRoutes.pick(JSONObject("""{"routes":[{"routeLabels":["DEFAULT_ROUTE","FUEL_EFFICIENT"],"routeToken":"x",
            "duration":"3600s","staticDuration":"3000s","distanceMeters":2000000,
            "polyline":{"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"},
            "travelAdvisory":{"speedReadingIntervals":[{"startPolylinePointIndex":0,"endPolylinePointIndex":1,"speed":"NORMAL"},
              {"startPolylinePointIndex":1,"endPolylinePointIndex":2,"speed":"TRAFFIC_JAM"}]}}]}""")) as EcoRoutes.Result.Routes
        val jam = r.efficient.firstJamMeters!!
        assertTrue("jam at $jam m", jam in 240_000..265_000) // first leg of the example, (38.5,-120.2)→(40.7,-120.95): ~252 km
        assertEquals((jam.toDouble() / 2_000_000 * 3600 / 60).toInt(), r.efficient.minutesToFirstJam)
        val none = EcoRoutes.pick(JSONObject("""{"routes":[{"routeLabels":["DEFAULT_ROUTE"],"routeToken":"x","duration":"60s","distanceMeters":5}]}""")) as EcoRoutes.Result.Routes
        assertEquals(null, none.efficient.firstJamMeters)
    }

    @Test fun noRoutesIsUnavailable() {
        assertTrue(EcoRoutes.pick(JSONObject("{}")) is EcoRoutes.Result.Unavailable)
        assertTrue(EcoRoutes.pick(JSONObject("""{"routes":[{"routeLabels":["DEFAULT_ROUTE"],"duration":"5s"}]}""")) is EcoRoutes.Result.Unavailable)
    }
}
