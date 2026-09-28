/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMeterTest {
    @Test
    fun steadyRate() {
        val meter = SpeedMeter()
        var speed = 0.0
        for (i in 0..40) speed = meter.update(i * 1_000_000L, i * 250L) // 4 MB/s
        assertEquals(4.0, speed, 0.01)
    }

    /** WDT counts whole blocks: the amount jumps, then stays the same for seconds. */
    @Test
    fun blockSizedStepsNeverShowZero() {
        val meter = SpeedMeter()
        val block = 16_777_216L
        var bytes = 0L
        for (i in 0..200) { // reports every 250 ms, a block every 4 s: ~4.2 MB/s
            val now = i * 250L
            if (now > 0 && now % 4000 == 0L) bytes += block
            val speed = meter.update(bytes, now)
            if (now >= 4000) assertTrue("speed at $now ms: $speed", speed > 1.0 && speed < 8.0)
        }
    }

    /** Reports in bursts (late UI thread): still an average over time. */
    @Test
    fun burstsOfReportsAtTheSameTime() {
        val meter = SpeedMeter()
        var bytes = 0L
        var speed = 0.0
        for (burst in 0..10) {
            val now = burst * 5000L
            repeat(20) {
                bytes += 1_000_000
                speed = meter.update(bytes, now)
            }
        }
        assertTrue("speed $speed", speed > 1.0)
    }

    @Test
    fun newTransferResets() {
        val meter = SpeedMeter()
        for (i in 0..20) meter.update(i * 10_000_000L, i * 250L)
        assertEquals(0.0, meter.update(0, 6000), 0.0)
    }
}
