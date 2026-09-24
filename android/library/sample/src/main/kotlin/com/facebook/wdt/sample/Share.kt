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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom

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
 * The link is awdt://<host>:<port>/<key>: the 16 byte key encrypts the WDT
 * transfer and never goes over the network; the proof is a hash of it.
 */

private const val PROTOCOL = "AWDT/1"
private const val LINK_SCHEME = "awdt://"

/** Placeholder host in the downloader's URL, replaced by the sharer. */
private const val RECEIVER_HOST = "receiver"

class ShareLink(val host: String, val port: Int, val key: ByteArray) {
    override fun toString(): String {
        val h = if (host.contains(':')) "[$host]" else host
        return "$LINK_SCHEME$h:$port/${key.toHex()}"
    }

    companion object {
        fun isLink(text: String) = text.trim().startsWith(LINK_SCHEME)

        /** @return null if [text] isn't a valid share link */
        fun parse(text: String): ShareLink? {
            val m = Regex("""^awdt://(\[[0-9a-fA-F:.]+]|[^:/\[\]]+):(\d{1,5})/([0-9a-fA-F]{32})/?$""")
                .matchEntire(text.trim()) ?: return null
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

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private class LineConnection(val socket: Socket) : AutoCloseable {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

    fun send(vararg words: String) {
        writer.write(PROTOCOL + " " + words.joinToString(" ") + "\n")
        writer.flush()
    }

    /** The words after the protocol tag; throws on anything unexpected. */
    fun receive(expected: String): List<String> {
        val line = reader.readLine() ?: throw ShareException("Connection closed")
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
 * Serves [files] (taken from [directory]) to whoever opens [link], one
 * downloader after the other, until [close].
 */
class ShareServer(
    host: String,
    private val directory: File,
    private val files: List<WdtSender.SourceFile>,
    private val totalBytes: Long,
    private val options: () -> WdtOptions,
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

    interface Listener : ProgressListener {
        fun onDownloaderConnected(address: String)

        fun onFinished(address: String, report: TransferReport)

        fun onError(message: String)
    }

    /** Accepts downloaders until [close]; blocking. */
    fun serve(listener: Listener) {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                if (closed) return
                throw e
            }
            val peer = socket.inetAddress
            val address = peer.hostAddress ?: "?"
            try {
                LineConnection(socket).use { conn ->
                    socket.soTimeout = 30_000
                    val (theirProof) = conn.receive("HELLO")
                    if (!MessageDigest.isEqual(theirProof.toByteArray(), proof(key).toByteArray())) {
                        conn.send("ERROR", "wrong link")
                        throw ShareException("$address: wrong link, refused")
                    }
                    conn.send("OFFER", files.size.toString(), totalBytes.toString())
                    val receiverUrl = conn.receive("RECEIVER").singleOrNull()
                        ?: throw ShareException("$address: bad receiver URL")
                    listener.onDownloaderConnected(address)
                    val host = if (peer is Inet6Address) "[$address]" else address
                    val url = senderUrl(receiverUrl, host, key)
                    WdtSender(url, directory, options(), files).use { s ->
                        sender = s
                        if (closed) s.abort()
                        val report = s.transfer(listener)
                        sender = null
                        listener.onFinished(address, report)
                    }
                }
            } catch (e: Exception) {
                if (!closed) listener.onError(e.message ?: e.toString())
            }
        }
    }

    override fun close() {
        closed = true
        server.close()
        sender?.abort()
    }

    private companion object {
        /** The downloader's URL, pointed at its address and with the key. */
        fun senderUrl(receiverUrl: String, host: String, key: ByteArray): String {
            val prefix = "wdt://$RECEIVER_HOST?"
            if (!receiverUrl.startsWith(prefix)) throw ShareException("Unexpected receiver URL")
            val params = receiverUrl.removePrefix(prefix).split('&')
                .filter { it.isNotEmpty() && !it.startsWith("Enc=", ignoreCase = true) }
            return "wdt://$host?" + (params + "Enc=2:${key.toHex()}").joinToString("&")
        }
    }
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
