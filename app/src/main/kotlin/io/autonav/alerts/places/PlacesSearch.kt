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
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.nav.NavigatorHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * One row of the car's search list: an autocomplete prediction (no position yet, [placeId] only), a text-search
 * result (position known), or a contact address turned into a hit. [distanceM] from the car, when known.
 */
data class SearchHit(val placeId: String?, val title: String, val subtitle: String?, val distanceM: Int?, val lat: Double? = null, val lon: Double? = null)

/** Awaits a Play Services Task; any failure becomes null, so a search error shows as "no results", never a crash on the car screen. */
private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t -> cont.resume(if (t.isSuccessful) t.result else null) }
}

/** Whole metres from this fix to ([lat], [lon]), as `Location.distanceBetween` gives it: the rows' distance text (§8.2, §8.3). */
internal fun Location.metresTo(lat: Double, lon: Double): Int = FloatArray(1).also { Location.distanceBetween(latitude, longitude, lat, lon, it) }[0].toInt()

/**
 * Places search for the car's Search screen, and the recents it feeds (DESIGN.md §8 "Search", §8.2). A Hilt
 * singleton. Called by `SearchScreen` (autocomplete as you type, text search on submit or voice, resolve on a
 * tap), `ContactsSource.locate` (text search of an address, §8.3) and the phone's Places screen (recents).
 * Suspend functions, callable from any coroutine; the Places client does its own threading.
 * The client comes from `NavigatorHolder.placesClient()`, which applies the user's own Maps key first and
 * returns null without one (§1.4, hard rule 5): every search is billed to the user's account.
 * Cost rules of §16 #12: one session token per search session, a 3-character minimum, and a 50 km bias.
 */
@Singleton
class PlacesSearch @Inject constructor(
    private val holder: NavigatorHolder,
    private val db: AppDatabase,
    private val eventLog: EventLog,
) {
    // The autocomplete session (§16 #12): one token from the first keystroke until resolve() fetches the place,
    // so Google bills the typing and the fetch as one session rather than per request.
    private var token: AutocompleteSessionToken? = null

    /**
     * Autocomplete predictions for [query], biased to within 50 km of [near] (§8 "Search": debounced by the
     * caller). Empty below 3 characters, without a Maps key, or on any error. At most 6: the car list is short.
     */
    suspend fun autocomplete(query: String, near: Location?): List<SearchHit> {
        if (query.trim().length < 3) return emptyList()
        val client = holder.placesClient() ?: return emptyList()
        val t = token ?: AutocompleteSessionToken.newInstance().also { token = it }
        val req = FindAutocompletePredictionsRequest.builder().setQuery(query).setSessionToken(t).apply {
            near?.let {
                setOrigin(LatLng(it.latitude, it.longitude))
                setLocationBias(bounds(it))
            }
        }.build()
        val resp = client.findAutocompletePredictions(req).awaitOrNull() ?: return emptyList()
        return resp.autocompletePredictions.take(6).map {
            SearchHit(it.placeId, it.getPrimaryText(null).toString(), it.getSecondaryText(null).toString(), it.distanceMeters)
        }
    }

    /**
     * Places Text Search (New) for a submitted or spoken query, or a contact's address (§8.3 "Geocoding"), up to
     * 6 results with positions, biased to 50 km of [near]. Every call is logged
     * `Search Places textSearch n= ms= status=OK|empty|error` to debug-nav (DESIGN.md §15.5).
     */
    suspend fun textSearch(query: String, near: Location?): List<SearchHit> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val client = holder.placesClient() ?: run { log(0, t0, "error noClient"); return emptyList() }
        val fields = listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.FORMATTED_ADDRESS, Place.Field.LOCATION)
        val req = SearchByTextRequest.builder(query, fields).setMaxResultCount(6).apply {
            near?.let { setLocationBias(bounds(it)) }
        }.build()
        val resp = client.searchByText(req).awaitOrNull() ?: run { log(0, t0, "error"); return emptyList() }
        val hits = resp.places.mapNotNull { p ->
            val loc = p.location ?: return@mapNotNull null
            SearchHit(p.id, p.displayName ?: "Place", p.formattedAddress, near?.metresTo(loc.latitude, loc.longitude), loc.latitude, loc.longitude)
        }
        log(hits.size, t0, if (hits.isEmpty()) "empty" else "OK")
        return hits
    }

    /** The 50 km bias around the car for autocomplete and text search (§16 #12). */
    private fun bounds(near: Location): CircularBounds = CircularBounds.newInstance(LatLng(near.latitude, near.longitude), 50_000.0)

    /** The §15.5 log line for a text search. */
    private fun log(n: Int, t0: Long, status: String) =
        eventLog.record(EventLog.Channel.NAV, "Search", "Places textSearch n=$n ms=${android.os.SystemClock.elapsedRealtime() - t0} status=$status")

    /**
     * Resolves a hit to a destination: a hit that already has a position is returned as it is; an autocomplete
     * prediction is fetched by place id. The fetch closes the autocomplete session (the token is sent with it and
     * then dropped, §16 #12). Null without a Maps key or when the place can't be fetched.
     */
    suspend fun resolve(hit: SearchHit): Destination? {
        if (hit.lat != null && hit.lon != null) return Destination(hit.title, hit.lat, hit.lon, hit.placeId)
        val id = hit.placeId ?: return null
        val client = holder.placesClient() ?: return null
        val req = FetchPlaceRequest.builder(id, listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.LOCATION, Place.Field.FORMATTED_ADDRESS))
            .apply { token?.let { setSessionToken(it) } }.build()
        // The session ends with this fetch; the next keystroke starts a new one.
        token = null
        val place = client.fetchPlace(req).awaitOrNull()?.place ?: return null
        val loc = place.location ?: return null
        return Destination(place.displayName ?: hit.title, loc.latitude, loc.longitude, place.id)
    }

    // --- Recents -----------------------------------------------------------------------------

    /** Favourites then recents, newest first (§8.2 "Car screen use"); the caller passes the host's list limit. */
    suspend fun recents(limit: Int = 50) = db.places().list(limit)

    /**
     * Records a destination the user navigated to (§8.2 "Our own app"): the same Google Place ID bumps the
     * existing row's time and count, anything else is a new recent; then the list is trimmed to 200.
     * A destination without a place id (a contact address with no match) is never deduped.
     */
    suspend fun remember(dest: Destination, address: String? = null) {
        val now = System.currentTimeMillis()
        val existing = dest.placeId?.let { db.places().byPlaceId(it) }
        if (existing != null) db.places().update(existing.copy(lastUsedAt = now, useCount = existing.useCount + 1))
        else db.places().insert(PlaceEntity(placeId = dest.placeId, name = dest.title, address = address, lat = dest.lat, lon = dest.lon, lastUsedAt = now))
        db.places().trimRecents()
    }

    /** Delete from the car row's action or the phone's swipe (§8.2 "Deleting"). */
    suspend fun delete(p: PlaceEntity) = db.places().delete(p)
    /** "Clear all recents" after its confirmation (§8.2). */
    suspend fun clearRecents() = db.places().clearRecents()
}
