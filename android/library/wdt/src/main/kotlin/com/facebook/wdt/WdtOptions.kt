/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

/**
 * Transfer options. Unset (null) properties keep WDT's defaults. See
 * WdtOptions.h for the details of each option.
 *
 * The most useful options have properties. Any other WDT option can be set by
 * its native name with [set], e.g. `set("two_phases", true)`.
 */
class WdtOptions {
    /** Number of parallel connections (default 8). */
    var numPorts: Int? = null

    /** First port the receiver listens on (default 22356), 0 for any free ports. */
    var startPort: Int? = null

    /** Receiver: fail instead of picking other ports if these are busy. */
    var staticPorts: Boolean? = null

    /** Size of the blocks files are split into, in MB (default 16). */
    var blockSizeMbytes: Double? = null

    /** Checksums (CRC32C) on the data, in addition to encryption. */
    var enableChecksum: Boolean? = null

    /** Default [Encryption.AES128_GCM]. Set by the receiver, the sender follows. */
    var encryption: Encryption? = null

    /** Throttle to this average rate, MB/s (default: unlimited). */
    var avgMbytesPerSec: Double? = null

    /** Peak rate when throttling, MB/s. */
    var maxMbytesPerSec: Double? = null

    /** Resume interrupted transfers (the receiver keeps a `.wdt.log` journal). */
    var enableDownloadResumption: Boolean? = null

    /** Receiver: allow overwriting existing files. */
    var overwrite: Boolean? = null

    /** Receiver: fsync files when they are complete (default true). */
    var fsync: Boolean? = null

    /** Sender: follow symbolic links while walking the directory. */
    var followSymlinks: Boolean? = null

    /** Sender: only send files whose relative path matches this regex. */
    var includeRegex: String? = null

    /** Sender: skip files whose relative path matches this regex. */
    var excludeRegex: String? = null

    /** Sender: don't descend into directories matching this regex. */
    var pruneDirRegex: String? = null

    var connectTimeoutMillis: Int? = null
    var readTimeoutMillis: Int? = null
    var writeTimeoutMillis: Int? = null

    /**
     * Receiver: how long to wait for the sender to connect is roughly
     * `acceptTimeoutMillis * maxAcceptRetries` (default 100 ms * 500).
     */
    var acceptTimeoutMillis: Int? = null
    var maxAcceptRetries: Int? = null

    /** Connection retries per port (default 20). */
    var maxRetries: Int? = null

    /** Interval between [ProgressListener] calls (default 200). */
    var progressReportIntervalMillis: Int? = null

    /** How quickly an [WdtTransfer.abort] is noticed (default 200). */
    var abortCheckIntervalMillis: Int? = null

    private val extra = LinkedHashMap<String, String>()

    /**
     * Sets any WDT option by its native name (the `wdt` command line flag
     * name). Unknown names and invalid values are rejected when the transfer
     * is created.
     */
    fun set(name: String, value: Any): WdtOptions = apply { extra[name] = value.toString() }

    enum class Encryption(internal val nativeName: String) {
        NONE("none"),
        AES128_CTR("aes128ctr"),
        AES128_GCM("aes128gcm"),
    }

    /** Alternating native names and values. */
    internal fun toNative(): Array<String> {
        val all = LinkedHashMap<String, String>()
        fun put(name: String, value: Any?) {
            if (value != null) all[name] = value.toString()
        }
        put("num_ports", numPorts)
        put("start_port", startPort)
        put("static_ports", staticPorts)
        put("block_size_mbytes", blockSizeMbytes)
        put("enable_checksum", enableChecksum)
        put("encryption_type", encryption?.nativeName)
        put("avg_mbytes_per_sec", avgMbytesPerSec)
        put("max_mbytes_per_sec", maxMbytesPerSec)
        put("enable_download_resumption", enableDownloadResumption)
        put("overwrite", overwrite)
        put("fsync", fsync)
        put("follow_symlinks", followSymlinks)
        put("include_regex", includeRegex)
        put("exclude_regex", excludeRegex)
        put("prune_dir_regex", pruneDirRegex)
        put("connect_timeout_millis", connectTimeoutMillis)
        put("read_timeout_millis", readTimeoutMillis)
        put("write_timeout_millis", writeTimeoutMillis)
        put("accept_timeout_millis", acceptTimeoutMillis)
        put("max_accept_retries", maxAcceptRetries)
        put("max_retries", maxRetries)
        put("progress_report_interval_millis", progressReportIntervalMillis)
        put("abort_check_interval_millis", abortCheckIntervalMillis)
        all.putAll(extra)
        return all.flatMap { listOf(it.key, it.value) }.toTypedArray()
    }
}
