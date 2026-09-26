/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.facebook.wdt.WdtSender
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * A file being shared: read with positional reads, so several downloads
 * (browsers, WDT) can read it at the same time.
 *
 * @param pfd its content, or null to read [file]
 */
class SharedFile(val name: String, val size: Long, private val pfd: ParcelFileDescriptor?, private val file: File?) {
    private var channel: FileChannel? = null

    /** For WDT: [file]s are found by name in the share's directory. */
    val source: WdtSender.SourceFile
        get() = if (pfd != null) WdtSender.SourceFile.fromFileDescriptor(name, pfd) else WdtSender.SourceFile(name)

    /** Reads at [position]; returns the number of bytes read, 0 at the end. */
    fun read(position: Long, buf: ByteArray, len: Int): Int {
        if (pfd != null) {
            while (true) {
                try {
                    return Os.pread(pfd.fileDescriptor, buf, 0, len, position)
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EINTR) throw e
                }
            }
        }
        val ch = synchronized(this) {
            channel ?: FileInputStream(file!!).channel.also { channel = it }
        }
        return ch.read(ByteBuffer.wrap(buf, 0, len), position).coerceAtLeast(0)
    }

    fun close() {
        pfd?.close()
        synchronized(this) { channel?.close() }
    }
}

/**
 * The browser side of a share (see Share.kt): a page listing the files, and
 * the files over plain HTTP. Everything is under /<key>/, anything else is
 * "not found":
 *
 *   /<key>/             the page (assets/web/index.html, app.js, style.css)
 *   /<key>/files.json   what's shared
 *   /<key>/f/<i>/<name> file i (Range requests supported: resumable)
 *   /<key>/all.zip      everything, zipped (not compressed) on the fly
 */
class WebShare(
    private val files: List<SharedFile>,
    private val keyHex: String,
    private val deviceName: String,
    private val appLink: String,
    private val asset: (String) -> ByteArray?,
    private val listener: Listener,
) {
    interface Listener {
        /** A browser at [address] started downloading [what] ([bytes] of it). */
        fun onWebDownload(address: String, what: String, bytes: Long)

        /** Progress of the latest browser download. */
        fun onWebProgress(bytes: Long, total: Long)

        fun onWebDone(address: String, what: String, bytes: Long, seconds: Double, complete: Boolean)
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    fun handle(requestLine: String, input: InputStream, output: OutputStream, address: String) {
        val request = parse(requestLine, input) ?: return send(output, 400, "Bad Request", "text/plain", "Bad request".toByteArray())
        if (request.method != "GET" && request.method != "HEAD") {
            return send(output, 405, "Method Not Allowed", "text/plain", "Method not allowed".toByteArray())
        }
        val parts = request.path.split('/').drop(1) // "", key, ...
        val key = parts.getOrNull(0).orEmpty()
        if (!MessageDigest.isEqual(key.lowercase().toByteArray(), keyHex.toByteArray())) {
            return send(output, 404, "Not Found", "text/html; charset=utf-8", notFoundPage(), head = request.method == "HEAD")
        }
        val rest = parts.drop(1)
        val head = request.method == "HEAD"
        when {
            rest.isEmpty() -> send(output, 302, "Found", "text/plain", ByteArray(0), extra = mapOf("Location" to "/$keyHex/"))
            rest == listOf("") -> page(output, "index.html", head)
            rest == listOf("files.json") -> send(output, 200, "OK", "application/json", filesJson(), head = head)
            rest == listOf("all.zip") -> zip(output, address, head)
            rest.size == 1 && rest[0] in listOf("app.js", "style.css") -> page(output, rest[0], head)
            rest.size >= 2 && rest[0] == "f" -> {
                val file = rest[1].toIntOrNull()?.let { files.getOrNull(it) }
                if (file == null) {
                    send(output, 404, "Not Found", "text/plain", "Not found".toByteArray(), head = head)
                } else {
                    download(output, file, request, address, head)
                }
            }
            else -> send(output, 404, "Not Found", "text/plain", "Not found".toByteArray(), head = head)
        }
    }

    private fun parse(requestLine: String, input: InputStream): Request? {
        val words = requestLine.split(' ')
        if (words.size != 3 || !words[2].startsWith("HTTP/")) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (headers.size > 100) return null
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Request(words[0], words[1].substringBefore('?'), headers)
    }

    private fun page(output: OutputStream, name: String, head: Boolean) {
        val body = asset(name) ?: return send(output, 404, "Not Found", "text/plain", "Not found".toByteArray())
        val type = when {
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".js") -> "text/javascript; charset=utf-8"
            else -> "text/css; charset=utf-8"
        }
        send(output, 200, "OK", type, body, head = head)
    }

    private fun filesJson(): ByteArray {
        val list = JSONArray()
        files.forEachIndexed { i, f ->
            list.put(
                JSONObject()
                    .put("name", f.name)
                    .put("size", f.size)
                    .put("url", "f/$i/" + encodePath(f.name)),
            )
        }
        return JSONObject()
            .put("device", deviceName)
            .put("files", list)
            .put("total", files.sumOf { it.size })
            .put("zip", if (files.size > 1) "all.zip" else JSONObject.NULL)
            .put("appLink", appLink)
            .toString()
            .toByteArray()
    }

    private fun download(output: OutputStream, file: SharedFile, request: Request, address: String, head: Boolean) {
        var start = 0L
        var end = file.size - 1 // inclusive
        var status = 200
        val range = request.headers["range"]
        if (range != null) {
            val m = Regex("""^bytes=(\d*)-(\d*)$""").matchEntire(range.trim())
            val from = m?.groupValues?.get(1)?.toLongOrNull()
            val to = m?.groupValues?.get(2)?.toLongOrNull()
            when {
                m == null -> {} // not a single range: the whole file
                from != null -> {
                    start = from
                    if (to != null) end = minOf(to, end)
                    status = 206
                }
                to != null -> { // the last `to` bytes
                    start = maxOf(0, file.size - to)
                    status = 206
                }
            }
            if (status == 206 && (start > end || start >= file.size)) {
                return send(
                    output, 416, "Range Not Satisfiable", "text/plain", ByteArray(0),
                    extra = mapOf("Content-Range" to "bytes */${file.size}"),
                )
            }
        }
        val length = end - start + 1
        val headers = linkedMapOf(
            "Content-Disposition" to contentDisposition(file.name),
            "Accept-Ranges" to "bytes",
            "Content-Length" to length.toString(),
        )
        if (status == 206) headers["Content-Range"] = "bytes $start-$end/${file.size}"
        val type = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        val out = BufferedOutputStream(output, 256 * 1024)
        writeHeaders(out, status, if (status == 206) "Partial Content" else "OK", type, headers)
        if (head) return out.flush()
        transfer(address, file.name, length) { count ->
            val buf = ByteArray(256 * 1024)
            var position = start
            while (position <= end) {
                val n = file.read(position, buf, minOf(buf.size.toLong(), end - position + 1).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                position += n
                count(n)
            }
            out.flush()
            position > end
        }
    }

    private fun zip(output: OutputStream, address: String, head: Boolean) {
        val out = BufferedOutputStream(output, 256 * 1024)
        // Size unknown in advance: the end of the connection ends the body
        writeHeaders(
            out, 200, "OK", "application/zip",
            mapOf("Content-Disposition" to contentDisposition("WDT files.zip")),
        )
        if (head) return out.flush()
        val total = files.sumOf { it.size }
        transfer(address, "all the files (zip)", total) { count ->
            val zip = ZipOutputStream(out)
            zip.setLevel(Deflater.NO_COMPRESSION) // fast; photos and videos don't compress anyway
            val buf = ByteArray(256 * 1024)
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name).apply { size = file.size })
                var position = 0L
                while (true) {
                    val n = file.read(position, buf, buf.size)
                    if (n <= 0) break
                    zip.write(buf, 0, n)
                    position += n
                    count(n)
                }
                zip.closeEntry()
            }
            zip.finish()
            out.flush()
            true
        }
    }

    /** Runs [body] (true if complete), reporting it; [body] counts bytes sent. */
    private fun transfer(address: String, what: String, total: Long, body: ((Int) -> Unit) -> Boolean) {
        listener.onWebDownload(address, what, total)
        val startMs = SystemClock.elapsedRealtime()
        var sent = 0L
        var lastReport = 0L
        var complete = false
        try {
            complete = body { n ->
                sent += n
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= 250) {
                    lastReport = now
                    listener.onWebProgress(sent, total)
                }
            }
        } catch (e: Exception) {
            // the browser went away (cancelled, or stopped sharing)
        } finally {
            listener.onWebProgress(sent, total)
            val seconds = (SystemClock.elapsedRealtime() - startMs) / 1000.0
            listener.onWebDone(address, what, sent, seconds, complete)
        }
    }

    private fun send(
        output: OutputStream,
        status: Int,
        reason: String,
        type: String,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
        head: Boolean = false,
    ) {
        val out = BufferedOutputStream(output)
        writeHeaders(out, status, reason, type, extra + ("Content-Length" to body.size.toString()))
        if (!head) out.write(body)
        out.flush()
    }

    private fun writeHeaders(out: OutputStream, status: Int, reason: String, type: String, headers: Map<String, String>) {
        val text = StringBuilder("HTTP/1.1 $status $reason\r\n")
        val all = linkedMapOf(
            "Content-Type" to type,
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Content-Security-Policy" to
                "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
                "img-src 'self' data:; frame-ancestors 'none'",
            "Connection" to "close",
        )
        all.putAll(headers)
        for ((k, v) in all) text.append(k).append(": ").append(v).append("\r\n")
        text.append("\r\n")
        out.write(text.toString().toByteArray(Charsets.UTF_8))
    }

    private fun notFoundPage() = """
        <!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <title>WDT</title><p style="font:16px system-ui;margin:2em">This link isn't shared anymore,
        or it's incomplete. Ask for the link again.</p>
    """.trimIndent().toByteArray()

    private companion object {
        fun encodePath(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        /** attachment, with the UTF-8 name and an ASCII fallback (RFC 6266) */
        fun contentDisposition(name: String): String {
            val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''${encodePath(name)}"
        }
    }
}
