// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.diag

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * DESIGN.md §15.5: the two always-on logs' file names and the names they are exported under
 * (`autonavalerts-<channel>-<date>.log`, the date in the phone's zone).
 */
class DiagFilesTest {
    @Test fun channelsAreTheTwoLogFiles() {
        assertEquals(listOf("debug-nav.log", "alert-debug.log"), EventLog.Channel.entries.map { it.fileName })
        assertEquals(listOf("debug-nav", "alert-debug"), EventLog.Channel.entries.map { it.shortName })
    }

    @Test fun exportNameCarriesTheChannelAndTheLocalDate() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            // 2026-10-13 12:00 UTC.
            assertEquals("autonavalerts-debug-nav-2026-10-13.log", DiagFiles.exportName(EventLog.Channel.NAV, 1_791_892_800_000L))
            assertEquals("autonavalerts-alert-debug-2026-10-13.log", DiagFiles.exportName(EventLog.Channel.ALERT, 1_791_892_800_000L))
            // 23:30 UTC on 31 Oct 2026 is still the 31st in London (GMT again by then); 23:30 UTC on 13 Oct is the 14th (BST).
            assertEquals("autonavalerts-debug-nav-2026-10-31.log", DiagFiles.exportName(EventLog.Channel.NAV, 1_793_489_400_000L))
            assertEquals("autonavalerts-debug-nav-2026-10-14.log", DiagFiles.exportName(EventLog.Channel.NAV, 1_791_934_200_000L))
        } finally {
            TimeZone.setDefault(zone)
        }
    }
}
