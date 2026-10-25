// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * What the app's background jobs share (DESIGN.md §6.2 the PocketGPSWorld feed check and sync,
 * §8.4a the speed-limit download, §8.5 the report submit and the end-of-drive reminder): the
 * WorkManager network constraint and the notification channel each job posts on. Plain functions,
 * safe from any thread; nothing here touches the Navigation SDK or Maps (hard rule 5). The alert
 * channel itself stays with `AlertDispatcher.ensureChannel` (§12.2).
 */

/** A constraints builder for a job that needs the network: Wi-Fi (unmetered) only when [wifiOnly], else any connection. */
fun network(wifiOnly: Boolean = false): Constraints.Builder =
    Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)

/** The notification manager with channel [id] in place: created with [name] and [importance] the first time, left alone after. */
fun ensureChannel(context: Context, id: String, name: String, importance: Int): NotificationManager {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    return nm
}
