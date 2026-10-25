// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.settings

import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.pgw.PgwDownload
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.StageCols
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DESIGN.md §13.4 step 4 restore rules beyond `SettingsBackupJsonTest`: values written as strings, a camera
 * set's stages that fail the §11.1 checks (the phone's stages are kept, both of them), a stage's own fallbacks,
 * the zoom range, empty templates and unknown icons; and every enum the file can carry.
 */
class SettingsRestoreEdgeTest {
    private val phone = PoiSetEntity(
        id = 3, name = "PGW fixed cameras · 30 mph", source = PoiSourceType.PGW, format = "OV2:gatso_30",
        defaultIconId = "builtin:camera_fixed", firstTemplate = "f", secondTemplate = "s", passingTemplate = "p",
        firstAlert = StageCols(true, DistanceMode.TIME, 242, 15), secondAlert = StageCols(true, DistanceMode.TIME, 142, 7),
        minZoom = 11f, lastUpdated = 9,
    )
    private val icons = setOf("builtin:camera_fixed", "builtin:camera_average")

    private fun apply(json: String) = SettingsBackup.applySet(phone, JSONObject(json), icons)

    @Test fun numbersAndBooleansWrittenAsStringsStillRestore() {
        val r = SettingsBackup.applySettings(AppSettings(), JSONObject("""{"voiceOn":"false","alertLeadSeconds":"30","overspeedPct":"0.05","avoidTolls":"FALSE"}"""))
        assertEquals(false, r.voiceOn)
        assertEquals(30, r.alertLeadSeconds)
        assertEquals(0.05, r.overspeedPct, 0.0)
        assertEquals(false, r.avoidTolls)
        // Anything else that isn't a boolean keeps the current value.
        assertEquals(true, SettingsBackup.applySettings(AppSettings(), JSONObject("""{"voiceOn":1}""")).voiceOn)
        assertEquals(15, SettingsBackup.applySettings(AppSettings(), JSONObject("""{"alertLeadSeconds":"soon"}""")).alertLeadSeconds)
    }

    @Test fun everyEnumValueRestoresByName() {
        for (u in Units.entries) assertEquals(u, SettingsBackup.applySettings(AppSettings(), JSONObject().put("units", u.name)).units)
        for (s in SpeedSource.entries) assertEquals(s, SettingsBackup.applySettings(AppSettings(), JSONObject().put("speedSource", s.name)).speedSource)
        for (d in PgwDownload.entries) assertEquals(d, SettingsBackup.applySettings(AppSettings(), JSONObject().put("pgwDownload", d.name)).pgwDownload)
        // Names are case-sensitive.
        assertEquals(Units.IMPERIAL, SettingsBackup.applySettings(AppSettings(), JSONObject("""{"units":"metric"}""")).units)
    }

    @Test fun anEmptyObjectChangesNothing() {
        assertEquals(phone, apply("{}"))
        assertEquals(phone, apply("""{"templates":{},"firstAlert":{},"secondAlert":{}}"""))
    }

    @Test fun stagesThatFailTheChecksKeepBothOfThePhonesStages() {
        // A 2nd under 142 m: neither stage is taken, though the 1st alone would have been fine.
        val r = apply("""{"firstAlert":{"metres":600},"secondAlert":{"metres":100}}""")
        assertEquals(phone.firstAlert, r.firstAlert)
        assertEquals(phone.secondAlert, r.secondAlert)
        // A 1st turned off is refused the same way, and the rest of the set still restores.
        val off = apply("""{"firstAlert":{"enabled":false},"enabled":false}""")
        assertEquals(phone.firstAlert, off.firstAlert)
        assertEquals(false, off.enabled)
        // A 1st too close to the 2nd.
        assertEquals(phone.firstAlert, apply("""{"firstAlert":{"metres":200}}""").firstAlert)
    }

    @Test fun stagesThatPassAreTakenFieldByField() {
        val r = apply("""{"firstAlert":{"mode":"FIXED","metres":500},"secondAlert":{"enabled":false,"metres":50}}""")
        // Missing fields keep the phone's; the 2nd turned off is no longer checked against the floor.
        assertEquals(StageCols(true, DistanceMode.FIXED, 500, 15), r.firstAlert)
        assertEquals(StageCols(false, DistanceMode.TIME, 50, 7), r.secondAlert)
    }

    @Test fun aStagesOwnFallbacks() {
        // An unknown mode, a zero or negative distance keep the phone's.
        val r = apply("""{"firstAlert":{"mode":"WARP","metres":0},"secondAlert":{"metres":-5}}""")
        assertEquals(phone.firstAlert, r.firstAlert)
        assertEquals(phone.secondAlert, r.secondAlert)
        // seconds: JSON null and 0 both mean none; absent keeps the phone's.
        assertNull(apply("""{"firstAlert":{"seconds":null}}""").firstAlert.seconds)
        assertNull(apply("""{"firstAlert":{"seconds":0}}""").firstAlert.seconds)
        assertEquals(15, apply("""{"firstAlert":{"metres":300}}""").firstAlert.seconds)
        assertEquals(40, apply("""{"firstAlert":{"seconds":40}}""").firstAlert.seconds)
    }

    @Test fun zoomMustBeAMapZoom() {
        assertEquals(0f, apply("""{"minZoom":0}""").minZoom, 0f)
        assertEquals(21f, apply("""{"minZoom":21}""").minZoom, 0f)
        assertEquals(13.5f, apply("""{"minZoom":"13.5"}""").minZoom, 0f)
        assertEquals(11f, apply("""{"minZoom":21.5}""").minZoom, 0f)
        assertEquals(11f, apply("""{"minZoom":-1}""").minZoom, 0f)
        assertEquals(11f, apply("""{"minZoom":"near"}""").minZoom, 0f)
    }

    @Test fun emptyTemplatesAndUnknownIconsKeepThePhones() {
        val r = apply("""{"templates":{"first":"","second":"{category} {distance}"},"icon":""}""")
        assertEquals("f", r.firstTemplate)
        assertEquals("{category} {distance}", r.secondTemplate)
        assertEquals("p", r.passingTemplate)
        assertEquals("builtin:camera_fixed", r.defaultIconId)
        assertEquals("builtin:camera_average", apply("""{"icon":"builtin:camera_average"}""").defaultIconId)
    }

    @Test fun theSetsIdentityIsNeverTakenFromTheFile() {
        // Sets are matched by name; the file can't rename, re-source or re-count one.
        val r = apply("""{"name":"Other","source":"FILE","format":"CSV","poiCount":99,"version":"1"}""")
        assertEquals(phone, r)
    }
}
