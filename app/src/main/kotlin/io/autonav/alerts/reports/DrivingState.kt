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
 * "Never while driving" guard for report submission (DESIGN.md §8.5): blocked while the phone is
 * connected to Android Auto, while a car session is open, or after moving faster than 5 mph
 * in the last 2 minutes.
 */
@Singleton
class DrivingState @Inject constructor(@ApplicationContext private val context: Context) {
    private val _carConnected = MutableStateFlow(false)
    val carConnected: StateFlow<Boolean> = _carConnected

    @Volatile var carSessionOpen = false
    @Volatile private var lastMovingMs = 0L

    /** Call once from the Application (main thread). */
    fun observe() {
        CarConnection(context).type.observeForever { t ->
            _carConnected.value = t != null && t != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        }
    }

    fun onSpeed(mps: Double) {
        if (mps > MOVING_MPS) lastMovingMs = SystemClock.elapsedRealtime()
    }

    /** Null when submitting is allowed, otherwise the reason it isn't. */
    fun blockedReason(): String? = when {
        _carConnected.value || carSessionOpen -> "Disconnect from Android Auto first. Reports are never sent while driving."
        lastMovingMs > 0 && SystemClock.elapsedRealtime() - lastMovingMs < STILL_FOR_MS -> "Wait until you've stopped. Reports are never sent while driving."
        else -> null
    }

    companion object {
        private const val MOVING_MPS = 2.235 // 5 mph
        private const val STILL_FOR_MS = 2 * 60_000L
    }
}
