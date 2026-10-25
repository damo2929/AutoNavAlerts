// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.R
import io.autonav.alerts.core.alerts.AlertEvent
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.util.ensureChannel
import io.autonav.alerts.util.notifyIfAllowed
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Something that can show an alert on the car screen (set by the car session while visible):
 * the banner on the map surface, or the host `Alert` while the surface isn't ready (DESIGN.md
 * §12.1). `NavSession` sets [AlertDispatcher.carSink] on create and clears it on destroy.
 */
fun interface CarAlertSink {
    /**
     * Returns true if it was shown on the car screen.
     *
     * Called on the alert collector; the sink posts the banner update to the main thread itself (§12.1 Threading).
     */
    fun show(alert: ActiveAlert): Boolean
}

/**
 * Routes each alert to the right outputs (DESIGN.md §12, the routing table): the car screen
 * through [carSink] when our screen is up, else a navigation-category heads-up notification
 * (§12.2) that Android Auto shows over another app and the phone shows without a car; and speech
 * through [NavTts] (§12.3) whenever `speak` is true. The visual always goes out; only speech is
 * subject to mute (§12.3 "Mute: stop speech only"). Events are never dropped here: every call
 * reaches a visual and either the speech queue or a MUTED record.
 *
 * Lifecycle: a Hilt singleton. `App.onCreate` calls [ensureChannel]; `AlertCoordinator` calls
 * [dispatch] on the alert collector for every engine event, so this class does no main-thread
 * work itself (the sink and NavTts post to the main thread as they need).
 */
@Singleton
class AlertDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tts: NavTts,
    private val icons: IconRepository,
    private val eventLog: EventLog,
) {
    /** The car screen's alert surface while a session is up; null means "not in the foreground" (§12). */
    @Volatile var carSink: CarAlertSink? = null

    /** The `poi_alerts` channel (§12.2): high importance, no sound and no vibration, since the TTS does the speaking. */
    fun ensureChannel() {
        ensureChannel(context, CHANNEL, "POI alerts", NotificationManager.IMPORTANCE_HIGH, ALERT_CHANNEL)
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
    private fun notify(alert: ActiveAlert): Boolean = notifyIfAllowed(context, CHANNEL, "POI alerts", NotificationManager.IMPORTANCE_HIGH, ALERT_CHANNEL) {
        val large = icons.bitmap(alert.iconId, 128)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_action_report)
            .setLargeIcon(large)
            .setContentTitle(alert.display)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            // 10 s: a little longer than the banner's 8 s, so the heads-up doesn't outlive the approach (§12.2).
            .setTimeoutAfter(10_000)
            .extend(
                CarAppExtender.Builder()
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .setSmallIcon(R.drawable.ic_action_report)
                    .setLargeIcon(large)
                    .setContentTitle(alert.display)
                    .build(),
            )
        // One id per POI, so a SECOND or PASSING updates the FIRST's notification instead of stacking (§12.2).
        runCatching { CarNotificationManager.from(context).notify(NOTIFICATION_BASE + (alert.poiId % 10_000).toInt(), n) }.isSuccess
    } == true

    companion object {
        /** The notification channel id of §12.2. */
        const val CHANNEL = "poi_alerts"
        /** The channel's look (§12.2): a description, no sound and no vibration, since the TTS does the speaking. */
        private val ALERT_CHANNEL: NotificationChannel.() -> Unit = {
            description = "Speed camera and POI alerts"
            setSound(null, null)
            enableVibration(false)
        }
        /** Keeps alert ids clear of the app's other notifications (the speed-limit download's progress, §8.4a). */
        private const val NOTIFICATION_BASE = 20_000
    }
}
