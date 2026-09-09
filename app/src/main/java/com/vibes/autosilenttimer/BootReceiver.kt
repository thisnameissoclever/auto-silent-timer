package com.vibes.autosilenttimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms monitoring after the two events that kill the app's process without
 * any way for the process to restart itself:
 *  - a reboot (`BOOT_COMPLETED`), and
 *  - an app update or reinstall (`MY_PACKAGE_REPLACED`).
 *
 * Both are documented exemptions that let a background app start a foreground
 * service. [MonitorService.startIfEnabled] applies the permission gate, which
 * avoids launching a useless foreground service (with its persistent
 * notification) when the app cannot function.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // A reboot is not a kill: the service went down with the phone.
                ProcessDeath.noteServiceStop(context)
                MonitorService.startIfEnabled(context)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> MonitorService.startIfEnabled(context)
        }
    }
}
