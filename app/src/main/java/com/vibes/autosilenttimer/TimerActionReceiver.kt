package com.vibes.autosilenttimer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Single entry point for everything that *ends* an active restore timer:
 *  - [ACTION_TIMER_EXPIRED] — the scheduled alarm fired (see [AlarmScheduler]).
 *  - [ACTION_RESTORE_SOUND] — the user tapped "Restore sound" (notification action).
 *  - [ACTION_STOP_TIMER]   — the user tapped "Stop timer" (notification action).
 *
 * The work itself lives in [TimerController] so the in-app buttons can share it.
 */
class TimerActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_TIMER_EXPIRED -> TimerController.restoreSound(context, alerted = true)
            ACTION_RESTORE_SOUND -> TimerController.restoreSound(context, alerted = false)
            ACTION_STOP_TIMER -> TimerController.stopTimer(context)
        }
    }

    companion object {
        /** Fired by the [AlarmScheduler] alarm when the timer runs out. */
        const val ACTION_TIMER_EXPIRED = "com.vibes.autosilenttimer.action.TIMER_EXPIRED"

        /** User-initiated "restore sound now" from a notification action. */
        const val ACTION_RESTORE_SOUND = "com.vibes.autosilenttimer.action.RESTORE_SOUND"

        /** User-initiated "stop timer, stay silenced" from a notification action. */
        const val ACTION_STOP_TIMER = "com.vibes.autosilenttimer.action.STOP_TIMER"

        private const val REQUEST_RESTORE_SOUND = 2002
        private const val REQUEST_STOP_TIMER = 2003

        /** PendingIntent for the "Restore sound" notification action. */
        fun restoreSoundPendingIntent(context: Context): PendingIntent =
            broadcast(context, REQUEST_RESTORE_SOUND, ACTION_RESTORE_SOUND)

        /** PendingIntent for the "Stop timer" notification action. */
        fun stopTimerPendingIntent(context: Context): PendingIntent =
            broadcast(context, REQUEST_STOP_TIMER, ACTION_STOP_TIMER)

        private fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent {
            val intent = Intent(context, TimerActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
