/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import android.util.Base64
import com.facebook.wdt.ProgressListener
import com.facebook.wdt.TransferReport
import com.facebook.wdt.WdtOptions
import com.facebook.wdt.WdtReceiver
import com.facebook.wdt.WdtTransfer
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyAgreement

/*
 * Phones finding each other: while the app is open, it listens on the same
 * port as awdt (22355):
 *
 *   UDP: the other phones' announcements (AWDT/1 ANNOUNCE, see Announcer):
 *        their shares, listed to download. And discovery requests (AWDT/1
 *        DISCOVER), answered like awdt does (AWDT/1 HERE ...) when the user
 *        accepts files from nearby devices.
 *   TCP: pushes (AWDT/1 PUSH ..., the protocol of desktop/awdt.cpp), each one
 *        accepted or declined by the user.
 */

/** A phone sharing files, as announced. */
class NearbyShare(val address: String, val name: String, val link: ShareLink)

/** Listens for the nearby devices' UDP messages (see above). */
class NearbyListener(
    private val name: () -> String,
    /** Whether to answer discovery requests (accepting pushes). */
    private val receiving: () -> Boolean,
) : AutoCloseable {
    private class Seen(val share: NearbyShare, val atMs: Long)

    private val shares = ConcurrentHashMap<String, Seen>()
    private var socket: DatagramSocket? = null

    /** False if the port is taken (e.g. another copy of the app): no listening. */
    fun start(): Boolean {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(NEARBY_PORT))
            }
        } catch (e: Exception) {
            return false
        }
        socket = s
        Thread({ loop(s) }, "wdt-listen").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** The shares announced by other phones in the last few seconds. */
    fun nearbyShares(): List<NearbyShare> {
        val now = System.currentTimeMillis()
        shares.entries.removeIf { now - it.value.atMs > 12_000 }
        return shares.values.map { it.share }.sortedBy { it.name }
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(1024)
        while (!s.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: Exception) {
                if (s.isClosed) return
                continue
            }
            val address = packet.address.hostAddress ?: continue
            if (address in Nearby.ownAddresses()) continue // our own broadcasts
            val words = String(packet.data, 0, packet.length, Charsets.UTF_8).trim().split(' ', limit = 5)
            if (words.size < 2 || words[0] != PROTOCOL) continue
            when (words[1]) {
                "DISCOVER" -> if (receiving()) {
                    val n = name().replace(Regex("\\s+"), " ").trim().ifEmpty { "Android" }
                    val reply = "$PROTOCOL HERE $NEARBY_PORT 0 $n".toByteArray()
                    try {
                        s.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    } catch (e: Exception) {
                        // gone
                    }
                }
                "ANNOUNCE" -> if (words.size == 5) { // <port|0> <key|-> <name>
                    val port = words[2].toIntOrNull() ?: 0
                    val link = if (port in 1..65535) ShareLink.parse("http://$address:$port/${words[3]}") else null
                    if (link == null) {
                        shares.remove(address)
                    } else {
                        shares[address] = Seen(NearbyShare(address, words[4], link), System.currentTimeMillis())
                    }
                }
            }
        }
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}

/** A device asking to send files here, see [PushServer]. */
class PushRequest internal constructor(
    val address: String,
    val name: String,
    val files: Int,
    val bytes: Long,
    private val conn: LineConnection,
    private val theirDer: ByteArray,
) {
    fun decline(reason: String) = conn.send("ERROR", reason)

    /**
     * Receives the files into [directory] (blocking): the WDT key comes from
     * an ECDH key agreement with the sender. [onStart] gets the transfer (to
     * stop it).
     */
    fun receive(
        directory: File,
        options: WdtOptions,
        progress: ProgressListener,
        onStart: (WdtTransfer) -> Unit,
    ): TransferReport {
        val mine = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val myDer = mine.public.encoded
        val theirs = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirDer))
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(mine.private)
            doPhase(theirs, true)
            generateSecret()
        }
        // same derivation as awdt: sha256("awdt-push" + secret + sender's key + receiver's key)
        val key = MessageDigest.getInstance("SHA-256")
            .digest("awdt-push".toByteArray() + secret + theirDer + myDer)
            .copyOf(16)
        WdtReceiver(directory, options, hostName = RECEIVER_HOST, encryptionKey = key).use { r ->
            onStart(r)
            val url = r.start(progress)
            val withoutKey = url.replace(Regex("""Enc=[^&]*&?"""), "").trimEnd('&', '?')
            conn.send("ACCEPT", Base64.encodeToString(myDer, Base64.NO_WRAP), withoutKey)
            return r.awaitFinish()
        }
    }
}

/**
 * Accepts pushes from nearby devices (like `awdt receive`) on TCP port 22355;
 * [onPush] decides, one at a time (it may take a while: it asks the user).
 */
class PushServer(private val onPush: (PushRequest) -> Unit) : AutoCloseable {
    private var server: ServerSocket? = null

    /** False if the port is taken: no receiving. */
    fun start(): Boolean {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(NEARBY_PORT))
            }
        } catch (e: Exception) {
            return false
        }
        server = s
        Thread({ loop(s) }, "wdt-push-server").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun loop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket: Socket = try {
                s.accept()
            } catch (e: Exception) {
                if (s.isClosed) return
                continue
            }
            try {
                socket.use {
                    // long enough for the user to answer, and for the sender to start
                    socket.soTimeout = 90_000
                    val conn = LineConnection(socket)
                    val push = conn.receive("PUSH")
                    if (push.size < 3) throw ShareException("Bad request")
                    val name = push.drop(3).joinToString(" ").ifEmpty { socket.inetAddress.hostAddress ?: "?" }
                    onPush(
                        PushRequest(
                            socket.inetAddress.hostAddress ?: "?", name,
                            push[1].toIntOrNull() ?: 0, push[2].toLongOrNull() ?: -1,
                            conn, Base64.decode(push[0], Base64.NO_WRAP),
                        ),
                    )
                }
            } catch (e: Exception) {
                // that device went away, or wasn't a WDT app: next
            }
        }
    }

    override fun close() {
        server?.close()
        server = null
    }
}
