// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.nav

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The car follows the phone's set-up live (DESIGN.md §1.4, owner 2026-10-25): saving or removing the key and a
 * newly recorded terms acceptance each bump `setupChanges`, which the car session answers with `ensureNavigator()`;
 * an acceptance already on record bumps nothing. With no key, `ensureNavigator()` reports NO_API_KEY and makes no
 * SDK call (AGENTS.md §3.1 rule 5). Robolectric, main thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class NavigatorHolderSetupTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = SettingsStore(app)
    private val holder = NavigatorHolder(app, settings)

    @Test
    fun keyChanged_bumpsSetupChanges() {
        val before = holder.setupChanges.value
        holder.keyChanged()
        holder.keyChanged()
        assertEquals(before + 2, holder.setupChanges.value)
    }

    @Test
    fun termsAccepted_bumpsOnceOnlyWhenNewlyRecorded() = runBlocking {
        val before = holder.setupChanges.value
        holder.recordTermsAccepted()
        assertEquals(before + 1, holder.setupChanges.value)
        // Already on record: the phone's Settings call this on every refresh, so it must not bump again.
        holder.recordTermsAccepted()
        assertEquals(before + 1, holder.setupChanges.value)
    }

    @Test
    fun noKey_ensureNavigatorReportsNoApiKeyWithoutSdkCall() = runBlocking {
        settings.setMapsApiKey(null)
        holder.keyChanged()
        assertNull(holder.ensureNavigator())
        assertEquals(NavUnavailable.NO_API_KEY, holder.unavailable.value)
    }
}
