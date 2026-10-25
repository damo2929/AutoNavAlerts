// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import io.autonav.alerts.data.LimitSource
import io.autonav.alerts.map.MapSurface

/**
 * What the car screens read from their session (DESIGN.md §3, §8): the app's singletons, the
 * display units, the map surface and the best-known limit for a report. [NavSession] is the one
 * implementation; the screens are typed against this so their templates can be built in a JVM
 * test without a host-made session. Main thread, as the screens are. It adds no behaviour, so the
 * screens' hard rules (2: nothing twice, no stop of ours; 3: no re-send of an unchanged template)
 * are unchanged by it.
 */
interface CarScreenHost {
    /** The app's singletons (`CarDeps`); valid once the session has created its first screen. */
    val deps: CarDeps
    /** Miles, yards and mph when true (§13 units). */
    val imperial: Boolean
    /** The map on the car's surface, which Home and the route preview tell about their cards (§8). */
    val surface: MapSurface
    /** Best-known speed limit now and its source, for the one-tap report (§8.5). */
    fun currentLimit(): Pair<Int?, LimitSource>
}
