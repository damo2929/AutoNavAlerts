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
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import io.autonav.alerts.R
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.FollowMyLocationOptions
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
    private var setup: Job? = null
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
    private var lastRefollow = 0L
    private var refollowsInARow = 0

    /** The satellite toggle at the top right of the map (DESIGN.md §8), or null before the map exists. */
    private var mapTypeButton: MapTypeButtonView? = null

    /** Zoom in, zoom out and recenter, in a column under the satellite toggle (DESIGN.md §8). */
    private var mapButtons: List<MapButtonView> = emptyList()

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
        setup = scope.launch { if (applyKey()) attach(container) }
    }

    @SuppressLint("MissingPermission")
    private fun attach(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        this.container = container
        refollowsInARow = 0
        surfaceWidth = container.width
        surfaceHeight = container.height
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
        val toggle = MapTypeButtonView(pres.context).apply { satellite = this@MapSurface.satellite }
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
            MapButtonView(pres.context, R.drawable.ic_action_zoom_in) { zoom(1f) },
            MapButtonView(pres.context, R.drawable.ic_action_zoom_out) { zoom(-1f) },
            MapButtonView(pres.context, R.drawable.ic_action_recenter) { recenter() },
        ).onEachIndexed { i, b ->
            root.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = 16
                topMargin = clearTop + ((MapTypeButtonView.HEIGHT_DP + MapButtonView.GAP_DP + i * (MapButtonView.SIZE_DP + MapButtonView.GAP_DP)) * density).toInt()
            })
        }
        pres.show()
        presentation = pres
        navView = view
        IncidentReporting.enableButton(view)
        view.getMapAsync { m ->
            runCatching { m.isMyLocationEnabled = true }
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isMyLocationButtonEnabled = false
            m.mapType = if (satellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
            m.isTrafficEnabled = true
            // The road map stops drawing traffic above zoom 17 in towns (satellite keeps it), and the follow
            // camera sits at 18. Capping the zoom keeps traffic on screen on both (DESIGN.md §8).
            m.setMaxZoomPreference(MAX_ZOOM)
            Log.i(TAG, "Map ready: type ${m.mapType}, traffic ${m.isTrafficEnabled}, max zoom $MAX_ZOOM")
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
        Log.i(TAG, "Visible area $area (stable $stableArea, surface ${surfaceWidth}x$surfaceHeight)")
        visibleArea = area
        applyPadding()
    }

    override fun onStableAreaChanged(area: Rect) {
        stableArea = area
        applyPadding()
    }

    /** A tap on the map: the satellite toggle switches the map type; the alert banner dismisses the alert. */
    override fun onClick(x: Float, y: Float) {
        Log.i(TAG, "Tap at ${x.toInt()},${y.toInt()}")
        mapTypeButton?.let { t ->
            if (t.isShown && hit(t, x, y)) {
                onSatelliteToggle?.invoke(!satellite)
                return
            }
        }
        mapButtons.firstOrNull { it.isShown && hit(it, x, y) }?.let { it.onTap(); return }
        val b = alertBanner ?: return
        if (b.showing == null) return
        if (x >= b.left && x <= b.right && y >= b.top && y <= b.bottom) b.hide()
    }

    private fun hit(v: android.view.View, x: Float, y: Float) = x >= v.left && x <= v.right && y >= v.top && y <= v.bottom

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val m = _map.value ?: return
        following = false
        m.moveCamera(CameraUpdateFactory.scrollBy(distanceX, distanceY))
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val m = _map.value ?: return
        following = false
        m.animateCamera(CameraUpdateFactory.scrollBy(-velocityX / 10f, -velocityY / 10f))
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
     * Called with every fix. After a big jump in position (a tunnel exit, or the bench's GPS
     * playback starting somewhere new) the SDK's map can stop following the car. If the map is
     * meant to follow but its centre is far from the car, follow again; if that doesn't bring it
     * back, rebuild the map on the same surface.
     */
    fun keepFollowing(lat: Double, lon: Double) {
        val m = _map.value ?: return
        // Google traffic is always on (DESIGN.md §8): put it back if anything (guidance, a map type
        // change, the SDK) turned it off.
        if (!m.isTrafficEnabled) m.isTrafficEnabled = true
        if (!following) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefollow < REFOLLOW_INTERVAL_MS) return
        val target = m.cameraPosition.target
        val d = FloatArray(1)
        Location.distanceBetween(target.latitude, target.longitude, lat, lon, d)
        if (d[0] < LOST_METRES) { refollowsInARow = 0; return }
        lastRefollow = now
        if (++refollowsInARow > 1) {
            Log.i(TAG, "Map still ${d[0].toInt()} m from the car: rebuilding it")
            container?.let(::attach)
        } else {
            Log.i(TAG, "Map is ${d[0].toInt()} m from the car: following again")
            follow(m)
        }
    }

    /** Google incident reporting on the car screen, if a future Navigation SDK supports it (§16 #21). */
    val incidentReportingSupported: Boolean get() = navView?.let(IncidentReporting::supportedBySdk) == true
    val incidentReportingAvailableNow: Boolean get() = navView?.let(IncidentReporting::availableNow) == true
    fun showIncidentPanel(): Boolean = navView?.let(IncidentReporting::showPanel) == true

    /**
     * Keeps the car marker and route clear of the turn card, rail and split-screen media panel:
     * the stable area sets the padding so the camera target stays steady (DESIGN.md §8).
     */
    private fun applyPadding() {
        val m = _map.value ?: return
        val area = stableArea ?: visibleArea ?: return
        val w = surfaceWidth.takeIf { it > 0 } ?: return
        val h = surfaceHeight.takeIf { it > 0 } ?: return
        m.setPadding(area.left, area.top, (w - area.right).coerceAtLeast(0), (h - area.bottom).coerceAtLeast(0))
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
                lp.rightMargin = (surfaceWidth - v.right).coerceAtLeast(0) + 16
                // Always below the action strip: it's persistent now, but the host sometimes reports a
                // visible area that ignores it (right after the map starts), which put the toggle under it.
                lp.topMargin = maxOf(v.top, stableArea?.top ?: 0, (ACTION_STRIP_CLEAR_DP * density).toInt()) + 16
                t.layoutParams = lp
                // Zoom in, zoom out, recenter: down the right edge under the toggle.
                val size = (MapButtonView.SIZE_DP * density).toInt()
                val gap = (MapButtonView.GAP_DP * density).toInt()
                var top = lp.topMargin + (MapTypeButtonView.HEIGHT_DP * density).toInt() + gap
                for (b in mapButtons) {
                    (b.layoutParams as? FrameLayout.LayoutParams)?.let { blp ->
                        blp.rightMargin = (surfaceWidth - v.right).coerceAtLeast(0) + 16
                        blp.topMargin = top
                        b.layoutParams = blp
                    }
                    top += size + gap
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

    companion object {
        private const val TAG = "MapSurface"
        private const val LOST_METRES = 500f
        private const val MIN_FOLLOW_ZOOM = 3f
        private const val REFOLLOW_INTERVAL_MS = 5_000L
        /** Room left either side of the banner for the speed panel (two 64 dp boxes and gaps). */
        private const val SPEED_PANEL_CLEAR_DP = 170
        /** Height kept clear at the top for the host's action strip (its buttons end at 88 px on a 160 dpi DHU). */
        private const val ACTION_STRIP_CLEAR_DP = 88
        /** Closest zoom at which the road map still draws Google traffic (found on the bench: 17.5 has none in towns, 18 none anywhere). */
        const val MAX_ZOOM = 17f
    }
}
