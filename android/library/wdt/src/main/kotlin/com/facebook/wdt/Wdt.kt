/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

/** Library wide information and settings. */
object Wdt {
    /** WDT version, e.g. "1.32.1910230". */
    @JvmStatic
    val version: String
        get() = NativeWdt.version()

    /** Wire protocol version: both ends of a transfer negotiate on it. */
    @JvmStatic
    val protocolVersion: Int
        get() = NativeWdt.protocolVersion()

    /**
     * WDT logs to logcat with the tag "WDT". By default only warnings and
     * errors are logged.
     *
     * @param minLevel lowest level logged: [LogLevel.INFO] for all of WDT's
     *   (quite detailed) transfer logs
     * @param verbosity extra WDT debug logging (glog's -v), 0 to disable
     */
    @JvmStatic
    @JvmOverloads
    fun setLogLevel(minLevel: LogLevel, verbosity: Int = 0) {
        NativeWdt.setLogLevel(minLevel.ordinal, verbosity)
    }

    enum class LogLevel { INFO, WARNING, ERROR }
}
