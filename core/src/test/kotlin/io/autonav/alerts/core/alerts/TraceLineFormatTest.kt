// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `Trace …` lines and the per-camera `PASSED` passage summary (DESIGN.md §11 step 10, §15.5). */
class TraceLineFormatTest {
    private val lines = ArrayList<Pair<String, String>>()
    private var fix: Fix? = Fix(51.0, -1.0, speedMps = 31.3, heading = 92.0, timeMs = 1_000L, headingAccuracyDeg = 5.0)
    private val trace = AlertTraceLog(
        info = { id -> if (id == 42L) TraceInfo("Gatso 30", 90f, DirectionType.ONE_WAY) else null },
        fix = { fix },
        sink = { tag, msg -> lines += tag to msg },
    )

    private fun passed(): String = lines.single { it.first == "PASSED" }.second
    private fun traces(prefix: String) = lines.filter { it.first == "Trace" && it.second.startsWith(prefix) }.map { it.second }

    @Test fun threeStagePassGivesTheApproachLine() {
        trace.verdict(42, Verdict.AHEAD_OK, 512.0, 3.0, 92.0, 90f, DirectionType.ONE_WAY, 12.0, 107.0)
        trace.stage(AlertEvent(42, AlertKind.FIRST, 466.0, overspeed = false, priority = 10, triggerM = 469.0, staleBelowM = 235.0))
        trace.onSpoken(42, AlertKind.FIRST, spoken = true, delayMs = 800, outcome = "SPOKEN")
        trace.stage(AlertEvent(42, AlertKind.SECOND, 218.0, overspeed = true, priority = 50, triggerM = 235.0, staleBelowM = 91.44))
        trace.onSpoken(42, AlertKind.SECOND, spoken = true, delayMs = 0, outcome = "SPOKEN")
        trace.stage(AlertEvent(42, AlertKind.PASSING, 88.0, overspeed = false, priority = 100, triggerM = 91.44, staleBelowM = null))
        trace.onSpoken(42, AlertKind.PASSING, spoken = true, delayMs = 0, outcome = "SPOKEN")
        trace.cleared(42, ClearReason.PASSED)

        assertEquals(
            "poi=42 set=\"Gatso 30\" dir=ONE_WAY camBrg=90 ourHdg=92±5 clear=PASSED approach: " +
                "enter d=512 → FIRST d=466 spoken(+0.8 s) → SECOND d=218 spoken(+0.0 s) → PASSING d=88 spoken(+0.0 s)",
            passed(),
        )
        assertEquals(listOf("enter poi=42 set=\"Gatso 30\" d=512 hdg=92 camBrg=90 dir=ONE_WAY"), traces("enter"))
        val stages = traces("stage")
        assertEquals(3, stages.size)
        assertEquals("stage FIRST poi=42 set=\"Gatso 30\" d=466 spd=31.3 hdg=92 brg=90 expectedD=469 late=3 overspeed=false", stages[0])
        assertTrue(stages[1], stages[1].endsWith("expectedD=235 late=17 overspeed=true"))
        assertEquals(listOf("spoken FIRST poi=42 spoken=true delayMs=800 outcome=SPOKEN"), traces("spoken FIRST"))
        assertEquals(listOf("stateClear poi=42 reason=PASSED"), traces("stateClear"))
        assertEquals("PASSING poi=42 d=88", trace.lastStage)
    }

    @Test fun wrongDirectionCameraIsNotAlertedAndSaysWhy() {
        // Camera bearing 160°, our heading 90°: 70° off, dimmed at 240 m, then it falls behind.
        val trace = AlertTraceLog(
            info = { TraceInfo("Mobile", 160f, DirectionType.ONE_WAY) },
            fix = { fix },
            sink = { tag, msg -> lines += tag to msg },
        )
        trace.verdict(9, Verdict.WRONG_DIRECTION, 240.0, 2.0, 90.0, 160f, DirectionType.ONE_WAY, 8.0, 100.0)
        trace.dimmed(9, true, 70.0, 10.0)
        trace.verdict(9, Verdict.BEHIND, 30.0, 170.0, 90.0, 160f, DirectionType.ONE_WAY, 5.0, 40.0)
        trace.cleared(9, ClearReason.BEHIND)

        assertEquals(
            "poi=9 set=\"Mobile\" dir=ONE_WAY camBrg=160 ourHdg=92±5 clear=BEHIND approach: NOT ALERTED reason=behind dimmed at d=240",
            passed(),
        )
        assertEquals(
            "suppressed reason=direction poi=9 set=\"Mobile\" d=240 offAxis=2 hdg=90 camBrg=160 dir=ONE_WAY lateral=8 allowed=100",
            traces("suppressed").first(),
        )
        assertEquals(listOf("dim poi=9 bearingDiff=70 headingAcc=10"), traces("dim"))
        assertEquals("behind poi=9 d=30", trace.lastSuppress)
    }

    @Test fun directionIsTheReasonWhenTheCameraClearsStillFacingAway() {
        val trace = AlertTraceLog(
            info = { TraceInfo("Mobile", 160f, DirectionType.ONE_WAY) },
            fix = { fix },
            sink = { tag, msg -> lines += tag to msg },
        )
        trace.verdict(9, Verdict.WRONG_DIRECTION, 240.0, 2.0, 90.0, 160f, DirectionType.ONE_WAY, 8.0, 100.0)
        trace.dimmed(9, true, 70.0, 10.0)
        trace.cleared(9, ClearReason.PRUNED)
        assertEquals(
            "poi=9 set=\"Mobile\" dir=ONE_WAY camBrg=160 ourHdg=92±5 clear=PRUNED approach: NOT ALERTED reason=direction(70°) dimmed at d=240",
            passed(),
        )
    }

    @Test fun mutedAndReplacedStagesShowInTheSummary() {
        trace.verdict(42, Verdict.AHEAD_OK, 500.0, 1.0, 92.0, 90f, DirectionType.ONE_WAY, 10.0, 100.0)
        trace.stage(AlertEvent(42, AlertKind.FIRST, 460.0, false, 10, 469.0, 235.0))
        trace.onSpoken(42, AlertKind.FIRST, spoken = false, delayMs = null, outcome = "REPLACED")
        trace.stage(AlertEvent(42, AlertKind.SECOND, 230.0, false, 50, 235.0, 91.44))
        trace.onSpoken(42, AlertKind.SECOND, spoken = false, delayMs = null, outcome = "MUTED")
        trace.stage(AlertEvent(42, AlertKind.PASSING, 90.0, false, 100, 91.44, null))
        trace.cleared(42, ClearReason.PASSED)
        assertTrue(passed(), passed().endsWith("enter d=500 → FIRST d=460 NOT spoken(REPLACED) → SECOND d=230 muted → PASSING d=90 unreported"))
    }

    @Test fun reasonsMapEveryVerdict() {
        val expected = mapOf(
            Verdict.WRONG_DIRECTION to "direction", Verdict.OFF_ROAD to "offRoad", Verdict.NOT_AHEAD to "notAhead",
            Verdict.NOT_MOVING to "notMoving", Verdict.NO_HEADING to "noHeading", Verdict.OUT_OF_RANGE to "outOfRange",
            Verdict.BEHIND to "behind", Verdict.AHEAD_OK to "ok",
        )
        assertEquals(Verdict.entries.size, expected.size)
        for (v in Verdict.entries) assertEquals(expected.getValue(v), AlertTraceLog.reasonOf(v))
        assertEquals(70.0, AlertTraceLog.mismatchDeg(160f, DirectionType.ONE_WAY, 90.0)!!, 0.0)
        assertEquals(20.0, AlertTraceLog.mismatchDeg(250f, DirectionType.BOTH, 90.0)!!, 0.0)
        assertEquals(0.0, AlertTraceLog.mismatchDeg(250f, DirectionType.ANY, 90.0)!!, 0.0)
        assertEquals(null, AlertTraceLog.mismatchDeg(null, DirectionType.ONE_WAY, 90.0))
    }
}
