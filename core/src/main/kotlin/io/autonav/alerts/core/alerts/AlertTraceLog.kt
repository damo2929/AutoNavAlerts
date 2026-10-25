// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.alerts

import io.autonav.alerts.core.geo.Geo
import io.autonav.alerts.core.poi.DirectionType
import kotlin.math.roundToInt

/**
 * What the trace needs to know about a POI it is writing about: its set's name, its stored
 * [bearing] (degrees true, null for none) and its [dirType] (ANY / ONE_WAY / BOTH, §11 step 3).
 */
data class TraceInfo(val setName: String, val bearing: Float?, val dirType: DirectionType)

/**
 * The engine's [AlertTrace] as `alert-debug.log` lines (DESIGN.md §11 step 10, §15.5): `Trace
 * enter/leave/suppressed/stage/spoken/stateClear/dim/undim`, and one `PASSED` passage summary per
 * camera when its state clears, assembled from what the trace saw on that approach. Pure Kotlin
 * (JVM-tested, no Android): [info] names the POI, [fix] gives the current fix, [sink] takes
 * `(tag, msg)`; the app's `AlertCoordinator` wires those to its cache, its last fix and `EventLog`.
 *
 * Threads: the engine calls [verdict], [stage], [cleared] and [dimmed] on the alert collector;
 * `NavTts` reports [onSpoken] from the main handler; every method is `@Synchronized` for that.
 * It is the evidence trail for hard rule 1 (AGENTS.md §3.1: a camera facing away is dimmed, never
 * hidden, and never alerts): every direction refusal is written with its mismatch, and the
 * `PASSED` line says what was done at each camera, so a silent or wrong alert can be explained.
 */
class AlertTraceLog(
    private val info: (Long) -> TraceInfo?,
    private val fix: () -> Fix?,
    private val sink: (tag: String, msg: String) -> Unit,
) : AlertTrace {
    /** One stage fired on an approach and, once NavTts reports back, whether it was heard. */
    private class StageRec(val kind: AlertKind, val d: Double) {
        var spoken: Boolean? = null
        var delayMs: Long? = null
        var outcome: String? = null
    }

    /** Everything seen for one POI since it came into the lookahead, for the PASSED line. */
    private class Passage {
        var enterD: Double? = null
        var enterHdg: Double? = null
        var enterAcc: Double? = null
        val stages = ArrayList<StageRec>()
        var lastSuppress: String? = null
        var dimmedAtD: Double? = null
        var lastVerdict = Verdict.OUT_OF_RANGE
        var lastD = 0.0
    }

    private val passages = HashMap<Long, Passage>()
    /** The last stage line and the last suppression, for the bench's engine snapshot. */
    @Volatile var lastStage: String? = null
        private set
    @Volatile var lastSuppress: String? = null
        private set

    /**
     * The engine's verdict for a POI changed (§11 step 10): `enter` when it becomes AHEAD_OK, else
     * `leave` (if it was) and `suppressed reason=` with the geometry that failed. [d] metres,
     * [offAxisDeg] the bearing-to-POI against the heading, [headingDeg] degrees true or null,
     * [poiBearing] the camera's stored bearing, [lateralM] against [allowedM] the corridor test.
     */
    @Synchronized
    override fun verdict(
        poiId: Long, verdict: Verdict, d: Double, offAxisDeg: Double, headingDeg: Double?,
        poiBearing: Float?, dirType: DirectionType, lateralM: Double, allowedM: Double,
    ) {
        val p = passages.getOrPut(poiId) { Passage() }
        val set = info(poiId)?.setName ?: "?"
        if (verdict == Verdict.AHEAD_OK) {
            if (p.enterD == null) { p.enterD = d; p.enterHdg = headingDeg; p.enterAcc = fix()?.headingAccuracyDeg }
            sink("Trace", "enter poi=$poiId set=\"$set\" d=${d.toInt()} hdg=${deg(headingDeg)} camBrg=${deg(poiBearing?.toDouble())} dir=$dirType")
        } else {
            if (p.lastVerdict == Verdict.AHEAD_OK) sink("Trace", "leave poi=$poiId set=\"$set\" d=${d.toInt()} verdict=$verdict")
            val reason = reasonOf(verdict)
            val mismatch = mismatchDeg(poiBearing, dirType, headingDeg)
            val detail = if (verdict == Verdict.WRONG_DIRECTION && mismatch != null) "$reason(${mismatch.toInt()}°)" else reason
            p.lastSuppress = detail
            lastSuppress = "$detail poi=$poiId d=${d.toInt()}"
            sink(
                "Trace",
                "suppressed reason=$reason poi=$poiId set=\"$set\" d=${d.toInt()} offAxis=${deg(offAxisDeg)} hdg=${deg(headingDeg)}" +
                    " camBrg=${deg(poiBearing?.toDouble())} dir=$dirType lateral=${metres(lateralM)} allowed=${metres(allowedM)}",
            )
        }
        p.lastVerdict = verdict
        p.lastD = d
    }

    /** A stage fired: the `Trace stage` line with distance, speed, heading, camera bearing and how late it was. */
    @Synchronized
    override fun stage(event: AlertEvent) {
        val f = fix()
        val i = info(event.poiId)
        val p = passages.getOrPut(event.poiId) { Passage() }
        val late = event.triggerM - event.distanceM
        val line = "stage ${event.kind} poi=${event.poiId} set=\"${i?.setName ?: "?"}\" d=${event.distanceM.toInt()}" +
            " spd=${f?.let { "%.1f".format(it.speedMps) } ?: "-"} hdg=${deg(f?.heading)} brg=${deg(i?.bearing?.toDouble())}" +
            " expectedD=${event.triggerM.toInt()} late=${late.toInt()} overspeed=${event.overspeed}"
        sink("Trace", line)
        lastStage = "${event.kind} poi=${event.poiId} d=${event.distanceM.toInt()}"
        p.stages += StageRec(event.kind, event.distanceM)
    }

    /** NavTts reported the outcome of a stage's speech (through the coordinator's callback). */
    @Synchronized
    fun onSpoken(poiId: Long, kind: AlertKind, spoken: Boolean, delayMs: Long?, outcome: String) {
        // A speed-limit change (owner, 2026-10-13) and a direction (DirectionsSpeaker, §12.3) are their
        // own things, never a camera stage: one `Alerts` line each, no passage bookkeeping.
        if (kind == AlertKind.LIMIT_CHANGE || kind == AlertKind.GUIDANCE) {
            val what = if (kind == AlertKind.LIMIT_CHANGE) "limitChange" else "directions"
            sink("Alerts", "$what spoken=$spoken delayMs=${delayMs ?: "-"} outcome=$outcome")
            return
        }
        sink("Trace", "spoken $kind poi=$poiId spoken=$spoken delayMs=${delayMs ?: "-"} outcome=$outcome")
        val p = passages[poiId] ?: return
        val rec = p.stages.lastOrNull { it.kind == kind && it.spoken == null } ?: return
        rec.spoken = spoken
        rec.delayMs = delayMs
        rec.outcome = outcome
    }

    /**
     * The engine forgot the POI (passed, stale, behind, pruned; §11 step 7): the `stateClear` line and
     * the one-line `PASSED` passage summary (§15.5), which answers what was done as the car passed it.
     */
    @Synchronized
    override fun cleared(poiId: Long, reason: ClearReason) {
        sink("Trace", "stateClear poi=$poiId reason=$reason")
        val p = passages.remove(poiId) ?: return
        val i = info(poiId)
        val f = fix()
        val hdg = p.enterHdg ?: f?.heading
        val acc = p.enterAcc ?: f?.headingAccuracyDeg
        val line = buildString {
            append("poi=").append(poiId).append(" set=\"").append(i?.setName ?: "?").append('"')
            append(" dir=").append(i?.dirType ?: "?").append(" camBrg=").append(deg(i?.bearing?.toDouble()))
            append(" ourHdg=").append(deg(hdg))
            if (hdg != null && acc != null) append('±').append(acc.roundToInt())
            append(" clear=").append(reason).append(" approach: ")
            if (p.stages.isEmpty()) {
                append("NOT ALERTED reason=").append(p.lastSuppress ?: if (p.enterD != null) "noStageReached" else "none")
                p.dimmedAtD?.let { append(" dimmed at d=").append(it.toInt()) }
                p.enterD?.let { append(" (entered d=").append(it.toInt()).append(')') }
            } else {
                append("enter d=").append(p.enterD?.toInt()?.toString() ?: "-")
                for (s in p.stages) {
                    append(" → ").append(s.kind).append(" d=").append(s.d.toInt()).append(' ').append(spokenWord(s))
                }
            }
        }
        sink("PASSED", line)
    }

    /** A facing-away camera was dimmed or undimmed on the map (§8.1, hard rule 1: dimmed, never hidden). */
    @Synchronized
    override fun dimmed(poiId: Long, dimmed: Boolean, mismatchDeg: Double, headingAccuracyDeg: Double) {
        sink("Trace", "${if (dimmed) "dim" else "undim"} poi=$poiId bearingDiff=${mismatchDeg.toInt()} headingAcc=${headingAccuracyDeg.toInt()}")
        if (dimmed) passages[poiId]?.let { it.dimmedAtD = it.lastD }
    }

    /** Forgets passages of POIs no longer among the candidates, so the map can't grow for the whole process. */
    @Synchronized
    fun prune(keep: Set<Long>) {
        passages.keys.retainAll(keep)
    }

    /** Session end: forget every passage. */
    @Synchronized
    fun reset() = passages.clear()

    private fun spokenWord(s: StageRec): String = when {
        s.spoken == true -> "spoken" + (s.delayMs?.let { "(+${"%.1f".format(it / 1000.0)} s)" } ?: "")
        s.outcome == "MUTED" -> "muted"
        s.spoken == false -> "NOT spoken(${s.outcome})"
        else -> "unreported"
    }

    companion object {
        /** The verdict as the `suppressed reason=` word of §15.5. */
        fun reasonOf(v: Verdict): String = when (v) {
            Verdict.WRONG_DIRECTION -> "direction"
            Verdict.OFF_ROAD -> "offRoad"
            Verdict.NOT_AHEAD -> "notAhead"
            Verdict.NOT_MOVING -> "notMoving"
            Verdict.NO_HEADING -> "noHeading"
            Verdict.OUT_OF_RANGE -> "outOfRange"
            Verdict.BEHIND -> "behind"
            Verdict.AHEAD_OK -> "ok"
        }

        /**
         * The camera's bearing against our heading (both ways for BOTH), or null when either is unknown.
         * The same test as `AlertEngine.headingMismatch` (§11 step 3), which is private to the engine
         * and takes its `AlertPoi`; this one takes the raw columns the trace has.
         */
        fun mismatchDeg(bearing: Float?, dirType: DirectionType, heading: Double?): Double? {
            val b = bearing?.toDouble() ?: return null
            val h = heading ?: return null
            return when (dirType) {
                DirectionType.ANY -> 0.0
                DirectionType.ONE_WAY -> Geo.absAngleDiffDeg(b, h)
                DirectionType.BOTH -> minOf(Geo.absAngleDiffDeg(b, h), Geo.absAngleDiffDeg(b + 180.0, h))
            }
        }

        private fun deg(v: Double?): String = if (v == null || v.isNaN()) "-" else v.roundToInt().toString()
        private fun metres(v: Double): String = if (v.isNaN()) "-" else v.roundToInt().toString()
    }
}
