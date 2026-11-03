package io.moata.pleos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UxPolicyTest {
    @Test fun stateMappingFailsSafe() {
        assertEquals(DrivingState.UNKNOWN, UxPolicy.stateFrom(null))       // no answer from the car => not parked
        assertEquals(DrivingState.DRIVING, UxPolicy.stateFrom(true))
        assertEquals(DrivingState.PARKED, UxPolicy.stateFrom(false))
    }

    @Test fun truthTableEveryFeatureIsOnlyAllowedWhenParked() {
        for (f in Feature.values()) {
            assertTrue("$f parked", UxPolicy.isAllowed(f, DrivingState.PARKED))
            assertFalse("$f driving", UxPolicy.isAllowed(f, DrivingState.DRIVING))
            assertFalse("$f unknown must be restricted", UxPolicy.isAllowed(f, DrivingState.UNKNOWN))
        }
    }

    @Test fun theRestrictedFeaturesCoverTheSpec() {
        val gated = Feature.values().toSet()
        assertTrue(gated.containsAll(listOf(Feature.QR_INVITE, Feature.COST_EDIT, Feature.MOA_ANIMATION, Feature.REWARDS, Feature.SETTINGS)))
        assertTrue(UxPolicy.isRestricted(DrivingState.UNKNOWN))
        assertFalse(UxPolicy.isRestricted(DrivingState.PARKED))
    }
}
