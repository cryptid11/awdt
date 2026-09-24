/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WdtOptionsTest {
    @Test
    fun unsetOptionsAreNotPassed() {
        assertEquals(0, WdtOptions().toNative().size)
    }

    @Test
    fun optionsUseNativeNames() {
        val options = WdtOptions().apply {
            numPorts = 4
            enableChecksum = true
            encryption = WdtOptions.Encryption.NONE
            blockSizeMbytes = 0.5
        }
        assertArrayEquals(
            arrayOf(
                "num_ports", "4",
                "block_size_mbytes", "0.5",
                "enable_checksum", "true",
                "encryption_type", "none",
            ),
            options.toNative(),
        )
    }

    @Test
    fun rawOptionsOverrideProperties() {
        val options = WdtOptions().apply { numPorts = 4 }
            .set("num_ports", 2)
            .set("two_phases", true)
        assertArrayEquals(
            arrayOf("num_ports", "2", "two_phases", "true"),
            options.toNative(),
        )
    }
}
