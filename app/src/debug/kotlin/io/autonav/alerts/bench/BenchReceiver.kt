// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import io.autonav.alerts.App
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.car.screens.RoutePreviewScreen
import io.autonav.alerts.contacts.Located
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only (TESTING.md §3): drives the car screen from adb, because the DHU's window
 * can't be clicked from a script. Sends the same navigate intent Assistant would.
 *
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es q "Mum"        # Search with "Mum" typed in
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es geo "53.38,-1.47"  # route preview there
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es contact "Mum"  # as if the first "Mum" contact row was tapped
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es zoom -2       # like tapping − twice
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es osm download  # or download-any (mobile data too) / cancel / delete
 *   (add -p io.autonav.alerts: Android only delivers explicit broadcasts to manifest receivers)
 */
class BenchReceiver : BroadcastReceiver() {
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface Deps { fun speedLimits(): io.autonav.alerts.osm.SpeedLimitRepository }

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("osm")?.let { cmd ->
            val repo = dagger.hilt.android.EntryPointAccessors.fromApplication(context, Deps::class.java).speedLimits()
            when (cmd) {
                "download" -> repo.download(wifiOnly = true)
                "download-any" -> repo.download(wifiOnly = false)
                "cancel" -> repo.cancel()
                "delete" -> repo.delete()
                else -> Unit
            }
            App.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { Log.i(TAG, "OSM $cmd: ${repo.stats()}") }
            return
        }
        val session = NavSession.active
        if (session == null) { Log.w(TAG, "No car session"); return }
        intent.getStringExtra("contact")?.let { pickContact(session, it); return }
        intent.getStringExtra("zoom")?.toFloatOrNull()?.let { z ->
            val h = Handler(Looper.getMainLooper())
            h.post { session.surface.zoom(z) }
            h.postDelayed({ Log.i(TAG, "Zoom now ${session.surface.map.value?.cameraPosition?.zoom}") }, 1_500)
            return
        }
        val uri = intent.getStringExtra("geo")?.let { Uri.parse("geo:$it") }
            ?: intent.getStringExtra("q")?.let { Uri.parse("geo:0,0?q=" + Uri.encode(it)) }
        if (uri == null) { Log.w(TAG, "Nothing to do: give --es q <text> or --es geo <lat,lon>"); return }
        Log.i(TAG, "Bench: $uri")
        Handler(Looper.getMainLooper()).post { session.onNewIntent(Intent(CarContext.ACTION_NAVIGATE, uri)) }
    }

    /** Same as tapping the first matching contact row in Search (DESIGN.md §8.3). */
    private fun pickContact(session: NavSession, name: String) {
        val pending = goAsync()
        App.appScope.launch(Dispatchers.Main) {
            try {
                val deps = session.deps
                val c = deps.contacts().search(name).firstOrNull()
                if (c == null) { Log.w(TAG, "No contact address matches \"$name\""); return@launch }
                val r = deps.contacts().locate(c, deps.location().lastLocation.value)
                Log.i(TAG, "Contact ${c.title}: $r")
                if (r is Located.Found) {
                    session.carContext.getCarService(ScreenManager::class.java)
                        .push(RoutePreviewScreen(session.carContext, session, r.destination.copy(address = r.address)))
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "Bench" }
}
