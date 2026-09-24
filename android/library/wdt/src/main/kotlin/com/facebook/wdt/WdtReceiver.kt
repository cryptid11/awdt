/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

import java.io.File

/**
 * Receives files into [directory].
 *
 * ```
 * WdtReceiver(dir, hostName = myIp).use { receiver ->
 *     val url = receiver.start()     // give this URL to the sender
 *     val report = receiver.awaitFinish()
 * }
 * ```
 *
 * [awaitFinish] blocks: don't call it on the main thread.
 *
 * @param hostName host (or IP) the sender should connect to, put in the
 *   connection URL. Defaults to the device's host name, which on Android is
 *   usually "localhost": set it to the device's IP address for transfers from
 *   another machine.
 * @param transferId identifies the transfer, generated when null
 */
class WdtReceiver @JvmOverloads constructor(
    directory: File,
    options: WdtOptions = WdtOptions(),
    hostName: String? = null,
    transferId: String? = null,
) : WdtTransfer(
    NativeWdt.receiverCreate(directory.path, options.toNative(), hostName, transferId),
) {
    /** The connection URL, once [start]ed. It contains the encryption secret. */
    @Volatile
    var connectionUrl: String? = null
        private set

    /**
     * Binds the ports and starts accepting the sender's connections.
     *
     * @return the connection URL to give to the sender
     * @throws WdtException if the receiver can't be set up (e.g. no free port)
     */
    @JvmOverloads
    @Throws(WdtException::class)
    fun start(listener: ProgressListener? = null): String =
        withHandle { NativeWdt.receiverStart(it, listener) }.also { connectionUrl = it }

    /**
     * Blocks until the transfer is complete, failed or aborted. The receiver
     * waits for the sender to connect for about
     * [WdtOptions.acceptTimeoutMillis] * [WdtOptions.maxAcceptRetries].
     */
    fun awaitFinish(): TransferReport {
        checkNotNull(connectionUrl) { "start() must be called first" }
        return withHandle { NativeWdt.finish(it) }
    }
}
