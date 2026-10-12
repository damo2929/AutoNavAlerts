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

class BatchLogTest {
    @get:Rule val tmp = TemporaryFolder()

    // 2026-10-12 18:04:00 UTC
    private val t0 = 1_791_828_240_000L
    private fun log(maxLines: Int = 400, maxBytes: Long = BatchLog.DEFAULT_MAX_BYTES) = BatchLog(maxLines, maxBytes, ZoneOffset.UTC)

    @Test
    fun blockHeaderSummarisesFixesAndLinesFollow() {
        val b = log()
        b.record("18:04:01.000 Guidance start simulate=false")
        repeat(100) { b.fix(FixSummary("car", 20.0 + it * 0.1, 88.0 + it * 0.05)) }
        repeat(47) { b.fix(FixSummary("phone", 25.0, 92.0)) }
        b.record("18:04:09.500 Map recenter")
        val block = b.buildBlock(t0, t0 + 15_000)
        val lines = block.split('\n')
        assertEquals("--- 18:04:00–18:04:15 fixes=147 src=car/phone hz=9.8 spd=20.0–29.9 hdg=88–92 ---", lines[0])
        assertEquals("18:04:01.000 Guidance start simulate=false", lines[1])
        assertEquals("18:04:09.500 Map recenter", lines[2])
        assertEquals("", lines[3])
        assertEquals(4, lines.size)
        // Draining leaves the next block empty.
        assertTrue(b.isEmpty())
        assertEquals("", b.buildBlock(t0 + 15_000, t0 + 30_000))
    }

    @Test
    fun blockWithoutFixesHasNoRateFields() {
        val b = log()
        b.record("18:04:01.000 Session create api=6 host=com.google.android.projection.gearhead")
        val block = b.buildBlock(t0, t0 + 15_000)
        assertEquals("--- 18:04:00–18:04:15 fixes=0 ---", block.lineSequence().first())
    }

    @Test
    fun fixesOnlyStillMakeABlock() {
        val b = log()
        b.fix(FixSummary("phone", null, null))
        val block = b.buildBlock(t0, t0 + 15_000)
        assertEquals("--- 18:04:00–18:04:15 fixes=1 src=phone hz=0.1 ---\n", block)
    }

    @Test
    fun bufferIsBoundedAndSaysWhatItDropped() {
        val b = log(maxLines = 5)
        for (i in 1..8) b.record("line $i")
        assertEquals(5, b.pending())
        val lines = b.buildBlock(t0, t0 + 15_000).trimEnd().split('\n')
        assertEquals("--- 18:04:00–18:04:15 fixes=0 dropped=3 ---", lines[0])
        assertEquals("…dropped 3", lines[1])
        assertEquals(listOf("line 4", "line 5", "line 6", "line 7", "line 8"), lines.drop(2))
        // The marker and the count reset with the block.
        b.record("line 9")
        assertFalse(b.buildBlock(t0, t0 + 15_000).contains("dropped"))
    }

    @Test
    fun appendRotatesOnceAtTheCap() {
        val f = File(tmp.root, "debug-nav.log")
        val cap = 100L
        assertFalse(BatchLog.append(f, "a".repeat(60) + "\n", cap))
        assertFalse(BatchLog.append(f, "b".repeat(30) + "\n", cap))
        assertEquals(92L, f.length())
        // This one would pass the cap: the file rotates and starts again.
        assertTrue(BatchLog.append(f, "c".repeat(20) + "\n", cap))
        val r = BatchLog.rotated(f)
        assertEquals("debug-nav.1.log", r.name)
        assertEquals(92L, r.length())
        assertEquals(21L, f.length())
        // A second rotation replaces the first.
        assertTrue(BatchLog.append(f, "d".repeat(90) + "\n", cap))
        assertEquals(21L, r.length())
        assertEquals(91L, f.length())
        assertEquals(listOf(r, f), BatchLog.files(f))
        assertEquals(112L, BatchLog.sizeBytes(f))
    }

    @Test
    fun appendBlockDatesFreshFilesAndNewDays() {
        val f = File(tmp.root, "alert-debug.log")
        val b = log()
        assertFalse(b.appendBlock(f, t0, t0 + 15_000))
        assertFalse(f.exists())
        b.record("one")
        assertTrue(b.appendBlock(f, t0, t0 + 15_000))
        b.record("two")
        assertTrue(b.appendBlock(f, t0 + 15_000, t0 + 30_000))
        val day = 86_400_000L
        b.record("three")
        assertTrue(b.appendBlock(f, t0 + day, t0 + day + 15_000))
        val text = f.readText()
        assertEquals(
            "=== 2026-10-12 ===\n--- 18:04:00–18:04:15 fixes=0 ---\none\n" +
                "--- 18:04:15–18:04:30 fixes=0 ---\ntwo\n" +
                "=== 2026-10-13 ===\n--- 18:04:00–18:04:15 fixes=0 ---\nthree\n",
            text,
        )
        assertEquals("2026-10-12 18:04:00", BatchLog.oldest(f))
    }

    @Test
    fun tailReadsTheEndAndSpansTheRotation() {
        val f = File(tmp.root, "debug-nav.log")
        assertEquals(emptyList<String>(), BatchLog.tail(f, 10))
        val many = (1..300).joinToString("\n") { "line $it padded to be a bit longer than usual so chunks matter" } + "\n"
        BatchLog.append(f, many)
        assertEquals(listOf("line 299 padded to be a bit longer than usual so chunks matter", "line 300 padded to be a bit longer than usual so chunks matter"), BatchLog.tail(f, 2))
        assertEquals(300, BatchLog.tail(f, 1000).size)
        assertEquals("line 1 padded to be a bit longer than usual so chunks matter", BatchLog.tail(f, 1000).first())
        // Rotate, then a short current file: the tail starts in the rotation.
        BatchLog.append(f, "x\ny\n", maxBytes = 10)
        assertEquals(listOf("line 300 padded to be a bit longer than usual so chunks matter", "x", "y"), BatchLog.tail(f, 3))
        BatchLog.clear(f)
        assertFalse(f.exists())
        assertFalse(BatchLog.rotated(f).exists())
        assertNull(BatchLog.oldest(f))
    }
}
