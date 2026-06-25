package com.vibes.autosilenttimer

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock

/**
 * Process-wide singleton that performs ringer-mode changes and suppresses the
 * app's *own* changes so they don't re-trigger the silent-mode prompt.
 *
 * Both [MonitorService]'s runtime `RINGER_MODE_CHANGED` receiver and
 * [TimerActionReceiver] run in the same process, so a single shared
 * [suppressUntil] window lets the receiver tell "the user flipped the switch"
 * apart from "we just restored the ringer ourselves".
 */
object RingerController {

    /**
     * How long a self-initiated ringer change stays suppressed, in millis.
     *
     * Must comfortably outlast the `RINGER_MODE_CHANGED` broadcast round-trip so
     * the monitor does not treat our own restore as a manual flip and run its
     * own (redundant) teardown for it.
     */
    private const val SUPPRESS_WINDOW_MS = 2000L

    /** [SystemClock.elapsedRealtime] up to which receiver handling is ignored. */
    @Volatile
    private var suppressUntil: Long = 0L

    /**
     * Sets the ringer [mode], first opening a suppression window so the ensuing
     * `RINGER_MODE_CHANGED` broadcast is recognised as self-initiated and
     * ignored. Setting NORMAL/SILENT can throw [SecurityException] when the app
     * lacks Do Not Disturb access, so the change is wrapped defensively.
     */
    fun setRinger(context: Context, mode: Int) {
        suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_WINDOW_MS
        try {
            audioManager(context).ringerMode = mode
        } catch (e: SecurityException) {
            // No DND access: the system rejected the change. Nothing else to do.
        }
    }

    /** True while a self-initiated change is still within the suppression window. */
    fun shouldIgnore(): Boolean = SystemClock.elapsedRealtime() < suppressUntil

    /** Current ringer mode, one of `AudioManager.RINGER_MODE_*`. */
    fun currentMode(context: Context): Int = audioManager(context).ringerMode

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
