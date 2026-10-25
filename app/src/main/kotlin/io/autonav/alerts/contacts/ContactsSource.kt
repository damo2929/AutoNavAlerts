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

/** One postal address of one contact (§8.3): the `StructuredPostal` row id, the contact's display name, the type label ("Home", "Work", a custom label) and the formatted address. */
data class ContactAddress(val dataId: Long, val name: String, val label: String, val address: String) {
    /** "Mum (Home)": the title used for the route and the recent. */
    val title: String get() = "$name ($label)"
}

/** Where a picked contact address is (§8.3 "Choosing the result"): a destination, a choice between several results ("Which address?"), or not found. */
sealed interface Located {
    data class Found(val destination: Destination, val address: String) : Located
    data class Choose(val options: List<SearchHit>) : Located
    data object NotFound : Located
}

/**
 * Contacts' postal addresses for navigation (DESIGN.md §8.3). Reads the address book only when searched;
 * locates an address only when it's picked, and caches where it was found (`contact_geocode`, 90 days).
 * A Hilt singleton; the content-resolver reads run on Dispatchers.IO. Called by the car's `SearchScreen`
 * (matches above the Places results) and `ContactsScreen` (the full list), and by the phone's Permissions
 * card (clear cache). Read-only: the app never changes contacts.
 * Privacy (§8.3): contact data stays on the device; only the one address the user picks goes to Google
 * (Places Text Search, billed to their own key), and the cache stores a hash of it, not the text.
 */
@Singleton
class ContactsSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val places: PlacesSearch,
) {
    /** True once the optional READ_CONTACTS permission is granted (§8.3); without it every method returns nothing and the car hides the contact rows. */
    val available: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Contact addresses whose name matches [query], best first (at most [limit]; the car passes 3 above the Places
     * results and 6 for the contacts-only search, §8.3 "Matching a name"). Case-insensitive, from 2 characters
     * (`ContactMatch.MIN_QUERY`): the name starts with the query, or any word in it does; names that start with
     * it rank first, then by name. Local, so it runs on every keystroke with no delay.
     */
    suspend fun search(query: String, limit: Int = 5): List<ContactAddress> = withContext(Dispatchers.IO) {
        if (!available || query.trim().length < ContactMatch.MIN_QUERY) return@withContext emptyList()
        val q = query.trim()
        val out = ArrayList<Pair<Int, ContactAddress>>()
        // A cheap first filter in the provider; the word-start rule is applied by the rank.
        queryPostal("${StructuredPostal.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$q%")) { a ->
            ContactMatch.rank(a.name, q)?.let { out += it to a }
        }
        out.sortedWith(compareBy({ it.first }, { it.second.name.lowercase() })).take(limit).map { it.second }
    }

    /**
     * One read of the address book's `StructuredPostal` rows matching [selection], by name: each row with a name
     * and a non-blank formatted address goes to [each]. Multi-line addresses become one line; blank ones are
     * skipped (§8.3 "Addresses with a blank formatted address are skipped"). A provider failure is logged and
     * yields nothing.
     */
    private inline fun queryPostal(selection: String?, args: Array<String>?, each: (ContactAddress) -> Unit) {
        runCatching {
            context.contentResolver.query(
                StructuredPostal.CONTENT_URI,
                arrayOf(StructuredPostal._ID, StructuredPostal.DISPLAY_NAME_PRIMARY, StructuredPostal.TYPE, StructuredPostal.LABEL, StructuredPostal.FORMATTED_ADDRESS),
                selection, args,
                "${StructuredPostal.DISPLAY_NAME_PRIMARY} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val address = c.getString(4)?.replace('\n', ',')?.replace(Regex(""",\s*"""), ", ")?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val label = StructuredPostal.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                    each(ContactAddress(c.getLong(0), name, label, address))
                }
            }
        }.onFailure { Log.w(TAG, "contacts query", it) }
    }

    /**
     * Every contact address, for `ContactsScreen` (DESIGN.md §8.3): the ones located before
     * first, most recently located first (from the `contact_geocode` cache, by its `updatedAt`),
     * then the rest A–Z by name; at most [limit]. Reads the address book once per call.
     */
    suspend fun all(limit: Int): List<ContactAddress> = withContext(Dispatchers.IO) {
        if (!available || limit <= 0) return@withContext emptyList()
        val out = ArrayList<ContactAddress>()
        queryPostal(null, null) { out += it }
        // When each address was last located: a plain query on the cache table, keyed like `locate`.
        val usedAt = HashMap<String, Long>()
        runCatching {
            db.query("SELECT `key`, updatedAt FROM contact_geocode", null).use { c ->
                while (c.moveToNext()) usedAt[c.getString(0)] = c.getLong(1)
            }
        }.onFailure { Log.w(TAG, "contact_geocode query", it) }
        out.sortedWith(
            compareByDescending<ContactAddress> { usedAt[ContactMatch.key(it.address)] ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() }.thenBy { it.label },
        ).take(limit)
    }

    /**
     * Where [a] is (§8.3 "Geocoding"): from the cache if found in the last 90 days, else by Places Text Search on the
     * formatted address with a 50 km bias around [near]. `ContactMatch.choose` takes the result whose address has
     * the same UK postcode, or the only result; several results without a postcode match are handed back to
     * choose from, and none gives [Located.NotFound]. A chosen result is cached.
     */
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

    /** Saves the result the user (from "Which address?") or the postcode rule chose for [a] in the cache (§8.3 "Cache"). */
    suspend fun remember(a: ContactAddress, hit: SearchHit) {
        val lat = hit.lat ?: return
        val lon = hit.lon ?: return
        db.contactGeocodes().put(ContactGeocodeEntity(ContactMatch.key(a.address), hit.placeId, lat, lon, hit.subtitle, System.currentTimeMillis()))
    }

    /** Distance in metres to [a] if it has been located before (any age), without looking it up; for the rows' distance text (§8.3). */
    suspend fun knownDistanceM(a: ContactAddress, near: Location?): Int? {
        near ?: return null
        val e = db.contactGeocodes().get(ContactMatch.key(a.address)) ?: return null
        return FloatArray(1).also { Location.distanceBetween(near.latitude, near.longitude, e.lat, e.lon, it) }[0].toInt()
    }

    /** Settings → Permissions → Clear contact locations (§8.3). */
    suspend fun clearCache() = db.contactGeocodes().clear()

    companion object {
        private const val TAG = "ContactsSource"
        /** A cached location older than 90 days is looked up again (§8.3 "Cache"). */
        private const val CACHE_MS = 90L * 24 * 3_600_000
    }
}
