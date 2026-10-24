// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.diag

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.autonav.alerts.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Singleton

/**
 * Getting the diagnostics off the phone (DESIGN.md §15.5, §13): Save through the system file
 * picker and Share through the `${applicationId}.diag` FileProvider (`res/xml/diag_paths.xml`).
 * Nothing leaves the phone unless the user does one of these (§15.5 Privacy).
 *
 * Plain helpers with no state, called from the phone's Diagnostics screen on the main thread
 * (the copy in [writeTo] is small: two files of at most 1 MB each, or one recording). The
 * caller flushes [EventLog] or [DriveRecorder] first so the export has the last 15 s.
 */
object DiagFiles {
    /** The FileProvider authority declared in the manifest; the application id keeps it unique per install. */
    const val AUTHORITY = BuildConfig.APPLICATION_ID + ".diag"

    /** `autonavalerts-debug-nav-2026-10-13.log`, for the file picker. */
    fun exportName(channel: EventLog.Channel, nowMs: Long = System.currentTimeMillis()): String =
        "autonavalerts-${channel.shortName}-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(nowMs)) + ".log"

    /** Copies [files] in order (rotation first, then current) into the picked document. */
    fun writeTo(context: Context, uri: Uri, files: List<File>) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Couldn't open the document")
        out.use { o -> files.forEach { f -> f.inputStream().use { it.copyTo(o) } } }
    }

    /** A content URI another app may read for one file under `files/diag/` (the provider's paths file). */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, AUTHORITY, file)

    /** A share-sheet chooser for one or more files under `files/diag/`. */
    fun shareIntent(context: Context, files: List<File>, mime: String = "text/plain", title: String = "AutoNavAlerts diagnostics"): Intent {
        val uris = files.map { uriFor(context, it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mime
        send.putExtra(Intent.EXTRA_SUBJECT, title)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }
}

/**
 * The alert engine's state as text for Settings → Diagnostics → **Engine now** and the bench's
 * `--ez engine true` (DESIGN.md §15.5): `AlertCoordinator.snapshot()`, bound in [DiagModule].
 */
fun interface DiagnosticsSnapshot {
    fun snapshot(): String
}

/** Hilt wiring for the engine snapshot, so the phone app can inject it without depending on the alerts package. */
@Module
@InstallIn(SingletonComponent::class)
object DiagModule {
    /** Lazy, so the phone app doesn't build the engine stack just to inject the snapshot. */
    @Provides
    @Singleton
    fun diagnosticsSnapshot(alerts: dagger.Lazy<io.autonav.alerts.alerts.AlertCoordinator>): DiagnosticsSnapshot =
        DiagnosticsSnapshot { alerts.get().snapshot() }
}
