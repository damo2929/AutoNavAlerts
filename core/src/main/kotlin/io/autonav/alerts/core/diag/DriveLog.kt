// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.diag

import io.autonav.alerts.core.alerts.Fix
import io.autonav.alerts.core.location.FixInput
import io.autonav.alerts.core.location.LocationSource
import java.io.Reader

/**
 * The drive recorder's CSV (DESIGN.md §15.5): one row per `Location` reaching the location
 * source (phone or car, used or dropped), car fix and car speed report. The app writes it with
 * [format]; the replay test reads it with [parse]. Empty fields are nulls.
 *
 * No Android, no threads of its own: `DriveRecorder` formats rows on the diag thread (fed by
 * `AdaptiveLocationSource` and `CarDataSource`); `DriveReplayTest` and `tools/drive.py --replay`
 * read them back. Units: degrees for lat/lon and bearings, metres, m/s, and milliseconds ([RecordedFix.tMs]
 * wall clock, [RecordedFix.fixTMs] the fix's own clock).
 */
object DriveLog {
    /** The header row, exactly as DESIGN.md §15.5 lists the columns; the order is the file format. */
    const val HEADER = "t_ms,kind,src,used,lat,lon,acc_m,spd_mps,brg_deg,has_brg,alt_m,fix_t_ms,eng_spd_mps,eng_hdg_deg,hdg_src,car_raw_mps,car_disp_mps"
    /** The column names in order; a row with fewer fields is skipped by [parseLine]. */
    val COLUMNS: List<String> = HEADER.split(',')

    /** [RecordedFix.kind] values. */
    const val KIND_FIX = "fix"
    const val KIND_SPEED = "speed"

    /** [RecordedFix.src] values. */
    const val SRC_PHONE = "phone"
    const val SRC_CAR = "car"
    const val SRC_SIM = "sim"

    /**
     * One row. [kind] is [KIND_FIX] or [KIND_SPEED]; [src] one of the SRC_ values. The app builds
     * these directly (`AdaptiveLocationSource` for a fix, `CarDataSource` for a speed report, where
     * only the car's two speeds are set); the fields a row lacks default to null (empty in the CSV).
     */
    data class RecordedFix(
        /** Wall-clock time the row was recorded, ms. */
        val tMs: Long,
        val kind: String,
        val src: String,
        /** Whether the location source used it (a phone fix is dropped while the car's GPS is fresh). */
        val used: Boolean,
        // A fix's own fields as Android gave them: degrees, metres, m/s, degrees true; null when absent.
        val lat: Double? = null,
        val lon: Double? = null,
        val accM: Double? = null,
        val spdMps: Double? = null,
        val brgDeg: Double? = null,
        /** Whether the fix carried a bearing at all (a 0 bearing with hasBrg=false is "none"). */
        val hasBrg: Boolean = false,
        val altM: Double? = null,
        /** The fix's own timestamp (Location.time), ms, as opposed to [tMs] when the app saw it. */
        val fixTMs: Long? = null,
        /** What the engine was given for this fix at the time, after the speed/heading rules. */
        val engSpdMps: Double? = null,
        val engHdgDeg: Double? = null,
        val hdgSrc: String? = null,
        // The car's measured and dial speeds, m/s: when fresh for a fix row, the report itself for a speed row.
        val carRawMps: Double? = null,
        val carDispMps: Double? = null,
    ) {
        /** The engine's fix, as recorded (the engine's speed and heading of the time). */
        fun toFix(): Fix = Fix(
            lat = lat ?: 0.0, lon = lon ?: 0.0,
            speedMps = engSpdMps ?: spdMps ?: 0.0,
            heading = engHdgDeg,
            timeMs = fixTMs ?: tMs,
        )

        /** The fix as the estimator would have seen it live. */
        fun toInput(): FixInput = FixInput(
            lat = lat ?: 0.0, lon = lon ?: 0.0, timeMs = fixTMs ?: tMs,
            accuracyM = accM, speedMps = spdMps, bearingDeg = if (hasBrg) brgDeg else null,
            source = when (src) { SRC_CAR -> LocationSource.CAR; SRC_SIM -> LocationSource.SIM; else -> LocationSource.PHONE },
        )
    }

    /** One CSV row (no newline). */
    fun format(r: RecordedFix): String = listOf(
        r.tMs, r.kind, r.src, if (r.used) 1 else 0, r.lat, r.lon, r.accM, r.spdMps, r.brgDeg,
        if (r.hasBrg) 1 else 0, r.altM, r.fixTMs, r.engSpdMps, r.engHdgDeg, r.hdgSrc, r.carRawMps, r.carDispMps,
    ).joinToString(",") { it?.toString() ?: "" }

    /** Rows in file order; the header row and blank or malformed lines are skipped. */
    fun parse(reader: Reader): Sequence<RecordedFix> = reader.buffered().lineSequence().mapNotNull(::parseLine)

    /** One row, or null for the header, a blank line, too few fields or an unreadable number. */
    fun parseLine(line: String): RecordedFix? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("t_ms")) return null
        val f = t.split(',')
        if (f.size < COLUMNS.size) return null
        return try {
            RecordedFix(
                tMs = f[0].toLong(), kind = f[1], src = f[2], used = flag(f[3]),
                lat = f[4].toDoubleOrNull(), lon = f[5].toDoubleOrNull(), accM = f[6].toDoubleOrNull(),
                spdMps = f[7].toDoubleOrNull(), brgDeg = f[8].toDoubleOrNull(), hasBrg = flag(f[9]),
                altM = f[10].toDoubleOrNull(), fixTMs = f[11].toLongOrNull(),
                engSpdMps = f[12].toDoubleOrNull(), engHdgDeg = f[13].toDoubleOrNull(),
                hdgSrc = f[14].ifEmpty { null }, carRawMps = f[15].toDoubleOrNull(), carDispMps = f[16].toDoubleOrNull(),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun flag(s: String) = s == "1" || s.equals("true", ignoreCase = true)
}
