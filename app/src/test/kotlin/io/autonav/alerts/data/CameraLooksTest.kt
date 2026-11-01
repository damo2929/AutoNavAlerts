// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which of our camera icons a marker gets (DESIGN.md §7.1, owner 2026-10-25): the type from the category
 * code or the download file stem, the limit from the POI or the stem's "var", else "*", the temporary tab, and
 * when the download's own bitmap or a user's picture is kept instead. Pure JVM.
 */
class CameraLooksTest {
    @Test
    fun builtinCamerasAlwaysGetOurIconWithThePoiLimit() {
        assertEquals(CameraLook(CameraType.FIXED, "30"), CameraLooks.of(IconRepository.CAMERA_FIXED, null, 30, ours = true))
        // The setting only swaps the download's bitmaps; a built-in has no other picture.
        assertEquals(CameraLook(CameraType.MOBILE, "*"), CameraLooks.of(IconRepository.CAMERA_MOBILE, null, null, ours = false))
        assertEquals(CameraType.REDLIGHT_SPEED, CameraLooks.of(IconRepository.CAMERA_REDLIGHT_SPEED, "11", 40, true)!!.type)
        assertEquals(CameraType.PENDING, CameraLooks.of(IconRepository.CAMERA_PENDING, "5", null, true)!!.type)
    }

    @Test
    fun downloadSetsTakeTheTypeAndLimitFromTheirFileStem() {
        assertEquals(CameraLook(CameraType.FIXED, "30"), CameraLooks.of("user:pgw_gatso_30", null, null, true))
        assertEquals(CameraLook(CameraType.AVERAGE, "VAR"), CameraLooks.of("user:pgw_specs_var", null, null, true))
        // No limit known ("tba" or none in the stem): "*" in the number's place, a speed is always shown (owner, 2026-10-25).
        assertEquals(CameraLook(CameraType.FIXED, "*"), CameraLooks.of("user:pgw_gatso_tba", null, null, true))
        assertEquals(CameraLook(CameraType.REDLIGHT, "*"), CameraLooks.of("user:pgw_redlight", null, null, true))
        assertEquals(CameraLook(CameraType.PENDING, "70"), CameraLooks.of("user:pgw_pmobile_70", null, null, true))
        assertEquals(CameraType.REDLIGHT_SPEED, CameraLooks.of("user:pgw_redspeed_30", null, null, true)!!.type)
        assertEquals(CameraType.MOBILE, CameraLooks.of("user:pgw_mobile_50", null, null, true)!!.type)
    }

    @Test
    fun thePoisOwnLimitWinsOverTheStem() {
        // A camera in a "var" file that carries its own limit shows that limit.
        assertEquals("50", CameraLooks.of("user:pgw_specs_var", "SPECS", 50, true)!!.limit)
        assertEquals("40", CameraLooks.of("user:pgw_gatso_30", "GATSO", 40, true)!!.limit)
    }

    @Test
    fun theCategoryCodeNamesTheTypeAndATemporaryCamera() {
        val look = CameraLooks.of("user:pgw_specs_50", "tSPECS", 50, true)!!
        assertEquals(CameraType.AVERAGE, look.type)
        assertEquals(true, look.temporary)
        // The category code wins over the stem when both name a type.
        assertEquals(CameraType.MOBILE, CameraLooks.of("user:pgw_gatso_30", "MOBILE", 30, true)!!.type)
        // An unknown code falls back to the stem's type.
        assertEquals(CameraType.FIXED, CameraLooks.of("user:pgw_gatso_30", "WHATEVER", 30, true)!!.type)
        // "pMOBILE" and "tpMOBILE" aren't temporary-prefixed types: no orange tab for a pending site.
        assertEquals(false, CameraLooks.of("user:pgw_pmobile_30", "pMOBILE", 30, true)!!.temporary)
        assertEquals(false, CameraLooks.of(IconRepository.CAMERA_FIXED, "t", 30, true)!!.temporary)
    }

    @Test
    fun theDownloadsBitmapsAndOtherPicturesAreLeftAlone() {
        // "From the download": the supplied bitmap is drawn as before.
        assertNull(CameraLooks.of("user:pgw_gatso_30", null, 30, ours = false))
        // A user's own picture, a zip import's icon and non-camera built-ins are never replaced.
        assertNull(CameraLooks.of("user:3f1c2a", null, 30, true))
        assertNull(CameraLooks.of("user:imp_trams", null, null, true))
        assertNull(CameraLooks.of(IconRepository.PIN, null, 30, true))
        assertNull(CameraLooks.of("builtin:hazard", null, null, true))
        // A download stem of a type we don't know keeps its bitmap.
        assertNull(CameraLooks.of("user:pgw_bridge_30", null, 30, true))
    }

    @Test
    fun typeCodesCoverEveryOv2NameAndIgoNumber() {
        mapOf(
            "GATSO" to CameraType.FIXED, "MONITRON" to CameraType.FIXED, "TRUVELO" to CameraType.FIXED, "1" to CameraType.FIXED,
            "MOBILE" to CameraType.MOBILE, "5" to CameraType.MOBILE, "SPECS" to CameraType.AVERAGE, "4" to CameraType.AVERAGE,
            "REDLIGHT" to CameraType.REDLIGHT, "3" to CameraType.REDLIGHT, "REDSPEED" to CameraType.REDLIGHT_SPEED,
            "11" to CameraType.REDLIGHT_SPEED, "pMOBILE" to CameraType.PENDING, "tGATSO" to CameraType.FIXED, "tSPECS" to CameraType.AVERAGE,
        ).forEach { (code, type) -> assertEquals(code, type, CameraLooks.typeOfCode(code)) }
        assertNull(CameraLooks.typeOfCode("2"))
        assertNull(CameraLooks.typeOfCode("t"))
    }
}
