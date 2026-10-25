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
import kotlin.math.abs

/**
 * What More → GPS shows (DESIGN.md §10.2, §10.6, §10.7), refreshed on every fix the engine gets.
 * Read on the main thread by the GPS row and by `AlertCoordinator.snapshot()`; the collector
 * compares rows before re-sending the template (hard rule 3: no re-send with unchanged content).
 */
data class LocationStats(
    /** The rate asked of the phone, Hz: always [AdaptiveLocationSource.GPS_HZ] (§10.2). */
    val requestedHz: Int,
    /** The rate actually delivered, Hz: 1000 over the mean gap of the last 20 fixes, 0 until two fixes. */
    val actualHz: Double,
    /** The engine's speed (the car's raw speed when fresh, else the estimator's). */
    val speedMph: Double,
    /** True while the engine's speed is the car's raw speed (a report in the last 3 s, §10.6). */
    val speedFromCar: Boolean = false,
    /** True while the car's GPS is the live feed (§10.7). */
    val locationFromCar: Boolean = false,
    /** The engine's heading, degrees true; null when there is none (parked, or right after a reset). */
    val headingDeg: Double? = null,
    /** `track` / `gps` / `blend` / `held` / `none` (lower-case [HeadingSource]). */
    val headingSrc: String = "",
    /** The heading's estimated error, degrees (§10.6a); null with no heading. */
    val headingAccDeg: Double? = null,
    /** `gps` / `displacement` / `car_raw` / `simulated` / `none`. */
    val speedSrc: String = "",
    /** The fix's horizontal accuracy, metres; null when the feed gives none. */
    val accuracyM: Double? = null,
    /** The length of the track chord the heading was taken over, metres (§10.6a: 10–60 m). */
    val chordM: Double? = null,
    /** The raw GPS bearing of the fix, degrees true, before any blending. */
    val gpsBrgDeg: Double? = null,
    /** The reason of the last `TRACK_RESET` (handover, gap, jump, simulation), for the GPS row. */
    val lastReset: String? = null,
    /** The dial speed shown on the panel (the car's display speed when fresh), mph. */
    val dialSpeedMph: Double? = null,
)

/**
 * The bridge from the estimator's [Estimate] to the engine's [Fix] (DESIGN.md §10.6a "what the
 * engine sees"). Pure, so it is unit-tested on the JVM: the `SpeedSource`/`HeadingSource` enums
 * differ between `core.location` and `core.alerts` (DISPLACEMENT is logged as `derived` by the engine),
 * and the mapping between them is kept here in one place rather than in the location source's fix
 * path. `LocationSource` is one enum (core.location; core.alerts aliases it), so it passes straight through.
 */
object EstimateMapper {
    /** The estimator's speed source as the engine names it (DISPLACEMENT is the engine's DERIVED). */
    fun speedSource(s: SpeedSource): io.autonav.alerts.core.alerts.SpeedSource = when (s) {
        SpeedSource.GPS -> io.autonav.alerts.core.alerts.SpeedSource.GPS
        SpeedSource.DISPLACEMENT -> io.autonav.alerts.core.alerts.SpeedSource.DERIVED
        SpeedSource.CAR_RAW -> io.autonav.alerts.core.alerts.SpeedSource.CAR_RAW
        SpeedSource.SIMULATED -> io.autonav.alerts.core.alerts.SpeedSource.SIMULATED
        SpeedSource.NONE -> io.autonav.alerts.core.alerts.SpeedSource.NONE
    }

    /** The estimator's heading source as the engine names it (track / gps / blend / held / none). */
    fun headingSource(h: HeadingSource): io.autonav.alerts.core.alerts.HeadingSource = when (h) {
        HeadingSource.GPS -> io.autonav.alerts.core.alerts.HeadingSource.GPS
        HeadingSource.TRACK -> io.autonav.alerts.core.alerts.HeadingSource.TRACK
        HeadingSource.BLEND -> io.autonav.alerts.core.alerts.HeadingSource.BLEND
        HeadingSource.HELD -> io.autonav.alerts.core.alerts.HeadingSource.HELD
        HeadingSource.NONE -> io.autonav.alerts.core.alerts.HeadingSource.NONE
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
     *
     * [e] is the estimator's result for this fix; [lat]/[lon] are the fix's own position, degrees;
     * [accuracyM] its horizontal accuracy, metres, or null; [carRawMps] and [carDispMps] the car's
     * measured and dial speeds, m/s, both null when the car's report isn't fresh. The returned
     * [Fix.timeMs] is the estimator's monotonic time (elapsedRealtime ms), never the wall clock.
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
            locationSource = e.locationSource,
            accuracyM = accuracyM,
        )
    }
}

/**
 * The raw location feed for the alert engine: the phone's fused location at the phone's own rate,
 * the car's GPS while fresh, and the Navigation SDK's simulator on the bench, turned into the
 * engine's [Fix] by the core [FixEstimator] (DESIGN.md §10.1, §10.2, §10.6a, §10.7). Every
 * `Location` that reaches this class goes to the [DriveRecorder] (used or not); the feeds' state
 * goes to `debug-nav.log` by sample and on change (§15.5).
 *
 * Rules upheld here (AGENTS.md §3.2; DESIGN.md §10.2, owner 2026-10-24): the phone's GPS is asked
 * for at its natural rate, [GPS_HZ] (1 Hz), with no minimum interval, so any faster fix the phone
 * already delivers is taken and the chip is never driven faster than it would run on its own; the
 * request is sent once and never changed while running. The car's speed and GPS come from
 * [CarDataSource] at the fastest rate the car offers (§10.6, §10.7) and take priority while fresh.
 *
 * Lifecycle: a Hilt singleton shared by the car session and the phone app (§2). `AlertCoordinator`
 * calls [start] when the car session begins and [stop] when it ends (§10.5). Fixes arrive on the
 * `adaptive-location` [HandlerThread] (phone), the main executor (car, via [CarDataSource]) or the
 * SDK's thread (simulator); [onLocation] is synchronised, so one fix is processed at a time and
 * [fixes] is emitted in order. The alert coordinator collects [fixes]; the GPS row and the marker
 * layer read [stats] and [lastLocation].
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
    /** The core heading and speed maths (§10.2, §10.6a); reset on every handover and simulation flip. */
    private val estimator = FixEstimator()
    private var running = false
    /** The last 20 gaps between fixes, ms, for the delivered-rate figure in [stats]. */
    private val intervals = ArrayDeque<Long>()
    private var lastArrivalMs = 0L
    /** The feed the last used fix came from; a different one means a handover (§10.7). */
    private var lastSource: LocationSource? = null
    /** `elapsedRealtime` of the last fix seen from each feed, for the handover line. */
    private var lastCarMs = 0L
    private var lastPhoneMs = 0L
    /** Car fixes in a row, each within [CarDataSource.LOCATION_FRESH_MS] of the last (§10.7 hysteresis). */
    private var carStreak = 0
    /** Whether the last fix was worse than [POOR_ACCURACY_M], so the flip is logged once each way. */
    private var accuracyPoor = false
    private var lastReset: String? = null
    /** The rate actually requested from the phone. */
    @Volatile private var phoneHz = -1
    @Volatile private var usingCarLocation = false

    /**
     * While true, real GPS is ignored and positions come from [inject] (Navigation SDK simulator).
     *
     * Set by GuidanceController through setSimulated() when a debug Simulate drive starts or stops (§10.7).
     */
    @Volatile var simulated = false
        private set

    private val _fixes = MutableSharedFlow<Fix>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /**
     * Raw fixes for the alert engine and the per-fix marker redraw.
     *
     * Capacity 1 with DROP_OLDEST: a slow collector gets the newest fix, never a backlog of stale ones.
     */
    val fixes: SharedFlow<Fix> = _fixes

    /** The last `Location` used, as Android gave it (the route lookahead and the eco re-ask origin read it). */
    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation

    /** What More → GPS shows; refreshed on every used fix. */
    private val _stats = MutableStateFlow(LocationStats(GPS_HZ, 0.0, 0.0))
    val stats: StateFlow<LocationStats> = _stats

    /** The fused provider's callback: runs on the `adaptive-location` thread's looper. */
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (simulated) {
                // Real GPS is recorded but not used during a simulated drive (§10.7).
                result.locations.forEach { record(it, LocationSource.PHONE, used = false, estimate = null) }
                return
            }
            // Car GPS takes priority while it's the live feed and fresh; the phone is only a backup (DESIGN.md §10.7).
            if (usingCarLocation && carData.carLocationFresh()) {
                result.locations.forEach { record(it, LocationSource.PHONE, used = false, estimate = null) }
                return
            }
            result.locations.forEach { onLocation(it, LocationSource.PHONE) }
        }
    }

    /**
     * Starts the phone request and hooks the car feed. Idempotent. Needs the fine-location permission,
     * which the phone app asks for before any car session (§13). The car listeners themselves are
     * started by the session through [CarDataSource.start]; this only subscribes to what they deliver.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        carData.onLocation = { loc -> if (!simulated) onCarLocation(loc) }
        thread = HandlerThread("adaptive-location").also { it.start() }
        request()
    }

    /**
     * Bench testing: feed simulated positions (DESIGN.md §15.3).
     *
     * Both directions reset the track (§10.6a) so a chord never spans real and simulated positions,
     * and the rate figure starts again so the GPS row doesn't show the simulator's rate as the phone's.
     */
    @Synchronized
    fun setSimulated(on: Boolean) {
        if (simulated == on) return
        simulated = on
        reset(if (on) "simulation on" else "simulation off")
        intervals.clear()
        lastSource = null
    }

    /**
     * A car fix: used at once while the car is the live feed; after the phone took over, only from
     * the second car fix in a row, so a slow head unit on the freshness edge doesn't flap (§10.7).
     */
    @Synchronized
    private fun onCarLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        carStreak = if (lastCarMs > 0 && now - lastCarMs <= CarDataSource.LOCATION_FRESH_MS) carStreak + 1 else 1
        if (lastSource == LocationSource.PHONE && carStreak < CAR_RETURN_FIXES) {
            lastCarMs = now
            record(loc, LocationSource.CAR, used = false, estimate = null)
            return
        }
        onLocation(loc, LocationSource.CAR)
    }

    /**
     * A simulated position from the Navigation SDK's road-snapped provider, fed by
     * `GuidanceController` during a debug Simulate drive (DESIGN.md §15.3). Ignored unless
     * [simulated] is on, so a late callback after the drive ends can't move the car.
     */
    fun inject(loc: Location) {
        if (simulated) onLocation(loc, LocationSource.SIM)
    }

    /**
     * Removes the phone request, unhooks the car feed and quits the thread. Idempotent. The estimator
     * keeps its state: the next [start] continues the track if the gap is short, else the estimator's
     * own 5 s gap rule resets it (§10.6a).
     */
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

    /**
     * Sends the one phone request (DESIGN.md §10.2), always at [GPS_HZ]. Does nothing when it is
     * already requested (a second [start] without a [stop]) or the thread isn't up.
     */
    @SuppressLint("MissingPermission")
    private fun request() {
        val looper = thread?.looper ?: return
        if (phoneHz == GPS_HZ) return
        phoneHz = GPS_HZ
        // 1 Hz → a 1000 ms interval: the natural GPS rate, which still refreshes the car map promptly after a restart (§10.2).
        val interval = 1000L / GPS_HZ
        client.removeLocationUpdates(callback)
        // The phone's own rate, never forced (DESIGN.md §10.2, owner 2026-10-24): asked at the natural
        // GPS rate, and any faster fix the phone already delivers is taken rather than dropped.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(0)
            .setMaxUpdateDelayMillis(0)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching { client.requestLocationUpdates(req, callback, looper) }
            .onFailure {
                Log.w(TAG, "Location request failed: ${it.message}")
                navLog("request failed: ${it.message}")
            }
        navLog("request interval=${interval}ms min=0")
    }

    /** Forgets the track (handover, simulation on/off) and says so (§10.6a, §15.5). */
    private fun reset(reason: String) {
        estimator.resetTrack(reason)
        lastReset = reason
        navLog("TRACK_RESET reason=$reason")
    }

    /** A `Location …` line in debug-nav (§15.5). */
    private fun navLog(msg: String) = eventLog.record(EventLog.Channel.NAV, "Location", msg)

    /**
     * One used fix from any feed: runs the estimator, builds the engine's [Fix], updates the stats,
     * writes the diagnostics and the recorder row, and emits the fix. Synchronised, so feeds that
     * arrive on different threads never interleave inside the estimator. [source] is the feed it came
     * from; the arrival time is taken on `elapsedRealtime` for the handover and gap bookkeeping.
     */
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
                navLog("source $flip (+$sinceOther ms)")
            } else {
                navLog("source ${source.name.lowercase()}")
            }
            lastSource = source
        }
        usingCarLocation = source == LocationSource.CAR
        // A gap over 5 s between used fixes is logged here; the estimator resets its track on the same gap (§10.6a).
        if (prevArrival > 0 && arrival - prevArrival > GAP_MS) navLog("gap ms=${arrival - prevArrival}")

        val estimate = estimator.onFix(toInput(loc, source, arrival))
        estimate.resetReason?.let { r ->
            lastReset = r
            navLog("TRACK_RESET reason=$r")
        }
        // Car speed when the head unit provides it (DESIGN.md §10.6); simulated drives use their own.
        val car = if (simulated) null else carData.freshSpeed(arrival)
        val accuracy = loc.accM
        val fix = EstimateMapper.toFix(
            estimate, loc.latitude, loc.longitude, accuracy,
            carRawMps = car?.rawMps?.toDouble(), carDispMps = car?.displayMps?.toDouble(),
        )

        // A moving average over the last 20 gaps: the "actual Hz" the GPS row shows (§10.2).
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
        // Worse than 50 m is logged once on the way in and once on the way out (§15.5 `accuracy>50m`), never per fix.
        val poor = accuracy != null && accuracy > POOR_ACCURACY_M
        if (poor != accuracyPoor) {
            accuracyPoor = poor
            navLog(if (poor) "accuracy>50m acc=${accuracy?.toInt()}" else "accuracy ok acc=${accuracy?.toInt()}")
        }
        eventLog.sample("fix", 5_000, EventLog.Channel.NAV, "Location") {
            "fix lat=${"%.5f".format(loc.latitude)} lon=${"%.5f".format(loc.longitude)} acc=${accuracy?.toInt() ?: "-"}" +
                " spd=${"%.1f".format(fix.speedMps)} hdg=${fix.heading?.toInt() ?: "-"} src=${source.name.lowercase()}" +
                " hz=${"%.1f".format(actualHz)} gpsBrg=${estimate.gpsBearingDeg?.toInt() ?: "-"} track=${estimate.trackDeg?.toInt() ?: "-"}"
        }
        record(loc, source, used = true, estimate = estimate, fix = fix, car = car)

        _fixes.tryEmit(fix)
    }

    /**
     * The estimator's input from an Android `Location`: position in degrees, accuracy in metres,
     * speed in m/s, bearing in degrees true with its accuracy, each null when the fix lacks it, so the
     * estimator can apply its own rules (a reported 0 speed is a real 0; displacement only without
     * `hasSpeed()`, §10.2).
     */
    private fun toInput(loc: Location, source: LocationSource, arrivalMs: Long): FixInput = FixInput(
        lat = loc.latitude, lon = loc.longitude,
        // One monotonic clock for every feed (§10.7): the fix's own boot-time stamp when it's sane,
        // else when it reached us. Location.time is a GNSS or car clock and some feeds stamp 0.
        timeMs = (loc.elapsedRealtimeNanos / 1_000_000).takeIf { it > 0 && abs(arrivalMs - it) < 10_000 } ?: arrivalMs,
        accuracyM = loc.accM, speedMps = loc.spdMps, bearingDeg = loc.brgDeg,
        bearingAccuracyDeg = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else null,
        source = source,
    )

    // A `Location`'s optional fields as the estimator and the recorder take them: metres, m/s and
    // degrees true as Double, null when the fix lacks the field (a missing speed is not 0, §10.2).
    private val Location.accM: Double? get() = if (hasAccuracy()) accuracy.toDouble() else null
    private val Location.spdMps: Double? get() = if (hasSpeed()) speed.toDouble() else null
    private val Location.brgDeg: Double? get() = if (hasBearing()) bearing.toDouble() else null

    /** One recorder row per `Location` (DESIGN.md §15.5), dropped ones included. Cheap when not recording. */
    private fun record(loc: Location, source: LocationSource, used: Boolean, estimate: Estimate?, fix: Fix? = null, car: CarSpeed? = null) {
        driveRecorder.record(
            DriveLog.RecordedFix(
                tMs = System.currentTimeMillis(), kind = DriveLog.KIND_FIX, src = EstimateMapper.recorderSrc(source), used = used,
                lat = loc.latitude, lon = loc.longitude, accM = loc.accM, spdMps = loc.spdMps, brgDeg = loc.brgDeg, hasBrg = loc.hasBearing(),
                altM = if (loc.hasAltitude()) loc.altitude else null, fixTMs = loc.time,
                engSpdMps = fix?.speedMps, engHdgDeg = fix?.heading, hdgSrc = estimate?.headingSource?.name?.lowercase(),
                carRawMps = car?.rawMps?.toDouble(), carDispMps = car?.displayMps?.toDouble(),
            ),
        )
    }

    companion object {
        private const val TAG = "AdaptiveLocation"
        /** The natural GPS rate asked for; faster fixes the phone offers are accepted (DESIGN.md §10.2). */
        const val GPS_HZ = 1
        /** A gap between used fixes longer than this is logged (`Location gap ms=`; §10.6a resets the track on it too). */
        private const val GAP_MS = 5_000L
        /** Car fixes in a row before the car takes over from the phone again (§10.7). */
        private const val CAR_RETURN_FIXES = 2
        /** Horizontal accuracy worse than this is logged as poor (§15.5 `accuracy>50m`). */
        private const val POOR_ACCURACY_M = 50.0
    }
}
