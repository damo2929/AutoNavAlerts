// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.annotation.SuppressLint
import android.app.Presentation
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.navigation.NavigationViewForAuto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ln

/**
 * Hosts the Navigation SDK map on the car's surface (DESIGN.md §8): SurfaceContainer →
 * VirtualDisplay → Presentation → NavigationViewForAuto. Also owns the speed panel overlay.
 */
class MapSurface(private val carContext: CarContext) : SurfaceCallback {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var navView: NavigationViewForAuto? = null

    private val _map = MutableStateFlow<GoogleMap?>(null)
    val map: StateFlow<GoogleMap?> = _map

    var speedPanel: SpeedPanelView? = null
        private set

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var following = true

    /** Called when markers/camera listeners should attach. */
    var onMapReady: ((GoogleMap) -> Unit)? = null

    @SuppressLint("MissingPermission")
    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        surfaceWidth = container.width
        surfaceHeight = container.height
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "autonav-map", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        display = vd
        val pres = Presentation(carContext, vd.display)
        val view = NavigationViewForAuto(pres.context)
        pres.setContentView(view)
        view.onCreate(null)
        view.onStart()
        view.onResume()
        val panel = SpeedPanelView(pres.context)
        view.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        })
        speedPanel = panel
        pres.show()
        presentation = pres
        navView = view
        IncidentReporting.enableButton(view)
        view.getMapAsync { m ->
            runCatching { m.isMyLocationEnabled = true }
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isMyLocationButtonEnabled = false
            m.isTrafficEnabled = true
            runCatching { m.followMyLocation(GoogleMap.CameraPerspective.TILTED) }
            following = true
            _map.value = m
            applyPadding()
            onMapReady?.invoke(m)
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onVisibleAreaChanged(area: Rect) {
        visibleArea = area
        applyPadding()
    }

    override fun onStableAreaChanged(area: Rect) {
        stableArea = area
        applyPadding()
    }

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
        val focus = if (focusX < 0 || focusY < 0) null else Point(focusX.toInt(), focusY.toInt())
        m.moveCamera(if (focus != null) CameraUpdateFactory.zoomBy(amount, focus) else CameraUpdateFactory.zoomBy(amount))
    }

    fun zoom(delta: Float) {
        _map.value?.animateCamera(CameraUpdateFactory.zoomBy(delta))
    }

    @SuppressLint("MissingPermission")
    fun recenter() {
        val m = _map.value ?: return
        following = true
        // Location permission is granted on the phone during setup; without it this is a no-op.
        runCatching { m.followMyLocation(GoogleMap.CameraPerspective.TILTED) }
    }

    val isFollowing: Boolean get() = following

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
                val v = visibleArea ?: area
                lp.leftMargin = v.left + 16
                lp.bottomMargin = (h - v.bottom).coerceAtLeast(0) + 16
                p.layoutParams = lp
            }
        }
    }

    fun release() {
        _map.value = null
        speedPanel = null
        navView?.let { v ->
            runCatching { v.onPause(); v.onStop(); v.onDestroy() }.onFailure { Log.w(TAG, "navView teardown", it) }
        }
        navView = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
    }

    companion object { private const val TAG = "MapSurface" }
}
