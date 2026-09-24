/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Common part of [WdtSender] and [WdtReceiver]: owns the native transfer.
 *
 * Thread safety: [abort] and [close] may be called from any thread, including
 * while another thread is blocked in the transfer; [close] then aborts and
 * waits for that thread to return. So don't call [close] from a
 * [ProgressListener] (use [abort]).
 */
abstract class WdtTransfer internal constructor(handle: Long) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private var handle: Long = handle

    /** Runs [block] with the live native handle, while keeping it alive. */
    internal fun <T> withHandle(block: (Long) -> T): T =
        lock.read {
            check(handle != 0L) { "${javaClass.simpleName} is closed" }
            block(handle)
        }

    /**
     * Stops the transfer as soon as possible (within
     * [WdtOptions.abortCheckIntervalMillis]). The blocking call then returns
     * a report with [WdtErrorCode.ABORT]. Does nothing once closed.
     */
    fun abort() {
        // Never blocks (it may be called from a ProgressListener, on the
        // thread close() waits for). Failing to lock means close() is running,
        // and it aborts itself.
        val readLock = lock.readLock()
        if (!readLock.tryLock()) return
        try {
            if (handle != 0L) NativeWdt.abort(handle)
        } finally {
            readLock.unlock()
        }
    }

    /** Aborts any ongoing transfer and releases the native resources. */
    override fun close() {
        abort()
        lock.write {
            if (handle != 0L) {
                NativeWdt.destroy(handle)
                handle = 0L
            }
        }
    }
}
