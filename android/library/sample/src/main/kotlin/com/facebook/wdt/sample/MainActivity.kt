/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.facebook.wdt.Wdt
import com.facebook.wdt.WdtOptions
import com.facebook.wdt.WdtReceiver
import com.facebook.wdt.WdtSender
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Sends a generated directory to itself over loopback with WDT, showing the
 * progress and the result (also logged with the tag "WdtSample").
 */
class MainActivity : Activity() {
    private val executor = Executors.newCachedThreadPool()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(status)
        executor.execute { runDemo() }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun show(text: String) {
        Log.i(TAG, text)
        runOnUiThread { status.text = text }
    }

    private fun runDemo() {
        val src = File(cacheDir, "demo-src").apply { deleteRecursively(); mkdirs() }
        val dst = File(cacheDir, "demo-dst").apply { deleteRecursively(); mkdirs() }
        for (i in 1..20) {
            File(src, "file$i.bin").writeBytes(Random.nextBytes(1024 * 1024))
        }
        val options = WdtOptions().apply {
            startPort = 0 // any free ports
            numPorts = 4
        }
        show("WDT ${Wdt.version}: sending 20 MB over loopback...")
        WdtReceiver(dst, options).use { receiver ->
            val url = receiver.start()
            val received = executor.submit<Any> { receiver.awaitFinish() }
            val report = WdtSender(url, src, options).use { sender ->
                sender.transfer { p -> show("Sent ${p.bytesTransferred / 1024} KB (${p.percent}%)") }
            }
            received.get()
            val same = src.listFiles()!!.all {
                it.readBytes().contentEquals(File(dst, it.name).readBytes())
            }
            show(
                "Result: ${report.errorCode}, ${report.numFiles} files, " +
                    "%.1f MB/s, identical=$same".format(report.throughputMBps),
            )
        }
    }

    private companion object {
        const val TAG = "WdtSample"
    }
}
