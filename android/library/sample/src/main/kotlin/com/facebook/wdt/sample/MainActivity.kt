/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import android.app.Activity
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
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.facebook.wdt.ProgressListener
import com.facebook.wdt.TransferReport
import com.facebook.wdt.Wdt
import com.facebook.wdt.WdtException
import com.facebook.wdt.WdtOptions
import com.facebook.wdt.WdtReceiver
import com.facebook.wdt.WdtSender
import com.facebook.wdt.WdtTransfer
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Sends and receives files with WDT, between two devices running this app,
 * or with the `wdt` command line tool on a computer.
 *
 * Receive: shows a wdt:// URL for the sender; received files are saved in
 * Download/WDT. Send: paste (or share, or open) a receiver's URL and choose
 * files; files shared from other apps can be sent too.
 */
class MainActivity : Activity() {
    private val executor = Executors.newCachedThreadPool()

    /** The running transfer, for Stop. */
    @Volatile
    private var current: WdtTransfer? = null

    /** Files shared to this app (ACTION_SEND), sent instead of picking. */
    private var sharedUris: List<Uri> = emptyList()

    private lateinit var addressView: TextView
    private lateinit var receiveButton: Button
    private lateinit var receivePanel: LinearLayout
    private lateinit var receiveUrlView: TextView
    private lateinit var urlInput: EditText
    private lateinit var sendButton: Button
    private lateinit var selfTestButton: Button
    private lateinit var stopButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildUi()
        setContentView(root)
        applySystemBarInsets(root) // needs the window's decor: after setContentView
        log("WDT ${Wdt.version}, protocol ${Wdt.protocolVersion}")
        showAddress()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        // close() waits for the transfer to stop: not on the UI thread
        current?.let { transfer -> executor.execute { transfer.close() } }
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ receive

    private fun startReceive() {
        val address = preferredAddress()
        if (address == null) {
            log("No network: connect to Wi-Fi (both devices on the same network)")
            return
        }
        if (address.rank > RANK_HOTSPOT) {
            log("Not on Wi-Fi (${address.iface}): the sender may not reach ${address.ip}")
        }
        // Received into internal storage, then copied to Download/WDT
        val staging = File(filesDir, "incoming").apply {
            deleteRecursively()
            mkdirs()
        }
        val options = WdtOptions().apply {
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
        receiveUrlView.text = url
        receivePanel.visibility = View.VISIBLE
        setBusy(receiver)
        log("Waiting for the sender (10 min)...")
        executor.execute {
            val report = receiver.awaitFinish()
            receiver.close()
            val saved = if (report.isSuccess) saveReceived(staging) else null
            runOnUiThread {
                receivePanel.visibility = View.GONE
                finished("Received", report)
                saved?.let { log(it) }
            }
        }
    }

    /** Moves the received files to Download/WDT (where file managers see them). */
    private fun saveReceived(staging: File): String {
        val files = staging.walkTopDown().filter { it.isFile && it.name != ".wdt.log" }.toList()
        if (Build.VERSION.SDK_INT < 29) {
            return "Files saved in ${staging.path}"
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
                logFromWorker("Could not save ${file.name}")
                continue
            }
            contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            file.delete()
            count++
        }
        staging.deleteRecursively()
        return "Saved $count file(s) in Download/WDT"
    }

    // --------------------------------------------------------------- send

    private fun onSendClicked() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("wdt://")) {
            log("First paste the receiver's URL (it starts with wdt://)")
            return
        }
        if (sharedUris.isNotEmpty()) {
            send(url, sharedUris)
            return
        }
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
        if (uris.isNotEmpty()) send(urlInput.text.toString().trim(), uris)
    }

    private fun send(url: String, uris: List<Uri>) {
        setBusy(null)
        log("Sending ${uris.size} file(s)...")
        executor.execute {
            val opened = mutableListOf<ParcelFileDescriptor>()
            // for content that can't be read through a plain file descriptor
            val staging = File(cacheDir, "outgoing").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val names = HashSet<String>()
                val files = uris.map { uri ->
                    val name = uniqueName(displayName(uri), names)
                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("Can't open $uri")
                    if (pfd.statSize >= 0) {
                        opened += pfd
                        WdtSender.SourceFile.fromFileDescriptor(name, pfd)
                    } else {
                        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                            File(staging, name).outputStream().use { input.copyTo(it) }
                        }
                        WdtSender.SourceFile(name)
                    }
                }
                val options = WdtOptions().apply { progressReportIntervalMillis = 250 }
                val sender = WdtSender(url, staging, options, files)
                runOnUiThread { current = sender }
                val report = sender.use { it.transfer(progressListener) }
                runOnUiThread {
                    if (report.isSuccess) sharedUris = emptyList()
                    updateSendButton()
                    finished("Sent", report)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    log("Send failed: ${e.message}")
                    setIdle()
                }
            } finally {
                opened.forEach { it.close() }
                staging.deleteRecursively()
            }
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

    /** wdt:// links, URLs and files shared to the app. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("wdt://") }?.let {
                urlInput.setText(it)
                log("Got a receiver URL: choose the files to send")
            }
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                    ?.takeIf { it.startsWith("wdt://") }?.let {
                        urlInput.setText(it)
                        log("Got a receiver URL: choose the files to send")
                    }
                streamExtra(intent)?.let { sharedUris = listOf(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> sharedUris = streamListExtra(intent)
        }
        if (sharedUris.isNotEmpty()) {
            log("${sharedUris.size} shared file(s) ready: paste the receiver's URL and send")
        }
        updateSendButton()
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

    private fun updateSendButton() {
        sendButton.text =
            if (sharedUris.isEmpty()) "Choose files and send" else "Send ${sharedUris.size} shared file(s)"
    }

    // ---------------------------------------------------------- self-test

    /** Sends 20 MB to this device itself, and compares. */
    private fun selfTest() {
        setBusy(null)
        log("Self-test: sending 20 MB to this device...")
        executor.execute {
            val src = File(cacheDir, "selftest-src").apply { deleteRecursively(); mkdirs() }
            val dst = File(cacheDir, "selftest-dst").apply { deleteRecursively(); mkdirs() }
            try {
                for (i in 1..20) File(src, "file$i.bin").writeBytes(Random.nextBytes(1 shl 20))
                val options = WdtOptions().apply {
                    numPorts = 4
                    progressReportIntervalMillis = 100
                }
                WdtReceiver(dst, options).use { receiver ->
                    val url = receiver.start()
                    val received = executor.submit<TransferReport> { receiver.awaitFinish() }
                    val report = WdtSender(url, src, options).use { sender ->
                        runOnUiThread { current = sender }
                        sender.transfer(progressListener)
                    }
                    received.get()
                    val identical = src.listFiles()!!.all {
                        it.readBytes().contentEquals(File(dst, it.name).readBytes())
                    }
                    runOnUiThread {
                        finished("Self-test", report)
                        log(if (identical) "Self-test: files identical" else "Self-test: FILES DIFFER")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    log("Self-test failed: $e")
                    setIdle()
                }
            } finally {
                src.deleteRecursively()
                dst.deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------- state

    private val progressListener = ProgressListener { p ->
        runOnUiThread {
            if (p.totalBytes > 0) {
                progressBar.isIndeterminate = false
                progressBar.progress = p.percent
                progressText.text = "%s / %s  ·  %.1f MB/s".format(
                    mb(p.bytesTransferred), mb(p.totalBytes), p.currentThroughputMBps,
                )
            } else {
                progressText.text = "%s  ·  %.1f MB/s".format(mb(p.bytesTransferred), p.currentThroughputMBps)
            }
        }
    }

    private fun setBusy(transfer: WdtTransfer?) {
        current = transfer
        for (b in listOf(receiveButton, sendButton, selfTestButton)) b.isEnabled = false
        stopButton.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setIdle() {
        current = null
        for (b in listOf(receiveButton, sendButton, selfTestButton)) b.isEnabled = true
        stopButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun finished(what: String, report: TransferReport) {
        setIdle()
        if (report.isSuccess) {
            progressText.text = ""
            // only the sender counts files
            val files = if (report.numFiles > 0) "${report.numFiles} file(s), " else ""
            log(
                "$what: $files${mb(report.bytesTransferred)} in " +
                    "%.1f s (%.1f MB/s)".format(report.totalTimeSeconds, report.throughputMBps),
            )
        } else {
            log("$what: failed, ${report.errorCode}")
            if (report.failedFiles.isNotEmpty()) log("  failed: ${report.failedFiles.joinToString()}")
        }
    }

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1e6)

    private fun log(message: String) {
        Log.i(TAG, message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("$time  $message\n")
    }

    private fun logFromWorker(message: String) = runOnUiThread { log(message) }

    // ------------------------------------------------------------ network

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

    private fun showAddress() {
        val all = localAddresses()
        addressView.text = if (all.isEmpty()) {
            "This device: no network"
        } else {
            "This device: " + all.joinToString { "${it.ip} (${it.iface})" }
        }
    }

    // ----------------------------------------------------------------- UI

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        column.addView(text("WDT", 30f, bold = true))
        column.addView(text("Warp speed Data Transfer", 14f, secondary = true))
        addressView = text("", 14f, secondary = true).also { column.addView(it, margins(top = 4)) }

        column.addView(heading("Receive"))
        column.addView(text("Files are saved in Download/WDT.", 14f, secondary = true))
        receiveButton = button("Receive files") { startReceive() }.also { column.addView(it, margins(top = 8)) }
        receivePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        receivePanel.addView(text("Give this URL to the sender:", 14f), margins(top = 12))
        receiveUrlView = text("", 13f, mono = true).apply { setTextIsSelectable(true) }
        receivePanel.addView(receiveUrlView, margins(top = 4))
        receivePanel.addView(
            row(
                button("Copy URL") { copy(receiveUrlView.text.toString()) },
                button("Share URL") { share(receiveUrlView.text.toString()) },
            ),
        )
        receivePanel.addView(
            text(
                "Other phone: this app → paste the URL under Send.\n" +
                    "Computer: wdt -directory <folder> -connection_url '<URL>'",
                13f, secondary = true,
            ),
            margins(top = 4),
        )
        column.addView(receivePanel)

        column.addView(heading("Send"))
        urlInput = EditText(this).apply {
            id = R.id.url_input // keeps the text across recreation
            hint = "Receiver URL (wdt://...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        column.addView(urlInput, margins(top = 4))
        sendButton = button("Choose files and send") { onSendClicked() }
        column.addView(row(button("Paste") { paste() }, sendButton))

        stopButton = button("Stop") {
            current?.abort()
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
        clipboard.setPrimaryClip(ClipData.newPlainText("WDT URL", value))
        // Android 13+ shows its own confirmation
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(value: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        startActivity(Intent.createChooser(send, "Share the WDT URL"))
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (value.isNullOrEmpty()) {
            log("The clipboard is empty")
        } else {
            urlInput.setText(value)
        }
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
        const val RANK_HOTSPOT = 2
    }
}
