/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import com.facebook.wdt.ProgressListener
import com.facebook.wdt.TransferReport
import com.facebook.wdt.WdtOptions
import com.facebook.wdt.WdtReceiver
import com.facebook.wdt.WdtSender
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/*
 * Share links: the device with the files shares a link, the other one opens
 * it and downloads.
 *
 * WDT itself only works the other way: the receiver listens and the sender
 * connects to the receiver's URL. So the sharing device runs a small
 * handshake server (ShareServer) and the downloading device (ShareDownload)
 * connects to it:
 *
 *   downloader -> sharer   AWDT/1 HELLO <proof>        proves it has the link
 *   sharer -> downloader   AWDT/1 OFFER <files> <bytes>
 *   downloader             starts a WDT receiver, with the key from the link
 *   downloader -> sharer   AWDT/1 RECEIVER <its wdt:// URL, without the key>
 *   sharer                 WDT-sends to that URL (host: the connection's peer)
 *
 * The link is http://<host>:<port>/<key> (or awdt://..., the older form):
 * the 16 byte key encrypts the WDT transfer, and the proof is a hash of it.
 *
 * The same port also serves browsers (WebShare.kt): opening the link in one
 * shows the files, downloaded over plain HTTP. The first line of each
 * connection tells which it is. Browsers send the key in the clear (it's in
 * the address), so the link is the secret, protected by nothing more than
 * the local network.
 */

internal const val PROTOCOL = "AWDT/1"

/** Placeholder host in the downloader's URL, replaced by the sharer. */
internal const val RECEIVER_HOST = "receiver"

class ShareLink(val host: String, val port: Int, val key: ByteArray) {
    private val hostPart get() = if (host.contains(':')) "[$host]" else host

    /** What to share: opens in a browser, or in this app. */
    override fun toString() = "http://$hostPart:$port/${key.toHex()}"

    /** Opens this app (for the page's "Open in the app" button). */
    fun appLink() = "awdt://$hostPart:$port/${key.toHex()}"

    companion object {
        private val LINK = Regex("""^(?:awdt|http)://(\[[0-9a-fA-F:.]+]|[^:/\[\]]+):(\d{1,5})/([0-9a-fA-F]{32})/?$""")

        /** Whether [text] looks like a share link (valid or not) */
        fun isLink(text: String) = text.trim().let {
            it.startsWith("awdt://") || (it.startsWith("http://") && parse(it) != null)
        }

        /** @return null if [text] isn't a valid share link */
        fun parse(text: String): ShareLink? {
            val m = LINK.matchEntire(text.trim()) ?: return null
            val port = m.groupValues[2].toInt().takeIf { it in 1..65535 } ?: return null
            return ShareLink(m.groupValues[1].trim('[', ']'), port, m.groupValues[3].hexToBytes())
        }

        fun newKey() = ByteArray(16).also { SecureRandom().nextBytes(it) }
    }
}

class ShareException(message: String) : Exception(message)

private fun proof(key: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest("awdt-proof".toByteArray() + key).toHex()

internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

/**
 * A receiver's URL (with the [RECEIVER_HOST] placeholder and without its
 * key), pointed at [host] and with [key]: what the sender uses.
 */
internal fun senderUrl(receiverUrl: String, host: String, key: ByteArray): String {
    // wdt://receiver?ports=a,b,... or, for consecutive ports,
    // wdt://receiver:<first port>?num_ports=n...
    val m = Regex("""^wdt://$RECEIVER_HOST(:\d{1,5})?\?(.*)$""").matchEntire(receiverUrl)
        ?: throw ShareException("Unexpected receiver URL")
    val params = m.groupValues[2].split('&')
        .filter { it.isNotEmpty() && !it.startsWith("Enc=", ignoreCase = true) }
    val h = if (host.contains(':')) "[$host]" else host
    return "wdt://$h${m.groupValues[1]}?" + (params + "Enc=2:${key.toHex()}").joinToString("&")
}

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal class LineConnection(
    val socket: Socket,
    input: InputStream = socket.getInputStream(),
) : AutoCloseable {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

    fun send(vararg words: String) {
        writer.write(PROTOCOL + " " + words.joinToString(" ") + "\n")
        writer.flush()
    }

    /** The words after the protocol tag; throws on anything unexpected. */
    fun receive(expected: String): List<String> =
        parse(reader.readLine() ?: throw ShareException("Connection closed"), expected)

    fun parse(line: String, expected: String): List<String> {
        if (line.length > 8192) throw ShareException("Message too long")
        val words = line.split(' ')
        if (words.size < 2 || words[0] != PROTOCOL) throw ShareException("Not a WDT share: $line")
        if (words[1] == "ERROR") throw ShareException(words.drop(2).joinToString(" "))
        if (words[1] != expected) throw ShareException("Expected $expected, got ${words[1]}")
        return words.drop(2)
    }

    override fun close() = socket.close()
}

/**
 * Serves [files] (read from [directory] when they don't have a file
 * descriptor) to whoever opens [link], in this app (with WDT, one downloader
 * after the other) or in a browser, until [close].
 */
class ShareServer(
    host: String,
    private val directory: File,
    private val files: List<SharedFile>,
    private val totalBytes: Long,
    private val options: () -> WdtOptions,
    private val deviceName: String,
    /** The web page's files (index.html, app.js, style.css) */
    private val webAsset: (String) -> ByteArray?,
    bindAddress: String? = null,
) : AutoCloseable {
    private val key = ShareLink.newKey()
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(if (bindAddress != null) InetSocketAddress(bindAddress, 0) else InetSocketAddress(0))
    }

    val link = ShareLink(host, server.localPort, key)

    @Volatile
    private var closed = false

    @Volatile
    private var sender: WdtSender? = null

    /** WDT transfers one at a time (browsers download in parallel). */
    private val wdtLock = Any()
    private val connections = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    interface Listener : ProgressListener, WebShare.Listener {
        fun onDownloaderConnected(address: String)

        fun onFinished(address: String, report: TransferReport)

        fun onError(message: String)
    }

    /** Accepts downloaders until [close]; blocking. */
    fun serve(listener: Listener) {
        val web = WebShare(files, key.toHex(), deviceName, link.appLink(), webAsset, listener)
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                if (closed) return
                throw e
            }
            connections += socket
            Thread({
                try {
                    handle(socket, web, listener)
                } catch (e: Exception) {
                    if (!closed) listener.onError(e.message ?: e.toString())
                } finally {
                    connections -= socket
                    socket.close()
                }
            }, "wdt-share").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket, web: WebShare, listener: Listener) {
        val address = socket.inetAddress.hostAddress ?: "?"
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        val first = readLine(input) ?: return
        if (!first.startsWith("$PROTOCOL ")) {
            web.handle(first, input, socket.getOutputStream(), address)
            return
        }
        val conn = LineConnection(socket, input)
        val (theirProof) = conn.parse(first, "HELLO")
        if (!MessageDigest.isEqual(theirProof.toByteArray(), proof(key).toByteArray())) {
            conn.send("ERROR", "wrong link")
            throw ShareException("$address: wrong link, refused")
        }
        conn.send("OFFER", files.size.toString(), totalBytes.toString())
        val receiverUrl = conn.receive("RECEIVER").singleOrNull()
            ?: throw ShareException("$address: bad receiver URL")
        synchronized(wdtLock) {
            if (closed) return
            listener.onDownloaderConnected(address)
            val url = senderUrl(receiverUrl, address, key)
            WdtSender(url, directory, options(), files.map { it.source }).use { s ->
                sender = s
                val report = s.transfer(listener)
                sender = null
                listener.onFinished(address, report)
            }
        }
    }

    override fun close() {
        closed = true
        server.close()
        sender?.abort()
        connections.forEach { it.close() } // browsers' downloads
    }
}

/**
 * A line of at most 8 KB (without its line end), decoded as UTF-8; null at
 * the end of the stream.
 */
internal fun readLine(input: InputStream): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
        if (b == '\n'.code) break
        if (bytes.size() >= 8192) throw ShareException("Line too long")
        bytes.write(b)
    }
    return bytes.toString("UTF-8").trimEnd('\r')
}

/** The downloading side of a share link. */
class ShareDownload(private val link: ShareLink) : AutoCloseable {
    @Volatile
    private var receiver: WdtReceiver? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var closed = false

    /**
     * Connects to the sharer and receives its files into [directory];
     * blocking. [onOffer] is called with the number of files and bytes.
     */
    fun run(
        directory: File,
        options: WdtOptions,
        progress: ProgressListener,
        onOffer: (files: Int, bytes: Long) -> Unit,
    ): TransferReport {
        Socket().use { socket ->
            this.socket = socket
            if (closed) throw ShareException("Stopped")
            socket.connect(InetSocketAddress(link.host, link.port), 10_000)
            socket.soTimeout = 30_000
            val conn = LineConnection(socket)
            conn.send("HELLO", proof(link.key))
            val offer = conn.receive("OFFER")
            onOffer(offer.getOrNull(0)?.toIntOrNull() ?: 0, offer.getOrNull(1)?.toLongOrNull() ?: -1)
            WdtReceiver(directory, options, hostName = RECEIVER_HOST, encryptionKey = link.key).use { r ->
                receiver = r
                if (closed) r.abort()
                val url = r.start(progress)
                // The key stays out of the network: the sharer has it
                val withoutKey = url.replace(Regex("""Enc=[^&]*&?"""), "").trimEnd('&', '?')
                conn.send("RECEIVER", withoutKey)
                return r.awaitFinish()
            }
        }
    }

    override fun close() {
        closed = true
        socket?.close() // unblocks connecting / reading
        receiver?.abort()
    }
}
