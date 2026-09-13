package com.vibes.autosilenttimer

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * Typed wrapper around the app's single [SharedPreferences] file
 * ("auto_silent_timer").
 *
 * Implemented as a small class constructed with a [Context]: `Prefs(context)`.
 * The application context is used internally, so an instance is safe to hold,
 * and any number of instances created from different components (Activity,
 * Service, BroadcastReceiver) all read/write the same backing file and stay
 * consistent.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Last unit the user picked in the custom-duration row, as one of
     * "minutes" / "hours" / "days". Defaults to "hours".
     */
    var lastUnit: String
        get() = prefs.getString(KEY_LAST_UNIT, DEFAULT_UNIT) ?: DEFAULT_UNIT
        set(value) = prefs.edit().putString(KEY_LAST_UNIT, value).apply()

    /**
     * Last accepted silent-mode timer duration, in milliseconds. A value of 0L
     * means the user has not accepted a duration yet.
     */
    var lastDurationMillis: Long
        get() = prefs.getLong(KEY_LAST_DURATION_MILLIS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_DURATION_MILLIS, value.coerceAtLeast(0L)).apply()

    /**
     * Whether the user currently has monitoring enabled. Defaults to true: the
     * app represents the user's intent as "on", and auto-starts the engine once
     * the required permissions are present. An explicit Stop sets this to false.
     */
    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    /**
     * Wall-clock time (epoch millis, [System.currentTimeMillis] basis) at which
     * the ringer should be restored to normal, or 0L when no timer is active.
     */
    var timerEndAtMillis: Long
        get() = prefs.getLong(KEY_TIMER_END_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_TIMER_END_AT, value).apply()

    /** Clears any active timer by resetting [timerEndAtMillis] to 0L. */
    fun clearTimer() {
        timerEndAtMillis = 0L
    }

    // region Process-death bookkeeping (see ProcessDeath)

    /**
     * True from [MonitorService.onCreate] until [MonitorService.onDestroy]. If it
     * is still true when the service is created again, the previous process died
     * without a clean shutdown: Android, the phone maker, or the user killed it.
     *
     * Written synchronously ([SharedPreferences.Editor.commit]) because a kill can
     * follow at any moment and an unflushed write would hide it.
     */
    var serviceAlive: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ALIVE, false)
        @SuppressLint("ApplySharedPref") // Deliberate: must hit disk before a kill can.
        set(value) {
            prefs.edit().putBoolean(KEY_SERVICE_ALIVE, value).commit()
        }

    /** Wall-clock time (epoch millis) the service last started, or 0L. Committed synchronously. */
    var lastServiceStartAt: Long
        get() = prefs.getLong(KEY_LAST_SERVICE_START_AT, 0L)
        @SuppressLint("ApplySharedPref") // Deliberate: see serviceAlive.
        set(value) {
            prefs.edit().putLong(KEY_LAST_SERVICE_START_AT, value).commit()
        }

    /** Coarse cause of the last detected kill, as a [KillCause] name, or null if none. */
    val lastKillCause: String?
        get() = prefs.getString(KEY_LAST_KILL_CAUSE, null)

    /** Android's own free-text description of the last kill, when it gave one. */
    val lastKillDescription: String?
        get() = prefs.getString(KEY_LAST_KILL_DESCRIPTION, null)

    /** Wall-clock time (epoch millis) of the last detected kill, or 0L. */
    val lastKillAt: Long
        get() = prefs.getLong(KEY_LAST_KILL_AT, 0L)

    /** Records a detected kill so the UI can show why monitoring stopped. */
    fun recordKill(cause: KillCause, description: String?, atMillis: Long) {
        prefs.edit()
            .putString(KEY_LAST_KILL_CAUSE, cause.name)
            .putString(KEY_LAST_KILL_DESCRIPTION, description)
            .putLong(KEY_LAST_KILL_AT, atMillis)
            .apply()
    }

    // endregion

    companion object {
        const val FILE_NAME = "auto_silent_timer"
        const val DEFAULT_UNIT = "hours"

        private const val KEY_LAST_UNIT = "last_unit"
        private const val KEY_LAST_DURATION_MILLIS = "last_duration_millis"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_TIMER_END_AT = "timer_end_at_millis"
        private const val KEY_SERVICE_ALIVE = "service_alive"
        private const val KEY_LAST_SERVICE_START_AT = "last_service_start_at"
        private const val KEY_LAST_KILL_CAUSE = "last_kill_cause"
        private const val KEY_LAST_KILL_DESCRIPTION = "last_kill_description"
        private const val KEY_LAST_KILL_AT = "last_kill_at"
    }
}
