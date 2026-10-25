// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.location.Location
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Speed
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import io.autonav.alerts.core.diag.DriveLog
import io.autonav.alerts.diag.DriveRecorder
import io.autonav.alerts.diag.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One speed report from the car (DESIGN.md §10.6). Either value may be missing if the car doesn't
 * provide it; [AdaptiveLocationSource] gives the engine [rawMps] and the panel [displayMps] from
 * the same report, so the dial and the engine can't silently diverge.
 */
data class CarSpeed(
    /**
     * The car's measured road speed.
     *
     * m/s. The engine's speed while fresh (TIME-mode trigger distances, the over-limit check).
     */
    val rawMps: Float?,
    /**
     * What the car's own speedometer shows (usually a few percent high).
     *
     * m/s. The panel's number while fresh; never passed off as the measured speed.
     */
    val displayMps: Float?,
    /** SystemClock.elapsedRealtime() when it arrived. */
    val atMs: Long,
)

/**
 * The car's own speed and GPS via Android Auto (DESIGN.md §10.6–10.7), both at the fastest rate the
 * car offers. The car session starts and stops the listeners; everyone else reads [freshSpeed] /
 * [carLocationFresh], which report nothing (use the phone) when the car hasn't reported recently,
 * doesn't provide the value, or the user chose "Phone only". What the car does (or doesn't) send
 * goes to debug-nav as `CarData …` lines, and each speed report to the drive recorder (§15.5).
 *
 * Rule upheld here (AGENTS.md §3.2; DESIGN.md §10.6, §10.7, owner 2026-10-12): the car's GPS is
 * requested at `UPDATE_RATE_FASTEST`, never a slower rate; the car decides what it can deliver.
 * The Car App Library's speed listener takes no rate, so the car's speed comes as fast as the
 * host sends it. Neither feed is ever throttled here.
 *
 * Lifecycle: a Hilt singleton. `NavSession` calls [start] with its `CarContext` when the car
 * session is created and [stop] when it's destroyed (the listeners belong to that session's
 * host connection). The listeners run on the car context's main executor, so [onLocation] is
 * invoked on the main thread; `AlertCoordinator` sets [enabled] from the "Speed and location
 * source" setting; [AdaptiveLocationSource] reads [freshSpeed] per fix.
 */
@Singleton
class CarDataSource @Inject constructor(
    private val eventLog: EventLog,
    private val driveRecorder: DriveRecorder,
) {
    /** The latest report, fresh or not; every reader goes through [freshSpeed] for the 3 s rule. */
    private val _speed = MutableStateFlow<CarSpeed?>(null)

    /** `elapsedRealtime` of the last car fix with status SUCCESS, for [carLocationFresh]; 0 before any. */
    @Volatile private var lastLocationMs = 0L

    /**
     * False when Settings → Speed and location source is "Phone only".
     *
     * With it false both feeds are still listened to and logged (§15.5), but neither is handed on.
     */
    @Volatile var enabled = true

    /** Called for each car GPS fix. */
    @Volatile var onLocation: ((Location) -> Unit)? = null

    /** How to take each listener off the host again, one per listener the host accepted; empty when stopped. */
    private val unregister = ArrayList<() -> Unit>()
    /** The first report of each kind is logged in full (`CarData firstSpeed` / `firstLocation`, §15.5). */
    private var firstSpeedLogged = false
    private var firstLocationLogged = false

    /**
     * The car's speed report when it's usable: enabled, with a raw value, and no older than
     * [SPEED_FRESH_MS] at [nowMs] (elapsedRealtime ms). Null means "use the phone" (§10.6 Fallback).
     */
    fun freshSpeed(nowMs: Long = SystemClock.elapsedRealtime()): CarSpeed? =
        _speed.value?.takeIf { speedUsable(enabled, it, nowMs) }

    /**
     * True while the car's GPS is the live feed (§10.7): enabled and a SUCCESS fix within
     * [LOCATION_FRESH_MS] of [nowMs] (elapsedRealtime ms). While true the phone's fixes are recorded
     * but not used.
     */
    fun carLocationFresh(nowMs: Long = SystemClock.elapsedRealtime()): Boolean = locationFresh(enabled, lastLocationMs, nowMs)

    /**
     * Registers the speed and location listeners with the host. Needs car API level 3 and, for
     * speed, the runtime `CAR_SPEED` permission (§10.6: without it the host refuses the listener,
     * which is logged as `unavailable speed`, and the phone's speed is used). Idempotent. Main thread.
     */
    fun start(carContext: CarContext) {
        if (unregister.isNotEmpty()) return
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) {
            nav("unavailable api=${carContext.carAppApiLevel} (< 3)")
            return
        }
        val hw = runCatching { carContext.getCarService(CarHardwareManager::class.java) }
            .onFailure { nav("unavailable noHardwareManager: ${it.message}") }
            .getOrNull() ?: return
        listenTo(hw, ContextCompat.getMainExecutor(carContext))
    }

    /**
     * Registers the speed and location listeners on [hw], delivered on [executor]; split from [start]
     * so the JVM tests can hand in a fake car (DESIGN.md §15.1). Main thread.
     */
    internal fun listenTo(hw: CarHardwareManager, executor: Executor) {
        firstSpeedLogged = false
        firstLocationLogged = false

        val sl = OnCarDataAvailableListener<Speed> { s ->
            val rawStatus = s.rawSpeedMetersPerSecond.status
            val raw = s.rawSpeedMetersPerSecond.okValue()
            // No dial speed from this car: the raw one stands in, so the panel still shows a number.
            val display = s.displaySpeedMetersPerSecond.okValue() ?: raw
            if (!firstSpeedLogged) {
                firstSpeedLogged = true
                nav("firstSpeed raw=${raw ?: "-"} disp=${display ?: "-"} status=$rawStatus")
            }
            val noValue = raw == null && display == null
            eventLog.onChange("carData.speedStatus", rawStatus, EventLog.Channel.NAV, "CarData") { "speedStatus=$rawStatus${if (noValue) " (no value)" else ""}" }
            if (noValue) return@OnCarDataAvailableListener
            // A car reporting raw 0 with SUCCESS would stop the engine's `moving` flag; say so once per run of zeros (§10.6).
            val zero = raw == 0f && rawStatus == CarValue.STATUS_SUCCESS
            eventLog.onChange("carData.zero", zero, EventLog.Channel.NAV, "CarData") {
                if (zero) "zeroFix raw=0 disp=${display ?: "-"}" else "speed raw=${raw ?: "-"} disp=${display ?: "-"}"
            }
            // The dashboard figure reads a few % high: it is never passed off as the measured speed.
            _speed.value = CarSpeed(raw, display, SystemClock.elapsedRealtime())
            driveRecorder.record(
                DriveLog.RecordedFix(
                    tMs = System.currentTimeMillis(), kind = DriveLog.KIND_SPEED, src = DriveLog.SRC_CAR, used = enabled,
                    carRawMps = raw?.toDouble(), carDispMps = display?.toDouble(),
                ),
            )
        }
        listen("speed", "listening speed", { hw.carInfo.addSpeedListener(executor, sl) }) { hw.carInfo.removeSpeedListener(sl) }

        val ll = OnCarDataAvailableListener<CarHardwareLocation> { h ->
            val v = h.location
            eventLog.onChange("carData.locationStatus", v.status, EventLog.Channel.NAV, "CarData") { "locationStatus=${v.status}" }
            if (v.status != CarValue.STATUS_SUCCESS) return@OnCarDataAvailableListener
            val loc = v.value ?: return@OnCarDataAvailableListener
            if (loc.latitude == 0.0 && loc.longitude == 0.0) {
                eventLog.onChange("carData.zeroLocation", true, EventLog.Channel.NAV, "CarData") { "location 0,0 ignored" }
                return@OnCarDataAvailableListener
            }
            if (!firstLocationLogged) {
                firstLocationLogged = true
                nav("firstLocation acc=${if (loc.hasAccuracy()) loc.accuracy.toInt() else "-"} spd=${if (loc.hasSpeed()) "%.1f".format(loc.speed) else "-"} brg=${if (loc.hasBearing()) loc.bearing.toInt() else "-"} t=${loc.time}")
            }
            lastLocationMs = SystemClock.elapsedRealtime()
            if (enabled) onLocation?.invoke(loc)
        }
        listen("location", "listening location rate=fastest", { hw.carSensors.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_FASTEST, executor, ll) }) {
            hw.carSensors.removeCarHardwareLocationListener(ll)
        }
    }

    /**
     * Registers one listener with the host by [add]: `listening …` ([listening]) and its [remove] kept
     * for [stop] when the host takes it, `unavailable <what>: <why>` when it refuses (§10.6, §15.5).
     */
    private fun listen(what: String, listening: String, add: () -> Unit, remove: () -> Unit) {
        runCatching(add).onFailure { nav("unavailable $what: ${it.message}") }.onSuccess {
            unregister += remove
            nav(listening)
        }
    }

    /** A `CarData …` line in debug-nav (§15.5). */
    private fun nav(msg: String) = eventLog.record(EventLog.Channel.NAV, "CarData", msg)

    /**
     * Removes the listeners and forgets the last report, so a stale car speed can't outlive the
     * session and feed the next one. Safe to call without [start]. Main thread.
     */
    fun stop() {
        unregister.forEach { runCatching(it) }
        unregister.clear()
        _speed.value = null
        lastLocationMs = 0L
        nav("stopped")
    }

    companion object {
        // The rules behind [freshSpeed], [carLocationFresh] and the value check, internal for `CarDataRulesTest`.
        internal fun speedUsable(enabled: Boolean, s: CarSpeed, nowMs: Long) = enabled && nowMs - s.atMs <= SPEED_FRESH_MS && s.rawMps != null
        internal fun locationFresh(enabled: Boolean, lastMs: Long, nowMs: Long) = enabled && lastMs > 0 && nowMs - lastMs <= LOCATION_FRESH_MS

        // A sanity bound as well as the status check: 120 m/s is about 270 mph, so anything above is a bad value, not a car.
        internal fun CarValue<Float>.okValue(): Float? =
            if (status == CarValue.STATUS_SUCCESS) value?.takeIf { it >= 0f && it < 120f } else null

        /** A speed report older than this is stale and the phone's speed is used (DESIGN.md §10.6). */
        const val SPEED_FRESH_MS = 3_000L
        /** A car fix older than this hands location to the phone (DESIGN.md §10.7); also the hysteresis window. */
        const val LOCATION_FRESH_MS = 3_000L
    }
}
