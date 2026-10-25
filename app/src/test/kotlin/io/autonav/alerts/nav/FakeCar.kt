// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.location.Location
import android.os.SystemClock
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Speed
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * A head unit for the location-feed tests (DESIGN.md §10.6, §10.7): a `CarHardwareManager` whose CarInfo and
 * CarSensors keep the listeners `CarDataSource.listenTo` registers, with the rate asked for, so a test can
 * send speed reports and GPS fixes as the host would. Test-only; the listeners run on the caller's thread
 * (the Robolectric main thread). Upholds nothing itself; it lets the tests check the UPDATE_RATE_FASTEST rule.
 */
internal class FakeCar {
    var speed: OnCarDataAvailableListener<Speed>? = null
    var location: OnCarDataAvailableListener<CarHardwareLocation>? = null
    var locationRate: Int? = null
    val removed = ArrayList<String>()
    /** The host refuses the speed listener, as it does without the CAR_SPEED permission. */
    var refuseSpeed = false

    val executor = Executor { it.run() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, call: (String, Array<Any?>) -> Unit): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { p, m, a ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                "toString" -> "Fake${type.simpleName}"
                else -> { call(m.name, a ?: emptyArray()); null }
            }
        } as T

    @Suppress("UNCHECKED_CAST")
    val hardware = object : CarHardwareManager {
        override fun getCarInfo(): CarInfo = proxy(CarInfo::class.java) { name, a ->
            when (name) {
                "addSpeedListener" -> { if (refuseSpeed) throw SecurityException("CAR_SPEED not granted"); speed = a[1] as OnCarDataAvailableListener<Speed> }
                "removeSpeedListener" -> removed += "speed"
            }
        }

        override fun getCarSensors(): CarSensors = proxy(CarSensors::class.java) { name, a ->
            when (name) {
                "addCarHardwareLocationListener" -> { locationRate = a[0] as Int; location = a[2] as OnCarDataAvailableListener<CarHardwareLocation> }
                "removeCarHardwareLocationListener" -> removed += "location"
            }
        }
    }

    /** One speed report: [raw] and [display] in m/s, each with its own status. */
    fun sendSpeed(raw: Float?, display: Float? = raw, rawStatus: Int = CarValue.STATUS_SUCCESS, displayStatus: Int = CarValue.STATUS_SUCCESS) {
        speed!!.onCarDataAvailable(
            Speed.Builder()
                .setRawSpeedMetersPerSecond(CarValue(raw, 0, rawStatus))
                .setDisplaySpeedMetersPerSecond(CarValue(display, 0, displayStatus))
                .build(),
        )
    }

    /** One car GPS fix with [status]. */
    fun sendLocation(loc: Location, status: Int = CarValue.STATUS_SUCCESS) {
        location!!.onCarDataAvailable(CarHardwareLocation(CarValue(loc, 0, status)))
    }

    companion object {
        /** A fix at ([lat], [lon]) stamped now on the boot clock, as both feeds stamp theirs (§10.7 "Clock"). */
        fun fix(lat: Double, lon: Double, provider: String = "gps", speedMps: Float? = 20f, accuracyM: Float = 5f): Location =
            Location(provider).apply {
                latitude = lat; longitude = lon; accuracy = accuracyM
                speedMps?.let { speed = it }
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
    }
}
