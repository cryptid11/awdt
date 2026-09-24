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
import com.facebook.wdt.WdtSender
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/*
 * Sending to a nearby receiver: a computer running `awdt receive <folder>`
 * (desktop/awdt.cpp, which documents the protocol).
 *
 * Discovery: a UDP broadcast on port 22355, receivers answer with their name.
 * Push: over TCP to the receiver, both sides exchange P-256 public keys and
 * derive the WDT encryption key from their ECDH secret (it's never sent); the
 * receiver starts a WDT receiver and tells where, and the app WDT-sends there.
 */

const val NEARBY_PORT = 22355

class NearbyReceiver(val host: String, val port: Int, val autoAccept: Boolean, val name: String) {
    override fun toString() = "$name ($host)"
}

object Nearby {
    /** Broadcasts a discovery request, returns who answered within [timeoutMs]. */
    fun discover(timeoutMs: Int = 1500): List<NearbyReceiver> {
        val found = LinkedHashMap<String, NearbyReceiver>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val request = "$PROTOCOL DISCOVER".toByteArray()
            for (target in broadcastAddresses()) {
                try {
                    socket.send(DatagramPacket(request, request.size, target, NEARBY_PORT))
                } catch (e: Exception) {
                    // an interface that can't broadcast: try the others
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1024)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                parseAnswer(packet.address, String(packet.data, 0, packet.length, Charsets.UTF_8))
                    ?.let { found["${it.host}:${it.port}"] = it }
            }
        }
        return found.values.toList()
    }

    /** "AWDT/1 HERE <port> <auto accept 0|1> <name>" */
    private fun parseAnswer(from: InetAddress, text: String): NearbyReceiver? {
        val words = text.trim().split(' ', limit = 5)
        if (words.size < 4 || words[0] != PROTOCOL || words[1] != "HERE") return null
        val port = words[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val name = words.getOrNull(4)?.takeIf { it.isNotBlank() } ?: from.hostAddress ?: "?"
        return NearbyReceiver(from.hostAddress ?: return null, port, words[3] == "1", name)
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val subnets = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNull { it.broadcast }
        } catch (e: Exception) {
            emptyList()
        }
        return (subnets + InetAddress.getByName("255.255.255.255")).distinct()
    }
}

/** Sends files to a nearby receiver. */
class Push(private val target: NearbyReceiver) : AutoCloseable {
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var sender: WdtSender? = null

    @Volatile
    private var closed = false

    /**
     * Blocking. [onAccepted] is called once the receiver accepted (it may
     * first ask its user); throws [ShareException] if it declines.
     */
    fun run(
        directory: File,
        files: List<WdtSender.SourceFile>,
        totalBytes: Long,
        deviceName: String,
        options: WdtOptions,
        progress: ProgressListener,
        onAccepted: () -> Unit,
    ): TransferReport {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val mine = generator.generateKeyPair()
        val myDer = mine.public.encoded // X.509 SubjectPublicKeyInfo
        val receiverUrl: String
        val key: ByteArray
        Socket().use { s ->
            socket = s
            if (closed) throw ShareException("Stopped")
            s.connect(InetSocketAddress(target.host, target.port), 10_000)
            // the receiver may ask its user first (up to a minute)
            s.soTimeout = 90_000
            val conn = LineConnection(s)
            val name = deviceName.replace(Regex("\\s+"), " ").trim().ifEmpty { "Android" }
            conn.send("PUSH", Base64.encodeToString(myDer, Base64.NO_WRAP), files.size.toString(),
                totalBytes.toString(), name)
            val accept = conn.receive("ACCEPT")
            if (accept.size < 2) throw ShareException("Bad answer from the receiver")
            val theirDer = Base64.decode(accept[0], Base64.NO_WRAP)
            val theirs = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirDer))
            val secret = KeyAgreement.getInstance("ECDH").run {
                init(mine.private)
                doPhase(theirs, true)
                generateSecret()
            }
            key = MessageDigest.getInstance("SHA-256")
                .digest("awdt-push".toByteArray() + secret + myDer + theirDer)
                .copyOf(16)
            receiverUrl = accept[1]
        }
        onAccepted()
        val url = senderUrl(receiverUrl, target.host, key)
        WdtSender(url, directory, options, files).use { s ->
            sender = s
            if (closed) s.abort()
            return s.transfer(progress)
        }
    }

    override fun close() {
        closed = true
        socket?.close()
        sender?.abort()
    }
}
