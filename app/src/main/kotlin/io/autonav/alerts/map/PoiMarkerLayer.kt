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
import com.google.android.gms.maps.model.MarkerOptions
import io.autonav.alerts.alerts.MarkerState
import io.autonav.alerts.alerts.ResolvedPoi
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
 *
 * Lifecycle: created by `NavSession.onCreateScreen`, given the map in `MapSurface.onMapReady`
 * (a new map drops everything drawn on the old one), fed from the session's alert collector on
 * every fix and from the camera-idle listener, and cleared in the session's `onDestroy`.
 * [redraw] may be called from any thread; all `GoogleMap` calls happen in one main-thread post
 * per fix (§8.1 step 5). No Room access and no bitmap allocation per redraw: the POIs come from
 * the in-memory cache and the images from `IconRepository`'s cache (§8.1 "Cost").
 *
 * The direction hard rule (rule 1) shows here as the dimmed, never hidden, facing-away camera
 * with its arrow; the verdict itself is the engine's. Map calls are made only for what differs
 * (rule 3): a redraw with nothing changed touches no marker.
 *
 * @param basePx the marker icon's side in pixels (36 dp on the car display); ACTIVE is 1.3× it.
 * @param toDescriptor and [arrowOf] hand marker and arrow images to the map; the defaults are the
 *   production ones, and the JVM tests pass fakes (§15.1 "PoiMarkerLayer diff").
 */
class PoiMarkerLayer(
    private val icons: IconRepository,
    private val basePx: Int,
    private val toDescriptor: (android.graphics.Bitmap) -> BitmapDescriptor = BitmapDescriptorFactory::fromBitmap,
    private val arrowOf: (DirectionType, Emphasis, Int) -> BitmapDescriptor = icons::arrow,
    /** The whole car surface in pixels (`MapSurface.surfaceSize`); null falls back to the visible region. */
    private val screenSize: () -> android.util.Size? = { null },
) {
    /**
     * The map calls the layer makes (DESIGN.md §15.1 "PoiMarkerLayer diff"): `GoogleMap` and `Marker`
     * are SDK classes a JVM test can't build, so the tests draw on a fake through this; production wraps [map].
     */
    internal interface MarkerMap {
        val zoom: Float
        fun screenBounds(): LatLngBounds?
        fun addMarker(options: MarkerOptions): Drawn?
    }

    /** One drawn marker: the four `Marker` calls the diff makes. */
    internal interface Drawn {
        fun remove()
        fun setIcon(icon: BitmapDescriptor)
        fun setZIndex(z: Float)
        fun setRotation(degrees: Float)
    }

    /** What is drawn for one POI and the state it was drawn with, so a redraw changes only what differs (§8.1 step 4). */
    private class Shown(
        val marker: Drawn, var emphasis: Emphasis, var iconKey: String,
        var arrow: Drawn?, var arrowRotation: Float, var arrowDir: DirectionType,
    )

    private val main = Handler(Looper.getMainLooper())
    private val shown = HashMap<Long, Shown>()
    /** A main-thread draw is queued; a second fix before it runs only replaces [latest] (conflation, §8.1 step 5). */
    private val pending = AtomicBoolean(false)
    @Volatile private var latest: MarkerState? = null
    /** Marker images handed to the map once and reused, keyed by icon, limit and emphasis. */
    private val descriptors = HashMap<String, BitmapDescriptor>()
    /** The map to draw on. Setting a different map forgets the old markers (they went with it) and its descriptors. */
    var map: GoogleMap? = null
        set(value) {
            if (field !== value) target = value?.let { m ->
                object : MarkerMap {
                    override val zoom get() = m.cameraPosition.zoom
                    override fun screenBounds() = screenBounds(m)
                    override fun addMarker(options: MarkerOptions) = m.addMarker(options)?.let { mk ->
                        object : Drawn {
                            override fun remove() = mk.remove()
                            override fun setIcon(icon: BitmapDescriptor) = mk.setIcon(icon)
                            override fun setZIndex(z: Float) { mk.zIndex = z }
                            override fun setRotation(degrees: Float) { mk.rotation = degrees }
                        }
                    }
                }
            }
            field = value
        }

    /** What the draw goes through: [map] in production, a fake in the tests. A different one forgets what was drawn. */
    internal var target: MarkerMap? = null
        set(value) {
            if (field !== value) { shown.clear(); descriptors.clear() }
            field = value
        }

    /**
     * Called on every fix (any thread) with the coordinator's [MarkerState]: the nearby POIs from the
     * grid cache with their set, category and resolved icon; the fix the engine used (position in
     * degrees), or null before the first fix (then no range clip, no distance ranking and no ACTIVE);
     * the alert being shown, whose POI is drawn ACTIVE for 15 s; and the verdicts, which drive the
     * emphasis (when empty, not wired yet, `facingAway` alone marks the dimmed cameras).
     */
    fun redraw(state: MarkerState) {
        latest = state
        if (pending.compareAndSet(false, true)) main.post { pending.set(false); drawLatest() }
    }

    // One redraw on the main thread for the newest state: pick (§8.1 step 1), rank and cap (step 2),
    // work out each marker's state (step 3), diff against what's drawn (step 4).
    private fun drawLatest() {
        val m = target ?: return
        val state = latest ?: return
        val pois = state.nearby; val fix = state.lastFix; val active = state.active
        val zoom = m.zoom
        val bounds = m.screenBounds() ?: return
        var south = bounds.southwest.latitude; var north = bounds.northeast.latitude
        var west = bounds.southwest.longitude; var east = bounds.northeast.longitude
        // Clipped to MAX_RANGE_M around the car (§8.1): the tilted camera's top corners sit near the horizon.
        if (fix != null) {
            // Metres per degree of latitude; longitude shrinks with the cosine (floored away from the poles).
            val dLat = MAX_RANGE_M / 111_195.0
            val dLon = dLat / Math.cos(Math.toRadians(fix.lat)).coerceAtLeast(0.1)
            south = maxOf(south, fix.lat - dLat); north = minOf(north, fix.lat + dLat)
            if (west <= east) { west = maxOf(west, fix.lon - dLon); east = minOf(east, fix.lon + dLon) }
        }
        // A 10 % margin, so markers at the edge don't drop out as the map moves (§8.1).
        val latMargin = (north - south) * BOUNDS_MARGIN
        south -= latMargin; north += latMargin
        if (west <= east) {
            val lonMargin = (east - west) * BOUNDS_MARGIN
            west -= lonMargin; east += lonMargin
        }
        // A plain loop without allocation per POI; the bounds may cross the antimeridian (west > east).
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
            // ACTIVE on top of everything (§8.1); the arrow sits half a level under its own icon.
            val z = if (emphasis == Emphasis.ACTIVE) 10f else 1f
            val existing = shown[r.poi.id]
            val arrowWanted = hasArrow(r)
            val bearing = r.poi.bearing ?: 0f
            if (existing == null) {
                val marker = m.addMarker(options(r, descriptor(r, iconKey, emphasis), z).title(r.categoryName)) ?: continue
                val arrow = if (arrowWanted) addArrow(m, r, emphasis, bearing, z) else null
                shown[r.poi.id] = Shown(marker, emphasis, iconKey, arrow, bearing, r.poi.directionType)
                continue
            }
            // Still visible: change only what differs (§8.1 step 4); in steady driving that's 0–3 markers a fix.
            if (existing.emphasis != emphasis || existing.iconKey != iconKey) {
                existing.marker.setIcon(descriptor(r, iconKey, emphasis))
                existing.marker.setZIndex(z)
                existing.arrow?.let { a ->
                    if (existing.emphasis != emphasis || existing.arrowDir != r.poi.directionType) a.setIcon(arrowDescriptor(r.poi.directionType, emphasis))
                    a.setZIndex(z - 0.5f)
                }
                existing.emphasis = emphasis
                existing.iconKey = iconKey
                existing.arrowDir = r.poi.directionType
            }
            // The arrow comes or goes with the bearing in the data; a bearing that changed turns the one there.
            if (arrowWanted != (existing.arrow != null)) {
                existing.arrow?.remove()
                existing.arrow = if (arrowWanted) addArrow(m, r, emphasis, bearing, z) else null
                existing.arrowRotation = bearing
            } else if (existing.arrow != null && existing.arrowRotation != bearing) {
                existing.arrow?.setRotation(bearing)
                existing.arrowRotation = bearing
            }
        }
    }

    /** What every marker of a POI shares (§8.1): at the POI, centred on it, at [z]. */
    private fun options(r: ResolvedPoi, icon: BitmapDescriptor, z: Float) =
        MarkerOptions().position(LatLng(r.poi.lat, r.poi.lon)).icon(icon).anchor(0.5f, 0.5f).zIndex(z)

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
            val pt = android.graphics.Point()
            for (i in 0 until 4) {
                pt.set(if (i and 1 == 0) 0 else size.width, if (i < 2) 0 else size.height)
                b.include(p.fromScreenLocation(pt))
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
    private fun emphasisOf(r: ResolvedPoi, state: MarkerState): Emphasis {
        val active = state.active
        if (active?.poiId == r.poi.id && System.currentTimeMillis() - active.timeMs < 15_000) return Emphasis.ACTIVE
        return when (state.verdicts[r.poi.id]) {
            Verdict.AHEAD_OK -> Emphasis.AHEAD
            Verdict.WRONG_DIRECTION -> Emphasis.FACING_AWAY
            else -> if (r.poi.id in state.facingAway) Emphasis.FACING_AWAY else Emphasis.OTHER
        }
    }

    // An arrow for every camera with a bearing in its data: single-headed ONE_WAY, two-headed BOTH (§8.1).
    private fun hasArrow(r: ResolvedPoi): Boolean = r.poi.bearing != null && r.poi.directionType != DirectionType.ANY

    // Flat and rotated to the true bearing: the follow camera is heading-up, so the icon itself stays
    // upright and the arrow is a second marker beneath it (§8.1 "direction arrow").
    private fun addArrow(m: MarkerMap, r: ResolvedPoi, emphasis: Emphasis, bearing: Float, z: Float): Drawn? =
        m.addMarker(options(r, arrowDescriptor(r.poi.directionType, emphasis), z - 0.5f).flat(true).rotation(bearing))

    private fun arrowDescriptor(dir: DirectionType, emphasis: Emphasis): BitmapDescriptor {
        val iconPx = if (emphasis == Emphasis.ACTIVE) (basePx * 1.3f).toInt() else basePx
        return arrowOf(dir, emphasis, (iconPx * ARROW_SCALE).toInt())
    }

    // One descriptor per (icon, limit badge, emphasis), handed to the map once; a crude cap keeps the
    // table bounded over a long drive through many sets.
    private fun descriptor(r: ResolvedPoi, iconKey: String, emphasis: Emphasis): BitmapDescriptor {
        if (descriptors.size > 256) descriptors.clear()
        return descriptors.getOrPut("$iconKey:$emphasis") {
            toDescriptor(icons.marker(r.iconId, r.poi.speedLimit, emphasis, basePx))
        }
    }

    /** Removes every marker (the session is ending); posted to the main thread like the redraws. */
    fun clear() {
        main.post { shown.values.forEach { it.marker.remove(); it.arrow?.remove() }; shown.clear() }
    }

    companion object {
        /** The cap on markers drawn, arrows included (§8.1 step 2: the closest 200). */
        const val MAX_MARKERS = 200
        /** Markers are drawn this far from the car at most (§8.1). */
        private const val MAX_RANGE_M = 3_000.0
        /** The arrow square is this many times the icon, so the head sits outside the icon (§8.1). */
        const val ARROW_SCALE = 2.2f
        /** The 10 % margin round the screen bounds, so markers at the edge don't drop out (§8.1 step 1). */
        private const val BOUNDS_MARGIN = 0.10
    }
}
