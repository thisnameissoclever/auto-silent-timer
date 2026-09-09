package com.vibes.autosilenttimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Stateless helpers for checking the special permissions this app needs and for
 * building the Intents that deep-link to the matching system settings screens.
 *
 * Shared by both the monitoring engine and the onboarding UI. All version-gated
 * APIs are guarded with [Build.VERSION] checks and degrade sensibly on older
 * releases.
 */
object Permissions {

    /** True if the app may draw overlays (SYSTEM_ALERT_WINDOW). */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** True if the app has Do Not Disturb / notification-policy access. */
    fun hasDndAccess(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    /** True if exact alarms can be scheduled. Always true below Android 12 (S). */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** True if notifications may be posted. Always true below Android 13 (TIRAMISU). */
    fun hasPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return canPostNotificationsForSdk(Build.VERSION.SDK_INT, permissionGranted)
    }

    /**
     * True if the app is exempt from battery optimization (Doze / App Standby).
     *
     * This is the one setting Android and the phone makers' battery managers all
     * honor. Without it, a long-running background app, the monitoring service
     * included, eventually gets stopped. It is also one of the exemptions that
     * let an app restart its own foreground service from the background on
     * Android 12 and newer.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * True if the app's battery usage is set to "Restricted" (Android 9+). In that
     * state Android will not run the monitoring service while the app is not on
     * screen, and refuses to restart it. Only the user can change it, on the
     * app's details page.
     */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isBackgroundRestricted
    }

    /** True when Android will leave the monitoring service alone in the background. */
    fun hasUnrestrictedBattery(context: Context): Boolean =
        isIgnoringBatteryOptimizations(context) && !isBackgroundRestricted(context)

    /** Opens the "display over other apps" settings for this app. */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /** Opens the system Do Not Disturb / notification-policy access list. */
    fun dndAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /**
     * Opens this app's exact-alarm permission screen (Android 12+). On older
     * releases there is no such screen, so this falls back to the app's details
     * page where the user can review permissions.
     */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            appDetailsIntent(context)
        }

    /**
     * Opens the one-tap system dialog asking to let this app always run in the
     * background. Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest.
     *
     * Lint flags this intent because Play only allows it when an app's core
     * function is adversely affected without the exemption. That is the case
     * here: the app is a background monitor and nothing else. See section 16 of
     * docs/google-play-publishing.md for the review justification.
     */
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )

    /** Opens the system list of battery-optimization exemptions (fallback). */
    fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Opens this app's details page, where the Restricted battery setting lives. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
}

internal fun canPostNotificationsForSdk(
    sdkInt: Int,
    permissionGranted: Boolean
): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted
