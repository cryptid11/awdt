/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Sends files to a [WdtReceiver], given its connection URL.
 *
 * ```
 * WdtSender(url, dir).use { sender ->
 *     val report = sender.transfer()
 * }
 * ```
 *
 * [transfer] blocks: don't call it on the main thread.
 *
 * @param directory what to send: the whole tree, or the base for [files]
 * @param files if not null, only these files are sent (the directory isn't
 *   walked)
 */
class WdtSender @JvmOverloads constructor(
    connectionUrl: String,
    directory: File,
    options: WdtOptions = WdtOptions(),
    files: List<SourceFile>? = null,
) : WdtTransfer(
    NativeWdt.senderCreate(
        connectionUrl,
        directory.path,
        options.toNative(),
        files?.map { it.name }?.toTypedArray(),
        files?.map { it.size }?.toLongArray(),
        files?.map { it.fd }?.toIntArray(),
    ),
) {
    /** Connects and sends everything, blocking until done, failed or aborted. */
    @JvmOverloads
    fun transfer(listener: ProgressListener? = null): TransferReport =
        withHandle { NativeWdt.senderTransfer(it, listener) }

    /**
     * A file to send.
     *
     * @param name path relative to the sender's directory; also the path the
     *   receiver creates
     * @param size size in bytes, or -1 to look it up (not allowed with [fd])
     * @param fd an open, readable file descriptor to read from instead of the
     *   path: this is how to send content only reachable through a
     *   ContentResolver. It must stay open until the transfer ends; WDT does
     *   not close it.
     */
    class SourceFile @JvmOverloads constructor(
        val name: String,
        val size: Long = -1,
        val fd: Int = -1,
    ) {
        init {
            require(fd < 0 || size >= 0) { "The size is required with a file descriptor" }
        }

        companion object {
            /** Sends [pfd]'s content as [name]; keep [pfd] open during the transfer. */
            @JvmStatic
            fun fromFileDescriptor(name: String, pfd: ParcelFileDescriptor): SourceFile =
                SourceFile(name, pfd.statSize, pfd.fd)
        }
    }
}
