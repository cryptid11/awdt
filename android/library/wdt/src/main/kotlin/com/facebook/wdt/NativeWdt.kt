/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

/** JNI entry points, implemented in wdt_jni.cpp. */
internal object NativeWdt {
    init {
        System.loadLibrary("wdtjni")
    }

    @JvmStatic external fun version(): String
    @JvmStatic external fun protocolVersion(): Int
    @JvmStatic external fun errorName(code: Int): String
    @JvmStatic external fun errorCount(): Int
    @JvmStatic external fun setLogLevel(minLevel: Int, verbosity: Int)

    /** @param options alternating option names and values */
    @JvmStatic external fun receiverCreate(
        directory: String,
        options: Array<String>,
        hostName: String?,
        transferId: String?,
    ): Long

    /** Binds the ports and starts accepting; returns the connection URL. */
    @JvmStatic external fun receiverStart(handle: Long, listener: ProgressListener?): String

    @JvmStatic external fun senderCreate(
        url: String,
        directory: String,
        options: Array<String>,
        fileNames: Array<String>?,
        fileSizes: LongArray?,
        fileFds: IntArray?,
    ): Long

    /** Runs the whole transfer (blocking). */
    @JvmStatic external fun senderTransfer(handle: Long, listener: ProgressListener?): TransferReport

    /** Waits for the end of the transfer. */
    @JvmStatic external fun finish(handle: Long): TransferReport
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun destroy(handle: Long)
}
