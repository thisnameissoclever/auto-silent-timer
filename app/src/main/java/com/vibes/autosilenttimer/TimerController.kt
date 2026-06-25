package com.vibes.autosilenttimer

import android.content.Context
import android.media.AudioManager

/**
 * Single place that ends an active restore timer, so the alarm, the notification
 * actions and the in-app buttons all behave identically.
 *
 * Two outcomes are offered:
 *  - [restoreSound]: turn the ringer/notification sound back on now (the natural
 *    end of a timer, or an explicit "Restore sound").
 *  - [stopTimer]: drop the timer but leave the current (silent/vibrate) ringer
 *    untouched, i.e. stay silenced indefinitely.
 *
 * Both cancel any pending restore alarm, clear [Prefs.timerEndAtMillis] and
 * refresh the ongoing foreground-service notification so its text/actions match
 * the new state.
 */
object TimerController {

    /**
     * Ends the timer and restores [AudioManager.RINGER_MODE_NORMAL] (sound on).
     *
     * [RingerController.setRinger] opens its own suppression window (and wraps the
     * change in try/catch), so the resulting `RINGER_MODE_CHANGED` broadcast is not
     * mistaken for a manual change.
     *
     * @param alerted when true, also posts the one-shot "sound restored" alert.
     *   Used for the alarm-driven expiry (the user may not be looking); skipped
     *   when the user triggered the restore themselves and already knows.
     */
    fun restoreSound(context: Context, alerted: Boolean) {
        AlarmScheduler.cancel(context)
        RingerController.setRinger(context, AudioManager.RINGER_MODE_NORMAL)
        if (alerted) NotificationHelper.showRestoreNotification(context)
        Prefs(context).clearTimer()
        NotificationHelper.updateMonitorNotification(context)
    }

    /**
     * Ends the timer without touching the ringer: the phone stays in whatever
     * silenced mode the user set, indefinitely.
     */
    fun stopTimer(context: Context) {
        AlarmScheduler.cancel(context)
        Prefs(context).clearTimer()
        NotificationHelper.updateMonitorNotification(context)
    }
}
