/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

/** Outcome of a transfer. Created by the native code. */
class TransferReport internal constructor(
    errorCode: Int,
    /** File data bytes transferred (excluding protocol overhead). */
    val bytesTransferred: Long,
    val numFiles: Long,
    val numBlocks: Long,
    val totalTimeSeconds: Double,
    val throughputMBps: Double,
    failed: Array<String>,
) {
    val errorCode: WdtErrorCode = WdtErrorCode.fromCode(errorCode)

    /** Files (and directories) that could not be transferred. */
    val failedFiles: List<String> = failed.toList()

    val isSuccess: Boolean
        get() = errorCode == WdtErrorCode.OK

    override fun toString(): String =
        "TransferReport(errorCode=$errorCode, bytesTransferred=$bytesTransferred, " +
            "numFiles=$numFiles, numBlocks=$numBlocks, totalTimeSeconds=$totalTimeSeconds, " +
            "throughputMBps=$throughputMBps, failedFiles=$failedFiles)"
}

/** Snapshot of an ongoing transfer, see [ProgressListener]. */
class TransferProgress internal constructor(
    val bytesTransferred: Long,
    /** Total bytes to transfer, or a negative value while still unknown. */
    val totalBytes: Long,
    /** Average throughput since the start. */
    val throughputMBps: Double,
    /** Recent throughput. */
    val currentThroughputMBps: Double,
    /** True for the last report, at the end of the transfer. */
    val isDone: Boolean,
) {
    /** Completion in [0, 100], or -1 while [totalBytes] is unknown. */
    val percent: Int
        get() = if (totalBytes > 0) (bytesTransferred * 100 / totalBytes).toInt() else -1

    override fun toString(): String =
        "TransferProgress(bytesTransferred=$bytesTransferred, totalBytes=$totalBytes, " +
            "throughputMBps=$throughputMBps, currentThroughputMBps=$currentThroughputMBps, " +
            "isDone=$isDone)"
}

/**
 * Receives progress reports, every [WdtOptions.progressReportIntervalMillis].
 * Called on a WDT thread: don't block, and post to the main thread to update
 * UI. Exceptions thrown here are logged and ignored.
 */
fun interface ProgressListener {
    fun onProgress(progress: TransferProgress)
}
