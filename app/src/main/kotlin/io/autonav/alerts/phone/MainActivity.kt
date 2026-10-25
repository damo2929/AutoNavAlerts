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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import io.autonav.alerts.reports.CameraReportRepository
import io.autonav.alerts.settings.SettingsStore
import javax.inject.Inject

/**
 * The phone app's one Activity (DESIGN.md §13; every setting in docs/SETTINGS.md): a Material 3
 * `Scaffold` with four bottom tabs, POIs · Cameras · Reports · Settings, each a composable in
 * PhoneScreens.kt. There is no Setup tab: the setup steps are the first sections of Settings.
 *
 * Started by the launcher, and by the "N camera reports to check" notification with [EXTRA_TAB]
 * set to [TAB_REPORTS] (DESIGN.md §8.5). Runs on the main thread; the screens launch their own
 * coroutines for Room, network and settings writes.
 *
 * Lifecycle: an ordinary Activity, recreated on rotation; the chosen tab survives through
 * `rememberSaveable`. Everything the screens need is built by Hilt as one [PhoneDeps], so the
 * composables stay plain functions.
 *
 * Camera reports are reviewed and sent from here, on the phone, and nowhere else (AGENTS.md §3.1
 * rule 8). No Maps or Navigation SDK call is made before a key is saved (rule 5): the Settings
 * tab's guards and `NavigatorHolder` see to that (DESIGN.md §1.4).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Everything the screens need, one Hilt-built holder (see [PhoneDeps] for what each member is used for). */
    @Inject lateinit var deps: PhoneDeps

    /**
     * Builds the tab scaffold. The tab to open comes from the launching intent, else from the
     * setup state: Settings while setup is unfinished, otherwise POIs (DESIGN.md §13 intro).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // -1 means "no tab asked for" (the launcher); a notification passes the tab it wants (DESIGN.md §8.5).
        val requestedTab = intent?.getIntExtra(EXTRA_TAB, -1) ?: -1
        setContent {
            // Follows the phone's light or dark theme with the stock Material 3 schemes; no custom palette.
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                // Both survive rotation and process death: a requested tab wins at once; -1 shows POIs until the effect below decides.
                var tab by rememberSaveable { mutableIntStateOf(requestedTab.coerceAtLeast(TAB_POIS)) }
                // True once the opening tab is decided, so a rotation doesn't bounce the user back to Settings.
                var chosen by rememberSaveable { mutableStateOf(requestedTab >= 0) }
                // With no tab asked for, open Settings while setup is unfinished (no key or terms), else POIs.
                LaunchedEffect(Unit) {
                    if (!chosen) {
                        // termsAccepted() checks the app's own record first and only asks the SDK once a key is applied (DESIGN.md §1.4).
                        if (deps.settings.mapsApiKey() == null || !deps.navigator.termsAccepted()) tab = TAB_SETTINGS
                        chosen = true
                    }
                }
                Scaffold(
                    bottomBar = {
                        // The four tabs in DESIGN.md §13's order; the index is the TAB_* constant.
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
                            TAB_POIS -> PoiSetsScreen(deps)
                            TAB_CAMERAS -> CameraDbScreen(deps)
                            TAB_REPORTS -> ReportsScreen(this@MainActivity, deps)
                            else -> SettingsScreen(this@MainActivity, deps)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Intent extra: which tab to open, one of the `TAB_*` constants. Absent or -1 means "decide from the setup state". */
        const val EXTRA_TAB = "tab"
        /** Tab indexes in bottom-bar order (DESIGN.md §13). */
        const val TAB_POIS = 0
        /** The Cameras tab (PocketGPSWorld and the camera sets). */
        const val TAB_CAMERAS = 1
        /** The Reports tab; the "N camera reports to check" notification opens this one (DESIGN.md §8.5). */
        const val TAB_REPORTS = 2
        /** The Settings tab, where setup lives too. */
        const val TAB_SETTINGS = 3
        /** Label and icon per tab, in bar order; the position is the `TAB_*` constant. */
        private val TABS = listOf(
            "POIs" to R.drawable.ic_action_layers,
            "Cameras" to R.drawable.ic_action_report,
            "Reports" to R.drawable.ic_action_place,
            "Settings" to R.drawable.ic_action_more,
        )
    }
}

/**
 * Everything the phone screens need, built by Hilt and injected into [MainActivity] as one value, so each
 * composable in PhoneScreens.kt takes `deps` instead of a dozen parameters. All members are the
 * process-wide Hilt singletons shared with the car service (DESIGN.md §2).
 */
class PhoneDeps @Inject constructor(
    /** Settings, the encrypted Maps key and PGW login, and the setup state (DESIGN.md §13; docs/SETTINGS.md). */
    val settings: SettingsStore,
    /** Navigation SDK gatekeeper: the terms record and `keyChanged()`; every SDK call goes through its key guard (DESIGN.md §1.4). */
    val navigator: NavigatorHolder,
    /** POI sets and import (POIs and Cameras tabs; DESIGN.md §4, §5). */
    val pois: PoiRepository,
    /** Built-in and user icons, as bitmaps for the set cards and the picker (DESIGN.md §7.1). */
    val icons: IconRepository,
    /** PocketGPSWorld login and download (Cameras tab; DESIGN.md §6). */
    val pgw: PgwClient,
    /** Sync now / Force re-download and the automatic update schedule (DESIGN.md §6.2). */
    val scheduler: PgwScheduler,
    /** The camera-report drafts reviewed and sent on the Reports tab (DESIGN.md §8.5). */
    val reports: CameraReportRepository,
    /** The real speech output, for Test speak in the template editor (DESIGN.md §13 item 7). */
    val tts: NavTts,
    /** Whether sending reports is blocked (connected to the car or moving; DESIGN.md §8.5). */
    val driving: io.autonav.alerts.reports.DrivingState,
    /** Create a key with my Google account, and the signing SHA-1 for the guided set-up (DESIGN.md §13.1, §13.2). */
    val keyCreator: io.autonav.alerts.settings.MapsKeyCreator,
    /** The settings backup file: save, preview and restore (DESIGN.md §13.4). */
    val backup: io.autonav.alerts.settings.SettingsBackup,
    /** Contacts' addresses, for Clear contact locations (DESIGN.md §8.3). */
    val contacts: io.autonav.alerts.contacts.ContactsSource,
    /** The OpenStreetMap limits download and its state (DESIGN.md §8.4a). */
    val speedLimits: io.autonav.alerts.osm.SpeedLimitRepository,
    /** The two debug logs shown, saved and shared under Diagnostics (DESIGN.md §15.5). */
    val eventLog: io.autonav.alerts.diag.EventLog,
    /** The opt-in drive recorder: its latest CSV and recording state (DESIGN.md §15.5). */
    val driveRecorder: io.autonav.alerts.diag.DriveRecorder,
    /** Settings → Diagnostics → Engine now: the alert engine's snapshot text (DESIGN.md §15.5). */
    val diagnostics: io.autonav.alerts.diag.DiagnosticsSnapshot,
)
