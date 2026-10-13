// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Speed
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import io.autonav.alerts.core.diag.DriveLog
import io.autonav.alerts.diag.DriveRecorder
import io.autonav.alerts.diag.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One speed report from the car. Either value may be missing if the car doesn't provide it. */
data class CarSpeed(
    /** The car's measured road speed. */
    val rawMps: Float?,
    /** What the car's own speedometer shows (usually a few percent high). */
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
 */
@Singleton
class CarDataSource @Inject constructor(
    private val eventLog: EventLog,
    private val driveRecorder: DriveRecorder,
) {
    private val _speed = MutableStateFlow<CarSpeed?>(null)
    val speed: StateFlow<CarSpeed?> = _speed

    @Volatile private var lastLocationMs = 0L

    /** False when Settings → Speed and location source is "Phone only". */
    @Volatile var enabled = true

    /** Called for each car speed report, so the GPS rate reacts at once. */
    @Volatile var onSpeedUpdate: ((CarSpeed) -> Unit)? = null

    /** Called for each car GPS fix. */
    @Volatile var onLocation: ((Location) -> Unit)? = null

    private var speedListener: OnCarDataAvailableListener<Speed>? = null
    private var locationListener: OnCarDataAvailableListener<CarHardwareLocation>? = null
    private var info: CarInfo? = null
    private var sensors: CarSensors? = null
    private var firstSpeedLogged = false
    private var firstLocationLogged = false

    fun freshSpeed(nowMs: Long = SystemClock.elapsedRealtime()): CarSpeed? =
        _speed.value?.takeIf { enabled && nowMs - it.atMs <= SPEED_FRESH_MS && it.rawMps != null }

    fun carLocationFresh(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        enabled && lastLocationMs > 0 && nowMs - lastLocationMs <= LOCATION_FRESH_MS

    fun start(carContext: CarContext) {
        if (speedListener != null || locationListener != null) return
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) {
            Log.i(TAG, "Car API level ${carContext.carAppApiLevel} < 3: car speed and GPS not available")
            nav("unavailable api=${carContext.carAppApiLevel} (< 3)")
            return
        }
        val hw = runCatching { carContext.getCarService(CarHardwareManager::class.java) }
            .onFailure { Log.w(TAG, "No car hardware manager: ${it.message}"); nav("unavailable noHardwareManager: ${it.message}") }
            .getOrNull() ?: return
        val executor = ContextCompat.getMainExecutor(carContext)
        firstSpeedLogged = false
        firstLocationLogged = false

        val sl = OnCarDataAvailableListener<Speed> { s ->
            val rawStatus = s.rawSpeedMetersPerSecond.status
            val raw = s.rawSpeedMetersPerSecond.okValue()
            val display = s.displaySpeedMetersPerSecond.okValue() ?: raw
            if (!firstSpeedLogged) {
                firstSpeedLogged = true
                nav("firstSpeed raw=${raw ?: "-"} disp=${display ?: "-"} status=$rawStatus")
            }
            if (raw == null && display == null) {
                eventLog.onChange("carData.speedStatus", rawStatus, EventLog.Channel.NAV, "CarData") { "speedStatus=$rawStatus (no value)" }
                return@OnCarDataAvailableListener
            }
            eventLog.onChange("carData.speedStatus", rawStatus, EventLog.Channel.NAV, "CarData") { "speedStatus=$rawStatus" }
            // A car reporting raw 0 with SUCCESS would stop the engine's `moving` flag; say so once per run of zeros (§10.6).
            val zero = raw == 0f && rawStatus == CarValue.STATUS_SUCCESS
            eventLog.onChange("carData.zero", zero, EventLog.Channel.NAV, "CarData") {
                if (zero) "zeroFix raw=0 disp=${display ?: "-"}" else "speed raw=${raw ?: "-"} disp=${display ?: "-"}"
            }
            val cs = CarSpeed(raw ?: display, display, SystemClock.elapsedRealtime())
            _speed.value = cs
            driveRecorder.record(
                DriveRecorder.Row(
                    tMs = System.currentTimeMillis(), kind = DriveLog.KIND_SPEED, src = DriveLog.SRC_CAR, used = enabled,
                    hasBrg = false, carRawMps = raw?.toDouble(), carDispMps = display?.toDouble(),
                ),
            )
            if (enabled) onSpeedUpdate?.invoke(cs)
        }
        runCatching {
            hw.carInfo.addSpeedListener(executor, sl)
            info = hw.carInfo
            speedListener = sl
            Log.i(TAG, "Listening for car speed")
            nav("listening speed")
        }.onFailure { Log.w(TAG, "Car speed unavailable: ${it.message}"); nav("unavailable speed: ${it.message}") }

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
        runCatching {
            hw.carSensors.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_FASTEST, executor, ll)
            sensors = hw.carSensors
            locationListener = ll
            Log.i(TAG, "Listening for car GPS")
            nav("listening location rate=fastest")
        }.onFailure { Log.w(TAG, "Car GPS unavailable: ${it.message}"); nav("unavailable location: ${it.message}") }
    }

    private fun nav(msg: String) = eventLog.record(EventLog.Channel.NAV, "CarData", msg)

    fun stop() {
        speedListener?.let { l -> runCatching { info?.removeSpeedListener(l) } }
        locationListener?.let { l -> runCatching { sensors?.removeCarHardwareLocationListener(l) } }
        speedListener = null
        locationListener = null
        info = null
        sensors = null
        _speed.value = null
        lastLocationMs = 0L
        nav("stopped")
    }

    private fun CarValue<Float>.okValue(): Float? =
        if (status == CarValue.STATUS_SUCCESS) value?.takeIf { it >= 0f && it < 120f } else null

    companion object {
        private const val TAG = "CarData"
        const val SPEED_FRESH_MS = 3_000L
        const val LOCATION_FRESH_MS = 2_000L
    }
}
