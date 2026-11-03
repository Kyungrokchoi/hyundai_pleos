package io.moata.pleos.core

import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import io.moata.pleos.FakeSdk
import io.moata.pleos.core.vehicle.PleosProbe
import io.moata.pleos.core.vehicle.ProbeEnv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import io.moata.pleos.core.vehicle.ProbeReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEnv : ProbeEnv {
    override fun environment() = mapOf("device.model" to "fake")
    override fun permissions() = mapOf("pleos.car.permission.CAR_DRIVE" to "GRANTED")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PleosProbeTest {
    @Test fun everyStepIsClassifiedAsExactlyOneOfCompleteFailedTimeoutThrew() = runTest {
        val sdk = FakeSdk()
        val cap = CapabilityConfig(AccessType.READ)
        listOf("checkCurrentDistanceDrivenCapabilities", "checkCurrentFuelEconomyCapabilities", "checkCumulativeFuelEconomyCapabilities",
            "checkCurrentEvEfficiencyCapabilities", "checkEvBatteryStateOfChargeCapabilities").forEach { sdk.complete(it, cap) }
        sdk.complete("getCurrentDistanceDriven", 12345.5f)
        sdk.complete("getTripCurrentDrivingDistance", null)                       // COMPLETE but null
        sdk.fail("getTripCumulativeDrivingDistance", UnsupportedOperationException("not supported"))   // FAILED
        sdk.throwing("getCurrentFuelEconomy", IllegalStateException("not bound")) // THREW
        // no behaviour for getCumulativeFuelEconomy etc., so TIMEOUT
        val report = PleosProbe(sdk, FakeEnv(), timeoutMs = 1_000, listenMs = 0, log = {}).run()
        val byId = report.steps.associateBy { it.id }
        assertEquals("COMPLETE", byId.getValue("get.odometer.current").outcome); assertEquals("12345.5", byId.getValue("get.odometer.current").value)
        assertEquals("km", byId.getValue("get.odometer.current").documentedUnit)
        assertTrue(byId.getValue("get.odometer.trip.current").valueIsNull); assertNull(byId.getValue("get.odometer.trip.current").value)
        assertEquals("FAILED", byId.getValue("get.odometer.trip.cumulative").outcome)
        assertTrue(byId.getValue("get.odometer.trip.cumulative").error!!.contains("not supported"))
        assertEquals("THREW", byId.getValue("get.fuel.current").outcome)
        assertEquals("TIMEOUT", byId.getValue("get.fuel.cumulative").outcome)
        assertEquals("L/100km", byId.getValue("get.fuel.current").documentedUnit)
        assertEquals("SDK_RETURNED_VALUES", report.verdict)
        assertEquals(1, sdk.initialized); assertEquals(1, sdk.released)
    }

    @Test fun releaseComesAfterEveryCallbackAndListenerIsUnregistered() = runTest {
        val sdk = FakeSdk()
        val report = PleosProbe(sdk, FakeEnv(), timeoutMs = 500, listenMs = 0, log = {}).run()
        val order = report.order
        val releaseAt = order.indexOfFirst { it.contains("release:call") }
        assertTrue(releaseAt > 0)
        assertFalse("a callback arrived after release()", order.drop(releaseAt + 1).any { it.contains(":callback") })
        assertTrue(order.indexOfFirst { it.contains("listener:register") } < order.indexOfFirst { it.contains("listener:unregister") })
        assertEquals(1, sdk.registered.size); assertEquals(sdk.registered, sdk.unregistered)
        assertTrue(report.verdict.startsWith("BLOCKED"))                          // all timed out: blocked, not success
    }

    @Test fun failedInitializeIsReportedAndStillReleasesSafely() = runTest {
        val sdk = FakeSdk().also { it.initThrows = SecurityException("bind denied") }
        val report = PleosProbe(sdk, FakeEnv(), timeoutMs = 500, listenMs = 0, log = {}).run()
        assertEquals("THREW", report.initialize!!.outcome); assertTrue(report.initialize!!.error!!.contains("bind denied"))
        assertTrue(report.steps.isEmpty()); assertTrue(report.verdict.startsWith("BLOCKED: initialize"))
    }

    @Test fun reportRoundTripsThroughJson() = runTest {
        val sdk = FakeSdk()
        val json = PleosProbe.encode(PleosProbe(sdk, FakeEnv(), timeoutMs = 100, listenMs = 0, log = {}).run())
        val back = PleosProbe.json.decodeFromString<ProbeReport>(json)
        assertEquals("fake", back.environment["device.model"]); assertTrue(json.contains("\"outcome\""))
    }
}
