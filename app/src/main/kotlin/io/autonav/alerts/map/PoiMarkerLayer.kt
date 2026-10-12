// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.autonav.alerts.alerts.ActiveAlert
import io.autonav.alerts.alerts.ResolvedPoi
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.IconRepository.Emphasis
import java.util.concurrent.atomic.AtomicBoolean

/**
 * POI markers, redrawn on every GPS fix as a diff (DESIGN.md §8.1): pick visible POIs from the
 * in-memory cache, rank by distance, cap at 200, then add/remove/change only what differs.
 * Bursts of fixes are conflated: only the newest state is drawn. Cameras on our road that point
 * the other way are left off: they're never alerted (the direction rule), so they'd only mislead.
 */
class PoiMarkerLayer(
    private val icons: IconRepository,
    private val basePx: Int,
) {
    private data class Shown(val marker: Marker, val emphasis: Emphasis, val iconKey: String)

    private val main = Handler(Looper.getMainLooper())
    private val shown = HashMap<Long, Shown>()
    private val pending = AtomicBoolean(false)
    private class State(val pois: List<ResolvedPoi>, val fix: Fix?, val active: ActiveAlert?, val facingAway: Set<Long>)
    @Volatile private var latest: State? = null
    /** Marker images handed to the map once and reused, keyed by icon, limit and emphasis. */
    private val descriptors = HashMap<String, BitmapDescriptor>()
    var map: GoogleMap? = null
        set(value) {
            if (field !== value) { shown.clear(); descriptors.clear() }
            field = value
        }

    /** Called on every fix (any thread). */
    fun redraw(pois: List<ResolvedPoi>, fix: Fix?, active: ActiveAlert?, facingAway: Set<Long> = emptySet()) {
        latest = State(pois, fix, active, facingAway)
        if (pending.compareAndSet(false, true)) main.post { pending.set(false); drawLatest() }
    }

    private fun drawLatest() {
        val m = map ?: return
        val state = latest ?: return
        val pois = state.pois; val fix = state.fix; val active = state.active; val away = state.facingAway
        val zoom = m.cameraPosition.zoom
        val bounds = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        val south = bounds.southwest.latitude; val north = bounds.northeast.latitude
        val west = bounds.southwest.longitude; val east = bounds.northeast.longitude
        val onScreen = ArrayList<ResolvedPoi>()
        for (r in pois) {
            if (!r.set.showOnMap || !r.set.enabled || zoom < r.set.minZoom) continue
            if (r.poi.id in away) continue
            val lat = r.poi.lat; val lon = r.poi.lon
            if (lat < south || lat > north) continue
            if (if (west <= east) lon < west || lon > east else lon < west && lon > east) continue
            onScreen += r
        }
        // Usually everything on screen fits; only past the cap, keep the nearest (each distance once).
        val visible = if (onScreen.size <= MAX_MARKERS || fix == null) onScreen.take(MAX_MARKERS) else {
            val d = DoubleArray(onScreen.size) { Geo.distanceM(fix.lat, fix.lon, onScreen[it].poi.lat, onScreen[it].poi.lon) }
            onScreen.indices.sortedBy { d[it] }.take(MAX_MARKERS).map { onScreen[it] }
        }
        val wanted = visible.associateBy { it.poi.id }

        // Remove markers no longer wanted.
        val it = shown.entries.iterator()
        while (it.hasNext()) {
            val (id, s) = it.next()
            if (id !in wanted) { s.marker.remove(); it.remove() }
        }
        // Add or update.
        for (r in visible) {
            val emphasis = when {
                active?.poiId == r.poi.id && System.currentTimeMillis() - active.timeMs < 15_000 -> Emphasis.ACTIVE
                fix != null && isAhead(fix, r) -> Emphasis.AHEAD
                else -> Emphasis.OTHER
            }
            val iconKey = "${r.iconId}:${r.poi.speedLimit}"
            val existing = shown[r.poi.id]
            if (existing == null) {
                val marker = m.addMarker(
                    MarkerOptions().position(LatLng(r.poi.lat, r.poi.lon))
                        .icon(descriptor(r, iconKey, emphasis))
                        .anchor(0.5f, 0.5f)
                        .zIndex(if (emphasis == Emphasis.ACTIVE) 10f else 1f)
                        .title(r.categoryName),
                ) ?: continue
                shown[r.poi.id] = Shown(marker, emphasis, iconKey)
            } else if (existing.emphasis != emphasis || existing.iconKey != iconKey) {
                existing.marker.setIcon(descriptor(r, iconKey, emphasis))
                existing.marker.zIndex = if (emphasis == Emphasis.ACTIVE) 10f else 1f
                shown[r.poi.id] = existing.copy(emphasis = emphasis, iconKey = iconKey)
            }
        }
    }

    private fun descriptor(r: ResolvedPoi, iconKey: String, emphasis: Emphasis): BitmapDescriptor {
        if (descriptors.size > 256) descriptors.clear()
        return descriptors.getOrPut("$iconKey:$emphasis") {
            BitmapDescriptorFactory.fromBitmap(icons.marker(r.iconId, r.poi.speedLimit, emphasis, basePx))
        }
    }

    private fun isAhead(fix: Fix, r: ResolvedPoi): Boolean {
        val h = fix.heading ?: return false
        if (Geo.distanceM(fix.lat, fix.lon, r.poi.lat, r.poi.lon) > 3_000) return false
        if (Geo.absAngleDiffDeg(Geo.bearingDeg(fix.lat, fix.lon, r.poi.lat, r.poi.lon), h) > 30) return false
        val b = r.poi.bearing?.toDouble() ?: return true
        return when (r.poi.directionType) {
            DirectionType.ANY -> true
            DirectionType.ONE_WAY -> Geo.absAngleDiffDeg(b, h) <= 45
            DirectionType.BOTH -> Geo.absAngleDiffDeg(b, h) <= 45 || Geo.absAngleDiffDeg(b + 180, h) <= 45
        }
    }

    fun clear() {
        main.post { shown.values.forEach { it.marker.remove() }; shown.clear() }
    }

    companion object { const val MAX_MARKERS = 200 }
}
