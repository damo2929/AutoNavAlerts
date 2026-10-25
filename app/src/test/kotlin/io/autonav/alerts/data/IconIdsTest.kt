// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of [IconRepository] (DESIGN.md §7.1, §5.2, §5.2a): which icon ids count as camera icons
 * (drawn alone, never with a limit badge; owner 2026-10-23), which came with a download, the built-in pack in
 * picker order, and the built-in icon a PGW category gets by iGO code or OV2 type name.
 */
class IconIdsTest {
    @Test fun builtInPackIsInPickerOrder() {
        assertEquals(
            listOf(
                IconRepository.CAMERA_FIXED, IconRepository.CAMERA_MOBILE, IconRepository.CAMERA_AVERAGE, IconRepository.CAMERA_REDLIGHT,
                IconRepository.CAMERA_REDLIGHT_SPEED, IconRepository.CAMERA_PENDING, IconRepository.PIN,
                "builtin:hazard", "builtin:school", "builtin:fuel", "builtin:ev", "builtin:star",
            ),
            IconRepository.BUILTIN.keys.toList(),
        )
        // Every built-in has its own drawable.
        assertEquals(IconRepository.BUILTIN.size, IconRepository.BUILTIN.values.toSet().size)
    }

    @Test fun suppliedIconsAreThePgwAndZipImportOnes() {
        assertTrue(IconRepository.isPgwIcon("user:pgw_gatso_30"))
        assertFalse(IconRepository.isPgwIcon("user:imp_speed_bumps"))
        assertFalse(IconRepository.isPgwIcon("builtin:camera_fixed"))
        assertTrue(IconRepository.isSuppliedIcon("user:pgw_specs"))
        assertTrue(IconRepository.isSuppliedIcon("user:imp_speed_bumps"))
        // An icon the user added from the gallery is neither.
        assertFalse(IconRepository.isSuppliedIcon("user:my_photo"))
        assertFalse(IconRepository.isSuppliedIcon(IconRepository.PIN))
    }

    @Test fun cameraIconsAreTheSuppliedOnesAndTheBuiltInCameras() {
        for (id in listOf(IconRepository.CAMERA_FIXED, IconRepository.CAMERA_MOBILE, IconRepository.CAMERA_AVERAGE, IconRepository.CAMERA_REDLIGHT,
            IconRepository.CAMERA_REDLIGHT_SPEED, IconRepository.CAMERA_PENDING, "user:pgw_gatso_30", "user:imp_x")) {
            assertTrue(id, IconRepository.isCameraIcon(id))
        }
        for (id in listOf(IconRepository.PIN, "builtin:hazard", "builtin:school", "builtin:fuel", "builtin:ev", "builtin:star", "user:my_photo")) {
            assertFalse(id, IconRepository.isCameraIcon(id))
        }
    }

    @Test fun igoTypeCodesPickTheirCamera() {
        val pgw = PoiSourceType.PGW
        assertEquals(IconRepository.CAMERA_FIXED, IconRepository.pgwIconFor("1", pgw))
        assertEquals(IconRepository.CAMERA_REDLIGHT, IconRepository.pgwIconFor("3", pgw))
        assertEquals(IconRepository.CAMERA_AVERAGE, IconRepository.pgwIconFor("4", pgw))
        assertEquals(IconRepository.CAMERA_MOBILE, IconRepository.pgwIconFor("5", pgw))
        assertEquals(IconRepository.CAMERA_REDLIGHT_SPEED, IconRepository.pgwIconFor("11", pgw))
        // An unknown code becomes a "Type N" category with the fixed-camera icon (§5.2).
        assertEquals(IconRepository.CAMERA_FIXED, IconRepository.pgwIconFor("2", pgw))
        assertEquals(IconRepository.CAMERA_FIXED, IconRepository.pgwIconFor("", pgw))
    }

    @Test fun ov2TypeNamesPickTheirCameraWithOrWithoutTheTemporaryPrefix() {
        val pgw = PoiSourceType.PGW
        val expected = mapOf(
            "GATSO" to IconRepository.CAMERA_FIXED, "MONITRON" to IconRepository.CAMERA_FIXED, "TRUVELO" to IconRepository.CAMERA_FIXED,
            "REDSPEED" to IconRepository.CAMERA_REDLIGHT_SPEED, "REDLIGHT" to IconRepository.CAMERA_REDLIGHT,
            "SPECS" to IconRepository.CAMERA_AVERAGE, "MOBILE" to IconRepository.CAMERA_MOBILE,
        )
        for ((code, icon) in expected) {
            assertEquals(code, icon, IconRepository.pgwIconFor(code, pgw))
            assertEquals("t$code", icon, IconRepository.pgwIconFor("t$code", pgw))
        }
        // Names are matched exactly as the download writes them.
        assertEquals(IconRepository.CAMERA_FIXED, IconRepository.pgwIconFor("specs", pgw))
    }

    @Test fun pendingSitesGetThePendingIconWhateverTheCode() {
        for (code in listOf("5", "MOBILE", "GATSO", "1", "")) {
            assertEquals(code, IconRepository.CAMERA_PENDING, IconRepository.pgwIconFor(code, PoiSourceType.PGW_PENDING))
        }
        assertEquals(IconRepository.CAMERA_PENDING, IconRepository.pgwIconFor("pMOBILE", PoiSourceType.PGW))
    }
}
