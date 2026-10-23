// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.phone

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

// --- Setup -------------------------------------------------------------------------------------

/**
 * The setup steps (Maps key, permissions, navigation terms, Android Auto), shown at the top of the
 * Settings tab (DESIGN.md §13). Emits its sections into the caller's scrolling column.
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
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    // Re-read on every result and whenever the app comes back to the front (e.g. from Settings).
    val st = remember(refresh) { PermState.read(ctx) }
    // Setup done: the four sections fold into one row, so the settings start at the top (DESIGN.md §13 item 1).
    val complete = hasKey && termsOk && st.missingForeground().isEmpty() && st.background
    var showSetup by remember { mutableStateOf(false) }
    if (complete) Section("Setup") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("✓ Maps key saved, permissions allowed, navigation terms accepted.", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { showSetup = !showSetup }) { Text(if (showSetup) "Hide" else "Show") }
        }
    }
    if (complete && !showSetup) return

    Section("Google Maps API key") {
        if (hasKey) Text("✓ Your key is saved.", color = MaterialTheme.colorScheme.primary)
        else Text("No key yet: the map, search and navigation won't work until you add one.",
            color = MaterialTheme.colorScheme.error)
        CreateKeyWithGoogle(activity, deps, onCreated = { refresh++ })
        HorizontalDivider()
        Text("Or paste a key you already have. It needs the Navigation SDK and Places API (New) enabled.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(keyText, { keyText = it }, label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = keyText.isNotBlank(), onClick = {
                scope.launch { deps.settings.setMapsApiKey(keyText); deps.navigator.keyChanged(); keyText = ""; refresh++ }
            }) { Text("Save key") }
            if (hasKey) OutlinedButton(onClick = {
                scope.launch { deps.settings.setMapsApiKey(null); deps.navigator.keyChanged(); refresh++ }
            }) { Text("Remove") }
        }
    }

    Section("Permissions") {
        PermLine("Location (needed)", st.fine)
        PermLine("Location all the time (recommended)", st.background)
        PermLine("Notifications (needed for alerts behind other apps)", st.notifications)
        PermLine("Microphone (spoken report comments)", st.mic)
        PermLine("Car speed (speed panel uses the car's own speed)", st.carSpeed)
        PermLine("Contacts (optional: drive to a contact's address)", st.contacts)
        val missing = st.missingForeground()
        when {
            missing.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { askedOnce = true; permLauncher.launch(missing.toTypedArray()) }) { Text("Allow") }
                if (askedOnce) OutlinedButton(onClick = { openAppSettings(ctx) }) { Text("Open app settings") }
            }
            !st.background -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    askedOnce = true
                    if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Allow all the time") }
                if (askedOnce) OutlinedButton(onClick = { openAppSettings(ctx) }) { Text("Open app settings") }
            }
            else -> Text("✓ All permissions allowed.", color = MaterialTheme.colorScheme.primary)
        }
        // Contacts are optional, so they have their own button rather than being part of Allow (DESIGN.md §8.3).
        if (!st.contacts) {
            OutlinedButton(onClick = { permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }) { Text("Allow contacts") }
        } else {
            TextButton(onClick = {
                scope.launch { deps.contacts.clearCache(); Toast.makeText(ctx, "Contact locations cleared", Toast.LENGTH_SHORT).show() }
            }) { Text("Clear contact locations") }
        }
        if (askedOnce && (missing.isNotEmpty() || !st.background)) {
            Text("If no prompt appears, Android has stopped asking. Use \"Open app settings\" → Permissions to allow them there.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("\"All the time\" keeps camera alerts working while another app (like music) fills the car screen.",
            style = MaterialTheme.typography.bodySmall)
    }

    Section("Navigation terms") {
        if (termsOk) {
            Text("✓ Accepted" + (termsAcceptedAt?.let { " on " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "") +
                ". You won't be asked again.", color = MaterialTheme.colorScheme.primary)
        } else {
            Text("Google's navigation terms must be accepted on the phone before using the car screen.")
            termsMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!hasKey) Text("Add your Google Maps API key above first.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = hasKey, onClick = {
                termsMessage = null
                runCatching {
                    NavigationApi.showTermsAndConditionsDialog(activity, "AutoNavAlerts") { accepted ->
                        if (accepted) {
                            // Record it ourselves too, so it's a one-time step.
                            scope.launch { deps.navigator.recordTermsAccepted(); refresh++ }
                            termsOk = true
                        } else {
                            termsMessage = "You declined the terms. Navigation can't start until they're accepted."
                        }
                    }
                }.onFailure { termsMessage = "Couldn't open the terms: ${it.message}" }
            }) { Text("Review and accept") }
        }
    }

    Section("Android Auto") {
        Text("This app is sideloaded. In Android Auto settings, tap the version number repeatedly to enable developer mode, " +
            "then turn on \"Unknown sources\" in developer settings. Choose AutoNavAlerts as your navigation app on the car screen.")
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent("com.google.android.projection.gearhead.SETTINGS").setPackage("com.google.android.projection.gearhead")) }
        }) { Text("Open Android Auto settings") }
    }
}

/** The steps behind Settings → Connecting to a car (DESIGN.md §13 item 12). */
@Composable
private fun CarConnectionSection(activity: Activity) {
    @Composable
    fun Steps(title: String, steps: List<String>) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
    }
    Steps("On a car (wired or wireless)", listOf(
        "In Android Auto settings, tap Version about 10 times to turn on developer mode.",
        "Android Auto ⋮ → Developer settings → tick Unknown sources (this app isn't from Play).",
        "Android Auto → Customise Launcher → make sure AutoNavAlerts is ticked.",
        "Open AutoNavAlerts on the phone once: Maps key saved, permissions allowed, navigation terms accepted.",
        "Connect to the car: a data cable for a wired car, or Bluetooth and Wi-Fi on for wireless. Open the car's app grid and pick AutoNavAlerts.",
        "Not there? Android Auto only picks up a sideloaded app after a fresh install or update when it's next connected: disconnect and reconnect once.",
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
    OutlinedButton(onClick = {
        runCatching { activity.startActivity(Intent("com.google.android.projection.gearhead.SETTINGS").setPackage("com.google.android.projection.gearhead")) }
    }) { Text("Open Android Auto settings") }
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

    fun createWith(accessToken: String, billing: String? = null) {
        token = accessToken
        busy = true; stop = null; done = null
        scope.launch {
            try {
                val c = deps.keyCreator.create(accessToken, billing) { progress = it }
                deps.settings.setMapsApiKey(c.key)
                deps.navigator.keyChanged()
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

    Text("Let the app make your own key in your own Google Cloud account, using a Google account on this phone.",
        style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy && stop !is MapsKeyCreator.Stop.NotSetUp, onClick = { confirm = true }) {
        Text(if (busy) "Creating key…" else "Create a key with my Google account")
    }
    progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    done?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    stop?.let { s ->
        Text(s.message.orEmpty(), color = MaterialTheme.colorScheme.error)
        when (s) {
            is MapsKeyCreator.Stop.ChooseBilling -> s.accounts.forEach { a ->
                OutlinedButton(onClick = { token?.let { createWith(it, a.name) } ?: signIn() }) { Text(a.displayName) }
            }
            is MapsKeyCreator.Stop.NoBilling -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MapsKeyCreator.BILLING_CREATE_URL))) }) {
                    Text("Add billing account")
                }
                Button(onClick = { signIn() }) { Text("Try again") }
            }
            is MapsKeyCreator.Stop.NotSetUp -> Unit // the guided steps below open instead
            is MapsKeyCreator.Stop.Refused -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.consoleUrl?.let { url ->
                    OutlinedButton(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Open in Google Cloud") }
                }
                Button(onClick = { signIn() }) { Text("Try again") }
            }
        }
    }

    var guided by remember { mutableStateOf(false) }
    LaunchedEffect(stop) { if (stop is MapsKeyCreator.Stop.NotSetUp) guided = true }
    TextButton(onClick = { guided = !guided }) {
        Text((if (guided) "▲ " else "▼ ") + "Set it up yourself in Google Cloud (step by step)")
    }
    if (guided) GuidedKeySetup(activity, deps, onSaved = { done = "✓ Key saved."; stop = null; onCreated() })

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Create your Maps key?") },
        text = {
            Text("After you choose a Google account, the app will, in that account's Google Cloud:\n\n" +
                "• create a project called \"AutoNavAlerts\" (or reuse it)\n" +
                "• link it to your billing account\n" +
                "• turn on the Navigation SDK, Maps SDK for Android and Places API (New)\n" +
                "• create a key that only this app can use\n\n" +
                "Google Maps needs a billing account, and Google may charge for use beyond its free allowance. " +
                "Only the key is kept on this phone, never your Google sign-in.")
        },
        confirmButton = { Button(onClick = { confirm = false; signIn() }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

/** Current runtime-permission state for the setup sections. */
private data class PermState(
    val fine: Boolean, val background: Boolean, val notifications: Boolean, val mic: Boolean, val contacts: Boolean, val carSpeed: Boolean,
) {
    fun missingForeground(): List<String> = buildList {
        if (!fine) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        if (!notifications && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (!mic) add(Manifest.permission.RECORD_AUDIO)
        if (!carSpeed) add(CAR_SPEED)
    }

    companion object {
        fun read(ctx: Context) = PermState(
            fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
            background = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS),
            mic = granted(ctx, Manifest.permission.RECORD_AUDIO),
            contacts = granted(ctx, Manifest.permission.READ_CONTACTS),
            carSpeed = granted(ctx, CAR_SPEED),
        )

        /** Android Auto's runtime permission for the car's speed (CarInfo speed listener). */
        const val CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"
    }
}

@Composable
private fun PermLine(label: String, ok: Boolean) {
    Text((if (ok) "✓ " else "✗ ") + label, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
}

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
    fun open(url: String) = activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    fun copy(label: String, text: String) =
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

    @Composable
    fun Step(n: Int, text: String, buttons: @Composable () -> Unit) {
        Text("$n. $text", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Each Open button shows the page in your browser, signed in to your Google account. Do the steps in order: " +
            "Google Cloud applies each page to the project you created in step 1.", style = MaterialTheme.typography.bodySmall)
        Step(1, "Create a project, for example \"AutoNavAlerts\".") {
            OutlinedButton(onClick = { open(MapsKeyCreator.CONSOLE_PROJECT_CREATE) }) { Text("Open") }
        }
        Step(2, "Link a billing account to it (add one first if you have none). Google Maps needs billing.") {
            OutlinedButton(onClick = { open(MapsKeyCreator.CONSOLE_BILLING) }) { Text("Open") }
        }
        Step(3, "Turn on these APIs: press Enable on each page. The Routes API is only needed for energy-efficient routes.") {
            OutlinedButton(onClick = { open(MapsKeyCreator.CONSOLE_NAVIGATION) }) { Text("Navigation SDK") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open(MapsKeyCreator.CONSOLE_MAPS_ANDROID) }) { Text("Maps SDK for Android") }
            OutlinedButton(onClick = { open(MapsKeyCreator.CONSOLE_PLACES) }) { Text("Places API (New)") }
        }
        OutlinedButton(onClick = { open(MapsKeyCreator.CONSOLE_ROUTES) }) { Text("Routes API") }
        Step(4, "Create credentials → API key. Then edit the key: under Application restrictions pick Android apps and add this " +
            "app's package name and SHA-1; under API restrictions pick the APIs from step 3.") {
            OutlinedButton(onClick = { open(MapsKeyCreator.CREDENTIALS_URL) }) { Text("Open") }
        }
        Text("Package: ${activity.packageName}", style = MaterialTheme.typography.bodySmall)
        Text("SHA-1: $sha1", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copy("Package name", activity.packageName) }) { Text("Copy package") }
            OutlinedButton(onClick = { copy("SHA-1", sha1) }) { Text("Copy SHA-1") }
        }
        Step(5, "Copy the key in Google Cloud, then come back here.") {
            Button(onClick = {
                val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()?.trim().orEmpty()
                if (MapsKeyCreator.looksLikeKey(text)) {
                    scope.launch { deps.settings.setMapsApiKey(text); deps.navigator.keyChanged(); pasteMessage = null; onSaved() }
                } else {
                    pasteMessage = "The clipboard doesn't hold a Google API key (they start with \"AIza\"). Copy the key in Google Cloud first."
                }
            }) { Text("Paste key from clipboard") }
        }
        pasteMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

// --- POI sets ----------------------------------------------------------------------------------

@Composable
fun PoiSetsScreen(deps: PhoneDeps) {
    // Only files imported by hand; camera sets live on the Cameras tab (DESIGN.md §13).
    val sets by deps.pois.activeSets.collectAsState(initial = emptyList())
    val fileSets = sets.filter { it.source == PoiSourceType.FILE }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var editing by remember { mutableStateOf<PoiSetEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<PoiSetEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    editing?.let { set -> SetEditor(deps, set, onDone = { editing = null }); return }

    Column {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("POI sets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import file") }
        }
        Text("POI files you've imported yourself. Camera sets are on the Cameras tab.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary) }
        if (fileSets.isEmpty()) Text("No POI files imported yet. Use Import file to add one.", Modifier.padding(16.dp))
        LazyColumn {
            items(fileSets, key = { it.id }) { set ->
                SetCard(deps, set, onEdit = { editing = set }, onDelete = { confirmDelete = set })
            }
        }
        if (fileSets.isNotEmpty()) Text("Tip: swipe a set left to delete it.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }

    confirmDelete?.let { set -> DeleteSetDialog(deps, set, onClose = { msg -> confirmDelete = null; msg?.let { message = it } }) }

    importUri?.let { uri ->
        ImportDialog(deps, uri, onClose = { msg -> importUri = null; message = msg })
    }
}

/** A set's card: tap to edit, switch to turn it on or off, swipe left to delete (springs back until confirmed). */
@Composable
private fun SetCard(deps: PhoneDeps, set: PoiSetEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            swipe.reset()
        }
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
        Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(deps.icons.bitmap(set.defaultIconId, 96).asImageBitmap(), null, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(set.name, style = MaterialTheme.typography.titleMedium)
                    Text("${set.poiCount} POIs · ${set.source.name.lowercase().replace('_', ' ')}" +
                        (set.version?.let { " · v$it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = set.enabled, onCheckedChange = { on -> scope.launch { deps.pois.updateSet(set.copy(enabled = on)) } })
            }
        }
    }
}

/** Confirms deleting a set; [onClose] gets a message when it was deleted, or null when cancelled. */
@Composable
private fun DeleteSetDialog(deps: PhoneDeps, set: PoiSetEntity, onClose: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val pgw = set.source == PoiSourceType.PGW || set.source == PoiSourceType.PGW_PENDING
    AlertDialog(
        onDismissRequest = { onClose(null) },
        title = { Text("Delete ${set.name}?") },
        text = {
            Text("Its ${set.poiCount} POIs will be removed from this phone." +
                if (pgw) " The next PocketGPSWorld sync will download them again. To stop that, turn off automatic updates on the Cameras tab." else "")
        },
        confirmButton = {
            Button(onClick = {
                scope.launch { deps.pois.deleteSet(set.id); onClose("Deleted ${set.name}") }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { onClose(null) }) { Text("Cancel") } },
    )
}

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
    val preview = remember {
        if (isCsv) ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r -> r.lineSequence().take(4).toList() } ?: emptyList() else emptyList()
    }
    var latCol by remember { mutableStateOf("0") }
    var lonCol by remember { mutableStateOf("1") }
    var nameCol by remember { mutableStateOf("2") }
    var speedCol by remember { mutableStateOf("") }
    var bearingCol by remember { mutableStateOf("") }
    var dirCol by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose(null) },
        title = { Text("Import $fileName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Set names come from the files themselves (DESIGN.md §5): nothing to type.
                val names = if (zipFiles.isNotEmpty()) zipFiles else listOf(fileName)
                Text(if (names.size > 1) "${names.size} sets will be created, one per file:" else "Will be imported as:",
                    style = MaterialTheme.typography.bodyMedium)
                names.take(12).forEach { Text("• " + deps.pois.setNameFor(it), style = MaterialTheme.typography.bodySmall) }
                if (names.size > 12) Text("…and ${names.size - 12} more", style = MaterialTheme.typography.bodySmall)
                if (isCsv) {
                    Text("CSV columns (0 = first column). Leave blank if not present.", style = MaterialTheme.typography.bodySmall)
                    preview.forEach { Text(it.take(80), style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ColField("Lat", latCol) { latCol = it }; ColField("Lon", lonCol) { lonCol = it }; ColField("Name", nameCol) { nameCol = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ColField("Speed", speedCol) { speedCol = it }; ColField("Heading", bearingCol) { bearingCol = it }; ColField("Dir", dirCol) { dirCol = it }
                    }
                }
                result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && result == null, onClick = {
                busy = true
                scope.launch {
                    val profile = if (isCsv) CsvProfile(
                        delimiter = CsvImporter.detectDelimiter(preview.joinToString("\n")),
                        hasHeader = preview.firstOrNull()?.any { it.isLetter() } == true,
                        latCol = latCol.toIntOrNull() ?: 0, lonCol = lonCol.toIntOrNull() ?: 1,
                        nameCol = nameCol.toIntOrNull(), speedCol = speedCol.toIntOrNull(),
                        bearingCol = bearingCol.toIntOrNull(), dirTypeCol = dirCol.toIntOrNull(),
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

@Composable
private fun ColField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.size(width = 86.dp, height = 64.dp))
}

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
            Text("Taken from the file when imported. Change it here if you like.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Show and alert") {
            SwitchRow("Show on map", set.showOnMap) { set = set.copy(showOnMap = it) }
            SwitchRow("Alerts", set.alertsEnabled) { set = set.copy(alertsEnabled = it) }
            SwitchRow("Sound (makes the 20 yd passing alert compulsory)", set.soundEnabled) { set = set.copy(soundEnabled = it) }
        }
        Section("Icon") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(deps.icons.bitmap(set.defaultIconId, 128).asImageBitmap(), null, Modifier.size(48.dp))
                OutlinedButton(onClick = { pickIcon = !pickIcon }) { Text("Choose") }
                OutlinedButton(onClick = { iconPicker.launch(arrayOf("image/*")) }) { Text("From file") }
            }
            if (IconRepository.isPgwIcon(set.defaultIconId)) {
                Text(io.autonav.alerts.core.poi.importers.PgwOv2.ICON_CREDIT, style = MaterialTheme.typography.bodySmall)
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
        Section("Passing alert (20 yd)") {
            Text("Always on while sound is enabled for this set.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Spoken text") {
            Text("Placeholders: {category} {speed} {units} {distance} {direction}", style = MaterialTheme.typography.bodySmall)
            TemplateField(deps, "1st alert", set.firstTemplate, units) { set = set.copy(firstTemplate = it) }
            TemplateField(deps, "2nd alert", set.secondTemplate, units) { set = set.copy(secondTemplate = it) }
            TemplateField(deps, "Passing", set.passingTemplate, units) { set = set.copy(passingTemplate = it) }
        }
        val error = validate(set)
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = error == null && set.name.isNotBlank(), onClick = {
                scope.launch { deps.pois.updateSet(withSliderSeconds(set, leadSeconds)); onDone() }
            }) { Text("Save") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete set") }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${set.name}?") },
        text = { Text("Its ${set.poiCount} POIs will be removed from this phone.") },
        confirmButton = { Button(onClick = { scope.launch { deps.pois.deleteSet(set.id); onDone() } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** DESIGN.md §11.1 validation: 2nd ≥ 142 m; 1st ≥ 2nd + 100 m (FIXED distances). */
private fun validate(set: PoiSetEntity): String? = io.autonav.alerts.data.StageRules.problem(set.firstAlert, set.secondAlert)

/**
 * The DB's a1_seconds/a2_seconds are no longer edited: in seconds-ahead mode they're the slider's
 * value and its half (rounded down, as the 7 s default was), so anything still reading them agrees
 * with what the slider says. Distance-mode stages keep whatever they had.
 */
private fun withSliderSeconds(set: PoiSetEntity, leadSeconds: Int): PoiSetEntity {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(stage.mode == DistanceMode.FIXED, { onChange(stage.copy(mode = DistanceMode.FIXED)) }, label = { Text("Distance") })
                FilterChip(stage.mode == DistanceMode.TIME, { onChange(stage.copy(mode = DistanceMode.TIME)) }, label = { Text("Seconds ahead") })
            }
            OutlinedTextField(toUser(stage.metres).toString(), { v -> v.toIntOrNull()?.let { onChange(stage.copy(metres = fromUser(it))) } },
                label = { Text(if (stage.mode == DistanceMode.TIME) "Minimum ($unit)" else "Distance ($unit)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (stage.mode == DistanceMode.TIME) Text(sliderNote, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TemplateField(deps: PhoneDeps, label: String, value: String, units: Units, onChange: (String) -> Unit) {
    val sample = TemplateValues(category = "Fixed camera", speed = 30, speedUnit = SpeedUnit.MPH, distanceM = 400.0, direction = "ahead")
    val rendered = AlertTemplateRenderer(units).render(value, sample)
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("“${rendered.display}”", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { deps.tts.speak(rendered.spoken, 10, -1, chime = false) }) { Text("Test speak") }
    }
}

// --- Camera database (PGW) ---------------------------------------------------------------------

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
    var editing by remember { mutableStateOf<PoiSetEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<PoiSetEntity?>(null) }
    var setMessage by remember { mutableStateOf<String?>(null) }

    editing?.let { set -> SetEditor(deps, set, onDone = { editing = null }); return }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("PocketGPSWorld", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Section("Your subscription") {
            Text("Uses your own PocketGPSWorld login. Your copy stays on this phone and is never shared or backed up.",
                style = MaterialTheme.typography.bodySmall)
            if (hasLogin) Text("Login saved.") else {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hasLogin) Button(enabled = user.isNotBlank() && pass.isNotBlank(), onClick = {
                    scope.launch {
                        deps.settings.setPgwCredentials(user.trim(), pass)
                        deps.pgw.forgetSession()
                        status = runCatching { deps.pgw.ensureLoggedIn(); "Logged in." }.getOrElse { "Login failed: ${it.message}" }
                        pass = ""; refresh++
                        deps.scheduler.schedule()
                    }
                }) { Text("Save and test") } else OutlinedButton(onClick = {
                    scope.launch { deps.settings.setPgwCredentials(null, null); deps.pgw.forgetSession(); deps.scheduler.schedule(); refresh++ }
                }) { Text("Forget login") }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        Section("Download format") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PgwDownload.entries.forEach { d ->
                    FilterChip(settings.pgwDownload == d, {
                        scope.launch { deps.settings.update { it.copy(pgwDownload = d) } }
                    }, label = { Text(d.label + if (d == PgwDownload.DEFAULT) " (recommended)" else "") })
                }
            }
            Text(settings.pgwDownload.detail, style = MaterialTheme.typography.bodySmall)
            if (state.installedPackage != null && state.installedPackage != settings.pgwDownload.idPackage) {
                Text("Press Sync now to switch to ${settings.pgwDownload.label}. Your camera sets will be replaced; " +
                    "icon and alert choices made for the old format's sets are reset.", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Database") {
            Text("Installed: ${state.installedVersion ?: "none"}")
            Text("Latest in feed: ${state.latestVersion ?: "not checked yet"}")
            state.latestSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.lastFeedCheckMs > 0) Text("Feed checked: ${df.format(Date(state.lastFeedCheckMs))}", style = MaterialTheme.typography.bodySmall)
            if (state.lastSyncMs > 0) Text("Last sync: ${df.format(Date(state.lastSyncMs))}", style = MaterialTheme.typography.bodySmall)
            state.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = hasLogin, onClick = { deps.scheduler.syncNow(force = false) }) { Text("Sync now") }
                OutlinedButton(enabled = hasLogin, onClick = { deps.scheduler.syncNow(force = true) }) { Text("Force re-download") }
            }
            SwitchRow("Update automatically when a new release is announced", settings.pgwAutoSync) { on ->
                scope.launch { deps.settings.update { it.copy(pgwAutoSync = on) }; deps.scheduler.schedule() }
            }
            SwitchRow("Wi-Fi only", settings.pgwWifiOnly) { on -> scope.launch { deps.settings.update { it.copy(pgwWifiOnly = on) } } }
        }
        CameraSets(deps, cameraGroups(sets), setMessage, onEdit = { editing = it }, onDelete = { confirmDelete = it })
        Section("Manual import") {
            Text("You can also download the iGO file yourself and import it on the POIs tab.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PgwConfig.BASE + PgwConfig.CAMERAS_PATH))) }) {
                Text("Open download page")
            }
        }
    }

    confirmDelete?.let { set -> DeleteSetDialog(deps, set, onClose = { msg -> confirmDelete = null; msg?.let { setMessage = it } }) }
}

/** One camera type's sets, batched together on the Cameras tab (DESIGN.md §13). */
private data class CameraGroup(val name: String, val sets: List<PoiSetEntity>) {
    val cameras get() = sets.sumOf { it.poiCount }
    val anyOn get() = sets.any { it.enabled }
}

/**
 * Camera sets (PGW, pending and My reports) grouped by type: the part of the name before " · ",
 * so the OV2 download's per-speed sets share one group. Pending sites come last, then My reports.
 */
private fun cameraGroups(sets: List<PoiSetEntity>): List<CameraGroup> {
    val order = listOf(PoiSourceType.PGW, PoiSourceType.PGW_PENDING, PoiSourceType.REPORT)
    return sets.filter { it.source in order }
        .groupBy { it.name.substringBefore(" · ") }
        .map { (name, members) -> CameraGroup(name, members.sortedWith(compareBy({ speedOrder(it.name) }, { it.name }))) }
        .sortedWith(compareBy({ order.indexOf(it.sets.first().source) }, { it.name }))
}

/** Sorts "· 20 mph" before "· 100 mph"; sets without a number keep name order after them. */
private fun speedOrder(name: String): Int =
    Regex("""· (\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

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
    Text(
        if (groups.isEmpty()) "None yet. Log in and press Sync now to download your cameras."
        else "%,d cameras in %d %s. Tap a type to see its sets.".format(total, setCount, if (setCount == 1) "set" else "sets"),
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
    )
    message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary) }
    groups.forEach { g ->
        val open = g.name in expanded
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { expanded = if (open) expanded - g.name else expanded + g.name }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(deps.icons.bitmap(g.sets.first().defaultIconId, 96).asImageBitmap(), null, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    Text("%,d cameras · %d %s".format(g.cameras, g.sets.size, if (g.sets.size == 1) "set" else "sets"),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(if (open) "▲" else "▼", Modifier.padding(end = 8.dp))
                Switch(checked = g.anyOn, onCheckedChange = { on ->
                    scope.launch { deps.pois.updateSets(g.sets.map { it.copy(enabled = on) }) }
                })
            }
        }
        if (open) Column(Modifier.padding(start = 16.dp)) {
            g.sets.forEach { set -> key(set.id) { SetCard(deps, set, onEdit = { onEdit(set) }, onDelete = { onDelete(set) }) } }
        }
    }
    if (groups.isNotEmpty()) Text("Tip: swipe a set left to delete it.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall)
}

// --- Reports (final review and submission happen only here, never on the car screen) ----------

@Composable
fun ReportsScreen(activity: Activity, deps: PhoneDeps) {
    val reports by deps.reports.reports.collectAsState(initial = emptyList())
    val carConnected by deps.driving.carConnected.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(carConnected) { tick++ }
    val blocked = remember(carConnected, tick, reports) { deps.driving.blockedReason() }
    val drafts = reports.filter { it.status == ReportStatus.DRAFT || it.status == ReportStatus.FAILED }

    Column {
        Text("Camera reports", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Reports made with the Report button in the car wait here. Check each one, then press Send. " +
                "Don't report warning signs or Traffic Master cameras; only report mobiles when a unit is actually there.",
                style = MaterialTheme.typography.bodySmall)
            blocked?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (drafts.size > 1) Button(enabled = blocked == null, onClick = {
                scope.launch { drafts.forEach { deps.reports.queueForSending(it) }; ReportSubmitWorker.enqueue(activity) }
            }) { Text("Send all ${drafts.size}") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (reports.isEmpty()) Text("No reports yet. Tap Report on the car screen when you see a camera.", Modifier.padding(16.dp))
        LazyColumn {
            items(reports, key = { it.id }) { r -> ReportCard(activity, deps, r, blocked) }
        }
    }
}

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
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.createdAt)) +
                " · " + r.status.name.lowercase() + (r.lastError?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
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
                val dLat = 10.0 / 111_195.0
                val dLon = 10.0 / (111_195.0 * kotlin.math.cos(Math.toRadians(r.lat)))
                listOf("N" to (dLat to 0.0), "S" to (-dLat to 0.0), "E" to (0.0 to dLon), "W" to (0.0 to -dLon)).forEach { (l, d) ->
                    OutlinedButton(onClick = { save(r.copy(lat = r.lat + d.first, lon = r.lon + d.second)) }) { Text("$l 10 m") }
                }
            }
            TextButton(onClick = {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${r.lat},${r.lon}?q=${r.lat},${r.lon}(Reported camera)")))
            }) { Text("Show on map") }

            // Speed limit (with where it came from)
            val src = when (r.limitSource) {
                LimitSource.GOOGLE -> "from Google"
                LimitSource.CAMERA -> "from camera data"
                LimitSource.USER -> "set by you"
                LimitSource.NONE -> "unknown"
            }
            Text("Speed limit: ${r.speedLimit?.let { "$it mph" } ?: "not set"} ($src)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(20, 30, 40, 50, 60, 70).forEach { v ->
                    FilterChip(r.speedLimit == v, { save(r.copy(speedLimit = v, limitSource = LimitSource.USER)) }, label = { Text("$v") })
                }
            }

            // Direction
            Text("Direction of travel: ${r.heading?.roundToInt()?.let { "$it° (${compass(it)})" } ?: "unknown"}", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                fun turn(by: Int) = save(r.copy(heading = (((r.heading ?: 0f) + by) % 360 + 360) % 360, directionType =
                    if (r.directionType == DirectionType.ANY) DirectionType.ONE_WAY else r.directionType))
                OutlinedButton(onClick = { turn(-45) }) { Text("−45°") }
                OutlinedButton(onClick = { turn(-5) }) { Text("−5°") }
                OutlinedButton(onClick = { turn(5) }) { Text("+5°") }
                OutlinedButton(onClick = { turn(45) }) { Text("+45°") }
                OutlinedButton(onClick = { turn(180) }) { Text("Reverse") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(r.directionType == DirectionType.ONE_WAY,
                    { save(r.copy(directionType = DirectionType.ONE_WAY)) }, label = { Text("One way") })
                FilterChip(r.directionType == DirectionType.BOTH,
                    { save(r.copy(directionType = DirectionType.BOTH)) }, label = { Text("Both ways") })
            }

            // Comment (speech-to-text from the car, editable)
            OutlinedTextField(r.comment.orEmpty(), { r = r.copy(comment = it) }, label = { Text("Comment") },
                modifier = Modifier.fillMaxWidth(), supportingText = {
                    Text(when (r.transcription) {
                        "PENDING" -> "Turning your recording into text…"
                        "FAILED" -> "Couldn't transcribe the recording. Play it and type the comment."
                        "DONE" -> "From your spoken comment; edit if needed."
                        else -> "Optional"
                    })
                })
            r.audioPath?.let { path ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Transcriber.play(java.io.File(path)) }) { Text("Play recording") }
                    if (r.transcription == "FAILED") OutlinedButton(onClick = {
                        scope.launch { deps.reports.transcribe(r.id) }
                    }) { Text("Try transcribing again") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = blocked == null, onClick = {
                    scope.launch { deps.reports.queueForSending(r); ReportSubmitWorker.enqueue(activity) }
                }) { Text("Send") }
                OutlinedButton(onClick = { scope.launch { deps.reports.saveReview(r) } }) { Text("Save") }
                OutlinedButton(onClick = { scope.launch { deps.reports.discard(r) } }) { Text("Discard") }
                TextButton(onClick = {
                    val s = deps.reports.toSubmission(r)
                    val text = s.formFields().filter { it.first != "SendEntry" }.joinToString("\n") { "${it.first}: ${it.second}" }
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Camera report", text))
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PgwConfig.BASE + PgwConfig.SUBMIT_PATH)))
                }) { Text("Browser") }
            }
        }
    }
}

private fun compass(deg: Int): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg + 22) % 360) / 45]

// --- Settings ----------------------------------------------------------------------------------

@Composable
fun SettingsScreen(activity: Activity, deps: PhoneDeps) {
    val s by deps.settings.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    fun set(block: (AppSettings) -> AppSettings) = scope.launch { deps.settings.update(block) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        SetupSections(activity, deps)
        Section("Car screen") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.units == Units.IMPERIAL, { set { it.copy(units = Units.IMPERIAL) } }, label = { Text("mph, miles, yards") })
                FilterChip(s.units == Units.METRIC, { set { it.copy(units = Units.METRIC) } }, label = { Text("km/h, km, m") })
            }
            SwitchRow("Show speed and limit on the map", s.speedPanelOn) { v -> set { it.copy(speedPanelOn = v) } }
            SwitchRow("Satellite map in the car", s.satelliteMap) { v -> set { it.copy(satelliteMap = v) } }
        }
        Section("Alerts") {
            SwitchRow("Voice", s.voiceOn) { v -> set { it.copy(voiceOn = v) } }
            SwitchRow("Chime before the passing alert", s.chimeOn) { v -> set { it.copy(chimeOn = v) } }
            OverLimitSlider(s.overspeedPct) { p -> set { it.copy(overspeedPct = p) } }
            AlertLeadSlider(s.alertLeadSeconds, s.units) { n -> set { it.copy(alertLeadSeconds = n) } }
            SwitchRow("Announce speed limit changes ahead", s.announceLimitChanges) { v -> set { it.copy(announceLimitChanges = v) } }
            Text("Spoken once per change, from the OpenStreetMap limits on the phone.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Speed and location source") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.speedSource == SpeedSource.CAR_WHEN_AVAILABLE,
                    { set { it.copy(speedSource = SpeedSource.CAR_WHEN_AVAILABLE) } }, label = { Text("Car when available") })
                FilterChip(s.speedSource == SpeedSource.PHONE_GPS,
                    { set { it.copy(speedSource = SpeedSource.PHONE_GPS) } }, label = { Text("Phone only") })
            }
            Text("The car's own speed and GPS through Android Auto when it shares them, else the phone.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Routing") {
            SwitchRow("Avoid tolls", s.avoidTolls) { v -> set { it.copy(avoidTolls = v) } }
            SwitchRow("Energy-efficient routes (EV)", s.evEfficientRoutes) { v -> set { it.copy(evEfficientRoutes = v) } }
            Text("With EV routes on, the preview offers Fastest and Energy-efficient (Routes API on your key) and you pick before Go.",
                style = MaterialTheme.typography.bodySmall)
        }
        Section("Speed limits (OpenStreetMap)") { OsmLimitsSection(activity, deps, s) { b -> set(b) } }
        if (io.autonav.alerts.BuildConfig.DEBUG) Section("Bench testing (debug builds)") {
            Text("Speed of the Simulate drive on the car screen; 1× is real time.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 2f, 4f).forEach { v -> FilterChip(s.simulationSpeed == v, { set { it.copy(simulationSpeed = v) } }, label = { Text("${v.roundToInt()}×") }) }
            }
        }
        Section("Backup") {
            val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) scope.launch {
                    val msg = runCatching { deps.backup.writeTo(activity, uri) }
                        .fold({ "Settings backup saved" }, { "Couldn't save the backup: ${it.message}" })
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
            }
            Text("Settings, your Maps key, your PocketGPSWorld login and each camera set's alert settings, as a JSON file. Not camera data, reports or places.",
                style = MaterialTheme.typography.bodySmall)
            Text("The file holds your key and password in plain text: keep it private.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
            restoreMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            preview?.let { p ->
                AlertDialog(
                    onDismissRequest = { preview = null },
                    title = { Text("Restore settings?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Backup made ${p.createdAt?.take(16)?.replace('T', ' ') ?: "(no date)"}" + (p.appVersion?.let { " by version $it" } ?: ""))
                            Text("Maps API key: " + if (p.hasMapsKey) "included" else "not included")
                            Text("PocketGPSWorld login: " + (p.pgwUser?.let { "included ($it)" } ?: "not included"))
                            Text("Camera set settings: ${p.sets} sets, ${p.matchingSets} of them match sets on this phone")
                            Text("This replaces your current settings. Navigation terms still need accepting on this phone.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val text = p.text
                            preview = null
                            scope.launch {
                                restoreMessage = runCatching { deps.backup.restore(text) }.fold({ r ->
                                    if (r.mapsKey) deps.navigator.keyChanged()
                                    val parts = buildList {
                                        if (r.settings) add("settings")
                                        if (r.mapsKey) add("Maps key")
                                        if (r.pgwLogin) add("PGW login")
                                        add("${r.sets} camera sets")
                                    }
                                    "Restored " + parts.joinToString(", ").replace(Regex(", ([^,]+)$"), " and $1") + "." +
                                        (if (r.setsNotFound > 0) " ${r.setsNotFound} sets weren't found: download the cameras first, then restore again." else "")
                                }, { "Couldn't restore: ${it.message}" })
                            }
                        }) { Text("Restore") }
                    },
                    dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
                )
            }
        }
        // Folded: one line when closed, the developer panel when open (DESIGN.md §13 item 11).
        var showDiag by remember { mutableStateOf(false) }
        Section("Diagnostics") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Two debug logs always on · drive recording ${if (s.driveRecordingOn) "on" else "off"}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { showDiag = !showDiag }) { Text(if (showDiag) "Hide" else "Show") }
            }
            if (showDiag) DiagnosticsSection(activity, deps, s) { b -> set(b) }
        }
        // Folded like Diagnostics: the car set-up manual (DESIGN.md §13 item 12).
        var showCar by remember { mutableStateOf(false) }
        Section("Connecting to a car") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Getting AutoNavAlerts on the car screen, and debugging on a head unit", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { showCar = !showCar }) { Text(if (showCar) "Hide" else "Show") }
            }
            if (showCar) CarConnectionSection(activity)
        }
        Section("About") {
            Text("AutoNavAlerts ${io.autonav.alerts.BuildConfig.VERSION_NAME} by Damien Dye")
            Text("Build ${io.autonav.alerts.BuildConfig.GIT_COMMIT}" + if (io.autonav.alerts.BuildConfig.DEBUG) " (debug)" else "",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/damo2929/AutoNavAlerts")))
            }) { Text("github.com/damo2929/AutoNavAlerts") }
            Text("Apache License 2.0. AI-assisted code, written with Claude Fable and Claude Opus.", style = MaterialTheme.typography.bodySmall)
            var showLicence by remember { mutableStateOf(false) }
            val licence = remember { deps.icons.pgwIconLicence() }
            if (licence != null) {
                Text("Camera set icons from your PocketGPSWorld download are © GerryC, 2007, used non-commercially with credit.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showLicence = !showLicence }) { Text(if (showLicence) "Hide icon licence" else "Show icon licence") }
                if (showLicence) Text(licence.replace("\r", ""), style = MaterialTheme.typography.bodySmall)
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
    LaunchedEffect(progress?.finished) { stats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { deps.speedLimits.stats() } }

    Text("Fills the speed panel's limit (grey dot) when Google hasn't given one. Downloaded once; nothing is looked up while driving.",
        style = MaterialTheme.typography.bodySmall)
    SwitchRow("Show OpenStreetMap limits", s.osmLimitsOn) { v -> set { it.copy(osmLimitsOn = v) } }
    val p = progress
    if (p != null && !p.finished) {
        Text(if (p.total > 0) "${p.region}: ${p.step} · ${p.done / 1_000_000} of ${p.total / 1_000_000} MB" else p.step)
        if (p.total > 0) androidx.compose.material3.LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { deps.speedLimits.cancel() }) { Text("Cancel") }
    } else {
        p?.error?.let { Text("The last download failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        SwitchRow("Download on Wi-Fi only", s.osmWifiOnly) { v -> set { it.copy(osmWifiOnly = v) } }
        Button(onClick = { deps.speedLimits.download(s.osmWifiOnly) }) {
            Text(if (stats == null) "Download speed limits for the UK" else "Update speed limits")
        }
        Text((if (s.osmWifiOnly) "About 1.1 GB on Wi-Fi only" else "About 1.1 GB, over mobile data too") + ", in the background; about 30 MB kept on the phone.",
            style = MaterialTheme.typography.bodySmall)
    }
    stats?.let { st ->
        Text("Data from ${st.dataDate} · ${"%,d".format(st.roads)} roads · ${st.bytes / 1_000_000} MB. Update every few months.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { deps.speedLimits.delete(); stats = null }) { Text("Delete downloaded limits") }
    }
    TextButton(onClick = {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")))
    }) { Text("Speed limits © OpenStreetMap contributors (ODbL), extract by Geofabrik") }
}

/** Over-limit warning threshold: −5% to +20% in 0.1% steps (DESIGN.md §11 step 9). */
@Composable
private fun OverLimitSlider(current: Double, onChange: (Double) -> Unit) {
    var v by remember(current) { mutableStateOf((current * 1000).roundToInt().toFloat()) } // tenths of a percent
    val pct = v / 10.0
    Text("Over-limit warning: ${pctLabel(pct / 100)}", style = MaterialTheme.typography.bodyLarge)
    Text(
        when {
            pct < 0 -> "Warns ${pctLabel(-pct / 100)} before you reach the limit"
            pct == 0.0 -> "Warns as soon as you go over the limit"
            else -> "Warns when you're more than ${pctLabel(pct / 100)} over the limit"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    androidx.compose.material3.Slider(
        value = v, onValueChange = { v = Math.round(it).toFloat() },
        onValueChangeFinished = { onChange(v / 1000.0) },
        valueRange = -50f..200f, steps = 249,
    )
    Row(Modifier.fillMaxWidth()) {
        Text("−5%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { v = -1f; onChange(-0.001) }) { Text("Reset to −0.1%") }
        Text("+20%", style = MaterialTheme.typography.bodySmall)
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
    Text("First alert: $v s ahead · second alert at ${halfSecondsLabel(v)}", style = MaterialTheme.typography.bodySmall)
    androidx.compose.material3.Slider(
        value = v.toFloat(), onValueChange = { v = Math.round(it) },
        onValueChangeFinished = { onChange(v) },
        valueRange = MIN_ALERT_LEAD_SECONDS.toFloat()..MAX_ALERT_LEAD_SECONDS.toFloat(),
        steps = MAX_ALERT_LEAD_SECONDS - MIN_ALERT_LEAD_SECONDS - 1,
    )
    Row(Modifier.fillMaxWidth()) {
        Text("$MIN_ALERT_LEAD_SECONDS s", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("$MAX_ALERT_LEAD_SECONDS s", style = MaterialTheme.typography.bodySmall)
    }
    Text("First alert at " + listOf(30, 50, 70).joinToString(" · ") { mph -> "$mph mph: ${leadDistanceText(mph, v, units)}" },
        style = MaterialTheme.typography.bodySmall)
}

/** "7.5 s" for 15, "8 s" for 16: half the lead time, as the 2nd alert uses it. */
private fun halfSecondsLabel(seconds: Int): String = if (seconds % 2 == 0) "${seconds / 2} s" else "${seconds / 2}.5 s"

/**
 * The 1st alert's distance at [mph] with [seconds] of lead: max(242 m floor, speed × seconds),
 * in the user's units, marked "(minimum)" when the floor applies.
 */
private fun leadDistanceText(mph: Int, seconds: Int, units: Units): String {
    val bySpeed = (mph * Geo.MPS_PER_MPH * seconds).roundToInt()
    val floored = bySpeed < FIRST_ALERT_FLOOR_M
    val metres = if (floored) FIRST_ALERT_FLOOR_M else bySpeed
    val text = if (units == Units.IMPERIAL) {
        val yards = metres / Geo.METRES_PER_YARD
        if (yards < 1000) "${yards.roundToInt()} yd" else "%.2f mi".format(yards / 1760)
    } else "$metres m"
    return if (floored) "$text (minimum)" else text
}

/** The 1st alert never fires closer than this in seconds-ahead mode (DESIGN.md §11.1). */
private const val FIRST_ALERT_FLOOR_M = 242

/** "−0.1%", "0%", "5%"… for the over-limit setting. */
private fun pctLabel(p: Double): String {
    val v = p * 100
    val text = if (kotlin.math.abs(v - Math.rint(v)) < 1e-9) "${Math.rint(v).roundToInt()}%" else "%.1f%%".format(v)
    return text.replace("-", "−")
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
    Text("debug-nav: routes, the map and the location feed. alert-debug: every camera and alert. 15-second blocks, 1 MB each.",
        style = MaterialTheme.typography.bodySmall)
    for (ch in EventLog.Channel.entries) key(ch) { DiagLogCard(activity, deps, ch, refresh) }

    var snapshot by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { deps.eventLog.clear() }
                refresh++
                Toast.makeText(activity, "Debug logs cleared", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Clear both logs") }
        Button(onClick = {
            scope.launch {
                val text = withContext(Dispatchers.Default) {
                    runCatching { deps.diagnostics.snapshot() }.getOrElse { "snapshot failed: $it" }
                }
                text.lines().forEach { deps.eventLog.record(EventLog.Channel.ALERT, "Engine", it) }
                snapshot = text
            }
        }) { Text("Engine now") }
    }
    snapshot?.let { text ->
        AlertDialog(
            onDismissRequest = { snapshot = null },
            title = { Text("Engine now") },
            text = { MonoLines(text.lines(), 420.dp) },
            confirmButton = { TextButton(onClick = { snapshot = null }) { Text("Close") } },
        )
    }

    HorizontalDivider()
    SwitchRow("Drive recording", s.driveRecordingOn) { v -> set { it.copy(driveRecordingOn = v) } }
    Text("Every GPS fix to a CSV, for replaying a drive on the bench. About 1–8 MB an hour; the newest 3 are kept.",
        style = MaterialTheme.typography.bodySmall)
    var latest by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, s.driveRecordingOn) {
        withContext(Dispatchers.IO) { latest = deps.driveRecorder.latest(); recording = deps.driveRecorder.isRecording }
    }
    Text(latest?.let { "Latest: ${it.name} · ${sizeText(it.length())}" + (if (recording) " · recording now" else "") } ?: "No recordings yet",
        style = MaterialTheme.typography.bodySmall)
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val f = latest
        if (uri != null && f != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { deps.driveRecorder.flush(); DiagFiles.writeTo(activity, uri, listOf(f)) }
            }.fold({ "Drive recording saved" }, { "Couldn't save the recording: ${it.message}" })
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }
    OutlinedButton(enabled = latest != null, onClick = { latest?.let { csvSaver.launch(it.name) } }) { Text("Save drive recording…") }

    HorizontalDivider()
    Text("Logs and recordings stay on the phone (private storage, no cloud backup) and contain where you drove. Nothing leaves unless you Save or Share.",
        style = MaterialTheme.typography.bodySmall)
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
        withContext(Dispatchers.IO) {
            if (show) deps.eventLog.flush("view")
            size = deps.eventLog.sizeBytes(ch)
            oldest = deps.eventLog.oldest(ch)
            lines = if (show) deps.eventLog.tail(ch, 200) else emptyList()
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    deps.eventLog.flush("export")
                    DiagFiles.writeTo(activity, uri, deps.eventLog.files(ch))
                }
            }.fold({ "${ch.shortName} saved" }, { "Couldn't save ${ch.shortName}: ${it.message}" })
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }
    Text("${ch.fileName} · ${sizeText(size)}" + (oldest?.let { " · oldest $it" } ?: " · empty"), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { show = !show }) { Text(if (show) "Hide lines" else "Show last 200 lines") }
        TextButton(onClick = { saver.launch(DiagFiles.exportName(ch)) }) { Text("Save ${ch.shortName}…") }
        TextButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { deps.eventLog.flush("share") }
                val files = deps.eventLog.files(ch)
                if (files.isEmpty()) Toast.makeText(activity, "Nothing to share yet", Toast.LENGTH_SHORT).show()
                else activity.startActivity(DiagFiles.shareIntent(activity, files, title = "AutoNavAlerts ${ch.shortName}"))
            }
        }) { Text("Share") }
    }
    if (show) {
        if (lines.isEmpty()) Text("(empty)", style = MaterialTheme.typography.bodySmall) else MonoLines(lines, 320.dp)
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

private fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}
