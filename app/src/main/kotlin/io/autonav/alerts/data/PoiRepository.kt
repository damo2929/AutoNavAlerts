// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.core.alerts.AlertStage
import io.autonav.alerts.core.alerts.AlertTemplateRenderer
import io.autonav.alerts.core.alerts.DistanceMode
import io.autonav.alerts.core.pgw.PgwConfig
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
import io.autonav.alerts.core.poi.importers.Ov2Importer
import io.autonav.alerts.core.poi.importers.PoiFormat
import io.autonav.alerts.core.poi.importers.ZipEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ImportOutcome(
    val setId: Long?,
    val imported: Int,
    val skipped: Int,
    val errorSamples: List<String>,
    val failed: Boolean,
    val message: String,
)

fun StageCols.toStage() = AlertStage(enabled, mode, metres, seconds)
fun AlertStage.toCols() = StageCols(enabled, mode, metres, seconds?.let { kotlin.math.round(it).toInt() })

/** POI storage, import pipeline with staging + atomic swap (DESIGN.md §4.4, §5). */
@Singleton
class PoiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val icons: IconRepository,
) {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits after any import, swap, delete or settings change, so caches reload. */
    val changes: SharedFlow<Unit> = _changes

    val activeSets = db.poiSets().observeActive()

    fun notifyChanged() { _changes.tryEmit(Unit) }

    suspend fun cleanupStaging() = db.poiSets().deleteStaging()

    suspend fun poisInCells(cells: List<Long>): List<PoiEntity> =
        if (cells.isEmpty()) emptyList() else cells.chunked(900).flatMap { db.pois().inCells(it, System.currentTimeMillis()) }

    suspend fun sets(): Map<Long, PoiSetEntity> = db.poiSets().active().associateBy { it.id }
    suspend fun categories(): Map<Long, PoiCategoryEntity> = db.categories().all().associateBy { it.id }

    suspend fun updateSet(set: PoiSetEntity) { db.poiSets().update(set); notifyChanged() }
    suspend fun updateSets(sets: List<PoiSetEntity>) { db.poiSets().updateAll(sets); notifyChanged() }
    suspend fun updateCategory(c: PoiCategoryEntity) { db.categories().update(c); notifyChanged() }
    suspend fun deleteSet(id: Long) { db.poiSets().delete(id); notifyChanged() }

    // --- File import ---------------------------------------------------------------------------

    fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "import"

    /** Reads a picked file (zip files are opened and the first matching entry used). */
    suspend fun importFile(
        uri: Uri,
        format: PoiFormat? = null,
        csvProfile: CsvProfile? = null,
        replaceSetId: Long? = null,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val entry: Pair<String, ByteArray> = if (name.endsWith(".zip", true)) {
            // Copied to a temp file (streamed), then each file is read from it only when needed.
            val tmp = copyToTemp(uri) ?: return@withContext fail("Couldn't open the file")
            try {
                ZipEntries.read(tmp).use { entries ->
                    val poiFiles = poiFilesIn(entries)
                    if (poiFiles.isEmpty()) return@withContext fail("No POI file found in the zip")
                    // Every file in the zip becomes its own set (never merged), with its own icon if supplied.
                    if (poiFiles.size > 1) return@withContext importZipFiles(name, entries, poiFiles, csvProfile)
                    poiFiles.keys.first().let { it to poiFiles.getValue(it) }
                }
            } finally { tmp.delete() }
        } else {
            // A single file is streamed to a temp file and parsed from it, never held whole in memory (§1.3).
            val tmp = copyToTemp(uri, ".poi") ?: return@withContext fail("Couldn't open the file")
            try {
                val head = tmp.inputStream().use { i -> ByteArray(512).let { b -> b.copyOf(i.read(b).coerceAtLeast(0)) } }
                val fmt = format ?: FormatSniffer.detect(name, head) ?: return@withContext fail("Unknown file format")
                if ((fmt == PoiFormat.KML || fmt == PoiFormat.GPX) && tmp.length() > MAX_XML_BYTES) {
                    return@withContext fail("This ${fmt.name} file is too large (over ${MAX_XML_BYTES / 1_000_000} MB). Split it, or use CSV or OV2.")
                }
                val result = parse(fmt, { tmp.inputStream() }, csvProfile) ?: return@withContext fail("CSV files need a column mapping")
                return@withContext storeImport(result, setNameFor(name), PoiSourceType.FILE, fmt.name, replaceSetId, categoryNamer = { it })
            } finally { tmp.delete() }
        }
        val (entryName, data) = entry
        val fmt = format ?: FormatSniffer.detect(entryName, data.copyOf(minOf(512, data.size)))
            ?: return@withContext fail("Unknown file format")
        val result = parse(fmt, { data.inputStream() }, csvProfile) ?: return@withContext fail("CSV files need a column mapping")
        // Set name comes from the file itself, never asked for.
        storeImport(result, setNameFor(entryName), PoiSourceType.FILE, fmt.name, replaceSetId, categoryNamer = { it })
    }

    private fun fail(msg: String) = ImportOutcome(null, 0, 0, emptyList(), true, msg)

    /** Parses from a fresh stream; OV2, iGO and CSV are read record by record. */
    private fun parse(fmt: PoiFormat, open: () -> java.io.InputStream, csv: CsvProfile?): ImportResult? = when (fmt) {
        PoiFormat.IGO_TXT -> open().bufferedReader(Charsets.ISO_8859_1).use { IgoTxtImporter().parse(it) }
        PoiFormat.KML -> open().use { KmlImporter().parse(it) }
        PoiFormat.GPX -> open().use { GpxImporter().parse(it) }
        PoiFormat.OV2 -> open().use { Ov2Importer().parse(it) }
        PoiFormat.CSV -> csv?.let { p -> open().bufferedReader(Charsets.UTF_8).use { CsvImporter(p).parse(it) } }
    }

    /** First POI file in a picked zip (read via the central directory; see ZipEntries). */
    /** POI files in a zip (by extension), leaving out licence/readme files. */
    private fun poiFilesIn(entries: Map<String, ByteArray>): Map<String, ByteArray> =
        io.autonav.alerts.core.poi.importers.LazyBytes(LinkedHashMap<String, () -> ByteArray>().apply {
            for (name in poiFileNames(entries.keys)) put(name) { entries.getValue(name) }
        })

    private fun poiFileNames(names: Collection<String>): List<String> = names.filter { name ->
        val lower = name.lowercase()
        listOf(".txt", ".csv", ".kml", ".gpx", ".ov2").any { lower.endsWith(it) } &&
            listOf("licence", "license", "release", "readme").none { lower.contains(it) }
    }

    private fun copyToTemp(uri: Uri, suffix: String = ".zip"): java.io.File? {
        val tmp = java.io.File.createTempFile("import", suffix, context.cacheDir)
        val ok = context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) }; true } ?: false
        return if (ok) tmp else { tmp.delete(); null }
    }

    /** Names of the POI files in a picked zip (for the import dialog). */
    suspend fun zipPoiFileNames(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        if (!displayName(uri).endsWith(".zip", true)) return@withContext emptyList()
        // Only the zip's directory is read; no file in it is inflated.
        val tmp = copyToTemp(uri) ?: return@withContext emptyList()
        try { runCatching { poiFileNames(ZipEntries.names(tmp)) }.getOrDefault(emptyList()) } finally { tmp.delete() }
    }

    /** "Croydon_Tramlink_Stop_PGPSW.ov2" → "Croydon Tramlink Stop". */
    fun setNameFor(fileName: String): String =
        fileName.substringBeforeLast('.').replace(Regex("(?i)[_ -]?pgpsw$"), "").replace('_', ' ').trim()
            .ifEmpty { fileName }

    /** One set per POI file in a zip; re-importing the same zip updates those sets instead of adding new ones. */
    private suspend fun importZipFiles(
        zipName: String,
        entries: Map<String, ByteArray>,
        poiFiles: Map<String, ByteArray>,
        csvProfile: CsvProfile?,
    ): ImportOutcome {
        var total = 0
        var skippedRows = 0
        var sets = 0
        var firstId: Long? = null
        val problems = ArrayList<String>()
        for ((fileName, data) in poiFiles) {
            val fmt = FormatSniffer.detect(fileName, data.copyOf(minOf(512, data.size)))
            if (fmt == null) { problems += "$fileName: unknown format"; continue }
            val result = parse(fmt, { data.inputStream() }, csvProfile)
            if (result == null) { problems += "$fileName: CSV needs a column mapping"; continue }
            val stem = fileName.substringBeforeLast('.')
            val iconBytes = entries.entries.firstOrNull { (n, _) ->
                n.substringBeforeLast('.').equals(stem, true) && n.lowercase().let { it.endsWith(".bmp") || it.endsWith(".png") }
            }?.value
            val icon = iconBytes?.let { icons.saveImportedIcon(stem, it) }
            // Keyed by zip and file, so another zip with a file of the same name gets its own set.
            // A set imported before 2026-10-13 has the old key without the zip: the first re-import claims it.
            val key = "${fmt.name}:file:$zipName/$fileName"
            val existing = db.poiSets().byFormat(key) ?: db.poiSets().byFormat("${fmt.name}:file:$fileName")
            val out = storeImport(
                result, setNameFor(fileName), PoiSourceType.FILE, key, existing?.id,
                categoryNamer = { it }, defaultIcon = icon, fileIcon = icon != null,
            )
            if (out.failed) problems += "$fileName: ${out.message}" else {
                total += out.imported; skippedRows += out.skipped; sets++
                if (firstId == null) firstId = out.setId
            }
        }
        val msg = "Imported $total POIs from $zipName into $sets sets" +
            (if (skippedRows > 0) " ($skippedRows rows skipped)" else "") +
            (if (problems.isNotEmpty()) ". Not imported: ${problems.joinToString("; ")}" else "")
        return ImportOutcome(firstId, total, skippedRows, problems, sets == 0, msg)
    }

    // --- PGW import ----------------------------------------------------------------------------

    /** Imports a downloaded PGW zip as two sets (main + pending mobiles). Returns the main outcome. */
    /**
     * Imports a PGW zip. "Consolidated by type" (package 288) becomes one set per file (fixed,
     * mobile, red light, average speed, pending mobile); a single-file package becomes one set.
     * Sets are matched by file across syncs, so the user's icon/alert choices are kept, and PGW
     * sets no longer in the download are removed (DESIGN.md §5.2).
     */
    suspend fun importPgwZip(zip: File, version: String): ImportOutcome = withContext(Dispatchers.IO) {
        ZipEntries.read(zip).use { entries -> importPgwEntries(zip, entries, version) }
    }

    private suspend fun importPgwEntries(zip: File, entries: Map<String, ByteArray>, version: String): ImportOutcome {
        android.util.Log.i("PgwImport", "Zip ${zip.length()} bytes, entries: ${entries.keys}")
        // TomTom OV2 (PGW's own naming carries direction, type, id and speed) or iGO TXT, one set per family.
        val ov2 = io.autonav.alerts.core.poi.importers.PgwOv2.files(entries)
        val files: Map<String, () -> ImportResult> = if (ov2.isNotEmpty()) {
            // One set per file in the download (e.g. "PGW fixed cameras · 30 mph").
            ov2.mapValues { (_, bytes) -> { io.autonav.alerts.core.poi.importers.PgwOv2.parse(bytes) } }
        } else {
            ZipEntries.pgwTypeFiles(entries).mapValues { (_, bytes) -> { IgoTxtImporter().parse(bytes.inputStream().bufferedReader(Charsets.ISO_8859_1)) } }
        }
        val fileFormat = if (ov2.isNotEmpty()) PoiFormat.OV2.name else PoiFormat.IGO_TXT.name
        entries.entries.firstOrNull { it.key.equals(io.autonav.alerts.core.poi.importers.PgwOv2.ICON_LICENCE_FILE, true) }
            ?.let { icons.savePgwIconLicence(it.value) }
        if (files.isEmpty()) return ImportOutcome(null, 0, 0, emptyList(), true,
            "No camera files in the download. The zip contained: " +
                entries.keys.joinToString().ifEmpty { "nothing readable (${zip.length()} bytes)" })
        val keptFormats = HashSet<String>()
        var total = 0
        var firstId: Long? = null
        val failures = ArrayList<String>()
        for ((stem, load) in files) {
            val info = PgwFile.of(stem, ov2 = ov2.isNotEmpty())
            val format = "$fileFormat:$stem"
            keptFormats += format
            val result = load()
            val existing = db.poiSets().byFormat(format)
            // The icon shipped with this file, if any (OV2 downloads include one per file).
            val fileIcon = io.autonav.alerts.core.poi.importers.PgwOv2.iconFor(stem, entries)?.let { icons.savePgwIcon(stem, it) }
            val out = storeImport(
                result, info.setName, info.source, format, existing?.id,
                categoryNamer = info.categoryName, version = version,
                alertsDefault = existing?.alertsEnabled ?: info.alertsByDefault, defaultIcon = fileIcon ?: info.icon,
                fileIcon = fileIcon != null,
            )
            if (out.failed) failures += "${info.setName}: ${out.message}" else { total += out.imported; if (firstId == null) firstId = out.setId }
        }
        // Remove PGW sets from an older package layout (e.g. the single combined set).
        db.poiSets().pgwSets().filter { it.format !in keptFormats }.forEach { db.poiSets().delete(it.id) }
        notifyChanged()
        return if (failures.size == files.size) ImportOutcome(null, 0, 0, failures, true, failures.joinToString("; "))
        else ImportOutcome(firstId, total, 0, failures, false,
            "Imported $total cameras in ${files.size - failures.size} sets" + if (failures.isNotEmpty()) " (${failures.size} failed)" else "")
    }

    // --- Shared storage ------------------------------------------------------------------------

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
        val staging = base.copy(
            id = 0, name = old?.name ?: setName, format = format, state = SetState.STAGING, replacesSetId = old?.id,
            poiCount = result.rows.size, version = version, lastUpdated = now,
        )
        val stagingId = db.poiSets().insert(staging)
        try {
            val oldCats = old?.let { db.categories().forSet(it.id) }?.associateBy { it.sourceCode } ?: emptyMap()
            val catIds = HashMap<String, Long>()
            for (code in result.rows.mapNotNull { it.categoryCode }.distinct()) {
                val prev = oldCats[code]
                val isPgw = source == PoiSourceType.PGW || source == PoiSourceType.PGW_PENDING
                val cat = (prev?.let { p -> if (fileIcon && p.iconId in IconRepository.BUILTIN) p.copy(iconId = null) else p } ?: PoiCategoryEntity(
                    setId = stagingId, sourceCode = code, name = categoryNamer(code),
                    iconId = if (isPgw && !fileIcon) IconRepository.pgwIconFor(code, source) else null,
                    zoneRole = if (isPgw && (code in IgoTxtImporter.PGW_AVERAGE_SPEED_CODES || io.autonav.alerts.core.poi.importers.PgwOv2.isAverageSpeed(code))) ZoneRole.ZONE_POINT else ZoneRole.NONE,
                )).copy(id = 0, setId = stagingId)
                catIds[code] = db.categories().insert(cat)
            }
            result.rows.chunked(2_000).forEach { chunk ->
                db.withTransaction { db.pois().insertAll(chunk.map { it.toEntity(stagingId, catIds) }) }
            }
            db.withTransaction {
                if (old != null) db.poiSets().delete(old.id)
                db.poiSets().update(staging.copy(id = stagingId, state = SetState.ACTIVE, replacesSetId = null))
            }
        } catch (t: Throwable) {
            db.poiSets().delete(stagingId)
            throw t
        }
        notifyChanged()
        return ImportOutcome(stagingId, result.rows.size, result.errors.size, samples, false,
            "Imported ${result.rows.size} POIs" + if (result.errors.isNotEmpty()) " (${result.errors.size} rows skipped)" else "")
    }

    private fun ImportRow.toEntity(setId: Long, cats: Map<String, Long>) = PoiEntity(
        setId = setId, categoryId = categoryCode?.let { cats[it] }, lat = lat, lon = lon, name = name,
        speedLimit = speed, speedUnit = speedUnit, directionType = dirType, bearing = bearing,
        gridCell = Grid.cellOf(lat, lon), sourceId = extra["pgwId"],
    )

    /** What each PGW file becomes (DESIGN.md §5.2). */
    private data class PgwFile(
        val setName: String,
        val source: PoiSourceType,
        val icon: String,
        val alertsByDefault: Boolean,
        val categoryName: (String) -> String,
    ) {
        companion object {
            fun of(stem: String, ov2: Boolean = false): PgwFile {
                if (!ov2) return of(stem)
                val (family, part) = io.autonav.alerts.core.poi.importers.PgwOv2.splitStem(stem)
                val base = of(family)
                val speed = io.autonav.alerts.core.poi.importers.PgwOv2.speedLabel(part)
                return base.copy(
                    setName = if (speed != null) "${base.setName} · $speed" else base.setName,
                    categoryName = io.autonav.alerts.core.poi.importers.PgwOv2::categoryName,
                )
            }

            fun of(stem: String): PgwFile = when (stem) {
                "gatso" -> PgwFile("PGW fixed cameras", PoiSourceType.PGW, IconRepository.CAMERA_FIXED, true) { c ->
                    if (c == "11") "Fixed camera (type 11)" else "Fixed camera"
                }
                "mobile" -> PgwFile("PGW mobile camera sites", PoiSourceType.PGW, IconRepository.CAMERA_MOBILE, true) { "Mobile camera site" }
                "redlight" -> PgwFile("PGW red light cameras", PoiSourceType.PGW, IconRepository.CAMERA_REDLIGHT, true) { "Red light camera" }
                "specs" -> PgwFile("PGW average speed cameras (SPECS)", PoiSourceType.PGW, IconRepository.CAMERA_AVERAGE, true) { "Average speed camera" }
                "pmobile" -> PgwFile("PGW pending mobile sites (unverified)", PoiSourceType.PGW_PENDING, IconRepository.CAMERA_PENDING, false) { "Pending mobile site" }
                "sc" -> PgwFile("PocketGPSWorld cameras (all)", PoiSourceType.PGW, IconRepository.CAMERA_FIXED, true, IgoTxtImporter::categoryName)
                else -> PgwFile("PGW ${stem.replaceFirstChar { it.uppercase() }}", PoiSourceType.PGW, IconRepository.CAMERA_FIXED, true, IgoTxtImporter::categoryName)
            }
        }
    }

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

    // --- "My reports" set (DESIGN.md §8.5) -------------------------------------------------------

    suspend fun reportsSetId(): Long {
        db.poiSets().bySource(PoiSourceType.REPORT)?.let { return it.id }
        val now = System.currentTimeMillis()
        return db.poiSets().insert(
            defaultSet("My reports", PoiSourceType.REPORT, "REPORT", true, now).copy(
                defaultIconId = IconRepository.CAMERA_MOBILE,
                firstAlert = AlertStage.DEFAULT_FIRST.toCols(),
            ),
        )
    }

    suspend fun addReportPoi(entity: PoiEntity): Long = db.pois().insert(entity).also { notifyChanged() }
    suspend fun updatePoi(p: PoiEntity) { db.pois().update(p); notifyChanged() }
    suspend fun deletePoi(id: Long) { db.pois().delete(id); notifyChanged() }
    suspend fun setHidden(poiId: Long, hidden: Boolean) { db.pois().setHidden(poiId, hidden); notifyChanged() }
    suspend fun poi(id: Long) = db.pois().get(id)
    suspend fun purgeExpired() = db.pois().deleteExpired(System.currentTimeMillis())

    private companion object {
        /** KML and GPX are read as a whole document; above this a phone could run out of memory. */
        const val MAX_XML_BYTES = 50_000_000L
    }
}
