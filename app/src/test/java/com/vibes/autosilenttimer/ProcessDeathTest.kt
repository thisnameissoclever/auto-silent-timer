package com.vibes.autosilenttimer

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessDeathTest {

    @Test
    fun classifyExit_userStopsAreUser() {
        assertEquals(
            KillCause.USER,
            classifyExit(ApplicationExitInfo.REASON_USER_REQUESTED, "stop from task manager")
        )
        assertEquals(KillCause.USER, classifyExit(ApplicationExitInfo.REASON_USER_STOPPED, null))
    }

    @Test
    fun classifyExit_packageAndPermissionChangesAreUpdated() {
        assertEquals(KillCause.UPDATED, classifyExit(ApplicationExitInfo.REASON_PACKAGE_UPDATED, null))
        assertEquals(KillCause.UPDATED, classifyExit(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, null))
        assertEquals(KillCause.UPDATED, classifyExit(ApplicationExitInfo.REASON_PERMISSION_CHANGE, null))
    }

    @Test
    fun classifyExit_memoryBatteryAndCrashes() {
        assertEquals(KillCause.MEMORY, classifyExit(ApplicationExitInfo.REASON_LOW_MEMORY, null))
        assertEquals(
            KillCause.BATTERY,
            classifyExit(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, null)
        )
        assertEquals(KillCause.CRASH, classifyExit(ApplicationExitInfo.REASON_CRASH, null))
        assertEquals(KillCause.CRASH, classifyExit(ApplicationExitInfo.REASON_CRASH_NATIVE, null))
        assertEquals(KillCause.CRASH, classifyExit(ApplicationExitInfo.REASON_ANR, null))
        assertEquals(
            KillCause.CRASH,
            classifyExit(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, null)
        )
    }

    @Test
    fun classifyExit_otherWithRestrictionDescriptionIsBattery() {
        assertEquals(KillCause.BATTERY, classifyExit(ApplicationExitInfo.REASON_OTHER, "bg restricted"))
        assertEquals(KillCause.BATTERY, classifyExit(ApplicationExitInfo.REASON_OTHER, "Background Restricted"))
    }

    @Test
    fun classifyExit_otherWithoutRestrictionDescriptionIsSystem() {
        assertEquals(KillCause.SYSTEM, classifyExit(ApplicationExitInfo.REASON_OTHER, null))
        assertEquals(KillCause.SYSTEM, classifyExit(ApplicationExitInfo.REASON_OTHER, "kill background"))
        assertEquals(KillCause.SYSTEM, classifyExit(ApplicationExitInfo.REASON_SIGNALED, "signal 9"))
        assertEquals(KillCause.SYSTEM, classifyExit(ApplicationExitInfo.REASON_UNKNOWN, null))
    }
}
