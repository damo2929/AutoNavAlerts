// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.geo

/** A position, so `:core` can take route points from the app without depending on its map types. */
interface LatLngLike {
    val lat: Double
    val lon: Double
}

/** A plain position. */
data class LatLngPoint(override val lat: Double, override val lon: Double) : LatLngLike
