// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.R
import io.autonav.alerts.core.alerts.AlertEvent
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.diag.EventLog
import javax.inject.Inject
import javax.inject.Singleton

/** Something that can show an alert on the car screen (set by the car session while visible). */
fun interface CarAlertSink {
    /** Returns true if it was shown on the car screen. */
    fun show(alert: ActiveAlert): Boolean
}

/** Routes each alert to the car Alert, a nav heads-up notification, and speech (DESIGN.md §12). */
@Singleton
class AlertDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tts: NavTts,
    private val icons: IconRepository,
    private val eventLog: EventLog,
) {
    @Volatile var carSink: CarAlertSink? = null

    fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "POI alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Speed camera and POI alerts"
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    /**
     * Shows the alert (car screen, else a heads-up notification) and speaks it unless [speak] is
     * off (muted), in which case the scheduler records it as MUTED so the ring and the STAGE line
     * say why it was silent (DESIGN.md §12.3). The event's kind, distance and staleness go with it.
     */
    fun dispatch(alert: ActiveAlert, event: AlertEvent, speak: Boolean, chime: Boolean) {
        val shownInCar = runCatching { carSink?.show(alert) == true }.getOrDefault(false)
        val via = when {
            shownInCar -> "car"
            notify(alert) -> "notification"
            else -> "none"
        }
        eventLog.record(EventLog.Channel.ALERT, "Dispatch", "shown via=$via ${event.kind} poi=${alert.poiId} speak=$speak")
        if (speak) {
            tts.speak(alert.spoken, event.priority, alert.poiId, chime && event.priority >= 100, event.kind, event.distanceM, event.staleBelowM)
        } else {
            tts.recordMuted(alert.poiId, event.kind, alert.spoken, event.distanceM)
        }
    }

    /** Returns true when the heads-up notification was posted. */
    private fun notify(alert: ActiveAlert): Boolean {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        ensureChannel()
        val large = icons.bitmap(alert.iconId, 128)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_action_report)
            .setLargeIcon(large)
            .setContentTitle(alert.display)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(10_000)
            .extend(
                CarAppExtender.Builder()
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .setSmallIcon(R.drawable.ic_action_report)
                    .setLargeIcon(large)
                    .setContentTitle(alert.display)
                    .build(),
            )
        return runCatching { CarNotificationManager.from(context).notify(NOTIFICATION_BASE + (alert.poiId % 10_000).toInt(), n) }.isSuccess
    }

    companion object {
        const val CHANNEL = "poi_alerts"
        private const val NOTIFICATION_BASE = 20_000
    }
}
