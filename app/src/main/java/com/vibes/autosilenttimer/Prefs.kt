package com.vibes.autosilenttimer

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

    companion object {
        const val FILE_NAME = "auto_silent_timer"
        const val DEFAULT_UNIT = "hours"

        private const val KEY_LAST_UNIT = "last_unit"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_TIMER_END_AT = "timer_end_at_millis"
    }
}
