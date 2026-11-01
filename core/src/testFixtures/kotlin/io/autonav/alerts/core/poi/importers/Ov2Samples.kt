// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.poi.importers

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Made-up OV2 sample data with the structure of real TomTom files (DESIGN.md §5.6), for the core and app
 * tests. Real files start with a skipper whose block size covers the whole file, split the area into nested
 * skipper blocks (a quadtree-like split until a block holds few POIs) and often wrap a block in chains of
 * single-child skippers, up to 15 deep; a big file's outer block is over 1 MiB. Every name and place here is
 * invented: PocketGPSWorld data never enters the repo or its tests (AGENTS.md §3.1 rule 6; DESIGN.md §6.4).
 * Test-only; no thread rules.
 */
object Ov2Samples {
    /** One POI record: type 2 (name only) or 3 (`name\0id\0extra\0`, the parser keeps the name). */
    data class Poi(val lat: Double, val lon: Double, val name: String, val type: Int = 2)

    /** A POI record's bytes: type, little-endian total length, lon and lat ×100000, NUL-terminated text. */
    fun record(p: Poi): ByteArray {
        val text = (if (p.type == 3) "${p.name}\u0000ID${p.name.length}\u0000extra\u0000" else p.name + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val len = 13 + text.size
        return le(len).put(p.type.toByte()).putInt(len).putInt(e5(p.lon)).putInt(e5(p.lat)).put(text).array()
    }

    /** A non-POI record of [type] with [payload] bytes after its 5-byte header, whose int is its own length. */
    fun other(type: Int, payload: Int): ByteArray = le(5 + payload).put(type.toByte()).putInt(5 + payload).array()

    /** A deleted record (type 0) of [payload] bytes after its header, as an editor leaves behind. */
    fun deleted(payload: Int = 8): ByteArray = other(0, payload)

    /** A 21-byte skipper over [block] (the records it covers), its int the block size including itself. */
    fun skipper(block: ByteArray, pois: List<Poi>): ByteArray {
        val east = pois.maxOf { it.lon }; val west = pois.minOf { it.lon }
        val north = pois.maxOf { it.lat }; val south = pois.minOf { it.lat }
        return le(21).put(1).putInt(21 + block.size).putInt(e5(east)).putInt(e5(north)).putInt(e5(west)).putInt(e5(south)).array()
    }

    /**
     * A whole file laid out as TomTom does: one skipper over everything, then the area split on its longer side
     * until a block holds at most [leaf] POIs, each block behind its own skipper. [chain] extra single-child
     * skippers wrap the first leaf (real files have up to 15). [deletedEvery] puts a deleted record after every
     * n-th POI. The POIs come out in the order [expectedOrder] gives.
     */
    fun file(pois: List<Poi>, leaf: Int = 8, chain: Int = 0, deletedEvery: Int = 0): ByteArray {
        var count = 0
        var chained = false
        fun block(ps: List<Poi>): ByteArray {
            val body = ByteArrayOutputStream()
            if (ps.size <= leaf) {
                for (p in ps) {
                    body.write(record(p))
                    count++
                    if (deletedEvery > 0 && count % deletedEvery == 0) body.write(deleted())
                }
                var bytes = body.toByteArray()
                if (!chained) {
                    chained = true
                    repeat(chain) { bytes = skipper(bytes, ps) + bytes }
                }
                return skipper(bytes, ps) + bytes
            }
            for (half in split(ps)) body.write(block(half))
            val bytes = body.toByteArray()
            return skipper(bytes, ps) + bytes
        }
        return block(pois)
    }

    /** The order [file] writes [pois] in: the same recursive split, leaves left to right. */
    fun expectedOrder(pois: List<Poi>, leaf: Int = 8): List<Poi> =
        if (pois.size <= leaf) pois else split(pois).flatMap { expectedOrder(it, leaf) }

    /** Halves on the longer side of the box at the median (ties kept stable), as a spatial index would. */
    private fun split(ps: List<Poi>): List<List<Poi>> {
        val byLon = (ps.maxOf { it.lon } - ps.minOf { it.lon }) >= (ps.maxOf { it.lat } - ps.minOf { it.lat })
        val sorted = if (byLon) ps.sortedBy { it.lon } else ps.sortedBy { it.lat }
        return listOf(sorted.subList(0, sorted.size / 2), sorted.subList(sorted.size / 2, sorted.size))
    }

    /**
     * [n] invented POIs on a grid around ([lat], [lon]), named "[prefix] <letters>" (letters, so no number in a
     * name is read as a limit), every third one with a limit in the name when [withLimits]; see [limitOf].
     */
    fun grid(n: Int, lat: Double, lon: Double, prefix: String, spacing: Double = 0.003, withLimits: Boolean = false): List<Poi> =
        List(n) { i ->
            val name = "$prefix ${letters(i)}" + if (withLimits && i % 3 == 0) " [${LIMITS[i % LIMITS.size]}]" else ""
            Poi(lat + (i / 10) * spacing, lon + (i % 10) * spacing, name, type = if (i % 7 == 6) 3 else 2)
        }

    /**
     * [n] invented speed cameras named in PGW's OV2 scheme (§5.2a), `<compass>-[R-]<TYPE>:<id>[@<mph>]`: the
     * compass point cycling through all eight, every third one reversible, ids from [firstId] (made up), the
     * limit [mph] (0 = variable, null = none given). The copyright watermark entry PGW adds is not included.
     */
    fun cameras(n: Int, lat: Double, lon: Double, type: String, mph: Int?, firstId: Int = 90_001): List<Poi> =
        List(n) { i ->
            val compass = COMPASS[i % COMPASS.size]
            val name = "$compass-" + (if (i % 3 == 2) "R-" else "") + "$type:${firstId + i}" + (mph?.let { "@$it" } ?: "")
            Poi(lat + (i / 10) * 0.004, lon + (i % 10) * 0.004, name)
        }

    /** PGW's compass points in the order [cameras] uses them, with their bearings. */
    val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** The limit [grid] wrote into [name], or null. */
    fun limitOf(name: String): Int? = Regex("""\[(\d+)]""").find(name)?.groupValues?.get(1)?.toInt()

    /** 0 → "a", 25 → "z", 26 → "ba": a POI's index in letters. */
    fun letters(i: Int): String = if (i < 26) ('a' + i).toString() else letters(i / 26) + ('a' + i % 26)

    private val LIMITS = listOf(20, 30, 40, 50)

    /** One file of the sample pack: its name in the zip, its POIs as written, its bytes and its icon. */
    class PackFile(val fileName: String, val pois: List<Poi>, val bytes: ByteArray, val icon: ByteArray)

    /**
     * A 22-file pack with the shape of a free POI download: every file a skipper tree of its own, sizes from 8 to
     * about 1,800 POIs, one file's first block behind 15 chained skippers, a matching 22×22 24-bit `.bmp` icon
     * per file. Invented towns and places throughout.
     */
    fun pack(): List<PackFile> {
        val towns = listOf(
            "Ashbridge", "Bramwell", "Calderford", "Dunmere", "Elmstead", "Farrowby", "Glenhallow", "Harnwick",
            "Ivydale", "Kestermoor", "Larkhaven", "Marrowgate", "Northwold", "Oakhurst", "Penmorrow", "Quarlby",
            "Rushcombe", "Saltmarsh", "Thornbury Vale", "Upperfold", "Westerholm", "Yarrowmere",
        )
        val kinds = listOf("Tram Stops", "Clean Air Zone", "Metro Stations", "Low Emission Zone")
        return towns.mapIndexed { i, town ->
            val size = when (i) { 10 -> 1_800; 15 -> 330; 21 -> 8; else -> 15 + i * 7 }
            val stem = "${town.replace(' ', '_')}_${kinds[i % kinds.size].replace(' ', '_')}_Sample"
            val pois = grid(size, 50.5 + i * 0.2, -3.5 + i * 0.15, "$town ${kinds[i % kinds.size].removeSuffix("s")}", withLimits = i % 4 == 1)
            PackFile("$stem.ov2", pois, file(pois, chain = if (i == 0) 15 else i % 3, deletedEvery = if (i == 5) 4 else 0), bmp(i))
        }
    }

    /** A 24-bit Windows BMP of [size]² pixels in a colour picked by [seed], the format the download's icons use. */
    fun bmp(seed: Int, size: Int = 22): ByteArray {
        val row = (size * 3 + 3) / 4 * 4
        val pixels = row * size
        val b = le(54 + pixels)
        b.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + pixels).putInt(0).putInt(54)
        b.putInt(40).putInt(size).putInt(size).putShort(1).putShort(24).putInt(0).putInt(pixels).putInt(2835).putInt(2835).putInt(0).putInt(0)
        val (r, g, bl) = Triple((seed * 53) % 256, (seed * 97 + 80) % 256, (seed * 29 + 160) % 256)
        repeat(size) {
            repeat(size) { b.put(bl.toByte()).put(g.toByte()).put(r.toByte()) }
            repeat(row - size * 3) { b.put(0) }
        }
        return b.array()
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun e5(deg: Double) = (deg * 100_000).roundToInt()
}
