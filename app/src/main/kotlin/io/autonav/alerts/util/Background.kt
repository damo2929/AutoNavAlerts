// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * What the app's background jobs and notifications share (DESIGN.md §6.2 the PocketGPSWorld feed
 * check and sync, §8.4a the speed-limit download, §8.5 the report submit and the end-of-drive
 * reminder, §12.2 the alert heads-up): the WorkManager network constraint, the notification
 * channel each one posts on, and the `POST_NOTIFICATIONS` gate every post goes through. Plain
 * functions, safe from any thread; nothing here touches the Navigation SDK or Maps (hard rule 5).
 */

/** A constraints builder for a job that needs the network: Wi-Fi (unmetered) only when [wifiOnly], else any connection. */
fun network(wifiOnly: Boolean = false): Constraints.Builder =
    Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)

/**
 * The notification manager with channel [id] in place: created with [name], [importance] and
 * [configure] (its description, sound, vibration) the first time, left alone after.
 */
fun ensureChannel(context: Context, id: String, name: String, importance: Int, configure: NotificationChannel.() -> Unit = {}): NotificationManager {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance).apply(configure))
    return nm
}

/**
 * Posts a notification only when the user granted `POST_NOTIFICATIONS`: then [channel] is made sure
 * of ([ensureChannel] with [name], [importance], [configure]) and [post] runs with the manager, and
 * its result is returned; without the permission nothing is posted and the result is null.
 */
fun <T> notifyIfAllowed(context: Context, channel: String, name: String, importance: Int, configure: NotificationChannel.() -> Unit = {}, post: (NotificationManager) -> T): T? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return null
    return post(ensureChannel(context, channel, name, importance, configure))
}
