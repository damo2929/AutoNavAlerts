// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.core.contacts.ContactMatch
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.ContactGeocodeEntity
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.places.PlacesSearch
import io.autonav.alerts.places.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One postal address of one contact. */
data class ContactAddress(val dataId: Long, val name: String, val label: String, val address: String) {
    /** "Mum (Home)": the title used for the route and the recent. */
    val title: String get() = "$name ($label)"
}

/** Where a picked contact address is: a destination, a choice between results, or not found. */
sealed interface Located {
    data class Found(val destination: Destination, val address: String) : Located
    data class Choose(val options: List<SearchHit>) : Located
    data object NotFound : Located
}

/**
 * Contacts' postal addresses for navigation (DESIGN.md §8.3). Reads the address book only when
 * searched; locates an address only when it's picked, and caches where it was found.
 */
@Singleton
class ContactsSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val places: PlacesSearch,
) {
    val available: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contact addresses whose name matches [query], best first (at most [limit]). */
    suspend fun search(query: String, limit: Int = 5): List<ContactAddress> = withContext(Dispatchers.IO) {
        if (!available || query.trim().length < ContactMatch.MIN_QUERY) return@withContext emptyList()
        val q = query.trim()
        val out = ArrayList<Pair<Int, ContactAddress>>()
        runCatching {
            context.contentResolver.query(
                StructuredPostal.CONTENT_URI,
                arrayOf(StructuredPostal._ID, StructuredPostal.DISPLAY_NAME_PRIMARY, StructuredPostal.TYPE, StructuredPostal.LABEL, StructuredPostal.FORMATTED_ADDRESS),
                // A cheap first filter in the provider; the word-start rule is applied below.
                "${StructuredPostal.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$q%"),
                "${StructuredPostal.DISPLAY_NAME_PRIMARY} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val address = c.getString(4)?.replace('\n', ',')?.replace(Regex(""",\s*"""), ", ")?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val rank = ContactMatch.rank(name, q) ?: continue
                    val label = StructuredPostal.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                    out += rank to ContactAddress(c.getLong(0), name, label, address)
                }
            }
        }.onFailure { Log.w(TAG, "contacts query", it) }
        out.sortedWith(compareBy({ it.first }, { it.second.name.lowercase() })).take(limit).map { it.second }
    }

    /** Where [a] is: from the cache if found in the last 90 days, else by Places text search. */
    suspend fun locate(a: ContactAddress, near: Location?): Located {
        val key = ContactMatch.key(a.address)
        val dao = db.contactGeocodes()
        dao.get(key)?.takeIf { System.currentTimeMillis() - it.updatedAt < CACHE_MS }?.let {
            return Located.Found(Destination(a.title, it.lat, it.lon, it.placeId), it.formattedAddress ?: a.address)
        }
        val results = places.textSearch(a.address, near).filter { it.lat != null && it.lon != null }
        val pick = ContactMatch.choose(a.address, results) { it.subtitle }
            ?: return if (results.isEmpty()) Located.NotFound else Located.Choose(results)
        remember(a, pick)
        return Located.Found(Destination(a.title, pick.lat!!, pick.lon!!, pick.placeId), pick.subtitle ?: a.address)
    }

    /** Saves the result the user (or the postcode rule) chose for [a]. */
    suspend fun remember(a: ContactAddress, hit: SearchHit) {
        val lat = hit.lat ?: return
        val lon = hit.lon ?: return
        db.contactGeocodes().put(ContactGeocodeEntity(ContactMatch.key(a.address), hit.placeId, lat, lon, hit.subtitle, System.currentTimeMillis()))
    }

    /** Distance to [a] if it has been located before, without looking it up. */
    suspend fun knownDistanceM(a: ContactAddress, near: Location?): Int? {
        near ?: return null
        val e = db.contactGeocodes().get(ContactMatch.key(a.address)) ?: return null
        return FloatArray(1).also { Location.distanceBetween(near.latitude, near.longitude, e.lat, e.lon, it) }[0].toInt()
    }

    suspend fun clearCache() = db.contactGeocodes().clear()

    companion object {
        private const val TAG = "ContactsSource"
        private const val CACHE_MS = 90L * 24 * 3_600_000
    }
}
