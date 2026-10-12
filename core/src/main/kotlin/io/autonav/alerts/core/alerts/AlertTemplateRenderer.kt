// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.SpeedUnit
import kotlin.math.roundToInt

enum class Units { IMPERIAL, METRIC }

/**
 * Values available to a template (DESIGN.md §7.2): {category} {speed} {units} {distance} {direction}.
 * Null = missing, and its clause is dropped. [setName] is only the fallback for {category}.
 */
data class TemplateValues(
    val category: String? = null,
    val speed: Int? = null,
    val speedUnit: SpeedUnit? = null,
    val distanceM: Double? = null,
    val direction: String? = null,
    val setName: String? = null,
)

data class RenderedAlert(val display: String, val spoken: String)

/**
 * Renders "{category} ahead, {speed} limit" style templates. Clauses are split on commas; a clause
 * that references a missing placeholder is dropped, then spacing and punctuation are tidied.
 */
class AlertTemplateRenderer(private val units: Units = Units.IMPERIAL) {

    fun render(template: String, v: TemplateValues): RenderedAlert =
        RenderedAlert(display = renderOne(template, v, spoken = false), spoken = renderOne(template, v, spoken = true))

    private fun renderOne(template: String, v: TemplateValues, spoken: Boolean): String {
        val speedInUnits = v.speed?.let { convertSpeed(it, v.speedUnit ?: SpeedUnit.MPH) }
        val values = mapOf(
            "category" to (v.category ?: v.setName),
            "speed" to speedInUnits?.toString(),
            "units" to speedInUnits?.let { if (spoken) spokenSpeedUnits() else shortSpeedUnits() },
            "distance" to v.distanceM?.let { if (spoken) spokenDistance(it) else shortDistance(it) },
            "direction" to v.direction,
        )
        val clauses = template.split(',').mapNotNull { clause ->
            val keys = PLACEHOLDER.findAll(clause).map { it.groupValues[1] }.toList()
            if (keys.any { values[it] == null && it in values }) return@mapNotNull null
            var text = PLACEHOLDER.replace(clause) { m -> values[m.groupValues[1]] ?: m.value }
            if (!spoken && "{speed}" in clause && "{units}" !in clause && speedInUnits != null) {
                text = text.replace(Regex("""\b$speedInUnits\b(?!\s*(mph|km/h))"""), "$speedInUnits ${shortSpeedUnits()}")
            }
            text.trim().takeIf { it.isNotEmpty() }
        }
        return clauses.joinToString(", ").replace(Regex("""\s+"""), " ").trim()
    }

    private fun convertSpeed(speed: Int, from: SpeedUnit): Int = when {
        units == Units.IMPERIAL && from == SpeedUnit.KMH -> (speed / 1.609344).roundToInt()
        units == Units.METRIC && from == SpeedUnit.MPH -> (speed * 1.609344).roundToInt()
        else -> speed
    }

    private fun shortSpeedUnits() = if (units == Units.IMPERIAL) "mph" else "km/h"
    private fun spokenSpeedUnits() = if (units == Units.IMPERIAL) "miles per hour" else "kilometres per hour"

    fun shortDistance(m: Double): String = if (units == Units.IMPERIAL) {
        val yd = m / Geo.METRES_PER_YARD
        if (yd < 1000) "${roundTo(yd, 10)} yd" else "%.1f mi".format(m / 1609.344)
    } else {
        if (m < 1000) "${roundTo(m, 10)} m" else "%.1f km".format(m / 1000)
    }

    fun spokenDistance(m: Double): String = if (units == Units.IMPERIAL) {
        val miles = m / 1609.344
        when {
            miles >= 0.9 -> "%.0f mile%s".format(miles, if (miles >= 1.5) "s" else "")
            miles >= 0.65 -> "three quarters of a mile"
            miles >= 0.4 -> "half a mile"
            miles >= 0.22 -> "a quarter of a mile"
            else -> "${roundTo(m / Geo.METRES_PER_YARD, 50).coerceAtLeast(50)} yards"
        }
    } else {
        if (m >= 1000) "%.1f kilometres".format(m / 1000) else "${roundTo(m, 50).coerceAtLeast(50)} metres"
    }

    private fun roundTo(x: Double, step: Int): Int = ((x / step).roundToInt() * step)

    companion object {
        private val PLACEHOLDER = Regex("""\{(\w+)\}""")

        const val DEFAULT_FIRST = "{category} ahead, {speed} limit"
        const val DEFAULT_SECOND = "{category}, {distance}"
        const val DEFAULT_PASSING = "{category} now"
        const val ZONE_ENTER = "Entering average speed zone, {speed} limit"
        const val ZONE_EXIT = "Leaving average speed zone"
        const val ZONE_OVERSPEED = "Average speed zone, {speed} limit, you are over the limit"
        const val OVERSPEED_SUFFIX = "you are over the limit"
    }
}
