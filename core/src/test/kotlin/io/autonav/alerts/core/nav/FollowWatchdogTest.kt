// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.nav.FollowWatchdog.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The follow watchdog's rules (DESIGN.md §8). */
class FollowWatchdogTest {
    private fun FollowWatchdog.at(
        now: Long, d: Float?, following: Boolean = true, guiding: Boolean = false, preview: Boolean = false, age: Long? = 100,
    ) = check(now, following, guiding, preview, age, d)

    @Test
    fun inPlaceDecidesWhenKnownTheDistanceOnlyBefore() {
        val w = FollowWatchdog()
        // Free drive with a frame: in place beats a big distance (the tilted camera's target is far ahead).
        assertEquals(Decision.None("in place"), w.check(0, true, false, false, 100, 2_000f, inPlace = true))
        assertEquals(Decision.Refollow(100f), w.check(0, true, false, false, 100, 100f, inPlace = false))
        assertEquals(Decision.None("in place"), w.check(1_000, true, false, false, 100, 100f, inPlace = true))
        assertEquals(0, w.misses)
        // No frame yet: free drive falls back to the distance, guidance waits.
        assertEquals(Decision.None("within 500 m"), w.check(2_000, true, false, false, 100, 100f, inPlace = null))
        assertEquals(Decision.None("guiding, no frame"), w.check(3_000, true, true, false, 100, 100f, inPlace = null))
        // The guidance camera's target is hundreds of metres ahead: a big distance alone is no miss.
        assertEquals(Decision.None("in place"), w.check(0, true, true, false, 100, 4_000f, inPlace = true))
        assertEquals(Decision.None("guiding, no frame"), w.check(1_000, true, true, false, 100, 4_000f, inPlace = null))
        assertEquals(0, w.misses)
        // Off the padded map: follow again, then the usual 5 s count to a rebuild.
        assertEquals(Decision.Refollow(300f), w.check(2_000, true, true, false, 100, 300f, inPlace = false))
        assertTrue(w.check(3_000, true, true, false, 100, 300f, inPlace = false) is Decision.None)
        assertEquals(Decision.Refollow(300f), w.check(7_000, true, true, false, 100, 300f, inPlace = false))
        assertEquals(Decision.Rebuild(300f), w.check(12_000, true, true, false, 100, 300f, inPlace = false))
        // Back on screen clears the count; the preview still stands the watchdog down.
        w.check(13_000, true, true, false, 100, 300f, inPlace = false)
        assertEquals(Decision.None("in place"), w.check(14_000, true, true, false, 100, 300f, inPlace = true))
        assertEquals(0, w.misses)
        assertEquals(Decision.None("preview"), w.check(15_000, true, true, true, 100, 300f, inPlace = false))
    }

    @Test
    fun noActionWhileGuidingOnThePreviewOrWithoutAnSdkFix() {
        val w = FollowWatchdog()
        assertEquals(Decision.None("guiding, no frame"), w.at(0, 4_000f, guiding = true))
        assertEquals(Decision.None("preview"), w.at(1_000, 4_000f, preview = true))
        assertEquals(Decision.None("no SDK fix"), w.at(2_000, null, age = null))
        assertEquals(Decision.None("no SDK fix"), w.at(2_000, 4_000f, age = null))
        assertEquals(Decision.None("not following"), w.at(3_000, 4_000f, following = false))
        assertTrue(w.at(4_000, 4_000f, age = 30_000) is Decision.None)
        assertEquals(0, w.misses)
    }

    @Test
    fun refollowOnTheFirstMissRebuildOnTheThirdFiveSecondsApart() {
        val w = FollowWatchdog()
        assertEquals(Decision.Refollow(3_771f), w.at(0, 3_771f))
        assertEquals(1, w.misses)
        assertTrue(w.at(1_000, 3_771f) is Decision.None) // too soon for the next count
        assertTrue(w.at(4_999, 3_771f) is Decision.None)
        assertEquals(Decision.Refollow(3_700f), w.at(5_000, 3_700f))
        assertTrue(w.at(9_000, 3_700f) is Decision.None)
        assertEquals(Decision.Rebuild(3_650f), w.at(10_000, 3_650f))
        assertEquals(0, w.misses) // the count starts over after a rebuild
        assertEquals(Decision.Refollow(3_650f), w.at(15_000, 3_650f))
    }

    @Test
    fun withinFiveHundredMetresResetsTheCount() {
        val w = FollowWatchdog()
        w.at(0, 2_000f)
        w.at(5_000, 2_000f)
        assertEquals(2, w.misses)
        assertEquals(Decision.None("within 500 m"), w.at(6_000, 120f))
        assertEquals(0, w.misses)
        assertEquals(Decision.Refollow(2_000f), w.at(7_000, 2_000f))
        assertTrue(w.at(12_000, 2_000f) is Decision.Refollow)
        // Guiding in between also clears it.
        assertEquals(Decision.None("in place"), w.check(17_000, true, true, false, 100, 2_000f, inPlace = true))
        assertEquals(0, w.misses)
    }

    @Test
    fun fourKilometreFreeDriveJumpRecovers() {
        val w = FollowWatchdog()
        // The jump: the map is 4 km from the car → follow again; the SDK catches up within 5 s.
        assertEquals(Decision.Refollow(4_000f), w.at(0, 4_000f))
        assertTrue(w.at(1_000, 3_000f) is Decision.None)
        assertTrue(w.at(3_000, 800f) is Decision.None)
        assertEquals(Decision.None("within 500 m"), w.at(4_000, 30f))
        assertEquals(0, w.misses)
        assertTrue(w.at(5_000, 20f) is Decision.None)
    }
}
