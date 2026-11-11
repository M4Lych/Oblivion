package com.m4lych.oblivion

import com.m4lych.oblivion.game.ExternalActivityNotFoundException
import com.m4lych.oblivion.game.ExternalLaunchFailure
import com.m4lych.oblivion.game.ExternalLaunchIntent
import com.m4lych.oblivion.game.ExternalLaunchOutcome
import com.m4lych.oblivion.game.ExternalMinecraftLaunchCoordinator
import com.m4lych.oblivion.game.ExternalMinecraftLaunchGateway
import com.m4lych.oblivion.game.MinecraftPackageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMinecraftLaunchCoordinatorTest {
    @Test
    fun missingPackageStopsBeforeCreatingIntent() {
        val gateway = FakeGateway(status = MinecraftPackageStatus(visible = false, enabled = false))
        val events = mutableListOf<String>()

        val result = coordinator(gateway, events).launch()

        assertEquals(ExternalLaunchOutcome.Failed(ExternalLaunchFailure.PACKAGE_MISSING), result)
        assertTrue(events.contains("fallback_requested"))
        assertTrue(events.contains("package_visibility_check"))
        assertTrue(events.contains("minecraft_package_missing"))
        assertTrue(events.contains("minecraft_activity_launch_failed"))
        assertFalse(events.contains("launch_intent_created"))
        assertEquals(0, gateway.launchIntentRequests)
        assertEquals(0, gateway.launchRequests)
    }

    @Test
    fun missingLaunchIntentReportsFailureAndDoesNotStartActivity() {
        val gateway = FakeGateway(launchIntent = null)
        val events = mutableListOf<String>()

        val result = coordinator(gateway, events).launch()

        assertEquals(ExternalLaunchOutcome.Failed(ExternalLaunchFailure.LAUNCH_INTENT_MISSING), result)
        assertTrue(events.contains("minecraft_package_found"))
        assertTrue(events.contains("launch_intent_missing"))
        assertTrue(events.contains("minecraft_activity_launch_failed"))
        assertEquals(0, gateway.launchRequests)
    }

    @Test
    fun launchRequestIsDispatchedAndRecordedSeparatelyFromGameplay() {
        val gateway = FakeGateway()
        val events = mutableListOf<String>()

        val result = coordinator(gateway, events).launch()

        assertEquals(ExternalLaunchOutcome.Dispatched, result)
        assertEquals(1, gateway.launchRequests)
        assertTrue(events.indexOf("minecraft_activity_launch_attempted") < events.indexOf("minecraft_activity_launch_dispatched"))
        assertFalse(events.any { it.contains("gameplay_loaded") })
    }

    @Test
    fun activityNotFoundIsReportedAsLaunchFailure() {
        val gateway = FakeGateway(startFailure = ExternalActivityNotFoundException("No activity"))
        val events = mutableListOf<String>()

        val result = coordinator(gateway, events).launch()

        assertEquals(ExternalLaunchOutcome.Failed(ExternalLaunchFailure.ACTIVITY_NOT_FOUND), result)
        assertTrue(events.contains("minecraft_activity_launch_attempted"))
        assertTrue(events.contains("minecraft_activity_launch_failed"))
        assertFalse(events.contains("minecraft_activity_launch_dispatched"))
    }

    @Test
    fun securityExceptionIsReportedAsLaunchFailure() {
        val gateway = FakeGateway(startFailure = SecurityException("Denied"))
        val events = mutableListOf<String>()

        val result = coordinator(gateway, events).launch()

        assertEquals(ExternalLaunchOutcome.Failed(ExternalLaunchFailure.SECURITY_DENIED), result)
        assertTrue(events.contains("minecraft_activity_launch_failed"))
        assertFalse(events.contains("minecraft_activity_launch_dispatched"))
    }

    private fun coordinator(gateway: FakeGateway, events: MutableList<String>) =
        ExternalMinecraftLaunchCoordinator(gateway) { event, _, _ -> events += event }

    private class FakeIntent : ExternalLaunchIntent

    private class FakeGateway(
        private val status: MinecraftPackageStatus = MinecraftPackageStatus(visible = true, enabled = true),
        private val launchIntent: ExternalLaunchIntent? = FakeIntent(),
        private val startFailure: Exception? = null
    ) : ExternalMinecraftLaunchGateway {
        var launchIntentRequests = 0
            private set
        var launchRequests = 0
            private set

        override fun inspectPackage(): MinecraftPackageStatus = status

        override fun createLaunchIntent(): ExternalLaunchIntent? {
            launchIntentRequests++
            return launchIntent
        }

        override fun startActivity(intent: ExternalLaunchIntent) {
            launchRequests++
            startFailure?.let { throw it }
        }
    }
}
