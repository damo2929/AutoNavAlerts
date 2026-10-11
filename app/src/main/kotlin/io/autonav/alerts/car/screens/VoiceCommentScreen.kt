// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.App
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.reports.CarVoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step 2 of the one-tap report (DESIGN.md §8.5): the report is already saved; this records an
 * optional spoken comment from the car's microphone, then returns to the map with Undo.
 * No review or sending happens on the car screen.
 */
class VoiceCommentScreen(
    carContext: CarContext,
    private val session: NavSession,
    private val reportId: Long,
) : Screen(carContext) {
    private var recorder: CarVoiceRecorder? = null
    private var listening = false
    private var finished = false

    init {
        lifecycleScope.launch {
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 200) }
            delay(300)
            val rec = CarVoiceRecorder(carContext, session.deps.reports().audioFile(reportId))
            recorder = rec
            listening = true
            invalidate()
            val file = withContext(Dispatchers.IO) { rec.recordBlocking() }
            listening = false
            if (file != null) App.appScope.launch { session.deps.reports().attachAudio(reportId, file) }
            done(commented = file != null)
        }
    }

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

    private fun done(commented: Boolean) {
        if (finished) return
        finished = true
        val ctx = carContext
        screenManager.popToRoot()
        val text = if (commented) "Camera reported with your comment. Check it on your phone after the drive."
        else "Camera reported. Check it on your phone after the drive."
        if (ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
            val alertId = 900_000 + (reportId % 1000).toInt()
            val am = ctx.getCarService(AppManager::class.java)
            am.showAlert(
                Alert.Builder(alertId, CarText.create(text), 5_000)
                    .addAction(Action.Builder().setTitle("Undo").setOnClickListener {
                        App.appScope.launch { session.deps.reports().undo(reportId) }
                        am.dismissAlert(alertId)
                    }.build())
                    .build(),
            )
        } else {
            CarToast.makeText(ctx, text, CarToast.LENGTH_LONG).show()
        }
    }
}
