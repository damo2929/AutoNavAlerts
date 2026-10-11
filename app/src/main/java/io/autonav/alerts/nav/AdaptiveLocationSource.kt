// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.HandlerThread
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

data class LocationStats(val requestedHz: Int, val actualHz: Double, val speedMph: Double)

/**
 * Speed-aware fused location for the alert engine (DESIGN.md §10): 1 Hz below 10 mph, linear up
 * to 10 Hz at 50 mph, whole-Hz steps with a 2 s hold, capped to what the chip delivers.
 */
@Singleton
class AdaptiveLocationSource @Inject constructor(@ApplicationContext private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var thread: HandlerThread? = null
    private var controller = AdaptiveRateController()
    private val smoother = SpeedSmoother()
    private var running = false
    private var lastFix: Location? = null
    private var headingFrom: Location? = null
    private var lastHeading: Double? = null
    private val intervals = ArrayDeque<Long>()

    private val _fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Raw fixes for the alert engine and the per-fix marker redraw. */
    val fixes: SharedFlow<Fix> = _fixes

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation

    private val _stats = MutableStateFlow(LocationStats(1, 0.0, 0.0))
    val stats: StateFlow<LocationStats> = _stats

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    fun setCurve(curve: RateCurve) {
        controller = AdaptiveRateController(curve)
        if (running) request(controller.requestedHz)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = HandlerThread("adaptive-location").also { it.start() }
        request(controller.requestedHz)
    }

    fun stop() {
        if (!running) return
        running = false
        client.removeLocationUpdates(callback)
        thread?.quitSafely()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun request(hz: Int) {
        val looper = thread?.looper ?: return
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

    private fun onLocation(loc: Location) {
        val prev = lastFix
        val rawSpeed = when {
            loc.hasSpeed() -> loc.speed.toDouble()
            prev != null && loc.accuracy <= 30f -> {
                val dt = (loc.time - prev.time) / 1000.0
                if (dt > 0) Geo.distanceM(prev.latitude, prev.longitude, loc.latitude, loc.longitude) / dt else 0.0
            }
            else -> 0.0
        }
        val speed = smoother.add(rawSpeed)
        val heading = when {
            loc.hasBearing() && speed > 2.0 -> loc.bearing.toDouble()
            else -> {
                val from = headingFrom
                if (from != null && Geo.distanceM(from.latitude, from.longitude, loc.latitude, loc.longitude) > 5.0) {
                    headingFrom = loc
                    Geo.bearingDeg(from.latitude, from.longitude, loc.latitude, loc.longitude)
                } else {
                    if (from == null) headingFrom = loc
                    lastHeading
                }
            }
        }
        lastHeading = heading
        prev?.let { intervals.addLast(loc.time - it.time); while (intervals.size > 20) intervals.removeFirst() }
        lastFix = loc
        _lastLocation.value = loc

        val now = loc.elapsedRealtimeNanos / 1_000_000
        val mph = speed / Geo.MPS_PER_MPH
        controller.onSpeed(mph, now).newHz?.let(::request)
        val avg = intervals.filter { it > 0 }.average().takeIf { !it.isNaN() }
        val actualHz = avg?.let { 1000.0 / it } ?: 0.0
        if (avg != null) controller.onMeasuredRate(actualHz, now).newHz?.let(::request)
        _stats.value = LocationStats(controller.requestedHz, actualHz, mph)

        _fixes.tryEmit(Fix(loc.latitude, loc.longitude, speed, heading, System.currentTimeMillis()))
    }

    companion object { private const val TAG = "AdaptiveLocation" }
}
