// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.annotation.SuppressLint
import android.app.Presentation
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import io.autonav.alerts.R
import io.autonav.alerts.car.CarDeps
import io.autonav.alerts.core.nav.FollowWatchdog
import io.autonav.alerts.diag.EventLog
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.FollowMyLocationOptions
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.NavigationViewForAuto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.ln

/**
 * Hosts the Navigation SDK map on the car's surface (DESIGN.md §8): SurfaceContainer →
 * VirtualDisplay → Presentation → NavigationViewForAuto. Also owns the speed panel overlay.
 *
 * [applyKey] hands the user's Maps key to the SDK and says whether there is one. The map is only
 * created after it succeeds: there is no manifest key (DESIGN.md §1.4), so a map made first crashes.
 * Without a key the surface stays empty and the home screen asks for one.
 */
class MapSurface(
    private val carContext: CarContext,
    private val applyKey: suspend () -> Boolean,
) : SurfaceCallback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val eventLog: EventLog by lazy { CarDeps.from(carContext).eventLog() }
    private var setup: Job? = null
    /** Free-drive-only follow check against the SDK's own position (DESIGN.md §8). */
    private val watchdog = FollowWatchdog()
    /** Set by `RoutePreviewScreen` while it's on screen: the route overview moves the camera on purpose. */
    @Volatile var previewShowing = false
    /** Whether guidance is running (the watchdog never acts then); set by `NavSession`. */
    @Volatile var guidingProvider: () -> Boolean = { false }
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var navView: NavigationViewForAuto? = null

    private val _map = MutableStateFlow<GoogleMap?>(null)
    val map: StateFlow<GoogleMap?> = _map

    var speedPanel: SpeedPanelView? = null
        private set
    /** The car map window's root view (debug bench checks of what draws). */
    var overlayRoot: FrameLayout? = null
        private set

    /** The alert banner at the bottom of the map (DESIGN.md §12.1), or null before the map exists. */
    var alertBanner: AlertBannerView? = null
        private set

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var following = true
    /**
     * The zoom the map follows the car at: MAX_ZOOM until the user picks another (+ / − or pinch).
     * Always passed to the SDK: its own follow zoom draws closer than the max-zoom cap and so
     * shows no traffic (DESIGN.md §8).
     */
    private var followZoom: Float = MAX_ZOOM
    private var container: SurfaceContainer? = null

    /** The satellite toggle at the top right of the map (DESIGN.md §8), or null before the map exists. */
    private var mapTypeButton: MapTypeButtonView? = null

    /** Zoom in, zoom out and recenter, in a column under the satellite toggle (DESIGN.md §8). */
    /** The column under the satellite toggle: the zoom pill and recenter (car API 5+). */
    private var mapButtons: List<android.view.View> = emptyList()
    /** Day or night colours for our buttons, from the car (DESIGN.md §8). */
    private var dark = true

    /** Satellite imagery (hybrid: with road names) instead of the road map. */
    var satellite: Boolean = false
        set(v) {
            field = v
            _map.value?.let { m ->
                m.mapType = if (v) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
                m.isTrafficEnabled = true
            }
            mapTypeButton?.let { b -> b.post { b.satellite = v } }
        }

    /** The user tapped the satellite toggle; the value is what they want now. */
    var onSatelliteToggle: ((Boolean) -> Unit)? = null

    /** Called when markers/camera listeners should attach. */
    var onMapReady: ((GoogleMap) -> Unit)? = null

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        setup?.cancel()
        setup = scope.launch { if (applyKey()) attach(container, "surfaceAvailable") }
    }

    @SuppressLint("MissingPermission")
    private fun attach(container: SurfaceContainer, reason: String) {
        val surface = container.surface ?: run { log("attach ($reason) skipped: no surface"); return }
        release()
        this.container = container
        surfaceWidth = container.width
        surfaceHeight = container.height
        log("surface ${surfaceWidth}×$surfaceHeight dpi=${container.dpi} ($reason)")
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "autonav-map", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        display = vd
        val pres = Presentation(carContext, vd.display)
        // NavigationViewForAuto takes no child views, so the speed panel sits beside it in a plain frame.
        val root = FrameLayout(pres.context).also { overlayRoot = it }
        val view = NavigationViewForAuto(pres.context)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pres.setContentView(root)
        view.onCreate(null)
        view.onStart()
        view.onResume()
        val sdkControlsOff = disableSdkControls(view, "resume")
        val panel = SpeedPanelView(pres.context)
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        })
        speedPanel = panel
        val banner = AlertBannerView(pres.context)
        root.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        })
        alertBanner = banner
        dark = carContext.isDarkMode
        val toggle = MapTypeButtonView(pres.context).apply { satellite = this@MapSurface.satellite; dark = this@MapSurface.dark }
        // Our buttons are on the right only (CHECKPOINT.md §5), anchored to the right edge from the
        // start: before the host reports the visible area they sit under the action strip at the right.
        val density = pres.context.resources.displayMetrics.density
        val clearTop = (ACTION_STRIP_CLEAR_DP * density).toInt() + 16
        root.addView(toggle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = 16
            topMargin = clearTop
        })
        mapTypeButton = toggle
        // Below car API 5 there's no onClick, so the host's map action strip keeps these instead.
        if (carContext.carAppApiLevel >= androidx.car.app.versioning.CarAppApiLevels.LEVEL_5) mapButtons = listOf(
            ZoomPillView(pres.context) { zoom(it) }.apply { dark = this@MapSurface.dark },
            MapButtonView(pres.context, R.drawable.ic_action_recenter) { recenter() }.apply { dark = this@MapSurface.dark },
        ).also { buttons ->
            var top = clearTop + ((MapTypeButtonView.HEIGHT_DP + MapButtonView.GAP_DP) * density).toInt()
            for (b in buttons) {
                root.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    rightMargin = 16
                    topMargin = top
                })
                top += ((heightDp(b) + MapButtonView.GAP_DP) * density).toInt()
            }
        }
        layoutOverlays()
        pres.show()
        presentation = pres
        navView = view
        view.getMapAsync { m ->
            runCatching { m.isMyLocationEnabled = true }
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isMyLocationButtonEnabled = false
            m.mapType = if (satellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
            m.isTrafficEnabled = true
            // The road map stops drawing traffic above zoom 17 in towns (satellite keeps it), and the follow
            // camera sits at 18. Capping the zoom keeps traffic on screen on both (DESIGN.md §8).
            m.setMaxZoomPreference(MAX_ZOOM)
            log("ready type=${m.mapType} traffic=${m.isTrafficEnabled} maxZoom=$MAX_ZOOM followZoom=$followZoom")
            // Again now the map exists: the SDK builds its ETA card and the rest lazily, and a setter
            // called before that had no effect on the bench (the card was still drawn, 2026-10-13).
            disableSdkControls(view, if (sdkControlsOff) "mapReady" else "final")
            following = true
            follow(m)
            _map.value = m
            applyPadding()
            onMapReady?.invoke(m)
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        setup?.cancel()
        release()
    }

    /** Ends the map for good (the car session is closing). */
    fun close() {
        scope.cancel()
        release()
    }

    override fun onVisibleAreaChanged(area: Rect) {
        log("visible $area stable=$stableArea surface=${surfaceWidth}×$surfaceHeight")
        visibleArea = area
        applyPadding()
    }

    override fun onStableAreaChanged(area: Rect) {
        stableArea = area
        applyPadding()
    }

    /** A tap on the map: the satellite toggle switches the map type; the alert banner dismisses the alert. */
    override fun onClick(x: Float, y: Float) {
        mapTypeButton?.let { t ->
            if (t.isShown && hit(t, x, y)) {
                log("tap ${x.toInt()},${y.toInt()} hit=satellite")
                onSatelliteToggle?.invoke(!satellite)
                return
            }
        }
        val button = mapButtons.firstOrNull { it.isShown && hit(it, x, y) }
        if (button != null) {
            val name = when (button) {
                is ZoomPillView -> button.tap(y - button.top)
                is MapButtonView -> { button.onTap(); "recenter" }
                else -> "button"
            }
            log("tap ${x.toInt()},${y.toInt()} hit=$name")
            return
        }
        val b = alertBanner
        if (b != null && b.showing != null && x >= b.left && x <= b.right && y >= b.top && y <= b.bottom) {
            log("tap ${x.toInt()},${y.toInt()} hit=banner")
            b.hide()
            return
        }
        log("tap ${x.toInt()},${y.toInt()} hit=none")
    }

    /** Day or night from the car: our buttons' colours follow it (DESIGN.md §8); they redraw only on a change. */
    fun setDark(dark: Boolean) {
        if (dark == this.dark) return
        this.dark = dark
        log("dark=$dark")
        navView?.let { v -> main.post { findNavigationView(v)?.let(::applyNightMode) } }
        mapTypeButton?.dark = dark
        for (b in mapButtons) when (b) {
            is ZoomPillView -> b.dark = dark
            is MapButtonView -> b.dark = dark
        }
    }

    private fun heightDp(v: android.view.View) = if (v is ZoomPillView) ZoomPillView.HEIGHT_DP else MapButtonView.SIZE_DP

    private fun hit(v: android.view.View, x: Float, y: Float) = x >= v.left && x <= v.right && y >= v.top && y <= v.bottom

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val m = _map.value ?: return
        followOff("scroll")
        m.moveCamera(CameraUpdateFactory.scrollBy(distanceX, distanceY))
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val m = _map.value ?: return
        followOff("fling")
        m.animateCamera(CameraUpdateFactory.scrollBy(-velocityX / 10f, -velocityY / 10f))
    }

    /** The user panned: follow mode ends until Recenter (logged once per pan, not per event). */
    private fun followOff(how: String) {
        if (following) log("followOff ($how)")
        following = false
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val m = _map.value ?: return
        val amount = (ln(scaleFactor.toDouble()) / ln(2.0)).toFloat()
        if (following) { zoom(amount); return }
        val focus = if (focusX < 0 || focusY < 0) null else Point(focusX.toInt(), focusY.toInt())
        m.moveCamera(if (focus != null) CameraUpdateFactory.zoomBy(amount, focus) else CameraUpdateFactory.zoomBy(amount))
    }

    /**
     * + / − (DESIGN.md §8). While following, keep following at the new zoom: a plain camera zoom
     * would be undone by the SDK's follow camera, which picks its own zoom.
     */
    fun zoom(delta: Float) {
        val m = _map.value ?: return
        if (!following) { m.animateCamera(CameraUpdateFactory.zoomBy(delta)); return }
        followZoom = (followZoom + delta).coerceIn(MIN_FOLLOW_ZOOM, MAX_ZOOM)
        follow(m)
    }

    fun recenter() {
        val m = _map.value ?: return
        log("recenter wasFollowing=$following")
        following = true
        followZoom = MAX_ZOOM
        follow(m)
    }

    /** Follows the car at [followZoom]. */
    @SuppressLint("MissingPermission")
    private fun follow(m: GoogleMap) {
        // Location permission is granted on the phone during setup; without it this is a no-op.
        runCatching {
            m.followMyLocation(GoogleMap.CameraPerspective.TILTED, FollowMyLocationOptions.builder().setZoomLevel(followZoom).build())
        }
    }

    val isFollowing: Boolean get() = following

    /**
     * The follow watchdog (DESIGN.md §8), fed the SDK's own road-snapped position by `NavSession`
     * (never our fix, which may be the car's GPS while the SDK map follows the phone's). Core
     * [FollowWatchdog] decides: nothing while panned, guiding or on the route preview; on a miss
     * (camera target more than 500 m from the SDK position) follow again at the chosen zoom; a
     * rebuild only after three misses 5 s apart. Also keeps Google traffic on (§8, always on).
     * Every decision goes to `debug-nav`; skips only when their reason changes (SDK fixes are ~1 Hz).
     */
    fun onSdkLocation(loc: Location) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onSdkLocation(loc) }; return }
        val m = _map.value ?: run { eventLog.onChange("map.watchdog", "no map", EventLog.Channel.NAV, "Map") { "watchdog skip (no map)" }; return }
        if (!m.isTrafficEnabled) m.isTrafficEnabled = true
        val now = SystemClock.elapsedRealtime()
        val ageMs = if (loc.elapsedRealtimeNanos > 0) (now - loc.elapsedRealtimeNanos / 1_000_000).coerceAtLeast(0)
            else (System.currentTimeMillis() - loc.time).coerceAtLeast(0)
        val target = m.cameraPosition.target
        val d = FloatArray(1)
        Location.distanceBetween(target.latitude, target.longitude, loc.latitude, loc.longitude, d)
        when (val r = watchdog.check(now, following, guidingProvider(), previewShowing, ageMs, d[0])) {
            is FollowWatchdog.Decision.None ->
                eventLog.onChange("map.watchdog", r.reason, EventLog.Channel.NAV, "Map") { "watchdog skip (${r.reason}) d=${d[0].toInt()} age=${ageMs}ms" }
            is FollowWatchdog.Decision.Refollow -> {
                eventLog.onChange("map.watchdog", "refollow", EventLog.Channel.NAV, "Map") { "watchdog miss" }
                log("refollow d=${r.distanceM.toInt()} age=${ageMs}ms zoom=$followZoom misses=${watchdog.misses}")
                follow(m)
            }
            is FollowWatchdog.Decision.Rebuild -> {
                eventLog.onChange("map.watchdog", "rebuild", EventLog.Channel.NAV, "Map") { "watchdog miss (third)" }
                log("rebuild d=${r.distanceM.toInt()} age=${ageMs}ms")
                container?.let { attach(it, "watchdog rebuild d=${r.distanceM.toInt()}") }
            }
        }
    }

    /**
     * The SDK's own on-map controls (header, ETA card, recenter, speedometer, trip progress bar)
     * must be off: no touch reaches the SDK view on Android Auto, so they'd be dead and overlap
     * ours (DESIGN.md §8, §16 row 23). Navigation SDK 8.0.0's `NavigationViewForAuto` has none of
     * the `set*Enabled` setters that `NavigationView` has (checked in the 8.0.0 jar), so they are
     * looked up by reflection, like `IncidentReporting`: a future SDK that adds them gets them
     * turned off with no code change, and the log says which were found.
     */
    /**
     * Turns the SDK's own on-map controls off (DESIGN.md §8 step 6): no touch reaches the SDK view,
     * so its ETA card, header, recenter, speedometer, trip bar and limit icon would be dead and
     * overlap ours. `NavigationViewForAuto` has no setters, but it wraps the phone `NavigationView`,
     * which has them all. Returns false when that child isn't there yet (the caller retries on map ready).
     */
    private fun disableSdkControls(view: NavigationViewForAuto, attempt: String): Boolean {
        val inner = findNavigationView(view)
        if (inner == null) {
            if (attempt == "final") log("sdkControls missing: no NavigationView inside NavigationViewForAuto ($attempt)")
            return false
        }
        val off = ArrayList<String>()
        val failed = ArrayList<String>()
        val setters: List<Pair<String, () -> Unit>> = listOf(
            "setHeaderEnabled" to { inner.setHeaderEnabled(false) },
            "setEtaCardEnabled" to { inner.setEtaCardEnabled(false) },
            "setRecenterButtonEnabled" to { inner.setRecenterButtonEnabled(false) },
            "setSpeedometerEnabled" to { inner.setSpeedometerEnabled(false) },
            "setTripProgressBarEnabled" to { inner.setTripProgressBarEnabled(false) },
            "setSpeedLimitIconEnabled" to { inner.setSpeedLimitIconEnabled(false) },
        )
        // The navigation camera (tilted, our padding) only works with the navigation UI on; a fresh start
        // had it off until the first route, so free drive showed a flat map with the car centred (DESIGN.md §8).
        val navUiWas = runCatching { inner.isNavigationUiEnabled }.getOrNull()
        if (navUiWas == false) runCatching { inner.setNavigationUiEnabled(true) }
        for ((name, call) in setters) runCatching(call).onSuccess { off += name }.onFailure { failed += name }
        applyNightMode(inner)
        // Belt and braces: the SDK draws its cards as ordinary child views beside the map container
        // (the one holding the GL texture view), and a setter called before a card exists did nothing
        // on the bench. Everything that isn't the map container is hidden; what was hidden is logged.
        val hid = ArrayList<String>()
        for (i in 0 until inner.childCount) {
            val c = inner.getChildAt(i)
            if (holdsMap(c)) continue
            if (c.visibility != android.view.View.GONE) { c.visibility = android.view.View.GONE; hid += c.javaClass.simpleName.ifEmpty { "view" } + "#$i" }
        }
        log("sdkControls off=$off${if (failed.isEmpty()) "" else " failed=$failed"}${if (hid.isEmpty()) "" else " hid=$hid"} navUi=$navUiWas ($attempt)")
        return true
    }

    /** The map's day/night style follows the car's, as Google Maps' does (DESIGN.md §8). */
    private fun applyNightMode(inner: NavigationView) {
        runCatching { inner.setForceNightMode(if (dark) ForceNightMode.FORCE_NIGHT else ForceNightMode.FORCE_DAY) }
            .onFailure { log("nightMode failed: ${it.message}") }
    }

    /** True for the SDK child that holds the map's GL view (never hidden). */
    private fun holdsMap(v: android.view.View): Boolean {
        if (v is android.opengl.GLSurfaceView || v is android.view.TextureView) return true
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (holdsMap(v.getChildAt(i))) return true
        return false
    }

    /** Re-applies the SDK-controls-off calls (when guidance starts or stops, from [NavSession]). */
    fun refreshSdkControls(reason: String) {
        // Guidance started or stopped: the car keeps its place (free drive pads differently, DESIGN.md §8).
        main.post { applyPadding() }
        val view = navView ?: return
        main.post { disableSdkControls(view, reason) }
    }

    private fun findNavigationView(root: android.view.ViewGroup): NavigationView? {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is NavigationView) return c
            if (c is android.view.ViewGroup) findNavigationView(c)?.let { return it }
        }
        return null
    }

    /** Google incident reporting on the car screen, if a future Navigation SDK supports it (§16 #21). */
    val incidentReportingSupported: Boolean get() = navView?.let(IncidentReporting::supportedBySdk) == true
    val incidentReportingAvailableNow: Boolean get() = navView?.let(IncidentReporting::availableNow) == true
    fun showIncidentPanel(): Boolean = navView?.let(IncidentReporting::showPanel) == true

    /** Places our overlays, then the map's fixed camera padding (DESIGN.md §8 "Where the car sits"). */
    private fun applyPadding() {
        layoutOverlays()
        val m = _map.value ?: return
        val density = presentation?.context?.resources?.displayMetrics?.density ?: return
        // Fixed padding, so the car sits in the same spot in free drive and guidance and never moves
        // with the host's cards: centred across the map, just above the alert banner (owner, DESIGN.md §8
        // "Where the car sits").
        val h = surfaceHeight.takeIf { it > 0 } ?: return
        val top = (ACTION_STRIP_CLEAR_DP * density).toInt()
        val bottom = (AlertBannerView.HEIGHT_DP * density).toInt() + 16 + (CAR_GAP_DP * density).toInt()
        if (guidingProvider()) {
            m.setPadding(0, top, 0, bottom)
        } else {
            // Free drive centres the car in the padded area; raise the top so that centre is where guidance puts it.
            val carY = top + GUIDED_CAR_FRACTION * (h - bottom - top)
            m.setPadding(0, (2 * carY - (h - bottom)).toInt().coerceIn(top, h - bottom - 1), 0, bottom)
        }
    }

    /**
     * Places our overlays in the visible area. It doesn't wait for the map: until the host reports
     * a visible area the whole surface is used, so the banner never starts full width over the speed
     * panel after a fresh start.
     */
    private fun layoutOverlays() {
        val w = surfaceWidth.takeIf { it > 0 } ?: return
        val h = surfaceHeight.takeIf { it > 0 } ?: return
        val area = stableArea ?: visibleArea ?: Rect(0, 0, w, h)
        speedPanel?.let { p ->
            (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                // Bottom right of the visible area (DESIGN.md §8.4).
                val v = visibleArea ?: area
                lp.rightMargin = (w - v.right).coerceAtLeast(0) + 16
                lp.bottomMargin = (h - v.bottom).coerceAtLeast(0) + 16
                p.layoutParams = lp
            }
        }
        // The banner: bottom centre of the visible area, kept clear of the speed panel on the left.
        alertBanner?.let { b ->
            (b.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val v = visibleArea ?: area
                val density = b.resources.displayMetrics.density
                val clear = (SPEED_PANEL_CLEAR_DP * density).toInt()
                val width = minOf(v.width() - clear * 2, (AlertBannerView.MAX_WIDTH_DP * density).toInt()).coerceAtLeast(v.width() / 2)
                lp.width = width
                lp.leftMargin = v.left + (v.width() - width) / 2
                lp.bottomMargin = (h - v.bottom).coerceAtLeast(0) + 16
                b.layoutParams = lp
            }
        }
        // The satellite toggle: top-right corner of the visible area. The host shrinks that area
        // while its action strip is showing, so the toggle moves down out of its way and back up.
        mapTypeButton?.let { t ->
            (t.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val v = visibleArea ?: area
                val density = t.resources.displayMetrics.density
                lp.rightMargin = (w - v.right).coerceAtLeast(0) + 16
                // Always below the action strip: it's persistent now, but the host sometimes reports a
                // visible area that ignores it (right after the map starts), which put the toggle under it.
                lp.topMargin = maxOf(v.top, stableArea?.top ?: 0, (ACTION_STRIP_CLEAR_DP * density).toInt()) + 16
                t.layoutParams = lp
                // The zoom pill, then recenter: down the right edge under the toggle.
                val gap = (MapButtonView.GAP_DP * density).toInt()
                var top = lp.topMargin + (MapTypeButtonView.HEIGHT_DP * density).toInt() + gap
                for (b in mapButtons) {
                    (b.layoutParams as? FrameLayout.LayoutParams)?.let { blp ->
                        blp.rightMargin = (w - v.right).coerceAtLeast(0) + 16
                        blp.topMargin = top
                        b.layoutParams = blp
                    }
                    top += (heightDp(b) * density).toInt() + gap
                }
            }
        }
    }

    fun release() {
        _map.value = null
        speedPanel = null
        alertBanner = null
        mapTypeButton = null
        mapButtons = emptyList()
        navView?.let { v ->
            runCatching { v.onPause(); v.onStop(); v.onDestroy() }.onFailure { Log.w(TAG, "navView teardown", it) }
        }
        navView = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
    }

    private fun log(msg: String) = eventLog.record(EventLog.Channel.NAV, "Map", msg)

    companion object {
        private const val TAG = "MapSurface"
        private const val MIN_FOLLOW_ZOOM = 3f
        /** `NavigationView`'s setters for its on-map controls; absent from `NavigationViewForAuto` in SDK 8.0.0. */
        /** Room left either side of the banner for the speed panel (its pill and a margin). */
        private const val SPEED_PANEL_CLEAR_DP = 140
        /** Height kept clear at the top for the host's action strip (its buttons end at 88 px on a 160 dpi DHU). */
        private const val ACTION_STRIP_CLEAR_DP = 88
        /** Closest zoom at which the road map still draws Google traffic (found on the bench: 17.5 has none in towns, 18 none anywhere). */
        const val MAX_ZOOM = 17f
        /** Gap between the alert banner's top and the followed car (owner, 2026-10-23). */
        private const val CAR_GAP_DP = 8
        /** Where the SDK's guidance camera puts the car: this far down the padded area (measured on the DHU, 2026-10-23). */
        private const val GUIDED_CAR_FRACTION = 0.917f
    }
}
