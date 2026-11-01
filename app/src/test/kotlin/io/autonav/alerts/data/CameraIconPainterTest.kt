// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.data.IconRepository.Emphasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Our camera icons as drawn on the phone (DESIGN.md §7.1): the roundel's white centre and red ring, the
 * tab in the type's colour (orange when temporary), fixed and SPECS as the UK speed-camera sign (white and
 * yellow panel), "*" when no limit is known (never a glyph-only icon; owner 2026-10-25), any size
 * drawn sharp; and `IconRepository` using them for camera markers and rows, honouring the setting and
 * keeping the download bitmaps for "From the download". Robolectric with native graphics.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraIconPainterTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val painter = CameraIconPainter(app)

    // The sign tab at 96 px (2 px a unit): its glyph square is 20 units, centred at (47 − 10·23/24, 47 − 10·19.68/24);
    // glyph x 1.0 is the frame, 2.8 the white gap before the camera body, 4.4 the body left of its big lens, all at glyph y 12.
    private val SIGN_FRAME_X = ((47 - 10 * 23.0 / 24 - 10 + 1.0 * 20 / 24) * 2).toInt()
    private val SIGN_PANEL_X = ((47 - 10 * 23.0 / 24 - 10 + 2.8 * 20 / 24) * 2).toInt()
    private val SIGN_BODY_X = ((47 - 10 * 23.0 / 24 - 10 + 4.4 * 20 / 24) * 2).toInt()
    private val SIGN_PANEL_Y = ((47 - 10 * 19.68 / 24) * 2).toInt()

    private fun near(expected: Int, actual: Int, tol: Int = 40) =
        Math.abs(Color.red(expected) - Color.red(actual)) <= tol && Math.abs(Color.green(expected) - Color.green(actual)) <= tol &&
            Math.abs(Color.blue(expected) - Color.blue(actual)) <= tol && Color.alpha(actual) > 200

    @Test
    fun withALimitTheRoundelIsRedRingedAndTheTabTakesTheTypeColour() {
        val bmp = painter.draw(CameraLook(CameraType.MOBILE, "50"), 96)
        val k = 2 // 96 px / 48 units
        // The ring at radius 17 on the roundel's left edge: (21 − 17, 21).
        assertTrue(near(CameraIconPainter.RING, bmp.getPixel((4 * k), 21 * k)))
        // White between the ring and the digits.
        assertTrue(near(Color.WHITE, bmp.getPixel(10 * k, 13 * k)))
        // Inside the tab, left of the glyph: blue.
        assertTrue(near(CameraType.MOBILE.tab, bmp.getPixel(57, 37 * k)))
        // Outside the roundel and the tab: transparent.
        assertEquals(0, Color.alpha(bmp.getPixel(46 * k, 2 * k)))
    }

    @Test
    fun aTemporaryCameraHasAnOrangeTab() {
        val bmp = painter.draw(CameraLook(CameraType.MOBILE, "40", temporary = true), 96)
        assertTrue(near(CameraIconPainter.TEMPORARY, bmp.getPixel(57, 74)))
        // A temporary sign keeps its black frame and camera on the orange panel.
        val sign = painter.draw(CameraLook(CameraType.FIXED, "40", temporary = true), 96)
        assertTrue(near(CameraIconPainter.TEMPORARY, sign.getPixel(SIGN_PANEL_X, SIGN_PANEL_Y)))
        assertTrue(near(Color.BLACK, sign.getPixel(SIGN_FRAME_X, SIGN_PANEL_Y)))
    }

    @Test
    fun fixedIsTheSpeedCameraSignOnWhiteAndSpecsTheSameSignOnYellow() {
        for ((type, panel) in listOf(CameraType.FIXED to Color.WHITE, CameraType.AVERAGE to 0xFFF2B705.toInt())) {
            val bmp = painter.draw(CameraLook(type, "50"), 96)
            assertTrue(type.name, type.sign)
            // Between the sign's black frame and its camera body: the panel colour.
            assertTrue(type.name, near(panel, bmp.getPixel(SIGN_PANEL_X, SIGN_PANEL_Y)))
            // The frame itself, at the sign's left edge, and the camera body to the right of the gap: black.
            assertTrue(type.name, near(Color.BLACK, bmp.getPixel(SIGN_FRAME_X, SIGN_PANEL_Y)))
            assertTrue(type.name, near(Color.BLACK, bmp.getPixel(SIGN_BODY_X, SIGN_PANEL_Y)))
            // The sign is wider than tall: above it, in the square a plain tab would fill and clear of the roundel, is transparent.
            assertEquals(type.name, 0, Color.alpha(bmp.getPixel(92, 58)))
        }
        // Both types draw the one sign glyph; neither has a glyph of its own any more.
        assertEquals(CameraType.FIXED.glyph, CameraType.AVERAGE.glyph)
    }

    @Test
    fun aRedLightCameraIsTheTrafficLightAloneWithRedAmberAndGreenLampsAndNoRoundel() {
        // Bare layout (owner 2026-10-25): a 46-unit glyph centred in the 48-unit box, 92 px at 96, so 3.83 px a
        // glyph unit from (2, 2). The lamps at glyph (12, 6), (12, 11.2), (12, 16.4) are at x 48 and y 25, 45, 65.
        val bmp = painter.draw(CameraLook(CameraType.REDLIGHT, "30"), 96)
        assertTrue(near(Lamp.RED, bmp.getPixel(48, 25), tol = 30))
        assertTrue(near(Lamp.AMBER, bmp.getPixel(48, 45), tol = 30))
        assertTrue(near(Lamp.GREEN, bmp.getPixel(48, 65), tol = 30))
        // The black housing (glyph x 8), the white backing round it (glyph x 6), nothing beyond (glyph x 3).
        assertTrue(near(Color.BLACK, bmp.getPixel(33, 45)))
        assertTrue(near(Color.WHITE, bmp.getPixel(25, 45)))
        assertEquals(0, Color.alpha(bmp.getPixel(13, 45)))
        // No roundel: where the ring would be is empty, and no limit is drawn.
        assertEquals(0, Color.alpha(bmp.getPixel(8, 42)))
        // A temporary one has an orange backing.
        assertTrue(near(CameraIconPainter.TEMPORARY, painter.draw(CameraLook(CameraType.REDLIGHT, "30", temporary = true), 96).getPixel(25, 45)))
        // Red light + speed keeps its roundel and has the same three lamps; other types none.
        assertTrue(near(CameraIconPainter.RING, painter.draw(CameraLook(CameraType.REDLIGHT_SPEED, "30"), 96).getPixel(8, 42)))
        assertEquals(listOf(Lamp.RED, Lamp.AMBER, Lamp.GREEN), CameraType.REDLIGHT_SPEED.lamps.map { it.color })
        assertTrue(CameraType.MOBILE.lamps.isEmpty())
    }

    @Test
    fun mobileIsBlackOnWhiteAndPendingTheSameGlyphGreyOnWhite() {
        assertEquals(CameraType.MOBILE.glyph, CameraType.PENDING.glyph)
        assertEquals(Color.WHITE, CameraType.MOBILE.tab)
        assertEquals(Color.WHITE, CameraType.PENDING.tab)
        assertEquals(Color.BLACK, CameraType.MOBILE.tabInk)
        assertEquals(0xFF757575.toInt(), CameraType.PENDING.tabInk)
        // The tab's top-left inside corner is white on both.
        for (t in listOf(CameraType.MOBILE, CameraType.PENDING)) assertTrue(t.name, near(Color.WHITE, painter.draw(CameraLook(t, "30"), 96).getPixel(57, 58)))
    }

    @Test
    fun noLimitKnownShowsAStarInTheRoundelWithTheTypeTab() {
        val star = painter.draw(CameraLook(CameraType.REDLIGHT_SPEED), 96)
        assertEquals(CameraLook.NO_LIMIT, CameraLook(CameraType.REDLIGHT_SPEED).limit)
        // The same roundel and tab as with a number: ring on the left edge, the tab's colour in the corner.
        assertTrue(near(CameraIconPainter.RING, star.getPixel(8, 42)))
        assertTrue(near(CameraType.REDLIGHT_SPEED.tab, star.getPixel(58, 62)))
        // The star is dark ink at the roundel's centre (21, 21).
        assertTrue(near(CameraIconPainter.INK, star.getPixel(42, 42)))
    }

    @Test
    fun itDrawsAtTheSizeAsked() {
        for (px in listOf(24, 38, 64, 128)) {
            val bmp = painter.draw(CameraLook(CameraType.MOBILE, "30"), px)
            assertEquals(px, bmp.width)
            assertEquals(px, bmp.height)
        }
    }

    @Test
    fun theRepositoryDrawsCameraMarkersWithOurIconsAndFollowsTheSetting() {
        val icons = IconRepository(app)
        val ours = icons.marker("user:pgw_gatso_30", 30, Emphasis.AHEAD, 60, "GATSO")
        // The marker's inner square is 80%: our roundel's ring shows at its left edge.
        val inner = (60 * 0.8f).toInt()
        val off = (60 - inner) / 2
        assertTrue(near(CameraIconPainter.RING, ours.getPixel(off + (4 * inner / 48), off + 21 * inner / 48)))
        // The same marker is cached until the style changes.
        assertSame(ours, icons.marker("user:pgw_gatso_30", 30, Emphasis.AHEAD, 60, "GATSO"))
        icons.ourCameraIcons = false
        val download = icons.marker("user:pgw_gatso_30", 30, Emphasis.AHEAD, 60, "GATSO")
        assertNotSame(ours, download)
        // With no bitmap on disk the download style falls back to the pin, never our camera ring.
        assertTrue(!near(CameraIconPainter.RING, download.getPixel(off + (4 * inner / 48), off + 21 * inner / 48)))
        icons.ourCameraIcons = true
        assertNotEquals(0, Color.alpha(icons.bitmap(IconRepository.CAMERA_FIXED, 64).getPixel(32, 32)))
    }
}
