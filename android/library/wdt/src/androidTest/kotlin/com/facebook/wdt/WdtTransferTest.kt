/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WdtTransferTest {
    private lateinit var root: File
    private lateinit var src: File
    private lateinit var dst: File
    private val executor = Executors.newCachedThreadPool()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "wdt-test").apply { deleteRecursively() }
        src = File(root, "src").apply { mkdirs() }
        dst = File(root, "dst").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        root.deleteRecursively()
    }

    /** Random files: a big one, many small ones, an empty one, nested dirs. */
    private fun makeTree() {
        val random = Random(42)
        File(src, "big.bin").writeBytes(random.nextBytes(40 * 1024 * 1024))
        val sub = File(src, "sub/deeper").apply { mkdirs() }
        for (i in 1..100) {
            File(src, "sub/f$i").writeBytes(random.nextBytes(i * 997))
        }
        File(sub, "small.txt").writeText("hello wdt\n")
        File(src, "empty").writeBytes(ByteArray(0))
    }

    private fun digests(dir: File): Map<String, String> =
        dir.walkTopDown()
            .filter { it.isFile && it.name != ".wdt.log" }
            .associate { file ->
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                }
                file.relativeTo(dir).path to md.digest().joinToString("") { "%02x".format(it) }
            }

    /** Any free ports, so tests never collide with each other or other apps. */
    private fun options() = WdtOptions().apply {
        startPort = 0
        numPorts = 4
        progressReportIntervalMillis = 50
    }

    private fun loopback(options: WdtOptions): Pair<TransferReport, TransferReport> {
        WdtReceiver(dst, options).use { receiver ->
            val url = receiver.start()
            assertTrue(url, url.startsWith("wdt://"))
            val received = executor.submit<TransferReport> { receiver.awaitFinish() }
            val sent = WdtSender(url, src, options).use { it.transfer() }
            return sent to received.get(60, TimeUnit.SECONDS)
        }
    }

    @Test
    fun errorCodesMatchNative() {
        val names = (0 until NativeWdt.errorCount()).map { NativeWdt.errorName(it) }
        assertEquals(names, WdtErrorCode.entries.map { it.name })
    }

    @Test
    fun version() {
        assertTrue(Wdt.version, Wdt.version.matches(Regex("""\d+\.\d+\.\d+""")))
        assertTrue(Wdt.protocolVersion > 0)
    }

    @Test
    fun transfersDirectoryTree() {
        makeTree()
        val progress = CopyOnWriteArrayList<TransferProgress>()
        val (sent, received) = WdtReceiver(dst, options()).use { receiver ->
            val url = receiver.start()
            val received = executor.submit<TransferReport> { receiver.awaitFinish() }
            val sent = WdtSender(url, src, options()).use { sender ->
                sender.transfer { progress.add(it) }
            }
            sent to received.get(60, TimeUnit.SECONDS)
        }
        assertTrue(sent.toString(), sent.isSuccess)
        assertTrue(received.toString(), received.isSuccess)
        assertEquals(digests(src), digests(dst))
        assertEquals(103L, sent.numFiles)
        val total = src.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(total, sent.bytesTransferred)
        assertTrue(sent.failedFiles.isEmpty())
        // progress reports: at least the final one, complete
        assertTrue(progress.isNotEmpty())
        val last = progress.last()
        assertTrue(last.isDone)
        assertEquals(total, last.bytesTransferred)
        assertEquals(100, last.percent)
    }

    @Test
    fun transfersWithChecksumsAndWithoutEncryption() {
        makeTree()
        for (encryption in WdtOptions.Encryption.entries) {
            dst.deleteRecursively()
            dst.mkdirs()
            val options = options().apply {
                this.encryption = encryption
                enableChecksum = true
            }
            val (sent, received) = loopback(options)
            assertTrue("$encryption $sent", sent.isSuccess)
            assertTrue("$encryption $received", received.isSuccess)
            assertEquals(digests(src), digests(dst))
        }
    }

    @Test
    fun sendsFileDescriptorsUnderOtherNames() {
        val random = Random(7)
        val a = File(root, "a.bin").apply { writeBytes(random.nextBytes(3 * 1024 * 1024 + 5)) }
        val b = File(root, "b.bin").apply { writeBytes(random.nextBytes(12345)) }
        val pfds = listOf(a, b).map {
            ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        try {
            val files = listOf(
                WdtSender.SourceFile.fromFileDescriptor("photos/one.bin", pfds[0]),
                WdtSender.SourceFile.fromFileDescriptor("two.bin", pfds[1]),
            )
            WdtReceiver(dst, options()).use { receiver ->
                val url = receiver.start()
                val received = executor.submit<TransferReport> { receiver.awaitFinish() }
                // the directory isn't used for fd sources: anything works
                val sent = WdtSender(url, src, options(), files).use { it.transfer() }
                assertTrue(sent.toString(), sent.isSuccess)
                assertTrue(received.get(60, TimeUnit.SECONDS).isSuccess)
            }
        } finally {
            pfds.forEach { it.close() }
        }
        assertTrue(a.readBytes().contentEquals(File(dst, "photos/one.bin").readBytes()))
        assertTrue(b.readBytes().contentEquals(File(dst, "two.bin").readBytes()))
        assertEquals(2, dst.walkTopDown().count { it.isFile })
    }

    @Test
    fun sendsFileListFromDirectory() {
        makeTree()
        val files = listOf(WdtSender.SourceFile("sub/f3"), WdtSender.SourceFile("empty"))
        WdtReceiver(dst, options()).use { receiver ->
            val url = receiver.start()
            val received = executor.submit<TransferReport> { receiver.awaitFinish() }
            val sent = WdtSender(url, src, options(), files).use { it.transfer() }
            assertTrue(sent.toString(), sent.isSuccess)
            assertTrue(received.get(60, TimeUnit.SECONDS).isSuccess)
        }
        assertEquals(setOf("sub/f3", "empty"), digests(dst).keys)
        assertEquals(digests(src)["sub/f3"], digests(dst)["sub/f3"])
    }

    @Test
    fun usesTheProvidedEncryptionKey() {
        makeTree()
        val key = ByteArray(16) { (it * 7 + 1).toByte() }
        val hex = key.joinToString("") { "%02x".format(it) }
        WdtReceiver(dst, options(), encryptionKey = key).use { receiver ->
            val url = receiver.start()
            assertTrue(url, url.contains("Enc=2:$hex"))
            // a sender given the key by other means than the url
            val withoutKey = url.replace(Regex("Enc=[^&]*&?"), "")
            val received = executor.submit<TransferReport> { receiver.awaitFinish() }
            val sent = WdtSender("$withoutKey&Enc=2:$hex", src, options()).use { it.transfer() }
            assertTrue(sent.toString(), sent.isSuccess)
            assertTrue(received.get(60, TimeUnit.SECONDS).isSuccess)
        }
        assertEquals(digests(src), digests(dst))
    }

    @Test
    fun wrongEncryptionKeyFails() {
        makeTree()
        val options = options().apply { maxRetries = 2 }
        WdtReceiver(dst, options, encryptionKey = ByteArray(16) { 1 }).use { receiver ->
            val url = receiver.start()
            val received = executor.submit<TransferReport> { receiver.awaitFinish() }
            val wrong = url.replace(Regex("Enc=2:[0-9a-f]*"), "Enc=2:" + "02".repeat(16))
            val sent = WdtSender(wrong, src, options).use { it.transfer() }
            assertFalse(sent.isSuccess)
            receiver.abort()
            assertFalse(received.get(60, TimeUnit.SECONDS).isSuccess)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptionKeyMustBe16Bytes() {
        WdtReceiver(dst, options(), encryptionKey = ByteArray(8))
    }

    @Test
    fun abortsReceiverWaitingForSender() {
        WdtReceiver(dst, options()).use { receiver ->
            receiver.start()
            val received = executor.submit<TransferReport> { receiver.awaitFinish() }
            Thread.sleep(300)
            assertFalse(received.isDone)
            val start = System.nanoTime()
            receiver.abort()
            val report = received.get(10, TimeUnit.SECONDS)
            assertNotEquals(WdtErrorCode.OK, report.errorCode)
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5))
        }
    }

    @Test
    fun closeWhileWaitingUnblocksTheWaiter() {
        val receiver = WdtReceiver(dst, options())
        receiver.start()
        val received = executor.submit<TransferReport> { receiver.awaitFinish() }
        Thread.sleep(300)
        receiver.close()
        assertNotEquals(WdtErrorCode.OK, received.get(10, TimeUnit.SECONDS).errorCode)
        receiver.close() // idempotent
        receiver.abort() // no-op once closed
    }

    @Test
    fun abortFromProgressListener() {
        makeTree()
        val options = options().apply { avgMbytesPerSec = 5.0 } // slow enough to abort
        WdtReceiver(dst, options).use { receiver ->
            val url = receiver.start()
            val received = executor.submit<TransferReport> { receiver.awaitFinish() }
            WdtSender(url, src, options).use { sender ->
                val start = System.nanoTime()
                val report = sender.transfer { if (it.bytesTransferred > 0) sender.abort() }
                assertNotEquals(WdtErrorCode.OK, report.errorCode)
                // at 5 MB/s the whole transfer would take ~9 s
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3))
            }
            // the receiver notices after its read timeout (5 s by default)
            assertNotEquals(WdtErrorCode.OK, received.get(60, TimeUnit.SECONDS).errorCode)
        }
    }

    @Test
    fun senderWithoutReceiverFails() {
        val options = WdtOptions().apply {
            maxRetries = 2
            connectTimeoutMillis = 200
        }.set("sleep_millis", 10)
        // a valid url, with nobody listening on the port
        val url = "wdt://127.0.0.1:1?id=x&num_ports=1&recpv=${Wdt.protocolVersion}"
        val report = WdtSender(url, src, options).use { it.transfer() }
        assertEquals(WdtErrorCode.CONN_ERROR, report.errorCode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownOptionIsRejected() {
        WdtReceiver(dst, WdtOptions().set("no_such_option", 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidOptionValueIsRejected() {
        WdtReceiver(dst, WdtOptions().set("num_ports", "many"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidUrlIsRejected() {
        WdtSender("http://example.com", src)
    }

    @Test
    fun busyStaticPortsFail() {
        val options = options().apply {
            startPort = 23456
            numPorts = 1
            staticPorts = true
        }
        WdtReceiver(dst, options).use { first ->
            first.start()
            WdtReceiver(dst, options).use { second ->
                try {
                    second.start()
                    throw AssertionError("second receiver started on a busy port")
                } catch (e: WdtException) {
                    assertNotEquals(WdtErrorCode.OK, e.errorCode)
                }
            }
        }
    }
}
