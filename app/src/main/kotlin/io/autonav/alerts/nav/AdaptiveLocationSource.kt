// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.location.AdaptiveRateController
import io.autonav.alerts.core.location.RateCurve
import io.autonav.alerts.core.location.SpeedSmoother
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LocationStats(
    val requestedHz: Int,
    val actualHz: Double,
    val speedMph: Double,
    val speedFromCar: Boolean = false,
    val locationFromCar: Boolean = false,
)

/**
 * Speed-aware fused location for the alert engine (DESIGN.md §10): 1 Hz below 10 mph, linear up
 * to 10 Hz at 50 mph, whole-Hz steps with a 2 s hold, capped to what the chip delivers.
 */
@Singleton
class AdaptiveLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val carData: CarDataSource,
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var thread: HandlerThread? = null
    private val smoother = SpeedSmoother()
    private var running = false
    private var lastFix: Location? = null
    private var headingFrom: Location? = null
    private var movedHeading: Double? = null
    private var lastHeading: Double? = null
    private val intervals = ArrayDeque<Long>()
    private var lastArrivalMs = 0L
    /** The rate actually requested from the phone (1 Hz backup while car GPS is fresh). */
    @Volatile private var phoneHz = -1
    @Volatile private var usingCarLocation = false

    /** While true, real GPS is ignored and positions come from [inject] (Navigation SDK simulator). */
    @Volatile var simulated = false
        private set

    private val _fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Raw fixes for the alert engine and the per-fix marker redraw. */
    val fixes: SharedFlow<Fix> = _fixes

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation

    private val _stats = MutableStateFlow(LocationStats(1, 0.0, 0.0))
    val stats: StateFlow<LocationStats> = _stats

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (simulated) return
            // Car GPS takes priority while it's fresh; the phone is only a backup (DESIGN.md §10.7).
            if (carData.carLocationFresh()) return
            if (usingCarLocation) {
                usingCarLocation = false
                request(GPS_HZ) // car went quiet: the phone takes over
            }
            result.locations.forEach { onLocation(it, fromCar = false) }
        }
    }


    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        // React to car speed changes straight away, without waiting for the next GPS fix (§10.6).
        carData.onSpeedUpdate = { cs -> if (!simulated) cs.rawMps?.let { onCarSpeed(it.toDouble()) } }
        carData.onLocation = { loc -> if (!simulated) onCarLocation(loc) }
        thread = HandlerThread("adaptive-location").also { it.start() }
        request(GPS_HZ)
    }

    /** Bench testing: feed simulated positions (DESIGN.md §15.3). */
    fun setSimulated(on: Boolean) {
        simulated = on
        headingFrom = null
        movedHeading = null
        lastFix = null
        smoother.reset()
        intervals.clear()
    }

    fun inject(loc: Location) {
        if (simulated) onLocation(loc, fromCar = false)
    }

    private fun onCarLocation(loc: Location) {
        usingCarLocation = true
        onLocation(loc, fromCar = true)
    }

    @Synchronized
    private fun onCarSpeed(mps: Double) = Unit

    fun stop() {
        if (!running) return
        running = false
        carData.onSpeedUpdate = null
        carData.onLocation = null
        usingCarLocation = false
        phoneHz = -1
        client.removeLocationUpdates(callback)
        thread?.quitSafely()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun request(wantedHz: Int) {
        val looper = thread?.looper ?: return
        val hz = wantedHz
        if (hz == phoneHz) return
        phoneHz = hz
        val interval = RateCurve.intervalMs(hz)
        client.removeLocationUpdates(callback)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setMaxUpdateDelayMillis(0)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching { client.requestLocationUpdates(req, callback, looper) }
            .onFailure { Log.w(TAG, "Location request failed: ${it.message}") }
        Log.d(TAG, "Requested $hz Hz")
    }

    @Synchronized
    private fun onLocation(loc: Location, fromCar: Boolean) {
        val prev = lastFix
        val arrival = SystemClock.elapsedRealtime()
        val prevArrival = lastArrivalMs
        lastArrivalMs = arrival
        val rawSpeed = when {
            loc.hasSpeed() && loc.speed > 0f -> loc.speed.toDouble()
            prev != null && loc.accuracy <= 30f -> {
                val dtFix = (loc.time - prev.time) / 1000.0
                val dt = if (dtFix > 0) dtFix else (arrival - prevArrival) / 1000.0
                if (dt > 0) Geo.distanceM(prev.latitude, prev.longitude, loc.latitude, loc.longitude) / dt else 0.0
            }
            else -> 0.0
        }
        val gpsSpeed = smoother.add(rawSpeed)
        // Car speed when the head unit provides it (DESIGN.md §10.6); simulated drives use their own.
        val car = if (simulated) null else carData.freshSpeed(arrival)
        val speed = car?.rawMps?.toDouble() ?: gpsSpeed
        // Heading from the track we've actually moved along (needs > 5 m of movement)...
        val from = headingFrom
        val moved = if (from != null && Geo.distanceM(from.latitude, from.longitude, loc.latitude, loc.longitude) > 5.0) {
            headingFrom = loc
            Geo.bearingDeg(from.latitude, from.longitude, loc.latitude, loc.longitude)
        } else {
            if (from == null) headingFrom = loc
            null
        }
        if (moved != null) movedHeading = moved
        // ...and the GPS bearing when it agrees with it. Some sources report a stale or fixed bearing
        // (the emulator's is always 0, north), which would fail every camera's direction check (§11).
        val gpsBearing = loc.bearing.toDouble().takeIf { loc.hasBearing() && speed > 2.0 }
        val track = movedHeading
        val heading = when {
            gpsBearing != null && (track == null || Geo.absAngleDiffDeg(gpsBearing, track) <= BEARING_TRUST_DEG) -> gpsBearing
            track != null -> track
            else -> lastHeading
        }
        lastHeading = heading
        if (prev != null) { intervals.addLast(arrival - prevArrival); while (intervals.size > 20) intervals.removeFirst() }
        lastFix = loc
        _lastLocation.value = loc

        val mph = speed / Geo.MPS_PER_MPH
        val avg = intervals.filter { it > 0 }.average().takeIf { !it.isNaN() }
        val actualHz = avg?.let { 1000.0 / it } ?: 0.0
        _stats.value = LocationStats(GPS_HZ, actualHz, mph, speedFromCar = car != null, locationFromCar = fromCar)

        _fixes.tryEmit(Fix(loc.latitude, loc.longitude, speed, heading, System.currentTimeMillis()))
    }

    companion object {
        private const val TAG = "AdaptiveLocation"
        /** The phone's GPS is always asked for 10 fixes a second (DESIGN.md §10), whatever the speed. */
        const val GPS_HZ = 10
        /** A GPS bearing further than this from the track we've moved along isn't believed. */
        private const val BEARING_TRUST_DEG = 60.0
    }
}
