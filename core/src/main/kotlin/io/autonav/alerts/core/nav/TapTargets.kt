// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import kotlin.math.max

/**
 * Which of the car map's buttons a tap belongs to (DESIGN.md §8 "Map buttons", owner 2026-11-01: "make the
 * buttons work if you hit on any pixel in the box, doesn't have to be right the centre"). A tap counts for a
 * button anywhere in its box or within [slop] pixels around it, so the edges and the gaps between buttons still
 * work; where two margins overlap, the button whose box is nearest wins. Pure; `MapSurface` calls it on the
 * main thread. Hard rule 4 is untouched: the buttons stay where they are, only their tap areas grow.
 */
object TapTargets {
    /** A button's box in surface pixels, with [id] for the caller to act on. */
    data class Box(val id: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** The [Box.id] of the button the tap at [x], [y] belongs to, or null when it is on the map. */
    fun pick(x: Float, y: Float, boxes: List<Box>, slop: Float): Int? =
        boxes.map { it to distance(x, y, it) }
            .filter { (_, d) -> d <= slop }
            .minByOrNull { (_, d) -> d }
            ?.first?.id

    /** How far the point is outside [b] (0 inside), the larger of the horizontal and vertical gaps. */
    private fun distance(x: Float, y: Float, b: Box): Float =
        max(max(b.left - x, x - b.right).coerceAtLeast(0f), max(b.top - y, y - b.bottom).coerceAtLeast(0f))
}
