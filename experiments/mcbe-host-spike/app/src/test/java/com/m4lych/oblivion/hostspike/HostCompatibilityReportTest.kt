package com.m4lych.oblivion.hostspike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCompatibilityReportTest {
    @Test
    fun installedPackageDoesNotClaimRuntimeOrEntitlement() {
        val report = report(installed = true)

        assertEquals("NO_AUTHORIZED_IN_APP_RUNTIME", report.outcome)
        assertTrue(report.asDisplayText().contains("does not verify Minecraft ownership"))
        assertTrue(report.asDisplayText().contains("No gameplay session was started"))
        assertFalse(report.asDisplayText().contains("Minecraft entitlement verified"))
    }

    @Test
    fun absentPackageIsReportedWithoutClaimingLaunch() {
        val report = report(installed = false)
        val text = report.asDisplayText()

        assertEquals("MINECRAFT_NOT_INSTALLED", report.outcome)
        assertTrue(text.contains("No external app was launched"))
        assertTrue(text.contains("Samsung SM-M215F"))
    }

    @Test
    fun reportIncludesSupportedPublicDeviceMetadata() {
        val report = report(installed = false)
        val text = report.asDisplayText()

        assertTrue(text.contains("Android version: 12 (API 31)"))
        assertTrue(text.contains("arm64-v8a"))
        assertTrue(text.contains("OpenGL ES level reported by device: 3.2"))
    }

    private fun report(installed: Boolean) = HostCompatibilityReport(
        installed = installed,
        packageVersion = if (installed) "1.21.0" else null,
        packageVersionCode = if (installed) 121000L else null,
        installerPackage = if (installed) "com.android.vending" else null,
        splitCount = if (installed) 2 else 0,
        exportedActivityNames = emptyList(),
        nativeLibraryDirectoryReported = installed,
        deviceManufacturer = "Samsung",
        deviceModel = "SM-M215F",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        processIs64Bit = true,
        androidRelease = "12",
        androidApi = 31,
        availableMemoryMb = 1_200,
        totalMemoryMb = 4_096,
        reportedGlEsVersion = "3.2"
    )
}
