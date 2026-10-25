// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.SpeedUnit
import kotlin.math.roundToInt

/** The user's display units (DESIGN.md §13 settings): yards and miles with mph, or metres and km with km/h. */
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

/** The two forms of one alert (DESIGN.md §7.2): short units on screen ("30 mph"), full words for speech. */
data class RenderedAlert(val display: String, val spoken: String)

/**
 * Renders "{category} ahead, {speed} limit" style templates. Clauses are split on commas; a clause
 * that references a missing placeholder is dropped, then spacing and punctuation are tidied.
 *
 * DESIGN.md §7.2 is the spec: the only placeholders are {category} {speed} {units} {distance}
 * {direction}, the display and spoken texts come from the same template, and a missing value drops
 * its clause ("Fixed camera ahead" when there's no limit). No Android, no threads of its own: the
 * app's `AlertCoordinator` renders each event on the alert thread; `PoiRepository` takes the default
 * templates from it and the phone's set editor renders previews. Speeds come in with their own unit and go out converted to
 * [units]; distances are metres in.
 */
class AlertTemplateRenderer(private val units: Units = Units.IMPERIAL) {

    /** Both forms of [template] filled from [v]. Never throws on an unknown placeholder: it is left as typed. */
    fun render(template: String, v: TemplateValues): RenderedAlert =
        RenderedAlert(display = renderOne(template, v, spoken = false), spoken = renderOne(template, v, spoken = true))

    private fun renderOne(template: String, v: TemplateValues, spoken: Boolean): String {
        val speedInUnits = v.speed?.let { (v.speedUnit ?: SpeedUnit.MPH).toDisplay(it, units == Units.IMPERIAL) }
        val values = mapOf(
            "category" to (v.category ?: v.setName),
            "speed" to speedInUnits?.toString(),
            "units" to speedInUnits?.let { if (spoken) spokenSpeedUnits() else shortSpeedUnits() },
            "distance" to v.distanceM?.let { if (spoken) spokenDistance(it) else shortDistance(it) },
            "direction" to v.direction,
        )
        val clauses = template.split(',').mapNotNull { clause ->
            val keys = PLACEHOLDER.findAll(clause).map { it.groupValues[1] }.toList()
            // A clause naming a known placeholder with no value goes (§7.2 "Rendering rules"); an unknown
            // placeholder name is not ours to judge and stays in the text.
            if (keys.any { values[it] == null && it in values }) return@mapNotNull null
            var text = PLACEHOLDER.replace(clause) { m -> values[m.groupValues[1]] ?: m.value }
            // On screen a bare "{speed}" reads as "30 mph", not "30": the unit is added unless the template
            // already has {units} or the number already carries one.
            if (!spoken && "{speed}" in clause && "{units}" !in clause && speedInUnits != null) {
                text = text.replace(Regex("""\b$speedInUnits\b(?!\s*(mph|km/h))"""), "$speedInUnits ${shortSpeedUnits()}")
            }
            text.trim().takeIf { it.isNotEmpty() }
        }
        return clauses.joinToString(", ").replace(Regex("""\s+"""), " ").trim()
    }

    private fun shortSpeedUnits() = if (units == Units.IMPERIAL) "mph" else "km/h"
    private fun spokenSpeedUnits() = if (units == Units.IMPERIAL) "miles per hour" else "kilometres per hour"

    /** Display form of [m] metres: "140 yd" (10 yd steps) / "1.2 mi", or "300 m" / "1.2 km" (§7.2, §12.1 subtitle). */
    fun shortDistance(m: Double): String = if (units == Units.IMPERIAL) {
        val yd = m / Geo.METRES_PER_YARD
        if (yd < 1000) "${roundTo(yd, 10)} yd" else "%.1f mi".format(m / Geo.METRES_PER_MILE)
    } else {
        if (m < 1000) "${roundTo(m, 10)} m" else "%.1f km".format(m / 1000)
    }

    /**
     * Spoken form of [m] metres (§7.2 {distance}): whole miles from 0.9 mi, then "three quarters of a
     * mile", "half a mile", "a quarter of a mile", else yards in 50 yd steps (never under 50); metric
     * is kilometres to one decimal or metres in 50 m steps.
     */
    fun spokenDistance(m: Double): String = if (units == Units.IMPERIAL) {
        val miles = m / Geo.METRES_PER_MILE
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

        // The default templates of §7.2: one per stage, and the three zone texts of §11 step 8.
        const val DEFAULT_FIRST = "{category} ahead, {speed} limit"
        const val DEFAULT_SECOND = "{category}, {distance}"
        const val DEFAULT_PASSING = "{category} now"
        const val ZONE_ENTER = "Entering average speed zone, {speed} limit"
        const val ZONE_EXIT = "Leaving average speed zone"
        const val ZONE_OVERSPEED = "Average speed zone, {speed} limit, you are over the limit"
        const val OVERSPEED_SUFFIX = "you are over the limit"
    }
}
