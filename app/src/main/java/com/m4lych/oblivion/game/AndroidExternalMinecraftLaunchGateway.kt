package com.m4lych.oblivion.game

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

/** Android adapter for supported package metadata and separate-app launch APIs only. */
class AndroidExternalMinecraftLaunchGateway(context: Context) : ExternalMinecraftLaunchGateway {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override fun inspectPackage(): MinecraftPackageStatus {
        val info = try {
            queryMinecraftPackage()
        } catch (_: PackageManager.NameNotFoundException) {
            return MinecraftPackageStatus(visible = false, enabled = false)
        }

        val applicationInfo = info.applicationInfo
            ?: return MinecraftPackageStatus(visible = true, enabled = false)
        return MinecraftPackageStatus(visible = true, enabled = applicationInfo.enabled)
    }

    override fun createLaunchIntent(): ExternalLaunchIntent? =
        packageManager.getLaunchIntentForPackage(MINECRAFT_PACKAGE)?.let(::AndroidExternalLaunchIntent)

    override fun startActivity(intent: ExternalLaunchIntent) {
        val androidIntent = (intent as? AndroidExternalLaunchIntent)?.intent
            ?: throw IllegalArgumentException("Launch intent was not created by the Android Minecraft gateway")
        try {
            appContext.startActivity(androidIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: ActivityNotFoundException) {
            throw ExternalActivityNotFoundException(error.message, error)
        }
    }

    private fun queryMinecraftPackage(): PackageInfo {
        val flags = PackageManager.GET_ACTIVITIES
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                MINECRAFT_PACKAGE,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(MINECRAFT_PACKAGE, flags)
        }
    }

    private data class AndroidExternalLaunchIntent(val intent: Intent) : ExternalLaunchIntent

    companion object {
        const val MINECRAFT_PACKAGE = "com.mojang.minecraftpe"

        fun isInstalled(context: Context): Boolean = runCatching {
            AndroidExternalMinecraftLaunchGateway(context).inspectPackage().visible
        }.getOrDefault(false)
    }
}
