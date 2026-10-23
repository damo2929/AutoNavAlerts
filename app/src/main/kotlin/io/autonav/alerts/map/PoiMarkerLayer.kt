// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.map

import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.autonav.alerts.alerts.ActiveAlert
import io.autonav.alerts.alerts.ResolvedPoi
import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.alerts.Verdict
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.IconRepository.Emphasis
import java.util.concurrent.atomic.AtomicBoolean

/**
 * POI markers, redrawn on every GPS fix as a diff (DESIGN.md §8.1): pick visible POIs from the
 * in-memory cache (the visible bounds plus a 10 % margin), rank by distance, cap at 200 markers,
 * then add/remove/change only what differs. Bursts of fixes are conflated: only the newest state
 * is drawn. The emphasis comes from the engine's verdict (one source of truth): cameras on our road
 * that point the other way are drawn dimmed with their arrow, never hidden (owner, 2026-10-12).
 * Every camera with a bearing gets a second, flat marker: a chevron rotated to its bearing.
 */
class PoiMarkerLayer(
    private val icons: IconRepository,
    private val basePx: Int,
    /** The whole car surface in pixels (`MapSurface.surfaceSize`); null falls back to the visible region. */
    private val screenSize: () -> android.util.Size? = { null },
) {
    private class Shown(
        val marker: Marker, var emphasis: Emphasis, var iconKey: String,
        var arrow: Marker?, var arrowRotation: Float, var arrowDir: DirectionType,
    )

    private val main = Handler(Looper.getMainLooper())
    private val shown = HashMap<Long, Shown>()
    private val pending = AtomicBoolean(false)
    private class State(
        val pois: List<ResolvedPoi>, val fix: Fix?, val active: ActiveAlert?, val facingAway: Set<Long>,
        val verdicts: Map<Long, Verdict>,
    )
    @Volatile private var latest: State? = null
    /** Marker images handed to the map once and reused, keyed by icon, limit and emphasis. */
    private val descriptors = HashMap<String, BitmapDescriptor>()
    var map: GoogleMap? = null
        set(value) {
            if (field !== value) { shown.clear(); descriptors.clear() }
            field = value
        }

    /**
     * Called on every fix (any thread). [verdicts] (from `AlertCoordinator.verdicts`) drives the
     * emphasis; when it's empty (not wired yet) [facingAway] alone marks the dimmed cameras.
     */
    fun redraw(
        pois: List<ResolvedPoi>, fix: Fix?, active: ActiveAlert?, facingAway: Set<Long> = emptySet(),
        verdicts: Map<Long, Verdict> = emptyMap(),
    ) {
        latest = State(pois, fix, active, facingAway, verdicts)
        if (pending.compareAndSet(false, true)) main.post { pending.set(false); drawLatest() }
    }

    private fun drawLatest() {
        val m = map ?: return
        val state = latest ?: return
        val pois = state.pois; val fix = state.fix; val active = state.active
        val zoom = m.cameraPosition.zoom
        val bounds = screenBounds(m) ?: return
        var south = bounds.southwest.latitude; var north = bounds.northeast.latitude
        var west = bounds.southwest.longitude; var east = bounds.northeast.longitude
        // A 10 % margin, so markers at the edge don't drop out as the map moves (§8.1).
        val latMargin = (north - south) * BOUNDS_MARGIN
        south -= latMargin; north += latMargin
        if (west <= east) {
            val lonMargin = (east - west) * BOUNDS_MARGIN
            west -= lonMargin; east += lonMargin
        }
        val onScreen = ArrayList<ResolvedPoi>()
        for (r in pois) {
            if (!r.set.showOnMap || !r.set.enabled || zoom < r.set.minZoom) continue
            val lat = r.poi.lat; val lon = r.poi.lon
            if (lat < south || lat > north) continue
            if (if (west <= east) lon < west || lon > east else lon < west && lon > east) continue
            onScreen += r
        }
        // Nearest first once the cap could bite (arrows count as markers too), each distance once.
        val ranked = if (onScreen.size <= MAX_MARKERS / 2 || fix == null) onScreen else {
            val d = DoubleArray(onScreen.size) { Geo.distanceM(fix.lat, fix.lon, onScreen[it].poi.lat, onScreen[it].poi.lon) }
            onScreen.indices.sortedBy { d[it] }.map { onScreen[it] }
        }
        val visible = ArrayList<ResolvedPoi>(ranked.size)
        var markers = 0
        for (r in ranked) {
            val cost = if (hasArrow(r)) 2 else 1
            if (markers + cost > MAX_MARKERS) break
            markers += cost
            visible += r
        }
        val wanted = visible.associateBy { it.poi.id }

        // Remove markers no longer wanted.
        val it = shown.entries.iterator()
        while (it.hasNext()) {
            val (id, s) = it.next()
            if (id !in wanted) { s.marker.remove(); s.arrow?.remove(); it.remove() }
        }
        // Add or update.
        for (r in visible) {
            val emphasis = emphasisOf(r, state)
            val iconKey = "${r.iconId}:${r.poi.speedLimit}"
            val z = if (emphasis == Emphasis.ACTIVE) 10f else 1f
            val existing = shown[r.poi.id]
            val arrowWanted = hasArrow(r)
            val bearing = r.poi.bearing ?: 0f
            if (existing == null) {
                val marker = m.addMarker(
                    MarkerOptions().position(LatLng(r.poi.lat, r.poi.lon))
                        .icon(descriptor(r, iconKey, emphasis))
                        .anchor(0.5f, 0.5f)
                        .zIndex(z)
                        .title(r.categoryName),
                ) ?: continue
                val arrow = if (arrowWanted) addArrow(m, r, emphasis, bearing, z) else null
                shown[r.poi.id] = Shown(marker, emphasis, iconKey, arrow, bearing, r.poi.directionType)
                continue
            }
            if (existing.emphasis != emphasis || existing.iconKey != iconKey) {
                existing.marker.setIcon(descriptor(r, iconKey, emphasis))
                existing.marker.zIndex = z
                existing.arrow?.let { a ->
                    if (existing.emphasis != emphasis || existing.arrowDir != r.poi.directionType) a.setIcon(arrowDescriptor(r.poi.directionType, emphasis))
                    a.zIndex = z - 0.5f
                }
                existing.emphasis = emphasis
                existing.iconKey = iconKey
                existing.arrowDir = r.poi.directionType
            }
            if (arrowWanted && existing.arrow == null) {
                existing.arrow = addArrow(m, r, emphasis, bearing, z)
                existing.arrowRotation = bearing
            } else if (!arrowWanted && existing.arrow != null) {
                existing.arrow?.remove()
                existing.arrow = null
            } else if (existing.arrow != null && existing.arrowRotation != bearing) {
                existing.arrow?.rotation = bearing
                existing.arrowRotation = bearing
            }
        }
    }

    /**
     * The whole screen's bounds (DESIGN.md §8.1): its four corners projected onto the map. Not
     * `visibleRegion`, which is only the padded area: in free drive the top padding that puts the car
     * low covered most of the screen, so a camera was drawn only once it reached the lower part.
     */
    private fun screenBounds(m: GoogleMap): LatLngBounds? {
        val size = screenSize() ?: return runCatching { m.projection.visibleRegion.latLngBounds }.getOrNull()
        return runCatching {
            val p = m.projection
            val b = LatLngBounds.builder()
            for ((x, y) in listOf(0 to 0, size.width to 0, 0 to size.height, size.width to size.height)) {
                b.include(p.fromScreenLocation(android.graphics.Point(x, y)))
            }
            b.build()
        }.getOrNull()
    }

    /**
     * ACTIVE for the camera being alerted on; otherwise the engine's verdict (AHEAD_OK → AHEAD,
     * WRONG_DIRECTION → FACING_AWAY, the rest OTHER). The engine is the one source of truth for
     * "ahead" (DESIGN.md §8.1); the layer has no direction test of its own. `facingAway` is the
     * engine's own set, kept as a belt-and-braces for a POI the verdict map hasn't seen.
     */
    private fun emphasisOf(r: ResolvedPoi, state: State): Emphasis {
        val active = state.active
        if (active?.poiId == r.poi.id && System.currentTimeMillis() - active.timeMs < 15_000) return Emphasis.ACTIVE
        return when (state.verdicts[r.poi.id]) {
            Verdict.AHEAD_OK -> Emphasis.AHEAD
            Verdict.WRONG_DIRECTION -> Emphasis.FACING_AWAY
            else -> if (r.poi.id in state.facingAway) Emphasis.FACING_AWAY else Emphasis.OTHER
        }
    }

    private fun hasArrow(r: ResolvedPoi): Boolean = r.poi.bearing != null && r.poi.directionType != DirectionType.ANY

    private fun addArrow(m: GoogleMap, r: ResolvedPoi, emphasis: Emphasis, bearing: Float, z: Float): Marker? =
        m.addMarker(
            MarkerOptions().position(LatLng(r.poi.lat, r.poi.lon))
                .icon(arrowDescriptor(r.poi.directionType, emphasis))
                .flat(true)
                .rotation(bearing)
                .anchor(0.5f, 0.5f)
                .zIndex(z - 0.5f),
        )

    private fun arrowDescriptor(dir: DirectionType, emphasis: Emphasis): BitmapDescriptor {
        val iconPx = if (emphasis == Emphasis.ACTIVE) (basePx * 1.3f).toInt() else basePx
        return icons.arrow(dir, emphasis, (iconPx * ARROW_SCALE).toInt())
    }

    private fun descriptor(r: ResolvedPoi, iconKey: String, emphasis: Emphasis): BitmapDescriptor {
        if (descriptors.size > 256) descriptors.clear()
        return descriptors.getOrPut("$iconKey:$emphasis") {
            BitmapDescriptorFactory.fromBitmap(icons.marker(r.iconId, r.poi.speedLimit, emphasis, basePx))
        }
    }

    fun clear() {
        main.post { shown.values.forEach { it.marker.remove(); it.arrow?.remove() }; shown.clear() }
    }

    companion object {
        const val MAX_MARKERS = 200
        /** The arrow square is this many times the icon, so the head sits outside the icon (§8.1). */
        const val ARROW_SCALE = 2.2f
        private const val BOUNDS_MARGIN = 0.10
    }
}
