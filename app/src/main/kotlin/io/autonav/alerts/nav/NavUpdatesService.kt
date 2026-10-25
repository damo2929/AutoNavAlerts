// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Receives the Navigation SDK's turn-by-turn feed (registered via `registerServiceForNavUpdates`
 * in `GuidanceController.startGuidance`, unregistered in `stopGuidance`; DESIGN.md §9.3, §12.3).
 * The SDK binds this bound service and posts a `NavInfo` bundle on every guidance update; the
 * latest one is published through [navInfo], a process-wide StateFlow, for the turn card
 * (`TurnByTurnMapper`), the cluster trip (`TripPublisher`, §12.4) and the spoken directions
 * (`DirectionsSpeaker`). Declared in the manifest; the SDK creates it, so it isn't Hilt-managed
 * and keeps no injected state. Messages are handled on the main looper.
 */
class NavUpdatesService : Service() {
    /** The SDK's decoder for the `NavInfo` bundle it sends in each message. */
    private val manager = TurnByTurnManager.createInstance()

    /** The binder the SDK talks to; one message type, [TurnByTurnManager.MSG_NAV_INFO]. */
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {
                // A bundle that fails to decode (an SDK version skew) is dropped rather than crashing guidance.
                runCatching { manager.readNavInfoFromBundle(msg.data) }.getOrNull()?.let { navInfo.value = it }
            } else {
                super.handleMessage(msg)
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /** The SDK let go (guidance ended): the feed goes null so the turn card and cluster clear (§9.3). */
    override fun onUnbind(intent: Intent?): Boolean {
        navInfo.value = null
        return super.onUnbind(intent)
    }

    companion object {
        /** The latest turn-by-turn state (the current step, distances, ETA and nav state), null without guidance; written only here. */
        val navInfo = MutableStateFlow<NavInfo?>(null)
    }
}
