// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.core.alerts.AlertStage
import io.autonav.alerts.core.alerts.AlertTemplateRenderer
import io.autonav.alerts.core.poi.Grid
import io.autonav.alerts.core.poi.ImportResult
import io.autonav.alerts.core.poi.ImportRow
import io.autonav.alerts.core.poi.ZoneRole
import io.autonav.alerts.core.poi.importers.CsvImporter
import io.autonav.alerts.core.poi.importers.CsvProfile
import io.autonav.alerts.core.poi.importers.FormatSniffer
import io.autonav.alerts.core.poi.importers.GpxImporter
import io.autonav.alerts.core.poi.importers.IgoTxtImporter
import io.autonav.alerts.core.poi.importers.KmlImporter
import io.autonav.alerts.core.poi.importers.LazyBytes
import io.autonav.alerts.core.poi.importers.Ov2Importer
import io.autonav.alerts.core.poi.importers.PgwOv2
import io.autonav.alerts.core.poi.importers.PoiFormat
import io.autonav.alerts.core.poi.importers.ZipEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The result of an import, shown on the phone and logged (DESIGN.md §5.1 "error report"). [setId] is the
 * new ACTIVE set (the first one for a zip of several); [imported] and [skipped] are row counts;
 * [errorSamples] the first 20 bad lines; [failed] true when nothing was stored (§5.1: over 20% bad rows,
 * no good rows, or a file that couldn't be read).
 */
data class ImportOutcome(
    val setId: Long?,
    val imported: Int,
    val skipped: Int,
    val errorSamples: List<String>,
    val failed: Boolean,
    val message: String,
)

/** Stored stage columns → the core `AlertStage` the engine takes (§11.1). */
fun StageCols.toStage() = AlertStage(enabled, mode, metres, seconds)
/** Core `AlertStage` → stored columns; seconds are rounded to the integer column. */
fun AlertStage.toCols() = StageCols(enabled, mode, metres, seconds?.let { kotlin.math.round(it).toInt() })

/**
 * POI storage and the import pipeline with staging and atomic swap (DESIGN.md §4.4, §5, §5.2). A Hilt
 * singleton shared by the car service and the phone UI (§2); every method runs on Dispatchers.IO (Room, file
 * copies). Callers: `PgwSyncWorker` (`importPgwZip`), the phone's import flow and set editor, `AlertCoordinator`
 * (`poisInCells`, `sets`, `categories`, `changes`), `CameraReportRepository` (the "My reports" set, §8.5),
 * `SettingsBackup` (§13.4) and `App` at startup (`cleanupStaging`, `purgeExpired`).
 * Upholds hard rule 9 (AGENTS.md §3.1): every file in a PGW or zip download becomes its own set, never
 * merged, named after its file with no prompt, with the zip's matching `.bmp`/`.png` as its icon.
 * Memory rule (§1.3): big files are streamed from disk and parsed record by record, never held whole.
 */
@Singleton
class PoiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val icons: IconRepository,
) {
    // extraBufferCapacity = 1 so tryEmit from a non-suspending context never drops the signal while a collector is slow.
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits after any import, swap, delete or settings change, so caches reload (§4.4 step 5 `setsChanged`): the alert cache and the marker layer collect it. */
    val changes: SharedFlow<Unit> = _changes

    /** Live list of ACTIVE sets, ordered by name, for the phone's tabs and the car's layer screen. */
    val activeSets = db.poiSets().observeActive()

    /** Pokes [changes]. Public so the phone can signal a change it made through another path. */
    fun notifyChanged() { _changes.tryEmit(Unit) }

    /** Startup: drops any STAGING set an interrupted import left behind (§4.4 step 4). */
    suspend fun cleanupStaging() = db.poiSets().deleteStaging()

    /**
     * The alert cache's reload (§4.3): every visible POI in the given cells, from ACTIVE and enabled sets, not
     * hidden by a report, not expired. Chunked at 900 cells because SQLite allows 999 bound variables per statement.
     */
    suspend fun poisInCells(cells: List<Long>): List<PoiEntity> =
        if (cells.isEmpty()) emptyList() else cells.chunked(900).flatMap { db.pois().inCells(it, System.currentTimeMillis()) }

    /** ACTIVE sets by id, for the icon and template resolution of §4.2 and for the backup. */
    suspend fun sets(): Map<Long, PoiSetEntity> = db.poiSets().active().associateBy { it.id }
    /** Every category by id, for the resolution of §4.2. */
    suspend fun categories(): Map<Long, PoiCategoryEntity> = db.categories().all().associateBy { it.id }

    // Set and category edits from the phone (and the backup restore); each one signals the caches.
    suspend fun updateSet(set: PoiSetEntity) { db.poiSets().update(set); notifyChanged() }
    suspend fun updateSets(sets: List<PoiSetEntity>) { db.poiSets().updateAll(sets); notifyChanged() }
    /** Deletes a set and, by cascade, its categories and POIs (§4.1). */
    suspend fun deleteSet(id: Long) { db.poiSets().delete(id); notifyChanged() }

    // --- File import ---------------------------------------------------------------------------

    /** The file name Android shows for a picked document (SAF gives opaque URIs), or a last-resort "import". */
    fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "import"

    /**
     * Imports a file the user picked on the phone (DESIGN.md §5): a single POI file becomes one set named after
     * the file; a zip becomes one set per POI file inside it, never merged (hard rule 9). [format] overrides the
     * sniffed format; [csvProfile] is the wizard's column mapping (§5.7) and is required for CSV; [replaceSetId]
     * is the set a **Re-import** replaces, keeping its settings (§4.4). Runs on Dispatchers.IO.
     */
    suspend fun importFile(
        uri: Uri,
        format: PoiFormat? = null,
        csvProfile: CsvProfile? = null,
        replaceSetId: Long? = null,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val outcome = if (name.endsWith(".zip", true)) {
            // Copied to a temp file (streamed), then each file is read from it only when needed.
            withTemp(uri, ".zip") { tmp ->
                ZipEntries.read(tmp).use { entries ->
                    val poiFiles = poiFilesIn(entries)
                    if (poiFiles.isEmpty()) return@withContext fail("No POI file found in the zip")
                    // Every file in the zip becomes its own set (never merged), with its own icon if supplied.
                    if (poiFiles.size > 1) return@withContext importZipFiles(name, entries, poiFiles, csvProfile)
                    val entryName = poiFiles.keys.first()
                    val data = poiFiles.getValue(entryName)
                    importOne(entryName, data.copyOf(minOf(512, data.size)), format, { data.inputStream() }, csvProfile, replaceSetId)
                }
            }
        } else {
            // A single file is streamed to a temp file and parsed from it, never held whole in memory (§1.3).
            withTemp(uri, ".poi") { tmp ->
                val head = tmp.inputStream().use { i -> ByteArray(512).let { b -> b.copyOf(i.read(b).coerceAtLeast(0)) } }
                importOne(name, head, format, { tmp.inputStream() }, csvProfile, replaceSetId, sizeBytes = tmp.length())
            }
        }
        outcome ?: fail("Couldn't open the file")
    }

    /**
     * One picked POI file, as itself or as the one POI file in a zip: its format ([format], else sniffed from
     * [name] and the first bytes in [head]), parsed from [open] and stored as a set named after the file (never
     * asked for). [sizeBytes] is the file's size on disk for the KML/GPX cap of [MAX_XML_BYTES]; a zip entry,
     * already inflated, passes 0 and isn't capped.
     */
    private suspend fun importOne(
        name: String, head: ByteArray, format: PoiFormat?, open: () -> java.io.InputStream,
        csvProfile: CsvProfile?, replaceSetId: Long?, sizeBytes: Long = 0L,
    ): ImportOutcome {
        val fmt = format ?: FormatSniffer.detect(name, head) ?: return fail("Unknown file format")
        if ((fmt == PoiFormat.KML || fmt == PoiFormat.GPX) && sizeBytes > MAX_XML_BYTES) {
            return fail("This ${fmt.name} file is too large (over ${MAX_XML_BYTES / 1_000_000} MB). Split it, or use CSV or OV2.")
        }
        val result = parse(fmt, open, csvProfile) ?: return fail("CSV files need a column mapping")
        return storeImport(result, setNameFor(name), PoiSourceType.FILE, fmt.name, replaceSetId, categoryNamer = { it })
    }

    /** An outcome for a file that couldn't be read at all. */
    private fun fail(msg: String) = ImportOutcome(null, 0, 0, emptyList(), true, msg)

    /**
     * Parses from a fresh stream; OV2, iGO and CSV are read record by record (§1.3 Memory). KML and GPX are
     * read as whole documents, which is why [importFile] caps them at [MAX_XML_BYTES]. Returns null only for a
     * CSV with no column mapping. iGO is Latin-1: PGW's files are ASCII and a stray byte must not abort the import.
     */
    private fun parse(fmt: PoiFormat, open: () -> java.io.InputStream, csv: CsvProfile?): ImportResult? = when (fmt) {
        PoiFormat.IGO_TXT -> open().bufferedReader(Charsets.ISO_8859_1).use { IgoTxtImporter().parse(it) }
        PoiFormat.KML -> open().use { KmlImporter().parse(it) }
        PoiFormat.GPX -> open().use { GpxImporter().parse(it) }
        PoiFormat.OV2 -> open().use { Ov2Importer().parse(it) }
        PoiFormat.CSV -> csv?.let { p -> open().bufferedReader(Charsets.UTF_8).use { CsvImporter(p).parse(it) } }
    }

    /**
     * The POI files in a picked zip (by extension, leaving out licence and readme files), as a lazy map: each
     * entry is inflated from the zip on disk only when read, and not kept (§5.2 "Never all in memory").
     */
    private fun poiFilesIn(entries: Map<String, ByteArray>): Map<String, ByteArray> = LazyBytes.select(entries) { it.takeIf(::isPoiFile) }

    /**
     * Streams a picked document to a temp file in the cache dir, so it can be parsed from disk (§1.3 Memory),
     * runs [block] on it and deletes it; null, with nothing run, if the document can't be opened.
     */
    private inline fun <T> withTemp(uri: Uri, suffix: String, block: (File) -> T): T? {
        val tmp = File.createTempFile("import", suffix, context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return null
            return block(tmp)
        } finally { tmp.delete() }
    }

    /** Names of the POI files in a picked zip, for the import dialog's list (§5.2: from the zip's directory alone). Empty for a non-zip. */
    suspend fun zipPoiFileNames(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        if (!displayName(uri).endsWith(".zip", true)) return@withContext emptyList()
        // Only the zip's directory is read; no file in it is inflated.
        withTemp(uri, ".zip") { tmp -> runCatching { ZipEntries.names(tmp).filter(::isPoiFile) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    /**
     * The set name from a file name, never asked for (§5, hard rule 9): "Croydon_Tramlink_Stop_PGPSW.ov2" →
     * "Croydon Tramlink Stop". Drops the extension and a trailing "PGPSW" suffix, turns underscores into spaces.
     */
    fun setNameFor(fileName: String): String = Companion.setNameFor(fileName)

    /**
     * One set per POI file in a zip, never merged (§5, hard rule 9); re-importing the same zip updates those sets
     * instead of adding new ones, matched by zip name and file name. A file whose stem matches a `.bmp`/`.png`
     * in the zip gets that image as the set's icon (§5). Files that fail are listed in the message; the import
     * counts as failed only when no set was made.
     */
    private suspend fun importZipFiles(
        zipName: String,
        entries: Map<String, ByteArray>,
        poiFiles: Map<String, ByteArray>,
        csvProfile: CsvProfile?,
    ): ImportOutcome {
        val outs = poiFiles.map { (fileName, data) -> fileName to importZipFile(zipName, entries, fileName, data, csvProfile) }
        return combine(outs) { imported, sets, skipped, problems ->
            "Imported $imported POIs from $zipName into $sets sets" +
                (if (skipped > 0) " ($skipped rows skipped)" else "") +
                (if (problems.isNotEmpty()) ". Not imported: ${problems.joinToString("; ")}" else "")
        }
    }

    /** One POI file of a zip as its own set (§5): sniffed, parsed, given the zip's matching icon, and stored over the set an earlier import of the same zip and file made. */
    private suspend fun importZipFile(zipName: String, entries: Map<String, ByteArray>, fileName: String, data: ByteArray, csvProfile: CsvProfile?): ImportOutcome {
        val fmt = FormatSniffer.detect(fileName, data.copyOf(minOf(512, data.size))) ?: return fail("unknown format")
        val result = parse(fmt, { data.inputStream() }, csvProfile) ?: return fail("CSV needs a column mapping")
        val stem = fileName.substringBeforeLast('.')
        val iconBytes = entries.entries.firstOrNull { (n, _) ->
            n.substringBeforeLast('.').equals(stem, true) && n.lowercase().let { it.endsWith(".bmp") || it.endsWith(".png") }
        }?.value
        val icon = iconBytes?.let { icons.saveImportedIcon(stem, it) }
        // Keyed by zip and file, so another zip with a file of the same name gets its own set.
        // A set imported before 2026-10-13 has the old key without the zip: the first re-import claims it.
        val key = "${fmt.name}:file:$zipName/$fileName"
        val existing = db.poiSets().byFormat(key) ?: db.poiSets().byFormat("${fmt.name}:file:$fileName")
        return storeImport(
            result, setNameFor(fileName), PoiSourceType.FILE, key, existing?.id,
            categoryNamer = { it }, defaultIcon = icon, fileIcon = icon != null,
        )
    }

    /**
     * The outcome of a zip of several files from each file's own, paired with the name a problem is listed under:
     * the counts over the files that made a set, "<name>: <message>" for the rest, failed only when no set was
     * made; [message] gets the POIs imported, the sets made, the rows skipped and the problems.
     */
    private fun combine(outs: List<Pair<String, ImportOutcome>>, message: (Int, Int, Int, List<String>) -> String): ImportOutcome {
        val ok = outs.map { it.second }.filter { !it.failed }
        val problems = outs.filter { it.second.failed }.map { (name, out) -> "$name: ${out.message}" }
        val imported = ok.sumOf { it.imported }
        val skipped = ok.sumOf { it.skipped }
        return ImportOutcome(ok.firstOrNull()?.setId, imported, skipped, problems, ok.isEmpty(), message(imported, ok.size, skipped, problems))
    }

    // --- PGW import ----------------------------------------------------------------------------

    /**
     * Imports a PocketGPSWorld download (DESIGN.md §5.2, §5.2a; called by `PgwSyncWorker` on Dispatchers.IO).
     * Every file in the zip becomes its own set, never merged (hard rule 9): the OV2 package gives one per type
     * and speed ("PGW fixed cameras · 30 mph"), the iGO package one per type file. Sets are matched by file
     * across syncs, so the user's icon, alert and sound choices are kept, and PGW sets no longer in the download
     * are removed. [version] is the release from `Release.txt` or the feed (§6.2a step 3), stored on each set.
     * The zip is read through its central directory and inflated entry by entry (§5.2 "Reading the zip").
     */
    suspend fun importPgwZip(zip: File, version: String): ImportOutcome = withContext(Dispatchers.IO) {
        ZipEntries.read(zip).use { entries -> importPgwEntries(zip, entries, version) }
    }

    /** The body of [importPgwZip] over the zip's lazy entry map. */
    private suspend fun importPgwEntries(zip: File, entries: Map<String, ByteArray>, version: String): ImportOutcome {
        // Entry names only: no PGW data is logged (§6.4).
        Log.i("PgwImport", "Zip ${zip.length()} bytes, entries: ${entries.keys}")
        // TomTom OV2 (PGW's own naming carries direction, type, id and speed) or iGO TXT, one set per family.
        val ov2 = PgwOv2.files(entries)
        val files: Map<String, () -> ImportResult> = if (ov2.isNotEmpty()) {
            // One set per file in the download (e.g. "PGW fixed cameras · 30 mph").
            ov2.mapValues { (_, bytes) -> { PgwOv2.parse(bytes) } }
        } else {
            ZipEntries.pgwTypeFiles(entries).mapValues { (_, bytes) -> { IgoTxtImporter().parse(bytes.inputStream().bufferedReader(Charsets.ISO_8859_1)) } }
        }
        val fileFormat = if (ov2.isNotEmpty()) PoiFormat.OV2.name else PoiFormat.IGO_TXT.name
        // The _X2 icons' licence text travels with the download and must be kept beside the icons (§5.2a).
        entries.entries.firstOrNull { it.key.equals(PgwOv2.ICON_LICENCE_FILE, true) }
            ?.let { icons.savePgwIconLicence(it.value) }
        if (files.isEmpty()) return fail(
            "No camera files in the download. The zip contained: " +
                entries.keys.joinToString().ifEmpty { "nothing readable (${zip.length()} bytes)" },
        )
        val keptFormats = HashSet<String>()
        val outs = files.map { (stem, load) ->
            val info = PgwFile.of(stem, ov2 = ov2.isNotEmpty())
            // The import key that matches this file to its set on the next sync (§5.2a "matched by file stem").
            val format = "$fileFormat:$stem"
            keptFormats += format
            // Parsed now, one file at a time, so only one file's rows are in memory (§1.3).
            val result = load()
            val existing = db.poiSets().byFormat(format)
            // The icon shipped with this file, if any (OV2 downloads include one per file).
            val fileIcon = PgwOv2.iconFor(stem, entries)?.let { icons.savePgwIcon(stem, it) }
            info.setName to storeImport(
                result, info.setName, info.source, format, existing?.id,
                categoryNamer = info.categoryName, version = version,
                alertsDefault = existing?.alertsEnabled ?: info.alertsByDefault, defaultIcon = fileIcon ?: info.icon,
                fileIcon = fileIcon != null,
            )
        }
        // Remove PGW sets from an older package layout (e.g. the single combined set), or the other package
        // when the user switched between OV2 and iGO (§5.2 "PGW sets from an older layout are removed").
        db.poiSets().pgwSets().filter { it.format !in keptFormats }.forEach { db.poiSets().delete(it.id) }
        notifyChanged()
        return combine(outs) { imported, sets, _, problems ->
            if (sets == 0) problems.joinToString("; ")
            else "Imported $imported cameras in $sets sets" + if (problems.isNotEmpty()) " (${problems.size} failed)" else ""
        }
    }

    // --- Shared storage ------------------------------------------------------------------------

    /**
     * The staging swap of DESIGN.md §4.4 for one parsed file: a STAGING set is inserted, the old set's
     * categories are carried over by `sourceCode` (keeping the user's icon and template choices), the rows go
     * in in batches of 2,000, then one transaction deletes the old set and marks the new one ACTIVE. On any
     * failure the staging set is deleted and the old set is untouched. [replaceSetId] is the set this one
     * replaces (null for a first import); [categoryNamer] turns a source code into a display name;
     * [alertsDefault] and [defaultIcon] seed a new set; [version] is the PGW release.
     */
    private suspend fun storeImport(
        result: ImportResult,
        setName: String,
        source: PoiSourceType,
        format: String,
        replaceSetId: Long?,
        categoryNamer: (String) -> String,
        version: String? = null,
        alertsDefault: Boolean = true,
        defaultIcon: String? = null,
        /** True when [defaultIcon] came from the download: categories then use the set's icon. */
        fileIcon: Boolean = false,
    ): ImportOutcome {
        val samples = result.errors.take(20).map { "Line ${it.lineNumber}: ${it.reason}" }
        // The importer has already applied the 20% / no-good-rows rule (§5.1); nothing is stored for a failed file.
        if (result.failed) {
            return ImportOutcome(null, result.rows.size, result.errors.size, samples, true,
                "Import failed: ${result.errors.size} bad rows out of ${result.totalConsidered}")
        }
        val old = replaceSetId?.let { db.poiSets().get(it) }
        val now = System.currentTimeMillis()
        // A sync refreshes the icon only if the user hasn't picked their own (built-in defaults and
        // PGW-supplied icons are replaced; anything else is kept).
        val keepUserIcon = old != null && !(old.defaultIconId in IconRepository.BUILTIN || IconRepository.isSuppliedIcon(old.defaultIconId))
        val base = old?.let { o -> if (defaultIcon != null && !keepUserIcon) o.copy(defaultIconId = defaultIcon) else o }
            ?: defaultSet(setName, source, format, alertsDefault, now).let { d -> defaultIcon?.let { d.copy(defaultIconId = it) } ?: d }
        // The old set's name is kept: the user may have renamed it (§5 "Sets can be renamed").
        val staging = base.copy(
            id = 0, name = old?.name ?: setName, format = format, state = SetState.STAGING, replacesSetId = old?.id,
            poiCount = result.rows.size, version = version, lastUpdated = now,
        )
        val stagingId = db.poiSets().insert(staging)
        try {
            // §4.4 step 2: the old categories' customisations move to the new set, matched on sourceCode.
            val oldCats = old?.let { db.categories().forSet(it.id) }?.associateBy { it.sourceCode } ?: emptyMap()
            val catIds = HashMap<String, Long>()
            for (code in result.rows.mapNotNull { it.categoryCode }.distinct()) {
                val prev = oldCats[code]
                val isPgw = source == PoiSourceType.PGW || source == PoiSourceType.PGW_PENDING
                val cat = (prev?.let { p -> if (fileIcon && p.iconId in IconRepository.BUILTIN) p.copy(iconId = null) else p } ?: PoiCategoryEntity(
                    setId = stagingId, sourceCode = code, name = categoryNamer(code),
                    iconId = if (isPgw && !fileIcon) IconRepository.pgwIconFor(code, source) else null,
                    zoneRole = if (isPgw && (code in IgoTxtImporter.PGW_AVERAGE_SPEED_CODES || PgwOv2.isAverageSpeed(code))) ZoneRole.ZONE_POINT else ZoneRole.NONE,
                )).copy(id = 0, setId = stagingId)
                catIds[code] = db.categories().insert(cat)
            }
            // §4.4 step 1: rows in batches of 2,000, one transaction per batch, so memory stays flat.
            result.rows.chunked(2_000).forEach { chunk ->
                db.withTransaction { db.pois().insertAll(chunk.map { it.toEntity(stagingId, catIds) }) }
            }
            // §4.4 step 2: the swap itself, atomic. The old set's delete cascades to its POIs and categories.
            db.withTransaction {
                if (old != null) db.poiSets().delete(old.id)
                db.poiSets().update(staging.copy(id = stagingId, state = SetState.ACTIVE, replacesSetId = null))
            }
        } catch (t: Throwable) {
            // §4.4 step 3: the staging set goes (cascading), the old set was never touched.
            db.poiSets().delete(stagingId)
            throw t
        }
        notifyChanged()
        return ImportOutcome(stagingId, result.rows.size, result.errors.size, samples, false,
            "Imported ${result.rows.size} POIs" + if (result.errors.isNotEmpty()) " (${result.errors.size} rows skipped)" else "")
    }

    /** An importer's row → a stored POI: the grid cell is computed here (§4.3); the PGW camera id rides in `extra["pgwId"]` (§5.2a). */
    private fun ImportRow.toEntity(setId: Long, cats: Map<String, Long>) = PoiEntity(
        setId = setId, categoryId = categoryCode?.let { cats[it] }, lat = lat, lon = lon, name = name,
        speedLimit = speed, speedUnit = speedUnit, directionType = dirType, bearing = bearing,
        gridCell = Grid.cellOf(lat, lon), sourceId = extra["pgwId"],
    )

    /**
     * What each PGW file becomes (DESIGN.md §5.2 table, §5.2a): its set name, source (PGW or PGW_PENDING),
     * built-in icon when the download has none, whether alerts start on, and how its codes are named.
     */
    internal data class PgwFile(
        val setName: String,
        val source: PoiSourceType,
        val icon: String,
        val categoryName: (String) -> String,
        val alertsByDefault: Boolean = true,
    ) {
        companion object {
            /** OV2 stems are "<family>_<speed>" ("gatso_30", "specs_var"): the family picks the base and the speed part names the set (§5.2a). */
            fun of(stem: String, ov2: Boolean = false): PgwFile {
                if (!ov2) return of(stem)
                val (family, part) = PgwOv2.splitStem(stem)
                val base = of(family)
                val speed = PgwOv2.speedLabel(part)
                return base.copy(
                    setName = if (speed != null) "${base.setName} · $speed" else base.setName,
                    categoryName = PgwOv2::categoryName,
                )
            }

            /** The iGO package's file stems (§5.2 table); "sc" is the older single-file package 61; anything else gets a generic name. */
            fun of(stem: String): PgwFile = when (stem) {
                "gatso" -> PgwFile("PGW fixed cameras", PoiSourceType.PGW, IconRepository.CAMERA_FIXED, { c ->
                    if (c == "11") "Fixed camera (type 11)" else "Fixed camera"
                })
                "mobile" -> PgwFile("PGW mobile camera sites", PoiSourceType.PGW, IconRepository.CAMERA_MOBILE, { "Mobile camera site" })
                "redlight" -> PgwFile("PGW red light cameras", PoiSourceType.PGW, IconRepository.CAMERA_REDLIGHT, { "Red light camera" })
                "specs" -> PgwFile("PGW average speed cameras (SPECS)", PoiSourceType.PGW, IconRepository.CAMERA_AVERAGE, { "Average speed camera" })
                // Pending sites alert by default (owner, 2026-10-23, §5.2) but stay a separate set so they can be turned off.
                "pmobile" -> PgwFile("PGW pending mobile sites (unverified)", PoiSourceType.PGW_PENDING, IconRepository.CAMERA_PENDING, { "Pending mobile site" })
                "sc" -> PgwFile("PocketGPSWorld cameras (all)", PoiSourceType.PGW, IconRepository.CAMERA_FIXED, IgoTxtImporter::categoryName)
                else -> PgwFile("PGW ${stem.replaceFirstChar { it.uppercase() }}", PoiSourceType.PGW, IconRepository.CAMERA_FIXED, IgoTxtImporter::categoryName)
            }
        }
    }

    /** A new set with the design defaults: the §7.2 templates, the §11.1 stages, pin icon for a hand import and a camera for the rest. */
    fun defaultSet(name: String, source: PoiSourceType, format: String, alerts: Boolean, now: Long) = Companion.defaultSet(name, source, format, alerts, now)

    // --- "My reports" set (DESIGN.md §8.5) -------------------------------------------------------

    /** The one local REPORT set, created on first use with a mobile-camera icon; its POIs are the markers reports put on the map. */
    suspend fun reportsSetId(): Long {
        db.poiSets().bySource(PoiSourceType.REPORT)?.let { return it.id }
        val now = System.currentTimeMillis()
        return db.poiSets().insert(
            defaultSet("My reports", PoiSourceType.REPORT, "REPORT", true, now).copy(defaultIconId = IconRepository.CAMERA_MOBILE),
        )
    }

    // Single-POI changes for camera reports (§8.5); each one signals the caches so the map and engine see it.
    suspend fun addReportPoi(entity: PoiEntity): Long = db.pois().insert(entity).also { notifyChanged() }
    suspend fun updatePoi(p: PoiEntity) { db.pois().update(p); notifyChanged() }
    suspend fun deletePoi(id: Long) { db.pois().delete(id); notifyChanged() }
    /** "Camera not there": hides an existing camera without deleting it (§8.5). */
    suspend fun setHidden(poiId: Long, hidden: Boolean) { db.pois().setHidden(poiId, hidden); notifyChanged() }
    suspend fun poi(id: Long) = db.pois().get(id)
    /** Startup: drops reported mobile sites past their 24 h (§8.5). */
    suspend fun purgeExpired() = db.pois().deleteExpired(System.currentTimeMillis())

    // Internal so the pure helpers below are JVM-tested (PoiRepositoryNamingTest); the instance forwarders keep the callers unchanged.
    internal companion object {
        /** KML and GPX are read as a whole document; above this a phone could run out of memory (§1.3 Memory: refused above 50 MB). */
        const val MAX_XML_BYTES = 50_000_000L

        /** Which zip entries count as POI files: a known extension, and not a licence, release or readme text file. */
        fun isPoiFile(name: String): Boolean {
            val lower = name.lowercase()
            return listOf(".txt", ".csv", ".kml", ".gpx", ".ov2").any { lower.endsWith(it) } &&
                listOf("licence", "license", "release", "readme").none { lower.contains(it) }
        }

        fun setNameFor(fileName: String): String =
            fileName.substringBeforeLast('.').replace(Regex("(?i)[_ -]?pgpsw$"), "").replace('_', ' ').trim()
                .ifEmpty { fileName }

        fun defaultSet(name: String, source: PoiSourceType, format: String, alerts: Boolean, now: Long) = PoiSetEntity(
            name = name, source = source, format = format,
            alertsEnabled = alerts,
            defaultIconId = if (source == PoiSourceType.FILE) IconRepository.PIN else IconRepository.CAMERA_FIXED,
            firstTemplate = AlertTemplateRenderer.DEFAULT_FIRST,
            secondTemplate = AlertTemplateRenderer.DEFAULT_SECOND,
            passingTemplate = AlertTemplateRenderer.DEFAULT_PASSING,
            firstAlert = AlertStage.DEFAULT_FIRST.toCols(),
            secondAlert = AlertStage.DEFAULT_SECOND.toCols(),
            lastUpdated = now,
        )
    }
}
