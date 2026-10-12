// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.osm

import android.util.Log
import io.autonav.alerts.BuildConfig
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A zip file on a web server, read with HTTP range requests (DESIGN.md §8.4a): its directory, and
 * one entry streamed and inflated, without downloading the rest. A dropped connection is resumed
 * from the same byte (up to [RETRIES] times).
 */
class RemoteZip private constructor(val url: String, val size: Long, private val entries: Map<String, Entry>) {
    data class Entry(val name: String, val method: Int, val compressed: Long, val size: Long, val headerOffset: Long)

    fun entry(name: String): Entry? = entries[name]

    /** The entry's bytes, inflated. [onBytes] gets the compressed bytes read so far. */
    fun open(e: Entry, onBytes: (Long) -> Unit): InputStream {
        val local = range(e.headerOffset, 30)
        val b = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(0) == 0x04034b50) { "Bad local header for ${e.name}" }
        val start = e.headerOffset + 30 + (b.getShort(26).toInt() and 0xffff) + (b.getShort(28).toInt() and 0xffff)
        val raw = ResumingStream(url, start, e.compressed, onBytes)
        return when (e.method) {
            0 -> raw
            8 -> InflaterInputStream(raw, Inflater(true), 1 shl 16)
            else -> error("Unsupported zip method ${e.method}")
        }
    }

    private fun range(headerOffset: Long, len: Int): ByteArray = get(url, headerOffset, headerOffset + len - 1).use { it.readBytes() }

    /** Reads [length] bytes from [start], reopening the request where it stopped if the connection drops. */
    private class ResumingStream(val url: String, start: Long, length: Long, val onBytes: (Long) -> Unit) : InputStream() {
        private var pos = start
        private val end = start + length
        private var read = 0L
        private var stream: InputStream? = null
        private var failures = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= end) return -1
            while (true) {
                try {
                    val s = stream ?: get(url, pos, end - 1).also { stream = it }
                    val n = s.read(b, off, minOf(len.toLong(), end - pos).toInt())
                    if (n < 0) throw IOException("Connection closed at $pos of $end")
                    pos += n; read += n
                    onBytes(read)
                    return n
                } catch (e: IOException) {
                    runCatching { stream?.close() }
                    stream = null
                    if (++failures > RETRIES) throw e
                    Log.w(TAG, "Download interrupted (${e.message}); resuming at $pos (try $failures)")
                    Thread.sleep(5_000L * failures)
                }
            }
        }

        override fun close() { runCatching { stream?.close() } }
    }

    companion object {
        private const val TAG = "RemoteZip"
        private const val RETRIES = 8
        private val UA = "AutoNavAlerts/${BuildConfig.VERSION_NAME} (Android)"

        /** Follows redirects (Geofabrik's "-latest" links point at dated files) and reads the directory. */
        fun open(link: String): RemoteZip {
            val url = resolve(link)
            val size = head(url)
            val tailLen = minOf(size, 65_557L)
            val tail = get(url, size - tailLen, size - 1).use { it.readBytes() }
            val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val eocd = lastIndexOf(tail, 0x06054b50) ?: error("Not a zip: $url")
            var cdSize = tb.getInt(eocd + 12).toLong() and 0xffffffffL
            var cdOff = tb.getInt(eocd + 16).toLong() and 0xffffffffL
            lastIndexOf(tail, 0x06064b50)?.let { z64 -> cdSize = tb.getLong(z64 + 40); cdOff = tb.getLong(z64 + 48) }
            val cd = get(url, cdOff, cdOff + cdSize - 1).use { it.readBytes() }
            val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
            val entries = HashMap<String, Entry>()
            var p = 0
            while (p + 46 <= cd.size && b.getInt(p) == 0x02014b50) {
                val method = b.getShort(p + 10).toInt() and 0xffff
                var comp = b.getInt(p + 20).toLong() and 0xffffffffL
                var unc = b.getInt(p + 24).toLong() and 0xffffffffL
                val n = b.getShort(p + 28).toInt() and 0xffff
                val e = b.getShort(p + 30).toInt() and 0xffff
                val c = b.getShort(p + 32).toInt() and 0xffff
                var off = b.getInt(p + 42).toLong() and 0xffffffffL
                val name = String(cd, p + 46, n, Charsets.UTF_8)
                var q = p + 46 + n
                val extraEnd = q + e
                while (q + 4 <= extraEnd) { // ZIP64 sizes and offset
                    val id = b.getShort(q).toInt() and 0xffff
                    val len = b.getShort(q + 2).toInt() and 0xffff
                    if (id == 1) {
                        var k = q + 4
                        if (unc == 0xffffffffL) { unc = b.getLong(k); k += 8 }
                        if (comp == 0xffffffffL) { comp = b.getLong(k); k += 8 }
                        if (off == 0xffffffffL) off = b.getLong(k)
                    }
                    q += 4 + len
                }
                entries[name] = Entry(name, method, comp, unc, off)
                p += 46 + n + e + c
            }
            return RemoteZip(url, size, entries)
        }

        private fun lastIndexOf(bytes: ByteArray, sig: Int): Int? {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in bytes.size - 4 downTo 0) if (b.getInt(i) == sig) return i
            return null
        }

        private fun connect(url: String, method: String): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
            }

        private fun resolve(link: String): String {
            var u = link
            repeat(5) {
                val c = connect(u, "HEAD")
                try {
                    if (c.responseCode in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString() } else return u
                } finally { c.disconnect() }
            }
            return u
        }

        private fun head(url: String): Long {
            val c = connect(url, "HEAD")
            try {
                if (c.responseCode != 200) error("HTTP ${c.responseCode} for $url")
                return c.contentLengthLong
            } finally { c.disconnect() }
        }

        private fun get(url: String, from: Long, to: Long): InputStream {
            val c = connect(url, "GET")
            c.setRequestProperty("Range", "bytes=$from-$to")
            if (c.responseCode != 206) { c.disconnect(); throw IOException("HTTP ${c.responseCode} (range not supported?) for $url") }
            return c.inputStream
        }
    }
}
