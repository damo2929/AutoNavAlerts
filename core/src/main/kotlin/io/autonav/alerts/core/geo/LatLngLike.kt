// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.geo

/**
 * A position in degrees, so `:core` can take route points from the app (the Navigation SDK's
 * `LatLng`) without depending on its map types. `RoutePolyline`, `LimitLookahead.roadAhead` and
 * `RoadAheadMatcher` (DESIGN.md §11 step 4, §8.4a) speak this.
 */
interface LatLngLike {
    val lat: Double
    val lon: Double
}

/** A plain position. */
/** A plain position, degrees; what `:core` builds when it has no app type to hand. */
data class LatLngPoint(override val lat: Double, override val lon: Double) : LatLngLike
