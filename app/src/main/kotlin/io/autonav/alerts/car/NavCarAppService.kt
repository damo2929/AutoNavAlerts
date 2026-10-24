// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * The `CarAppService` Android Auto binds to (DESIGN.md §3, §9.1): the manifest declares it in
 * the navigation category, so the host lists the app as a navigation app and opens it through
 * here. The host creates and destroys it; every call is on the main thread. Its only jobs are to
 * say which hosts may bind (debug builds allow any, so the Desktop Head Unit and the emulator
 * work; release builds take the Car App Library's list of known Android Auto and AAOS
 * signatures) and to hand each connection a fresh [NavSession].
 */
class NavCarAppService : CarAppService() {

    /** Which hosts may connect: all in a debuggable build (the bench), the known signatures otherwise. */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Known Android Auto / AAOS host signatures shipped with the Car App Library.
            @Suppress("PrivateResource")
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    /** One [NavSession] per connection; the host destroys it when the car screen closes. */
    override fun onCreateSession(sessionInfo: SessionInfo): Session = NavSession()
}
