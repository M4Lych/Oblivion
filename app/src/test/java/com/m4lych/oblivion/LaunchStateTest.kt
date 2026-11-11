package com.m4lych.oblivion

import com.m4lych.oblivion.game.LaunchPhase
import com.m4lych.oblivion.game.restoreLaunchPhaseAfterProcessDeath
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchStateTest {
    @Test fun transientHostCheckBecomesInterruptedAfterProcessDeath() {
        assertEquals(LaunchPhase.INTERRUPTED, restoreLaunchPhaseAfterProcessDeath(LaunchPhase.PREPARING_IN_CLIENT))
    }

    @Test fun fallbackHandoffIsNotRestoredAsRunningSession() {
        assertEquals(LaunchPhase.INTERRUPTED, restoreLaunchPhaseAfterProcessDeath(LaunchPhase.EXTERNAL_FALLBACK_STARTING))
    }

    @Test fun terminalUnavailableStateRemainsVisibleAfterRecreation() {
        assertEquals(LaunchPhase.IN_CLIENT_UNAVAILABLE, restoreLaunchPhaseAfterProcessDeath(LaunchPhase.IN_CLIENT_UNAVAILABLE))
    }
}
