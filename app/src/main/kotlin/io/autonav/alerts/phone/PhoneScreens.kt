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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.libraries.navigation.NavigationApi
import io.autonav.alerts.core.alerts.AlertTemplateRenderer
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.alerts.TemplateValues
import io.autonav.alerts.core.alerts.Units
import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.location.RateCurve
import io.autonav.alerts.core.pgw.PgwConfig
import io.autonav.alerts.core.poi.SpeedUnit
import io.autonav.alerts.core.poi.importers.CsvImporter
import io.autonav.alerts.core.poi.importers.CsvProfile
import io.autonav.alerts.data.CameraReportEntity
import io.autonav.alerts.data.PoiSetEntity
import io.autonav.alerts.data.ReportKind
import io.autonav.alerts.data.ReportStatus
import io.autonav.alerts.reports.Transcriber
import io.autonav.alerts.data.StageCols
import io.autonav.alerts.pgw.ReportSubmitWorker
import io.autonav.alerts.reports.CameraReportRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

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

@Composable
fun SetupScreen(activity: Activity, deps: PhoneDeps) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var keyText by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var termsOk by remember { mutableStateOf(NavigationApi.areTermsAccepted(activity.application)) }
    LaunchedEffect(refresh) { hasKey = deps.settings.mapsApiKey() != null }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("AutoNavAlerts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        Text("Navigation runs on your car screen. Set things up here first.", Modifier.padding(horizontal = 16.dp))

        Section("1. Google Maps API key") {
            Text(if (hasKey) "A key is set." else "No key yet. The key needs the Navigation SDK and Places API (New) enabled.")
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

        Section("2. Permissions") {
            val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            val bg = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            val notif = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
            val contacts = granted(ctx, Manifest.permission.READ_CONTACTS)
            Text("Location: ${if (fine) "allowed" else "needed"} · Background: ${if (bg) "allowed" else "recommended"}")
            val mic = granted(ctx, Manifest.permission.RECORD_AUDIO)
            Text("Notifications: ${if (notif) "allowed" else "needed"} · Microphone: ${if (mic) "allowed" else "for spoken report comments"}")
            Text("Contacts: ${if (contacts) "allowed" else "optional"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val ps = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) ps += Manifest.permission.POST_NOTIFICATIONS
                    ps += Manifest.permission.RECORD_AUDIO
                    permLauncher.launch(ps.toTypedArray())
                }) { Text("Allow") }
                OutlinedButton(enabled = fine && !bg, onClick = {
                    if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) {
                    Text("Allow all the time")
                }
            }
            Text("\"All the time\" keeps camera alerts working while another app (like music) fills the car screen.",
                style = MaterialTheme.typography.bodySmall)
        }

        Section("3. Navigation terms") {
            Text(if (termsOk) "Accepted." else "Google's navigation terms must be accepted on the phone before using the car screen.")
            if (!termsOk) Button(onClick = {
                NavigationApi.showTermsAndConditionsDialog(activity, "AutoNavAlerts") { accepted -> termsOk = accepted }
            }) { Text("Review and accept") }
        }

        Section("4. Android Auto") {
            Text("This app is sideloaded. In Android Auto settings, tap the version number repeatedly to enable developer mode, " +
                "then turn on \"Unknown sources\" in developer settings. Choose AutoNavAlerts as your navigation app on the car screen.")
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent("com.google.android.projection.gearhead.SETTINGS").setPackage("com.google.android.projection.gearhead")) }
            }) { Text("Open Android Auto settings") }
        }
    }
}

// --- POI sets ----------------------------------------------------------------------------------

@Composable
fun PoiSetsScreen(deps: PhoneDeps) {
    val sets by deps.pois.activeSets.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var editing by remember { mutableStateOf<PoiSetEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    editing?.let { set -> SetEditor(deps, set, onDone = { editing = null }); return }

    Column {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("POI sets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import file") }
        }
        Text("Free and other POI files are loaded by hand. PocketGPSWorld syncs on its own (Cameras tab).",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary) }
        LazyColumn {
            items(sets, key = { it.id }) { set ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { editing = set }) {
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
    }

    importUri?.let { uri ->
        ImportDialog(deps, uri, onClose = { msg -> importUri = null; message = msg })
    }
}

@Composable
private fun ImportDialog(deps: PhoneDeps, uri: Uri, onClose: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileName = remember { deps.pois.displayName(uri) }
    var name by remember { mutableStateOf(fileName.substringBeforeLast('.')) }
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
                OutlinedTextField(name, { name = it }, label = { Text("Set name") }, singleLine = true)
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
            Button(enabled = !busy && result == null && name.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val profile = if (isCsv) CsvProfile(
                        delimiter = CsvImporter.detectDelimiter(preview.joinToString("\n")),
                        hasHeader = preview.firstOrNull()?.any { it.isLetter() } == true,
                        latCol = latCol.toIntOrNull() ?: 0, lonCol = lonCol.toIntOrNull() ?: 1,
                        nameCol = nameCol.toIntOrNull(), speedCol = speedCol.toIntOrNull(),
                        bearingCol = bearingCol.toIntOrNull(), dirTypeCol = dirCol.toIntOrNull(),
                    ) else null
                    val out = runCatching { deps.pois.importFile(uri, name, csvProfile = profile) }
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
    var pickIcon by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { units = deps.settings.current().units }
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
        Section("Show and alert") {
            SwitchRow("Show on map", set.showOnMap) { set = set.copy(showOnMap = it) }
            SwitchRow("Alerts", set.alertsEnabled) { set = set.copy(alertsEnabled = it) }
            SwitchRow("Sound (makes the 100 yd passing alert compulsory)", set.soundEnabled) { set = set.copy(soundEnabled = it) }
        }
        Section("Icon") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(deps.icons.bitmap(set.defaultIconId, 128).asImageBitmap(), null, Modifier.size(48.dp))
                OutlinedButton(onClick = { pickIcon = !pickIcon }) { Text("Choose") }
                OutlinedButton(onClick = { iconPicker.launch(arrayOf("image/*")) }) { Text("From file") }
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
        StageEditor("1st alert", set.firstAlert, canDisable = false, unitLabel, ::toUser, ::fromUser) { set = set.copy(firstAlert = it) }
        StageEditor("2nd alert", set.secondAlert, canDisable = true, unitLabel, ::toUser, ::fromUser) { set = set.copy(secondAlert = it) }
        Section("Passing alert (100 yd)") {
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
            Button(enabled = error == null, onClick = { scope.launch { deps.pois.updateSet(set); onDone() } }) { Text("Save") }
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

/** DESIGN.md §11.1 validation: 2nd ≥ 100 yd + 50 m; 1st ≥ 2nd + 100 m (FIXED distances). */
private fun validate(set: PoiSetEntity): String? {
    val s2 = set.secondAlert
    val s1 = set.firstAlert
    if (s2.enabled && s2.metres < 142) return "The 2nd alert must be at least 155 yd (142 m), outside the 100 yd passing alert."
    if (s2.enabled && s1.metres < s2.metres + 100) return "The 1st alert must be at least 110 yd (100 m) before the 2nd."
    return null
}

@Composable
private fun StageEditor(
    title: String, stage: StageCols, canDisable: Boolean, unit: String,
    toUser: (Int) -> Int, fromUser: (Int) -> Int, onChange: (StageCols) -> Unit,
) {
    Section(title) {
        if (canDisable) SwitchRow("On", stage.enabled) { onChange(stage.copy(enabled = it)) }
        if (stage.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(stage.mode == DistanceMode.FIXED, { onChange(stage.copy(mode = DistanceMode.FIXED)) }, label = { Text("Distance") })
                FilterChip(stage.mode == DistanceMode.TIME, { onChange(stage.copy(mode = DistanceMode.TIME, seconds = stage.seconds ?: 30)) }, label = { Text("Seconds ahead") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(toUser(stage.metres).toString(), { v -> v.toIntOrNull()?.let { onChange(stage.copy(metres = fromUser(it))) } },
                    label = { Text(if (stage.mode == DistanceMode.TIME) "Minimum ($unit)" else "Distance ($unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                if (stage.mode == DistanceMode.TIME) {
                    OutlinedTextField((stage.seconds ?: 30).toString(), { v -> v.toIntOrNull()?.let { onChange(stage.copy(seconds = it)) } },
                        label = { Text("Seconds") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
            }
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
    val state by deps.settings.pgwState.collectAsState(initial = io.autonav.alerts.settings.PgwState())
    val settings by deps.settings.settings.collectAsState(initial = io.autonav.alerts.settings.AppSettings())
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var hasLogin by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { hasLogin = deps.settings.pgwCredentials() != null }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

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
        Section("Manual import") {
            Text("You can also download the iGO file yourself and import it on the POIs tab.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PgwConfig.BASE + PgwConfig.CAMERAS_PATH))) }) {
                Text("Open download page")
            }
        }
    }
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
                io.autonav.alerts.data.LimitSource.GOOGLE -> "from Google"
                io.autonav.alerts.data.LimitSource.CAMERA -> "from camera data"
                io.autonav.alerts.data.LimitSource.USER -> "set by you"
                io.autonav.alerts.data.LimitSource.NONE -> "unknown"
            }
            Text("Speed limit: ${r.speedLimit?.let { "$it mph" } ?: "not set"} ($src)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(20, 30, 40, 50, 60, 70).forEach { v ->
                    FilterChip(r.speedLimit == v, { save(r.copy(speedLimit = v, limitSource = io.autonav.alerts.data.LimitSource.USER)) }, label = { Text("$v") })
                }
            }

            // Direction
            Text("Direction of travel: ${r.heading?.roundToInt()?.let { "$it° (${compass(it)})" } ?: "unknown"}", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                fun turn(by: Int) = save(r.copy(heading = (((r.heading ?: 0f) + by) % 360 + 360) % 360, directionType =
                    if (r.directionType == io.autonav.alerts.core.poi.DirectionType.ANY) io.autonav.alerts.core.poi.DirectionType.ONE_WAY else r.directionType))
                OutlinedButton(onClick = { turn(-45) }) { Text("−45°") }
                OutlinedButton(onClick = { turn(-5) }) { Text("−5°") }
                OutlinedButton(onClick = { turn(5) }) { Text("+5°") }
                OutlinedButton(onClick = { turn(45) }) { Text("+45°") }
                OutlinedButton(onClick = { turn(180) }) { Text("Reverse") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(r.directionType == io.autonav.alerts.core.poi.DirectionType.ONE_WAY,
                    { save(r.copy(directionType = io.autonav.alerts.core.poi.DirectionType.ONE_WAY)) }, label = { Text("One way") })
                FilterChip(r.directionType == io.autonav.alerts.core.poi.DirectionType.BOTH,
                    { save(r.copy(directionType = io.autonav.alerts.core.poi.DirectionType.BOTH)) }, label = { Text("Both ways") })
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
fun SettingsScreen(deps: PhoneDeps) {
    val s by deps.settings.settings.collectAsState(initial = io.autonav.alerts.settings.AppSettings())
    val scope = rememberCoroutineScope()
    fun set(block: (io.autonav.alerts.settings.AppSettings) -> io.autonav.alerts.settings.AppSettings) = scope.launch { deps.settings.update(block) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Section("Units") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.units == Units.IMPERIAL, { set { it.copy(units = Units.IMPERIAL) } }, label = { Text("mph, miles, yards") })
                FilterChip(s.units == Units.METRIC, { set { it.copy(units = Units.METRIC) } }, label = { Text("km/h, km, m") })
            }
        }
        Section("Alerts") {
            SwitchRow("Voice", s.voiceOn) { v -> set { it.copy(voiceOn = v) } }
            SwitchRow("Chime before the passing alert", s.chimeOn) { v -> set { it.copy(chimeOn = v) } }
            OverLimitSlider(s.overspeedPct) { p -> set { it.copy(overspeedPct = p) } }
        }
        Section("Speed and location source") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.speedSource == io.autonav.alerts.settings.SpeedSource.CAR_WHEN_AVAILABLE,
                    { set { it.copy(speedSource = io.autonav.alerts.settings.SpeedSource.CAR_WHEN_AVAILABLE) } }, label = { Text("Car when available") })
                FilterChip(s.speedSource == io.autonav.alerts.settings.SpeedSource.PHONE_GPS,
                    { set { it.copy(speedSource = io.autonav.alerts.settings.SpeedSource.PHONE_GPS) } }, label = { Text("Phone only") })
            }
            Text("Uses the car's own speed and GPS from Android Auto when the car shares them, and falls back to the phone otherwise. The map itself always follows the phone's location.",
                style = MaterialTheme.typography.bodySmall)
        }
        Section("Speed panel") {
            SwitchRow("Show speed and limit on the map", s.speedPanelOn) { v -> set { it.copy(speedPanelOn = v) } }
        }
        Section("GPS rate") {
            val c = s.rateCurve
            Text("1 Hz below ${c.minMph.roundToInt()} mph, rising to ${c.maxHz} Hz at ${c.maxMph.roundToInt()} mph.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { v -> FilterChip(c.maxHz == v, { set { it.copy(rateCurve = RateCurve(c.minMph, c.maxMph, 1, v)) } }, label = { Text("max $v Hz") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(40.0, 50.0, 60.0).forEach { v -> FilterChip(c.maxMph == v, { set { it.copy(rateCurve = RateCurve(c.minMph, v, 1, c.maxHz)) } }, label = { Text("full at ${v.roundToInt()}") }) }
            }
        }
        if (io.autonav.alerts.BuildConfig.DEBUG) Section("Bench testing (debug builds)") {
            Text("Speed of the Simulate drive on the car screen. 1× is real time; alerts are tested at the speed shown.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 2f, 4f).forEach { v -> FilterChip(s.simulationSpeed == v, { set { it.copy(simulationSpeed = v) } }, label = { Text("${v.roundToInt()}×") }) }
            }
        }
        Section("About") {
            Text("Apache License 2.0. Written with the help of Claude (AI-assisted code).", style = MaterialTheme.typography.bodySmall)
        }
    }
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

/** "−0.1%", "0%", "5%"… for the over-limit setting. */
private fun pctLabel(p: Double): String {
    val v = p * 100
    val text = if (kotlin.math.abs(v - Math.rint(v)) < 1e-9) "${Math.rint(v).roundToInt()}%" else "%.1f%%".format(v)
    return text.replace("-", "−")
}
