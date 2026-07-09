package com.vibes.autosilenttimer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {

    @Test
    fun canPostNotificationsForSdk_allowsPreAndroid13WithoutRuntimePermission() {
        assertTrue(canPostNotificationsForSdk(sdkInt = 32, permissionGranted = false))
    }

    @Test
    fun canPostNotificationsForSdk_requiresRuntimePermissionOnAndroid13AndLater() {
        assertFalse(canPostNotificationsForSdk(sdkInt = 33, permissionGranted = false))
        assertTrue(canPostNotificationsForSdk(sdkInt = 33, permissionGranted = true))
        assertFalse(canPostNotificationsForSdk(sdkInt = 34, permissionGranted = false))
        assertTrue(canPostNotificationsForSdk(sdkInt = 34, permissionGranted = true))
    }
}
