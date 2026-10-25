// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The map buttons down the right of the car map (DESIGN.md §8 "Map buttons", "Satellite view") and the
 * limit-change marker's no-shake rule (§8.4a): sizes in dp, the day and night palette, the zoom pill's two
 * tap halves, the satellite pill's label, and a redraw only on a change. Runs on the Robolectric main thread at
 * xhdpi (2 px per dp). Hard rules 3 (no redraw with unchanged content) and 4: the right-hand placement is
 * `MapSurface`'s; here the measured sizes it stacks the column with.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class, qualifiers = "xhdpi")
class MapButtonsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val night = Color.argb(235, 32, 33, 36)
    private val edge = Color.rgb(218, 220, 224)

    @Test fun recenterIsA56DpDisc() {
        val b = MapButtonView(context, R.drawable.ic_action_zoom_in) {}
        RecordingCanvas.frameOf(b)
        assertEquals(112, b.measuredWidth)
        assertEquals(112, b.measuredHeight)
        assertEquals(MapButtonView.SIZE_DP, b.heightDp)
    }

    @Test fun zoomPillIs56By112DpTheWidthOfRecenter() {
        val z = ZoomPillView(context) {}
        RecordingCanvas.frameOf(z)
        assertEquals(112, z.measuredWidth)
        assertEquals(224, z.measuredHeight)
        assertEquals(ZoomPillView.HEIGHT_DP, z.heightDp)
        assertEquals(MapButtonView.SIZE_DP, ZoomPillView.WIDTH_DP)
    }

    @Test fun satellitePillIs150By52Dp() {
        val t = MapTypeButtonView(context)
        RecordingCanvas.frameOf(t)
        assertEquals(300, t.measuredWidth)
        assertEquals(104, t.measuredHeight)
        assertEquals(52, t.heightDp)
    }

    @Test fun sizeIsFixedWhateverTheParentOffers() {
        val b = MapButtonView(context, R.drawable.ic_action_zoom_in) {}
        RecordingCanvas.frameOf(b, w = 500, h = 40)
        assertEquals(112, b.measuredWidth)
        assertEquals(112, b.measuredHeight)
    }

    @Test fun byNightTheDiscIsDarkWithNoEdge() {
        val b = MapButtonView(context, R.drawable.ic_action_zoom_in) {}
        assertTrue("night is the default", b.dark)
        val f = RecordingCanvas.frameOf(b)
        val disc = f.circles.single()
        assertEquals(night, disc.color)
        assertEquals(Paint.Style.FILL, disc.style)
    }

    @Test fun byDayTheDiscIsWhiteWithAHairlineEdge() {
        val b = MapButtonView(context, R.drawable.ic_action_zoom_in) {}.apply { dark = false }
        val f = RecordingCanvas.frameOf(b)
        assertEquals(listOf(Color.WHITE to Paint.Style.FILL, edge to Paint.Style.STROKE), f.circles.map { it.color to it.style })
    }

    @Test fun recenterRunsItsTapAction() {
        var taps = 0
        val b = MapButtonView(context, R.drawable.ic_action_zoom_in) { taps++ }
        b.onTap()
        assertEquals(1, taps)
    }

    @Test fun dayNightRedrawsOnlyOnAChange() {
        val buttons = listOf(MapButtonView(context, R.drawable.ic_action_zoom_in) {}, ZoomPillView(context) {}, MapTypeButtonView(context))
        for (b in buttons) {
            shadowOf(b).clearWasInvalidated()
            b.dark = true
            assertFalse("${b.javaClass.simpleName}: same day/night, no redraw", shadowOf(b).wasInvalidated())
            b.dark = false
            assertTrue("${b.javaClass.simpleName}: day redraws", shadowOf(b).wasInvalidated())
            shadowOf(b).clearWasInvalidated()
            b.dark = false
            assertFalse(shadowOf(b).wasInvalidated())
        }
    }

    @Test fun zoomPillTopHalfZoomsInBottomHalfOut() {
        val steps = ArrayList<Float>()
        val z = ZoomPillView(context) { steps += it }
        RecordingCanvas.frameOf(z)
        assertEquals("zoomIn", z.tap(1f))
        assertEquals("zoomIn", z.tap(111.9f))
        assertEquals("the middle line belongs to zoom out", "zoomOut", z.tap(112f))
        assertEquals("zoomOut", z.tap(223f))
        assertEquals(listOf(1f, 1f, -1f, -1f), steps)
    }

    @Test fun zoomPillHairlineFollowsDayAndNight() {
        val z = ZoomPillView(context) {}
        val nightLine = RecordingCanvas.frameOf(z).ops.single { it.kind == "rect" }
        assertEquals(Color.argb(90, 255, 255, 255), nightLine.color)
        z.dark = false
        val dayLine = RecordingCanvas.frameOf(z).ops.single { it.kind == "rect" }
        assertEquals(edge, dayLine.color)
        // Over the middle 60 % of the width.
        assertEquals(112 * 0.2f, dayLine.x, 0.01f)
        assertEquals(112 * 0.6f, dayLine.size, 0.01f)
    }

    @Test fun satellitePillNamesTheTypeATapSwitchesTo() {
        val t = MapTypeButtonView(context)
        assertEquals("Satellite", RecordingCanvas.frameOf(t).texts.single().text)
        t.satellite = true
        assertEquals("Map", RecordingCanvas.frameOf(t).texts.single().text)
    }

    @Test fun satellitePillLabelColourFollowsDayAndNight() {
        val t = MapTypeButtonView(context)
        val nightFrame = RecordingCanvas.frameOf(t)
        assertEquals(Color.WHITE, nightFrame.texts.single().color)
        assertEquals(night, nightFrame.roundRects.single().color)
        t.dark = false
        val dayFrame = RecordingCanvas.frameOf(t)
        assertEquals(Color.rgb(60, 64, 67), dayFrame.texts.single().color)
        assertEquals(listOf(Color.WHITE, edge), dayFrame.roundRects.map { it.color })
    }

    @Test fun satellitePillRedrawsOnEverySetEvenUnchanged() {
        // Pinned as built: unlike `dark`, the `satellite` setter redraws on an unchanged value. The one caller
        // (`MapSurface.satellite`, guarded in NavSession by `if (surface.satellite != …)`) only sets it on a
        // change, so hard rule 3 holds in practice; reported as a gap.
        val t = MapTypeButtonView(context)
        t.satellite = false
        shadowOf(t).clearWasInvalidated()
        t.satellite = false
        assertTrue(shadowOf(t).wasInvalidated())
    }

    @Test fun limitChangeMarkerPostsOnlyForANewValueOrAMoveOverFifteenMetres() {
        val looper = shadowOf(Looper.getMainLooper())
        val marker = LimitChangeMarker(2f)
        looper.idle()
        marker.show(30, 53.0, -1.5)
        assertFalse("the first value is posted", looper.isIdle)
        looper.idle()
        marker.show(30, 53.00005, -1.5)
        assertTrue("about 5 m of drift: no map call (hard rule 3)", looper.isIdle)
        marker.show(30, 53.0003, -1.5)
        assertFalse("about 33 m: moved", looper.isIdle)
        looper.idle()
        marker.show(40, 53.0003, -1.5)
        assertFalse("a new value at the same place", looper.isIdle)
        looper.idle()
    }

    @Test fun limitChangeMarkerClearsOnNullOrNaNAndOnlyOnce() {
        val looper = shadowOf(Looper.getMainLooper())
        val marker = LimitChangeMarker(2f)
        marker.clear()
        assertTrue("already clear: nothing posted", looper.isIdle)
        marker.show(30, 53.0, -1.5)
        looper.idle()
        marker.show(null, 53.0, -1.5)
        assertFalse(looper.isIdle)
        looper.idle()
        marker.show(30, Double.NaN, -1.5)
        assertTrue("NaN after a clear: nothing to clear again", looper.isIdle)
        marker.show(30, 53.0, -1.5)
        looper.idle()
        marker.show(30, 53.0, Double.NaN)
        assertFalse(looper.isIdle)
        looper.idle()
    }
}
