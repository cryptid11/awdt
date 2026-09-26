/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import android.os.SystemClock

/**
 * Transfer speed averaged over the last [windowMs]. WDT's own "current
 * throughput" is measured over half a second, which on Wi-Fi (delivering in
 * bursts) swings between 0 and huge values.
 */
class SpeedMeter(private val windowMs: Long = 3000) {
    private val times = ArrayDeque<Long>()
    private val amounts = ArrayDeque<Long>()

    /** Records [bytes] transferred so far; returns the speed in MB/s. */
    fun update(bytes: Long, nowMs: Long = SystemClock.elapsedRealtime()): Double {
        if (amounts.isNotEmpty() && bytes < amounts.last()) reset() // a new transfer
        times.addLast(nowMs)
        amounts.addLast(bytes)
        while (times.size > 2 && nowMs - times.first() > windowMs) {
            times.removeFirst()
            amounts.removeFirst()
        }
        val ms = nowMs - times.first()
        return if (ms < 500) 0.0 else (bytes - amounts.first()) / 1e3 / ms
    }

    fun reset() {
        times.clear()
        amounts.clear()
    }
}
