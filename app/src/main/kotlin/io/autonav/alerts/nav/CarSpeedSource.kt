// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
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
 * The car's own speed via Android Auto (DESIGN.md §10.6). The car session starts and stops the
 * listener; everyone else reads [fresh], which falls back to null (use phone GPS) when the car
 * hasn't reported for 3 s, doesn't provide speed, or the user chose "Phone GPS only".
 */
@Singleton
class CarSpeedSource @Inject constructor() {
    private val _latest = MutableStateFlow<CarSpeed?>(null)
    val latest: StateFlow<CarSpeed?> = _latest

    /** False when Settings → Speed source is "Phone GPS only". */
    @Volatile var enabled = true

    /** Called (on the listener thread) for each car report; used to react to speed changes at once. */
    @Volatile var onUpdate: ((CarSpeed) -> Unit)? = null

    private var listener: OnCarDataAvailableListener<Speed>? = null
    private var info: androidx.car.app.hardware.info.CarInfo? = null

    fun fresh(nowMs: Long = SystemClock.elapsedRealtime()): CarSpeed? =
        _latest.value?.takeIf { enabled && nowMs - it.atMs <= FRESH_MS && it.rawMps != null }

    /** Whether the panel/engine are currently using car speed (for diagnostics). */
    val inUse: Boolean get() = fresh() != null

    fun start(carContext: CarContext) {
        if (listener != null) return
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) {
            Log.i(TAG, "Car API level ${carContext.carAppApiLevel} < 3: car speed not available")
            return
        }
        val l = OnCarDataAvailableListener<Speed> { s ->
            val raw = s.rawSpeedMetersPerSecond.okValue()
            val display = s.displaySpeedMetersPerSecond.okValue() ?: raw
            if (raw == null && display == null) return@OnCarDataAvailableListener
            val cs = CarSpeed(raw ?: display, display, SystemClock.elapsedRealtime())
            _latest.value = cs
            if (enabled) onUpdate?.invoke(cs)
        }
        runCatching {
            val hw = carContext.getCarService(CarHardwareManager::class.java)
            hw.carInfo.addSpeedListener(ContextCompat.getMainExecutor(carContext), l)
            info = hw.carInfo
            listener = l
            Log.i(TAG, "Listening for car speed")
        }.onFailure { Log.w(TAG, "Car speed unavailable: ${it.message}") }
    }

    fun stop() {
        val l = listener ?: return
        runCatching { info?.removeSpeedListener(l) }
        listener = null
        info = null
        _latest.value = null
    }

    private fun CarValue<Float>.okValue(): Float? =
        if (status == CarValue.STATUS_SUCCESS) value?.takeIf { it >= 0f && it < 120f } else null

    companion object {
        private const val TAG = "CarSpeed"
        const val FRESH_MS = 3_000L
    }
}
