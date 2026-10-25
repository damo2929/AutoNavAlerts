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

/**
 * Why the Navigator isn't available, shown on the car and phone screens.
 *
 * NOT_AUTHORIZED is the SDK's word for a key that isn't enabled for the Navigation SDK or is restricted
 * to another app; the phone's Settings explains it (§13.1).
 */
enum class NavUnavailable { NO_API_KEY, TERMS_NOT_ACCEPTED, NOT_AUTHORIZED, NETWORK, LOCATION_PERMISSION, UNKNOWN }

/**
 * Owns the Navigation SDK [Navigator] and the Places client. The Maps API key can be supplied by the
 * user at run time (phone Settings), so both are initialised lazily with the stored key.
 *
 * Rule upheld here (AGENTS.md §3.1 rule 5; DESIGN.md §1.4): there is no manifest key and no
 * fallback key. Every SDK call goes through [applyApiKey] first, because a Navigation SDK call
 * made before `NavigationApi.setApiKey` looks for a manifest key and crashes the app. Terms are
 * accepted once and remembered by the app itself (§6.2, §13), so the dialog never comes back
 * even if the SDK's own flag is lost.
 *
 * Lifecycle: a Hilt singleton shared by the car session and the phone app (§2). The session
 * (and the phone's key test) call [ensureNavigator]; `GuidanceController` reads [navigator] and
 * registers for [onKeyChanged]; the phone's Settings call [keyChanged] after a new key is saved.
 * The suspend functions run on whatever dispatcher the caller uses; the SDK's callbacks come on
 * the main thread, and the StateFlows are safe to read from any.
 */
@Singleton
class NavigatorHolder @Inject constructor(
    private val app: Application,
    private val settings: SettingsStore,
) {
    /** The live Navigator, null until [ensureNavigator] succeeds and again after [keyChanged]. */
    private val _navigator = MutableStateFlow<Navigator?>(null)
    val navigator: StateFlow<Navigator?> = _navigator

    /** Why there is no Navigator, for the car's home screen and the phone's Settings; null when fine. */
    private val _unavailable = MutableStateFlow<NavUnavailable?>(null)
    val unavailable: StateFlow<NavUnavailable?> = _unavailable

    private var places: PlacesClient? = null
    /** The key the SDK and Places were last initialised with, so a saved-again key isn't re-applied. */
    private var keyApplied: String? = null

    /**
     * Gives the SDK and Places the stored key, once per key. False (and [unavailable] = NO_API_KEY)
     * when none is stored: the caller must then make no SDK call. Reads DataStore, so suspend.
     */
    suspend fun applyApiKey(): Boolean {
        val key = settings.mapsApiKey()
        if (key.isNullOrBlank()) {
            _unavailable.value = NavUnavailable.NO_API_KEY
            return false
        }
        if (key != keyApplied) {
            NavigationApi.setApiKey(key)
            // A new key needs a new Places client: Places caches the key it was initialised with.
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

    /**
     * The SDK's road-snapped feed (§10.1): the map's car marker and the follow watchdog use it, and
     * the debug simulator feeds the alert engine through it. Null until a Navigator exists, because
     * asking the SDK for it before the key is applied is the crash of §1.4.
     */
    fun roadSnappedProvider(): RoadSnappedLocationProvider? =
        if (_navigator.value != null) NavigationApi.getRoadSnappedLocationProvider(app) else null

    /** The Places client for search and autocomplete (§8), or null with no key. */
    suspend fun placesClient(): PlacesClient? = if (applyApiKey()) places else null

    /** Run by [keyChanged] before the old Navigator is cleaned up (the one registrant is `GuidanceController`). */
    @Volatile private var keyListener: (() -> Unit)? = null

    /**
     * Runs [l] from [keyChanged], before the old Navigator is cleaned up, so guidance can stop on
     * a live navigator (`GuidanceController` registers `stopGuidance(KEY_CHANGED)`, DESIGN.md §9.3).
     */
    fun onKeyChanged(l: () -> Unit) {
        keyListener = l
    }

    /** Call after the user changes the key so the next use re-initialises. */
    fun keyChanged() {
        keyApplied = null
        // The listener first: it needs the navigator that is about to go.
        keyListener?.let { l -> runCatching(l).onFailure { Log.w(TAG, "keyChanged listener", it) } }
        _navigator.value?.cleanup()
        _navigator.value = null
    }

    companion object { private const val TAG = "NavigatorHolder" }
}
