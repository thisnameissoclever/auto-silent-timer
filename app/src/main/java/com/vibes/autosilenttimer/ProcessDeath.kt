package com.vibes.autosilenttimer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/** Coarse reasons the monitoring process can die. Stored by name in [Prefs]. */
enum class KillCause {
    /** Force stop, or the Stop button in the notification shade's "Active apps". */
    USER,

    /** The app was updated or reinstalled, or a permission changed; Android kills the old process. */
    UPDATED,

    /** Android reclaimed memory. */
    MEMORY,

    /** Android's battery rules (excessive background use, a battery restriction). */
    BATTERY,

    /** The app crashed or stopped responding. */
    CRASH,

    /** Killed by the system for some other reason; Android's description says more. */
    SYSTEM,

    /** Android refused to let the service come back to the foreground after a kill. */
    RESTART_REFUSED,

    /** The service was alive when the process ended, but Android kept no record of why. */
    UNKNOWN
}

/**
 * Detects when the monitoring process died without a clean shutdown and records
 * why, using Android's own exit history ([ApplicationExitInfo]), so the main
 * screen can say what happened instead of claiming monitoring is on.
 *
 * The mechanism is a flag in [Prefs]: set when the service is created, cleared
 * when it is destroyed. A service that starts and finds the flag already set
 * knows the previous process was killed while monitoring was supposed to be
 * running.
 */
object ProcessDeath {

    /** Newest exit records to inspect; only the main process since the last start matters. */
    private const val MAX_RECORDS = 8

    /**
     * Call first thing in [MonitorService.onCreate]. If [Prefs.serviceAlive] is
     * still set, the previous process was killed; look up Android's reason and
     * record it. Then marks the service alive and stamps the start time.
     */
    fun noteServiceStart(context: Context) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        if (prefs.serviceAlive) {
            val exit = latestExitSince(context, prefs.lastServiceStartAt)
            if (exit != null) {
                prefs.recordKill(
                    classifyExit(exit.reason, exit.description),
                    exit.description,
                    exit.timestamp
                )
            } else {
                prefs.recordKill(KillCause.UNKNOWN, null, now)
            }
        }
        prefs.lastServiceStartAt = now
        prefs.serviceAlive = true
    }

    /** Call from [MonitorService.onDestroy] (and at boot): a clean stop is not a kill. */
    fun noteServiceStop(context: Context) {
        Prefs(context).serviceAlive = false
    }

    /**
     * Call when Android refuses to start the service, or to bring it to the
     * foreground. The single "last kill" slot always holds the most recent
     * event, so this replaces any earlier record; clearing [Prefs.serviceAlive]
     * stops the next successful start from re-recording an older kill over it.
     * The actionable part is the same either way: the battery row is red.
     */
    fun noteRestartRefused(context: Context) {
        val prefs = Prefs(context)
        prefs.serviceAlive = false
        prefs.recordKill(KillCause.RESTART_REFUSED, null, System.currentTimeMillis())
    }

    private class Exit(val reason: Int, val description: String?, val timestamp: Long)

    private fun latestExitSince(context: Context, sinceMillis: Long): Exit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Api30.latestExitSince(context, sinceMillis)
    }

    // Kept in its own class so devices below API 30 never load ApplicationExitInfo.
    @RequiresApi(Build.VERSION_CODES.R)
    private object Api30 {
        fun latestExitSince(context: Context, sinceMillis: Long): Exit? {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val records = try {
                am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
            } catch (e: RuntimeException) {
                // Some OEM builds throw for the calling package; treat as "no record".
                return null
            }
            return records
                .asSequence()
                .filter { it.processName == context.packageName }
                .filter { it.reason != ApplicationExitInfo.REASON_EXIT_SELF }
                .filter { it.timestamp >= sinceMillis }
                .maxByOrNull { it.timestamp }
                ?.let { Exit(it.reason, it.description, it.timestamp) }
        }
    }
}

/**
 * Maps an [ApplicationExitInfo] reason code, plus Android's free-text
 * description, to a [KillCause]. Pure so it can be unit-tested. Reason codes are
 * compile-time constants, so this is safe to call on any API level.
 */
internal fun classifyExit(reason: Int, description: String?): KillCause = when (reason) {
    ApplicationExitInfo.REASON_USER_REQUESTED,
    ApplicationExitInfo.REASON_USER_STOPPED -> KillCause.USER

    ApplicationExitInfo.REASON_PACKAGE_UPDATED,
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> KillCause.UPDATED

    ApplicationExitInfo.REASON_LOW_MEMORY -> KillCause.MEMORY

    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> KillCause.BATTERY

    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> KillCause.CRASH

    else -> if (description?.contains("restrict", ignoreCase = true) == true) {
        // e.g. "bg restricted" from a battery-restriction kill.
        KillCause.BATTERY
    } else {
        KillCause.SYSTEM
    }
}
