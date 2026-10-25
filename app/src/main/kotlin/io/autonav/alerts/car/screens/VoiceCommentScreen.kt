// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.App
import io.autonav.alerts.R
import io.autonav.alerts.car.CarScreenHost
import io.autonav.alerts.reports.CarVoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step 2 of the one-tap report (DESIGN.md §8.5): the report is already saved; this records an
 * optional spoken comment from the car's microphone, then returns to the map with Undo.
 * No review or sending happens on the car screen.
 *
 * Pushed by `HomeScreen.openReport` only when the car microphone is usable (car API 5+ with
 * `RECORD_AUDIO`); it pops itself to the root when the recording ends, or on Done or Skip. A
 * `MessageTemplate` ("Listening…" with Done / Skip, §8 table). The recording blocks on
 * `Dispatchers.IO` (§2); the template and the host alert are on the main thread. Transcription
 * happens later on the phone, in the background (§8.5 "Speech to text").
 *
 * Hard rule 8: the car captures only; review and Send are on the phone. The toast and the
 * Undo alert say so.
 */
class VoiceCommentScreen(
    carContext: CarContext,
    private val session: CarScreenHost,
    private val reportId: Long,
) : Screen(carContext) {
    private var recorder: CarVoiceRecorder? = null
    private var listening = false
    /** Set once, by whichever of the recording's end, Done or Skip comes first, so the pop and the alert happen once. */
    private var finished = false

    init {
        lifecycleScope.launch {
            // The tone, then a short pause so it isn't in the recording (§8.5 "Say a comment after the tone").
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 200) }
            delay(300)
            val rec = CarVoiceRecorder(carContext, session.deps.reports().audioFile(reportId))
            recorder = rec
            listening = true
            invalidate()
            // Up to 15 s, ending after 2 s of silence or on Done (§8.5); null when nothing usable was recorded.
            val file = withContext(Dispatchers.IO) { rec.recordBlocking() }
            listening = false
            // Attached from the app scope: the screen is about to pop and its scope with it.
            if (file != null) App.appScope.launch { session.deps.reports().attachAudio(reportId, file) }
            done(commented = file != null)
        }
    }

    /** "Listening…" while the recorder runs, "Report" once it has stopped; the text changes once, so one re-send. */
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(
            if (listening) "Report saved. Say a comment now, for example \"mobile van in the lay-by\"."
            else "Report saved.",
        )
            .setHeader(Header.Builder().setTitle(if (listening) "Listening…" else "Report").build())
            .setIcon(CarUi.icon(carContext, R.drawable.ic_action_report))
            .addAction(CarUi.textAction("Done") { recorder?.stop() })
            .addAction(CarUi.textAction("Skip") { recorder?.stop(); done(commented = false) })
            .build()

    // Back to the map, then the 5 s Undo alert (§8.5 step 3); a toast without Undo on hosts below car API 5.
    private fun done(commented: Boolean) {
        if (finished) return
        finished = true
        val ctx = carContext
        screenManager.popToRoot()
        val text = if (commented) "Camera reported with your comment. Check it on your phone after the drive."
        else "Camera reported. Check it on your phone after the drive."
        if (ctx.carAppApiLevel < CarAppApiLevels.LEVEL_5) return CarToast.makeText(ctx, text, CarToast.LENGTH_LONG).show()
        // Its own id range, clear of the POI alert ids (`NavSession.showCarAlert` uses poiId % 100 000).
        val alertId = 900_000 + (reportId % 1000).toInt()
        val am = ctx.getCarService(AppManager::class.java)
        am.showAlert(CarUi.alert(alertId, text, 5_000, actions = arrayOf("Undo" to {
            App.appScope.launch { session.deps.reports().undo(reportId) }
            am.dismissAlert(alertId)
        })))
    }
}
