package com.vibes.autosilenttimer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.vibes.autosilenttimer.databinding.ActivityMainBinding
import java.text.DateFormat
import java.util.Date

/**
 * Onboarding / control screen.
 *
 * Owns the [Prefs.monitoringEnabled] flag (the engine never touches it): the
 * Start/Stop button flips the flag and then starts/stops [MonitorService]. Also
 * surfaces the four special permissions with deep-links to grant them, and shows
 * a live countdown of any active restore timer while resumed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())

    /** Re-renders the status block (incl. the countdown) roughly once a second. */
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val notificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
            maybeAutoStartMonitoring()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnStartStop.setOnClickListener { onStartStopClicked() }
        binding.btnRestoreSound.setOnClickListener { onRestoreSoundClicked() }
        binding.btnStopTimer.setOnClickListener { onStopTimerClicked() }

        binding.permOverlayButton.setOnClickListener {
            openSettings(Permissions.overlaySettingsIntent(this))
        }
        binding.permDndButton.setOnClickListener {
            openSettings(Permissions.dndAccessSettingsIntent())
        }
        binding.permNotificationsButton.setOnClickListener { requestNotificationsPermission() }
        binding.permExactAlarmButton.setOnClickListener {
            openSettings(Permissions.exactAlarmSettingsIntent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        maybeAutoStartMonitoring()
        // Drives the first status render immediately and then ticks every second.
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    /**
     * Auto-starts monitoring when the user intends it on ([Prefs.monitoringEnabled],
     * which defaults to true) and both required permissions are present. Starting an
     * already-running service is a no-op, so this is safe to call on every resume and
     * after the notifications-permission result. An explicit Stop sets the flag false,
     * so it correctly prevents auto-restart until the user taps Start again.
     */
    private fun maybeAutoStartMonitoring() {
        if (prefs.monitoringEnabled &&
            Permissions.canDrawOverlays(this) &&
            Permissions.hasDndAccess(this)
        ) {
            MonitorService.start(this)
        }
    }

    // region Monitoring start/stop

    private fun onStartStopClicked() {
        if (prefs.monitoringEnabled) {
            // Stop: flip the flag first, then stop the engine.
            prefs.monitoringEnabled = false
            MonitorService.stop(this)
            toast(R.string.msg_monitoring_stopped)
            refreshStatus()
            return
        }

        // Overlay + DND access are required for the core flow to work at all.
        if (!Permissions.canDrawOverlays(this) || !Permissions.hasDndAccess(this)) {
            toast(R.string.msg_permission_required)
            refreshPermissions()
            return
        }

        // Start: flip the flag first, then start the engine.
        prefs.monitoringEnabled = true
        MonitorService.start(this)

        // Notifications + exact alarm are recommended (not required): warn but allow.
        val recommendedMissing =
            !Permissions.hasPostNotifications(this) || !Permissions.canScheduleExactAlarms(this)
        toast(if (recommendedMissing) R.string.msg_recommended_permissions else R.string.msg_monitoring_started)
        refreshStatus()
    }

    // endregion

    // region Timer actions

    /** "Restore sound" — turn the ringer back on now, as if the timer had elapsed. */
    private fun onRestoreSoundClicked() {
        TimerController.restoreSound(this, alerted = false)
        toast(R.string.msg_sound_restored)
        refreshStatus()
    }

    /** "Stop timer" — drop the timer but leave the phone silenced indefinitely. */
    private fun onStopTimerClicked() {
        TimerController.stopTimer(this)
        toast(R.string.msg_timer_stopped)
        refreshStatus()
    }

    // endregion

    // region Permission actions

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Implicitly granted below API 33; nothing to request.
            refreshPermissions()
            return
        }
        if (Permissions.hasPostNotifications(this)) {
            refreshPermissions()
        } else {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Some settings deep-links are unavailable on certain devices/ROMs. */
    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.msg_settings_unavailable)
        } catch (e: SecurityException) {
            toast(R.string.msg_settings_unavailable)
        }
    }

    // endregion

    // region UI refresh

    private fun refreshStatus() {
        val enabled = prefs.monitoringEnabled
        val permsReady = Permissions.canDrawOverlays(this) && Permissions.hasDndAccess(this)
        // "Running" = intent on AND able to function; "pending" = intent on but
        // perms missing (auto-starts once granted); otherwise explicitly off.
        val running = enabled && permsReady

        val statusRes = when {
            running -> R.string.status_monitoring_on
            enabled -> R.string.status_monitoring_pending
            else -> R.string.status_monitoring_off
        }
        binding.statusMonitoring.text = getString(statusRes)
        binding.statusMonitoring.setTextColor(
            resolveColor(
                if (running) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )

        // Show Stop while running and Start while off. In the pending state the
        // permission rows below own the next action, so hide the button to avoid
        // a misleading "Start"/"Stop" affordance that can't do anything yet.
        if (enabled && !permsReady) {
            binding.btnStartStop.visibility = View.GONE
        } else {
            binding.btnStartStop.visibility = View.VISIBLE
            binding.btnStartStop.text =
                getString(if (running) R.string.action_stop else R.string.action_start)
            binding.btnStartStop.setIconResource(
                if (running) R.drawable.main_ic_stop else R.drawable.main_ic_play
            )
        }

        val endAt = prefs.timerEndAtMillis
        val remaining = endAt - System.currentTimeMillis()
        val timerActive = endAt > 0L && remaining > 0L
        if (timerActive) {
            binding.statusTimer.text =
                getString(R.string.status_timer_format, formatRemaining(remaining))
            binding.statusTimerUntil.text =
                getString(R.string.status_timer_until_format, formatClock(endAt, remaining))
            binding.statusTimerUntil.visibility = View.VISIBLE
        } else {
            binding.statusTimer.text = getString(R.string.status_timer_none)
            binding.statusTimerUntil.visibility = View.GONE
        }
        // The Restore sound / Stop timer controls only make sense with a live timer.
        binding.timerActionsRow.visibility = if (timerActive) View.VISIBLE else View.GONE
    }

    private fun refreshPermissions() {
        bindPermissionRow(
            Permissions.canDrawOverlays(this),
            binding.permOverlayStatus,
            binding.permOverlayButton
        )
        bindPermissionRow(
            Permissions.hasDndAccess(this),
            binding.permDndStatus,
            binding.permDndButton
        )
        bindPermissionRow(
            Permissions.hasPostNotifications(this),
            binding.permNotificationsStatus,
            binding.permNotificationsButton
        )
        bindPermissionRow(
            Permissions.canScheduleExactAlarms(this),
            binding.permExactAlarmStatus,
            binding.permExactAlarmButton
        )
    }

    private fun bindPermissionRow(
        granted: Boolean,
        statusChip: TextView,
        grantButton: MaterialButton
    ) {
        statusChip.setText(if (granted) R.string.perm_status_granted else R.string.perm_status_needed)
        val backgroundAttr =
            if (granted) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorErrorContainer
        val foregroundAttr =
            if (granted) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnErrorContainer
        statusChip.backgroundTintList = ColorStateList.valueOf(resolveColor(backgroundAttr))
        statusChip.setTextColor(resolveColor(foregroundAttr))
        grantButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    // endregion

    // region Helpers

    /** Formats a duration like "1d 2h 3m 4s", trimming leading zero units. */
    private fun formatRemaining(ms: Long): String {
        var totalSeconds = ms / 1_000L
        val days = totalSeconds / 86_400L
        totalSeconds %= 86_400L
        val hours = totalSeconds / 3_600L
        totalSeconds %= 3_600L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return buildString {
            if (days > 0L) append("${days}d ")
            if (days > 0L || hours > 0L) append("${hours}h ")
            if (days > 0L || hours > 0L || minutes > 0L) append("${minutes}m ")
            append("${seconds}s")
        }
    }

    /** Restore wall-clock time; includes the date when more than a day out. */
    private fun formatClock(endAt: Long, remaining: Long): String {
        val format = if (remaining >= DAY_MS) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        } else {
            DateFormat.getTimeInstance(DateFormat.SHORT)
        }
        return format.format(Date(endAt))
    }

    @ColorInt
    private fun resolveColor(@AttrRes attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    private fun toast(@StringRes resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    // endregion

    companion object {
        private const val TICK_INTERVAL_MS = 1_000L
        private const val DAY_MS = 86_400_000L
    }
}
