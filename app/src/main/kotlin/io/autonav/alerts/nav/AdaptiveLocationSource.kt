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
import io.autonav.alerts.core.diag.DriveLog
import io.autonav.alerts.core.diag.FixSummary
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.location.Estimate
import io.autonav.alerts.core.location.FixEstimator
import io.autonav.alerts.core.location.FixInput
import io.autonav.alerts.core.location.HeadingSource
import io.autonav.alerts.core.location.LocationSource
import io.autonav.alerts.core.location.SpeedSource
import io.autonav.alerts.diag.DriveRecorder
import io.autonav.alerts.diag.EventLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What More → GPS shows (DESIGN.md §10.2, §10.6, §10.7), refreshed on every fix the engine gets. */
data class LocationStats(
    val requestedHz: Int,
    val actualHz: Double,
    /** The engine's speed (the car's raw speed when fresh, else the estimator's). */
    val speedMph: Double,
    val speedFromCar: Boolean = false,
    val locationFromCar: Boolean = false,
    val headingDeg: Double? = null,
    /** `track` / `gps` / `blend` / `held` / `none` (lower-case [HeadingSource]). */
    val headingSrc: String = "",
    val headingAccDeg: Double? = null,
    /** `gps` / `displacement` / `car_raw` / `simulated` / `none`. */
    val speedSrc: String = "",
    val accuracyM: Double? = null,
    val chordM: Double? = null,
    val gpsBrgDeg: Double? = null,
    val lastReset: String? = null,
    /** The dial speed shown on the panel (the car's display speed when fresh), mph. */
    val dialSpeedMph: Double? = null,
)

/**
 * The bridge from the estimator's [Estimate] to the engine's [Fix] (DESIGN.md §10.6a "what the
 * engine sees"). Pure, so it is unit-tested on the JVM: the two `SpeedSource`/`HeadingSource`
 * enums differ between `core.location` and `core.alerts`.
 */
object EstimateMapper {
    fun speedSource(s: SpeedSource): io.autonav.alerts.core.alerts.SpeedSource = when (s) {
        SpeedSource.GPS -> io.autonav.alerts.core.alerts.SpeedSource.GPS
        SpeedSource.DISPLACEMENT -> io.autonav.alerts.core.alerts.SpeedSource.DERIVED
        SpeedSource.CAR_RAW -> io.autonav.alerts.core.alerts.SpeedSource.CAR_RAW
        SpeedSource.SIMULATED -> io.autonav.alerts.core.alerts.SpeedSource.SIMULATED
        SpeedSource.NONE -> io.autonav.alerts.core.alerts.SpeedSource.NONE
    }

    fun headingSource(h: HeadingSource): io.autonav.alerts.core.alerts.HeadingSource = when (h) {
        HeadingSource.GPS -> io.autonav.alerts.core.alerts.HeadingSource.GPS
        HeadingSource.TRACK -> io.autonav.alerts.core.alerts.HeadingSource.TRACK
        HeadingSource.BLEND -> io.autonav.alerts.core.alerts.HeadingSource.BLEND
        HeadingSource.HELD -> io.autonav.alerts.core.alerts.HeadingSource.HELD
        HeadingSource.NONE -> io.autonav.alerts.core.alerts.HeadingSource.NONE
    }

    fun locationSource(l: LocationSource): io.autonav.alerts.core.alerts.LocationSource = when (l) {
        LocationSource.PHONE -> io.autonav.alerts.core.alerts.LocationSource.PHONE
        LocationSource.CAR -> io.autonav.alerts.core.alerts.LocationSource.CAR
        LocationSource.SIM -> io.autonav.alerts.core.alerts.LocationSource.SIM
    }

    /** The recorder's `src` column for a feed. */
    fun recorderSrc(l: LocationSource): String = when (l) {
        LocationSource.PHONE -> DriveLog.SRC_PHONE
        LocationSource.CAR -> DriveLog.SRC_CAR
        LocationSource.SIM -> DriveLog.SRC_SIM
    }

    /**
     * The engine's fix. With a fresh car speed report ([carRawMps] non-null) the engine's speed is the
     * car's raw speed and the panel's [Fix.displaySpeedMps] the car's dial speed (§10.6); otherwise
     * both come from the estimate. The heading is null whenever its source is NONE.
     */
    fun toFix(e: Estimate, lat: Double, lon: Double, accuracyM: Double?, carRawMps: Double? = null, carDispMps: Double? = null): Fix {
        val heading = if (e.headingSource == HeadingSource.NONE) null else e.headingDeg
        return Fix(
            lat = lat, lon = lon,
            speedMps = carRawMps ?: e.speedMps,
            heading = heading,
            timeMs = e.timeMs,
            headingAccuracyDeg = if (heading == null) null else e.headingAccuracyDeg,
            headingSource = headingSource(e.headingSource),
            speedSource = if (carRawMps != null) io.autonav.alerts.core.alerts.SpeedSource.CAR_RAW else speedSource(e.speedSource),
            displaySpeedMps = if (carRawMps != null) carDispMps else null,
            locationSource = locationSource(e.locationSource),
            accuracyM = accuracyM,
        )
    }
}

/**
 * The phone's fused location at 10 Hz, the car's GPS while fresh, and the Navigation SDK's
 * simulator on the bench, turned into the engine's [Fix] by the core [FixEstimator] (DESIGN.md
 * §10.2, §10.6a, §10.7). Every `Location` that reaches this class goes to the [DriveRecorder]
 * (used or not); the feeds' state goes to `debug-nav.log` by sample and on change (§15.5).
 */
@Singleton
class AdaptiveLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val carData: CarDataSource,
    private val eventLog: EventLog,
    private val driveRecorder: DriveRecorder,
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var thread: HandlerThread? = null
    private val estimator = FixEstimator()
    private var running = false
    private val intervals = ArrayDeque<Long>()
    private var lastArrivalMs = 0L
    private var lastSource: LocationSource? = null
    /** `elapsedRealtime` of the last fix seen from each feed, for the handover line. */
    private var lastCarMs = 0L
    private var lastPhoneMs = 0L
    private var accuracyPoor = false
    private var lastReset: String? = null
    /** The rate actually requested from the phone. */
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

    private val _stats = MutableStateFlow(LocationStats(GPS_HZ, 0.0, 0.0))
    val stats: StateFlow<LocationStats> = _stats

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (simulated) {
                // Real GPS is recorded but not used during a simulated drive (§10.7).
                result.locations.forEach { record(it, LocationSource.PHONE, used = false, estimate = null) }
                return
            }
            // Car GPS takes priority while it's fresh; the phone is only a backup (DESIGN.md §10.7).
            if (carData.carLocationFresh()) {
                result.locations.forEach { record(it, LocationSource.PHONE, used = false, estimate = null) }
                return
            }
            result.locations.forEach { onLocation(it, LocationSource.PHONE) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        carData.onLocation = { loc -> if (!simulated) onLocation(loc, LocationSource.CAR) }
        thread = HandlerThread("adaptive-location").also { it.start() }
        request(GPS_HZ)
    }

    /** Bench testing: feed simulated positions (DESIGN.md §15.3). */
    @Synchronized
    fun setSimulated(on: Boolean) {
        if (simulated == on) return
        simulated = on
        reset(if (on) "simulation on" else "simulation off")
        intervals.clear()
        lastSource = null
    }

    fun inject(loc: Location) {
        if (simulated) onLocation(loc, LocationSource.SIM)
    }

    fun stop() {
        if (!running) return
        running = false
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
        val interval = 1000L / hz
        client.removeLocationUpdates(callback)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setMaxUpdateDelayMillis(0)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching { client.requestLocationUpdates(req, callback, looper) }
            .onFailure {
                Log.w(TAG, "Location request failed: ${it.message}")
                eventLog.record(EventLog.Channel.NAV, "Location", "request failed: ${it.message}")
            }
        eventLog.record(EventLog.Channel.NAV, "Location", "request hz=$hz")
    }

    /** Forgets the track (handover, simulation on/off) and says so (§10.6a, §15.5). */
    private fun reset(reason: String) {
        estimator.resetTrack(reason)
        lastReset = reason
        eventLog.record(EventLog.Channel.NAV, "Location", "TRACK_RESET reason=$reason")
    }

    @Synchronized
    private fun onLocation(loc: Location, source: LocationSource) {
        val arrival = SystemClock.elapsedRealtime()
        val prevArrival = lastArrivalMs
        lastArrivalMs = arrival
        if (source == LocationSource.CAR) lastCarMs = arrival else if (source == LocationSource.PHONE) lastPhoneMs = arrival

        // Handover (§10.7): the track is reset and the flip logged with the other feed's age.
        val prevSource = lastSource
        if (prevSource != source) {
            if (prevSource != null) {
                val sinceOther = when (prevSource) {
                    LocationSource.CAR -> arrival - lastCarMs
                    LocationSource.PHONE -> arrival - lastPhoneMs
                    LocationSource.SIM -> 0L
                }
                val flip = "${prevSource.name.lowercase()}→${source.name.lowercase()}"
                reset("handover $flip")
                eventLog.record(EventLog.Channel.NAV, "Location", "source $flip (+$sinceOther ms)")
            } else {
                eventLog.record(EventLog.Channel.NAV, "Location", "source ${source.name.lowercase()}")
            }
            lastSource = source
        }
        usingCarLocation = source == LocationSource.CAR
        if (prevArrival > 0 && arrival - prevArrival > GAP_MS) {
            eventLog.record(EventLog.Channel.NAV, "Location", "gap ms=${arrival - prevArrival}")
        }

        val estimate = estimator.onFix(toInput(loc, source))
        estimate.resetReason?.let { r ->
            lastReset = r
            eventLog.record(EventLog.Channel.NAV, "Location", "TRACK_RESET reason=$r")
        }
        // Car speed when the head unit provides it (DESIGN.md §10.6); simulated drives use their own.
        val car = if (simulated) null else carData.freshSpeed(arrival)
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        val fix = EstimateMapper.toFix(
            estimate, loc.latitude, loc.longitude, accuracy,
            carRawMps = car?.rawMps?.toDouble(), carDispMps = car?.displayMps?.toDouble(),
        )

        if (prevArrival > 0) { intervals.addLast(arrival - prevArrival); while (intervals.size > 20) intervals.removeFirst() }
        _lastLocation.value = loc

        val avg = intervals.filter { it > 0 }.average().takeIf { !it.isNaN() }
        val actualHz = avg?.let { 1000.0 / it } ?: 0.0
        _stats.value = LocationStats(
            requestedHz = GPS_HZ, actualHz = actualHz, speedMph = fix.speedMps / Geo.MPS_PER_MPH,
            speedFromCar = car != null, locationFromCar = source == LocationSource.CAR,
            headingDeg = fix.heading, headingSrc = estimate.headingSource.name.lowercase(),
            headingAccDeg = fix.headingAccuracyDeg, speedSrc = fix.speedSource.name.lowercase(),
            accuracyM = accuracy, chordM = estimate.chordM, gpsBrgDeg = estimate.gpsBearingDeg, lastReset = lastReset,
            dialSpeedMph = fix.displaySpeedMps?.let { it / Geo.MPS_PER_MPH },
        )

        // Diagnostics (§15.5): the header counter per fix, lines only by sample or on change.
        eventLog.fix(FixSummary(source.name.lowercase(), fix.speedMps, fix.heading))
        eventLog.onChange("location.headingSrc", estimate.headingSource, EventLog.Channel.NAV, "Location") {
            "heading src=${estimate.headingSource.name.lowercase()} hdg=${fix.heading?.toInt() ?: "-"} acc=${fix.headingAccuracyDeg?.toInt() ?: "-"}"
        }
        eventLog.onChange("location.speedSrc", fix.speedSource, EventLog.Channel.NAV, "Location") {
            "speed src=${fix.speedSource.name.lowercase()} spd=${"%.1f".format(fix.speedMps)}"
        }
        val poor = accuracy != null && accuracy > POOR_ACCURACY_M
        if (poor != accuracyPoor) {
            accuracyPoor = poor
            eventLog.record(EventLog.Channel.NAV, "Location", if (poor) "accuracy>50m acc=${accuracy?.toInt()}" else "accuracy ok acc=${accuracy?.toInt()}")
        }
        eventLog.sample("fix", 5_000, EventLog.Channel.NAV, "Location") {
            "fix lat=${"%.5f".format(loc.latitude)} lon=${"%.5f".format(loc.longitude)} acc=${accuracy?.toInt() ?: "-"}" +
                " spd=${"%.1f".format(fix.speedMps)} hdg=${fix.heading?.toInt() ?: "-"} src=${source.name.lowercase()}" +
                " hz=${"%.1f".format(actualHz)} gpsBrg=${estimate.gpsBearingDeg?.toInt() ?: "-"} track=${estimate.trackDeg?.toInt() ?: "-"}"
        }
        record(loc, source, used = true, estimate = estimate, fix = fix, car = car)

        _fixes.tryEmit(fix)
    }

    private fun toInput(loc: Location, source: LocationSource): FixInput = FixInput(
        lat = loc.latitude, lon = loc.longitude,
        // Some feeds stamp 0: fall back to the wall clock so the engine's timers stay sane.
        timeMs = if (loc.time > 0) loc.time else System.currentTimeMillis(),
        accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
        speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
        bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
        bearingAccuracyDeg = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else null,
        source = source,
    )

    /** One recorder row per `Location` (DESIGN.md §15.5), dropped ones included. Cheap when not recording. */
    private fun record(loc: Location, source: LocationSource, used: Boolean, estimate: Estimate?, fix: Fix? = null, car: CarSpeed? = null) {
        driveRecorder.record(
            DriveRecorder.Row(
                tMs = System.currentTimeMillis(), kind = DriveLog.KIND_FIX, src = EstimateMapper.recorderSrc(source), used = used,
                lat = loc.latitude, lon = loc.longitude,
                accM = if (loc.hasAccuracy()) loc.accuracy else null,
                spdMps = if (loc.hasSpeed()) loc.speed else null,
                brgDeg = if (loc.hasBearing()) loc.bearing else null,
                hasBrg = loc.hasBearing(),
                altM = if (loc.hasAltitude()) loc.altitude else null,
                fixTMs = loc.time,
                engSpdMps = fix?.speedMps, engHdgDeg = fix?.heading,
                hdgSrc = estimate?.headingSource?.name?.lowercase(),
                carRawMps = car?.rawMps?.toDouble(), carDispMps = car?.displayMps?.toDouble(),
            ),
        )
    }

    companion object {
        private const val TAG = "AdaptiveLocation"
        /** The phone's GPS is always asked for 10 fixes a second (DESIGN.md §10), whatever the speed. */
        const val GPS_HZ = 10
        private const val GAP_MS = 5_000L
        private const val POOR_ACCURACY_M = 50.0
    }
}
