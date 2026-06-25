package com.vibes.autosilenttimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Schedules and cancels the exact alarm that restores the ringer when a timer
 * elapses.
 *
 * A fixed request code together with [PendingIntent.FLAG_UPDATE_CURRENT] means a
 * fresh [schedule] always *replaces* any previously pending restore alarm, and
 * [cancel] rebuilds the identical [PendingIntent] so the alarm can be torn down.
 */
object AlarmScheduler {

    private const val REQUEST_CODE = 2001

    /**
     * Schedules the restore alarm for [endAtMillis] (a [System.currentTimeMillis]
     * / `RTC` value). Uses an exact alarm when allowed, falling back to an
     * inexact-but-while-idle alarm otherwise.
     */
    fun schedule(context: Context, endAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = restorePendingIntent(context)
        if (Permissions.canScheduleExactAlarms(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAtMillis, pi)
        }
    }

    /** Cancels any pending restore alarm. */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(restorePendingIntent(context))
    }

    private fun restorePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimerActionReceiver::class.java)
            .setAction(TimerActionReceiver.ACTION_TIMER_EXPIRED)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
