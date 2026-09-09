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
import kotlin.math.abs

/**
 * Onboarding / control screen.
 *
 * Owns the [Prefs.monitoringEnabled] flag (the engine never touches it): the
 * Start/Stop button flips the flag and then starts/stops [MonitorService]. Also
 * surfaces the special permissions with deep-links to grant them, shows a live
 * countdown of any active restore timer while resumed, and reports whether the
 * service is actually alive plus why it last died ([ProcessDeath]).
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
        binding.permBatteryButton.setOnClickListener { onBatteryButtonClicked() }
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
     *
     * This is also the recovery path of last resort: if Android killed the service
     * and no other hook brought it back, opening the app does.
     */
    private fun maybeAutoStartMonitoring() {
        MonitorService.startIfEnabled(this)
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

        // Notifications, exact alarm and the battery exemption are recommended (not
        // required): warn but allow.
        val recommendedMissing =
            !Permissions.hasPostNotifications(this) ||
                !Permissions.canScheduleExactAlarms(this) ||
                !Permissions.hasUnrestrictedBattery(this)
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

    /**
     * Battery row. Two different system screens depending on what is wrong:
     *  - "Restricted" battery usage can only be changed on the app's details page,
     *    so explain and go there;
     *  - otherwise open the one-tap "always run in background" dialog. A few
     *    devices lack it, so fall back to the system exemption list.
     */
    private fun onBatteryButtonClicked() {
        if (Permissions.isBackgroundRestricted(this)) {
            toast(R.string.msg_battery_restricted)
            openSettings(Permissions.appDetailsIntent(this))
            return
        }
        try {
            startActivity(Permissions.batteryExemptionIntent(this))
        } catch (e: ActivityNotFoundException) {
            openSettings(Permissions.batteryOptimizationListIntent())
        } catch (e: SecurityException) {
            openSettings(Permissions.batteryOptimizationListIntent())
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
        // "wanted" = intent on AND able to function; "running" additionally means
        // the service is alive right now (Android can kill it underneath us, and
        // the pref would never know); "pending" = intent on but perms missing
        // (auto-starts once granted); otherwise explicitly off.
        val wanted = enabled && permsReady
        val running = wanted && MonitorService.isRunning

        val statusRes = when {
            running -> R.string.status_monitoring_on
            wanted -> R.string.status_monitoring_starting
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

        // Show Stop while wanted and Start while off. In the pending state the
        // permission rows below own the next action, so hide the button to avoid
        // a misleading "Start"/"Stop" affordance that can't do anything yet.
        if (enabled && !permsReady) {
            binding.btnStartStop.visibility = View.GONE
        } else {
            binding.btnStartStop.visibility = View.VISIBLE
            binding.btnStartStop.text =
                getString(if (wanted) R.string.action_stop else R.string.action_start)
            binding.btnStartStop.setIconResource(
                if (wanted) R.drawable.main_ic_stop else R.drawable.main_ic_play
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

        renderLastKill()
    }

    /**
     * Shows why the monitoring process last died, once Android has killed it at
     * least once. Deliberately kept visible after a successful restart: the whole
     * point is that "it stopped again" becomes a readable line instead of a guess.
     */
    private fun renderLastKill() {
        val cause = prefs.lastKillCause?.let { name ->
            runCatching { KillCause.valueOf(name) }.getOrNull()
        }
        if (cause == null) {
            binding.statusLastStop.visibility = View.GONE
            return
        }
        val at = prefs.lastKillAt
        val line = getString(
            R.string.status_last_kill_format,
            getString(killCauseRes(cause)),
            formatClock(at, System.currentTimeMillis() - at)
        )
        val detail = prefs.lastKillDescription?.takeIf { it.isNotBlank() }
        binding.statusLastStop.text = if (detail != null) {
            line + "\n" + getString(R.string.status_last_kill_detail_format, detail)
        } else {
            line
        }
        binding.statusLastStop.visibility = View.VISIBLE
    }

    @StringRes
    private fun killCauseRes(cause: KillCause): Int = when (cause) {
        KillCause.USER -> R.string.kill_cause_user
        KillCause.UPDATED -> R.string.kill_cause_updated
        KillCause.MEMORY -> R.string.kill_cause_memory
        KillCause.BATTERY -> R.string.kill_cause_battery
        KillCause.CRASH -> R.string.kill_cause_crash
        KillCause.SYSTEM -> R.string.kill_cause_system
        KillCause.RESTART_REFUSED -> R.string.kill_cause_restart_refused
        KillCause.UNKNOWN -> R.string.kill_cause_unknown
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
        bindPermissionRow(
            Permissions.hasUnrestrictedBattery(this),
            binding.permBatteryStatus,
            binding.permBatteryButton
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

    /**
     * Wall-clock time; includes the date when [distanceMs] (how far from now, in
     * either direction) is more than a day.
     */
    private fun formatClock(at: Long, distanceMs: Long): String {
        val format = if (abs(distanceMs) >= DAY_MS) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        } else {
            DateFormat.getTimeInstance(DateFormat.SHORT)
        }
        return format.format(Date(at))
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
