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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
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
fun AlertStage.toCols() = StageCols(enabled, mode, metres, seconds)

/** POI storage, import pipeline with staging + atomic swap (DESIGN.md §4.4, §5). */
@Singleton
class PoiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
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
        setName: String,
        format: PoiFormat? = null,
        csvProfile: CsvProfile? = null,
        replaceSetId: Long? = null,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext fail("Couldn't open the file")
        val entry: Pair<String, ByteArray> = if (name.endsWith(".zip", true)) {
            firstPoiEntry(bytes) ?: return@withContext fail("No POI file found in the zip")
        } else {
            name to bytes
        }
        val (entryName, data) = entry
        val fmt = format ?: FormatSniffer.detect(entryName, data.copyOf(minOf(512, data.size)))
            ?: return@withContext fail("Unknown file format")
        val result = parse(fmt, data, csvProfile) ?: return@withContext fail("CSV files need a column mapping")
        storeImport(result, setName, PoiSourceType.FILE, fmt.name, replaceSetId, categoryNamer = { it })
    }

    private fun fail(msg: String) = ImportOutcome(null, 0, 0, emptyList(), true, msg)

    private fun parse(fmt: PoiFormat, data: ByteArray, csv: CsvProfile?): ImportResult? = when (fmt) {
        PoiFormat.IGO_TXT -> IgoTxtImporter().parse(data.inputStream().bufferedReader(Charsets.ISO_8859_1))
        PoiFormat.KML -> KmlImporter().parse(ByteArrayInputStream(data))
        PoiFormat.GPX -> GpxImporter().parse(ByteArrayInputStream(data))
        PoiFormat.OV2 -> Ov2Importer().parse(data)
        PoiFormat.CSV -> csv?.let { CsvImporter(it).parse(data.inputStream().bufferedReader(Charsets.UTF_8)) }
    }

    private fun firstPoiEntry(zip: ByteArray): Pair<String, ByteArray>? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var total = 0L
            while (true) {
                val e = zis.nextEntry ?: return null
                if (e.isDirectory || e.name.contains("..")) continue
                val lower = e.name.lowercase()
                if (listOf(".txt", ".csv", ".kml", ".gpx", ".ov2").any { lower.endsWith(it) } && !lower.endsWith("licence.txt") && !lower.endsWith("release.txt")) {
                    val b = zis.readBytes()
                    total += b.size
                    if (total > 200L * 1024 * 1024) return null
                    return e.name.substringAfterLast('/') to b
                }
            }
        }
    }

    // --- PGW import ----------------------------------------------------------------------------

    /** Imports a downloaded PGW zip as two sets (main + pending mobiles). Returns the main outcome. */
    suspend fun importPgwZip(zip: File, version: String): ImportOutcome = withContext(Dispatchers.IO) {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var total = 0L
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory || e.name.contains("..")) continue
                val b = zis.readBytes()
                total += b.size
                if (total > 200L * 1024 * 1024) error("Zip too large")
                entries[e.name.substringAfterLast('/')] = b
            }
        }
        val main = entries[PgwConfig.MAIN_FILE] ?: return@withContext ImportOutcome(null, 0, 0, emptyList(), true, "Main camera file missing from download")
        val importer = IgoTxtImporter()
        val mainResult = importer.parse(main.inputStream().bufferedReader(Charsets.ISO_8859_1))
        val existingMain = db.poiSets().bySource(PoiSourceType.PGW)
        val outcome = storeImport(
            mainResult, "PocketGPSWorld cameras", PoiSourceType.PGW, PoiFormat.IGO_TXT.name, existingMain?.id,
            categoryNamer = IgoTxtImporter::categoryName, version = version,
        )
        entries[PgwConfig.PENDING_MOBILE_FILE]?.let { pm ->
            val r = importer.parse(pm.inputStream().bufferedReader(Charsets.ISO_8859_1))
            val existing = db.poiSets().bySource(PoiSourceType.PGW_PENDING)
            storeImport(
                r, "PGW pending mobile sites (unverified)", PoiSourceType.PGW_PENDING, PoiFormat.IGO_TXT.name, existing?.id,
                categoryNamer = { "Pending mobile site" }, version = version, alertsDefault = existing?.alertsEnabled ?: false,
            )
        }
        outcome
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
    ): ImportOutcome {
        val samples = result.errors.take(20).map { "Line ${it.lineNumber}: ${it.reason}" }
        if (result.failed) {
            return ImportOutcome(null, result.rows.size, result.errors.size, samples, true,
                "Import failed: ${result.errors.size} bad rows out of ${result.totalConsidered}")
        }
        val old = replaceSetId?.let { db.poiSets().get(it) }
        val now = System.currentTimeMillis()
        val staging = (old ?: defaultSet(setName, source, format, alertsDefault, now)).copy(
            id = 0, name = old?.name ?: setName, state = SetState.STAGING, replacesSetId = old?.id,
            poiCount = result.rows.size, version = version, lastUpdated = now,
        )
        val stagingId = db.poiSets().insert(staging)
        try {
            val oldCats = old?.let { db.categories().forSet(it.id) }?.associateBy { it.sourceCode } ?: emptyMap()
            val catIds = HashMap<String, Long>()
            for (code in result.rows.mapNotNull { it.categoryCode }.distinct()) {
                val prev = oldCats[code]
                val isPgw = source == PoiSourceType.PGW || source == PoiSourceType.PGW_PENDING
                val cat = (prev ?: PoiCategoryEntity(
                    setId = stagingId, sourceCode = code, name = categoryNamer(code),
                    iconId = if (isPgw) IconRepository.pgwIconFor(code, source) else null,
                    zoneRole = if (isPgw && code in IgoTxtImporter.PGW_AVERAGE_SPEED_CODES) ZoneRole.ZONE_POINT else ZoneRole.NONE,
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
        gridCell = Grid.cellOf(lat, lon),
    )

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
                firstAlert = StageCols(true, DistanceMode.TIME, 457, 30),
            ),
        )
    }

    suspend fun addReportPoi(entity: PoiEntity): Long = db.pois().insert(entity).also { notifyChanged() }
    suspend fun setHidden(poiId: Long, hidden: Boolean) { db.pois().setHidden(poiId, hidden); notifyChanged() }
    suspend fun poi(id: Long) = db.pois().get(id)
    suspend fun purgeExpired() = db.pois().deleteExpired(System.currentTimeMillis())
}
