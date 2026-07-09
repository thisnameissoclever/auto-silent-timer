package com.vibes.autosilenttimer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

/**
 * Centralises the notification channels and the two notifications this app
 * posts: the ongoing foreground-service ("monitoring") notification and the
 * one-shot "ringer restored" alert.
 */
object NotificationHelper {

    /** Notification id of the ongoing foreground-service notification. */
    const val FGS_ID = 1001

    private const val RESTORE_ID = 1002

    // Bumped from the original "monitor" (IMPORTANCE_LOW) channel: a channel's
    // importance is fixed once created, so a new id is required for the lower
    // IMPORTANCE_MIN to take effect on existing installs. The legacy channel is
    // deleted in [ensureChannels].
    private const val CHANNEL_MONITOR = "monitor_status"
    private const val CHANNEL_MONITOR_LEGACY = "monitor"
    private const val CHANNEL_RESTORE = "restore"

    /** Creates the notification channels. Idempotent; safe to call repeatedly. */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Remove the old IMPORTANCE_LOW channel so it doesn't linger in Settings.
        nm.deleteNotificationChannel(CHANNEL_MONITOR_LEGACY)

        // IMPORTANCE_MIN keeps the ongoing FGS notification in the shade (required
        // to keep the service alive) but hides its icon from the status bar.
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.channel_monitor_desc)
            setShowBadge(false)
        }

        val restore = NotificationChannel(
            CHANNEL_RESTORE,
            context.getString(R.string.channel_restore_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_restore_desc)
        }

        nm.createNotificationChannel(monitor)
        nm.createNotificationChannel(restore)
    }

    /**
     * Builds the ongoing foreground-service notification. Its text reflects the
     * active timer (formatted local end time) when one is set, otherwise the
     * idle "watching" text. While a timer is active it also exposes the
     * "Restore sound" and "Stop timer" actions (visible when the notification is
     * expanded).
     */
    fun buildMonitorNotification(context: Context): Notification {
        val end = Prefs(context).timerEndAtMillis
        val hasTimer = end > 0L
        val text = if (hasTimer) {
            context.getString(R.string.notif_monitor_text_timer, formatEndTime(end))
        } else {
            context.getString(R.string.notif_monitor_text)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.overlay_ic_notification)
            .setContentTitle(context.getString(R.string.notif_monitor_title))
            .setContentText(text)
            .setContentIntent(mainActivityIntent(context))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (hasTimer) {
            builder.addAction(
                R.drawable.ic_action_sound,
                context.getString(R.string.action_restore_sound),
                TimerActionReceiver.restoreSoundPendingIntent(context)
            )
            builder.addAction(
                R.drawable.main_ic_stop,
                context.getString(R.string.action_stop_timer),
                TimerActionReceiver.stopTimerPendingIntent(context)
            )
        }

        return builder.build()
    }

    /** Re-posts the FGS notification so its text reflects current timer state. */
    fun updateMonitorNotification(context: Context) {
        notifySafely(context, FGS_ID, buildMonitorNotification(context))
    }

    /** Posts the one-shot "ringer restored" notification on the restore channel. */
    fun showRestoreNotification(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESTORE)
            .setSmallIcon(R.drawable.overlay_ic_notification)
            .setContentTitle(context.getString(R.string.notif_restore_title))
            .setContentText(context.getString(R.string.notif_restore_text))
            .setContentIntent(mainActivityIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifySafely(context, RESTORE_ID, notification)
    }

    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission may have changed between the check and notify call.
        }
    }

    private fun mainActivityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatEndTime(endAtMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(endAtMillis))
}
