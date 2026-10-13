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
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ei osmcheck 500   # random roads: does the lookup find their limit?
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es osm download  # or download-any (mobile data too) / cancel / delete
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez tworoutes true  # preview shows 2 rows for 1 route
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez views true      # log the car map window's views
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez overlays false  # hide our overlays (true shows)
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez search true  # the empty search screen
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez layers true  # More → POI layers
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es banner "'Speed camera'"  # a test alert banner for 8 s
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez ev true --ez tolls true  # route options
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez diag true  # last 100 lines of each debug log to logcat
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez go true    # Go on the route preview that's showing
 *   (add -p io.autonav.alerts: Android only delivers explicit broadcasts to manifest receivers)
 *   Every command lands in debug-nav.log as a `Bench` line (DESIGN.md §15.5).
 */
class BenchReceiver : BroadcastReceiver() {
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface Deps {
        fun speedLimits(): io.autonav.alerts.osm.SpeedLimitRepository
        fun settings(): io.autonav.alerts.settings.SettingsStore
        fun eventLog(): io.autonav.alerts.diag.EventLog
    }

    override fun onReceive(context: Context, intent: Intent) {
        val eventLog = dagger.hilt.android.EntryPointAccessors.fromApplication(context, Deps::class.java).eventLog()
        eventLog.record(io.autonav.alerts.diag.EventLog.Channel.NAV, "Bench", describe(intent))
        if (intent.getBooleanExtra("diag", false)) {
            // The end of each debug log to logcat, after a forced write of what's pending.
            eventLog.flush("bench")
            for (c in io.autonav.alerts.diag.EventLog.Channel.entries) {
                Log.i(TAG, "--- ${c.fileName}: ${eventLog.sizeBytes(c)} bytes, oldest ${eventLog.oldest(c) ?: "-"}")
                eventLog.tail(c, 100).forEach { Log.i(TAG, it) }
            }
            return
        }
        if (intent.hasExtra("ev") || intent.hasExtra("tolls")) {
            val store = dagger.hilt.android.EntryPointAccessors.fromApplication(context, Deps::class.java).settings()
            App.appScope.launch {
                store.update { s ->
                    s.copy(
                        evEfficientRoutes = if (intent.hasExtra("ev")) intent.getBooleanExtra("ev", false) else s.evEfficientRoutes,
                        avoidTolls = if (intent.hasExtra("tolls")) intent.getBooleanExtra("tolls", true) else s.avoidTolls,
                    )
                }
                val s = store.current()
                Log.i(TAG, "Route options: EV ${s.evEfficientRoutes}, avoid tolls ${s.avoidTolls}")
            }
            return
        }
        if (intent.hasExtra("osmcheck")) {
            // Random road pieces anywhere in the file: standing on each, facing along it, does the
            // lookup give its limit? Also facing the other way and standing still. Logs a summary
            // and a few positions to drive to.
            val n = intent.getIntExtra("osmcheck", 500)
            val repo = dagger.hilt.android.EntryPointAccessors.fromApplication(context, Deps::class.java).speedLimits()
            App.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val r = repo.reader() ?: run { Log.w(TAG, "OSM check: no limits file"); return@launch }
                val rnd = java.util.Random(intent.getLongExtra("seed", 1L))
                var along = 0; var reverse = 0; var still = 0; var tried = 0
                val wrong = ArrayList<String>()
                repeat(n) {
                    val tile = r.tileKeyAt(rnd.nextInt(r.tiles))
                    val segs = ArrayList<IntArray>()
                    r.forEachSegment(tile) { a, b, c, d, mph -> segs += intArrayOf(a, b, c, d, mph) }
                    if (segs.isEmpty()) return@repeat
                    val sg = segs[rnd.nextInt(segs.size)]
                    val lat = (sg[0] + sg[2]) / 2e6; val lon = (sg[1] + sg[3]) / 2e6
                    if (io.autonav.alerts.core.osm.OsmSpeedLimits.tileKey(lat, lon) != tile) return@repeat
                    val h = io.autonav.alerts.core.geo.Geo.bearingDeg(sg[0] / 1e6, sg[1] / 1e6, sg[2] / 1e6, sg[3] / 1e6)
                    val idx = io.autonav.alerts.core.osm.LimitIndex.build(r, io.autonav.alerts.core.osm.OsmSpeedLimits.around(lat, lon))
                    tried++
                    val a1 = idx.limitAt(lat, lon, h)?.toInt(); val a2 = idx.limitAt(lat, lon, h + 180)?.toInt(); val a3 = idx.limitAt(lat, lon, null)?.toInt()
                    if (a1 == sg[4]) along++ else if (wrong.size < 15) wrong += "%.5f,%.5f h%.0f want %d got %s".format(lat, lon, h, sg[4], a1)
                    if (a2 == sg[4]) reverse++
                    if (a3 == sg[4]) still++
                    if (it < 12) Log.i(TAG, "OSM sample %.6f %.6f %.0f %d".format(lat, lon, h, sg[4]))
                }
                Log.i(TAG, "OSM check: $tried random road pieces; facing along $along, facing back $reverse, standing still $still")
                wrong.forEach { Log.i(TAG, "OSM check miss: $it") }
            }
            return
        }
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
        intent.getStringExtra("import")?.let { name ->
            // Import a POI file from the app's own files/bench/ folder (put there with
            //   adb push f /data/local/tmp/f && adb shell run-as io.autonav.alerts sh -c 'mkdir -p files/bench && cp /data/local/tmp/f files/bench/'
            // because a debug app can't read /sdcard/Download without a storage grant). A .csv uses the
            // scenario layout lat,lon,name,speed,heading,dir (0-based columns; override with --es csvcols "0,1,2,3,4,5").
            // Re-importing the same file name replaces that set (TESTING.md §3 step 5, scenarios).
            val file = java.io.File(context.filesDir, "bench/$name")
            if (!file.isFile) { Log.w(TAG, "Import: no such file ${file.path}"); return }
            val cols = (intent.getStringExtra("csvcols") ?: "0,1,2,3,4,5").split(',').map { it.trim().toInt() }
            val profile = if (name.endsWith(".csv", true)) io.autonav.alerts.core.poi.importers.CsvProfile(
                latCol = cols[0], lonCol = cols[1], nameCol = cols.getOrNull(2), speedCol = cols.getOrNull(3),
                bearingCol = cols.getOrNull(4), dirTypeCol = cols.getOrNull(5),
            ) else null
            val pending = goAsync()
            io.autonav.alerts.App.appScope.launch {
                try {
                    val pois = io.autonav.alerts.car.CarDeps.from(context).pois()
                    val existing = pois.sets().values.firstOrNull { it.name == file.nameWithoutExtension || it.name == file.name }
                    val out = pois.importFile(android.net.Uri.fromFile(file), csvProfile = profile, replaceSetId = existing?.id)
                    val msg = "Import $name: ${if (out.failed) "FAILED " else ""}${out.imported} imported, ${out.skipped} skipped, set=${out.setId} ${out.message}"
                    Log.i(TAG, msg)
                    eventLog.record(io.autonav.alerts.diag.EventLog.Channel.NAV, "Bench", msg)
                } catch (t: Throwable) {
                    Log.w(TAG, "Import $name failed", t)
                } finally { pending.finish() }
            }
            return
        }
        if (intent.getBooleanExtra("engine", false)) {
            // The alert engine's snapshot (DESIGN.md §15.5): the same text as Settings → Diagnostics → Engine now,
            // to alert-debug and logcat. Needs no car session.
            val text = io.autonav.alerts.car.CarDeps.from(context).alerts().snapshot()
            text.lines().filter { it.isNotBlank() }.forEach { line ->
                eventLog.record(io.autonav.alerts.diag.EventLog.Channel.ALERT, "Engine", line)
                Log.i(TAG, "Engine: $line")
            }
            eventLog.flush("bench")
            return
        }
        val session = NavSession.active
        if (session == null) { Log.w(TAG, "No car session"); return }
        if (intent.getBooleanExtra("go", false)) {
            // Go on the showing route preview (DESIGN.md §15.5): remembers, starts guidance, pops.
            val carDeps = io.autonav.alerts.car.CarDeps.from(context)
            Handler(Looper.getMainLooper()).post {
                val preview = RoutePreviewScreen.showing
                if (preview == null) {
                    Log.w(TAG, "No route preview showing (guiding=${carDeps.guidance().guiding.value}, dest=${carDeps.guidance().destination.value?.title})")
                } else {
                    Log.i(TAG, "Go on the preview for ${carDeps.guidance().destination.value?.title}")
                    preview.go()
                }
            }
            return
        }
        if (intent.hasExtra("tworoutes")) {
            session.deps.guidance().debugTwoChoices = intent.getBooleanExtra("tworoutes", false)
            Log.i(TAG, "Two route rows: ${session.deps.guidance().debugTwoChoices}")
            return
        }
        if (intent.getBooleanExtra("views", false)) {
            // What's in the car map window, to see what draws (and how the map renders).
            Handler(Looper.getMainLooper()).post {
                fun walk(v: android.view.View, depth: Int) {
                    Log.i(TAG, "  ".repeat(depth) + "${v.javaClass.name} vis=${v.visibility} hw=${v.isHardwareAccelerated} layer=${v.layerType}")
                    if (v is android.view.ViewGroup && depth < 6) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
                }
                session.surface.overlayRoot?.let { walk(it, 0) } ?: Log.w(TAG, "No map window")
            }
            return
        }
        if (intent.hasExtra("follow")) {
            // false: like a tiny drag (the map stops following); true: like recenter.
            Handler(Looper.getMainLooper()).post {
                if (intent.getBooleanExtra("follow", true)) session.surface.recenter() else session.surface.onScroll(0f, 0f)
                Log.i(TAG, "Following: ${session.surface.isFollowing}")
            }
            return
        }
        if (intent.hasExtra("overlays")) {
            // Show or hide our own overlays (speed panel, buttons, banner), leaving only the map.
            val show = intent.getBooleanExtra("overlays", true)
            Handler(Looper.getMainLooper()).post {
                val root = session.surface.overlayRoot ?: return@post
                for (i in 1 until root.childCount) root.getChildAt(i).visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                Log.i(TAG, "Overlays ${if (show) "shown" else "hidden"}")
            }
            return
        }
        intent.getStringExtra("banner")?.let { text ->
            // A test alert banner (DESIGN.md §12.1), to check its place and size without a camera nearby.
            Handler(Looper.getMainLooper()).post {
                val b = session.surface.alertBanner ?: run { Log.w(TAG, "No map window"); return@post }
                b.show(-1L, null, text, "300 yd · 30 mph", 8_000)
                b.post { Log.i(TAG, "Banner at x=${b.left} y=${b.top} ${b.width}x${b.height} in ${(b.parent as android.view.View).width}x${(b.parent as android.view.View).height}") }
            }
            return
        }
        if (intent.getBooleanExtra("layers", false)) {
            // More → POI layers: each set with its map icon (DESIGN.md §8).
            Handler(Looper.getMainLooper()).post {
                session.carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                    .push(io.autonav.alerts.car.screens.PoiLayersScreen(session.carContext, session))
            }
            return
        }
        if (intent.getBooleanExtra("search", false)) {
            // The search screen with nothing typed: Home, Work, then recents (DESIGN.md §8.2).
            Handler(Looper.getMainLooper()).post {
                session.carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                    .push(io.autonav.alerts.car.screens.SearchScreen(session.carContext, session))
            }
            return
        }
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

    /** The command as typed: its extras, sorted, `key=value`. */
    @Suppress("DEPRECATION")
    private fun describe(intent: Intent): String {
        val b = intent.extras ?: return "(no extras)"
        return b.keySet().sorted().joinToString(" ") { k -> "$k=${b.get(k)}" }.ifEmpty { "(no extras)" }
    }

    private companion object { const val TAG = "Bench" }
}
