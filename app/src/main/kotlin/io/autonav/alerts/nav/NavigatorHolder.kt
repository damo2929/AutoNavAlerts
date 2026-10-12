// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.app.Application
import android.util.Log
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.net.PlacesClient
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Why the Navigator isn't available, shown on the car and phone screens. */
enum class NavUnavailable { NO_API_KEY, TERMS_NOT_ACCEPTED, NOT_AUTHORIZED, NETWORK, LOCATION_PERMISSION, UNKNOWN }

/**
 * Owns the Navigation SDK [Navigator] and the Places client. The Maps API key can be supplied by the
 * user at run time (phone Settings), so both are initialised lazily with the stored key.
 */
@Singleton
class NavigatorHolder @Inject constructor(
    private val app: Application,
    private val settings: SettingsStore,
) {
    private val _navigator = MutableStateFlow<Navigator?>(null)
    val navigator: StateFlow<Navigator?> = _navigator

    private val _unavailable = MutableStateFlow<NavUnavailable?>(null)
    val unavailable: StateFlow<NavUnavailable?> = _unavailable

    private var places: PlacesClient? = null
    private var keyApplied: String? = null

    suspend fun applyApiKey(): Boolean {
        val key = settings.mapsApiKey()
        if (key.isNullOrBlank()) {
            _unavailable.value = NavUnavailable.NO_API_KEY
            return false
        }
        if (key != keyApplied) {
            NavigationApi.setApiKey(key)
            if (Places.isInitialized()) Places.deinitialize()
            Places.initializeWithNewPlacesApiEnabled(app, key)
            places = Places.createClient(app)
            keyApplied = key
        }
        return true
    }

    /**
     * True once the user has accepted Google's terms: either the SDK remembers it, or the app recorded
     * the acceptance itself (so it's only ever asked once, even if the SDK's own flag is lost).
     */
    suspend fun termsAccepted(): Boolean = settings.navTermsAcceptedAt() != null || sdkTermsAccepted()

    /**
     * Whether the SDK itself remembers the acceptance. False while there's no key: any Navigation SDK
     * call made before [NavigationApi.setApiKey] crashes the app looking for a manifest key, and there
     * is deliberately none (DESIGN.md §1.4).
     */
    suspend fun sdkTermsAccepted(): Boolean = applyApiKey() && NavigationApi.areTermsAccepted(app)

    /** Records an acceptance from the terms dialog. */
    suspend fun recordTermsAccepted() {
        if (settings.navTermsAcceptedAt() == null) settings.setNavTermsAccepted(System.currentTimeMillis())
    }

    /** Gets (or returns the existing) Navigator. Terms must already be accepted on the phone. */
    suspend fun ensureNavigator(): Navigator? {
        _navigator.value?.let { return it }
        if (!applyApiKey()) return null
        if (!termsAccepted()) {
            _unavailable.value = NavUnavailable.TERMS_NOT_ACCEPTED
            return null
        }
        // The SDK remembers the acceptance → normal start. If its flag was lost but we recorded the
        // user's acceptance, start without showing the terms again.
        val sdkRemembers = sdkTermsAccepted()
        return suspendCancellableCoroutine { cont ->
            val listener = object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    _navigator.value = navigator
                    _unavailable.value = null
                    if (cont.isActive) cont.resume(navigator)
                }

                override fun onError(errorCode: Int) {
                    Log.w(TAG, "Navigator error $errorCode")
                    _unavailable.value = when (errorCode) {
                        NavigationApi.ErrorCode.NOT_AUTHORIZED -> NavUnavailable.NOT_AUTHORIZED
                        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> NavUnavailable.TERMS_NOT_ACCEPTED
                        NavigationApi.ErrorCode.NETWORK_ERROR -> NavUnavailable.NETWORK
                        NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> NavUnavailable.LOCATION_PERMISSION
                        else -> NavUnavailable.UNKNOWN
                    }
                    if (cont.isActive) cont.resume(null)
                }
            }
            if (sdkRemembers) NavigationApi.getNavigator(app, listener)
            else NavigationApi.getNavigatorNoToS(app, listener)
        }
    }

    fun roadSnappedProvider(): RoadSnappedLocationProvider? =
        if (_navigator.value != null) NavigationApi.getRoadSnappedLocationProvider(app) else null

    suspend fun placesClient(): PlacesClient? {
        if (!applyApiKey()) return null
        return places
    }

    /** Call after the user changes the key so the next use re-initialises. */
    fun keyChanged() {
        keyApplied = null
        _navigator.value?.cleanup()
        _navigator.value = null
    }

    companion object { private const val TAG = "NavigatorHolder" }
}
