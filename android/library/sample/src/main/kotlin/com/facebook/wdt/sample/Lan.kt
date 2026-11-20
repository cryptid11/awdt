/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/*
 * Reaching the other device through the right network.
 *
 * Android sends an app's connections through the default network. Connected
 * to a hotspot (another phone's) without internet behind it, while mobile
 * data is on, the default network is mobile data: connections to the
 * hotspot's devices (192.168.x.y) then go out over mobile data, and fail.
 * The other way around works (the hotspot's own routes are reachable from
 * any network), which is why only one direction broke.
 *
 * So connections to a device on the local subnet of a network that isn't the
 * default one are bound to that network: Java sockets one by one, WDT's
 * native sockets by binding the whole process while it sends.
 */
object Lan {
    @Volatile
    private var cm: ConnectivityManager? = null

    fun init(context: Context) {
        cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    }

    /**
     * The network to reach [host] through, if it isn't the default one: one
     * with [host] on its local subnet (a Wi-Fi without internet). Null when
     * the default network does (or [host] is a name, not an address).
     */
    fun networkFor(host: String): Network? {
        val cm = cm ?: return null
        val address = literal(host) ?: return null
        return try {
            @Suppress("DEPRECATION")
            val match = cm.allNetworks.firstOrNull { n ->
                cm.getLinkProperties(n)?.routes.orEmpty().any { r ->
                    !r.isDefaultRoute && r.destination.prefixLength > 0 && r.matches(address)
                }
            }
            match?.takeIf { it != cm.activeNetwork }
        } catch (e: Exception) {
            null // no access to the network state: as before
        }
    }

    /** For the log: why [host] is reached the way it is, null if nothing special. */
    fun describe(host: String): String? {
        networkFor(host) ?: return null
        return "$host is on a Wi-Fi without internet (a hotspot?): connecting through it, " +
            "not through mobile data"
    }

    fun bind(socket: Socket, host: String) {
        val n = networkFor(host) ?: return
        try {
            n.bindSocket(socket)
        } catch (e: Exception) {
            // e.g. an always-on VPN that forbids it: try the default network
        }
    }

    fun bind(socket: DatagramSocket, host: String) {
        val n = networkFor(host) ?: return
        try {
            n.bindSocket(socket)
        } catch (e: Exception) {
            // as above
        }
    }

    private val processLock = Any()
    private var processUsers = 0

    /**
     * Runs [block] (WDT connecting to [host]: native sockets, which can't be
     * bound one by one) with the process bound to [host]'s network, if needed.
     */
    fun <T> withProcessNetwork(host: String, block: () -> T): T {
        val cm = cm
        val n = networkFor(host)
        if (cm == null || n == null) return block()
        synchronized(processLock) {
            processUsers++
            cm.bindProcessToNetwork(n)
        }
        try {
            return block()
        } finally {
            synchronized(processLock) {
                if (--processUsers == 0) cm.bindProcessToNetwork(null)
            }
        }
    }

    /** The transports of the networks' interfaces, by interface name. */
    fun interfaceKinds(): Map<String, Kind> {
        val cm = cm ?: return emptyMap()
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.mapNotNull { n ->
                val name = cm.getLinkProperties(n)?.interfaceName ?: return@mapNotNull null
                val caps = cm.getNetworkCapabilities(n)
                val kind = when {
                    caps == null -> Kind.OTHER
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Kind.OTHER
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Kind.ETHERNET
                    else -> Kind.OTHER // mobile data...
                }
                name to kind
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    enum class Kind { WIFI, ETHERNET, OTHER }

    /** [host] as an address, without a DNS lookup; null if it's a name. */
    private fun literal(host: String): InetAddress? {
        val h = host.trim('[', ']')
        val isIp = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h) || (h.contains(':') && Regex("""^[0-9a-fA-F:.%\w]+$""").matches(h))
        if (!isIp) return null
        return try {
            InetAddress.getByName(h)
        } catch (e: Exception) {
            null
        }
    }
}
