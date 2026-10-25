// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * The alert banner on the car map (DESIGN.md §12.1): 96 dp high; icon, title and subtitle drawn once per
 * alert into a hardware layer; shown for its time (8 s, 5 s when passing) and restarted by each new stage;
 * the same POI updated in place with no fade; a tap or the time running out hides it with the 150 ms fade
 * and frees the layer. Runs on the Robolectric main looper (paused), whose clock the tests advance. Hard
 * rules 2 (the one alert surface on the map, no buttons) and 3 (no redraw on a timer while the bar runs).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlertBannerViewTest {
    // Attached to a window, as on the car surface: a detached view's postDelayed (the hide timer) never runs.
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val banner = AlertBannerView(activity).also { activity.setContentView(it) }
    private val looper = shadowOf(Looper.getMainLooper())
    private val icon: Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)

    /** Frames follow the looper's clock, so a running 8 s bar doesn't jump the clock to its end. */
    @Before fun pauseFrames() = ShadowChoreographer.setPaused(true)

    /** The 150 ms fade plus the frame or two Robolectric's render-thread animator takes to start. */
    private val fadeSettleMs = 400L

    /** Moves the clock on by [ms] a 16 ms frame at a time: with paused frames, each step draws one. */
    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) { val step = minOf(16L, left); looper.idleFor(Duration.ofMillis(step)); left -= step }
    }

    private fun frame(widthPx: Int = 1200): RecordingCanvas = RecordingCanvas.frameOf(banner, w = widthPx)

    @Test fun startsHiddenWithNothingShowing() {
        assertEquals(View.GONE, banner.visibility)
        assertNull(banner.showing)
        assertEquals(0f, banner.alpha, 0f)
    }

    @Test fun isAlways96DpHigh() {
        frame(widthPx = 900)
        assertEquals(192, banner.measuredHeight)
        assertEquals(900, banner.measuredWidth)
        assertEquals(640, AlertBannerView.MAX_WIDTH_DP)
    }

    @Test fun showFadesInIntoAHardwareLayer() {
        banner.show(7, icon, "Speed camera 30 mph", "300 yd · 30 mph", 8_000)
        assertEquals(View.VISIBLE, banner.visibility)
        assertEquals(7L, banner.showing)
        assertEquals(View.LAYER_TYPE_HARDWARE, banner.layerType)
        advance(fadeSettleMs)
        assertEquals(1f, banner.alpha, 0.001f)
    }

    @Test fun hidesItselfWhenItsTimeRunsOut() {
        banner.show(7, icon, "Speed camera", "", 8_000)
        advance(7_900)
        assertEquals(7L, banner.showing)
        advance(150)
        assertNull("gone at 8 s", banner.showing)
        advance(fadeSettleMs)
        assertEquals(View.GONE, banner.visibility)
        assertEquals("the texture is freed while hidden", View.LAYER_TYPE_NONE, banner.layerType)
    }

    @Test fun passingAlertsStayFiveSeconds() {
        banner.show(7, icon, "Passing camera", "", 5_000)
        advance(4_950)
        assertEquals(7L, banner.showing)
        advance(100)
        assertNull(banner.showing)
    }

    @Test fun aLaterStageForTheSamePoiUpdatesInPlaceAndRestartsTheTime() {
        banner.show(7, icon, "Camera in 500 yards", "500 yd · 30 mph", 8_000)
        advance(6_000)
        banner.show(7, icon, "Camera in 150 yards", "150 yd · 30 mph", 8_000)
        // No fade: the text changes with the banner fully up.
        assertEquals(1f, banner.alpha, 0.001f)
        assertEquals(listOf("Camera in 150 yards", "150 yd · 30 mph"), frame().texts.map { it.text })
        advance(4_000)
        assertEquals("the second stage restarted the 8 s", 7L, banner.showing)
        advance(4_100)
        assertNull(banner.showing)
    }

    @Test fun aDifferentPoiTakesTheBannerOver() {
        banner.show(7, icon, "First camera", "", 8_000)
        advance(1_000)
        banner.show(9, icon, "Second camera", "", 8_000)
        assertEquals(9L, banner.showing)
        assertEquals("Second camera", frame().texts.first().text)
    }

    @Test fun tapHideFadesOutAtOnceAndIsSafeTwice() {
        banner.show(7, icon, "Camera", "", 8_000)
        advance(500)
        banner.hide()
        assertNull(banner.showing)
        advance(fadeSettleMs)
        assertEquals(View.GONE, banner.visibility)
        banner.hide()
        advance(fadeSettleMs)
        assertEquals(View.GONE, banner.visibility)
        // The old timer was cancelled: a new alert isn't cut short by it.
        banner.show(8, icon, "Next", "", 8_000)
        advance(7_000)
        assertEquals(8L, banner.showing)
    }

    @Test fun drawsTheCardTheIconTheTitleAndTheSubtitle() {
        banner.show(7, icon, "Speed camera", "300 yd · 30 mph", 8_000)
        val f = frame()
        val card = f.roundRects.first()
        assertEquals(Color.argb(235, 32, 33, 36), card.color)
        assertEquals("16 dp corners", 32f, card.size, 0.01f)
        assertEquals(1, f.bitmaps.size)
        val (title, sub) = f.texts
        assertEquals("Speed camera", title.text)
        assertEquals(Color.WHITE, title.color)
        // Pinned as built, reported: §12.1 says 26 dp and 20 dp, but inside `TextPaint().apply { … }` the name
        // `density` is TextPaint's own field (1.0), not the view's, so the sizes are 26 px and 20 px at any density.
        assertEquals(26f, title.size, 0.01f)
        assertEquals("300 yd · 30 mph", sub.text)
        assertEquals(Color.rgb(200, 202, 206), sub.color)
        assertEquals(20f, sub.size, 0.01f)
        assertTrue("the text sits to the right of the icon", title.x > f.bitmaps[0].x + f.bitmaps[0].size)
        assertEquals("title and subtitle are left-aligned to each other", title.x, sub.x, 0.01f)
    }

    @Test fun anEmptySubtitleAndNoIconDrawOnlyTheTitle() {
        banner.show(7, null, "Hazard", "", 8_000)
        val f = frame()
        assertEquals(listOf("Hazard"), f.texts.map { it.text })
        assertTrue(f.bitmaps.isEmpty())
    }

    @Test fun theIconAndTextAreCentredTogether() {
        banner.show(7, null, "Hazard", "", 8_000)
        val narrow = frame(widthPx = 600).texts.single().x
        val wide = frame(widthPx = 1200).texts.single().x
        // The block moves by half the extra width: centred, not left-aligned.
        assertEquals(300f, wide - narrow, 1f)
    }

    @Test fun aLongTitleIsEllipsisedToTheWidth() {
        val long = "Average speed check zone for the next twelve miles on the A61 northbound"
        banner.show(7, icon, long, "", 8_000)
        val shown = frame(widthPx = 500).texts.single().text!!
        assertTrue(shown.length < long.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test fun theProgressBarRunsWithoutRedrawingTheBanner() {
        banner.show(7, icon, "Camera", "", 8_000)
        frame()
        advance(300)
        shadowOf(banner).clearWasInvalidated()
        advance(3_000)
        assertFalse("nothing redraws the card while the bar runs (hard rule 3)", shadowOf(banner).wasInvalidated())
        // The bar itself is animating towards full.
        val bar = banner.getChildAt(1)
        assertTrue(bar.scaleX > 0f)
        assertEquals(0f, bar.pivotX, 0f)
    }

    @Test fun hasNoButtonsOnlyTheBarAndItsTrack() {
        assertEquals(2, banner.childCount)
        for (i in 0 until banner.childCount) assertFalse(banner.getChildAt(i).isClickable)
    }
}
