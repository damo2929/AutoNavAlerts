// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.osm

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What [RemoteZip] asks a server for and what it makes of the answers (DESIGN.md §8.4a "Only the roads are
 * fetched"), pinned against a MockWebServer so the HTTP client can change underneath without the sequence the
 * server sees changing: a HEAD on the link, redirects followed by HEAD (never by GET), the size from the final
 * HEAD's Content-Length, then only Range GETs on the final URL: the directory in two (the tail, the central
 * directory), each entry in two more (its local header, its data), inflated to the original bytes. The dropped-
 * connection resume logs through `android.util.Log`, which the JVM's stub throws on, so that path is not run here.
 */
class RemoteZipTest {
    private val server = MockWebServer()
    private val text = ByteArray(200_000) { ('a' + it % 26).code.toByte() }
    private val binary = ByteArray(5_000) { (it * 7).toByte() }
    private val zip: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("roads/a.txt"))
            z.write(text)
            z.closeEntry()
            z.putNextEntry(ZipEntry("b.bin").apply {
                method = ZipEntry.STORED
                size = binary.size.toLong()
                crc = CRC32().apply { update(binary) }.value
            })
            z.write(binary)
            z.closeEntry()
        }
    }.toByteArray()

    private val dated = "/england-261011-free.shp.zip"
    private val link get() = server.url("/england-latest-free.shp.zip").toString()
    private val finalUrl get() = server.url(dated).toString()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/england-latest-free.shp.zip" -> MockResponse().setResponseCode(302).setHeader("Location", dated)
                    path == "/missing.zip" -> MockResponse().setResponseCode(404)
                    request.method == "HEAD" -> MockResponse().setHeader("Content-Length", zip.size)
                    path == "/noranges.zip" -> MockResponse().setBody(Buffer().write(zip))
                    else -> slice(request.getHeader("Range"))
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    /** A 206 for `bytes=from-to`, or the whole file as a 200 when there is no Range header. */
    private fun slice(range: String?): MockResponse {
        val m = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range ?: "") ?: return MockResponse().setBody(Buffer().write(zip))
        val from = m.groupValues[1].toInt()
        val to = m.groupValues[2].toInt()
        return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $from-$to/${zip.size}")
            .setBody(Buffer().write(zip, from, to - from + 1))
    }

    /** Every request the server saw so far, in order. */
    private fun requests(): List<RecordedRequest> = generateSequence { server.takeRequest(200, TimeUnit.MILLISECONDS) }.toList()

    /** The end-of-central-directory record's (offset, size) of the central directory, read from the zip bytes. */
    private fun centralDirectory(): Pair<Long, Long> {
        val b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (zip.size - 22 downTo 0).first { b.getInt(it) == 0x06054b50 }
        return b.getInt(eocd + 16).toLong() to b.getInt(eocd + 12).toLong()
    }

    /** The data offset of the entry whose local header is at [headerOffset]: 30 fixed bytes plus the name and extra lengths. */
    private fun dataStart(headerOffset: Long): Long {
        val b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        val h = headerOffset.toInt()
        assertEquals(0x04034b50, b.getInt(h))
        return headerOffset + 30 + (b.getShort(h + 26).toInt() and 0xffff) + (b.getShort(h + 28).toInt() and 0xffff)
    }

    @Test fun directoryIsReadByHeadsThenTwoRangeGetsOnTheFinalUrl() {
        val remote = RemoteZip.open(link)
        assertEquals(finalUrl, remote.url)
        assertNotNull(remote.entry("roads/a.txt"))
        assertNotNull(remote.entry("b.bin"))
        assertEquals(null, remote.entry("nope"))

        val seen = requests()
        val heads = seen.takeWhile { it.method == "HEAD" }
        val gets = seen.drop(heads.size)
        // The link is asked for first; the redirect is followed by HEAD, ending on the dated file; no GET before the size is known.
        assertEquals("/england-latest-free.shp.zip", heads.first().path)
        assertEquals(dated, heads.last().path)
        assertTrue(gets.all { it.method == "GET" && it.path == dated })
        // The tail (EOCD and any comment) first, then exactly the central directory.
        val size = zip.size.toLong()
        val tailLen = minOf(size, 65_557L)
        val (cdOff, cdSize) = centralDirectory()
        assertEquals(listOf("bytes=${size - tailLen}-${size - 1}", "bytes=$cdOff-${cdOff + cdSize - 1}"), gets.map { it.getHeader("Range") })
    }

    @Test fun deflatedEntryIsTwoRangeGetsAndInflatesToTheOriginal() {
        val remote = RemoteZip.open(link)
        requests()
        val e = remote.entry("roads/a.txt")!!
        assertEquals(8, e.method)
        var progress = 0L
        val bytes = remote.open(e) { progress = it }.use { it.readBytes() }
        assertArrayEquals(text, bytes)
        assertEquals(e.compressed, progress)
        val start = dataStart(e.headerOffset)
        val gets = requests()
        assertTrue(gets.all { it.method == "GET" && it.path == dated })
        assertEquals(
            listOf("bytes=${e.headerOffset}-${e.headerOffset + 29}", "bytes=$start-${start + e.compressed - 1}"),
            gets.map { it.getHeader("Range") },
        )
    }

    @Test fun storedEntryIsReadAsItIs() {
        val remote = RemoteZip.open(link)
        val e = remote.entry("b.bin")!!
        assertEquals(0, e.method)
        assertEquals(binary.size.toLong(), e.compressed)
        assertArrayEquals(binary, remote.open(e) {}.use { it.readBytes() })
    }

    @Test fun aServerThatIgnoresRangeIsAnError() {
        val url = server.url("/noranges.zip").toString()
        try {
            RemoteZip.open(url)
            fail("expected an IOException")
        } catch (e: IOException) {
            assertEquals("HTTP 200 (range not supported?) for $url", e.message)
        }
    }

    @Test fun aFailedHeadIsAnError() {
        val url = server.url("/missing.zip").toString()
        try {
            RemoteZip.open(url)
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("HTTP 404 for $url", e.message)
        }
    }
}
