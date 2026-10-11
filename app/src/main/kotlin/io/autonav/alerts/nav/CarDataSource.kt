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
 * The car's own speed and GPS via Android Auto (DESIGN.md §10.6–10.7). The car session starts and
 * stops the listeners; everyone else reads [freshSpeed] / [carLocationFresh], which report nothing
 * (use the phone) when the car hasn't reported recently, doesn't provide the value, or the user
 * chose "Phone only".
 */
@Singleton
class CarDataSource @Inject constructor() {
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

    fun freshSpeed(nowMs: Long = SystemClock.elapsedRealtime()): CarSpeed? =
        _speed.value?.takeIf { enabled && nowMs - it.atMs <= SPEED_FRESH_MS && it.rawMps != null }

    fun carLocationFresh(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        enabled && lastLocationMs > 0 && nowMs - lastLocationMs <= LOCATION_FRESH_MS

    fun start(carContext: CarContext) {
        if (speedListener != null || locationListener != null) return
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) {
            Log.i(TAG, "Car API level ${carContext.carAppApiLevel} < 3: car speed and GPS not available")
            return
        }
        val hw = runCatching { carContext.getCarService(CarHardwareManager::class.java) }
            .onFailure { Log.w(TAG, "No car hardware manager: ${it.message}") }.getOrNull() ?: return
        val executor = ContextCompat.getMainExecutor(carContext)

        val sl = OnCarDataAvailableListener<Speed> { s ->
            val raw = s.rawSpeedMetersPerSecond.okValue()
            val display = s.displaySpeedMetersPerSecond.okValue() ?: raw
            if (raw == null && display == null) return@OnCarDataAvailableListener
            val cs = CarSpeed(raw ?: display, display, SystemClock.elapsedRealtime())
            _speed.value = cs
            if (enabled) onSpeedUpdate?.invoke(cs)
        }
        runCatching {
            hw.carInfo.addSpeedListener(executor, sl)
            info = hw.carInfo
            speedListener = sl
            Log.i(TAG, "Listening for car speed")
        }.onFailure { Log.w(TAG, "Car speed unavailable: ${it.message}") }

        val ll = OnCarDataAvailableListener<CarHardwareLocation> { h ->
            val v = h.location
            if (v.status != CarValue.STATUS_SUCCESS) return@OnCarDataAvailableListener
            val loc = v.value ?: return@OnCarDataAvailableListener
            if (loc.latitude == 0.0 && loc.longitude == 0.0) return@OnCarDataAvailableListener
            lastLocationMs = SystemClock.elapsedRealtime()
            if (enabled) onLocation?.invoke(loc)
        }
        runCatching {
            hw.carSensors.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_FASTEST, executor, ll)
            sensors = hw.carSensors
            locationListener = ll
            Log.i(TAG, "Listening for car GPS")
        }.onFailure { Log.w(TAG, "Car GPS unavailable: ${it.message}") }
    }

    fun stop() {
        speedListener?.let { l -> runCatching { info?.removeSpeedListener(l) } }
        locationListener?.let { l -> runCatching { sensors?.removeCarHardwareLocationListener(l) } }
        speedListener = null
        locationListener = null
        info = null
        sensors = null
        _speed.value = null
        lastLocationMs = 0L
    }

    private fun CarValue<Float>.okValue(): Float? =
        if (status == CarValue.STATUS_SUCCESS) value?.takeIf { it >= 0f && it < 120f } else null

    companion object {
        private const val TAG = "CarData"
        const val SPEED_FRESH_MS = 3_000L
        const val LOCATION_FRESH_MS = 2_000L
    }
}
