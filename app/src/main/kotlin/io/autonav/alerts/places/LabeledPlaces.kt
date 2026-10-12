// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.places

import org.json.JSONArray
import org.json.JSONObject

/**
 * Google's labeled places (Data Portability `maps.aliased_places`): GeoJSON features with a name, address and usually coordinates (DESIGN.md §8.2a).
 */
object LabeledPlaces {
    enum class Label { HOME, WORK, OTHER }

    data class Labeled(val label: Label, val name: String, val address: String?, val lat: Double?, val lon: Double?)

    /** Every labeled place in [json]; anything unreadable is skipped. */
    fun parse(json: String): List<Labeled> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val features = root.optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i -> features.optJSONObject(i)?.let(::feature) }
    }

    private fun feature(f: JSONObject): Labeled? {
        val props = f.optJSONObject("properties") ?: f.optJSONObject("property") ?: JSONObject()
        val name = props.optString("name").ifBlank { props.optString("title") }.trim()
        val type = (props.optString("semantic_type").ifBlank { props.optString("semanticType") }).uppercase()
        val label = when {
            type.endsWith("HOME") || name.equals("home", ignoreCase = true) -> Label.HOME
            type.endsWith("WORK") || name.equals("work", ignoreCase = true) -> Label.WORK
            else -> Label.OTHER
        }
        val address = props.optString("address").ifBlank { null }
            ?: props.optJSONObject("location")?.optString("address")?.ifBlank { null }
        var lat: Double? = null
        var lon: Double? = null
        f.optJSONObject("geometry")?.let { g ->
            val c = g.opt("coordinates")
            when (c) {
                is JSONArray -> if (c.length() >= 2) { lon = c.optDouble(0); lat = c.optDouble(1) }
                is JSONObject -> { lat = c.optDouble("latitude", c.optDouble("lat")); lon = c.optDouble("longitude", c.optDouble("lng")) }
            }
        }
        // [0, 0] means no position.
        if (lat?.isNaN() != false || lon?.isNaN() != false || (lat == 0.0 && lon == 0.0)) { lat = null; lon = null }
        if (name.isEmpty() && address == null && lat == null) return null
        return Labeled(label, name.ifEmpty { address ?: "Place" }, address, lat, lon)
    }
}
