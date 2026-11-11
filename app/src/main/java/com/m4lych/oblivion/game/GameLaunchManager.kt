package com.m4lych.oblivion.game

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.m4lych.oblivion.logging.AppLogger
import com.m4lych.oblivion.logging.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LaunchPhase {
    IDLE,
    PREPARING_IN_CLIENT,
    IN_CLIENT_UNAVAILABLE,
    EXTERNAL_FALLBACK_STARTING,
    EXTERNAL_FALLBACK_RETURNED,
    FAILED,
    INTERRUPTED
}

data class LaunchUiState(
    val phase: LaunchPhase = LaunchPhase.IDLE,
    val message: String = "",
    val gameInstalled: Boolean = false
)

/** A future officially authorized runtime adapter can implement this without changing UI flow. */
interface InClientGameHost {
    fun availability(context: Context): HostAvailability
}

sealed interface HostAvailability {
    data object Available : HostAvailability
    data class Unavailable(val explanation: String) : HostAvailability
}

/** This build intentionally has no adapter for Minecraft's private GameActivity/runtime. */
class UnconfiguredGameHost : InClientGameHost {
    override fun availability(context: Context) = HostAvailability.Unavailable(
        "Minecraft Bedrock does not expose a documented Android runtime or embeddable Activity API to Oblivion. The installed game runs under a separate app identity and sandbox. This build has no authorized game runtime adapter, so it cannot host gameplay inside Oblivion."
    )
}

class GameLaunchViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {
    private val logger = AppLogger(application)
    private val host: InClientGameHost = UnconfiguredGameHost()
    private val _state = MutableStateFlow(restoreState(savedState))
    val state: StateFlow<LaunchUiState> = _state.asStateFlow()

    fun requestInClientLaunch() {
        if (_state.value.phase == LaunchPhase.PREPARING_IN_CLIENT) return
        viewModelScope.launch {
            val installed = isMinecraftInstalled(getApplication())
            setState(LaunchUiState(LaunchPhase.PREPARING_IN_CLIENT, "Checking supported in-client runtime…", installed))
            logger.write(LogLevel.INFO, "game-launch", "In-client launch requested; API ${Build.VERSION.SDK_INT}; game installed=$installed")
            val capability = runCatching { host.availability(getApplication()) }
                .getOrElse { error ->
                    logger.write(LogLevel.ERROR, "game-launch", "Runtime capability check failed", error)
                    setState(LaunchUiState(LaunchPhase.FAILED, "Oblivion could not check launch compatibility. Review Diagnostics and retry.", installed))
                    return@launch
                }
            when (capability) {
                HostAvailability.Available -> {
                    // No provider can currently reach this path. Keeping the boundary explicit
                    // prevents the UI from reporting a hosted session without a real game surface.
                    setState(LaunchUiState(LaunchPhase.FAILED, "An in-client runtime provider reported availability but did not provide a launch session.", installed))
                    logger.write(LogLevel.CRITICAL, "game-launch", "Host capability without a session provider")
                }
                is HostAvailability.Unavailable -> {
                    setState(LaunchUiState(LaunchPhase.IN_CLIENT_UNAVAILABLE, capability.explanation, installed))
                    logger.write(LogLevel.WARNING, "game-launch", "In-client gameplay unavailable: ${capability.explanation}")
                }
            }
        }
    }

    fun openExternalCompatibilityFallback() {
        val context = getApplication<Application>()
        val installed = isMinecraftInstalled(context)
        if (!installed) {
            setState(LaunchUiState(LaunchPhase.FAILED, "Minecraft was not detected. Install the official Minecraft app from Google Play, then return to Oblivion.", false))
            openOfficialStore(context)
            return
        }
        setState(LaunchUiState(LaunchPhase.EXTERNAL_FALLBACK_STARTING, "Opening Minecraft as a separate app. Oblivion is not hosting or controlling the game session.", true))
        val intent = context.packageManager.getLaunchIntentForPackage(MINECRAFT_PACKAGE)
        if (intent == null) {
            setState(LaunchUiState(LaunchPhase.FAILED, "Minecraft is installed but Android did not provide a launchable activity.", true))
            logger.write(LogLevel.ERROR, "game-launch", "Installed Minecraft package has no launch intent")
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            logger.write(LogLevel.INFO, "game-launch", "User selected separate-app compatibility fallback")
        } catch (error: ActivityNotFoundException) {
            setState(LaunchUiState(LaunchPhase.FAILED, "Android could not open Minecraft. Confirm that the official app is enabled and try again.", true))
            logger.write(LogLevel.ERROR, "game-launch", "Minecraft fallback activity not found", error)
        } catch (error: SecurityException) {
            setState(LaunchUiState(LaunchPhase.FAILED, "Android denied the Minecraft handoff. Check the app installation and retry.", true))
            logger.write(LogLevel.ERROR, "game-launch", "Minecraft fallback denied", error)
        }
    }

    fun onHostResumed() {
        if (_state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_STARTING) {
            setState(_state.value.copy(phase = LaunchPhase.EXTERNAL_FALLBACK_RETURNED, message = "Returned to Oblivion. Minecraft ran as a separate app; no in-client session is active."))
            logger.write(LogLevel.INFO, "game-launch", "Oblivion resumed after separate-app fallback")
        }
    }

    private fun setState(value: LaunchUiState) {
        _state.value = value
        savedState[KEY_PHASE] = value.phase.name
        savedState[KEY_MESSAGE] = value.message
        savedState[KEY_INSTALLED] = value.gameInstalled
    }

    private fun restoreState(handle: SavedStateHandle): LaunchUiState {
        val oldPhase = runCatching { LaunchPhase.valueOf(handle[KEY_PHASE] ?: LaunchPhase.IDLE.name) }.getOrDefault(LaunchPhase.IDLE)
        val phase = restoreLaunchPhaseAfterProcessDeath(oldPhase)
        val message = if (phase == LaunchPhase.INTERRUPTED) {
            "The launch flow was interrupted when Android recreated Oblivion. No in-client game session was restored. Retry the check or use the separately labeled compatibility fallback."
        } else handle[KEY_MESSAGE] ?: ""
        return LaunchUiState(phase, message, handle[KEY_INSTALLED] ?: false)
    }

    private fun openOfficialStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$MINECRAFT_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$MINECRAFT_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(web) }.onFailure {
                setState(_state.value.copy(message = "No store app is available. Find Minecraft from the official Google Play Store on this device."))
                logger.write(LogLevel.ERROR, "game-launch", "Official store handoff unavailable", it)
            }
        }
    }

    companion object {
        const val MINECRAFT_PACKAGE = "com.mojang.minecraftpe"
        private const val KEY_PHASE = "game_launch_phase"
        private const val KEY_MESSAGE = "game_launch_message"
        private const val KEY_INSTALLED = "game_launch_installed"

        fun isMinecraftInstalled(context: Context): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, 0)
            }
            true
        }.getOrDefault(false)
    }
}

fun restoreLaunchPhaseAfterProcessDeath(phase: LaunchPhase): LaunchPhase = when (phase) {
    LaunchPhase.PREPARING_IN_CLIENT, LaunchPhase.EXTERNAL_FALLBACK_STARTING -> LaunchPhase.INTERRUPTED
    else -> phase
}
