package com.vibes.autosilenttimer

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Always-on foreground service that watches the ringer mode and drives the
 * silent-mode prompt.
 *
 * Responsibilities:
 *  - Run as a `specialUse` foreground service with the ongoing notification.
 *  - Runtime-register a receiver for [AudioManager.RINGER_MODE_CHANGED_ACTION].
 *  - On a *user-initiated* switch to VIBRATE/SILENT, show the overlay prompt.
 *  - On a *user-initiated* return to NORMAL, cancel any pending restore alarm
 *    and clear the timer.
 *
 * Monitoring on/off is owned by the UI ([Prefs.monitoringEnabled] + the
 * companion [start]/[stop]); this service never modifies that flag. Self-made
 * ringer changes are filtered out via [RingerController.shouldIgnore].
 */
class MonitorService : Service() {

    private var overlay: OverlayController? = null
    private var ringerReceiver: BroadcastReceiver? = null
    private var dndReceiver: BroadcastReceiver? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingPrompt: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)

        overlay = OverlayController(
            context = this,
            onOk = { durationMillis -> onOverlayOk(durationMillis) },
            onCancel = { onOverlayCancel() }
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == AudioManager.RINGER_MODE_CHANGED_ACTION) {
                    onRingerModeChanged()
                }
            }
        }
        ringerReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Track DND/Bedtime transitions so a ringer change they cause can be told
        // apart from the user flipping the ringer switch. System broadcast: still
        // delivered to a NOT_EXPORTED runtime receiver.
        val dnd = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
                    RingerController.noteDndFilterChanged()
                }
            }
        }
        dndReceiver = dnd
        ContextCompat.registerReceiver(
            this,
            dnd,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent may be null on sticky redelivery; nothing here depends on it.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NotificationHelper.FGS_ID,
            NotificationHelper.buildMonitorNotification(this),
            type
        )
        rearmPendingTimer()
        return START_STICKY
    }

    /**
     * Re-arms the restore alarm for a timer still pending in [Prefs].
     *
     * `AlarmManager` alarms do not survive a reboot, but [Prefs.timerEndAtMillis]
     * does — so without this a timer active before a reboot would be silently
     * lost and the ringer never restored. Re-scheduling on every (re)start of the
     * service covers boot, sticky redelivery and auto-start; it is idempotent
     * because [AlarmScheduler.schedule] replaces any identical pending alarm. An
     * already-elapsed end time fires the alarm (near-)immediately, the desired
     * catch-up when the timer ran out while the device was off.
     */
    private fun rearmPendingTimer() {
        val end = Prefs(this).timerEndAtMillis
        if (end > 0L) {
            AlarmScheduler.schedule(this, end)
        }
    }

    private fun onRingerModeChanged() {
        if (RingerController.shouldIgnore()) return
        when (RingerController.currentMode(this)) {
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_SILENT -> scheduleSilencePrompt()

            AudioManager.RINGER_MODE_NORMAL -> {
                // User manually returned to normal: drop any pending timer/overlay.
                cancelPendingPrompt()
                AlarmScheduler.cancel(this)
                Prefs(this).clearTimer()
                NotificationHelper.updateMonitorNotification(this)
                overlay?.let { if (it.isShowing) it.dismiss() }
            }
        }
    }

    /**
     * Shows the silent-mode prompt unless the silence was caused by Do Not Disturb
     * or Bedtime mode.
     *
     * `RINGER_MODE_CHANGED` and `ACTION_INTERRUPTION_FILTER_CHANGED` are delivered
     * from independent paths with no guaranteed ordering, so the decision cannot
     * be made synchronously here. The prompt is deferred briefly to give the DND
     * broadcast time to land; if a DND transition occurred around this ringer
     * change *and* DND is still in effect, the silence was DND-driven and no
     * prompt is shown. Requiring a recent transition (not merely "DND is on")
     * means a manual silence still prompts under an always-on DND schedule.
     */
    private fun scheduleSilencePrompt() {
        cancelPendingPrompt()
        val runnable = Runnable {
            pendingPrompt = null
            if (RingerController.dndChangedRecently() && RingerController.isDndActive(this)) return@Runnable
            // The user may have flipped back to normal during the delay.
            if (RingerController.currentMode(this) == AudioManager.RINGER_MODE_NORMAL) return@Runnable
            // User silenced the phone: prompt (replacing-the-timer happens on OK).
            overlay?.let { if (!it.isShowing) it.show() }
        }
        pendingPrompt = runnable
        handler.postDelayed(runnable, PROMPT_DELAY_MS)
    }

    private fun cancelPendingPrompt() {
        pendingPrompt?.let { handler.removeCallbacks(it) }
        pendingPrompt = null
    }

    private fun onOverlayOk(durationMillis: Long) {
        val end = System.currentTimeMillis() + durationMillis
        Prefs(this).timerEndAtMillis = end
        AlarmScheduler.schedule(this, end)
        NotificationHelper.updateMonitorNotification(this)
        overlay?.dismiss()
    }

    private fun onOverlayCancel() {
        // Stay silenced indefinitely: just dismiss, no timer.
        overlay?.dismiss()
    }

    override fun onDestroy() {
        cancelPendingPrompt()
        listOfNotNull(ringerReceiver, dndReceiver).forEach {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered; ignore.
            }
        }
        ringerReceiver = null
        dndReceiver = null
        overlay?.dismiss()
        overlay = null
        super.onDestroy()
    }

    companion object {
        /**
         * How long the silent-mode prompt is deferred so a DND/Bedtime-driven
         * silence (whose `ACTION_INTERRUPTION_FILTER_CHANGED` broadcast may arrive
         * after this ringer change) can be recognised before the prompt appears.
         *
         * Kept comfortably under [RingerController.DND_CORRELATION_WINDOW_MS] and
         * well below the threshold of a perceptible UI lag: it widens the margin
         * for the two unordered broadcasts to be correlated at the cost of the
         * prompt appearing a fraction of a second later after a genuine silence.
         */
        private const val PROMPT_DELAY_MS = 600L

        /** Starts the monitoring service in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java)
            )
        }

        /** Stops the monitoring service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
