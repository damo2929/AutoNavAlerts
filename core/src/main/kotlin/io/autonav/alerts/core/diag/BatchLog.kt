// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.diag

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/** One fix, summarised for the block header (DESIGN.md §15.5): never a line of its own. */
data class FixSummary(
    /** "car" or "phone". */
    val source: String,
    val speedMps: Double?,
    val headingDeg: Double?,
)

/**
 * The in-memory half of one debug-log channel (DESIGN.md §15.5): formatted lines collect here and
 * go to the file as one block every 15 s. The block header carries a summary of the fixes seen in
 * that span (count, sources, speed and heading ranges, rate), so the 10 Hz feed costs no lines.
 *
 * The buffer is bounded: past [maxLines] the oldest lines go and the block says `…dropped N`.
 * Files are capped at [maxBytes] with one rotation (`<name>.1.log`). Pure JVM; the Android side
 * (`EventLog`) owns the thread and the clock.
 */
class BatchLog(
    private val maxLines: Int = 400,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lines = ArrayDeque<String>()
    private var dropped = 0

    private var fixes = 0
    private val sources = LinkedHashSet<String>()
    private var spdMin = Double.MAX_VALUE
    private var spdMax = -Double.MAX_VALUE
    private var hdgMin = Double.MAX_VALUE
    private var hdgMax = -Double.MAX_VALUE

    /** The day (yyyy-MM-dd) of the last block written through [appendBlock], to date new days. */
    @Volatile private var lastDay: String? = null

    /** Adds one already-formatted line. Any thread. */
    @Synchronized
    fun record(line: String) {
        if (lines.size >= maxLines) {
            lines.removeFirst()
            dropped++
        }
        lines.addLast(line)
    }

    /** Counts a fix into the header of the next block. Any thread, cheap. */
    @Synchronized
    fun fix(sample: FixSummary) {
        fixes++
        sources += sample.source
        sample.speedMps?.let { spdMin = min(spdMin, it); spdMax = max(spdMax, it) }
        sample.headingDeg?.let { hdgMin = min(hdgMin, it); hdgMax = max(hdgMax, it) }
    }

    /** True when the next block would be empty (no lines, no fixes). */
    @Synchronized
    fun isEmpty(): Boolean = lines.isEmpty() && fixes == 0

    /** Pending lines that haven't been written yet (for tests and status). */
    @Synchronized
    fun pending(): Int = lines.size

    /**
     * Drains the buffer and the fix accumulator into one block for the span [fromMs, toMs]:
     * `--- HH:mm:ss–HH:mm:ss fixes=N src=car/phone hz=… spd=… hdg=… dropped=N ---` then the lines,
     * each on its own line, ending with a newline. Returns "" when there is nothing to write.
     */
    @Synchronized
    fun buildBlock(fromMs: Long, toMs: Long): String {
        if (lines.isEmpty() && fixes == 0) return ""
        val sb = StringBuilder()
        sb.append("--- ").append(TIME.format(Instant.ofEpochMilli(fromMs).atZone(zone)))
            .append('–').append(TIME.format(Instant.ofEpochMilli(toMs).atZone(zone)))
            .append(" fixes=").append(fixes)
        if (fixes > 0) {
            sb.append(" src=").append(sources.joinToString("/"))
            val spanS = (toMs - fromMs) / 1000.0
            val hz = if (spanS > 0) fixes / spanS else fixes.toDouble()
            sb.append(" hz=").append(ONE_DP.format(hz))
            if (spdMax >= spdMin) sb.append(" spd=").append(ONE_DP.format(spdMin)).append('–').append(ONE_DP.format(spdMax))
            if (hdgMax >= hdgMin) sb.append(" hdg=").append(hdgMin.toInt()).append('–').append(hdgMax.toInt())
        }
        if (dropped > 0) sb.append(" dropped=").append(dropped)
        sb.append(" ---\n")
        if (dropped > 0) sb.append("…dropped ").append(dropped).append('\n')
        for (l in lines) sb.append(l).append('\n')
        lines.clear()
        dropped = 0
        fixes = 0
        sources.clear()
        spdMin = Double.MAX_VALUE; spdMax = -Double.MAX_VALUE
        hdgMin = Double.MAX_VALUE; hdgMax = -Double.MAX_VALUE
        return sb.toString()
    }

    /**
     * Builds the block for [fromMs, toMs] and appends it to [file] in one write, rotating the file
     * to `<name>.1.log` first if it would pass [maxBytes]. A fresh file, and the first block of a
     * new day, are preceded by a `=== yyyy-MM-dd ===` line, since the block headers carry times
     * only. Returns false when there was nothing to write.
     */
    fun appendBlock(file: File, fromMs: Long, toMs: Long): Boolean {
        val block = buildBlock(fromMs, toMs)
        if (block.isEmpty()) return false
        val day = DAY.format(Instant.ofEpochMilli(fromMs).atZone(zone))
        val fresh = !file.exists() || file.length() == 0L || wouldRotate(file, block.length.toLong(), maxBytes)
        val text = if (fresh || day != lastDay) "=== $day ===\n$block" else block
        lastDay = day
        append(file, text, maxBytes)
        return true
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 1_048_576L
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val ONE_DP = java.text.DecimalFormat("0.0", java.text.DecimalFormatSymbols(java.util.Locale.ROOT))
        private val DATE_LINE = Regex("""^=== (\d{4}-\d{2}-\d{2}) ===$""")
        private val HEADER_TIME = Regex("""^--- (\d{2}:\d{2}:\d{2})""")

        /** The rotation of [file]: `debug-nav.log` → `debug-nav.1.log`. */
        fun rotated(file: File): File {
            val n = file.name
            val base = if (n.endsWith(".log")) n.dropLast(4) else n
            return File(file.parentFile, "$base.1.log")
        }

        /** Current file plus its rotation, oldest first, only those that exist. */
        fun files(file: File): List<File> = listOf(rotated(file), file).filter { it.isFile }

        /** Bytes on disk for the file and its rotation. */
        fun sizeBytes(file: File): Long = files(file).sumOf { it.length() }

        private fun wouldRotate(file: File, addBytes: Long, maxBytes: Long): Boolean =
            file.isFile && file.length() > 0 && file.length() + addBytes > maxBytes

        /**
         * Appends [text] to [file] in one write. If the file would pass [maxBytes] it is first
         * renamed to its rotation (replacing any older one). Returns true when it rotated.
         */
        fun append(file: File, text: String, maxBytes: Long = DEFAULT_MAX_BYTES): Boolean {
            file.parentFile?.mkdirs()
            val bytes = text.toByteArray(Charsets.UTF_8)
            var rotatedNow = false
            if (wouldRotate(file, bytes.size.toLong(), maxBytes)) {
                val r = rotated(file)
                r.delete()
                rotatedNow = file.renameTo(r)
                if (!rotatedNow) file.delete()
            }
            java.io.FileOutputStream(file, true).use { it.write(bytes) }
            return rotatedNow
        }

        /**
         * The last [n] lines of [file], oldest first. When the file has fewer, the end of its
         * rotation is put in front so the view spans the two.
         */
        fun tail(file: File, n: Int): List<String> {
            if (n <= 0) return emptyList()
            val cur = tailOf(file, n)
            if (cur.size >= n) return cur
            val older = tailOf(rotated(file), n - cur.size)
            return if (older.isEmpty()) cur else older + cur
        }

        private fun tailOf(file: File, n: Int): List<String> {
            if (!file.isFile || n <= 0) return emptyList()
            val len = file.length()
            if (len == 0L) return emptyList()
            // Read from the end in chunks until n newlines are seen; a line is at most a few hundred bytes.
            val chunk = 8192
            RandomAccessFile(file, "r").use { raf ->
                var pos = len
                var newlines = 0
                val buf = java.io.ByteArrayOutputStream()
                val pieces = ArrayList<ByteArray>()
                while (pos > 0 && newlines <= n) {
                    val size = min(chunk.toLong(), pos).toInt()
                    pos -= size
                    raf.seek(pos)
                    val b = ByteArray(size)
                    raf.readFully(b)
                    pieces += b
                    for (x in b) if (x == '\n'.code.toByte()) newlines++
                }
                for (i in pieces.indices.reversed()) buf.write(pieces[i])
                val text = buf.toString("UTF-8")
                val all = text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
                return all.takeLast(n)
            }
        }

        /** Deletes [file] and its rotation. */
        fun clear(file: File) {
            rotated(file).delete()
            file.delete()
        }

        /**
         * "yyyy-MM-dd HH:mm:ss" of the oldest entry across the rotation and the file, from the
         * first date line and block header, or null when there is nothing.
         */
        fun oldest(file: File): String? {
            for (f in files(file)) {
                var day: String? = null
                f.bufferedReader().useLines { seq ->
                    for (line in seq.take(50)) {
                        DATE_LINE.find(line)?.let { day = it.groupValues[1] }
                        HEADER_TIME.find(line)?.let { m -> return (day?.let { "$it " } ?: "") + m.groupValues[1] }
                    }
                }
            }
            return null
        }
    }
}
