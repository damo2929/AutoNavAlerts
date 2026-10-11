// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dagger.hilt.android.AndroidEntryPoint
import io.autonav.alerts.R
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.nav.NavigatorHolder
import io.autonav.alerts.pgw.PgwClient
import io.autonav.alerts.pgw.PgwScheduler
import io.autonav.alerts.alerts.NavTts
import io.autonav.alerts.places.PlacesSearch
import io.autonav.alerts.reports.CameraReportRepository
import io.autonav.alerts.settings.SettingsStore
import javax.inject.Inject

/** Phone UI host (DESIGN.md §13): setup, POI sets, camera database, reports and settings. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var navigator: NavigatorHolder
    @Inject lateinit var pois: PoiRepository
    @Inject lateinit var icons: IconRepository
    @Inject lateinit var pgw: PgwClient
    @Inject lateinit var scheduler: PgwScheduler
    @Inject lateinit var reports: CameraReportRepository
    @Inject lateinit var tts: NavTts
    @Inject lateinit var places: PlacesSearch
    @Inject lateinit var driving: io.autonav.alerts.reports.DrivingState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deps = PhoneDeps(settings, navigator, pois, icons, pgw, scheduler, reports, tts, places, driving)
        val startTab = intent?.getIntExtra(EXTRA_TAB, 0) ?: 0
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var tab by rememberSaveable { mutableIntStateOf(startTab) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(painterResource(icon), contentDescription = null) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (tab) {
                            0 -> SetupScreen(this@MainActivity, deps)
                            1 -> PoiSetsScreen(deps)
                            2 -> CameraDbScreen(deps)
                            3 -> ReportsScreen(this@MainActivity, deps)
                            else -> SettingsScreen(deps)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_REPORTS = 3
        private val TABS = listOf(
            "Setup" to R.drawable.ic_action_navigate,
            "POIs" to R.drawable.ic_action_layers,
            "Cameras" to R.drawable.ic_action_report,
            "Reports" to R.drawable.ic_action_place,
            "Settings" to R.drawable.ic_action_more,
        )
    }
}

class PhoneDeps(
    val settings: SettingsStore,
    val navigator: NavigatorHolder,
    val pois: PoiRepository,
    val icons: IconRepository,
    val pgw: PgwClient,
    val scheduler: PgwScheduler,
    val reports: CameraReportRepository,
    val tts: NavTts,
    val places: PlacesSearch,
    val driving: io.autonav.alerts.reports.DrivingState,
)
