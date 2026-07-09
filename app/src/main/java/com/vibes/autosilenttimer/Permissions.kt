package com.vibes.autosilenttimer

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }
}

internal fun canPostNotificationsForSdk(
    sdkInt: Int,
    permissionGranted: Boolean
): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted
