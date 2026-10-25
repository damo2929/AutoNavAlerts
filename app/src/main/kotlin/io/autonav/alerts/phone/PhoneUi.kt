// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The building blocks the phone screens share (DESIGN.md §13; docs/SETTINGS.md): the settings card,
// the switch row, the three text styles every card uses (help, ✓ and error lines), the Show/Hide fold,
// chip groups, the confirm dialog, the icon-row card, the "save to a file" launcher, the browser
// link, the background-work button and the IO hop. Each one draws exactly what the screens drew inline before it existed, so the phone UI
// (wording, order, spacing, colours) is unchanged. All run on the main thread inside MainActivity's
// composition. Nothing here touches the car screen or the alert engine, so no hard rule of
// AGENTS.md §3.1 is at stake; the Backup card's helpers keep rule 6 (no camera data in the file)
// because the data they write comes from `SettingsBackup` and `DiagFiles` unchanged.

/** One settings card: a title and its controls, the shape every tab uses (docs/SETTINGS.md §1 "one scrolling page of cards"). */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A labelled switch filling the row: the shape of every on/off setting on the phone. */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** True when the runtime permission [p] is granted. */
internal fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/** The one short line of help under a control (DESIGN.md §13 item 9): `bodySmall`, in the given colour (the error colour for a warning). */
@Composable
internal fun Help(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) =
    Text(text, modifier, color = color, style = MaterialTheme.typography.bodySmall)

/** A "✓ done" or result line in the primary colour, in the body size. */
@Composable
internal fun Ok(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = MaterialTheme.colorScheme.primary)

/** An error line in the error colour, in the body size. */
@Composable
internal fun Err(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = MaterialTheme.colorScheme.error)

/** The one-line summary row of a folded card, with its Show/Hide button at the end; [summary] fills the rest with `weight(1f)`. */
@Composable
internal fun ShowHideRow(open: Boolean, onToggle: () -> Unit, summary: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        summary()
        TextButton(onClick = onToggle) { Text(if (open) "Hide" else "Show") }
    }
}

/**
 * A card folded behind Show/Hide (DESIGN.md §13 items 11 and 12): closed, it is [title] and the one-line
 * [summary]; open, [content] follows inside the same card. The fold starts closed and owns its flag.
 */
@Composable
internal fun Fold(title: String, summary: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Section(title) {
        ShowHideRow(open, { open = !open }) { Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
        if (open) content()
    }
}

/** One row of `FilterChip`s, one per option, with the [selected] one filled; the shape of every either-or setting on the phone. */
@Composable
internal fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, spacing: Dp = 8.dp, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        options.forEach { (value, label) -> FilterChip(selected == value, { onSelect(value) }, label = { Text(label) }) }
    }
}

/** Opens [url] in the browser (the Console pages of DESIGN.md §13.2, the PGW pages, the licences). */
internal fun Context.openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

/** The deep link to Android Auto's settings (DESIGN.md §13 items 1 and 12); nothing happens if Android Auto isn't installed. */
@Composable
internal fun OpenAndroidAutoButton() {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        runCatching { ctx.startActivity(Intent("com.google.android.projection.gearhead.SETTINGS").setPackage("com.google.android.projection.gearhead")) }
    }) { Text("Open Android Auto settings") }
}

/**
 * A `CreateDocument` launcher for the Save… buttons (DESIGN.md §13.4, §15.5): the user picks where the
 * file goes, [work] writes it, and a long toast says [okMsg] or "[errPrefix]: <reason>". Nothing happens
 * when the picker is cancelled. [work] runs in the composition's scope; it moves to IO itself if it must.
 */
@Composable
internal fun rememberSaveTo(mime: String, okMsg: String, errPrefix: String, work: suspend (Uri) -> Unit): ManagedActivityResultLauncher<String, Uri?> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) scope.launch {
            val msg = runCatching { work(uri) }.fold({ okMsg }, { "$errPrefix: ${it.message}" })
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * An `AlertDialog` with a Cancel text button (unless [cancel] is off: the Engine now view, whose one button
 * closes it) and one confirming action: a filled `Button` labelled [confirmLabel], or a `TextButton` when
 * [textConfirm] is set (the Restore dialog). Dismissing the dialog any other way counts as Cancel.
 */
@Composable
internal fun ConfirmDialog(
    title: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    textConfirm: Boolean = false, cancel: Boolean = true, text: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text,
        confirmButton = {
            if (textConfirm) TextButton(onClick = onConfirm) { Text(confirmLabel) } else Button(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = if (cancel) ({ TextButton(onClick = onDismiss) { Text("Cancel") } }) else null,
    )
}

/**
 * A filled (or [outlined]) button labelled [label] whose click runs [work] on [scope], the screen's own, so
 * the work finishes even when its result takes the button off the screen (Save and test, Send all): the
 * shape of every "do it and come back" button on the phone.
 */
@Composable
internal fun Btn(label: String, scope: CoroutineScope, enabled: Boolean = true, outlined: Boolean = false, work: suspend () -> Unit) {
    val onClick: () -> Unit = { scope.launch { work() } }
    if (outlined) OutlinedButton(onClick, enabled = enabled) { Text(label) } else Button(onClick, enabled = enabled) { Text(label) }
}

/** [block] on `Dispatchers.IO`: the file reads and log flushes the phone screens keep off the main thread (DESIGN.md §2). */
internal suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)

/**
 * A tappable card with a 40 dp icon, a title and a subtitle line, then [trailing] (a switch, an arrow):
 * the set card and the camera-type group card (DESIGN.md §13 items 3 and 8). [modifier] is the card's own
 * (its width and outer padding); the click is added after it.
 */
@Composable
internal fun IconRowCard(
    icon: ImageBitmap, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Card(modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(icon, null, Modifier.size(40.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            trailing()
        }
    }
}

/** The end labels under a slider, [min] at the left and [max] at the right, with [middle] (e.g. a Reset button) between them. */
@Composable
internal fun RangeLabels(min: String, max: String, middle: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth()) {
        Help(min, Modifier.weight(1f))
        middle()
        Help(max)
    }
}
