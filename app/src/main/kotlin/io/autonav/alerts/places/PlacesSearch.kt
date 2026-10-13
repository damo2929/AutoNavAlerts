// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.places

import android.location.Location
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.SearchByTextRequest
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.data.PlaceKind
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.nav.NavigatorHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class SearchHit(val placeId: String?, val title: String, val subtitle: String?, val distanceM: Int?, val lat: Double? = null, val lon: Double? = null)

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t -> cont.resume(if (t.isSuccessful) t.result else null) }
}

/** Places search (session tokens, location bias, 3-char minimum) and recents (DESIGN.md §8, §8.2). */
@Singleton
class PlacesSearch @Inject constructor(
    private val holder: NavigatorHolder,
    private val db: AppDatabase,
    private val eventLog: EventLog,
) {
    private var token: AutocompleteSessionToken? = null

    suspend fun autocomplete(query: String, near: Location?): List<SearchHit> {
        if (query.trim().length < 3) return emptyList()
        val client = holder.placesClient() ?: return emptyList()
        val t = token ?: AutocompleteSessionToken.newInstance().also { token = it }
        val req = FindAutocompletePredictionsRequest.builder().setQuery(query).setSessionToken(t).apply {
            near?.let {
                setOrigin(LatLng(it.latitude, it.longitude))
                setLocationBias(CircularBounds.newInstance(LatLng(it.latitude, it.longitude), 50_000.0))
            }
        }.build()
        val resp = client.findAutocompletePredictions(req).awaitOrNull() ?: return emptyList()
        return resp.autocompletePredictions.take(6).map {
            SearchHit(it.placeId, it.getPrimaryText(null).toString(), it.getSecondaryText(null).toString(), it.distanceMeters)
        }
    }

    /** Places Text Search; every call is logged `Search Places textSearch n= ms= status=OK|empty|error` (DESIGN.md §15.5). */
    suspend fun textSearch(query: String, near: Location?): List<SearchHit> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val client = holder.placesClient() ?: run { log(0, t0, "error noClient"); return emptyList() }
        val fields = listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.FORMATTED_ADDRESS, Place.Field.LOCATION)
        val req = SearchByTextRequest.builder(query, fields).setMaxResultCount(6).apply {
            near?.let { setLocationBias(CircularBounds.newInstance(LatLng(it.latitude, it.longitude), 50_000.0)) }
        }.build()
        val resp = client.searchByText(req).awaitOrNull() ?: run { log(0, t0, "error"); return emptyList() }
        val hits = resp.places.mapNotNull { p ->
            val loc = p.location ?: return@mapNotNull null
            val d = near?.let { n -> FloatArray(1).also { Location.distanceBetween(n.latitude, n.longitude, loc.latitude, loc.longitude, it) }[0].toInt() }
            SearchHit(p.id, p.displayName ?: "Place", p.formattedAddress, d, loc.latitude, loc.longitude)
        }
        log(hits.size, t0, if (hits.isEmpty()) "empty" else "OK")
        return hits
    }

    private fun log(n: Int, t0: Long, status: String) =
        eventLog.record(EventLog.Channel.NAV, "Search", "Places textSearch n=$n ms=${android.os.SystemClock.elapsedRealtime() - t0} status=$status")

    /** Resolves a hit to a destination, fetching its location if needed (ends the session token). */
    suspend fun resolve(hit: SearchHit): Destination? {
        if (hit.lat != null && hit.lon != null) return Destination(hit.title, hit.lat, hit.lon, hit.placeId)
        val id = hit.placeId ?: return null
        val client = holder.placesClient() ?: return null
        val req = FetchPlaceRequest.builder(id, listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.LOCATION, Place.Field.FORMATTED_ADDRESS))
            .apply { token?.let { setSessionToken(it) } }.build()
        token = null
        val place = client.fetchPlace(req).awaitOrNull()?.place ?: return null
        val loc = place.location ?: return null
        return Destination(place.displayName ?: hit.title, loc.latitude, loc.longitude, place.id)
    }

    // --- Recents -----------------------------------------------------------------------------

    suspend fun recents(limit: Int = 50) = db.places().list(limit)

    suspend fun remember(dest: Destination, address: String? = null) {
        val now = System.currentTimeMillis()
        val existing = dest.placeId?.let { db.places().byPlaceId(it) }
        if (existing != null) db.places().update(existing.copy(lastUsedAt = now, useCount = existing.useCount + 1))
        else db.places().insert(PlaceEntity(placeId = dest.placeId, name = dest.title, address = address, lat = dest.lat, lon = dest.lon, lastUsedAt = now))
        db.places().trimRecents()
    }

    suspend fun delete(p: PlaceEntity) = db.places().delete(p)
    suspend fun restore(p: PlaceEntity) { db.places().insert(p.copy(id = 0)) }
    suspend fun clearRecents() = db.places().clearRecents()
    suspend fun setKind(p: PlaceEntity, kind: PlaceKind) = db.places().update(p.copy(kind = kind))
}
