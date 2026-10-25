// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.app.Application
import androidx.car.app.model.MessageTemplate
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.R
import io.autonav.alerts.testing.CarScreenRig
import io.autonav.alerts.testing.setPrivate
import io.autonav.alerts.testing.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Step 2 of the one-tap report on the car screen (DESIGN.md §8 "Report" row, §8.5): "Report saved." under
 * "Report", then "Listening…" while the car microphone records, with exactly Done and Skip, and nothing else:
 * no review, no edit, no Send (hard rule 8, review and send on the phone only). Skip returns to the map once,
 * however often it is pressed, with the phone named as where to check. The recorder is never started here (the
 * looper's clock is not moved past the tone's pause). Robolectric main looper.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class VoiceCommentScreenTest {
    private fun screen(rig: CarScreenRig): VoiceCommentScreen {
        rig.root()
        return VoiceCommentScreen(rig.car, rig.host, reportId = 7).also { rig.start(it) }
    }

    @Test fun beforeTheToneItSaysTheReportIsSavedWithDoneAndSkipOnly() {
        val rig = CarScreenRig()
        val t = screen(rig).onGetTemplate() as MessageTemplate
        assertEquals("Report", t.header!!.title.toString())
        assertNull("no Back: Skip is the way out", t.header!!.startHeaderAction)
        assertEquals("Report saved.", t.message.toString())
        assertEquals(R.drawable.ic_action_report, t.icon!!.icon!!.resId)
        assertEquals("no review or Send on the car (hard rule 8)", listOf("Done", "Skip"), t.actions.map { it.title.toString() })
    }

    @Test fun whileListeningItAsksForTheCommentWithAnExample() {
        val rig = CarScreenRig()
        val s = screen(rig)
        s.setPrivate("listening", true)
        val t = s.onGetTemplate() as MessageTemplate
        assertEquals("Listening…", t.header!!.title.toString())
        assertEquals("Report saved. Say a comment now, for example \"mobile van in the lay-by\".", t.message.toString())
        assertEquals(listOf("Done", "Skip"), t.actions.map { it.title.toString() })
    }

    @Test fun doneBeforeTheRecordingStartsDoesNothing() {
        val rig = CarScreenRig()
        val s = screen(rig)
        (s.onGetTemplate() as MessageTemplate).actions[0].tap()
        rig.idle()
        assertTrue(rig.screens.screensRemoved.none { it === s })
    }

    @Test fun skipReturnsToTheMapAndBelowCarApi5AToastSaysToCheckOnThePhone() {
        val rig = CarScreenRig(apiLevel = 4)
        val s = screen(rig)
        val skip = (s.onGetTemplate() as MessageTemplate).actions[1]
        skip.tap()
        rig.idle()
        assertTrue(rig.screens.screensRemoved.contains(s))
        assertEquals(listOf("Camera reported. Check it on your phone after the drive."), rig.appManager.toastsShown.map { it.toString() })
        skip.tap()
        rig.idle()
        assertEquals("finished once: one toast, one pop", 1, rig.appManager.toastsShown.size)
    }

    @Test fun skipFromCarApi5UsesTheUndoAlertNotAToast() {
        val rig = CarScreenRig(apiLevel = 7)
        val s = screen(rig)
        (s.onGetTemplate() as MessageTemplate).actions[1].tap()
        rig.idle()
        assertTrue(rig.screens.screensRemoved.contains(s))
        assertTrue(rig.appManager.toastsShown.isEmpty())
    }
}
