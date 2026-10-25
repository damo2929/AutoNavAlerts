// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.content.Context
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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.util.component1
import androidx.core.util.component2
import androidx.core.view.updateLayoutParams
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import io.autonav.alerts.R
import io.autonav.alerts.car.CarDeps
import io.autonav.alerts.core.nav.FollowWatchdog
import io.autonav.alerts.diag.EventLog
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
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
import kotlin.math.abs
import kotlin.math.ln

/**
 * Hosts the Navigation SDK map on the car's surface (DESIGN.md §8): SurfaceContainer →
 * VirtualDisplay → Presentation → NavigationViewForAuto. Also owns the speed panel overlay.
 *
 * [applyKey] hands the user's Maps key to the SDK and says whether there is one. The map is only
 * created after it succeeds: there is no manifest key (DESIGN.md §1.4), so a map made first crashes.
 * Without a key the surface stays empty and the home screen asks for one.
 *
 * It is the `SurfaceCallback` the session registers with the host's `AppManager`, and the one
 * owner of everything drawn on our surface (§8 "Map surface set-up"): the SDK view, the speed
 * panel (§8.4), the alert banner (§12.1), the satellite pill and the zoom/recenter column
 * (§8 "Map buttons"), the map's camera frame ([applyView]: padding, follow, zoom lock; §8 "Where
 * the car sits", "One view with and without a route"), the follow watchdog (§8), the map type and
 * traffic (§8), day and night (§8 "Looking like Google Maps"), the host's visible and stable
 * areas and their per-size memory (§8 "Visible and stable areas"), and taps ([onClick]).
 *
 * Lifecycle: created by `NavSession.onCreateScreen`; the host drives it through
 * `onSurfaceAvailable` / `onSurfaceDestroyed` (the map is built and released with the surface,
 * and rebuilt on the same surface by the watchdog), and `NavSession.onDestroy` calls [close].
 * Every host callback and every map call is on the main thread; [onSdkLocation] and [applyView]
 * post themselves there when called from elsewhere. `setup` runs on its own
 * `Dispatchers.Main.immediate` scope so a surface lost mid-set-up cancels it.
 *
 * Hard rules it upholds: the SDK's own on-map controls are turned off and hidden and ours are the
 * only live ones (rule 2, [disableSdkControls]); every button is on the right-hand side, the speed
 * panel bottom right, the banner bottom centre (rule 4, [layoutOverlays]); the frame, the overlays
 * and the saved areas are written only when they changed (rule 3); and no map exists before the
 * user's own key is applied (rule 5).
 */
class MapSurface(
    private val carContext: CarContext,
    private val applyKey: suspend () -> Boolean,
) : SurfaceCallback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val eventLog: EventLog by lazy { CarDeps.from(carContext).eventLog() }
    private var setup: Job? = null
    /**
     * The follow check against the SDK's own position, in free drive and guidance (DESIGN.md §8
     * "Follow watchdog"; the pure rules are in `:core`). Not while panned or on the route preview.
     */
    private val watchdog = FollowWatchdog()
    /** Set by `RoutePreviewScreen` while it's on screen: the route overview moves the camera on purpose, so the zoom lock lifts (§8). */
    @Volatile var previewShowing = false
        set(v) { if (field != v) { field = v; applyView("preview") } }
    /**
     * Whether `HomeScreen`'s template puts one of the host's cards over the map's left (turn card,
     * travel estimate, set-up message): set in its `onGetTemplate`, before the template goes out, so the
     * visible area the host reports after it is read right (DESIGN.md §8 "Where the car sits").
     */
    @Volatile var homeCard = false
    /** A host card over the map's left: the route preview's, or Home's. The cards never push the car (§8). */
    private val hostCard: Boolean get() = previewShowing || homeCard
    /**
     * Whether guidance is running; set by `NavSession`. Guidance pads the map differently (the SDK
     * puts the car low) and the watchdog judges by the drawn position rather than the camera target then (§8).
     */
    @Volatile var guidingProvider: () -> Boolean = { false }
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var navView: NavigationViewForAuto? = null

    private val _map = MutableStateFlow<GoogleMap?>(null)
    /** The `GoogleMap` once ready, null before and after release; observed by the bench hooks. */
    val map: StateFlow<GoogleMap?> = _map

    /**
     * Our views on the map window's frame (§8 "Map surface set-up" step 4), made together by [attach]
     * and dropped together by [release]: the speed panel (§8.4), the alert banner (§12.1), the
     * satellite toggle and the zoom/recenter column under it (§8 "Map buttons"; empty below car API 5).
     */
    private class Overlays(val panel: SpeedPanelView, val banner: AlertBannerView, val toggle: MapTypeButtonView, val buttons: List<MapButton>)
    private var overlays: Overlays? = null
    /** The speed panel at the bottom right (DESIGN.md §8.4), or null before the map exists; `NavSession` posts updates to it. */
    val speedPanel: SpeedPanelView? get() = overlays?.panel
    /** The alert banner at the bottom of the map (DESIGN.md §12.1), or null before the map exists. */
    val alertBanner: AlertBannerView? get() = overlays?.banner
    /** The satellite toggle at the top right of the map (DESIGN.md §8), or null before the map exists. */
    private val mapTypeButton: MapTypeButtonView? get() = overlays?.toggle
    /** The column under the satellite toggle: the zoom pill and recenter (car API 5+; DESIGN.md §8 "Map buttons"). */
    private val mapButtons: List<MapButton> get() = overlays?.buttons.orEmpty()
    /** The car map window's root view (debug bench checks of what draws). */
    var overlayRoot: FrameLayout? = null
        private set

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    /** The whole car surface in pixels, or null before it exists (the marker layer's bounds, §8.1). */
    val surfaceSize: android.util.Size? get() = if (surfaceWidth > 0 && surfaceHeight > 0) android.util.Size(surfaceWidth, surfaceHeight) else null
    /** The host's stable area (never covered by its transient UI) and visible area (clear of its cards), in surface pixels (§8). */
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    /** The part of the surface the host shows our map in (DESIGN.md §8): the car is centred across it. */
    private var panel: Rect? = null
    /** The host's left card inset last measured against [panel]. */
    private var cardInset = 0
    /** Camera follow is on (the user hasn't panned since the last recenter). */
    private var following = true
    /**
     * The zoom the map follows the car at: MAX_ZOOM until the user picks another (+ / − or pinch).
     * Always passed to the SDK: its own follow zoom draws closer than the max-zoom cap and so
     * shows no traffic (DESIGN.md §8).
     */
    private var followZoom: Float = MAX_ZOOM
    private var container: SurfaceContainer? = null
    /** Day or night colours for our buttons, from the car (DESIGN.md §8). */
    private var dark = true

    /**
     * Satellite imagery (hybrid: with road names) instead of the road map. Only the map type changes:
     * every view rule (padding, follow, zoom, markers, buttons) is the same code for both (DESIGN.md §8,
     * owner: "no separate pathways"), so never branch on this anywhere else.
     */
    var satellite: Boolean = false
        set(v) {
            field = v
            _map.value?.let { m ->
                // A new map type drops the SDK's follow camera (DESIGN.md §8): the frame is set again.
                if (setMapType(m)) applyView("mapType")
            }
            mapTypeButton?.let { b -> b.post { b.satellite = v } }
        }

    /** The one place the map type is set (DESIGN.md §8); true when it changed. Traffic is always on. */
    private fun setMapType(m: GoogleMap): Boolean {
        val type = if (satellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
        val changed = (m.mapType != type).also { if (it) m.mapType = type }
        m.isTrafficEnabled = true
        return changed
    }

    /** The user tapped the satellite toggle; the value is what they want now. `NavSession` writes the setting, which comes back through [satellite]. */
    var onSatelliteToggle: ((Boolean) -> Unit)? = null

    /** Called when markers/camera listeners should attach. Fires again after a watchdog rebuild (a new map). */
    var onMapReady: ((GoogleMap) -> Unit)? = null

    /** Step 1 of the set-up (§8 "Order of events"): wait for the key, then build the map; a set-up still running is cancelled first. */
    override fun onSurfaceAvailable(container: SurfaceContainer) {
        setup?.cancel()
        setup = scope.launch { if (applyKey()) attach(container, "surfaceAvailable") }
    }

    // Steps 3 to 6 of §8 "Order of events": release the old map, VirtualDisplay and Presentation on
    // the host's Surface, the frame with the SDK view and our overlays, then the map callback.
    // Also the watchdog's rebuild path (same surface, a new map).
    @SuppressLint("MissingPermission")
    private fun attach(container: SurfaceContainer, reason: String) {
        val surface = container.surface ?: run { log("attach ($reason) skipped: no surface"); return }
        release()
        this.container = container
        surfaceWidth = container.width
        surfaceHeight = container.height
        log("surface ${surfaceWidth}×$surfaceHeight dpi=${container.dpi} ($reason)")
        // Areas belong to the surface they were reported for; a report before any surface counts for this one.
        if (areasFor != null && areasFor != sizeKey) { visibleArea = null; stableArea = null; panel = null; cardInset = 0 }
        areasFor = sizeKey
        restoreAreas()
        saveAreas()
        // OWN_CONTENT_ONLY: the display shows only what we draw on it, at the container's size and dpi.
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
        // Drawing order (§8 step 4): the SDK view, then the speed panel (bottom end, §8.4), then the banner.
        val panel = SpeedPanelView(pres.context)
        root.addView(panel, lp(Gravity.BOTTOM or Gravity.END))
        // Anchored at the start only so a left margin can place it; layoutOverlays centres it (§12.1).
        val banner = AlertBannerView(pres.context)
        root.addView(banner, lp(Gravity.BOTTOM or Gravity.START))
        dark = carContext.isDarkMode
        // Our buttons are on the right only (CHECKPOINT.md §5), anchored to the right edge from the start;
        // their margins come from layoutOverlays below, before the window shows.
        val toggle = MapTypeButtonView(pres.context).apply { satellite = this@MapSurface.satellite; dark = this@MapSurface.dark }
        root.addView(toggle, lp(Gravity.TOP or Gravity.END))
        // Below car API 5 there's no onClick, so the host's map action strip keeps these instead.
        val buttons = if (carContext.carAppApiLevel < androidx.car.app.versioning.CarAppApiLevels.LEVEL_5) emptyList() else listOf(
            ZoomPillView(pres.context) { zoom(it) },
            MapButtonView(pres.context, R.drawable.ic_action_recenter) { recenter() },
        ).onEach { b ->
            b.dark = dark
            root.addView(b, lp(Gravity.TOP or Gravity.END))
        }
        overlays = Overlays(panel, banner, toggle, buttons)
        layoutOverlays()
        pres.show()
        presentation = pres
        navView = view
        // Step 5 (§8): my-location on, the SDK's compass and location button off (dead on Android Auto,
        // hard rule 2), the map type and traffic, the zoom cap, then the frame and the session's listeners.
        view.getMapAsync { m ->
            runCatching { m.isMyLocationEnabled = true }
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isMyLocationButtonEnabled = false
            setMapType(m)
            // The road map stops drawing traffic above zoom 17 in towns (satellite keeps it), and the follow
            // camera sits at 18. Capping the zoom keeps traffic on screen on both (DESIGN.md §8).
            m.setMaxZoomPreference(MAX_ZOOM)
            log("ready type=${m.mapType} traffic=${m.isTrafficEnabled} maxZoom=$MAX_ZOOM followZoom=$followZoom")
            // Again now the map exists: the SDK builds its ETA card and the rest lazily, and a setter
            // called before that had no effect on the bench (the card was still drawn, 2026-10-13).
            disableSdkControls(view, if (sdkControlsOff) "mapReady" else "final")
            following = true
            _map.value = m
            applyView("ready")
            onMapReady?.invoke(m)
        }
    }

    /** Wrap-content layout params at [gravity]; the margins are [layoutOverlays]'s (hard rule 4). */
    private fun lp(gravity: Int) = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity)

    /** Tear-down (§8): the set-up is cancelled and the view, Presentation and VirtualDisplay released. The surface may come back. */
    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        setup?.cancel()
        release()
    }

    /** Ends the map for good (the car session is closing). */
    fun close() {
        scope.cancel()
        release()
    }

    /**
     * The host's visible area changed (§8 "Visible and stable areas"): the overlays follow it, the
     * map panel is worked out from it, it is saved for this surface size, and the frame is re-applied
     * (a follow is sent only when the padding actually changed).
     */
    override fun onVisibleAreaChanged(area: Rect) {
        log("visible $area stable=$stableArea surface=${surfaceWidth}×$surfaceHeight")
        if (!usable(area)) return
        visibleArea = area
        areasFor = sizeKey
        updatePanel(area)
        saveAreas()
        applyView("visible")
    }

    /** The host's stable area changed: kept for the satellite pill's top (always below the action strip) and saved. */
    override fun onStableAreaChanged(area: Rect) {
        if (!usable(area)) return
        stableArea = area
        areasFor = sizeKey
        saveAreas()
        applyView("stable")
    }

    /** A report narrower or shorter than the smallest map (a screen without the map, or nothing) changes nothing (§8). */
    private fun usable(area: Rect?): Boolean = area != null && area.width() >= MIN_MAP_PX && area.height() >= MIN_MAP_PX

    /** "w×h" of the surface the areas were reported for, or null before any surface. */
    private var areasFor: String? = null
    private val sizeKey: String? get() = if (surfaceWidth > 0 && surfaceHeight > 0) "${surfaceWidth}x$surfaceHeight" else null

    /**
     * The map panel from the host's report (DESIGN.md §8 "Where the car sits"): with no card of ours up
     * the visible area is the panel; with one up the panel keeps and the card's inset is measured, unless
     * the right edge moved (the host re-laid out with the card up), when the panel follows it.
     */
    private fun updatePanel(area: Rect) {
        val p = panel
        val next = when {
            !hostCard -> Rect(area)
            // A report with a card up and no panel known yet says nothing about the panel: the padding
            // uses the visible area until a card-free report comes (never this one, it would stick).
            p == null -> null
            abs(area.right - p.right) > PANEL_MOVE_PX -> Rect(area.left - cardInset, area.top, area.right, area.bottom)
            else -> p
        }
        cardInset = if (hostCard && next != null) (area.left - next.left).coerceAtLeast(0) else 0
        if (next != p) { panel = next; log("panel $next card=$hostCard inset=$cardInset") }
    }

    /**
     * The host reports the visible and stable areas only when they change, and what it last told us
     * outlives our process (DESIGN.md §8 "Visible and stable areas"): after a restart under a connected
     * head unit nothing arrives, so the last areas for this surface size are kept and restored.
     */
    private fun saveAreas() {
        val w = surfaceWidth.takeIf { it > 0 } ?: return
        val value = "${visibleArea?.flattenToString() ?: ""}|${stableArea?.flattenToString() ?: ""}|${panel?.flattenToString() ?: ""}|$cardInset"
        // The host sends a burst of reports while its layout animates; one write per change, not per report.
        if (value == lastSaved) return
        lastSaved = value
        areaPrefs.edit().putString("${w}x$surfaceHeight", value).apply()
    }

    private var lastSaved: String? = null

    // Before any callback for a new surface: the last areas saved for this exact size, each checked
    // to be usable and inside the surface (§8 "Visible and stable areas", bench 2026-10-23).
    private fun restoreAreas() {
        if (visibleArea != null || stableArea != null) return
        val saved = areaPrefs.getString("${surfaceWidth}x$surfaceHeight", null) ?: return
        val f = saved.split('|')
        val inside = Rect(0, 0, surfaceWidth, surfaceHeight)
        fun rect(i: Int) = Rect.unflattenFromString(f.getOrElse(i) { "" })?.takeIf { usable(it) && inside.contains(it) }
        visibleArea = rect(0)
        stableArea = rect(1)
        panel = rect(2)
        cardInset = f.getOrNull(3)?.toIntOrNull() ?: 0
        log("visible restored $visibleArea stable=$stableArea panel=$panel inset=$cardInset surface=${surfaceWidth}×$surfaceHeight")
    }

    private val areaPrefs by lazy { carContext.getSharedPreferences("map_surface", Context.MODE_PRIVATE) }

    /**
     * A tap on the map: the satellite toggle switches the map type; the alert banner dismisses the alert.
     *
     * Car API 5+ only (the host doesn't deliver taps below it). No touch ever reaches the SDK
     * view: [x], [y] in surface pixels are hit-tested against our views alone, top of the column
     * first, then the banner, and each tap is logged with what it hit (§8 "Map surface set-up").
     */
    override fun onClick(x: Float, y: Float) {
        val toggle = mapTypeButton?.takeIf { it.isShown && hit(it, x, y) }
        val button = mapButtons.firstOrNull { it.isShown && hit(it, x, y) }
        val banner = alertBanner?.takeIf { it.showing != null && hit(it, x, y) }
        val name = when {
            toggle != null -> { onSatelliteToggle?.invoke(!satellite); "satellite" }
            button is ZoomPillView -> button.tap(y - button.top)
            button is MapButtonView -> { button.onTap(); "recenter" }
            button != null -> "button"
            banner != null -> { banner.hide(); "banner" }
            else -> "none"
        }
        log("tap ${x.toInt()},${y.toInt()} hit=$name")
    }

    /** Day or night from the car: our buttons' colours follow it (DESIGN.md §8); they redraw only on a change. */
    fun setDark(dark: Boolean) {
        if (dark == this.dark) return
        this.dark = dark
        log("dark=$dark")
        navView?.let { v -> main.post { findNavigationView(v)?.let(::applyNightMode) } }
        mapTypeButton?.dark = dark
        for (b in mapButtons) b.dark = dark
    }

    // Our views are direct children of the frame, so their left/top are surface coordinates.
    private fun hit(v: View, x: Float, y: Float) = x >= v.left && x <= v.right && y >= v.top && y <= v.bottom

    /** A drag pans the map and enters pan mode, which turns camera-follow off (§8 "Gestures"). */
    override fun onScroll(distanceX: Float, distanceY: Float) = pan("scroll", CameraUpdateFactory.scrollBy(distanceX, distanceY), animate = false)

    // A fling gives a short animated pan (§8 "Gestures"); follow goes off as for a scroll. The velocity is
    // px/s; a tenth of it is a comfortable overshoot on the car screen.
    override fun onFling(velocityX: Float, velocityY: Float) = pan("fling", CameraUpdateFactory.scrollBy(-velocityX / 10f, -velocityY / 10f), animate = true)

    /** A user pan ([how] for the log): follow goes off, then the camera moves by [update], at once or animated. */
    private fun pan(how: String, update: CameraUpdate, animate: Boolean) {
        val m = _map.value ?: return
        followOff(how)
        if (animate) m.animateCamera(update) else m.moveCamera(update)
    }

    /** The user panned: follow mode ends until Recenter (logged once per pan, not per event). */
    private fun followOff(how: String) {
        if (following) log("followOff ($how)")
        following = false
        applyView("followOff")
    }

    /** A pinch (§8 "Gestures"): while following it changes the follow zoom like + / −; panned, it zooms about the focus. */
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val m = _map.value ?: return
        // A scale factor of 2 is one zoom level; log2 turns the factor into levels.
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
        // Between 3 and the 17 cap; the new value lasts the whole session, route or no route (§8).
        followZoom = (followZoom + delta).coerceIn(MIN_FOLLOW_ZOOM, MAX_ZOOM)
        applyView("zoom")
    }

    /** Recenter (§8 "Gestures"): follow comes back on at the zoom the user chose; the camera frame is re-applied. */
    fun recenter() {
        val m = _map.value ?: return
        log("recenter wasFollowing=$following zoom=$followZoom")
        // The chosen zoom lasts the session (DESIGN.md §8): recenter only puts the car back.
        following = true
        applyView("recenter")
    }

    /** Follows the car at [followZoom]. */
    @SuppressLint("MissingPermission")
    private fun follow(m: GoogleMap) {
        // Location permission is granted on the phone during setup; without it this is a no-op.
        runCatching { m.followMyLocation(GoogleMap.CameraPerspective.TILTED, FollowMyLocationOptions.builder().setZoomLevel(followZoom).build()) }
    }

    /** Whether the map is following the car (for the bench hooks and the session's logs). */
    val isFollowing: Boolean get() = following

    /**
     * The follow watchdog (DESIGN.md §8), fed the SDK's own road-snapped position by `NavSession`
     * (never our fix, which may be the car's GPS while the SDK map follows the phone's). Core
     * [FollowWatchdog] decides: nothing while panned or on the route preview, or on an SDK fix
     * older than 10 s; the car counts as followed when it is drawn inside the padded map at the
     * locked zoom ([carInPlace], free drive and guidance alike); the 500 m camera-target test is
     * only the fallback before the map has a frame, and never in guidance. On a miss, follow
     * again at the chosen zoom; a rebuild only after three misses 5 s apart. Also keeps Google
     * traffic on (§8, always on). Every decision goes to `debug-nav`; skips only when their
     * reason changes (SDK fixes are ~1 Hz). Any thread; posts itself to the main thread.
     */
    fun onSdkLocation(loc: Location) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onSdkLocation(loc) }; return }
        val m = _map.value ?: run { eventLog.onChange("map.watchdog", "no map", EventLog.Channel.NAV, "Map") { "watchdog skip (no map)" }; return }
        // Traffic is always on (§8): if guidance, a rebuild or the SDK turned it off, it's back within one fix.
        if (!m.isTrafficEnabled) m.isTrafficEnabled = true
        val now = SystemClock.elapsedRealtime()
        // The fix's age from the monotonic clock when the SDK gives it, else from wall time.
        val ageMs = if (loc.elapsedRealtimeNanos > 0) (now - loc.elapsedRealtimeNanos / 1_000_000).coerceAtLeast(0)
            else (System.currentTimeMillis() - loc.time).coerceAtLeast(0)
        val target = m.cameraPosition.target
        val d = FloatArray(1)
        Location.distanceBetween(target.latitude, target.longitude, loc.latitude, loc.longitude, d)
        // In guidance: is the car drawn inside the padded map at the locked zoom (DESIGN.md §8 "Follow watchdog")?
        val inPlace = carInPlace(m, loc)
        when (val r = watchdog.check(now, following, guidingProvider(), previewShowing, ageMs, d[0], inPlace)) {
            is FollowWatchdog.Decision.None ->
                eventLog.onChange("map.watchdog", r.reason, EventLog.Channel.NAV, "Map") { "watchdog skip (${r.reason}) d=${d[0].toInt()} age=${ageMs}ms" }
            is FollowWatchdog.Decision.Refollow -> {
                eventLog.onChange("map.watchdog", "refollow", EventLog.Channel.NAV, "Map") { "watchdog miss" }
                log("refollow d=${r.distanceM.toInt()} age=${ageMs}ms zoom=$followZoom misses=${watchdog.misses}")
                applyView("watchdog")
            }
            is FollowWatchdog.Decision.Rebuild -> {
                eventLog.onChange("map.watchdog", "rebuild", EventLog.Channel.NAV, "Map") { "watchdog miss (third)" }
                log("rebuild d=${r.distanceM.toInt()} age=${ageMs}ms")
                container?.let { attach(it, "watchdog rebuild d=${r.distanceM.toInt()}") }
            }
        }
    }

    /**
     * Whether the car is in place: the SDK's position projects inside the padded map area (a margin of a
     * tenth of the surface each way) and the camera is at the locked zoom; null before the map has a
     * frame. Same for both map types (DESIGN.md §8 "One view with and without a route").
     */
    private fun carInPlace(m: GoogleMap, loc: Location): Boolean? {
        val pad = lastPadding ?: return null
        val (w, h) = surfaceSize ?: return null
        val p = runCatching { m.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude)) }.getOrNull() ?: return null
        val mx = w / 10; val my = h / 10
        val onScreen = p.x >= pad[0] - mx && p.x <= w - pad[2] + mx && p.y >= pad[1] - my && p.y <= h - pad[3] + my
        return onScreen && abs(m.cameraPosition.zoom - followZoom) <= ZOOM_TOLERANCE
    }

    /** The padding [applyPadding] last set, as l, t, r, b in pixels, for [carInPlace]. */
    private var lastPadding: IntArray? = null

    /**
     * Turns the SDK's own on-map controls off (DESIGN.md §8 step 6): no touch reaches the SDK view,
     * so its ETA card, header, recenter, speedometer, trip bar and limit icon would be dead and
     * overlap ours. `NavigationViewForAuto` has no setters, but it wraps the phone `NavigationView`,
     * which has them all. Returns false when that child isn't there yet (the caller retries on map ready).
     *
     * This is hard rule 2 in code: nothing on the car screen that doesn't work, no function shown
     * twice. The `sdkControls off=[…]` log line is what TESTING.md U1 checks; a `missing` means a
     * later SDK changed the structure and the bottom-left ETA card with its dead X may be back.
     */
    private fun disableSdkControls(view: NavigationViewForAuto, attempt: String): Boolean {
        val inner = findNavigationView(view)
        if (inner == null) {
            if (attempt == "final") log("sdkControls missing: no NavigationView inside NavigationViewForAuto ($attempt)")
            return false
        }
        val off = ArrayList<String>()
        val failed = ArrayList<String>()
        // Logged by their own names (`KFunction.name` is the referenced method's source name, no reflection library needed).
        val setters = listOf(
            inner::setHeaderEnabled, inner::setEtaCardEnabled, inner::setRecenterButtonEnabled,
            inner::setSpeedometerEnabled, inner::setTripProgressBarEnabled, inner::setSpeedLimitIconEnabled,
        )
        // The navigation camera (tilted, our padding) only works with the navigation UI on; a fresh start
        // had it off until the first route, so free drive showed a flat map with the car centred (DESIGN.md §8).
        val navUiWas = runCatching { inner.isNavigationUiEnabled }.getOrNull()
        if (navUiWas == false) {
            runCatching { inner.setNavigationUiEnabled(true) }
            // Switching it on drops our padding, and a follow sent while it was off stays flat with the car
            // centred (bench, after a reinstall): the frame is set again.
            applyView("navUi on")
        }
        for (s in setters) runCatching { s(false) }.onSuccess { off += s.name }.onFailure { failed += s.name }
        applyNightMode(inner)
        // Belt and braces: the SDK draws its cards as ordinary child views beside the map container
        // (the one holding the GL texture view), and a setter called before a card exists did nothing
        // on the bench. Everything that isn't the map container is hidden; what was hidden is logged.
        val hid = ArrayList<String>()
        for (i in 0 until inner.childCount) {
            val c = inner.getChildAt(i)
            if (holdsMap(c)) continue
            if (c.visibility != View.GONE) { c.visibility = View.GONE; hid += c.javaClass.simpleName.ifEmpty { "view" } + "#$i" }
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
    private fun holdsMap(v: View): Boolean = v.firstDescendant { it is android.opengl.GLSurfaceView || it is android.view.TextureView } != null

    /** Re-applies the SDK-controls-off calls (when guidance starts or stops, from [NavSession]). */
    fun refreshSdkControls(reason: String) {
        // Guidance started or stopped: the car keeps its place (free drive pads differently, DESIGN.md §8),
        // and the view stays put: the SDK moves its camera a moment after stopGuidance returns, so settle.
        applyView(reason, settle = true)
        val view = navView ?: return
        main.post { disableSdkControls(view, reason) }
    }

    // The phone NavigationView inside NavigationViewForAuto, found by type (no obfuscated names, §8 step 6).
    private fun findNavigationView(root: ViewGroup): NavigationView? = root.firstDescendant { it is NavigationView } as NavigationView?

    /** This view or the first in its subtree, depth first, that [match]es; null when none does. */
    private fun View.firstDescendant(match: (View) -> Boolean): View? {
        if (match(this)) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).firstDescendant(match)?.let { return it }
        return null
    }

    /**
     * The one place the camera frame is set (DESIGN.md §8 "One view for both map types"): our overlays,
     * the map padding, then the follow while following. Every event that can move the camera or change
     * the frame comes here. [settle] follows again over 2 s, for SDK events that move the camera a moment
     * later (guidance start/stop); a follow already in place changes nothing on screen. Logged once per
     * event, only when the padding or the follow zoom changed (no shake, no log spam).
     */
    private fun applyView(reason: String, settle: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { applyView(reason, settle) }; return }
        layoutOverlays()
        val m = _map.value ?: return
        val padding = applyPadding(m)
        val locked = applyZoomLock(m)
        val frame = "$padding@$followZoom${if (locked) "!" else ""}"
        val changed = frame != lastFrame
        // A camera request only with a reason: the frame changed, or the SDK's camera was moved or
        // dropped. A host report that left the padding as it was sends nothing (no shake).
        val refollow = following && (changed || settle || reason in FOLLOW_REASONS)
        if (refollow) follow(m)
        if (changed) { lastFrame = frame; log("view $reason padding=$padding zoom=$followZoom following=$following") }
        if (settle && following) {
            main.removeCallbacks(settleFollow)
            for (ms in REFOLLOW_MS) main.postDelayed(settleFollow, ms)
        }
    }

    /**
     * The one view with and without a route, hard set (DESIGN.md §8): while following, and not on the
     * route preview, the map's min and max zoom are both [followZoom], so the SDK's own camera can't
     * change it; panned or on the preview the range is open (3–17). True while locked.
     */
    private fun applyZoomLock(m: GoogleMap): Boolean {
        val lock = following && !previewShowing
        m.setMinZoomPreference(if (lock) followZoom else MIN_FOLLOW_ZOOM)
        m.setMaxZoomPreference(if (lock) followZoom else MAX_ZOOM)
        return lock
    }

    /** The settle re-follow (one runnable, so a second settle replaces the first's timers). */
    private val settleFollow = Runnable { _map.value?.let { mm -> if (following) follow(mm) } }

    /** What [applyView] last set, so it logs only on a change. */
    private var lastFrame: String? = null
    /** What [layoutOverlays] last laid out. */
    private var lastOverlayKey: String? = null

    /** The map's camera padding (DESIGN.md §8 "Where the car sits"), as "l,t,r,b"; only [applyView] calls it. */
    private fun applyPadding(m: GoogleMap): String? {
        val density = presentation?.context?.resources?.displayMetrics?.density ?: return null
        // Fixed top and bottom, so the car sits in the same spot in free drive and guidance and never moves
        // with the host's cards: centred across the map, just above the alert banner (owner, DESIGN.md §8
        // "Where the car sits").
        val (w, h) = surfaceSize ?: return null
        // Centred across the map the host shows (its visible area narrows when it splits the screen for a
        // media panel); the top and bottom are fixed because the visible top moves between free drive and guidance.
        // Across the map panel, not the visible area: the host's cards float over the map and never push
        // the car. The panel can sit anywhere, e.g. starting past the middle when our map is the host's
        // right-hand panel (x 1216–1804 of 1816 on the DHU); only keep a map at least MIN_MAP_PX wide.
        val visible = panel ?: visibleArea ?: Rect(0, 0, w, h)
        var left = visible.left.coerceIn(0, w - MIN_MAP_PX)
        var right = (w - visible.right).coerceIn(0, w - MIN_MAP_PX)
        if (w - left - right < MIN_MAP_PX) { right = (w - MIN_MAP_PX - left).coerceAtLeast(0); left = (w - MIN_MAP_PX - right).coerceAtLeast(0) }
        // Top: the action strip's clearance (88 dp). Bottom: the banner (96 dp) plus its 16 px margin and
        // an 8 dp gap, so a camera alert never covers the car (§8 "Where the car sits").
        val top = (ACTION_STRIP_CLEAR_DP * density).toInt()
        val bottom = (AlertBannerView.HEIGHT_DP * density).toInt() + 16 + (CAR_GAP_DP * density).toInt()
        val padTop = if (guidingProvider()) top else {
            // Free drive centres the car in the padded area; raise the top so that centre is where guidance puts it.
            // Guidance draws the car GUIDED_CAR_FRACTION down the padded height (measured on the DHU, §8).
            val carY = top + GUIDED_CAR_FRACTION * (h - bottom - top)
            (2 * carY - (h - bottom)).toInt().coerceIn(top, h - bottom - 1)
        }
        m.setPadding(left, padTop, right, bottom)
        lastPadding = intArrayOf(left, padTop, right, bottom)
        return "$left,$padTop,$right,$bottom"
    }

    /**
     * Places our overlays in the visible area. It doesn't wait for the map: until the host reports
     * a visible area the whole surface is used, so the banner never starts full width over the speed
     * panel after a fresh start.
     *
     * Hard rule 4 lives here: the speed panel bottom right (§8.4), the satellite pill top right and
     * the zoom/recenter column under it (§8), all by their right and top margins; the banner bottom
     * centre (§12.1). Nothing of ours is placed on the left.
     */
    private fun layoutOverlays() {
        val (w, h) = surfaceSize ?: return
        // Only when the areas, the surface or the views changed: zoom, recenter and the watchdog come
        // through applyView too and must not re-lay out the overlays for nothing.
        val key = "$w×$h $visibleArea $stableArea ${System.identityHashCode(speedPanel)}"
        if (key == lastOverlayKey) return
        lastOverlayKey = key
        val o = overlays ?: return
        val density = o.panel.resources.displayMetrics.density
        // Everything is placed in the visible area (the stable area, then the whole surface, until the host reports one),
        // 16 px inside its right and bottom edges.
        val v = visibleArea ?: stableArea ?: Rect(0, 0, w, h)
        val right = (w - v.right).coerceAtLeast(0) + 16
        val bottom = (h - v.bottom).coerceAtLeast(0) + 16
        // Bottom right of the visible area (DESIGN.md §8.4).
        o.panel.updateLayoutParams<FrameLayout.LayoutParams> {
            rightMargin = right
            bottomMargin = bottom
        }
        // The banner: bottom centre of the visible area, kept clear of the speed panel on the left. Up to 640 dp
        // wide, the same clearance each side so it stays centred, never under half the visible width (§12.1).
        o.banner.updateLayoutParams<FrameLayout.LayoutParams> {
            val clear = (SPEED_PANEL_CLEAR_DP * density).toInt()
            width = minOf(v.width() - clear * 2, (AlertBannerView.MAX_WIDTH_DP * density).toInt()).coerceAtLeast(v.width() / 2)
            leftMargin = v.left + (v.width() - width) / 2
            bottomMargin = bottom
        }
        // The satellite toggle: top-right corner of the visible area. The host shrinks that area while its
        // action strip is showing, so the toggle moves down out of its way and back up. Always below the
        // action strip: it's persistent now, but the host sometimes reports a visible area that ignores it
        // (right after the map starts), which put the toggle under it.
        var top = maxOf(v.top, stableArea?.top ?: 0, (ACTION_STRIP_CLEAR_DP * density).toInt()) + 16
        o.toggle.updateLayoutParams<FrameLayout.LayoutParams> {
            rightMargin = right
            topMargin = top
        }
        // The zoom pill, then recenter: down the right edge under the toggle.
        val gap = (MapButtonView.GAP_DP * density).toInt()
        top += (MapTypeButtonView.HEIGHT_DP * density).toInt() + gap
        for (b in o.buttons) {
            b.updateLayoutParams<FrameLayout.LayoutParams> {
                rightMargin = right
                topMargin = top
            }
            top += (b.heightDp * density).toInt() + gap
        }
    }

    /**
     * Releases the map and everything on the surface (§8 "Tear-down"): the view's `onPause/onStop/
     * onDestroy`, the Presentation, the VirtualDisplay. The overlays go with the Presentation, so
     * their references are dropped; the areas, the panel and the follow zoom are kept for the next
     * [attach] on the same surface. Safe to call more than once.
     */
    fun release() {
        _map.value = null
        lastOverlayKey = null
        lastFrame = null
        overlays = null
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
        /** The open end of the zoom range while panned or on the preview (§8: "3–17"). */
        private const val MIN_FOLLOW_ZOOM = 3f
        /** Room left either side of the banner for the speed panel (its pill and a margin). */
        private const val SPEED_PANEL_CLEAR_DP = 140
        /** Height kept clear at the top for the host's action strip (its buttons end at 88 px on a 160 dpi DHU). */
        private const val ACTION_STRIP_CLEAR_DP = 88
        /** Closest zoom at which the road map still draws Google traffic (found on the bench: 17.5 has none in towns, 18 none anywhere). */
        const val MAX_ZOOM = 17f
        /** Gap between the alert banner's top and the followed car (owner, 2026-10-23). */
        private const val CAR_GAP_DP = 8
        /** The padded map is never narrower than this, whatever the host reports (§8). */
        private const val MIN_MAP_PX = 200
        /** A right edge moved by more than this while a card is up means the host re-laid out (§8). */
        private const val PANEL_MOVE_PX = 32
        /** The camera's zoom may differ from the locked one by this much before the watchdog re-follows (§8). */
        private const val ZOOM_TOLERANCE = 0.25f
        /** Where the SDK's guidance camera puts the car: this far down the padded area (measured on the DHU, 2026-10-23). */
        private const val GUIDED_CAR_FRACTION = 0.917f
        /** Follows again after guidance starts or stops: the SDK moves its camera a moment later (§8). */
        private val REFOLLOW_MS = longArrayOf(300, 700, 1_200, 2_000)
        /** Events after which the SDK's camera was moved or dropped, so the follow is sent whatever the frame (§8). */
        private val FOLLOW_REASONS = setOf("ready", "mapType", "navUi on", "recenter", "watchdog")
    }
}
