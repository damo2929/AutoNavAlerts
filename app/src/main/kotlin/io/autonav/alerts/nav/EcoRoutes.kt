// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.settings.MapsKeyCreator
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Energy-efficient routes for an electric car from Google's Routes API (DESIGN.md §8.3a): the
 * eco-friendly ("FUEL_EFFICIENT") route's token, which the Navigation SDK then follows. Uses the
 * user's own Maps key, so it's billed to them like the rest of their Maps use.
 */
@Singleton
class EcoRoutes @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val keys: MapsKeyCreator,
) {
    /** One route Google offers: its token, time with traffic, distance, and time without traffic. */
    data class Route(
        val token: String, val seconds: Int, val meters: Int, val freeFlowSeconds: Int? = null,
        /** Metres from the start to the first stretch Google expects to be jammed when you get there; null = none. */
        val firstJamMeters: Int? = null,
    ) {
        /** Rough minutes until you reach that first queue (its share of the route × the route's time). */
        val minutesToFirstJam: Int? get() = firstJamMeters?.let { m ->
            if (meters <= 0) null else ((m.toDouble() / meters) * seconds / 60).toInt()
        }

        /** Minutes lost to traffic (0 if Google gave no traffic-free time). */
        val delayMinutes: Int get() = freeFlowSeconds?.let { ((seconds - it).coerceAtLeast(0) + 30) / 60 } ?: 0

        /** Light / some / heavy traffic from the delay against the traffic-free time (DESIGN.md §8.3a). */
        val traffic: Traffic get() {
            val free = freeFlowSeconds ?: return Traffic.UNKNOWN
            val delay = (seconds - free).coerceAtLeast(0)
            val share = if (free > 0) delay.toDouble() / free else 0.0
            return when {
                delay < 180 || share < 0.10 -> Traffic.LIGHT
                share < 0.25 -> Traffic.SOME
                else -> Traffic.HEAVY
            }
        }
    }

    enum class Traffic { LIGHT, SOME, HEAVY, UNKNOWN }

    /** The fastest and the energy-efficient route (the same one when Google says so), or why not. */
    sealed class Result {
        data class Routes(val fastest: Route?, val efficient: Route, val same: Boolean) : Result()
        data class Unavailable(val reason: String) : Result()
    }

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val certHex by lazy { runCatching { keys.signingSha1().replace(":", "") }.getOrDefault("") }

    /** The fastest and energy-efficient routes from (lat, lon) to the destination. Never throws. */
    suspend fun route(fromLat: Double, fromLon: Double, dest: Destination, avoidTolls: Boolean): Result = withContext(Dispatchers.IO) {
        val key = settings.mapsApiKey() ?: return@withContext Result.Unavailable("no Maps key")
        val destination = JSONObject().apply {
            if (dest.placeId != null) put("placeId", dest.placeId) else put("location", latLng(dest.lat, dest.lon))
        }
        val body = JSONObject()
            .put("origin", JSONObject().put("location", latLng(fromLat, fromLon)))
            .put("destination", destination)
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE_OPTIMAL")
            .put("requestedReferenceRoutes", JSONArray().put("FUEL_EFFICIENT"))
            // Where along each route traffic is expected to be slow or jammed when you get there.
            .put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE"))
            .put("routeModifiers", JSONObject().put("vehicleInfo", JSONObject().put("emissionType", "ELECTRIC")).put("avoidTolls", avoidTolls))
        val req = Request.Builder().url(URL)
            .header("X-Goog-Api-Key", key)
            .header("X-Goog-FieldMask", "routes.routeToken,routes.routeLabels,routes.duration,routes.staticDuration,routes.distanceMeters," +
                "routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals")
            // The key is restricted to this app (§1.4); a REST call must say which app it is.
            .header("X-Android-Package", context.packageName)
            .header("X-Android-Cert", certHex)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                    Log.w(TAG, "Routes API ${r.code}: $msg")
                    return@withContext Result.Unavailable(
                        if (r.code == 403) "turn on the Routes API for your Maps key in Settings" else "Routes API error ${r.code}")
                }
                val json = JSONObject(text)
                val routes = json.optJSONArray("routes")
                Log.i(TAG, "Routes API: ${routes?.length() ?: 0} routes, labels " +
                    (0 until (routes?.length() ?: 0)).joinToString { routes!!.getJSONObject(it).optJSONArray("routeLabels").toString() })
                pick(json)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Routes API call failed: ${e.message}")
            Result.Unavailable("no connection to the Routes API")
        }
    }

    companion object {
        private const val TAG = "EcoRoutes"
        private const val URL = "https://routes.googleapis.com/directions/v2:computeRoutes"

        /** Metres along the route to the first TRAFFIC_JAM interval, or null. */
        private fun firstJam(r: JSONObject): Int? {
            val intervals = r.optJSONObject("travelAdvisory")?.optJSONArray("speedReadingIntervals") ?: return null
            val jamStart = (0 until intervals.length()).map { intervals.getJSONObject(it) }
                .filter { it.optString("speed") == "TRAFFIC_JAM" }
                .minOfOrNull { it.optInt("startPolylinePointIndex", 0) } ?: return null
            val pts = decode(r.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty())
            if (pts.size < 2) return null
            var m = 0.0
            for (i in 1..minOf(jamStart, pts.size - 1)) {
                m += io.autonav.alerts.core.geo.Geo.distanceM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
            }
            return m.toInt()
        }

        /** Google's encoded polyline → (lat, lon) points. */
        fun decode(s: String): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            var i = 0; var lat = 0; var lon = 0
            fun next(): Int {
                var shift = 0; var result = 0
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                return if (result and 1 != 0) (result shr 1).inv() else result shr 1
            }
            while (i < s.length) {
                lat += next(); if (i >= s.length) break
                lon += next()
                out += lat / 1e5 to lon / 1e5
            }
            return out
        }

        private fun latLng(lat: Double, lon: Double) =
            JSONObject().put("latLng", JSONObject().put("latitude", lat).put("longitude", lon))

        /**
         * The DEFAULT_ROUTE and FUEL_EFFICIENT routes. Google returns one route with both labels
         * when the fastest is also the most efficient; with no FUEL_EFFICIENT label (no eco route
         * there) the default is used for both.
         */
        fun pick(response: JSONObject): Result {
            val routes = response.optJSONArray("routes") ?: return Result.Unavailable("no route there")
            val all = (0 until routes.length()).map { routes.getJSONObject(it) }
            fun labels(r: JSONObject) = r.optJSONArray("routeLabels")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            fun route(r: JSONObject?): Route? {
                val token = r?.optString("routeToken")?.ifBlank { null } ?: return null
                val seconds = r.optString("duration").removeSuffix("s").toDoubleOrNull()?.toInt() ?: return null
                val free = r.optString("staticDuration").removeSuffix("s").toDoubleOrNull()?.toInt()
                return Route(token, seconds, r.optInt("distanceMeters"), free, firstJam(r))
            }
            val defaultJson = all.firstOrNull { "DEFAULT_ROUTE" in labels(it) } ?: all.firstOrNull()
            val efficientJson = all.firstOrNull { "FUEL_EFFICIENT" in labels(it) } ?: defaultJson
            val efficient = route(efficientJson) ?: return Result.Unavailable("no route token from Google")
            val same = efficientJson === defaultJson
            return Result.Routes(if (same) efficient else route(defaultJson), efficient, same)
        }
    }
}
