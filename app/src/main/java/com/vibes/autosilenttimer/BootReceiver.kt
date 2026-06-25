package com.vibes.autosilenttimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms monitoring after a reboot. If the user had monitoring enabled and the
 * required permissions are present, the [MonitorService] is started again on
 * BOOT_COMPLETED. The permission gate avoids launching a useless foreground
 * service (with its persistent notification) when the app cannot function.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs(context).monitoringEnabled &&
            Permissions.canDrawOverlays(context) &&
            Permissions.hasDndAccess(context)
        ) {
            MonitorService.start(context)
        }
    }
}
