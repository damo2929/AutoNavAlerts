// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.GoogleMap
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
 * Bursts of fixes are conflated: only the newest state is drawn.
 */
class PoiMarkerLayer(
    private val icons: IconRepository,
    private val basePx: Int,
) {
    private data class Shown(val marker: Marker, val emphasis: Emphasis, val iconKey: String)

    private val main = Handler(Looper.getMainLooper())
    private val shown = HashMap<Long, Shown>()
    private val pending = AtomicBoolean(false)
    @Volatile private var latest: Triple<List<ResolvedPoi>, Fix?, ActiveAlert?>? = null
    var map: GoogleMap? = null
        set(value) {
            if (field !== value) shown.clear()
            field = value
        }

    /** Called on every fix (any thread). */
    fun redraw(pois: List<ResolvedPoi>, fix: Fix?, active: ActiveAlert?) {
        latest = Triple(pois, fix, active)
        if (pending.compareAndSet(false, true)) main.post { pending.set(false); drawLatest() }
    }

    private fun drawLatest() {
        val m = map ?: return
        val (pois, fix, active) = latest ?: return
        val zoom = m.cameraPosition.zoom
        val bounds = runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        val visible = pois.asSequence()
            .filter { it.set.showOnMap && it.set.enabled && zoom >= it.set.minZoom }
            .filter { bounds.contains(LatLng(it.poi.lat, it.poi.lon)) }
            .sortedBy { if (fix == null) 0.0 else Geo.distanceM(fix.lat, fix.lon, it.poi.lat, it.poi.lon) }
            .take(MAX_MARKERS)
            .toList()
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
                        .icon(BitmapDescriptorFactory.fromBitmap(icons.marker(r.iconId, r.poi.speedLimit, emphasis, basePx)))
                        .anchor(0.5f, 0.5f)
                        .zIndex(if (emphasis == Emphasis.ACTIVE) 10f else 1f)
                        .title(r.categoryName),
                ) ?: continue
                shown[r.poi.id] = Shown(marker, emphasis, iconKey)
            } else if (existing.emphasis != emphasis || existing.iconKey != iconKey) {
                existing.marker.setIcon(BitmapDescriptorFactory.fromBitmap(icons.marker(r.iconId, r.poi.speedLimit, emphasis, basePx)))
                existing.marker.zIndex = if (emphasis == Emphasis.ACTIVE) 10f else 1f
                shown[r.poi.id] = existing.copy(emphasis = emphasis, iconKey = iconKey)
            }
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
