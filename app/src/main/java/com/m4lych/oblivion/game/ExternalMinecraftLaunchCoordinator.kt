package com.m4lych.oblivion.game

/** Minimal package state read through Android's public PackageManager APIs. */
data class MinecraftPackageStatus(val visible: Boolean, val enabled: Boolean)

/** Marker keeps Android Intent out of the coordinator and makes launch behavior unit-testable. */
interface ExternalLaunchIntent

interface ExternalMinecraftLaunchGateway {
    fun inspectPackage(): MinecraftPackageStatus
    fun createLaunchIntent(): ExternalLaunchIntent?
    fun startActivity(intent: ExternalLaunchIntent)
}

class ExternalActivityNotFoundException(message: String?, cause: Throwable? = null) : Exception(message, cause)

enum class ExternalLaunchFailure {
    PACKAGE_MISSING,
    PACKAGE_DISABLED,
    LAUNCH_INTENT_MISSING,
    ACTIVITY_NOT_FOUND,
    SECURITY_DENIED,
    OTHER
}

sealed interface ExternalLaunchOutcome {
    data object Dispatched : ExternalLaunchOutcome
    data class Failed(val reason: ExternalLaunchFailure) : ExternalLaunchOutcome
}

/** Coordinates the public Android app handoff and reports dispatch without claiming gameplay. */
class ExternalMinecraftLaunchCoordinator(
    private val gateway: ExternalMinecraftLaunchGateway,
    private val logEvent: (event: String, detail: String, error: Throwable?) -> Unit
) {
    fun launch(): ExternalLaunchOutcome {
        logEvent("fallback_requested", "package=${AndroidExternalMinecraftLaunchGateway.MINECRAFT_PACKAGE}", null)
        logEvent("package_visibility_check", "stage=started", null)

        val packageStatus = try {
            gateway.inspectPackage()
        } catch (error: SecurityException) {
            logEvent("package_visibility_check", "visible=denied", error)
            return failed(ExternalLaunchFailure.SECURITY_DENIED, "stage=package_visibility_check", error)
        } catch (error: Exception) {
            logEvent("package_visibility_check", "visible=error", error)
            return failed(ExternalLaunchFailure.OTHER, "stage=package_visibility_check", error)
        }

        if (!packageStatus.visible) {
            logEvent("package_visibility_check", "visible=false", null)
            logEvent("minecraft_package_missing", "package=${AndroidExternalMinecraftLaunchGateway.MINECRAFT_PACKAGE}", null)
            return failed(ExternalLaunchFailure.PACKAGE_MISSING, "stage=package_visibility_check", null)
        }

        logEvent("package_visibility_check", "visible=true enabled=${packageStatus.enabled}", null)
        logEvent("minecraft_package_found", "enabled=${packageStatus.enabled}", null)
        if (!packageStatus.enabled) {
            return failed(ExternalLaunchFailure.PACKAGE_DISABLED, "stage=package_enabled_check", null)
        }

        val intent = try {
            gateway.createLaunchIntent()
        } catch (error: SecurityException) {
            logEvent("launch_intent_missing", "reason=security_denied", error)
            return failed(ExternalLaunchFailure.SECURITY_DENIED, "stage=launch_intent_creation", error)
        } catch (error: Exception) {
            logEvent("launch_intent_missing", "reason=error", error)
            return failed(ExternalLaunchFailure.OTHER, "stage=launch_intent_creation", error)
        }
        if (intent == null) {
            logEvent("launch_intent_missing", "reason=no_launchable_activity", null)
            return failed(ExternalLaunchFailure.LAUNCH_INTENT_MISSING, "stage=launch_intent_creation", null)
        }

        logEvent("launch_intent_created", "package=${AndroidExternalMinecraftLaunchGateway.MINECRAFT_PACKAGE}", null)
        logEvent("minecraft_activity_launch_attempted", "package=${AndroidExternalMinecraftLaunchGateway.MINECRAFT_PACKAGE}", null)
        try {
            gateway.startActivity(intent)
        } catch (error: ExternalActivityNotFoundException) {
            return failed(ExternalLaunchFailure.ACTIVITY_NOT_FOUND, "stage=start_activity", error)
        } catch (error: SecurityException) {
            return failed(ExternalLaunchFailure.SECURITY_DENIED, "stage=start_activity", error)
        } catch (error: Exception) {
            return failed(ExternalLaunchFailure.OTHER, "stage=start_activity", error)
        }

        logEvent(
            "minecraft_activity_launch_dispatched",
            "package=${AndroidExternalMinecraftLaunchGateway.MINECRAFT_PACKAGE}; android_accepted_request=true; gameplay_verified=false",
            null
        )
        return ExternalLaunchOutcome.Dispatched
    }

    private fun failed(reason: ExternalLaunchFailure, detail: String, error: Throwable?): ExternalLaunchOutcome.Failed {
        logEvent("minecraft_activity_launch_failed", "$detail reason=$reason", error)
        return ExternalLaunchOutcome.Failed(reason)
    }
}
