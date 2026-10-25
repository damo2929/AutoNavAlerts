// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.util

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The shared background helpers (DESIGN.md §6.2 the sync's network rule, §8.4a the Wi-Fi-only download,
 * §8.5 and §12.2 the notifications): a channel is created once with its settings and never re-made over the
 * user's changes, nothing is posted without POST_NOTIFICATIONS, and the network constraint follows Wi-Fi only.
 * Runs on the test thread with Robolectric's notification manager and permission shadows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class NotificationsTest {
    private lateinit var app: Application
    private lateinit var nm: NotificationManager

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        nm = app.getSystemService(NotificationManager::class.java)
    }

    private fun post(nm: NotificationManager, title: String): Int {
        nm.notify(7, NotificationCompat.Builder(app, "test_channel").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).build())
        return 7
    }

    @Test fun aChannelIsMadeOnceWithItsSettingsAndThenLeftAlone() {
        val got = ensureChannel(app, "test_channel", "Test channel", NotificationManager.IMPORTANCE_LOW) {
            description = "What this channel is for"
            enableVibration(false)
            setSound(null, null)
        }
        assertEquals(nm, got)
        val ch = nm.getNotificationChannel("test_channel")
        assertEquals("Test channel", ch.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
        assertEquals("What this channel is for", ch.description)
        assertNull(ch.sound)

        // A second call with other settings changes nothing: the channel the user may have tuned stays as it is.
        var configured = false
        ensureChannel(app, "test_channel", "Renamed", NotificationManager.IMPORTANCE_HIGH) { configured = true }
        assertFalse(configured)
        assertEquals("Test channel", nm.getNotificationChannel("test_channel").name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel("test_channel").importance)
        assertEquals(1, nm.notificationChannels.size)
    }

    @Test fun withoutThePermissionNothingIsPostedAndNoChannelIsMade() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var ran = false
        val result = notifyIfAllowed(app, "test_channel", "Test channel", NotificationManager.IMPORTANCE_DEFAULT) { ran = true; post(it, "x") }
        assertNull(result)
        assertFalse(ran)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertNull(nm.getNotificationChannel("test_channel"))
    }

    @Test fun withThePermissionTheChannelIsMadeAndThePostRuns() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val result = notifyIfAllowed(app, "test_channel", "Test channel", NotificationManager.IMPORTANCE_DEFAULT) { post(it, "Posted") }
        assertEquals(7, result)
        val n: Notification = shadowOf(nm).allNotifications.single()
        assertEquals("Posted", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("test_channel", n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel("test_channel").importance)
    }

    @Test fun theNetworkRuleIsAnyConnectionUnlessWifiOnly() {
        assertEquals(NetworkType.CONNECTED, network().build().requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, network(wifiOnly = true).build().requiredNetworkType)
        assertFalse(network().build().requiresBatteryNotLow())
    }
}
