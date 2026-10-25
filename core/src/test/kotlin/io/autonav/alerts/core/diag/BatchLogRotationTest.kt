// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import java.util.Locale

/**
 * More of the two always-on debug logs (DESIGN.md §15.5): a rotation dates the fresh file even
 * mid-day, the oldest entry is found across the rotation, the tail's edges, the header's fields
 * when only some are known and its numbers in any locale, and a first block bigger than the cap.
 */
class BatchLogRotationTest {
    @get:Rule val tmp = TemporaryFolder()

    // 2026-10-12 18:04:00 UTC
    private val t0 = 1_791_828_240_000L
    private fun log(maxBytes: Long = BatchLog.DEFAULT_MAX_BYTES) = BatchLog(400, maxBytes, ZoneOffset.UTC)

    @Test
    fun aRotationMidDayStartsTheNewFileWithTheDate() {
        val f = File(tmp.root, "debug-nav.log")
        val b = log(maxBytes = 200)
        b.record("x".repeat(40))
        assertTrue(b.appendBlock(f, t0, t0 + 15_000))
        b.record("y".repeat(40))
        assertTrue(b.appendBlock(f, t0 + 15_000, t0 + 30_000))
        // Same day, but this block would pass the cap: the file rotates and the new one is dated.
        b.record("z".repeat(40))
        assertTrue(b.appendBlock(f, t0 + 30_000, t0 + 45_000))
        assertTrue(BatchLog.rotated(f).readText().startsWith("=== 2026-10-12 ===\n--- 18:04:00"))
        assertEquals("=== 2026-10-12 ===\n--- 18:04:30–18:04:45 fixes=0 ---\n" + "z".repeat(40) + "\n", f.readText())
        // The oldest entry now lives in the rotation.
        assertEquals("2026-10-12 18:04:00", BatchLog.oldest(f))
    }

    @Test
    fun oldestWithoutADateLineIsTheTimeAlone() {
        val f = File(tmp.root, "alert-debug.log").apply { writeText("--- 09:15:00–09:15:15 fixes=0 ---\nline\n") }
        assertEquals("09:15:00", BatchLog.oldest(f))
        // A file that has neither in its first lines says nothing; the rotation's turn comes first anyway.
        val g = File(tmp.root, "other.log").apply { writeText("free text\n".repeat(60) + "--- 10:00:00–10:00:15 fixes=0 ---\n") }
        assertNull(BatchLog.oldest(g))
        BatchLog.rotated(g).writeText("=== 2026-10-11 ===\n--- 23:59:45–00:00:00 fixes=0 ---\n")
        assertEquals("2026-10-11 23:59:45", BatchLog.oldest(g))
    }

    @Test
    fun appendMakesTheFolderAndAFirstTextBiggerThanTheCapIsKept() {
        val f = File(tmp.root, "diag/sub/debug-nav.log")
        assertFalse(BatchLog.append(f, "a".repeat(50) + "\n", maxBytes = 10))
        assertEquals(51L, f.length())
        assertFalse(BatchLog.rotated(f).exists())
        // The next one rotates it out.
        assertTrue(BatchLog.append(f, "b\n", maxBytes = 10))
        assertEquals(2L, f.length())
    }

    @Test
    fun tailEdges() {
        val f = File(tmp.root, "debug-nav.log")
        f.writeText("a\nb")
        assertEquals(emptyList<String>(), BatchLog.tail(f, 0))
        assertEquals(emptyList<String>(), BatchLog.tail(f, -3))
        // No newline at the end: the last line still counts.
        assertEquals(listOf("a", "b"), BatchLog.tail(f, 5))
        assertEquals(listOf("b"), BatchLog.tail(f, 1))
        // An empty current file with a rotation: the rotation's end.
        f.writeText("")
        BatchLog.rotated(f).writeText("r1\nr2\nr3\n")
        assertEquals(listOf("r2", "r3"), BatchLog.tail(f, 2))
        assertEquals(listOf(BatchLog.rotated(f)), BatchLog.files(f).filter { it.length() > 0 })
    }

    @Test
    fun headerShowsOnlyTheRangesItHas() {
        val b = log()
        b.fix(FixSummary("car", 12.34, null))
        b.fix(FixSummary("car", 10.0, null))
        assertEquals("--- 18:04:00–18:04:00 fixes=2 src=car hz=2.0 spd=10.0–12.3 ---\n", b.buildBlock(t0, t0))
        b.fix(FixSummary("phone", null, 359.9))
        b.fix(FixSummary("phone", null, 0.4))
        assertEquals("--- 18:04:00–18:04:10 fixes=2 src=phone hz=0.2 hdg=0–359 ---\n", b.buildBlock(t0, t0 + 10_000))
    }

    @Test
    fun headerNumbersUseADotInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val b = log()
            repeat(3) { b.fix(FixSummary("phone", 1.25, 90.0)) }
            assertEquals("--- 18:04:00–18:04:02 fixes=3 src=phone hz=1.5 spd=1.2–1.2 hdg=90–90 ---\n", b.buildBlock(t0, t0 + 2_000))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun rotationNames() {
        assertEquals("alert-debug.1.log", BatchLog.rotated(File(tmp.root, "alert-debug.log")).name)
        assertEquals("trace.txt.1.log", BatchLog.rotated(File(tmp.root, "trace.txt")).name)
        assertEquals(0L, BatchLog.sizeBytes(File(tmp.root, "none.log")))
    }
}
