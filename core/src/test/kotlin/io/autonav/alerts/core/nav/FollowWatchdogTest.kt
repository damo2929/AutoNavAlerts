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
    fun noActionWhileGuidingOnThePreviewOrWithoutAnSdkFix() {
        val w = FollowWatchdog()
        assertEquals(Decision.None("guiding"), w.at(0, 4_000f, guiding = true))
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
        assertEquals(Decision.None("guiding"), w.at(17_000, 2_000f, guiding = true))
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
