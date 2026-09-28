/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock

/**
 * A foreground service while a transfer runs: without one, Android freezes
 * the app soon after it leaves the screen, and the transfer with it. It also
 * shows the transfer's progress as a notification, like a download, with a
 * Stop button. The transfers themselves run in the app's own threads.
 *
 * Swiping the app away from the recent apps stops it (stopWithTask), and the
 * transfer (MainActivity.onDestroy).
 */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStop?.invoke()
            return START_NOT_STICKY
        }
        val notification = build(this, title, text, percent)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, notification)
        }
        return START_NOT_STICKY
    }

    /** Android 15+: data transfers get at most 6 h a day in the background. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val DONE_CHANNEL = "done"
        private const val ONGOING_ID = 1
        private const val DONE_ID = 2
        private const val ACTION_STOP = "com.facebook.wdt.sample.STOP"

        /** What the notification's Stop does (set by the activity). */
        @Volatile
        var onStop: (() -> Unit)? = null

        @Volatile
        private var title = "WDT"

        @Volatile
        private var text = ""

        /** 0..100, or -1: unknown */
        @Volatile
        private var percent = -1

        @Volatile
        private var running = false

        @Volatile
        private var lastUpdateMs = 0L

        /** A transfer starts: [what] for the notification's title. */
        fun start(context: Context, what: String) {
            title = what
            text = ""
            percent = -1
            running = true
            lastUpdateMs = 0
            val intent = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** New title (more is known about the transfer). */
        fun retitle(context: Context, what: String) {
            title = what
            update(context, force = true)
        }

        /** Progress (throttled: notifications are rate limited). */
        fun progress(context: Context, detail: String, pct: Int) {
            text = detail
            percent = pct
            update(context, force = false)
        }

        /** The transfer is over: removes its notification. */
        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, TransferService::class.java))
        }

        /** A finished transfer's outcome, as its own notification. */
        fun done(context: Context, doneTitle: String, doneText: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            ensureChannels(manager)
            val notification = builder(context, DONE_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(doneTitle)
                .setContentText(doneText)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            manager.notify(DONE_ID, notification)
        }

        private fun update(context: Context, force: Boolean) {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastUpdateMs < 1000) return
            lastUpdateMs = now
            context.getSystemService(NotificationManager::class.java)
                .notify(ONGOING_ID, build(context, title, text, percent))
        }

        private fun build(context: Context, title: String, text: String, percent: Int): Notification {
            ensureChannels(context.getSystemService(NotificationManager::class.java))
            val stop = PendingIntent.getService(
                context, 0,
                Intent(context, TransferService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            @Suppress("DEPRECATION")
            return builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent.coerceIn(0, 100), percent < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context))
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
        }

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        @Suppress("DEPRECATION")
        private fun builder(context: Context, channel: String): Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context)

        private fun ensureChannels(manager: NotificationManager) {
            if (Build.VERSION.SDK_INT < 26) return
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Transfers in progress", NotificationManager.IMPORTANCE_LOW),
                )
            }
            if (manager.getNotificationChannel(DONE_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(DONE_CHANNEL, "Finished transfers", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }
    }
}
