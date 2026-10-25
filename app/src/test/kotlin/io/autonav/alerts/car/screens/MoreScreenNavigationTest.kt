// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.testing.CarScreenRig
import io.autonav.alerts.testing.list
import io.autonav.alerts.testing.rows
import io.autonav.alerts.testing.tap
import io.autonav.alerts.testing.titleText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * More's rows lead where DESIGN.md §8's screen graph says (More → Recents, POI layers, Route options, Alerts):
 * each tap pushes exactly one screen of the right kind, so no row on the car screen is dead (hard rule 2).
 * Robolectric main looper.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class MoreScreenNavigationTest {
    private fun pushedBy(title: String): List<String> {
        val rig = CarScreenRig()
        val s = MoreScreen(rig.car, rig.host)
        rig.start(s)
        val before = rig.screens.screensPushed.size
        s.onGetTemplate().list.rows.single { it.titleText == title }.tap()
        rig.idle()
        return rig.screens.screensPushed.drop(before).map { it.javaClass.simpleName }
    }

    @Test fun recentsRowOpensRecents() = assertEquals(listOf("RecentsScreen"), pushedBy("Recents"))

    @Test fun poiLayersRowOpensPoiLayers() = assertEquals(listOf("PoiLayersScreen"), pushedBy("POI layers"))

    @Test fun routeOptionsRowOpensRouteOptions() = assertEquals(listOf("RouteOptionsScreen"), pushedBy("Route options"))

    @Test fun alertsRowOpensTheSpeechRecords() = assertEquals(listOf("AlertsRecordsScreen"), pushedBy("Alerts"))
}
