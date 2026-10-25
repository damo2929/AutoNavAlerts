// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import io.autonav.alerts.alerts.ActiveAlert
import io.autonav.alerts.alerts.AlertCoordinator
import io.autonav.alerts.alerts.MarkerState
import io.autonav.alerts.alerts.ResolvedPoi
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.alerts.Verdict
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.IconRepository.Emphasis
import io.autonav.alerts.data.PoiEntity
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.StageCols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * The per-fix marker diff (DESIGN.md §8.1; §15.1 "PoiMarkerLayer diff") on a fake map through the layer's
 * `MarkerMap` seam: the first fix adds a marker per POI and a flat arrow per directional camera; an
 * unchanged fix makes no map call; one emphasis change makes exactly one `setIcon`/`setZIndex`; leaving the
 * view removes the markers; a burst of fixes draws only the newest; the 200-marker cap keeps the nearest,
 * arrows counted; facing-away cameras are dimmed with their arrow, never hidden. Redraws are posted to the
 * Robolectric main looper, which the tests run. Hard rules 1 (dimmed, never hidden, arrow kept) and 3 (map
 * calls only for what differs).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PoiMarkerLayerTest {
    private val looper = shadowOf(Looper.getMainLooper())
    private val icons = IconRepository(ApplicationProvider.getApplicationContext<Application>())
    private val labels = IdentityHashMap<BitmapDescriptor, String>()
    private val map = FakeMap()

    private val layer = PoiMarkerLayer(
        icons, BASE,
        toDescriptor = { bmp -> fakeDescriptor(Emphasis.entries.first { icons.marker(ICON, null, it, BASE) === bmp }.name) },
        arrowOf = { dir, emphasis, px -> fakeDescriptor("arrow:$dir:$emphasis:$px") },
    ).also { it.target = map }

    private val set = PoiSetEntity(
        id = 1, name = "Cameras", source = PoiSourceType.FILE, format = "CSV", defaultIconId = ICON,
        firstTemplate = "", secondTemplate = "", passingTemplate = "",
        firstAlert = StageCols(true, DistanceMode.FIXED, 242, null), secondAlert = StageCols(true, DistanceMode.FIXED, 142, null),
        lastUpdated = 0,
    )

    /** A BitmapDescriptor the test can tell apart: the SDK's constructor takes an obfuscated interface, so a proxy stands in. */
    private fun fakeDescriptor(label: String): BitmapDescriptor {
        val ctor = BitmapDescriptor::class.java.constructors.single()
        val type = ctor.parameterTypes.single()
        val handler = java.lang.reflect.InvocationHandler { proxy, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> label
                else -> null
            }
        }
        return (ctor.newInstance(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)) as BitmapDescriptor).also { labels[it] = label }
    }

    /** One drawn marker on the fake map, with what it was last given. */
    internal inner class FakeDrawn(val name: String, var icon: String, var z: Float, var turned: Float, val flat: Boolean) : PoiMarkerLayer.Drawn {
        var removed = false
        override fun remove() { removed = true; map.calls += "remove:$name" }
        override fun setIcon(icon: BitmapDescriptor) { this.icon = labels.getValue(icon); map.calls += "icon:$name" }
        override fun setZIndex(z: Float) { this.z = z; map.calls += "z:$name" }
        override fun setRotation(degrees: Float) { turned = degrees; map.calls += "rot:$name" }
    }

    /** The map: a 0.1° square round the car at zoom 15, every call written down. */
    internal inner class FakeMap : PoiMarkerLayer.MarkerMap {
        val calls = ArrayList<String>()
        val drawn = ArrayList<FakeDrawn>()
        var draws = 0
        var bounds = LatLngBounds(LatLng(CAR_LAT - 0.05, CAR_LON - 0.05), LatLng(CAR_LAT + 0.05, CAR_LON + 0.05))
        override var zoom = 15f
        override fun screenBounds(): LatLngBounds { draws++; return bounds }
        override fun addMarker(options: MarkerOptions): PoiMarkerLayer.Drawn {
            val name = (if (options.isFlat) "arrow" else "icon") + ":" + idAt(options.position)
            calls += "add:$name"
            return FakeDrawn(name, labels.getValue(options.icon!!), options.zIndex, options.rotation, options.isFlat).also { drawn += it }
        }
        fun live(): List<FakeDrawn> = drawn.filter { !it.removed }
        fun live(name: String): FakeDrawn = live().single { it.name == name }
    }

    private val pois = HashMap<Long, PoiEntity>()
    private fun idAt(p: LatLng): Long = pois.values.single { it.lat == p.latitude && it.lon == p.longitude }.id

    /** A camera [northM] metres north of the car (and [eastM] east). */
    private fun poi(id: Long, northM: Double, dir: DirectionType = DirectionType.ANY, bearing: Float? = null, eastM: Double = 0.0, s: PoiSetEntity = set): ResolvedPoi {
        val p = PoiEntity(
            id = id, setId = s.id, categoryId = null, lat = CAR_LAT + northM / 111_195.0, lon = CAR_LON + eastM / (111_195.0 * Math.cos(Math.toRadians(CAR_LAT))),
            name = null, speedLimit = null, speedUnit = SpeedUnit.MPH, directionType = dir, bearing = bearing, gridCell = 0,
        )
        pois[id] = p
        return AlertCoordinator.resolve(p, s, null)
    }

    private val fix = Fix(CAR_LAT, CAR_LON, 20.0, 0.0, 1_000L)

    private fun draw(state: MarkerState) { layer.redraw(state); looper.idle() }

    @Test fun firstFixAddsOneMarkerPerPoiAndAFlatArrowPerDirectionalCamera() {
        val nearby = listOf(
            poi(1, 100.0), poi(2, 200.0), poi(3, 300.0, DirectionType.ONE_WAY, 90f),
            poi(4, 400.0, DirectionType.BOTH, 270f), poi(5, 500.0, DirectionType.ONE_WAY, null), poi(6, 600.0, DirectionType.ANY, 45f),
        )
        draw(MarkerState(nearby, lastFix = fix))
        assertEquals(8, map.calls.size)
        assertTrue(map.calls.all { it.startsWith("add:") })
        assertEquals(setOf("arrow:3", "arrow:4"), map.live().filter { it.flat }.map { it.name }.toSet())
        val arrow3 = map.live("arrow:3")
        assertEquals(90f, arrow3.turned, 0f)
        assertEquals("arrow:ONE_WAY:OTHER:${(BASE * PoiMarkerLayer.ARROW_SCALE).toInt()}", arrow3.icon)
        assertEquals("arrow:BOTH:OTHER:${(BASE * PoiMarkerLayer.ARROW_SCALE).toInt()}", map.live("arrow:4").icon)
        // The arrow sits half a level under its own icon.
        assertEquals(map.live("icon:3").z - 0.5f, arrow3.z, 0f)
        assertEquals(1f, map.live("icon:1").z, 0f)
        assertTrue("no verdict yet: OTHER (60 %)", map.live().filter { !it.flat }.all { it.icon == "OTHER" })
    }

    @Test fun anUnchangedFixMakesNoMapCalls() {
        val nearby = listOf(poi(1, 100.0), poi(2, 200.0, DirectionType.ONE_WAY, 10f))
        draw(MarkerState(nearby, verdicts = mapOf(1L to Verdict.AHEAD_OK), lastFix = fix))
        map.calls.clear()
        repeat(5) { i -> draw(MarkerState(nearby, verdicts = mapOf(1L to Verdict.AHEAD_OK), lastFix = fix.copy(timeMs = 2_000L + i))) }
        assertEquals("five fixes, nothing changed (hard rule 3)", emptyList<String>(), map.calls)
        assertEquals(6, map.draws)
    }

    @Test fun oneEmphasisChangeMakesExactlyOneSetIconAndSetZIndex() {
        val nearby = listOf(poi(1, 100.0), poi(2, 200.0), poi(3, 300.0))
        draw(MarkerState(nearby, lastFix = fix))
        map.calls.clear()
        draw(MarkerState(nearby, verdicts = mapOf(2L to Verdict.AHEAD_OK), lastFix = fix))
        assertEquals(listOf("icon:icon:2", "z:icon:2"), map.calls)
        assertEquals("AHEAD", map.live("icon:2").icon)
    }

    @Test fun anEmphasisChangeOnADirectionalCameraRedrawsItsArrowToo() {
        val nearby = listOf(poi(1, 100.0, DirectionType.ONE_WAY, 0f))
        draw(MarkerState(nearby, lastFix = fix))
        map.calls.clear()
        draw(MarkerState(nearby, verdicts = mapOf(1L to Verdict.AHEAD_OK), lastFix = fix))
        assertEquals(listOf("icon:icon:1", "z:icon:1", "icon:arrow:1", "z:arrow:1"), map.calls)
        assertTrue(map.live("arrow:1").icon.contains(":AHEAD:"))
    }

    @Test fun aFacingAwayCameraIsDimmedWithItsArrowNeverHidden() {
        val nearby = listOf(poi(1, 150.0, DirectionType.ONE_WAY, 180f), poi(2, 250.0, DirectionType.ONE_WAY, 0f))
        draw(MarkerState(nearby, verdicts = mapOf(1L to Verdict.WRONG_DIRECTION, 2L to Verdict.AHEAD_OK), lastFix = fix))
        assertEquals("FACING_AWAY", map.live("icon:1").icon)
        assertTrue(map.live("arrow:1").icon.startsWith("arrow:ONE_WAY:FACING_AWAY"))
        assertEquals(180f, map.live("arrow:1").turned, 0f)
        assertEquals("AHEAD", map.live("icon:2").icon)
        // Flipping to facing away later changes the look, never removes it (hard rule 1).
        map.calls.clear()
        draw(MarkerState(nearby, verdicts = mapOf(1L to Verdict.WRONG_DIRECTION, 2L to Verdict.WRONG_DIRECTION), lastFix = fix))
        assertFalse(map.calls.any { it.startsWith("remove") })
        assertEquals("FACING_AWAY", map.live("icon:2").icon)
        assertEquals(4, map.live().size)
    }

    @Test fun theEnginesFacingAwaySetDimsAPoiWithNoVerdict() {
        val nearby = listOf(poi(1, 150.0, DirectionType.ONE_WAY, 180f), poi(2, 250.0))
        draw(MarkerState(nearby, facingAway = setOf(1L), verdicts = mapOf(2L to Verdict.BEHIND), lastFix = fix))
        assertEquals("FACING_AWAY", map.live("icon:1").icon)
        assertEquals("BEHIND is drawn as OTHER", "OTHER", map.live("icon:2").icon)
    }

    @Test fun theAlertedCameraIsActiveOnTopForFifteenSeconds() {
        val nearby = listOf(poi(1, 150.0, DirectionType.ONE_WAY, 0f), poi(2, 250.0))
        val now = System.currentTimeMillis()
        val alert = ActiveAlert(1, AlertKind.FIRST, "", "", ICON, 150.0, null, 1, now)
        draw(MarkerState(nearby, verdicts = mapOf(1L to Verdict.AHEAD_OK), active = alert, lastFix = fix))
        assertEquals("ACTIVE", map.live("icon:1").icon)
        assertEquals(10f, map.live("icon:1").z, 0f)
        assertEquals(9.5f, map.live("arrow:1").z, 0f)
        assertEquals("the arrow is sized with the 1.3× icon", "arrow:ONE_WAY:ACTIVE:${((BASE * 1.3f).toInt() * PoiMarkerLayer.ARROW_SCALE).toInt()}", map.live("arrow:1").icon)
        // An alert older than 15 s is no longer ACTIVE: back to the verdict's look.
        draw(MarkerState(nearby, verdicts = mapOf(1L to Verdict.AHEAD_OK), active = alert.copy(timeMs = now - 16_000), lastFix = fix))
        assertEquals("AHEAD", map.live("icon:1").icon)
        assertEquals(1f, map.live("icon:1").z, 0f)
    }

    @Test fun leavingTheViewRemovesTheMarkerAndItsArrowAndComingBackAddsThemAgain() {
        val a = poi(1, 100.0, DirectionType.ONE_WAY, 0f)
        val b = poi(2, 200.0)
        draw(MarkerState(listOf(a, b), lastFix = fix))
        map.calls.clear()
        draw(MarkerState(listOf(b), lastFix = fix))
        assertEquals(setOf("remove:icon:1", "remove:arrow:1"), map.calls.toSet())
        assertEquals(listOf("icon:2"), map.live().map { it.name })
        map.calls.clear()
        draw(MarkerState(listOf(a, b), lastFix = fix))
        // As built there is no marker pool (§8.1 step 4): a returning POI gets new markers.
        assertEquals(setOf("add:icon:1", "add:arrow:1"), map.calls.toSet())
    }

    @Test fun markersOutsideTheScreenBoundsOrBeyondThreeKilometresAreNotDrawn() {
        // The screen spans ±0.05° (≈ 5.5 km north): the 3 km clip round the car still applies, and the 10 %
        // margin is taken on the clipped 6 km span, so the edge is at 3.6 km.
        val near = poi(1, 2_500.0)
        val inMargin = poi(4, 3_400.0)
        val far = poi(2, 3_800.0)
        val offScreen = poi(3, 200.0, eastM = 8_000.0)
        draw(MarkerState(listOf(near, far, offScreen, inMargin), lastFix = fix))
        assertEquals(setOf("icon:1", "icon:4"), map.live().map { it.name }.toSet())
    }

    @Test fun theTenPercentMarginKeepsAnEdgeMarker() {
        map.bounds = LatLngBounds(LatLng(CAR_LAT - 0.01, CAR_LON - 0.01), LatLng(CAR_LAT + 0.01, CAR_LON + 0.01))
        // 0.01° is about 1112 m; the margin adds 10 % of the 0.02° span (about 222 m) beyond it.
        draw(MarkerState(listOf(poi(1, 1_250.0), poi(2, 1_400.0)), lastFix = fix))
        assertEquals(listOf("icon:1"), map.live().map { it.name })
    }

    @Test fun hiddenDisabledAndTooFarOutSetsDrawNothing() {
        val hidden = set.copy(id = 2, showOnMap = false)
        val off = set.copy(id = 3, enabled = false)
        val zoomed = set.copy(id = 4, minZoom = 16f)
        draw(MarkerState(listOf(poi(1, 100.0, s = hidden), poi(2, 200.0, s = off), poi(3, 300.0, s = zoomed), poi(4, 400.0)), lastFix = fix))
        assertEquals(listOf("icon:4"), map.live().map { it.name })
        map.zoom = 16f
        draw(MarkerState(listOf(poi(3, 300.0, s = zoomed), poi(4, 400.0)), lastFix = fix))
        assertEquals(setOf("icon:3", "icon:4"), map.live().map { it.name }.toSet())
    }

    @Test fun aBurstOfFixesFasterThanTheDrawsDrawsOnlyTheNewest() {
        layer.redraw(MarkerState(listOf(poi(1, 100.0)), lastFix = fix))
        layer.redraw(MarkerState(listOf(poi(2, 200.0)), lastFix = fix))
        layer.redraw(MarkerState(listOf(poi(3, 300.0), poi(4, 400.0)), lastFix = fix))
        looper.idle()
        assertEquals("one main-thread draw for the burst", 1, map.draws)
        assertEquals(listOf("add:icon:3", "add:icon:4"), map.calls)
        // The next fix after the draw is drawn on its own.
        layer.redraw(MarkerState(listOf(poi(3, 300.0)), lastFix = fix))
        looper.idle()
        assertEquals(2, map.draws)
    }

    @Test fun theCapKeepsTheNearestTwoHundredMarkers() {
        // 250 plain POIs, inserted farthest first: only the nearest 200 are drawn.
        draw(MarkerState((250 downTo 1).map { poi(it.toLong(), it * 10.0) }, lastFix = fix))
        assertEquals(PoiMarkerLayer.MAX_MARKERS, map.live().size)
        assertEquals((1L..200L).toSet(), map.live().map { it.name.substringAfter(':').toLong() }.toSet())
    }

    @Test fun arrowsCountInsideTheCap() {
        draw(MarkerState((150 downTo 1).map { poi(it.toLong(), it * 10.0, DirectionType.ONE_WAY, 0f) }, lastFix = fix))
        assertEquals(200, map.live().size)
        assertEquals("100 cameras with their arrows", (1L..100L).toSet(), map.live().filter { it.flat }.map { it.name.substringAfter(':').toLong() }.toSet())
    }

    @Test fun aChangedBearingTurnsTheArrowThereAndANewBearingAddsOne() {
        draw(MarkerState(listOf(poi(1, 100.0, DirectionType.ONE_WAY, 10f), poi(2, 200.0)), lastFix = fix))
        map.calls.clear()
        draw(MarkerState(listOf(poi(1, 100.0, DirectionType.ONE_WAY, 40f), poi(2, 200.0, DirectionType.BOTH, 90f)), lastFix = fix))
        assertEquals(listOf("rot:arrow:1", "add:arrow:2"), map.calls)
        assertEquals(40f, map.live("arrow:1").turned, 0f)
        map.calls.clear()
        draw(MarkerState(listOf(poi(1, 100.0, DirectionType.ONE_WAY, 40f), poi(2, 200.0, DirectionType.BOTH, null)), lastFix = fix))
        assertEquals("a bearing gone takes its arrow with it", listOf("remove:arrow:2"), map.calls)
    }

    @Test fun aNewMapStartsAfreshAndClearRemovesEverything() {
        val nearby = listOf(poi(1, 100.0, DirectionType.ONE_WAY, 0f), poi(2, 200.0))
        draw(MarkerState(nearby, lastFix = fix))
        val second = FakeMap()
        layer.target = second
        draw(MarkerState(nearby, lastFix = fix))
        assertEquals("the new map gets every marker again", 3, second.calls.count { it.startsWith("add:") })
        layer.clear()
        looper.idle()
        assertTrue(second.live().isEmpty())
    }

    @Test fun noMapDrawsNothingAndDoesNotThrow() {
        layer.target = null
        draw(MarkerState(listOf(poi(1, 100.0)), lastFix = fix))
        assertEquals(0, map.draws)
    }

    @Test fun beforeTheFirstFixThereIsNoRangeClip() {
        draw(MarkerState(listOf(poi(1, 4_000.0)), lastFix = null))
        assertEquals(listOf("icon:1"), map.live().map { it.name })
    }

    companion object {
        const val ICON = IconRepository.CAMERA_FIXED
        const val BASE = 36
        const val CAR_LAT = 53.38
        const val CAR_LON = -1.47
    }
}
