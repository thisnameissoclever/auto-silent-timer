package com.vibes.autosilenttimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the duration helpers. These have no Android
 * dependencies, so they run on the local JVM via `testDebugUnitTest`
 * (`npm run test`) without an emulator or Robolectric.
 */
class DurationsTest {

    @Test
    fun computeMillis_multipliesValueByUnit() {
        assertEquals(900_000L, computeMillis(15L, TimeUnitOption.MINUTES))
        assertEquals(3_600_000L, computeMillis(1L, TimeUnitOption.HOURS))
        assertEquals(172_800_000L, computeMillis(2L, TimeUnitOption.DAYS))
    }

    @Test
    fun computeMillis_zeroIsZero() {
        assertEquals(0L, computeMillis(0L, TimeUnitOption.HOURS))
    }

    @Test
    fun fromLabel_matchesLabelsCaseInsensitively() {
        assertEquals(TimeUnitOption.MINUTES, TimeUnitOption.fromLabel("minutes"))
        assertEquals(TimeUnitOption.HOURS, TimeUnitOption.fromLabel("HOURS"))
        assertEquals(TimeUnitOption.DAYS, TimeUnitOption.fromLabel("Days"))
    }

    @Test
    fun fromLabel_defaultsToHoursForUnknownLabel() {
        assertEquals(TimeUnitOption.HOURS, TimeUnitOption.fromLabel("fortnights"))
        assertEquals(TimeUnitOption.HOURS, TimeUnitOption.fromLabel(""))
    }

    @Test
    fun presets_areAscendingAndPositive() {
        val millis = PRESETS.map { it.millis }
        assertEquals("presets should be sorted ascending", millis.sorted(), millis)
        assertTrue("presets should all be positive", millis.all { it > 0L })
    }

    @Test
    fun selectionFromLastDuration_returnsNoneWhenNothingHasBeenRemembered() {
        assertEquals(LastDurationSelection.None, selectionFromLastDuration(0L, Prefs.DEFAULT_UNIT))
    }

    @Test
    fun selectionFromLastDuration_matchesRememberedPreset() {
        val remembered = PRESETS.first { it.label == "2 hours" }

        assertEquals(
            LastDurationSelection.Preset(remembered.millis),
            selectionFromLastDuration(remembered.millis, Prefs.DEFAULT_UNIT)
        )
    }

    @Test
    fun selectionFromLastDuration_prefillsCustomValueUsingLastUnit() {
        val millis = computeMillis(48L, TimeUnitOption.HOURS)

        assertEquals(
            LastDurationSelection.Custom(48L, TimeUnitOption.HOURS),
            selectionFromLastDuration(millis, TimeUnitOption.HOURS.label)
        )
    }
}
