/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.facebook.wdt.ProgressListener
import com.facebook.wdt.TransferProgress
import com.facebook.wdt.TransferReport
import com.facebook.wdt.Wdt
import com.facebook.wdt.WdtException
import com.facebook.wdt.WdtOptions
import com.facebook.wdt.WdtReceiver
import com.facebook.wdt.WdtSender
import com.facebook.wdt.WdtTransfer
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Sends files with WDT between two devices running this app: the sender
 * chooses files and shares a link, the other device opens it and downloads
 * (see Share.kt). Also works with the `wdt` command line tool on a computer,
 * through wdt:// URLs.
 */
class MainActivity : Activity() {
    private val executor = Executors.newCachedThreadPool()

    /** What Stop stops. */
    @Volatile
    private var stoppable: AutoCloseable? = null

    /** Files shared to this app (ACTION_SEND), shared instead of picking. */
    private var sharedUris: List<Uri> = emptyList()

    /** What the picked files are for. */
    private var pickPurpose = PICK_TO_SHARE

    /** Where to push the picked files (PICK_TO_PUSH). */
    private var pushTarget: NearbyReceiver? = null

    private lateinit var addressView: TextView
    private lateinit var shareButton: Button
    private lateinit var sharePanel: LinearLayout
    private lateinit var shareLinkView: TextView
    private lateinit var nearbyList: LinearLayout
    private lateinit var nearbyStatus: TextView
    private lateinit var searchButton: Button
    private lateinit var addressInput: EditText
    private lateinit var addressButton: Button
    private lateinit var linkInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var computerButton: Button
    private lateinit var computerPanel: LinearLayout
    private lateinit var computerUrlView: TextView
    private lateinit var selfTestButton: Button
    private lateinit var stopButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logView: TextView
    private lateinit var visibleCheck: CheckBox
    private lateinit var receiveCheck: CheckBox
    private lateinit var nearbySharesTitle: TextView
    private lateinit var nearbySharesList: LinearLayout

    /** Other phones: their shares, their discovery requests. */
    private val listener = NearbyListener({ deviceName() }, { receiveCheck.isChecked })

    /** Pushes from other devices, asking the user. */
    private val pushServer = PushServer { onPush(it) }
    private var multicastLock: WifiManager.MulticastLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private var shownShares = ""
    private val refreshShares = object : Runnable {
        override fun run() {
            updateNearbyShares()
            ui.postDelayed(this, 3000)
        }
    }

    /** Tells hubs on the network about this device and its share. */
    private val announcer = Announcer { deviceName() }

    /** The running share (for the announcer). */
    @Volatile
    private var currentShare: ShareLink? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildUi()
        setContentView(root)
        applySystemBarInsets(root) // needs the window's decor: after setContentView
        log("WDT ${Wdt.version}, protocol ${Wdt.protocolVersion}")
        showAddress()
        handleIntent(intent)
        searchNearby()
        announcer.start()
        // the notification's Stop button
        TransferService.onStop = { stoppable?.let { s -> executor.execute { s.close() } } }
    }

    override fun onStart() {
        super.onStart()
        // Listening while the app is visible: its shares, pushes (with a prompt)
        multicastLock = applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("wdt:discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        if (!listener.start() || !pushServer.start()) {
            log("Can't listen on port $NEARBY_PORT (another copy of this app?): other phones can't see this one")
        }
        ui.post(refreshShares)
    }

    override fun onStop() {
        ui.removeCallbacks(refreshShares)
        listener.close()
        pushServer.close()
        multicastLock?.release()
        multicastLock = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        announcer.active = true
        announcer.poke()
    }

    override fun onPause() {
        announcer.active = false // still announced while sharing
        super.onPause()
    }

    private fun updateAnnouncedShare() {
        announcer.share = currentShare?.takeIf { visibleCheck.isChecked }
        announcer.poke()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        announcer.stop()
        // Closed for good (swiped away from the recent apps): the transfer ends.
        // May wait for it to stop: not on the UI thread
        stoppable?.let { executor.execute { it.close() } }
        TransferService.onStop = null
        TransferService.stop(this)
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------ share (send)

    private fun onShareClicked() {
        if (sharedUris.isNotEmpty()) {
            startSharing(sharedUris)
        } else {
            pickFiles(PICK_TO_SHARE)
        }
    }

    /** Serves [uris] behind a share link, until Stop. */
    private fun startSharing(uris: List<Uri>) {
        val address = preferredAddress()
        if (address == null) {
            log("No network: connect to Wi-Fi (both devices on the same network)")
            return
        }
        warnIfNotWifi(address)
        setBusy("Sharing ${uris.size} file(s)")
        executor.execute {
            val sources = try {
                openSources(uris)
            } catch (e: Exception) {
                runOnUiThread {
                    log("Can't read the files: ${e.message}")
                    setIdle()
                }
                return@execute
            }
            try {
                val server = ShareServer(
                    address.ip, sources.directory, sources.shared, sources.totalBytes,
                    options = { transferOptions().apply { progressReportIntervalMillis = 250 } },
                    deviceName = deviceName(),
                    webAsset = ::webAsset,
                )
                stoppable = server
                currentShare = server.link
                runOnUiThread { updateAnnouncedShare() }
                val link = server.link.toString()
                Log.i(TAG, "Share link: $link")
                runOnUiThread {
                    shareLinkView.text = link
                    sharePanel.visibility = View.VISIBLE
                    log("Sharing ${sources.shared.size} file(s), ${mb(sources.totalBytes)}: send the link to the other device")
                    if (sharedUris.isNotEmpty()) {
                        sharedUris = emptyList()
                        updateButtons()
                    }
                }
                server.serve(object : ShareServer.Listener {
                    override fun onProgress(progress: TransferProgress) = showProgress(progress)

                    override fun onDownloaderConnected(address: String) = runOnUiThread {
                        log("$address is downloading...")
                        progressBar.isIndeterminate = true
                    }

                    override fun onFinished(address: String, report: TransferReport) = runOnUiThread {
                        logReport("Sent to $address", report)
                        log("Still sharing: the link works until you tap Stop")
                    }

                    override fun onError(message: String) = runOnUiThread { log("Share: $message") }

                    override fun onWebDownload(address: String, what: String, bytes: Long) = runOnUiThread {
                        log("$address is downloading $what (${mb(bytes)}) in a browser...")
                        speedMeter.reset()
                    }

                    override fun onWebProgress(bytes: Long, total: Long) = showBytes(bytes, total, false)

                    override fun onWebDone(address: String, what: String, bytes: Long, seconds: Double, complete: Boolean) =
                        runOnUiThread {
                            if (complete) {
                                log("Sent $what to $address (browser): ${mb(bytes)} in %.1f s (%.1f MB/s)"
                                    .format(seconds, if (seconds > 0) bytes / 1e6 / seconds else 0.0))
                            } else {
                                log("$address stopped downloading $what (${mb(bytes)} sent)")
                            }
                        }
                })
            } catch (e: Exception) {
                runOnUiThread { log("Share failed: ${e.message}") }
            } finally {
                sources.close()
                currentShare = null
                runOnUiThread {
                    updateAnnouncedShare()
                    sharePanel.visibility = View.GONE
                    log("Stopped sharing")
                    setIdle()
                }
            }
        }
    }

    // ------------------------------------------ push (to a nearby computer)

    private fun searchNearby() {
        nearbyStatus.text = "Searching..."
        searchButton.isEnabled = false
        executor.execute {
            val found = try {
                Nearby.discover()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                searchButton.isEnabled = stoppable == null
                nearbyList.removeAllViews()
                for (target in found) {
                    val label = "Send to ${target.name} (${target.host})" +
                        if (target.autoAccept) "" else ", it asks first"
                    nearbyList.addView(button(label) { onPushClicked(target) }.apply {
                        isEnabled = stoppable == null
                    })
                }
                nearbyStatus.text = if (found.isEmpty()) {
                    "Nothing found. On a computer, run: awdt receive ~/Downloads --auto-accept\n" +
                        "On a phone: open this app (same Wi-Fi)."
                } else {
                    ""
                }
                nearbyStatus.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onAddressClicked() {
        val text = addressInput.text.toString().trim()
        val m = Regex("""^([^:\s]+)(?::(\d{1,5}))?$""").matchEntire(text)
        if (m == null) {
            log("Type the computer's address, like 192.168.1.103")
            return
        }
        val port = m.groupValues[2].toIntOrNull() ?: NEARBY_PORT
        onPushClicked(NearbyReceiver(m.groupValues[1], port, false, m.groupValues[1]))
    }

    private fun onPushClicked(target: NearbyReceiver) {
        if (sharedUris.isNotEmpty()) {
            push(target, sharedUris)
        } else {
            pushTarget = target
            pickFiles(PICK_TO_PUSH)
        }
    }

    private fun push(target: NearbyReceiver, uris: List<Uri>) {
        setBusy("Sending ${uris.size} file(s) to ${target.name}")
        log("Connecting to $target...")
        val push = Push(target)
        stoppable = push
        executor.execute {
            try {
                openSources(uris).use { sources ->
                    val options = transferOptions().apply { progressReportIntervalMillis = 250 }
                    val report = push.run(
                        sources.directory, sources.files, sources.totalBytes, deviceName(),
                        options, progressListener,
                    ) {
                        runOnUiThread {
                            log("Sending ${sources.files.size} file(s), ${mb(sources.totalBytes)} to ${target.name}...")
                        }
                    }
                    runOnUiThread {
                        logReport("Sent to ${target.name}", report)
                        if (report.isSuccess && sharedUris.isNotEmpty()) {
                            sharedUris = emptyList()
                            updateButtons()
                        }
                    }
                }
            } catch (e: Exception) {
                val message = when (e) {
                    is ConnectException, is SocketTimeoutException ->
                        "Can't reach ${target.host}. Is awdt running there, on the same network?"
                    else -> e.message ?: e.toString()
                }
                runOnUiThread { log("Send failed: $message") }
            } finally {
                runOnUiThread { setIdle() }
            }
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    // ------------------------------------------------ receiving pushes

    /** A device wants to send files here (on the push server's thread). */
    private fun onPush(request: PushRequest) {
        if (!receiveCheck.isChecked) return request.decline("this phone doesn't accept files")
        if (stoppable != null) return request.decline("this phone is busy with another transfer")
        val answered = CountDownLatch(1)
        var accepted = false
        var dialog: AlertDialog? = null
        runOnUiThread {
            dialog = AlertDialog.Builder(this)
                .setTitle("Receive files?")
                .setMessage(
                    "${request.name} (${request.address}) wants to send you " +
                        "${request.files} file(s), ${mb(request.bytes)}.",
                )
                .setPositiveButton("Accept") { _, _ ->
                    accepted = stoppable == null
                    if (accepted) setBusy("Receiving from ${request.name}")
                    answered.countDown()
                }
                .setNegativeButton("Decline") { _, _ -> answered.countDown() }
                .setOnCancelListener { answered.countDown() }
                .show()
        }
        if (!answered.await(60, TimeUnit.SECONDS)) {
            runOnUiThread { dialog?.dismiss() }
        }
        if (!accepted) {
            request.decline("declined")
            runOnUiThread { log("Declined files from ${request.name}") }
            return
        }
        runOnUiThread { log("Receiving ${request.files} file(s), ${mb(request.bytes)} from ${request.name}...") }
        val staging = freshDir(File(filesDir, "incoming-push"))
        try {
            val report = request.receive(staging, transferOptions().apply { progressReportIntervalMillis = 250 }, progressListener) {
                stoppable = TransferStopper(it)
            }
            val saved = if (report.isSuccess) saveReceived(staging) else null
            runOnUiThread {
                logReport("Received from ${request.name}", report)
                saved?.let { log(it) }
            }
        } catch (e: Exception) {
            runOnUiThread { log("Receiving failed: ${e.message}") }
        } finally {
            staging.deleteRecursively()
            runOnUiThread { setIdle() }
        }
    }

    /** The shares of the phones nearby, to download. */
    private fun updateNearbyShares() {
        val shares = listener.nearbyShares()
        val key = shares.joinToString { it.address + it.link }
        if (key == shownShares) return
        shownShares = key
        nearbySharesList.removeAllViews()
        for (share in shares) {
            nearbySharesList.addView(
                button("Download what ${share.name} shares") { startDownload(share.link) }.apply {
                    isEnabled = stoppable == null
                },
            )
        }
        val any = shares.isNotEmpty()
        nearbySharesTitle.visibility = if (any) View.VISIBLE else View.GONE
        nearbySharesList.visibility = if (any) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------- download (receive)

    private fun onDownloadClicked() {
        val text = linkInput.text.toString().trim()
        when {
            text.startsWith("wdt://") -> pickFiles(PICK_TO_SEND_TO_COMPUTER)
            ShareLink.isLink(text) -> {
                val link = ShareLink.parse(text)
                if (link == null) {
                    log("This link is incomplete or damaged: copy it again")
                } else {
                    startDownload(link)
                }
            }
            text.isEmpty() -> log("First paste the link from the other device")
            else -> log("That's not a WDT link (they start with awdt://)")
        }
    }

    private fun startDownload(link: ShareLink) {
        setBusy("Receiving from ${link.host}")
        log("Connecting to ${link.host}...")
        val download = ShareDownload(link)
        stoppable = download
        val staging = freshDir(File(filesDir, "incoming"))
        executor.execute {
            try {
                val options = transferOptions().apply {
                    progressReportIntervalMillis = 250
                    maxAcceptRetries = 300 // the sender connects right away: 30 s
                }
                val report = download.run(staging, options, progressListener) { files, bytes ->
                    runOnUiThread { log("Receiving $files file(s), ${mb(bytes)} from ${link.host}...") }
                }
                val saved = if (report.isSuccess) saveReceived(staging) else null
                runOnUiThread {
                    logReport("Received", report)
                    saved?.let { log(it) }
                }
            } catch (e: Exception) {
                val message = when (e) {
                    is ConnectException, is SocketTimeoutException ->
                        "Can't reach ${link.host}. Is it still sharing, on the same Wi-Fi?"
                    else -> e.message ?: e.toString()
                }
                runOnUiThread { log("Download failed: $message") }
            } finally {
                staging.deleteRecursively()
                runOnUiThread { setIdle() }
            }
        }
    }

    /** Moves the received files to Download/WDT (where file managers see them). */
    private fun saveReceived(staging: File): String {
        val files = staging.walkTopDown().filter { it.isFile && it.name != ".wdt.log" }.toList()
        if (Build.VERSION.SDK_INT < 29) {
            val dest = File(getExternalFilesDir(null), "received")
            staging.copyRecursively(dest, overwrite = true)
            return "Files saved in ${dest.path}"
        }
        var count = 0
        for (file in files) {
            val subdir = file.parentFile!!.relativeTo(staging).path
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    listOf(Environment.DIRECTORY_DOWNLOADS, "WDT", subdir)
                        .filter { it.isNotEmpty() }.joinToString("/"),
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                runOnUiThread { log("Could not save ${file.name}") }
                continue
            }
            contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            count++
        }
        return "Saved $count file(s) in Download/WDT"
    }

    // ---------------------------------------- with the wdt command line tool

    /** A WDT receiver whose wdt:// URL is given to `wdt` on a computer. */
    private fun receiveFromComputer() {
        val address = preferredAddress()
        if (address == null) {
            log("No network: connect to Wi-Fi")
            return
        }
        val staging = freshDir(File(filesDir, "incoming"))
        val options = transferOptions().apply {
            progressReportIntervalMillis = 250
            maxAcceptRetries = 6000 // with 100 ms accept timeouts: wait ~10 min
        }
        val receiver = WdtReceiver(staging, options, hostName = address.ip)
        val url = try {
            receiver.start(progressListener)
        } catch (e: WdtException) {
            receiver.close()
            log("Could not start receiving: ${e.message}")
            return
        }
        Log.i(TAG, "Receiver URL: $url")
        computerUrlView.text = url
        computerPanel.visibility = View.VISIBLE
        setBusy("Waiting for the computer to send")
        stoppable = TransferStopper(receiver)
        log("Waiting for wdt on the computer (10 min)...")
        executor.execute {
            val report = receiver.awaitFinish()
            receiver.close()
            val saved = if (report.isSuccess) saveReceived(staging) else null
            staging.deleteRecursively()
            runOnUiThread {
                computerPanel.visibility = View.GONE
                logReport("Received", report)
                saved?.let { log(it) }
                setIdle()
            }
        }
    }

    /** Sends to a `wdt` receiver, given the wdt:// URL it printed. */
    private fun sendToComputer(url: String, uris: List<Uri>) {
        setBusy("Sending ${uris.size} file(s)")
        log("Sending ${uris.size} file(s)...")
        executor.execute {
            try {
                openSources(uris).use { sources ->
                    val options = transferOptions().apply { progressReportIntervalMillis = 250 }
                    WdtSender(url, sources.directory, options, sources.files).use { sender ->
                        stoppable = TransferStopper(sender)
                        val report = sender.transfer(progressListener)
                        runOnUiThread { logReport("Sent", report) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { log("Send failed: ${e.message}") }
            } finally {
                runOnUiThread { setIdle() }
            }
        }
    }

    /** Stop for a plain WDT transfer (closed by its own thread). */
    private class TransferStopper(val transfer: WdtTransfer) : AutoCloseable {
        override fun close() = transfer.abort()
    }

    // ---------------------------------------------------------- self-test

    /** Shares 20 MB through a share link on this device and downloads it. */
    private fun selfTest() {
        setBusy("Self-test")
        log("Self-test: sharing 20 MB and downloading it on this device...")
        executor.execute {
            val src = freshDir(File(cacheDir, "selftest-src"))
            val dst = freshDir(File(cacheDir, "selftest-dst"))
            var server: ShareServer? = null
            try {
                for (i in 1..20) File(src, "file$i.bin").writeBytes(Random.nextBytes(1 shl 20))
                val files = src.listFiles()!!.map { SharedFile(it.name, it.length(), null, it) }
                val options = { transferOptions().apply { progressReportIntervalMillis = 100 } }
                server = ShareServer(
                    "127.0.0.1", src, files, 20L shl 20, options, "self-test", ::webAsset,
                    bindAddress = "127.0.0.1",
                )
                val serving = executor.submit {
                    server.serve(object : ShareServer.Listener {
                        override fun onProgress(progress: TransferProgress) = showProgress(progress)
                        override fun onDownloaderConnected(address: String) {}
                        override fun onFinished(address: String, report: TransferReport) =
                            runOnUiThread { logReport("Self-test sent", report) }
                        override fun onError(message: String) = runOnUiThread { log("Self-test: $message") }
                        override fun onWebDownload(address: String, what: String, bytes: Long) {}
                        override fun onWebProgress(bytes: Long, total: Long) {}
                        override fun onWebDone(address: String, what: String, bytes: Long, seconds: Double, complete: Boolean) {}
                    })
                }
                // through the text form, like a link pasted by a user
                val link = ShareLink.parse(server.link.toString())!!
                val download = ShareDownload(link)
                stoppable = download
                val report = download.run(dst, options(), ProgressListener {}) { _, _ -> }
                // and the browser side: the page, and a file over HTTP
                val page = httpGet(link.port, "/${link.key.toHex()}/")
                val web = httpGet(link.port, "/${link.key.toHex()}/f/0/x")
                server.close()
                serving.get()
                val identical = src.listFiles()!!.all {
                    it.readBytes().contentEquals(File(dst, it.name).readBytes())
                } && web.contentEquals(File(src, files[0].name).readBytes()) &&
                    String(page).contains("app.js")
                runOnUiThread {
                    logReport("Self-test received", report)
                    log(if (report.isSuccess && identical) "Self-test: OK, files identical" else "Self-test: FAILED")
                }
            } catch (e: Exception) {
                runOnUiThread { log("Self-test failed: $e") }
            } finally {
                server?.close()
                src.deleteRecursively()
                dst.deleteRecursively()
                runOnUiThread { setIdle() }
            }
        }
    }

    /** A body over HTTP from this device (self-test), without redirects. */
    private fun httpGet(port: Int, path: String): ByteArray =
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: self-test\r\n\r\n".toByteArray())
            val input = java.io.BufferedInputStream(socket.getInputStream())
            val status = readLine(input) ?: throw ShareException("No HTTP answer")
            if (!status.startsWith("HTTP/1.1 200")) throw ShareException("HTTP $path: $status")
            while (readLine(input)?.isNotEmpty() == true) {
            }
            input.readBytes()
        }

    /** The web page's files, served to browsers opening a share link. */
    private fun webAsset(name: String): ByteArray? = try {
        assets.open("web/$name").use { it.readBytes() }
    } catch (e: java.io.IOException) {
        null
    }

    // --------------------------------------------------------------- files

    private fun pickFiles(purpose: Int) {
        pickPurpose = purpose
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        @Suppress("DEPRECATION")
        startActivityForResult(pick, PICK_FILES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isEmpty()) return
        when (pickPurpose) {
            PICK_TO_SHARE -> startSharing(uris)
            PICK_TO_PUSH -> pushTarget?.let { push(it, uris) }
            PICK_TO_SEND_TO_COMPUTER -> sendToComputer(linkInput.text.toString().trim(), uris)
        }
    }

    /** Files to send, opened: read through their file descriptors when possible. */
    private inner class Sources(
        val directory: File,
        val shared: List<SharedFile>,
        val totalBytes: Long,
    ) : AutoCloseable {
        /** For WDT */
        val files get() = shared.map { it.source }

        override fun close() {
            shared.forEach { it.close() }
            directory.deleteRecursively()
        }
    }

    private fun openSources(uris: List<Uri>): Sources {
        // for content that can't be read through a plain file descriptor
        val staging = freshDir(File(cacheDir, "outgoing-${System.nanoTime()}"))
        val opened = mutableListOf<SharedFile>()
        try {
            val names = HashSet<String>()
            for (uri in uris) {
                val name = uniqueName(displayName(uri), names)
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Can't open $uri")
                opened += if (pfd.statSize >= 0) {
                    SharedFile(name, pfd.statSize, pfd, null)
                } else {
                    val copy = File(staging, name)
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        copy.outputStream().use { input.copyTo(it) }
                    }
                    SharedFile(name, copy.length(), null, copy)
                }
            }
            return Sources(staging, opened, opened.sumOf { it.size })
        } catch (e: Exception) {
            opened.forEach { it.close() }
            staging.deleteRecursively()
            throw e
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) return it.getString(0) }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        val clean = name.replace('/', '_').ifEmpty { "file" }
        var candidate = clean
        var i = 1
        while (!used.add(candidate)) {
            val dot = clean.lastIndexOf('.')
            candidate = if (dot > 0) {
                "${clean.substring(0, dot)} (${i++})${clean.substring(dot)}"
            } else {
                "$clean (${i++})"
            }
        }
        return candidate
    }

    private fun freshDir(dir: File) = dir.apply {
        deleteRecursively()
        mkdirs()
    }

    // ------------------------------------------------------------- intents

    /** awdt:// and wdt:// links, links shared as text, files shared. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { acceptLink(it) }
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    Regex("""(?:a?wdt|http)://\S+""").find(text)?.let { acceptLink(it.value) }
                }
                streamExtra(intent)?.let { sharedUris = listOf(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> sharedUris = streamListExtra(intent)
        }
        if (sharedUris.isNotEmpty()) {
            log("${sharedUris.size} file(s) received from another app: tap Share, or a nearby computer")
        }
        updateButtons()
    }

    private fun acceptLink(text: String) {
        when {
            ShareLink.isLink(text) -> {
                linkInput.setText(text)
                log("Got a link: tap Download")
            }
            text.startsWith("wdt://") -> {
                linkInput.setText(text)
                log("Got a wdt:// receiver URL: tap Choose files and send")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }.orEmpty()

    // --------------------------------------------------------------- state

    private val progressListener = ProgressListener { showProgress(it) }

    private val speedMeter = SpeedMeter()

    private fun showProgress(p: TransferProgress) = showBytes(p.bytesTransferred, p.totalBytes, p.isDone)

    /** Progress, from the transfer's thread (measured here: see SpeedMeter). */
    private fun showBytes(bytes: Long, total: Long, done: Boolean) {
        val speed = speedMeter.update(bytes)
        val rate = if (speed > 0) "  ·  %.1f MB/s".format(speed) else ""
        val percent = if (total > 0) (bytes * 100 / total).toInt() else -1
        val text = if (total > 0) {
            val left = if (speed > 0.05 && !done) {
                val seconds = ((total - bytes) / 1e6 / speed).toInt()
                "  ·  %d:%02d left".format(seconds / 60, seconds % 60)
            } else {
                ""
            }
            "%s / %s%s%s".format(mb(bytes), mb(total), rate, left)
        } else {
            mb(bytes) + rate
        }
        TransferService.progress(this, text, percent)
        runOnUiThread {
            if (percent >= 0) {
                progressBar.isIndeterminate = false
                progressBar.progress = percent
            }
            progressText.text = text
        }
    }

    private fun updateButtons() {
        shareButton.text =
            if (sharedUris.isEmpty()) "Choose files to share" else "Share ${sharedUris.size} file(s)"
        downloadButton.text =
            if (linkInput.text.trim().startsWith("wdt://")) "Choose files and send" else "Download"
    }

    private fun actionButtons(): List<View> =
        listOf(shareButton, downloadButton, computerButton, selfTestButton, searchButton, addressButton) +
            (0 until nearbyList.childCount).map { nearbyList.getChildAt(it) } +
            (0 until nearbySharesList.childCount).map { nearbySharesList.getChildAt(it) }

    /**
     * Wi-Fi can stall for seconds (power saving, interference): with WDT's
     * default 5 s socket timeouts, connections would be dropped and reopened.
     */
    private fun transferOptions() = WdtOptions().apply {
        // Connections, when this device receives (the receiver decides): more
        // don't help on Wi-Fi (measured: 3 are as fast as 8 from a phone)
        numPorts = 3
        readTimeoutMillis = 30_000
        writeTimeoutMillis = 30_000
    }

    /** Keeps the Wi-Fi radio (and the CPU) fully awake during transfers. */
    private val wifiLock by lazy {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifi.createWifiLock(mode, "wdt:transfer").apply { setReferenceCounted(false) }
    }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wdt:transfer")
            .apply { setReferenceCounted(false) }
    }

    /** A transfer starts; [what] is shown in its notification. */
    private fun setBusy(what: String = "WDT transfer") {
        // keeps going in the background, with a progress notification
        TransferService.start(this, what)
        askNotificationPermission()
        wifiLock.acquire()
        wakeLock.acquire(60 * 60 * 1000L) // released when idle; at most an hour
        for (b in actionButtons()) b.isEnabled = false
        stopButton.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.text = ""
        speedMeter.reset()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Android 13+: the progress notification needs the user's OK (asked once). */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFICATIONS)
    }

    /** Back during a transfer: to the background (it goes on), not closed. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stopButton.visibility == View.VISIBLE) {
            moveTaskToBack(true)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun setIdle() {
        TransferService.stop(this)
        stoppable = null
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
        for (b in actionButtons()) b.isEnabled = true
        stopButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressText.text = ""
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun logReport(what: String, report: TransferReport) {
        if (report.isSuccess) {
            // only the sender counts files
            val files = if (report.numFiles > 0) "${report.numFiles} file(s), " else ""
            val summary = "$files${mb(report.bytesTransferred)} in " +
                "%.1f s (%.1f MB/s)".format(report.totalTimeSeconds, report.throughputMBps)
            log("$what: $summary")
            TransferService.done(this, what, summary)
        } else {
            log("$what: failed, ${report.errorCode}")
            TransferService.done(this, "$what: failed", report.errorCode.toString())
            if (report.failedFiles.isNotEmpty()) log("  failed: ${report.failedFiles.joinToString()}")
        }
    }

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1e6)

    private fun log(message: String) {
        Log.i(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("$time  $message\n")
    }

    // ------------------------------------------------------------- network

    private data class LocalAddress(val iface: String, val ip: String, val rank: Int)

    /** IPv4 addresses of this device, Wi-Fi first. */
    private fun localAddresses(): List<LocalAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList().filterIsInstance<Inet4Address>().map {
                    LocalAddress(iface.name, it.hostAddress!!, rank(iface.name))
                }
            }
            .sortedBy { it.rank }
    } catch (e: Exception) {
        emptyList()
    }

    private fun rank(iface: String) = when {
        iface.startsWith("wlan") -> 0
        iface.startsWith("eth") -> 1
        iface.startsWith("ap") || iface.startsWith("swlan") -> RANK_HOTSPOT
        else -> 3 // mobile data, VPN...: usually not reachable by the other device
    }

    private fun preferredAddress() = localAddresses().firstOrNull()

    private fun warnIfNotWifi(address: LocalAddress) {
        if (address.rank > RANK_HOTSPOT) {
            log("Not on Wi-Fi (${address.iface}): the other device may not reach ${address.ip}")
        }
    }

    private fun showAddress() {
        val all = localAddresses()
        addressView.text = if (all.isEmpty()) {
            "This device: no network"
        } else {
            "This device: " + all.joinToString { "${it.ip} (${it.iface})" }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        // Title, and the app's version on the right
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    text("WDT", 30f, bold = true),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(text("v${appVersion()}", 13f, secondary = true, mono = true))
            },
        )
        column.addView(text("Warp speed Data Transfer, on the same Wi-Fi", 14f, secondary = true))
        addressView = text("", 14f, secondary = true).also { column.addView(it, margins(top = 4)) }

        column.addView(heading("Send"))
        column.addView(
            text("To a nearby device (a computer running awdt, or a phone with this app open):", 14f, secondary = true),
        )
        nearbyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(nearbyList, margins(top = 4))
        nearbyStatus = text("", 13f, secondary = true).also { column.addView(it, margins(top = 4)) }
        searchButton = button("Search again") { searchNearby() }
        addressInput = EditText(this).apply {
            hint = "or its address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            textSize = 14f
        }
        addressButton = button("Send") { onAddressClicked() }
        column.addView(searchButton, margins(top = 4))
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(addressInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    addressButton,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            },
        )
        column.addView(
            text("Or share a link, for browsers and other phones:", 14f, secondary = true),
            margins(top = 12),
        )
        shareButton = button("Choose files to share") { onShareClicked() }
            .also { column.addView(it, margins(top = 4)) }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        visibleCheck = CheckBox(this).apply {
            text = "Show my shares to hubs on this network (anyone on it can then download them)"
            textSize = 13f
            isChecked = prefs.getBoolean("visible_to_hubs", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("visible_to_hubs", checked).apply()
                updateAnnouncedShare()
            }
        }
        column.addView(visibleCheck, margins(top = 4))
        sharePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        sharePanel.addView(text("Link to send to the other device, to open in a browser or in this app:", 14f), margins(top = 12))
        shareLinkView = text("", 13f, mono = true).apply { setTextIsSelectable(true) }
        sharePanel.addView(shareLinkView, margins(top = 4))
        sharePanel.addView(
            row(
                button("Copy link") { copy(shareLinkView.text.toString()) },
                button("Share link") { shareText(shareLinkView.text.toString()) },
            ),
        )
        sharePanel.addView(
            text(
                "Computers download in a browser (unencrypted on this network), phones " +
                    "with this app faster through WDT. Anyone with the link on this network " +
                    "can download these files, until you tap Stop.",
                13f, secondary = true,
            ),
            margins(top = 4),
        )
        column.addView(sharePanel)

        column.addView(heading("Receive"))
        column.addView(text("Files are saved in Download/WDT.", 14f, secondary = true))
        receiveCheck = CheckBox(this).apply {
            text = "Let nearby devices send me files (you're asked each time)"
            textSize = 13f
            isChecked = prefs.getBoolean("accept_pushes", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("accept_pushes", checked).apply() }
        }
        column.addView(receiveCheck, margins(top = 4))
        nearbySharesTitle = text("Shared by nearby phones:", 14f, secondary = true).apply { visibility = View.GONE }
        column.addView(nearbySharesTitle, margins(top = 8))
        nearbySharesList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        column.addView(nearbySharesList)
        column.addView(text("Or open a link:", 14f, secondary = true), margins(top = 8))
        linkInput = EditText(this).apply {
            id = R.id.url_input // keeps the text across recreation
            hint = "Link from the other device (awdt://...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            textSize = 13f
            typeface = Typeface.MONOSPACE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateButtons()
            })
        }
        column.addView(linkInput, margins(top = 4))
        downloadButton = button("Download") { onDownloadClicked() }
        column.addView(row(button("Paste") { paste() }, downloadButton))

        stopButton = button("Stop") {
            stoppable?.let { s -> executor.execute { s.close() } }
            log("Stopping...")
        }.apply { visibility = View.GONE }
        column.addView(stopButton, margins(top = 16))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        column.addView(progressBar, margins(top = 8))
        progressText = text("", 14f).also { column.addView(it) }

        column.addView(heading("Log"))
        logView = text("", 12f, mono = true).apply { setTextIsSelectable(true) }
        column.addView(logView)

        column.addView(heading("With the wdt command line tool"))
        column.addView(
            text(
                "For computers without awdt, using WDT's own command line tool.\n" +
                    "Computer to phone: tap Receive from a computer, then run there\n" +
                    "  wdt -directory <folder> -connection_url '<URL>'\n" +
                    "Phone to computer: run there\n" +
                    "  wdt -directory <folder> -hostname <its IP>\n" +
                    "paste the wdt:// URL it prints under Receive, and choose the files.",
                13f, secondary = true,
            ),
        )
        computerButton = button("Receive from a computer") { receiveFromComputer() }
        column.addView(computerButton, margins(top = 8))
        computerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        computerUrlView = text("", 13f, mono = true).apply { setTextIsSelectable(true) }
        computerPanel.addView(computerUrlView, margins(top = 8))
        computerPanel.addView(
            row(
                button("Copy URL") { copy(computerUrlView.text.toString()) },
                button("Share URL") { shareText(computerUrlView.text.toString()) },
            ),
        )
        column.addView(computerPanel)

        selfTestButton = button("Run self-test") { selfTest() }
        column.addView(selfTestButton, margins(top = 16))

        return ScrollView(this).apply {
            addView(column)
            isFillViewport = true
        }
    }

    /**
     * Android 15 draws apps edge to edge (behind the status and navigation
     * bars): pad the content by the bars (and the keyboard). Done from API 30,
     * where it can be controlled, for the same look everywhere.
     */
    private fun applySystemBarInsets(view: View) {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    private fun copy(value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("WDT link", value))
        // Android 13+ shows its own confirmation
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(value: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        startActivity(Intent.createChooser(send, "Send the link"))
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (value.isNullOrEmpty()) {
            log("The clipboard is empty")
        } else {
            linkInput.setText(Regex("""(?:a?wdt|http)://\S+""").find(value)?.value ?: value)
        }
    }

    /** The installed version (1.0.<build>, set by CI). */
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun margins(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun text(
        value: String,
        size: Float,
        bold: Boolean = false,
        secondary: Boolean = false,
        mono: Boolean = false,
    ) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        if (secondary) alpha = 0.7f
    }

    private fun heading(value: String) = text(value, 20f, bold = true).apply {
        layoutParams = margins(top = 24)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private companion object {
        const val TAG = "WdtSample"
        const val PICK_FILES = 1
        const val PICK_TO_SHARE = 0
        const val PICK_TO_SEND_TO_COMPUTER = 1
        const val PICK_TO_PUSH = 2
        const val ASK_NOTIFICATIONS = 2
        const val RANK_HOTSPOT = 2
    }
}
