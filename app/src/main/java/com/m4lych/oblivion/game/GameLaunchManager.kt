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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LaunchPhase {
    IDLE,
    PREPARING_IN_CLIENT,
    IN_CLIENT_UNAVAILABLE,
    EXTERNAL_FALLBACK_STARTING,
    EXTERNAL_FALLBACK_DISPATCHED,
    EXTERNAL_FALLBACK_RETURNED,
    FAILED,
    INTERRUPTED
}

data class LaunchUiState(
    val phase: LaunchPhase = LaunchPhase.IDLE,
    val message: String = "",
    val gameInstalled: Boolean = false,
    val canRetryExternalFallback: Boolean = false
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
    private val externalLauncher = ExternalMinecraftLaunchCoordinator(
        AndroidExternalMinecraftLaunchGateway(application)
    ) { event, detail, error ->
        val level = when (event) {
            "minecraft_activity_launch_failed", "launch_intent_missing", "minecraft_package_missing" -> LogLevel.ERROR
            "package_visibility_check", "minecraft_package_found" -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }
        logger.write(level, "minecraft-launch", "$event package=$MINECRAFT_PACKAGE $detail", error)
    }
    private val _state = MutableStateFlow(restoreState(savedState))
    val state: StateFlow<LaunchUiState> = _state.asStateFlow()
    private var fallbackWasBackgrounded = false
    private var fallbackAttemptId = 0L

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
        if (_state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_STARTING ||
            _state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_DISPATCHED
        ) return

        val context = getApplication<Application>()
        fallbackWasBackgrounded = false
        fallbackAttemptId += 1
        val attemptId = fallbackAttemptId
        setState(
            LaunchUiState(
                phase = LaunchPhase.EXTERNAL_FALLBACK_STARTING,
                message = "Preparing to open Minecraft as a separate app…",
                gameInstalled = _state.value.gameInstalled
            )
        )
        viewModelScope.launch {
            // Let Compose render the launching state before PackageManager work begins.
            delay(120)
            val outcome = withContext(Dispatchers.IO) { externalLauncher.launch() }
            when (outcome) {
                ExternalLaunchOutcome.Dispatched -> {
                    if (_state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_RETURNED) return@launch
                    setState(
                        LaunchUiState(
                            phase = LaunchPhase.EXTERNAL_FALLBACK_DISPATCHED,
                            message = "Android accepted the request to open Minecraft separately. This does not confirm that gameplay loaded.",
                            gameInstalled = true
                        )
                    )
                    viewModelScope.launch {
                        delay(EXTERNAL_SWITCH_TIMEOUT_MS)
                        if (_state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_DISPATCHED &&
                            fallbackAttemptId == attemptId && !fallbackWasBackgrounded
                        ) {
                            logger.write(
                                LogLevel.WARNING,
                                "minecraft-launch",
                                "minecraft_activity_switch_not_observed package=$MINECRAFT_PACKAGE"
                            )
                            setState(
                                LaunchUiState(
                                    phase = LaunchPhase.FAILED,
                                    message = "Android accepted the request, but Oblivion stayed in the foreground. Confirm Minecraft is enabled, then retry.",
                                    gameInstalled = true,
                                    canRetryExternalFallback = true
                                )
                            )
                        }
                    }
                }
                is ExternalLaunchOutcome.Failed -> when (outcome.reason) {
                    ExternalLaunchFailure.PACKAGE_MISSING -> {
                        setState(
                            LaunchUiState(
                                phase = LaunchPhase.FAILED,
                                message = "Minecraft was not visible as an installed app. Install the official app from Google Play, then retry.",
                                gameInstalled = false
                            )
                        )
                        openOfficialStore(context)
                    }
                    ExternalLaunchFailure.PACKAGE_DISABLED -> setFallbackFailure(
                        "Minecraft is installed but disabled. Enable the official app in Android settings, then retry."
                    )
                    ExternalLaunchFailure.LAUNCH_INTENT_MISSING -> setFallbackFailure(
                        "Minecraft is installed and enabled, but Android did not provide a launchable activity."
                    )
                    ExternalLaunchFailure.ACTIVITY_NOT_FOUND -> setFallbackFailure(
                        "Android could not open Minecraft. Confirm that the official app is enabled, then retry."
                    )
                    ExternalLaunchFailure.SECURITY_DENIED -> setFallbackFailure(
                        "Android denied the Minecraft handoff. Check the app installation and retry."
                    )
                    ExternalLaunchFailure.OTHER -> setFallbackFailure(
                        "Oblivion could not request the Minecraft launch. Review Diagnostics and retry."
                    )
                }
            }
        }
    }

    fun onHostPaused() {
        if (_state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_STARTING ||
            _state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_DISPATCHED
        ) {
            fallbackWasBackgrounded = true
            logger.write(LogLevel.INFO, "minecraft-launch", "oblivion_paused_during_fallback package=$MINECRAFT_PACKAGE")
        }
    }

    fun onHostResumed() {
        if (fallbackWasBackgrounded &&
            (_state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_STARTING ||
                _state.value.phase == LaunchPhase.EXTERNAL_FALLBACK_DISPATCHED)
        ) {
            fallbackWasBackgrounded = false
            setState(_state.value.copy(phase = LaunchPhase.EXTERNAL_FALLBACK_RETURNED, message = "Oblivion resumed after the separate-app handoff. No Minecraft gameplay state was verified."))
            logger.write(LogLevel.INFO, "minecraft-launch", "Oblivion resumed after separate-app fallback; no gameplay state was verified")
        }
    }

    private fun setFallbackFailure(message: String) {
        setState(
            LaunchUiState(
                phase = LaunchPhase.FAILED,
                message = message,
                gameInstalled = true,
                canRetryExternalFallback = true
            )
        )
    }

    private fun setState(value: LaunchUiState) {
        _state.value = value
        savedState[KEY_PHASE] = value.phase.name
        savedState[KEY_MESSAGE] = value.message
        savedState[KEY_INSTALLED] = value.gameInstalled
        savedState[KEY_RETRY_EXTERNAL] = value.canRetryExternalFallback
    }

    private fun restoreState(handle: SavedStateHandle): LaunchUiState {
        val oldPhase = runCatching { LaunchPhase.valueOf(handle[KEY_PHASE] ?: LaunchPhase.IDLE.name) }.getOrDefault(LaunchPhase.IDLE)
        val phase = restoreLaunchPhaseAfterProcessDeath(oldPhase)
        val message = if (phase == LaunchPhase.INTERRUPTED) {
            "The launch flow was interrupted when Android recreated Oblivion. No in-client game session was restored. Retry the check or use the separately labeled compatibility fallback."
        } else handle[KEY_MESSAGE] ?: ""
        val retryExternal = handle[KEY_RETRY_EXTERNAL]
            ?: (oldPhase == LaunchPhase.EXTERNAL_FALLBACK_STARTING || oldPhase == LaunchPhase.EXTERNAL_FALLBACK_DISPATCHED)
        return LaunchUiState(phase, message, handle[KEY_INSTALLED] ?: false, retryExternal)
    }

    private fun openOfficialStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$MINECRAFT_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$MINECRAFT_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
            logger.write(LogLevel.INFO, "minecraft-launch", "official_store_handoff_dispatched; Minecraft launch was not attempted")
        } catch (error: Exception) {
            logger.write(LogLevel.WARNING, "minecraft-launch", "official_store_market_handoff_failed", error)
            try {
                context.startActivity(web)
                logger.write(LogLevel.INFO, "minecraft-launch", "official_store_web_handoff_dispatched; Minecraft launch was not attempted")
            } catch (webError: Exception) {
                setState(_state.value.copy(message = "No store app is available. Find Minecraft from the official Google Play Store on this device."))
                logger.write(LogLevel.ERROR, "minecraft-launch", "official_store_handoff_unavailable", webError)
            }
        }
    }

    companion object {
        const val MINECRAFT_PACKAGE = AndroidExternalMinecraftLaunchGateway.MINECRAFT_PACKAGE
        private const val KEY_PHASE = "game_launch_phase"
        private const val KEY_MESSAGE = "game_launch_message"
        private const val KEY_INSTALLED = "game_launch_installed"
        private const val KEY_RETRY_EXTERNAL = "game_launch_retry_external"
        private const val EXTERNAL_SWITCH_TIMEOUT_MS = 3_000L

        fun isMinecraftInstalled(context: Context): Boolean = AndroidExternalMinecraftLaunchGateway.isInstalled(context)
    }
}

fun restoreLaunchPhaseAfterProcessDeath(phase: LaunchPhase): LaunchPhase = when (phase) {
    LaunchPhase.PREPARING_IN_CLIENT,
    LaunchPhase.EXTERNAL_FALLBACK_STARTING,
    LaunchPhase.EXTERNAL_FALLBACK_DISPATCHED -> LaunchPhase.INTERRUPTED
    else -> phase
}
