// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import io.autonav.alerts.App
import io.autonav.alerts.alerts.NavTts
import io.autonav.alerts.core.alerts.AlertKind
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.nav.GuidancePrompter
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks the turn-by-turn directions through [NavTts] while the Navigation SDK's own voice is off,
 * so the directions and the alerts are one voice, the phone's default (DESIGN.md §12.3, owner
 * 2026-10-23). [GuidancePrompter] decides when; this feeds it the SDK's turn-by-turn updates.
 */
@Singleton
class DirectionsSpeaker @Inject constructor(
    private val tts: NavTts,
    private val location: AdaptiveLocationSource,
    private val settings: SettingsStore,
    private val eventLog: EventLog,
) {
    private val prompter = GuidancePrompter()
    private var job: Job? = null
    @Volatile private var imperial = true

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        prompter.reset()
        tts.warmUp()
        log("start")
        job = App.appScope.launch(Dispatchers.Main) {
            launch { settings.settings.collect { imperial = it.units == Units.IMPERIAL } }
            NavUpdatesService.navInfo.collectLatest { info ->
                val step = info?.currentStep ?: return@collectLatest
                val d = info.distanceToCurrentStepMeters?.toDouble() ?: return@collectLatest
                tts.onDistance(DIRECTIONS_POI, d)
                val p = prompter.onUpdate(
                    stepNumber = step.stepNumber ?: -1,
                    instruction = step.fullInstructionText,
                    distanceM = d,
                    // The engine's speed (car or GPS), as for the alerts.
                    speedMps = location.stats.value.speedMph * Geo.MPS_PER_MPH,
                    imperial = imperial,
                    rerouting = info.navState == NavState.REROUTING,
                    destination = when (step.maneuver) {
                        Maneuver.DESTINATION -> GuidancePrompter.Destination.AHEAD
                        Maneuver.DESTINATION_LEFT -> GuidancePrompter.Destination.LEFT
                        Maneuver.DESTINATION_RIGHT -> GuidancePrompter.Destination.RIGHT
                        else -> null
                    },
                ) ?: return@collectLatest
                log("say kind=${p.kind.name.lowercase()} step=${p.step} d=${d.toInt()} text=\"${p.text}\"")
                tts.speak(p.text, PRIORITY, DIRECTIONS_POI, chime = false, kind = AlertKind.GUIDANCE, distanceM = d, staleBelowM = p.staleBelowM)
            }
        }
    }

    /** Guidance ended (a stop, an arrival, the fallback to the SDK's voice). */
    fun stop(reason: String) {
        if (job == null) return
        job?.cancel()
        job = null
        prompter.reset()
        log("stop reason=$reason")
    }

    /** The destination is reached: said once, in the same voice, before guidance stops. */
    fun arrived() {
        if (!running) return
        log("say kind=arrived text=\"You have arrived\"")
        tts.speak("You have arrived", PRIORITY, DIRECTIONS_POI, chime = false, kind = AlertKind.GUIDANCE)
    }

    private fun log(msg: String) = eventLog.record(EventLog.Channel.NAV, "Directions", msg)

    companion object {
        /** The speech queue's id for directions (cameras are > 0, limit changes -1). */
        const val DIRECTIONS_POI = -2L
        /** Above a camera's 1st and 2nd stages, below its PASSING (DESIGN.md §12.3). */
        const val PRIORITY = 80
    }
}
