// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.nav

import io.autonav.alerts.core.geo.Geo

/**
 * When and what to say for the turn-by-turn directions, now that we speak them ourselves in the
 * alerts' voice (DESIGN.md §12.3 "Directions in our voice", owner 2026-10-23). Pure: the app feeds
 * every turn-by-turn update; at most one [Prompt] comes back per update, and each prompt is said
 * once per step.
 *
 * - **prepare**: "In 300 yards, turn right onto Main Rd, A61" once the distance falls to
 *   clamp(speed × 30 s, 150 m, 2 km), with the distance rounded down to one worth saying;
 * - **now**: the instruction alone at clamp(speed × 5 s, 40 m, 300 m);
 * - **continue**: "Continue for 3 miles" when a step longer than 3 km starts.
 *
 * No Android, no threads of its own: the app's `DirectionsSpeaker` keeps one instance and calls
 * [onUpdate] on the main thread for every `NavInfo` from the Navigation SDK while its own voice is
 * off, then hands the prompt to `NavTts` as `AlertKind.GUIDANCE` (priority 80, §12.3). A step that
 * starts already inside a prompt's distance skips the earlier prompt; nothing repeats for one step;
 * nothing is said while rerouting. Metres and m/s in; the words follow the app's units.
 */
class GuidancePrompter(private val config: Config = Config()) {
    data class Config(
        // prepare at clamp(speed × 30 s, 150 m, 2 km); now at clamp(speed × 5 s, 40 m, 300 m);
        // "Continue for …" on a step over 3 km (§12.3 "When").
        val prepareS: Double = 30.0, val prepareMinM: Double = 150.0, val prepareMaxM: Double = 2_000.0,
        val nowS: Double = 5.0, val nowMinM: Double = 40.0, val nowMaxM: Double = 300.0,
        val continueOverM: Double = 3_000.0,
    )

    /** Which of the three prompts; also the log's `kind=`. */
    enum class Kind { PREPARE, NOW, CONTINUE }

    /** The last step: the SDK's instruction is only the waypoint's title, so it's worded as Google does. */
    enum class Destination { AHEAD, LEFT, RIGHT }

    /** [staleBelowM]: not worth saying once the car is closer to the turn than this (the speech queue's rule). */
    /** [step] is the SDK's step number, [distanceM] the distance to the manoeuvre when the prompt was made. */
    data class Prompt(val kind: Kind, val step: Int, val text: String, val distanceM: Double, val staleBelowM: Double?)

    private var stepKey: String? = null
    private var step = -1
    private val said = HashSet<Kind>()

    /** Forget the steps (guidance stopped or a new route). */
    fun reset() {
        stepKey = null
        step = -1
        said.clear()
    }

    /**
     * One turn-by-turn update. [instruction] is the step's full instruction ("Turn right onto Main
     * Rd/A61"); [distanceM] the distance to it; [destination] set on the last step ("Your
     * destination is on the left"). Nothing while [rerouting] or without an instruction.
     */
    fun onUpdate(
        stepNumber: Int, instruction: String?, distanceM: Double, speedMps: Double, imperial: Boolean,
        rerouting: Boolean = false, destination: Destination? = null,
    ): Prompt? {
        if (rerouting || distanceM < 0) return null
        if (instruction.isNullOrBlank() && destination == null) return null
        val text = when (destination) {
            Destination.LEFT -> "Your destination is on the left"
            Destination.RIGHT -> "Your destination is on the right"
            Destination.AHEAD -> "Your destination is ahead"
            null -> clean(instruction!!)
        }
        val key = "$stepNumber|$text"
        val first = key != stepKey
        if (first) { stepKey = key; step = stepNumber; said.clear() }
        val v = speedMps.coerceAtLeast(0.0)
        val nowM = (v * config.nowS).coerceIn(config.nowMinM, config.nowMaxM)
        val prepareM = (v * config.prepareS).coerceIn(config.prepareMinM, config.prepareMaxM)
        if (distanceM <= nowM) {
            if (Kind.NOW in said) return null
            said += Kind.entries
            return Prompt(Kind.NOW, step, text, distanceM, null)
        }
        if (distanceM <= prepareM) {
            if (Kind.PREPARE in said) return null
            said += listOf(Kind.PREPARE, Kind.CONTINUE)
            // Not worth a "prepare" just before the "now" one.
            // Inside 1.5 × the now distance the "now" prompt follows within a second or two: one is enough.
            if (distanceM < nowM * 1.5) return null
            val (_, words) = spokenDistance(distanceM, imperial) ?: return null
            return Prompt(Kind.PREPARE, step, "In $words, ${lowerFirst(text)}", distanceM, nowM)
        }
        if (first && distanceM > config.continueOverM && Kind.CONTINUE !in said) {
            said += Kind.CONTINUE
            return Prompt(Kind.CONTINUE, step, "Continue for ${longDistance(distanceM, imperial)}", distanceM, prepareM)
        }
        return null
    }

    companion object {
        private const val YARD_M = Geo.METRES_PER_YARD
        private const val MILE_M = Geo.METRES_PER_MILE

        private val IMPERIAL = listOf(
            100 * YARD_M to "100 yards", 200 * YARD_M to "200 yards", 300 * YARD_M to "300 yards", 500 * YARD_M to "500 yards",
            MILE_M / 4 to "a quarter of a mile", MILE_M / 2 to "half a mile", MILE_M to "1 mile", 2 * MILE_M to "2 miles",
        )
        private val METRIC = listOf(
            100.0 to "100 metres", 200.0 to "200 metres", 300.0 to "300 metres", 500.0 to "500 metres",
            1_000.0 to "1 kilometre", 2_000.0 to "2 kilometres",
        )

        /** The largest distance worth saying that isn't more than [m], as (metres, words); null under 100 yards/metres. */
        fun spokenDistance(m: Double, imperial: Boolean): Pair<Double, String>? =
            (if (imperial) IMPERIAL else METRIC).lastOrNull { it.first <= m }

        /** "3 miles" / "5 kilometres" for a long step, rounded to the nearest whole one. */
        fun longDistance(m: Double, imperial: Boolean): String {
            val n = Math.round(m / if (imperial) MILE_M else 1_000.0).coerceAtLeast(1)
            return if (imperial) "$n mile${if (n == 1L) "" else "s"}" else "$n kilometre${if (n == 1L) "" else "s"}"
        }

        /** Road names joined by "/" ("Main Rd/A61") read as a pause, and spaces tidied. */
        fun clean(instruction: String): String = instruction.replace(Regex("\\s*/\\s*"), ", ").replace(Regex("\\s+"), " ").trim()

        private fun lowerFirst(s: String) = if (s.length > 1 && s[0].isUpperCase() && !s[1].isUpperCase()) s[0].lowercaseChar() + s.substring(1) else s
    }
}
