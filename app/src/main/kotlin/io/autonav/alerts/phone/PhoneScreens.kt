// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.phone

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.navigation.NavigationApi
import io.autonav.alerts.core.alerts.AlertTemplateRenderer
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.TemplateValues
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.pgw.PgwConfig
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.core.poi.importers.CsvImporter
import io.autonav.alerts.core.poi.importers.CsvProfile
import io.autonav.alerts.data.CameraReportEntity
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.PoiSourceType
import io.autonav.alerts.data.ReportKind
import io.autonav.alerts.data.ReportStatus
import io.autonav.alerts.reports.Transcriber
import io.autonav.alerts.data.StageCols
import io.autonav.alerts.diag.DiagFiles
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.pgw.ReportSubmitWorker
import io.autonav.alerts.reports.CameraReportRepository
import io.autonav.alerts.settings.DEFAULT_ALERT_LEAD_SECONDS
import io.autonav.alerts.settings.MAX_ALERT_LEAD_SECONDS
import io.autonav.alerts.settings.MIN_ALERT_LEAD_SECONDS
import io.autonav.alerts.settings.MapsKeyCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import io.autonav.alerts.core.pgw.PgwDownload
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.data.LimitSource
import io.autonav.alerts.settings.AppSettings
import io.autonav.alerts.settings.PgwState
import io.autonav.alerts.settings.SettingsBackup
import io.autonav.alerts.settings.SpeedSource

// The phone app's screens (DESIGN.md §13; every setting in docs/SETTINGS.md), hosted one per tab
// by MainActivity, plus the cards and dialogs they use. Camera reports are reviewed and sent from
// here only, never in the car (AGENTS.md §3.1 rule 8). The Maps key is the user's own, saved by
// the setup sections; there is no built-in or fallback key (rule 5; DESIGN.md §1.4).
//
// The composables in the order they appear on the phone (tab, then top to bottom):
//
//   POIs tab       PoiSetsScreen → SetCard (swipe: DeleteSetDialog) → ImportDialog (ColField)
//                  → SetEditor (StageEditor, TemplateField; validate, withSliderSeconds)
//                                                      DESIGN.md §13 items 3 to 7; docs/SETTINGS.md §3
//   Cameras tab    CameraDbScreen: subscription, download format, database, CameraSets
//                  (CameraGroup, cameraGroups, speedOrder) → SetCard / SetEditor, manual import
//                                                      DESIGN.md §13 item 8; docs/SETTINGS.md §4
//   Reports tab    ReportsScreen → ReportCard (compass)   DESIGN.md §8.5, §13 item 2; docs/SETTINGS.md §5
//   Settings tab   SettingsScreen:
//                    SetupSections (CreateKeyWithGoogle → GuidedKeySetup; PermState, PermLine,
//                      openAppSettings)                  DESIGN.md §13 item 1, §13.1, §13.2; docs/SETTINGS.md §2
//                    Car screen (units, speed panel, satellite)      docs/SETTINGS.md §1.1, §1.4
//                    Alerts (OverLimitSlider, AlertLeadSlider)       docs/SETTINGS.md §1.2
//                    Speed and location source                       docs/SETTINGS.md §1.3
//                    Routing                                         docs/SETTINGS.md §1.4b
//                    Speed limits (OsmLimitsSection)                 docs/SETTINGS.md §1.4a
//                    Bench testing (debug builds only)               docs/SETTINGS.md §1.6
//                    Backup (save, restore dialog)                   docs/SETTINGS.md §1.7; DESIGN.md §13.4
//                    Diagnostics (DiagnosticsSection → DiagLogCard, MonoLines)
//                                                                    docs/SETTINGS.md §1.8; DESIGN.md §15.5
//                    Connecting to a car (CarConnectionSection)      docs/SETTINGS.md §1.8a
//                    About                                           docs/SETTINGS.md §1.9
//   Shared         SetListHost (the editor swap and delete confirm of the POIs and Cameras tabs),
//                  sizeText, pctLabel, halfSecondsLabel, leadDistanceText, FIRST_ALERT_FLOOR_M;
//                  the cards, rows, text styles, chips, folds and dialogs are in PhoneUi.kt
//
// Every screen takes the one [PhoneDeps] from MainActivity and runs on the main thread; Room,
// network and settings writes go through `rememberCoroutineScope()` and the repositories'
// own dispatchers.

// --- Setup -------------------------------------------------------------------------------------

/**
 * The setup steps (Maps key, permissions, navigation terms, Android Auto), shown at the top of the
 * Settings tab (DESIGN.md §13 item 1; docs/SETTINGS.md §2). Emits its sections into the caller's
 * scrolling column. Once setup is complete (key saved, foreground permissions and background
 * location granted, terms accepted) the four sections fold into one "Setup" row with Show.
 *
 * Upholds AGENTS.md §3.1 rule 5: the key is the user's own, pasted or created in their account,
 * with no fallback; and the only Navigation SDK call here (the terms dialog) is disabled until a
 * key is saved, so nothing reaches the SDK before `applyApiKey()` (DESIGN.md §1.4).
 *
 * State: `refresh` re-reads the key, terms and permission state after each result and whenever
 * the Activity resumes (the user may have changed things in Android settings).
 */
@Composable
private fun SetupSections(activity: Activity, deps: PhoneDeps) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var keyText by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var termsOk by remember { mutableStateOf(false) }
    var termsAcceptedAt by remember { mutableStateOf<Long?>(null) }
    var termsMessage by remember { mutableStateOf<String?>(null) }
    var askedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        hasKey = deps.settings.mapsApiKey() != null
        // An acceptance the SDK already remembers is copied into our own record, so it sticks.
        if (deps.navigator.sdkTermsAccepted()) deps.navigator.recordTermsAccepted()
        termsOk = deps.navigator.termsAccepted()
        termsAcceptedAt = deps.settings.navTermsAcceptedAt()
    }
    // Refresh statuses when returning from the permission dialog or system Settings.
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    // Re-read on every result and whenever the app comes back to the front (e.g. from Settings).
    val st = remember(refresh) { PermState.read(ctx) }
    // Setup done: the four sections fold into one row, so the settings start at the top (DESIGN.md §13 item 1).
    val complete = hasKey && termsOk && st.missingForeground().isEmpty() && st.background
    var showSetup by remember { mutableStateOf(false) }
    if (complete) Section("Setup") {
        ShowHideRow(showSetup, { showSetup = !showSetup }) { Ok("✓ Maps key saved, permissions allowed, navigation terms accepted.", Modifier.weight(1f)) }
    }
    if (complete && !showSetup) return

    // Step 0 (DESIGN.md §13 item 1): the user's own key, or nothing. No built-in or fallback key exists (AGENTS.md §3.1 rule 5; DESIGN.md §1.4).
    Section("Google Maps API key") {
        if (hasKey) Ok("✓ Your key is saved.")
        else Err("No key yet: the map, search and navigation won't work until you add one.")
        CreateKeyWithGoogle(activity, deps, onCreated = { refresh++ })
        HorizontalDivider()
        Help("Or paste a key you already have. It needs the Navigation SDK and Places API (New) enabled.")
        OutlinedTextField(keyText, { keyText = it }, label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Save stores the key Keystore-encrypted; keyChanged() makes the SDK and Places re-apply it on next use (DESIGN.md §1.4).
            Btn("Save key", scope, enabled = keyText.isNotBlank()) { deps.saveKey(keyText); keyText = ""; refresh++ }
            // Remove leaves the app with no key at all: map, search and navigation stop until one is added again (rule 5).
            if (hasKey) Btn("Remove", scope, outlined = true) { deps.saveKey(null); refresh++ }
        }
    }

    // Steps 1 to 3 (DESIGN.md §13 item 1; docs/SETTINGS.md §2 "Permissions"): foreground grants in one request, background location after.
    Section("Permissions") {
        PERMS.forEach { PermLine(it.label, st.granted(it)) }
        val missing = st.missingForeground()
        if (missing.isNotEmpty() || !st.background) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Background location is asked for on its own, after the foreground grant: Android 10+ refuses it in the same request.
            if (missing.isNotEmpty()) Button(onClick = { askedOnce = true; permLauncher.launch(missing.toTypedArray()) }) { Text("Allow") }
            else Button(onClick = {
                askedOnce = true
                if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }) { Text("Allow all the time") }
            if (askedOnce) OutlinedButton(onClick = { openAppSettings(ctx) }) { Text("Open app settings") }
        } else Ok("✓ All permissions allowed.")
        // Contacts are optional, so they have their own button rather than being part of Allow (DESIGN.md §8.3).
        if (!st.contacts) OutlinedButton(onClick = { permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }) { Text("Allow contacts") }
        else TextButton(onClick = {
            scope.launch { deps.contacts.clearCache(); Toast.makeText(ctx, "Contact locations cleared", Toast.LENGTH_SHORT).show() }
        }) { Text("Clear contact locations") }
        if (askedOnce && (missing.isNotEmpty() || !st.background)) {
            Help("If no prompt appears, Android has stopped asking. Use \"Open app settings\" → Permissions to allow them there.")
        }
        Help("\"All the time\" keeps camera alerts working while another app (like music) fills the car screen.")
    }

    // Step 4 (DESIGN.md §13 item 1). The dialog is a Navigation SDK call, so the button stays disabled until a key is saved: no SDK call before applyApiKey() (DESIGN.md §1.4; AGENTS.md §3.1 rule 5).
    Section("Navigation terms") {
        if (termsOk) {
            Ok("✓ Accepted" + (termsAcceptedAt?.let { " on " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "") +
                ". You won't be asked again.")
        } else {
            Text("Google's navigation terms must be accepted on the phone before using the car screen.")
            termsMessage?.let { Err(it) }
            if (!hasKey) Help("Add your Google Maps API key above first.")
            Button(enabled = hasKey, onClick = {
                termsMessage = null
                runCatching {
                    NavigationApi.showTermsAndConditionsDialog(activity, "AutoNavAlerts") { accepted ->
                        if (accepted) {
                            // Record it ourselves too, so it's a one-time step.
                            scope.launch { deps.navigator.recordTermsAccepted(); refresh++ }
                            termsOk = true
                        } else termsMessage = "You declined the terms. Navigation can't start until they're accepted."
                    }
                }.onFailure { termsMessage = "Couldn't open the terms: ${it.message}" }
            }) { Text("Review and accept") }
        }
    }

    // Step 5 (DESIGN.md §13 item 1): developer mode and Unknown sources are only for sideloaded developer builds; a Play install needs neither.
    Section("Android Auto") {
        Text("Installed from Google Play? There is nothing to turn on: pick AutoNavAlerts as your navigation app on the car screen. " +
            "A sideloaded developer build also needs Android Auto's developer mode (tap the version number repeatedly) " +
            "and \"Unknown sources\" in its developer settings.")
        OpenAndroidAutoButton()
    }
}

/** The steps behind Settings → Connecting to a car (DESIGN.md §13 item 12). */
@Composable
private fun CarConnectionSection() {
    @Composable
    fun Steps(title: String, steps: List<String>) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
    }
    // DESIGN.md §13 item 12; docs/SETTINGS.md §1.8a. Plain numbered steps: the one card allowed more than one line of help, because it's a manual, not a control.
    Steps("On a car (wired or wireless)", listOf(
        "Installed from Google Play (the internal-testing link): nothing to turn on in Android Auto.",
        "Sideloaded developer build only: in Android Auto settings tap Version about 10 times for developer mode, then ⋮ → Developer settings → tick Unknown sources.",
        "Android Auto → Customise Launcher → make sure AutoNavAlerts is ticked (a sideloaded build is not kept in this list; that doesn't stop it appearing on the car).",
        "Open AutoNavAlerts on the phone once: Maps key saved, permissions allowed, navigation terms accepted.",
        "Connect to the car: a data cable for a wired car, or Bluetooth and Wi-Fi on for wireless. Open the car's app grid and pick AutoNavAlerts.",
        "Not there? Android Auto only picks up a newly installed or updated app when it next connects: disconnect and reconnect once.",
        "Still missing: Android settings → Apps → Android Auto → Force stop, then reconnect.",
        "Still missing: connect the phone once to a head unit on a computer (below), then reconnect the car.",
    ))
    Steps("Debugging on a head unit (computer)", listOf(
        "Install the Android SDK with \"Android Auto Desktop Head Unit\" (extras/google/auto).",
        "Android Auto ⋮ → Start head unit server (again after every phone restart).",
        "Turn on USB debugging and plug the phone into the computer with a data cable.",
        "On the computer: adb forward tcp:5277 tcp:5277",
        "Then run desktop-head-unit from extras/google/auto. The car screen opens in a window.",
        "The app's logs are in Diagnostics above (Share or Save).",
        "When done: Android Auto ⋮ → Stop head unit server, before driving.",
    ))
    OpenAndroidAutoButton()
}

/**
 * "Create a key with my Google account" (DESIGN.md §13.1): asks for the cloud-platform scope with a
 * Google account on the phone, then [MapsKeyCreator] makes the project, links billing, turns the APIs
 * on and creates a restricted key. The access token is only kept in memory while this screen is open.
 */
@Composable
private fun CreateKeyWithGoogle(activity: Activity, deps: PhoneDeps, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val auth = remember { Identity.getAuthorizationClient(activity) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var stop by remember { mutableStateOf<MapsKeyCreator.Stop?>(null) }
    var token by remember { mutableStateOf<String?>(null) }

    // Runs DESIGN.md §13.1 steps 1 to 5 with the token; [billing] is the account the user picked when they had several. Each step is safe to repeat, so Try again carries on.
    fun createWith(accessToken: String, billing: String? = null) {
        token = accessToken
        busy = true; stop = null; done = null
        scope.launch {
            try {
                val c = deps.keyCreator.create(accessToken, billing) { progress = it }
                deps.saveKey(c.key)
                done = "✓ Key created in your Google Cloud project ${c.projectId} and saved."
                onCreated()
            } catch (e: MapsKeyCreator.Stop) {
                stop = e
            } catch (e: Exception) {
                stop = MapsKeyCreator.Stop.Refused("Couldn't create the key: ${e.message ?: e.javaClass.simpleName}", null)
            } finally {
                busy = false; progress = null
            }
        }
    }

    // Maps a Google Identity failure to the message and buttons DESIGN.md §13.1 "Errors" asks for.
    fun signInProblem(e: Exception): MapsKeyCreator.Stop {
        val code = (e as? ApiException)?.statusCode
        // An unregistered app comes back as DEVELOPER_ERROR, or as INTERNAL_ERROR with UNREGISTERED_ON_API_CONSOLE.
        val unregistered = code == CommonStatusCodes.DEVELOPER_ERROR || e.message.orEmpty().contains("UNREGISTERED", ignoreCase = true)
        return when {
            unregistered -> MapsKeyCreator.Stop.NotSetUp()
            code == CommonStatusCodes.CANCELED -> MapsKeyCreator.Stop.Refused("Google sign-in was cancelled.", null)
            else -> MapsKeyCreator.Stop.Refused("Google sign-in failed${code?.let { " (code $it)" } ?: ""}: ${e.message.orEmpty()}", null)
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { auth.getAuthorizationResultFromIntent(result.data) }
            .onSuccess { r ->
                val t = r.accessToken
                if (t != null) createWith(t) else stop = MapsKeyCreator.Stop.Refused("Google didn't return access.", null)
            }
            .onFailure { stop = signInProblem(it as? Exception ?: Exception(it)) }
    }

    fun signIn() {
        stop = null
        // The scope on its own (DESIGN.md §13.1): with the default "include granted scopes" Google merged
        // this app's older grants into the request and refused it ("Incremental auth is not allowed").
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(MapsKeyCreator.SCOPE)))
            .setOptOutIncludingGrantedScopes(true)
            .build()
        auth.authorize(request)
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                else r.accessToken?.let { createWith(it) }
            }
            .addOnFailureListener { stop = signInProblem(it) }
    }

    Help("Let the app make your own key in your own Google Cloud account, using a Google account on this phone.")
    // An unregistered app disables the button for the rest of the visit: pressing again only showed the account picker and failed the same way (DESIGN.md §13.1 Errors).
    Button(enabled = !busy && stop !is MapsKeyCreator.Stop.NotSetUp, onClick = { confirm = true }) {
        Text(if (busy) "Creating key…" else "Create a key with my Google account")
    }
    progress?.let { Help(it) }
    done?.let { Ok(it) }
    stop?.let { s ->
        Err(s.message.orEmpty())
        when (s) {
            is MapsKeyCreator.Stop.ChooseBilling -> s.accounts.forEach { a ->
                OutlinedButton(onClick = { token?.let { createWith(it, a.name) } ?: signIn() }) { Text(a.displayName) }
            }
            is MapsKeyCreator.Stop.NoBilling -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { activity.openUrl(MapsKeyCreator.BILLING_CREATE_URL) }) { Text("Add billing account") }
                Button(onClick = { signIn() }) { Text("Try again") }
            }
            is MapsKeyCreator.Stop.NotSetUp -> Unit // the guided steps below open instead
            is MapsKeyCreator.Stop.Refused -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.consoleUrl?.let { url ->
                    OutlinedButton(onClick = { activity.openUrl(url) }) { Text("Open in Google Cloud") }
                }
                Button(onClick = { signIn() }) { Text("Try again") }
            }
        }
    }

    // The guided set-up (DESIGN.md §13.2) opens by itself when sign-in isn't registered; otherwise it's collapsed under a toggle.
    var guided by remember { mutableStateOf(false) }
    LaunchedEffect(stop) { if (stop is MapsKeyCreator.Stop.NotSetUp) guided = true }
    TextButton(onClick = { guided = !guided }) {
        Text((if (guided) "▲ " else "▼ ") + "Set it up yourself in Google Cloud (step by step)")
    }
    if (guided) GuidedKeySetup(activity, deps, onSaved = { done = "✓ Key saved."; stop = null; onCreated() })

    // Says what will be made in the user's Google Cloud, and that Google may charge, before anything starts (DESIGN.md §13.1).
    if (confirm) ConfirmDialog("Create your Maps key?", "Continue", onConfirm = { confirm = false; signIn() }, onDismiss = { confirm = false }) {
        Text("After you choose a Google account, the app will, in that account's Google Cloud:\n\n" +
            "• create a project called \"AutoNavAlerts\" (or reuse it)\n" +
            "• link it to your billing account\n" +
            "• turn on the Navigation SDK, Maps SDK for Android and Places API (New)\n" +
            "• create a key that only this app can use\n\n" +
            "Google Maps needs a billing account, and Google may charge for use beyond its free allowance. " +
            "Only the key is kept on this phone, never your Google sign-in.")
    }
}

/**
 * One line of the Permissions card (docs/SETTINGS.md §2): its [label], the [permission] whose grant it shows, the first
 * Android that has it ([minSdk]; older ones count it as granted) and what Allow [asks] for (fine location brings coarse).
 */
internal class Perm(val label: String, val permission: String, val minSdk: Int = 1, val asks: Array<String> = arrayOf(permission))

private val BACKGROUND = Perm("Location all the time (recommended)", Manifest.permission.ACCESS_BACKGROUND_LOCATION, minSdk = 29)
private val CONTACTS = Perm("Contacts (optional: drive to a contact's address)", Manifest.permission.READ_CONTACTS)

/** The card's lines in order. Allow asks for all but background location (asked on its own after) and contacts (its own button, DESIGN.md §8.3). Internal, like the pure helpers below, for `PhoneHelpersTest`. */
internal val PERMS = listOf(
    Perm("Location (needed)", Manifest.permission.ACCESS_FINE_LOCATION, asks = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    BACKGROUND,
    Perm("Notifications (needed for alerts behind other apps)", Manifest.permission.POST_NOTIFICATIONS, minSdk = 33),
    Perm("Microphone (spoken report comments)", Manifest.permission.RECORD_AUDIO),
    Perm("Car speed (speed panel uses the car's own speed)", PermState.CAR_SPEED),
    CONTACTS,
)

/** Current runtime-permission state for the setup sections: whether each [Perm] is granted. */
internal class PermState(private val ok: Map<Perm, Boolean>) {
    val background get() = ok.getValue(BACKGROUND)
    val contacts get() = ok.getValue(CONTACTS)
    fun granted(p: Perm) = ok.getValue(p)

    /** The permissions "Allow" still has to ask for, in one request; background location is asked for separately. */
    fun missingForeground(): List<String> = PERMS.filter { it != BACKGROUND && it != CONTACTS && !ok.getValue(it) }.flatMap { it.asks.asList() }

    companion object {
        /** Reads the current grants; a permission that doesn't exist on this Android version counts as granted. */
        fun read(ctx: Context) = PermState(PERMS.associateWith { Build.VERSION.SDK_INT < it.minSdk || granted(ctx, it.permission) })

        /** Android Auto's runtime permission for the car's speed (CarInfo speed listener). */
        const val CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"
    }
}

/** One ✓ / ✗ line in the Permissions section (docs/SETTINGS.md §2 "Permission lines"). */
@Composable
private fun PermLine(label: String, ok: Boolean) {
    Text((if (ok) "✓ " else "✗ ") + label, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
}

/** Saves (or with null removes) the Maps key and tells the Navigation SDK, the one way a key changes from the phone (DESIGN.md §1.4 "Changing the key"). */
private suspend fun PhoneDeps.saveKey(key: String?) {
    settings.setMapsApiKey(key)
    navigator.keyChanged()
}

/** Opens this app's page in Android settings, for when Android has stopped showing the permission prompt. */
private fun openAppSettings(ctx: Context) {
    ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The guided set-up (DESIGN.md §13.2): the user does each step in Google Cloud in their browser,
 * signed in to their own account, then pastes the key. Needs no registration of this app.
 */
@Composable
private fun GuidedKeySetup(activity: Activity, deps: PhoneDeps, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sha1 = remember { deps.keyCreator.signingSha1() }
    var pasteMessage by remember { mutableStateOf<String?>(null) }
    fun copy(label: String, text: String) =
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

    @Composable
    fun Step(n: Int, text: String, buttons: @Composable () -> Unit) {
        Text("$n. $text", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
    // The usual step: one Open button for its Console page.
    @Composable
    fun Step(n: Int, text: String, url: String) = Step(n, text) { OutlinedButton(onClick = { activity.openUrl(url) }) { Text("Open") } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Help("Each Open button shows the page in your browser, signed in to your Google account. Do the steps in order: " +
            "Google Cloud applies each page to the project you created in step 1.")
        Step(1, "Create a project, for example \"AutoNavAlerts\".", MapsKeyCreator.CONSOLE_PROJECT_CREATE)
        Step(2, "Link a billing account to it (add one first if you have none). Google Maps needs billing.", MapsKeyCreator.CONSOLE_BILLING)
        Step(3, "Turn on these APIs: press Enable on each page. The Routes API is only needed for energy-efficient routes.") {
            OutlinedButton(onClick = { activity.openUrl(MapsKeyCreator.CONSOLE_NAVIGATION) }) { Text("Navigation SDK") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { activity.openUrl(MapsKeyCreator.CONSOLE_MAPS_ANDROID) }) { Text("Maps SDK for Android") }
            OutlinedButton(onClick = { activity.openUrl(MapsKeyCreator.CONSOLE_PLACES) }) { Text("Places API (New)") }
        }
        OutlinedButton(onClick = { activity.openUrl(MapsKeyCreator.CONSOLE_ROUTES) }) { Text("Routes API") }
        Step(4, "Create credentials → API key. Then edit the key: under Application restrictions pick Android apps and add this " +
            "app's package name and SHA-1; under API restrictions pick the APIs from step 3.", MapsKeyCreator.CREDENTIALS_URL)
        Help("Package: ${activity.packageName}")
        // The SHA-1 of the certificate this APK is signed with, read from PackageManager, so the key's restriction matches this build (DESIGN.md §13.3).
        Help("SHA-1: $sha1")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copy("Package name", activity.packageName) }) { Text("Copy package") }
            OutlinedButton(onClick = { copy("SHA-1", sha1) }) { Text("Copy SHA-1") }
        }
        // Only a string shaped like a Google key (AIza + 35 characters) is saved (DESIGN.md §13.2 step 5).
        Step(5, "Copy the key in Google Cloud, then come back here.") {
            Button(onClick = {
                val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim().orEmpty()
                if (MapsKeyCreator.looksLikeKey(text)) scope.launch { deps.saveKey(text); pasteMessage = null; onSaved() }
                else pasteMessage = "The clipboard doesn't hold a Google API key (they start with \"AIza\"). Copy the key in Google Cloud first."
            }) { Text("Paste key from clipboard") }
        }
        pasteMessage?.let { Err(it) }
    }
}

// --- POI sets ----------------------------------------------------------------------------------

/**
 * The POIs tab (DESIGN.md §13 item 3; docs/SETTINGS.md §3): only sets imported by hand (source
 * FILE), one [SetCard] each, with Import file. Camera sets never appear here; they are on the
 * Cameras tab. Tapping a card swaps the whole tab for [SetEditor] until Back.
 */
@Composable
fun PoiSetsScreen(deps: PhoneDeps) {
    // Only files imported by hand; camera sets live on the Cameras tab (DESIGN.md §13).
    val sets by deps.pois.activeSets.collectAsState(initial = emptyList())
    val fileSets = sets.filter { it.source == PoiSourceType.FILE }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    SetListHost(deps) { list ->
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("POI sets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import file") }
            }
            Help("POI files you've imported yourself. Camera sets are on the Cameras tab.", Modifier.padding(horizontal = 16.dp))
            list.message?.let { Ok(it, Modifier.padding(16.dp)) }
            if (fileSets.isEmpty()) Text("No POI files imported yet. Use Import file to add one.", Modifier.padding(16.dp))
            LazyColumn {
                items(fileSets, key = { it.id }) { set ->
                    SetCard(deps, set, onEdit = { list.editing = set }, onDelete = { list.confirmDelete = set })
                }
            }
            if (fileSets.isNotEmpty()) Help("Tip: swipe a set left to delete it.", Modifier.padding(16.dp))
        }

        importUri?.let { uri ->
            ImportDialog(deps, uri, onClose = { msg -> importUri = null; list.message = msg })
        }
    }
}

/** What the two set lists keep between frames (DESIGN.md §13 items 3 and 8): the set being edited, the one awaiting delete confirmation, and the result line. */
private class SetListState {
    var editing by mutableStateOf<PoiSetEntity?>(null)
    var confirmDelete by mutableStateOf<PoiSetEntity?>(null)
    var message by mutableStateOf<String?>(null)
}

/**
 * Hosts a tab that lists sets: while one is being edited the whole tab is [SetEditor] (the editor replaces
 * the list rather than navigating: one Activity, no NavHost); otherwise [content] draws the list, and a
 * delete asked for there is confirmed by [DeleteSetDialog], whose result becomes [SetListState.message].
 */
@Composable
private fun SetListHost(deps: PhoneDeps, content: @Composable (SetListState) -> Unit) {
    val list = remember { SetListState() }
    list.editing?.let { set -> SetEditor(deps, set, onDone = { list.editing = null }); return }
    content(list)
    list.confirmDelete?.let { set -> DeleteSetDialog(deps, set, onClose = { msg -> list.confirmDelete = null; msg?.let { list.message = it } }) }
}

/** A set's card: tap to edit, switch to turn it on or off, swipe left to delete (springs back until confirmed). */
@Composable
private fun SetCard(deps: PhoneDeps, set: PoiSetEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue != androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) return@LaunchedEffect
        onDelete()
        swipe.reset()
    }
    androidx.compose.material3.SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(io.autonav.alerts.R.drawable.ic_action_delete), null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(8.dp))
                Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleMedium)
            }
        },
    ) {
        IconRowCard(
            deps.icons.bitmap(set.defaultIconId, 96).asImageBitmap(), set.name,
            "${set.poiCount} POIs · ${set.source.name.lowercase().replace('_', ' ')}" + (set.version?.let { " · v$it" } ?: ""),
            Modifier.fillMaxWidth(), onClick = onEdit,
        ) {
            Switch(checked = set.enabled, onCheckedChange = { on -> scope.launch { deps.pois.updateSet(set.copy(enabled = on)) } })
        }
    }
}

/**
 * Confirms deleting a set; [onClose] gets a message when it was deleted, or null when cancelled. With
 * [pgwNote] a PGW set's text says the next sync brings it back (the lists); the editor leaves it out.
 */
@Composable
private fun DeleteSetDialog(deps: PhoneDeps, set: PoiSetEntity, pgwNote: Boolean = true, onClose: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val pgw = pgwNote && (set.source == PoiSourceType.PGW || set.source == PoiSourceType.PGW_PENDING)
    ConfirmDialog("Delete ${set.name}?", "Delete", onDismiss = { onClose(null) }, onConfirm = {
        scope.launch { deps.pois.deleteSet(set.id); onClose("Deleted ${set.name}") }
    }) {
        Text("Its ${set.poiCount} POIs will be removed from this phone." +
            if (pgw) " The next PocketGPSWorld sync will download them again. To stop that, turn off automatic updates on the Cameras tab." else "")
    }
}

/**
 * The import flow (DESIGN.md §13 item 4, §5; docs/SETTINGS.md §3.2): shows the set names the
 * file, or each file in a zip, will get; the CSV column wizard for a `.csv` (§5.7); then imports
 * and reports the result. There is no set-name prompt: names come from the file names and every
 * file in a zip becomes its own set (AGENTS.md §3.1 rule 9). [onClose] gets the result message,
 * or null when cancelled.
 */
@Composable
private fun ImportDialog(deps: PhoneDeps, uri: Uri, onClose: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileName = remember { deps.pois.displayName(uri) }
    var zipFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(uri) { zipFiles = deps.pois.zipPoiFileNames(uri) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val isCsv = fileName.endsWith(".csv", true)
    // The first 4 rows of a CSV, for the wizard's preview and to detect the delimiter and header row.
    val preview = remember {
        if (isCsv) ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r -> r.lineSequence().take(4).toList() } ?: emptyList() else emptyList()
    }
    // The six column boxes of the CSV wizard, label and typed column number; lat, lon and name have defaults (docs/SETTINGS.md §3.2).
    val cols = remember { listOf("Lat" to "0", "Lon" to "1", "Name" to "2", "Speed" to "", "Heading" to "", "Dir" to "").map { (l, d) -> l to mutableStateOf(d) } }
    fun col(label: String) = cols.first { it.first == label }.second.value.toIntOrNull()

    AlertDialog(
        onDismissRequest = { if (!busy) onClose(null) },
        title = { Text("Import $fileName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Set names come from the files themselves (DESIGN.md §5): nothing to type.
                val names = if (zipFiles.isNotEmpty()) zipFiles else listOf(fileName)
                Text(if (names.size > 1) "${names.size} sets will be created, one per file:" else "Will be imported as:",
                    style = MaterialTheme.typography.bodyMedium)
                // A zip can hold dozens of files; 12 names are enough to see what will happen.
                names.take(12).forEach { Help("• " + deps.pois.setNameFor(it)) }
                if (names.size > 12) Help("…and ${names.size - 12} more")
                if (isCsv) {
                    Help("CSV columns (0 = first column). Leave blank if not present.")
                    preview.forEach { Help(it.take(80)) }
                    cols.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { (l, st) -> ColField(l, st.value) { st.value = it } } }
                    }
                }
                result?.let { Ok(it) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && result == null, onClick = {
                busy = true
                scope.launch {
                    // Delimiter and header are detected from the preview; a blank column box means "no such column" (docs/SETTINGS.md §3.2).
                    val profile = if (isCsv) CsvProfile(
                        delimiter = CsvImporter.detectDelimiter(preview.joinToString("\n")),
                        hasHeader = preview.firstOrNull()?.any { it.isLetter() } == true,
                        latCol = col("Lat") ?: 0, lonCol = col("Lon") ?: 1, nameCol = col("Name"), speedCol = col("Speed"),
                        bearingCol = col("Heading"), dirTypeCol = col("Dir"),
                    ) else null
                    val out = runCatching { deps.pois.importFile(uri, csvProfile = profile) }
                    result = out.fold({ it.message + (it.errorSamples.firstOrNull()?.let { s -> "\nFirst problem: $s" } ?: "") }, { "Import failed: ${it.message}" })
                    busy = false
                }
            }) { Text(if (busy) "Importing…" else "Import") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onClose(result) }) { Text(if (result == null) "Cancel" else "Close") } },
    )
}

/** One numbered column box of the CSV wizard (0 = first column; blank = not present). */
@Composable
private fun ColField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.size(width = 86.dp, height = 64.dp))
}

/**
 * Set detail (DESIGN.md §13 item 5; docs/SETTINGS.md §3.3), used by the POIs and Cameras tabs:
 * name, Show on map / Alerts / Sound, icon (built-in, user, or from a file), the 1st and 2nd
 * alert stages, the read-only passing alert, and the three spoken templates with Test speak.
 * Distances are edited in the user's units and stored in metres. Save checks the §11.1 rules
 * first; the passing alert has no controls because it is compulsory while sound is on (§11.1).
 * The set is not written until Save; Back discards the edits.
 */
@Composable
private fun SetEditor(deps: PhoneDeps, initial: PoiSetEntity, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var set by remember { mutableStateOf(initial) }
    var units by remember { mutableStateOf(Units.IMPERIAL) }
    var leadSeconds by remember { mutableIntStateOf(DEFAULT_ALERT_LEAD_SECONDS) }
    var pickIcon by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { deps.settings.current().let { units = it.units; leadSeconds = it.alertLeadSeconds } }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { deps.icons.addUserIcon(it)?.let { id -> set = set.copy(defaultIconId = id) } }
    }
    val yd = units == Units.IMPERIAL
    fun toUser(m: Int) = if (yd) (m / Geo.METRES_PER_YARD).roundToInt() else m
    fun fromUser(v: Int) = if (yd) (v * Geo.METRES_PER_YARD).roundToInt() else v
    val unitLabel = if (yd) "yd" else "m"

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("‹ Back") }
            Text(set.name, style = MaterialTheme.typography.headlineSmall)
        }
        Section("Name") {
            OutlinedTextField(set.name, { set = set.copy(name = it) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Help("Taken from the file when imported. Change it here if you like.")
        }
        Section("Show and alert") {
            SwitchRow("Show on map", set.showOnMap) { set = set.copy(showOnMap = it) }
            SwitchRow("Alerts", set.alertsEnabled) { set = set.copy(alertsEnabled = it) }
            SwitchRow("Sound (makes the passing alert compulsory)", set.soundEnabled) { set = set.copy(soundEnabled = it) }
        }
        Section("Icon") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(deps.icons.bitmap(set.defaultIconId, 128).asImageBitmap(), null, Modifier.size(48.dp))
                OutlinedButton(onClick = { pickIcon = !pickIcon }) { Text("Choose") }
                OutlinedButton(onClick = { iconPicker.launch(arrayOf("image/*")) }) { Text("From file") }
            }
            // PGW icons carry the credit their licence asks for (docs/SETTINGS.md §3.3 Icon).
            if (IconRepository.isPgwIcon(set.defaultIconId)) {
                Help(io.autonav.alerts.core.poi.importers.PgwOv2.ICON_CREDIT)
            }
            if (pickIcon) {
                (deps.icons.builtinIds() + deps.icons.userIds()).chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { id ->
                            Image(deps.icons.bitmap(id, 96).asImageBitmap(), deps.icons.label(id),
                                Modifier.size(40.dp).clickable { set = set.copy(defaultIconId = id); pickIcon = false })
                        }
                    }
                }
            }
        }
        // In seconds-ahead mode the seconds come from the Alerts slider (DESIGN.md §11.1): the
        // cards say so, and Save writes them into a1_seconds/a2_seconds so older readers see sane values.
        val sliderNote = "Seconds: from the Alerts slider ($leadSeconds s / ${halfSecondsLabel(leadSeconds)})"
        StageEditor("1st alert", set.firstAlert, canDisable = false, unitLabel, ::toUser, ::fromUser, sliderNote) { set = set.copy(firstAlert = it) }
        StageEditor("2nd alert", set.secondAlert, canDisable = true, unitLabel, ::toUser, ::fromUser, sliderNote) { set = set.copy(secondAlert = it) }
        // Compulsory and fixed (DESIGN.md §11.1; AGENTS.md §3.2): shown so the user knows it exists, with nothing to change.
        Section("Passing alert (1 s ahead, at least 20 yd)") {
            Help("Always on while sound is enabled for this set.")
        }
        // Only these five placeholders exist (DESIGN.md §7.2; AGENTS.md §3.2).
        Section("Spoken text") {
            Help("Placeholders: {category} {speed} {units} {distance} {direction}")
            TemplateField(deps, "1st alert", set.firstTemplate, units) { set = set.copy(firstTemplate = it) }
            TemplateField(deps, "2nd alert", set.secondTemplate, units) { set = set.copy(secondTemplate = it) }
            TemplateField(deps, "Passing", set.passingTemplate, units) { set = set.copy(passingTemplate = it) }
        }
        val error = validate(set)
        error?.let { Err(it, Modifier.padding(16.dp)) }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = error == null && set.name.isNotBlank(), onClick = {
                scope.launch { deps.pois.updateSet(withSliderSeconds(set, leadSeconds)); onDone() }
            }) { Text("Save") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete set") }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (confirmDelete) DeleteSetDialog(deps, set, pgwNote = false, onClose = { msg -> confirmDelete = false; if (msg != null) onDone() })
}

/** DESIGN.md §11.1 validation: 2nd ≥ 142 m; 1st ≥ 2nd + 100 m (FIXED distances). */
private fun validate(set: PoiSetEntity): String? = io.autonav.alerts.data.StageRules.problem(set.firstAlert, set.secondAlert)

/**
 * The DB's a1_seconds/a2_seconds are no longer edited: in seconds-ahead mode they're the slider's
 * value and its half (rounded down, as the 7 s default was), so anything still reading them agrees
 * with what the slider says. Distance-mode stages keep whatever they had.
 */
internal fun withSliderSeconds(set: PoiSetEntity, leadSeconds: Int): PoiSetEntity {
    fun StageCols.fromSlider(seconds: Int) = if (mode == DistanceMode.TIME) copy(seconds = seconds) else this
    return set.copy(firstAlert = set.firstAlert.fromSlider(leadSeconds), secondAlert = set.secondAlert.fromSlider(leadSeconds / 2))
}

/** One alert stage: on/off (2nd only), distance or seconds-ahead mode, and the metres (floor in seconds-ahead mode). */
@Composable
private fun StageEditor(
    title: String, stage: StageCols, canDisable: Boolean, unit: String,
    toUser: (Int) -> Int, fromUser: (Int) -> Int, sliderNote: String, onChange: (StageCols) -> Unit,
) {
    Section(title) {
        if (canDisable) SwitchRow("On", stage.enabled) { onChange(stage.copy(enabled = it)) }
        if (stage.enabled) {
            ChoiceChips(listOf(DistanceMode.FIXED to "Distance", DistanceMode.TIME to "Seconds ahead"), stage.mode) { onChange(stage.copy(mode = it)) }
            OutlinedTextField(toUser(stage.metres).toString(), { v -> v.toIntOrNull()?.let { onChange(stage.copy(metres = fromUser(it))) } },
                label = { Text(if (stage.mode == DistanceMode.TIME) "Minimum ($unit)" else "Distance ($unit)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (stage.mode == DistanceMode.TIME) Help(sliderNote)
        }
    }
}

/**
 * The template editor (DESIGN.md §13 item 7): the text, a live preview rendered against a sample
 * 30 mph fixed camera 400 m ahead, and Test speak through the real [NavTts] so the audio route
 * can be checked. Priority 10 and POI id -1: a plain utterance, no chime, tied to no camera.
 */
@Composable
private fun TemplateField(deps: PhoneDeps, label: String, value: String, units: Units, onChange: (String) -> Unit) {
    val sample = TemplateValues(category = "Fixed camera", speed = 30, speedUnit = SpeedUnit.MPH, distanceM = 400.0, direction = "ahead")
    val rendered = AlertTemplateRenderer(units).render(value, sample)
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Help("“${rendered.display}”", Modifier.weight(1f))
        TextButton(onClick = { deps.tts.speak(rendered.spoken, 10, -1, chime = false) }) { Text("Test speak") }
    }
}

// --- Camera database (PGW) ---------------------------------------------------------------------

/**
 * The Cameras tab (DESIGN.md §13 item 8, §6; docs/SETTINGS.md §4): the PocketGPSWorld login (the
 * user's own; the download stays on this phone, AGENTS.md §3.1 rule 6), the download format
 * (OV2 or iGO), the database state with Sync now / Force re-download / auto-update / Wi-Fi only,
 * the camera sets grouped by type ([CameraSets]), and the manual download page. Tapping a set
 * swaps the tab for [SetEditor].
 */
@Composable
fun CameraDbScreen(deps: PhoneDeps) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by deps.settings.pgwState.collectAsState(initial = PgwState())
    val settings by deps.settings.settings.collectAsState(initial = AppSettings())
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var hasLogin by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { hasLogin = deps.settings.pgwCredentials() != null }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val sets by deps.pois.activeSets.collectAsState(initial = emptyList())

    SetListHost(deps) { list ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("PocketGPSWorld", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
            Section("Your subscription") {
                Help("Uses your own PocketGPSWorld login. Your copy stays on this phone and is never shared or backed up.")
                if (hasLogin) Text("Login saved.") else {
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasLogin) Btn("Save and test", scope, enabled = user.isNotBlank() && pass.isNotBlank()) {
                        deps.settings.setPgwCredentials(user.trim(), pass)
                        // A new login must not reuse the old session cookies (DESIGN.md §6.1).
                        deps.pgw.forgetSession()
                        status = runCatching { deps.pgw.ensureLoggedIn(); "Logged in." }.getOrElse { "Login failed: ${it.message}" }
                        pass = ""; refresh++
                        deps.scheduler.schedule()
                    } else Btn("Forget login", scope, outlined = true) {
                        deps.settings.setPgwCredentials(null, null); deps.pgw.forgetSession(); deps.scheduler.schedule(); refresh++
                    }
                }
                status?.let { Ok(it) }
            }
            // DESIGN.md §5.2a: switching formats replaces the sets on the next sync, so the per-set icon and alert choices are lost.
            Section("Download format") {
                ChoiceChips(PgwDownload.entries.map { it to (it.label + if (it == PgwDownload.DEFAULT) " (recommended)" else "") }, settings.pgwDownload) { d ->
                    scope.launch { deps.settings.update { it.copy(pgwDownload = d) } }
                }
                Help(settings.pgwDownload.detail)
                if (state.installedPackage != null && state.installedPackage != settings.pgwDownload.idPackage) {
                    Help("Press Sync now to switch to ${settings.pgwDownload.label}. Your camera sets will be replaced; " +
                        "icon and alert choices made for the old format's sets are reset.", color = MaterialTheme.colorScheme.primary)
                }
            }
            Section("Database") {
                Text("Installed: ${state.installedVersion ?: "none"}")
                Text("Latest in feed: ${state.latestVersion ?: "not checked yet"}")
                state.latestSummary?.let { Help(it) }
                if (state.lastFeedCheckMs > 0) Help("Feed checked: ${df.format(Date(state.lastFeedCheckMs))}")
                if (state.lastSyncMs > 0) Help("Last sync: ${df.format(Date(state.lastSyncMs))}")
                state.lastResult?.let { Help(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Sync now only downloads when the feed shows a newer version; Force re-download always does (docs/SETTINGS.md §4).
                    Button(enabled = hasLogin, onClick = { deps.scheduler.syncNow(force = false) }) { Text("Sync now") }
                    OutlinedButton(enabled = hasLogin, onClick = { deps.scheduler.syncNow(force = true) }) { Text("Force re-download") }
                }
                SwitchRow("Update automatically when a new release is announced", settings.pgwAutoSync) { on ->
                    scope.launch { deps.settings.update { it.copy(pgwAutoSync = on) }; deps.scheduler.schedule() }
                }
                SwitchRow("Wi-Fi only", settings.pgwWifiOnly) { on -> scope.launch { deps.settings.update { it.copy(pgwWifiOnly = on) } } }
            }
            CameraSets(deps, cameraGroups(sets), list.message, onEdit = { list.editing = it }, onDelete = { list.confirmDelete = it })
            Section("Manual import") {
                Help("You can also download the iGO file yourself and import it on the POIs tab.")
                OutlinedButton(onClick = { ctx.openUrl(PgwConfig.BASE + PgwConfig.CAMERAS_PATH) }) { Text("Open download page") }
            }
        }
    }
}

/** One camera type's sets, batched together on the Cameras tab (DESIGN.md §13). */
internal data class CameraGroup(val name: String, val sets: List<PoiSetEntity>) {
    val cameras get() = sets.sumOf { it.poiCount }
    val anyOn get() = sets.any { it.enabled }
}

/**
 * Camera sets (PGW, pending and My reports) grouped by type: the part of the name before " · ",
 * so the OV2 download's per-speed sets share one group. Pending sites come last, then My reports. Internal for `PhoneHelpersTest`.
 */
internal fun cameraGroups(sets: List<PoiSetEntity>): List<CameraGroup> {
    val order = listOf(PoiSourceType.PGW, PoiSourceType.PGW_PENDING, PoiSourceType.REPORT)
    return sets.filter { it.source in order }
        .groupBy { it.name.substringBefore(" · ") }
        .map { (name, members) -> CameraGroup(name, members.sortedWith(compareBy({ speedOrder(it.name) }, { it.name }))) }
        .sortedWith(compareBy({ order.indexOf(it.sets.first().source) }, { it.name }))
}

/** Sorts "· 20 mph" before "· 100 mph"; sets without a number keep name order after them. */
internal fun speedOrder(name: String): Int =
    Regex("""· (\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

/**
 * The camera-set groups on the Cameras tab (DESIGN.md §13 item 8; docs/SETTINGS.md §4.1): a
 * summary line, then one card per type with its icon, totals, an expand arrow and a group switch
 * (on while any set in the group is on; turns them all). Expanded, the sets show as [SetCard]s.
 */
@Composable
private fun CameraSets(
    deps: PhoneDeps, groups: List<CameraGroup>, message: String?,
    onEdit: (PoiSetEntity) -> Unit, onDelete: (PoiSetEntity) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val total = groups.sumOf { it.cameras }
    val setCount = groups.sumOf { it.sets.size }
    Text("Camera sets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
    Help(
        if (groups.isEmpty()) "None yet. Log in and press Sync now to download your cameras."
        else "%,d cameras in %d %s. Tap a type to see its sets.".format(total, setCount, if (setCount == 1) "set" else "sets"),
        Modifier.padding(horizontal = 16.dp),
    )
    message?.let { Ok(it, Modifier.padding(horizontal = 16.dp)) }
    groups.forEach { g ->
        val open = g.name in expanded
        IconRowCard(
            deps.icons.bitmap(g.sets.first().defaultIconId, 96).asImageBitmap(), g.name,
            "%,d cameras · %d %s".format(g.cameras, g.sets.size, if (g.sets.size == 1) "set" else "sets"),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            onClick = { expanded = if (open) expanded - g.name else expanded + g.name },
        ) {
            Text(if (open) "▲" else "▼", Modifier.padding(end = 8.dp))
            Switch(checked = g.anyOn, onCheckedChange = { on ->
                scope.launch { deps.pois.updateSets(g.sets.map { it.copy(enabled = on) }) }
            })
        }
        if (open) Column(Modifier.padding(start = 16.dp)) {
            g.sets.forEach { set -> key(set.id) { SetCard(deps, set, onEdit = { onEdit(set) }, onDelete = { onDelete(set) }) } }
        }
    }
    if (groups.isNotEmpty()) Help("Tip: swipe a set left to delete it.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

// --- Reports (final review and submission happen only here, never on the car screen) ----------

/**
 * The Reports tab (DESIGN.md §8.5, §13 item 2; docs/SETTINGS.md §5): the end-of-drive review.
 * Final review and sending happen here, on the phone, and nowhere else (AGENTS.md §3.1 rule 8):
 * the car only captures. Send and Send all are disabled while the phone is connected to Android
 * Auto, a car session is open, or it moved faster than 5 mph in the last 2 minutes
 * (`DrivingState.blockedReason()`); the submit worker refuses to run then too. No auto-send.
 */
@Composable
fun ReportsScreen(activity: Activity, deps: PhoneDeps) {
    val reports by deps.reports.reports.collectAsState(initial = emptyList())
    val carConnected by deps.driving.carConnected.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(carConnected) { tick++ }
    // Recomputed when the car connects or disconnects and when the list changes; the moving gate is time-based, so it is read at those moments.
    val blocked = remember(carConnected, tick, reports) { deps.driving.blockedReason() }
    val drafts = reports.filter { it.status == ReportStatus.DRAFT || it.status == ReportStatus.FAILED }

    Column {
        Text("Camera reports", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Help("Reports made with the Report button in the car wait here. Check each one, then press Send. " +
                "Don't report warning signs or Traffic Master cameras; only report mobiles when a unit is actually there.")
            blocked?.let { Err(it) }
            // Send all: queues every draft and failed report; the worker sends them once the drive is over (DESIGN.md §8.5).
            if (drafts.size > 1) Btn("Send all ${drafts.size}", scope, enabled = blocked == null) {
                drafts.forEach { deps.reports.queueForSending(it) }; ReportSubmitWorker.enqueue(activity)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (reports.isEmpty()) Text("No reports yet. Tap Report on the car screen when you see a camera.", Modifier.padding(16.dp))
        LazyColumn {
            items(reports, key = { it.id }) { r -> ReportCard(activity, deps, r, blocked) }
        }
    }
}

/**
 * One report (DESIGN.md §8.5 "Review on the phone"; docs/SETTINGS.md §5): the header with its
 * status, then, while it is a draft or failed, the editable review: type chips (pre-filled from
 * the spoken comment), position with 10 m nudges and Show on map, speed limit with its source,
 * direction of travel (±5°, ±45°, Reverse; One way / Both ways), the comment with Play recording
 * and retry transcription, then Send / Save / Discard / Browser. Chip and nudge edits are saved
 * at once through [CameraReportRepository.saveReview]; the comment text waits for Save or Send.
 * Send is disabled while [blocked] is set (AGENTS.md §3.1 rule 8).
 */
@Composable
private fun ReportCard(activity: Activity, deps: PhoneDeps, initial: CameraReportEntity, blocked: String?) {
    val scope = rememberCoroutineScope()
    var r by remember(initial) { mutableStateOf(initial) }
    var units by remember { mutableStateOf(Units.IMPERIAL) }
    LaunchedEffect(Unit) { units = deps.settings.current().units }
    val editable = r.status == ReportStatus.DRAFT || r.status == ReportStatus.FAILED
    fun save(n: CameraReportEntity) { r = n; scope.launch { deps.reports.saveReview(n) } }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(CameraReportRepository.label(r.kind).removeSuffix(" (reported)"), style = MaterialTheme.typography.titleMedium)
            Help(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.createdAt)) +
                " · " + r.status.name.lowercase() + (r.lastError?.let { " · $it" } ?: ""))
            if (!editable) return@Column

            // Type (guessed from the spoken comment; confirm here)
            Text("Type", style = MaterialTheme.typography.labelLarge)
            listOf(
                ReportKind.MOBILE_SEEN to "Mobile", ReportKind.NEW_FIXED to "Fixed", ReportKind.NEW_AVERAGE to "Average",
                ReportKind.NEW_RED_LIGHT to "Red light", ReportKind.NEW_RED_LIGHT_SPEED to "Red light + speed",
                ReportKind.CAMERA_GONE to "Not there", ReportKind.WRONG_LIMIT to "Wrong limit", ReportKind.UNSPECIFIED to "Not sure",
            ).chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (k, label) -> FilterChip(r.kind == k, { save(r.copy(kind = k)) }, label = { Text(label) }) }
                }
            }

            // Position, with fine nudges
            Text("Position: %.5f, %.5f".format(r.lat, r.lon), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 10 m in degrees: about 111 195 m per degree of latitude; a degree of longitude shrinks with cos(lat).
                val dLat = 10.0 / 111_195.0
                val dLon = 10.0 / (111_195.0 * kotlin.math.cos(Math.toRadians(r.lat)))
                listOf("N" to (dLat to 0.0), "S" to (-dLat to 0.0), "E" to (0.0 to dLon), "W" to (0.0 to -dLon)).forEach { (l, d) ->
                    OutlinedButton(onClick = { save(r.copy(lat = r.lat + d.first, lon = r.lon + d.second)) }) { Text("$l 10 m") }
                }
            }
            TextButton(onClick = { activity.openUrl("geo:${r.lat},${r.lon}?q=${r.lat},${r.lon}(Reported camera)") }) { Text("Show on map") }

            // Speed limit (with where it came from)
            Text("Speed limit: ${r.speedLimit?.let { "$it mph" } ?: "not set"} (${LIMIT_SOURCE_TEXT.getValue(r.limitSource)})", style = MaterialTheme.typography.labelLarge)
            // UK limits in mph; PgwReportSubmitter maps them to the form's own speed codes (DESIGN.md §8.5 table).
            ChoiceChips(listOf(20, 30, 40, 50, 60, 70).map { it to "$it" }, r.speedLimit, spacing = 6.dp) { v ->
                save(r.copy(speedLimit = v, limitSource = LimitSource.USER))
            }

            // Direction
            Text("Direction of travel: ${r.heading?.roundToInt()?.let { "$it° (${compass(it)})" } ?: "unknown"}", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Turning the heading makes an "any direction" report one-way in that direction (DESIGN.md §8.5 "direction of travel").
                fun turn(by: Int) = save(r.copy(heading = (((r.heading ?: 0f) + by) % 360 + 360) % 360, directionType =
                    if (r.directionType == DirectionType.ANY) DirectionType.ONE_WAY else r.directionType))
                listOf(-45 to "−45°", -5 to "−5°", 5 to "+5°", 45 to "+45°", 180 to "Reverse").forEach { (by, label) ->
                    OutlinedButton(onClick = { turn(by) }) { Text(label) }
                }
            }
            ChoiceChips(listOf(DirectionType.ONE_WAY to "One way", DirectionType.BOTH to "Both ways"), r.directionType, spacing = 6.dp) {
                save(r.copy(directionType = it))
            }

            // Comment (speech-to-text from the car, editable)
            OutlinedTextField(r.comment.orEmpty(), { r = r.copy(comment = it) }, label = { Text("Comment") },
                modifier = Modifier.fillMaxWidth(), supportingText = { Text(TRANSCRIPTION_TEXT[r.transcription] ?: "Optional") })
            r.audioPath?.let { path ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Transcriber.play(java.io.File(path)) }) { Text("Play recording") }
                    if (r.transcription == "FAILED") Btn("Try transcribing again", scope, outlined = true) { deps.reports.transcribe(r.id) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Send", scope, enabled = blocked == null) { deps.reports.queueForSending(r); ReportSubmitWorker.enqueue(activity) }
                Btn("Save", scope, outlined = true) { deps.reports.saveReview(r) }
                Btn("Discard", scope, outlined = true) { deps.reports.discard(r) }
                TextButton(onClick = {
                    // The fallback when the form can't be automated (DESIGN.md §8.5): the fields on the clipboard, the Submit page in the browser. SendEntry is the button, not a field.
                    val s = deps.reports.toSubmission(r)
                    val text = s.formFields().filter { it.first != "SendEntry" }.joinToString("\n") { "${it.first}: ${it.second}" }
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Camera report", text))
                    activity.openUrl(PgwConfig.BASE + PgwConfig.SUBMIT_PATH)
                }) { Text("Browser") }
            }
        }
    }
}

/** Eight-point compass name for a heading in degrees (338° to 22° is N). */
internal fun compass(deg: Int): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg + 22) % 360) / 45]

/** Where a report's speed limit came from, as the review card says it (DESIGN.md §8.5 "with its source shown"). */
private val LIMIT_SOURCE_TEXT = mapOf(LimitSource.GOOGLE to "from Google", LimitSource.CAMERA to "from camera data", LimitSource.USER to "set by you", LimitSource.NONE to "unknown")

/** The comment field's help by the transcription state (§8.5 "Speech to text"); any other state (no recording) reads "Optional". */
private val TRANSCRIPTION_TEXT = mapOf(
    "PENDING" to "Turning your recording into text…", "FAILED" to "Couldn't transcribe the recording. Play it and type the comment.",
    "DONE" to "From your spoken comment; edit if needed.",
)

// --- Settings ----------------------------------------------------------------------------------

/**
 * The Settings tab (DESIGN.md §13 item 9; docs/SETTINGS.md §1): one scrolling column of cards in
 * this order: the setup sections (folded into one row once complete), Car screen, Alerts, Speed
 * and location source, Routing, Speed limits (OpenStreetMap), Bench testing (debug builds only),
 * Backup, Diagnostics and Connecting to a car (both folded behind Show), About. Each control
 * carries at most one short line of help; the full explanation is docs/SETTINGS.md. Every change
 * is written to [SettingsStore] at once; the car screen and the alert engine observe the same
 * flow, so there is no Save button.
 */
@Composable
fun SettingsScreen(activity: Activity, deps: PhoneDeps) {
    val s by deps.settings.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    fun set(block: (AppSettings) -> AppSettings) = scope.launch { deps.settings.update(block) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        SetupSections(activity, deps)
        // docs/SETTINGS.md §1.1 Units and §1.4 Speed panel share one card (DESIGN.md §13 item 9). Satellite is the same setting as the car's Satellite button.
        Section("Car screen") {
            ChoiceChips(listOf(Units.IMPERIAL to "mph, miles, yards", Units.METRIC to "km/h, km, m"), s.units) { u -> set { it.copy(units = u) } }
            SwitchRow("Show speed and limit on the map", s.speedPanelOn) { v -> set { it.copy(speedPanelOn = v) } }
            SwitchRow("Satellite map in the car", s.satelliteMap) { v -> set { it.copy(satelliteMap = v) } }
        }
        // docs/SETTINGS.md §1.2. Voice silences the POI alerts only; the directions are always spoken (DESIGN.md §12.3).
        Section("Alerts") {
            SwitchRow("Voice", s.voiceOn) { v -> set { it.copy(voiceOn = v) } }
            SwitchRow("Chime before the passing alert", s.chimeOn) { v -> set { it.copy(chimeOn = v) } }
            OverLimitSlider(s.overspeedPct) { p -> set { it.copy(overspeedPct = p) } }
            AlertLeadSlider(s.alertLeadSeconds, s.units) { n -> set { it.copy(alertLeadSeconds = n) } }
            SwitchRow("Announce speed limit changes ahead", s.announceLimitChanges) { v -> set { it.copy(announceLimitChanges = v) } }
            Help("Spoken once per change, from the OpenStreetMap limits on the phone.")
        }
        // docs/SETTINGS.md §1.3; DESIGN.md §10.6, §10.7.
        Section("Speed and location source") {
            ChoiceChips(listOf(SpeedSource.CAR_WHEN_AVAILABLE to "Car when available", SpeedSource.PHONE_GPS to "Phone only"), s.speedSource) { v ->
                set { it.copy(speedSource = v) }
            }
            Help("The car's own speed and GPS through Android Auto when it shares them, else the phone.")
        }
        // docs/SETTINGS.md §1.4b; DESIGN.md §8.3a.
        Section("Routing") {
            SwitchRow("Avoid tolls", s.avoidTolls) { v -> set { it.copy(avoidTolls = v) } }
            SwitchRow("Energy-efficient routes (EV)", s.evEfficientRoutes) { v -> set { it.copy(evEfficientRoutes = v) } }
            Help("With EV routes on, the preview offers Fastest and Energy-efficient (Routes API on your key) and you pick before Go.")
        }
        // docs/SETTINGS.md §1.4a; DESIGN.md §8.4a.
        Section("Speed limits (OpenStreetMap)") { OsmLimitsSection(activity, deps, s) { b -> set(b) } }
        // docs/SETTINGS.md §1.6: the Simulate drive exists only in debug builds (TESTING.md §5).
        if (io.autonav.alerts.BuildConfig.DEBUG) Section("Bench testing (debug builds)") {
            Help("Speed of the Simulate drive on the car screen; 1× is real time.")
            ChoiceChips(listOf(1f, 2f, 4f).map { it to "${it.roundToInt()}×" }, s.simulationSpeed) { v -> set { it.copy(simulationSpeed = v) } }
        }
        // docs/SETTINGS.md §1.7; DESIGN.md §13.4. The file holds the key and PGW password in plain text, so the card warns before the button.
        Section("Backup") {
            val saver = rememberSaveTo("application/json", "Settings backup saved", "Couldn't save the backup") { uri -> deps.backup.writeTo(activity, uri) }
            Help("Settings, your Maps key, your PocketGPSWorld login and each camera set's alert settings, as a JSON file. Not camera data, reports or places.")
            Help("The file holds your key and password in plain text: keep it private.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { saver.launch(SettingsBackup.fileName()) }) { Text("Save settings backup…") }

            // Restore: read and check the file, show what's in it, then apply on confirm (DESIGN.md §13.4).
            var preview by remember { mutableStateOf<SettingsBackup.Preview?>(null) }
            var restoreMessage by remember { mutableStateOf<String?>(null) }
            val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    runCatching { deps.backup.preview(deps.backup.readText(activity, uri)) }
                        .onSuccess { preview = it; restoreMessage = null }
                        .onFailure { restoreMessage = it.message ?: "Couldn't read the file" }
                }
            }
            OutlinedButton(onClick = { opener.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text("Restore from backup…")
            }
            restoreMessage?.let { Help(it) }
            preview?.let { p ->
                ConfirmDialog("Restore settings?", "Restore", textConfirm = true, onDismiss = { preview = null }, onConfirm = {
                    val text = p.text
                    preview = null
                    scope.launch {
                        restoreMessage = runCatching { deps.backup.restore(text) }.fold({ r ->
                            // A restored key is applied the same way as a pasted one (DESIGN.md §1.4, §13.4 step 4).
                            if (r.mapsKey) deps.navigator.keyChanged()
                            val parts = listOfNotNull("settings".takeIf { r.settings }, "Maps key".takeIf { r.mapsKey }, "PGW login".takeIf { r.pgwLogin }, "${r.sets} camera sets")
                            "Restored " + parts.joinToString(", ").replace(Regex(", ([^,]+)$"), " and $1") + "." +
                                (if (r.setsNotFound > 0) " ${r.setsNotFound} sets weren't found: download the cameras first, then restore again." else "")
                        }, { "Couldn't restore: ${it.message}" })
                    }
                }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Backup made ${p.createdAt?.take(16)?.replace('T', ' ') ?: "(no date)"}" + (p.appVersion?.let { " by version $it" } ?: ""))
                        Text("Maps API key: " + if (p.hasMapsKey) "included" else "not included")
                        Text("PocketGPSWorld login: " + (p.pgwUser?.let { "included ($it)" } ?: "not included"))
                        Text("Camera set settings: ${p.sets} sets, ${p.matchingSets} of them match sets on this phone")
                        // Terms are never restored: Google's terms are accepted per install (DESIGN.md §13.4 step 4).
                        Help("This replaces your current settings. Navigation terms still need accepting on this phone.")
                    }
                }
            }
        }
        // Folded: one line when closed, the developer panel when open (DESIGN.md §13 item 11).
        Fold("Diagnostics", "Two debug logs always on · drive recording ${if (s.driveRecordingOn) "on" else "off"}") {
            DiagnosticsSection(activity, deps, s) { b -> set(b) }
        }
        // Folded like Diagnostics: the car set-up manual (DESIGN.md §13 item 12).
        Fold("Connecting to a car", "Getting AutoNavAlerts on the car screen, and debugging on a head unit") { CarConnectionSection() }
        // docs/SETTINGS.md §1.9: the build stamp (DESIGN.md §1.3) and the licences; nothing to set.
        Section("About") {
            Text("AutoNavAlerts ${io.autonav.alerts.BuildConfig.VERSION_NAME} by Damien Dye")
            Help("Build ${io.autonav.alerts.BuildConfig.GIT_COMMIT}" + if (io.autonav.alerts.BuildConfig.DEBUG) " (debug)" else "")
            TextButton(onClick = { activity.openUrl("https://github.com/damo2929/AutoNavAlerts") }) { Text("github.com/damo2929/AutoNavAlerts") }
            Help("Apache License 2.0. AI-assisted code, written with Claude Fable and Claude Opus.")
            var showLicence by remember { mutableStateOf(false) }
            val licence = remember { deps.icons.pgwIconLicence() }
            if (licence != null) {
                Help("Camera set icons from your PocketGPSWorld download are © GerryC, 2007, used non-commercially with credit.")
                TextButton(onClick = { showLicence = !showLicence }) { Text(if (showLicence) "Hide icon licence" else "Show icon licence") }
                if (showLicence) Help(licence.replace("\r", ""))
            }
        }
    }
}

/**
 * OpenStreetMap limits for the UK (DESIGN.md §8.4a): one Wi-Fi download from Geofabrik,
 * progress, what's on the phone, Delete, and the attribution the licence asks for.
 */
@Composable
private fun OsmLimitsSection(
    activity: Activity, deps: PhoneDeps, s: AppSettings,
    set: ((AppSettings) -> AppSettings) -> Unit,
) {
    val progress by deps.speedLimits.progress.collectAsState()
    var stats by remember { mutableStateOf<io.autonav.alerts.osm.SpeedLimitRepository.Stats?>(null) }
    LaunchedEffect(progress?.finished) { stats = io { deps.speedLimits.stats() } }

    Help("Fills the speed panel's limit (grey dot) when Google hasn't given one. Downloaded once; nothing is looked up while driving.")
    SwitchRow("Show OpenStreetMap limits", s.osmLimitsOn) { v -> set { it.copy(osmLimitsOn = v) } }
    val p = progress
    if (p != null && !p.finished) {
        Text(if (p.total > 0) "${p.region}: ${p.step} · ${p.done / 1_000_000} of ${p.total / 1_000_000} MB" else p.step)
        if (p.total > 0) androidx.compose.material3.LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { deps.speedLimits.cancel() }) { Text("Cancel") }
    } else {
        p?.error?.let { Help("The last download failed: $it", color = MaterialTheme.colorScheme.error) }
        SwitchRow("Download on Wi-Fi only", s.osmWifiOnly) { v -> set { it.copy(osmWifiOnly = v) } }
        Button(onClick = { deps.speedLimits.download(s.osmWifiOnly) }) {
            Text(if (stats == null) "Download speed limits for the UK" else "Update speed limits")
        }
        // The Geofabrik extract is about 1.1 GB to download and about 30 MB once reduced to limits (DESIGN.md §8.4a).
        Help((if (s.osmWifiOnly) "About 1.1 GB on Wi-Fi only" else "About 1.1 GB, over mobile data too") + ", in the background; about 30 MB kept on the phone.")
    }
    stats?.let { st ->
        Help("Data from ${st.dataDate} · ${"%,d".format(st.roads)} roads · ${st.bytes / 1_000_000} MB. Update every few months.")
        TextButton(onClick = { deps.speedLimits.delete(); stats = null }) { Text("Delete downloaded limits") }
    }
    TextButton(onClick = { activity.openUrl("https://www.openstreetmap.org/copyright") }) {
        Text("Speed limits © OpenStreetMap contributors (ODbL), extract by Geofabrik")
    }
}

/** Over-limit warning threshold: −5% to +20% in 0.1% steps (DESIGN.md §11 step 9). */
@Composable
private fun OverLimitSlider(current: Double, onChange: (Double) -> Unit) {
    // The setting is a fraction (-0.001 is -0.1%); the slider works in tenths of a percent, -50 to +200.
    var v by remember(current) { mutableStateOf((current * 1000).roundToInt().toFloat()) } // tenths of a percent
    val pct = v / 10.0
    Text("Over-limit warning: ${pctLabel(pct / 100)}", style = MaterialTheme.typography.bodyLarge)
    Help(when {
        pct < 0 -> "Warns ${pctLabel(-pct / 100)} before you reach the limit"
        pct == 0.0 -> "Warns as soon as you go over the limit"
        else -> "Warns when you're more than ${pctLabel(pct / 100)} over the limit"
    })
    androidx.compose.material3.Slider(
        value = v, onValueChange = { v = Math.round(it).toFloat() },
        onValueChangeFinished = { onChange(v / 1000.0) },
        // 249 steps between -50 and +200 make each step one tenth of a percent (DESIGN.md §11 step 9).
        valueRange = -50f..200f, steps = 249,
    )
    RangeLabels("−5%", "+20%") {
        // The default (DESIGN.md §11 step 9; docs/SETTINGS.md §1.2).
        TextButton(onClick = { v = -1f; onChange(-0.001) }) { Text("Reset to −0.1%") }
    }
}

/**
 * Alert lead time: 15–60 s in 1 s steps (DESIGN.md §11.1). The 1st alert of every set in
 * seconds-ahead mode fires this many seconds ahead at the current speed, the 2nd at half, each
 * lifted to its metre floor; the helper text shows what that gives at 30, 50 and 70 mph.
 */
@Composable
private fun AlertLeadSlider(current: Int, units: Units, onChange: (Int) -> Unit) {
    var v by remember(current) { mutableIntStateOf(current) }
    Text("Alert lead time", style = MaterialTheme.typography.bodyLarge)
    Help("First alert: $v s ahead · second alert at ${halfSecondsLabel(v)}")
    androidx.compose.material3.Slider(
        value = v.toFloat(), onValueChange = { v = Math.round(it) },
        onValueChangeFinished = { onChange(v) },
        valueRange = MIN_ALERT_LEAD_SECONDS.toFloat()..MAX_ALERT_LEAD_SECONDS.toFloat(),
        steps = MAX_ALERT_LEAD_SECONDS - MIN_ALERT_LEAD_SECONDS - 1,
    )
    RangeLabels("$MIN_ALERT_LEAD_SECONDS s", "$MAX_ALERT_LEAD_SECONDS s")
    Help("First alert at " + listOf(30, 50, 70).joinToString(" · ") { mph -> "$mph mph: ${leadDistanceText(mph, v, units)}" })
}

/** "7.5 s" for 15, "8 s" for 16: half the lead time, as the 2nd alert uses it. */
internal fun halfSecondsLabel(seconds: Int): String = if (seconds % 2 == 0) "${seconds / 2} s" else "${seconds / 2}.5 s"

/**
 * The 1st alert's distance at [mph] with [seconds] of lead: max(242 m floor, speed × seconds),
 * in the user's units, marked "(minimum)" when the floor applies.
 */
internal fun leadDistanceText(mph: Int, seconds: Int, units: Units): String {
    val bySpeed = (mph * Geo.MPS_PER_MPH * seconds).roundToInt()
    val metres = bySpeed.coerceAtLeast(FIRST_ALERT_FLOOR_M)
    val yards = metres / Geo.METRES_PER_YARD
    val text = if (units != Units.IMPERIAL) "$metres m" else if (yards < 1000) "${yards.roundToInt()} yd" else "%.2f mi".format(yards / 1760)
    return if (bySpeed < FIRST_ALERT_FLOOR_M) "$text (minimum)" else text
}

/** The 1st alert never fires closer than this in seconds-ahead mode (DESIGN.md §11.1). */
private const val FIRST_ALERT_FLOOR_M = 242

/** "−0.1%", "0%", "5%"… for the over-limit setting. */
internal fun pctLabel(p: Double): String {
    val v = p * 100
    return (if (kotlin.math.abs(v - Math.rint(v)) < 1e-9) "${Math.rint(v).roundToInt()}%" else "%.1f%%".format(v)).replace("-", "−")
}

// --- Diagnostics (DESIGN.md §15.5, §13 item 11; docs/SETTINGS.md §1.8) ------------------------

/**
 * The two always-on debug logs and the opt-in drive recorder: a status line per file, the last
 * 200 lines, Save (file picker), Share (share sheet via the diag FileProvider), Clear, the engine
 * snapshot, the Drive recording switch and its Save, and the privacy note.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsSection(
    activity: Activity,
    deps: PhoneDeps,
    s: AppSettings,
    set: ((AppSettings) -> AppSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    Help("debug-nav: routes, the map and the location feed. alert-debug: every camera and alert. 15-second blocks, 1 MB each.")
    for (ch in EventLog.Channel.entries) key(ch) { DiagLogCard(activity, deps, ch, refresh) }

    var snapshot by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Clear both logs", scope, outlined = true) {
            io { deps.eventLog.clear() }
            refresh++
            Toast.makeText(activity, "Debug logs cleared", Toast.LENGTH_SHORT).show()
        }
        Button(onClick = {
            scope.launch {
                // The snapshot is also written to alert-debug, so the file shows what the engine saw at that moment (DESIGN.md §15.5).
                val text = withContext(Dispatchers.Default) {
                    runCatching { deps.diagnostics.snapshot() }.getOrElse { "snapshot failed: $it" }
                }
                text.lines().forEach { deps.eventLog.record(EventLog.Channel.ALERT, "Engine", it) }
                snapshot = text
            }
        }) { Text("Engine now") }
    }
    snapshot?.let { text ->
        ConfirmDialog("Engine now", "Close", textConfirm = true, cancel = false, onConfirm = { snapshot = null }, onDismiss = { snapshot = null }) { MonoLines(text.lines(), 420.dp) }
    }

    HorizontalDivider()
    SwitchRow("Drive recording", s.driveRecordingOn) { v -> set { it.copy(driveRecordingOn = v) } }
    Help("Every GPS fix to a CSV, for replaying a drive on the bench. About 1–8 MB an hour; the newest 3 are kept.")
    var latest by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, s.driveRecordingOn) { io { latest = deps.driveRecorder.latest(); recording = deps.driveRecorder.isRecording } }
    Help(latest?.let { "Latest: ${it.name} · ${sizeText(it.length())}" + (if (recording) " · recording now" else "") } ?: "No recordings yet")
    val csvSaver = rememberSaveTo("text/csv", "Drive recording saved", "Couldn't save the recording") { uri ->
        io { deps.driveRecorder.flush(); DiagFiles.writeTo(activity, uri, listOf(checkNotNull(latest))) }
    }
    OutlinedButton(enabled = latest != null, onClick = { latest?.let { csvSaver.launch(it.name) } }) { Text("Save drive recording…") }

    HorizontalDivider()
    Help("Logs and recordings stay on the phone (private storage, no cloud backup) and contain where you drove. Nothing leaves unless you Save or Share.")
}

/** One debug log: status, Show last 200 lines, Save…, Share. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagLogCard(activity: Activity, deps: PhoneDeps, ch: EventLog.Channel, refresh: Int) {
    val scope = rememberCoroutineScope()
    var size by remember { mutableLongStateOf(0L) }
    var oldest by remember { mutableStateOf<String?>(null) }
    var show by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, show, reload) {
        io {
            // A forced write first, else the last 15 s batch wouldn't be in the file yet (DESIGN.md §15.5).
            if (show) deps.eventLog.flush("view")
            size = deps.eventLog.sizeBytes(ch)
            oldest = deps.eventLog.oldest(ch)
            lines = if (show) deps.eventLog.tail(ch, 200) else emptyList()
        }
    }
    val saver = rememberSaveTo("text/plain", "${ch.shortName} saved", "Couldn't save ${ch.shortName}") { uri ->
        io {
            deps.eventLog.flush("export")
            DiagFiles.writeTo(activity, uri, deps.eventLog.files(ch))
        }
    }
    Text("${ch.fileName} · ${sizeText(size)}" + (oldest?.let { " · oldest $it" } ?: " · empty"), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { show = !show }) { Text(if (show) "Hide lines" else "Show last 200 lines") }
        TextButton(onClick = { saver.launch(DiagFiles.exportName(ch)) }) { Text("Save ${ch.shortName}…") }
        TextButton(onClick = {
            scope.launch {
                io { deps.eventLog.flush("share") }
                val files = deps.eventLog.files(ch)
                if (files.isEmpty()) Toast.makeText(activity, "Nothing to share yet", Toast.LENGTH_SHORT).show()
                else activity.startActivity(DiagFiles.shareIntent(activity, files, title = "AutoNavAlerts ${ch.shortName}"))
            }
        }) { Text("Share") }
    }
    if (show) {
        if (lines.isEmpty()) Help("(empty)") else MonoLines(lines, 320.dp)
        TextButton(onClick = { reload++ }) { Text("Refresh") }
    }
}

/** Monospace lines in a box that scrolls both ways, opened at the bottom (the newest lines). */
@Composable
private fun MonoLines(lines: List<String>, maxHeight: Dp) {
    val v = rememberScrollState()
    val h = rememberScrollState()
    LaunchedEffect(lines, v.maxValue) { v.scrollTo(v.maxValue) }
    Text(
        lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        softWrap = false,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(v).horizontalScroll(h),
    )
}

/** "512 B", "37 KB", "1.2 MB" for a file size. */
internal fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}
