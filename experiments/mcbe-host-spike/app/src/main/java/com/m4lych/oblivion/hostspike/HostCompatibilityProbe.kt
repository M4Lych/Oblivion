package com.m4lych.oblivion.hostspike

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

data class HostCompatibilityReport(
    val installed: Boolean,
    val packageVersion: String?,
    val packageVersionCode: Long?,
    val installerPackage: String?,
    val splitCount: Int,
    val exportedActivityNames: List<String>,
    val nativeLibraryDirectoryReported: Boolean,
    val deviceManufacturer: String,
    val deviceModel: String,
    val supportedAbis: List<String>,
    val processIs64Bit: Boolean,
    val androidRelease: String,
    val androidApi: Int,
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val reportedGlEsVersion: String?
) {
    val outcome: String
        get() = if (installed) "NO_AUTHORIZED_IN_APP_RUNTIME" else "MINECRAFT_NOT_INSTALLED"

    fun asDisplayText(): String = buildString {
        appendLine("Probe result: $outcome")
        appendLine("Device: ${deviceManufacturer.ifBlank { "unknown manufacturer" }} ${deviceModel.ifBlank { "unknown model" }}")
        appendLine("Android version: $androidRelease (API $androidApi)")
        appendLine("Process: ${if (processIs64Bit) "64-bit" else "32-bit"}")
        appendLine("Supported device ABIs: ${supportedAbis.ifEmpty { listOf("unknown") }.joinToString()}")
        appendLine("Available memory: ${availableMemoryMb} MiB / ${totalMemoryMb} MiB total")
        appendLine("OpenGL ES level reported by device: ${reportedGlEsVersion ?: "not reported"}")
        appendLine()
        if (installed) {
            appendLine("Minecraft package: ${HostCompatibilityProbe.MCBE_PACKAGE}")
            appendLine("Version: ${packageVersion ?: "unknown"} (${packageVersionCode ?: "unknown"})")
            appendLine("Installer package: ${installerPackage ?: "not reported"} (informational only; not ownership verification)")
            appendLine("Split APK count reported by PackageManager: $splitCount")
            appendLine("Native library directory reported: $nativeLibraryDirectoryReported")
            appendLine("Exported activity metadata: ${exportedActivityNames.ifEmpty { listOf("none reported") }.joinToString()}")
            appendLine()
            appendLine("This detects package metadata only. It does not verify Minecraft ownership, entitlement, runtime compatibility, or permission to load the game's code.")
        } else {
            appendLine("Minecraft was not visible as an installed package. No external app was launched.")
        }
        appendLine()
        appendLine("Host status: no authorized public in-app Minecraft runtime interface is configured. No Minecraft APK, DEX, native library, asset, or private app data was opened. No gameplay session was started.")
    }
}

object HostCompatibilityProbe {
    const val MCBE_PACKAGE = "com.mojang.minecraftpe"
    private const val MIB = 1024L * 1024L

    fun inspect(context: Context): HostCompatibilityReport {
        val packageManager = context.packageManager
        val gameInfo = queryGamePackage(packageManager)
        val gameApplication = gameInfo?.applicationInfo
        val memory = ActivityManager.MemoryInfo().also { info ->
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getMemoryInfo(info)
        }
        val glEsVersion = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.deviceConfigurationInfo
            ?.reqGlEsVersion
            ?.takeIf { it != 0 }
            ?.let(::formatGlEsVersion)

        return HostCompatibilityReport(
            installed = gameInfo != null,
            packageVersion = gameInfo?.versionName,
            packageVersionCode = gameInfo?.let(::getVersionCode),
            installerPackage = gameInfo?.let { getInstallerPackage(packageManager) },
            splitCount = gameApplication?.splitSourceDirs?.size ?: 0,
            exportedActivityNames = gameInfo?.activities.orEmpty()
                .filter { it.exported }
                .map { it.name },
            nativeLibraryDirectoryReported = !gameApplication?.nativeLibraryDir.isNullOrBlank(),
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            processIs64Bit = Process.is64Bit(),
            androidRelease = Build.VERSION.RELEASE,
            androidApi = Build.VERSION.SDK_INT,
            availableMemoryMb = memory.availMem / MIB,
            totalMemoryMb = memory.totalMem / MIB,
            reportedGlEsVersion = glEsVersion
        )
    }

    private fun queryGamePackage(packageManager: PackageManager): PackageInfo? {
        val flags = PackageManager.GET_ACTIVITIES
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    MCBE_PACKAGE,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                legacyPackageInfo(packageManager, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun getInstallerPackage(packageManager: PackageManager): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            packageManager.getInstallSourceInfo(MCBE_PACKAGE).installingPackageName
        } else {
            legacyInstallerPackageName(packageManager)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyPackageInfo(packageManager: PackageManager, flags: Int): PackageInfo =
        packageManager.getPackageInfo(MCBE_PACKAGE, flags)

    @Suppress("DEPRECATION")
    private fun legacyInstallerPackageName(packageManager: PackageManager): String? =
        packageManager.getInstallerPackageName(MCBE_PACKAGE)

    private fun getVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode(info)

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()

    private fun formatGlEsVersion(version: Int): String = "${version shr 16}.${version and 0xffff}"
}
