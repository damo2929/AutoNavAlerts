// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.reports

import android.content.Context
import android.os.SystemClock
import androidx.car.app.connection.CarConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Never while driving" guard for report submission (DESIGN.md §8.5 "Never while driving"; hard rule 8,
 * AGENTS.md §3.1): blocked while the phone is connected to Android Auto, while a car session is open, or
 * after moving faster than 5 mph in the last 2 minutes. A Hilt singleton. `App` calls [observe] once on the
 * main thread; `NavSession` sets [carSessionOpen] and feeds [onSpeed] from every fix; `ReportSubmitWorker`
 * and the phone's Send button ask [blockedReason] (the button is disabled with that text).
 */
@Singleton
class DrivingState @Inject constructor(@ApplicationContext private val context: Context) {
    private val _carConnected = MutableStateFlow(false)
    /** Android Auto connection state from the Car App Library's `CarConnection` (any type but NOT_CONNECTED counts). */
    val carConnected: StateFlow<Boolean> = _carConnected

    /** True while our car `Session` is alive: a belt-and-braces check next to [carConnected], set by `NavSession`. */
    @Volatile var carSessionOpen = false
    // elapsedRealtime of the last fix over MOVING_MPS: monotonic, so a clock change can't unblock sending.
    @Volatile private var lastMovingMs = 0L

    /** Call once from the Application (main thread): `CarConnection.type` is LiveData and must be observed there. */
    fun observe() {
        CarConnection(context).type.observeForever { t ->
            _carConnected.value = t != null && t != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        }
    }

    /** Every fix's speed in m/s; only a fix over 5 mph counts as moving. */
    fun onSpeed(mps: Double) {
        if (mps > MOVING_MPS) lastMovingMs = SystemClock.elapsedRealtime()
    }

    /** Null when submitting is allowed, otherwise the reason it isn't, in the words shown to the user. */
    fun blockedReason(): String? = when {
        _carConnected.value || carSessionOpen -> "Disconnect from Android Auto first. Reports are never sent while driving."
        lastMovingMs > 0 && SystemClock.elapsedRealtime() - lastMovingMs < STILL_FOR_MS -> "Wait until you've stopped. Reports are never sent while driving."
        else -> null
    }

    companion object {
        /** 5 mph in m/s (§8.5 "moved faster than 5 mph"). */
        private const val MOVING_MPS = 2.235 // 5 mph
        /** The phone must have been still this long (§8.5 "in the last 2 minutes"). */
        private const val STILL_FOR_MS = 2 * 60_000L
    }
}
