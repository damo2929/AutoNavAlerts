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
import kotlinx.coroutines.flow.StateFlow

/** Receives the Navigation SDK's turn-by-turn feed (registered via registerServiceForNavUpdates). */
class NavUpdatesService : Service() {
    private val manager = TurnByTurnManager.createInstance()

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == TurnByTurnManager.MSG_NAV_INFO) {
                runCatching { manager.readNavInfoFromBundle(msg.data) }.getOrNull()?.let { latest.value = it }
            } else {
                super.handleMessage(msg)
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        latest.value = null
        return super.onUnbind(intent)
    }

    companion object {
        /** Latest turn-by-turn state; null when no guidance is running. */
        val latest = MutableStateFlow<NavInfo?>(null)
        val navInfo: StateFlow<NavInfo?> get() = latest
    }
}
