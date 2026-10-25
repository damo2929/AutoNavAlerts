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
import android.view.View
import android.view.ViewGroup
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import io.autonav.alerts.App
import io.autonav.alerts.car.CarDeps
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.car.screens.MoreScreen
import io.autonav.alerts.car.screens.PoiLayersScreen
import io.autonav.alerts.car.screens.RoutePreviewScreen
import io.autonav.alerts.car.screens.SearchScreen
import io.autonav.alerts.contacts.Located
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.osm.LimitIndex
import io.autonav.alerts.core.osm.OsmSpeedLimits
import io.autonav.alerts.core.poi.importers.CsvProfile
import io.autonav.alerts.diag.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Random

/**
 * Debug builds only (TESTING.md §3 step 5a, §15; DESIGN.md §15.5): a `BroadcastReceiver` that
 * drives the car screen and a few app internals from adb, because the DHU's window can't be
 * clicked from a script and its dpad commands don't reach the app's screens. Lives in
 * `app/src/debug`, so release builds don't have it; declared in the debug manifest behind the
 * `android.permission.DUMP` permission, which only adb's shell holds, so nothing else on the
 * phone can send these. Sends the same navigate intent Assistant would.
 *
 * Called by Android on the main thread for every `io.autonav.alerts.BENCH` broadcast, a new
 * instance each time, so it keeps no state. Work that must leave the main thread goes to
 * [App.appScope] (with `goAsync()` where the receiver has to outlive `onReceive`); anything on
 * the Car App Library or the map is posted to the main looper (DESIGN.md §2 threads).
 *
 * Commands, one per broadcast (the first matching extra wins, in the order of the two `when`s in [onReceive] and [onCar]):
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
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez more true    # the More screen
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es banner "'Speed camera'"  # a test alert banner for 8 s
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez ev true --ez tolls true  # route options
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez diag true  # last 100 lines of each debug log to logcat
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez go true    # Go on the route preview that's showing
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez engine true  # the alert engine's snapshot to alert-debug and logcat
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --es import f.gpx  # import files/bench/f.gpx as a set (see the import branch)
 *   adb shell am broadcast -a io.autonav.alerts.BENCH --ez follow false  # stop the map following (true: like recenter)
 *   (add -p uk.damiendye.autonav, the application ID: Android only delivers explicit broadcasts to manifest receivers)
 *   Every command lands in debug-nav.log as a `Bench` line (DESIGN.md §15.5).
 *
 * The commands up to `engine` work with no car session; the rest need the DHU connected and
 * log "No car session" otherwise.
 */
class BenchReceiver : BroadcastReceiver() {
    /** Car App Library and map calls go on the main looper (DESIGN.md §2 threads). */
    private val main = Handler(Looper.getMainLooper())

    /**
     * Dispatches one command. Every broadcast is logged first, so a drive's log shows what the bench
     * did and when. The commands that need no car session are here; the rest are in [onCar]. A broadcast
     * with no known extra ends with a logcat hint.
     * The singletons come through `CarDeps` (a `BroadcastReceiver` isn't a Hilt component, so nothing is injected).
     */
    override fun onReceive(context: Context, intent: Intent) {
        val deps = CarDeps.from(context)
        val eventLog = deps.eventLog()
        eventLog.record(EventLog.Channel.NAV, "Bench", describe(intent))
        val osm = intent.getStringExtra("osm")
        val import = intent.getStringExtra("import")
        when {
            intent.getBooleanExtra("diag", false) -> {
                // The end of each debug log to logcat, after a forced write of what's pending.
                eventLog.flush("bench")
                for (c in EventLog.Channel.entries) {
                    Log.i(TAG, "--- ${c.fileName}: ${eventLog.sizeBytes(c)} bytes, oldest ${eventLog.oldest(c) ?: "-"}")
                    eventLog.tail(c, 100).forEach { Log.i(TAG, it) }
                }
            }
            // Route options (DESIGN.md §8.3a), written through SettingsStore like the phone's switches, so the next preview sees them.
            intent.hasExtra("ev") || intent.hasExtra("tolls") -> {
                val store = deps.settings()
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
            }
            intent.hasExtra("osmcheck") -> osmCheck(deps, intent)
            // The OpenStreetMap limits download (DESIGN.md §8.4a); download-any ignores the Wi-Fi-only setting for the bench.
            osm != null -> {
                val repo = deps.speedLimits()
                when (osm) {
                    "download" -> repo.download(wifiOnly = true)
                    "download-any" -> repo.download(wifiOnly = false)
                    "cancel" -> repo.cancel()
                    "delete" -> repo.delete()
                    else -> Unit
                }
                App.appScope.launch(Dispatchers.IO) { Log.i(TAG, "OSM $osm: ${repo.stats()}") }
            }
            import != null -> import(context, deps, intent, import)
            intent.getBooleanExtra("engine", false) -> {
                // The alert engine's snapshot (DESIGN.md §15.5): the same text as Settings → Diagnostics → Engine now,
                // to alert-debug and logcat. Needs no car session.
                val text = deps.alerts().snapshot()
                text.lines().filter { it.isNotBlank() }.forEach { line ->
                    eventLog.record(EventLog.Channel.ALERT, "Engine", line)
                    Log.i(TAG, "Engine: $line")
                }
                eventLog.flush("bench")
            }
            // Everything else acts on the car screen, so it needs a live session (the DHU connected).
            else -> {
                val session = NavSession.active
                if (session == null) Log.w(TAG, "No car session") else onCar(session, deps, intent)
            }
        }
    }

    /** The commands that act on the car screen, with the DHU connected. */
    private fun onCar(session: NavSession, deps: CarDeps, intent: Intent) {
        val banner = intent.getStringExtra("banner")
        val contact = intent.getStringExtra("contact")
        val zoom = intent.getStringExtra("zoom")?.toFloatOrNull()
        when {
            intent.getBooleanExtra("go", false) -> main.post {
                // Go on the showing route preview (DESIGN.md §15.5): remembers, starts guidance, pops.
                val preview = RoutePreviewScreen.showing
                if (preview == null) {
                    Log.w(TAG, "No route preview showing (guiding=${deps.guidance().guiding.value}, dest=${deps.guidance().destination.value?.title})")
                } else {
                    Log.i(TAG, "Go on the preview for ${deps.guidance().destination.value?.title}")
                    preview.go()
                }
            }
            // Makes the route preview show two rows for one route, to check the layout of the EV choice (DESIGN.md §8.3a).
            intent.hasExtra("tworoutes") -> {
                session.deps.guidance().debugTwoChoices = intent.getBooleanExtra("tworoutes", false)
                Log.i(TAG, "Two route rows: ${session.deps.guidance().debugTwoChoices}")
            }
            intent.getBooleanExtra("views", false) -> main.post {
                // What's in the car map window, to see what draws (and how the map renders).
                fun walk(v: View, depth: Int) {
                    Log.i(TAG, "  ".repeat(depth) + "${v.javaClass.name} vis=${v.visibility} hw=${v.isHardwareAccelerated} layer=${v.layerType}")
                    if (v is ViewGroup && depth < 6) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
                }
                session.surface.overlayRoot?.let { walk(it, 0) } ?: Log.w(TAG, "No map window")
            }
            intent.hasExtra("follow") -> main.post {
                // false: like a tiny drag (the map stops following); true: like recenter.
                if (intent.getBooleanExtra("follow", true)) session.surface.recenter() else session.surface.onScroll(0f, 0f)
                Log.i(TAG, "Following: ${session.surface.isFollowing}")
            }
            intent.hasExtra("overlays") -> main.post {
                // Show or hide our own overlays (speed panel, buttons, banner), leaving only the map.
                val show = intent.getBooleanExtra("overlays", true)
                val root = session.surface.overlayRoot ?: return@post
                for (i in 1 until root.childCount) root.getChildAt(i).visibility = if (show) View.VISIBLE else View.GONE
                Log.i(TAG, "Overlays ${if (show) "shown" else "hidden"}")
            }
            banner != null -> main.post {
                // A test alert banner (DESIGN.md §12.1), to check its place and size without a camera nearby.
                val b = session.surface.alertBanner ?: run { Log.w(TAG, "No map window"); return@post }
                b.show(-1L, null, banner, "300 yd · 30 mph", 8_000)
                b.post { Log.i(TAG, "Banner at x=${b.left} y=${b.top} ${b.width}x${b.height} in ${(b.parent as View).width}x${(b.parent as View).height}") }
            }
            // The More screen (DESIGN.md §8), for screenshots and the no-shake check (TESTING.md U2).
            intent.getBooleanExtra("more", false) -> push(session) { MoreScreen(session.carContext, session) }
            // More → POI layers: each set with its map icon (DESIGN.md §8).
            intent.getBooleanExtra("layers", false) -> push(session) { PoiLayersScreen(session.carContext, session) }
            // The search screen with nothing typed: Home, Work, then recents (DESIGN.md §8.2).
            intent.getBooleanExtra("search", false) -> push(session) { SearchScreen(session.carContext, session) }
            contact != null -> pickContact(session, contact)
            // Like tapping the zoom pill (DESIGN.md §8, zoom capped at 17); the zoom is read back after the map's animation.
            zoom != null -> {
                main.post { session.surface.zoom(zoom) }
                main.postDelayed({ Log.i(TAG, "Zoom now ${session.surface.map.value?.cameraPosition?.zoom}") }, 1_500)
            }
            else -> navigate(session, intent)
        }
    }

    /** The same ACTION_NAVIGATE intent Assistant and other apps send (DESIGN.md §9): geo:lat,lon previews a route; geo:0,0?q= opens Search with the text. */
    private fun navigate(session: NavSession, intent: Intent) {
        val uri = intent.getStringExtra("geo")?.let { Uri.parse("geo:$it") }
            ?: intent.getStringExtra("q")?.let { Uri.parse("geo:0,0?q=" + Uri.encode(it)) }
        if (uri == null) { Log.w(TAG, "Nothing to do: give --es q <text> or --es geo <lat,lon>"); return }
        Log.i(TAG, "Bench: $uri")
        main.post { session.onNewIntent(Intent(CarContext.ACTION_NAVIGATE, uri)) }
    }

    /**
     * Random road pieces anywhere in the file: standing on each, facing along it, does the
     * lookup give its limit? Also facing the other way and standing still. Logs a summary
     * and a few positions to drive to.
     */
    private fun osmCheck(deps: CarDeps, intent: Intent) {
        val n = intent.getIntExtra("osmcheck", 500)
        val repo = deps.speedLimits()
        // The whole check reads the limits file, so it runs on IO, never on the main thread the receiver was called on.
        App.appScope.launch(Dispatchers.IO) {
            val r = repo.reader() ?: run { Log.w(TAG, "OSM check: no limits file"); return@launch }
            val rnd = Random(intent.getLongExtra("seed", 1L))
            var along = 0; var reverse = 0; var still = 0; var tried = 0
            val wrong = ArrayList<String>()
            repeat(n) {
                val tile = r.tileKeyAt(rnd.nextInt(r.tiles))
                val segs = ArrayList<IntArray>()
                r.forEachSegment(tile) { a, b, c, d, mph -> segs += intArrayOf(a, b, c, d, mph) }
                if (segs.isEmpty()) return@repeat
                val sg = segs[rnd.nextInt(segs.size)]
                val lat = (sg[0] + sg[2]) / 2e6; val lon = (sg[1] + sg[3]) / 2e6
                if (OsmSpeedLimits.tileKey(lat, lon) != tile) return@repeat
                val h = Geo.bearingDeg(sg[0] / 1e6, sg[1] / 1e6, sg[2] / 1e6, sg[3] / 1e6)
                val idx = LimitIndex.build(r, OsmSpeedLimits.around(lat, lon))
                tried++
                val a1 = idx.limitAt(lat, lon, h)?.toInt(); val a2 = idx.limitAt(lat, lon, h + 180)?.toInt(); val a3 = idx.limitAt(lat, lon, null)?.toInt()
                // At most 15 misses and the first 12 samples are logged: enough to drive to, without flooding logcat.
                if (a1 == sg[4]) along++ else if (wrong.size < 15) wrong += "%.5f,%.5f h%.0f want %d got %s".format(lat, lon, h, sg[4], a1)
                if (a2 == sg[4]) reverse++
                if (a3 == sg[4]) still++
                if (it < 12) Log.i(TAG, "OSM sample %.6f %.6f %.0f %d".format(lat, lon, h, sg[4]))
            }
            Log.i(TAG, "OSM check: $tried random road pieces; facing along $along, facing back $reverse, standing still $still")
            wrong.forEach { Log.i(TAG, "OSM check miss: $it") }
        }
    }

    /**
     * Import a POI file from the app's own files/bench/ folder (put there with
     *   adb push f /data/local/tmp/f && adb shell run-as uk.damiendye.autonav sh -c 'mkdir -p files/bench && cp /data/local/tmp/f files/bench/'
     * because a debug app can't read /sdcard/Download without a storage grant). A .csv uses the
     * scenario layout lat,lon,name,speed,heading,dir (0-based columns; override with --es csvcols "0,1,2,3,4,5").
     * Re-importing the same file name replaces that set (TESTING.md §3 step 5, scenarios).
     */
    private fun import(context: Context, deps: CarDeps, intent: Intent, name: String) {
        val file = File(context.filesDir, "bench/$name")
        if (!file.isFile) { Log.w(TAG, "Import: no such file ${file.path}"); return }
        val cols = (intent.getStringExtra("csvcols") ?: "0,1,2,3,4,5").split(',').map { it.trim().toInt() }
        val profile = if (name.endsWith(".csv", true)) CsvProfile(
            latCol = cols[0], lonCol = cols[1], nameCol = cols.getOrNull(2), speedCol = cols.getOrNull(3),
            bearingCol = cols.getOrNull(4), dirTypeCol = cols.getOrNull(5),
        ) else null
        // The import outlives onReceive, so the receiver is kept alive until it finishes (or fails).
        val pending = goAsync()
        App.appScope.launch {
            try {
                val pois = deps.pois()
                // Set names come from the file name (DESIGN.md §5), so the same file name means the same set: replace, don't duplicate.
                val existing = pois.sets().values.firstOrNull { it.name == file.nameWithoutExtension || it.name == file.name }
                val out = pois.importFile(Uri.fromFile(file), csvProfile = profile, replaceSetId = existing?.id)
                val msg = "Import $name: ${if (out.failed) "FAILED " else ""}${out.imported} imported, ${out.skipped} skipped, set=${out.setId} ${out.message}"
                Log.i(TAG, msg)
                deps.eventLog().record(EventLog.Channel.NAV, "Bench", msg)
            } catch (t: Throwable) {
                Log.w(TAG, "Import $name failed", t)
            } finally { pending.finish() }
        }
    }

    /** Pushes a car screen on the main thread (for screenshots); the Car App Library is main-thread only (DESIGN.md §2). */
    private fun push(session: NavSession, screen: () -> Screen) {
        main.post { session.carContext.getCarService(ScreenManager::class.java).push(screen()) }
    }

    /**
     * Same as tapping the first matching contact row in Search (DESIGN.md §8.3): find the contact's
     * address, locate it (cached geocode, biased near the car's last fix) and push the route preview.
     * `goAsync()` keeps the receiver alive while the lookup runs; the whole thing runs on Main because
     * the push must, and the lookups are suspend calls that switch to IO themselves.
     */
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

    /** logcat tag: the bench scripts and TESTING.md grep for `Bench:` lines. */
    private companion object { const val TAG = "Bench" }
}
