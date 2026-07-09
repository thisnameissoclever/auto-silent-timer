package com.vibes.autosilenttimer

/**
 * Time-unit options for the custom-duration row.
 *
 * [label] matches both the entries of the `time_units` string-array (used by the
 * unit Spinner) and the value persisted by [Prefs.lastUnit], so the three stay
 * in sync. Ordinals are MINUTES, HOURS, DAYS — matching the array order.
 */
enum class TimeUnitOption(val label: String, val millisPerUnit: Long) {
    MINUTES("minutes", 60_000L),
    HOURS("hours", 3_600_000L),
    DAYS("days", 86_400_000L);

    companion object {
        /** Resolves a [TimeUnitOption] from its [label], defaulting to [HOURS]. */
        fun fromLabel(label: String): TimeUnitOption =
            TimeUnitOption.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
                ?: HOURS
    }
}

/** A one-tap preset duration. [millis] is the total length in milliseconds. */
data class Preset(val label: String, val millis: Long)

/** The previously accepted duration, mapped back to the overlay's input model. */
sealed class LastDurationSelection {
    data object None : LastDurationSelection()
    data class Preset(val millis: Long) : LastDurationSelection()
    data class Custom(val value: Long, val unit: TimeUnitOption) : LastDurationSelection()
}

/** Preset durations offered in the overlay prompt, in ascending order. */
val PRESETS: List<Preset> = listOf(
    Preset("15 min", 15L * TimeUnitOption.MINUTES.millisPerUnit),
    Preset("30 min", 30L * TimeUnitOption.MINUTES.millisPerUnit),
    Preset("1 hour", 1L * TimeUnitOption.HOURS.millisPerUnit),
    Preset("2 hours", 2L * TimeUnitOption.HOURS.millisPerUnit),
    Preset("4 hours", 4L * TimeUnitOption.HOURS.millisPerUnit),
    Preset("8 hours", 8L * TimeUnitOption.HOURS.millisPerUnit),
)

/** Converts a [value] expressed in [unit]s into milliseconds. */
fun computeMillis(value: Long, unit: TimeUnitOption): Long = value * unit.millisPerUnit

/**
 * Converts a remembered duration into the visible selection to restore next
 * time the overlay opens.
 */
fun selectionFromLastDuration(
    millis: Long,
    preferredUnitLabel: String
): LastDurationSelection {
    if (millis <= 0L) return LastDurationSelection.None

    PRESETS.firstOrNull { it.millis == millis }?.let {
        return LastDurationSelection.Preset(it.millis)
    }

    val preferredUnit = TimeUnitOption.fromLabel(preferredUnitLabel)
    if (millis % preferredUnit.millisPerUnit == 0L) {
        val value = millis / preferredUnit.millisPerUnit
        if (value > 0L) return LastDurationSelection.Custom(value, preferredUnit)
    }

    val fallbackUnits = listOf(
        TimeUnitOption.DAYS,
        TimeUnitOption.HOURS,
        TimeUnitOption.MINUTES
    )
    val fallbackUnit = fallbackUnits.firstOrNull { millis % it.millisPerUnit == 0L }
        ?: return LastDurationSelection.None

    return LastDurationSelection.Custom(millis / fallbackUnit.millisPerUnit, fallbackUnit)
}
