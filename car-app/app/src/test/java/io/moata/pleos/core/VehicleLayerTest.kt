package io.moata.pleos.core

import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import io.moata.pleos.FakeSdk
import io.moata.pleos.core.vehicle.DataSource
import io.moata.pleos.core.vehicle.DemoFixture
import io.moata.pleos.core.vehicle.DemoVehicleDataSource
import io.moata.pleos.core.vehicle.ManualVehicleDataSource
import io.moata.pleos.core.vehicle.PleosVehicleDataSource
import io.moata.pleos.core.vehicle.Quality
import io.moata.pleos.core.vehicle.SampleRules
import io.moata.pleos.core.vehicle.SignalType
import io.moata.pleos.core.vehicle.VehicleSample
import io.moata.pleos.domain.TripRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PleosVehicleDataSourceTest {
    private fun source(sdk: FakeSdk = FakeSdk()) = PleosVehicleDataSource(sdk, now = { 1_000L }, timeoutMs = 3_000) to sdk

    private fun FakeSdk.readable() {
        val cap = CapabilityConfig(AccessType.READ)
        complete("checkCurrentDistanceDrivenCapabilities", cap)
        complete("checkCurrentFuelEconomyCapabilities", cap)
        complete("checkCurrentEvEfficiencyCapabilities", cap)
        complete("checkEvBatteryStateOfChargeCapabilities", cap)
    }

    @Test fun odometerKeepsKmAndShortestDecimal() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        sdk.complete("getCurrentDistanceDriven", 12345.68f)
        ds.connect().getOrThrow()
        val s = ds.read(SignalType.ODOMETER)
        assertEquals(12345.68, s.value!!, 0.0)               // not 12345.6796875
        assertEquals("km", s.unit); assertEquals(DataSource.PLEOS, s.source); assertEquals(Quality.VERIFIED, s.quality)
        assertEquals("2.3.2", s.sdkVersion); assertNull(s.error)
    }

    @Test fun fuelEconomyStaysInLPer100kmAsReported() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        sdk.complete("getCurrentFuelEconomy", 6.2f)
        ds.connect()
        val s = ds.read(SignalType.FUEL_ECONOMY)
        assertEquals(6.2, s.value!!, 0.0)                    // raw L/100km, not converted on the device
        assertEquals("L/100km", s.unit)
        assertEquals("L/100km", TripRepository.toReading(s, "e1").unit)
    }

    @Test fun evEfficiencyAndSoc() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        sdk.complete("getCurrentEvEfficiency", 5.5f); sdk.complete("getEvBatteryStateOfCharge", 80)
        ds.connect()
        assertEquals("km/kWh", ds.read(SignalType.EV_EFFICIENCY).unit)
        val soc = ds.read(SignalType.EV_SOC)
        assertEquals(80.0, soc.value!!, 0.0); assertEquals("%", soc.unit)
    }

    @Test fun capabilityNoneIsUnavailableAndTheGetterIsNeverCalled() = runTest {
        val (ds, sdk) = source()
        sdk.complete("checkCurrentFuelEconomyCapabilities", CapabilityConfig(AccessType.NONE))
        sdk.complete("getCurrentFuelEconomy", 7.0f)
        ds.connect()
        val s = ds.read(SignalType.FUEL_ECONOMY)
        assertNull(s.value); assertEquals(Quality.UNAVAILABLE, s.quality); assertTrue(s.error!!.contains("CAPABILITY_NONE"))
        assertFalse("getCurrentFuelEconomy" in sdk.calls)
        assertEquals(DataSource.PLEOS, s.source)             // still PLEOS, never swapped for DEMO
    }

    @Test fun onFailedKeepsTheExactSdkErrorText() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        sdk.fail("getCurrentDistanceDriven", SecurityException("ERROR_SECURITY_PERMISSION: missing CAR_DRIVE"))
        ds.connect()
        val s = ds.read(SignalType.ODOMETER)
        assertNull(s.value); assertEquals(Quality.UNAVAILABLE, s.quality)
        assertTrue(s.error!!.contains("onFailed") && s.error!!.contains("ERROR_SECURITY_PERMISSION: missing CAR_DRIVE"))
    }

    @Test fun nullIsUnavailableNotZero() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        sdk.complete("getCurrentDistanceDriven", null)
        ds.connect()
        val s = ds.read(SignalType.ODOMETER)
        assertNull(s.value); assertEquals(Quality.UNAVAILABLE, s.quality); assertTrue(s.error!!.startsWith("NULL_VALUE"))
    }

    @Test fun invalidNumbersAreKeptButFlagged() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        ds.connect()
        for ((method, type, v) in listOf(
            Triple("getCurrentFuelEconomy", SignalType.FUEL_ECONOMY, 0f),
            Triple("getCurrentEvEfficiency", SignalType.EV_EFFICIENCY, -1f),
            Triple("getCurrentDistanceDriven", SignalType.ODOMETER, -5f),
            Triple("getCurrentDistanceDriven", SignalType.ODOMETER, Float.NaN),
        )) {
            sdk.complete(method, v)
            val s = ds.read(type)
            assertEquals("$method=$v", Quality.INVALID, s.quality)
            assertFalse(s.usable)
        }
        sdk.complete("getEvBatteryStateOfCharge", 140)
        assertEquals(Quality.INVALID, ds.read(SignalType.EV_SOC).quality)
    }

    @Test fun noCallbackIsATimeoutDistinctFromOnFailed() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        ds.connect()                                          // getCurrentDistanceDriven never calls back
        val s = ds.read(SignalType.ODOMETER)
        assertNull(s.value); assertTrue(s.error!!.startsWith("TIMEOUT")); assertFalse(s.error!!.startsWith("FAILED"))
    }

    @Test fun sdkThrowingIsReportedAsThrew() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        sdk.throwing("getCurrentDistanceDriven", IllegalStateException("service not bound"))
        ds.connect()
        val s = ds.read(SignalType.ODOMETER)
        assertTrue(s.error!!.startsWith("THREW") && s.error!!.contains("service not bound"))
    }

    @Test fun capabilityCheckFailingStillTriesTheRead() = runTest {
        val (ds, sdk) = source()
        sdk.fail("checkCurrentDistanceDrivenCapabilities", UnsupportedOperationException("no capability api"))
        sdk.complete("getCurrentDistanceDriven", 100.5f)
        ds.connect()
        val s = ds.read(SignalType.ODOMETER)
        assertEquals(100.5, s.value!!, 0.0); assertEquals(Quality.VERIFIED, s.quality)
    }

    @Test fun failedInitializeNeverFallsBackToDemo() = runTest {
        val sdk = FakeSdk().also { it.initThrows = IllegalStateException("bind failed") }
        val ds = PleosVehicleDataSource(sdk, now = { 1L })
        assertTrue(ds.connect().isFailure)
        val s = ds.read(SignalType.ODOMETER)
        assertNull(s.value); assertEquals(DataSource.PLEOS, s.source); assertTrue(s.error!!.startsWith("NOT_CONNECTED"))
        assertEquals(0, sdk.calls.size)
    }

    @Test fun releaseHappensOnlyOnDisconnect() = runTest {
        val (ds, sdk) = source().also { it.second.readable() }
        sdk.complete("getCurrentDistanceDriven", 1f)
        ds.connect(); ds.read(SignalType.ODOMETER)
        assertEquals(0, sdk.released)
        ds.disconnect(); ds.disconnect()
        assertEquals(1, sdk.released)
    }

    @Test fun listenerIsRegisteredOnceAndUnregisteredWhenCollectionStops() = runTest {
        val (ds, sdk) = source()
        ds.connect()
        val got = mutableListOf<VehicleSample>()
        val job = launch { ds.observeOdometer().collect { got += it } }
        advanceUntilIdle()
        assertEquals(1, sdk.registered.size)
        val l = sdk.registered[0] as DistanceDrivenListener
        l.onDistanceDrivenUpdated(10.5f); l.onDistanceDrivenUpdated(null); l.onFailed(RuntimeException("link lost"))
        advanceUntilIdle()
        assertEquals(listOf(Quality.VERIFIED, Quality.UNAVAILABLE, Quality.UNAVAILABLE), got.map { it.quality })
        assertTrue(got[2].error!!.contains("link lost"))
        job.cancel(); advanceUntilIdle()
        assertEquals(listOf(l), sdk.unregistered)
    }
}

class DemoAndManualSourceTest {
    @Test fun demoIsDeterministicAndAlwaysLabelledDemo() = runTest {
        suspend fun run() = DemoVehicleDataSource(DemoFixture(), now = { 5L }, tickMs = 0).observeOdometer().toList().map { it.value }
        val a = run(); val b = run()
        assertEquals(a, b)
        assertEquals(12_345.0, a.first()!!, 0.0); assertEquals(12_369.0, a.last()!!, 1e-9)   // exactly 24 km
        val d = DemoVehicleDataSource(now = { 5L })
        for (t in SignalType.values()) assertEquals(DataSource.DEMO, d.read(t).source)
        assertTrue(DemoVehicleDataSource().read(SignalType.ODOMETER).quality != Quality.VERIFIED)  // simulated, so never VERIFIED
        assertEquals("km/L", d.read(SignalType.FUEL_ECONOMY).unit)
    }

    @Test fun manualIsLabelledManualAndNeverVerified() = runTest {
        val m = ManualVehicleDataSource(now = { 1L })
        assertTrue(m.read(SignalType.ODOMETER).error!!.startsWith("NOT_ENTERED"))
        m.setOdometer(500.0); m.setEconomy(6.5, "L/100km")
        val o = m.read(SignalType.ODOMETER); val f = m.read(SignalType.FUEL_ECONOMY)
        assertEquals(DataSource.MANUAL, o.source); assertEquals(Quality.ESTIMATED, o.quality)
        assertEquals("L/100km", f.unit); assertEquals(6.5, f.value!!, 0.0)
        m.setOdometer(-1.0)
        assertEquals(Quality.INVALID, m.read(SignalType.ODOMETER).quality)
    }
}

class SampleRulesTest {
    @Test fun floatsBecomeShortestDecimals() {
        assertEquals(0.1, SampleRules.floatToDouble(0.1f), 0.0)
        assertEquals(12345.68, SampleRules.floatToDouble(12345.68f), 0.0)
    }
    @Test fun signalUnitsMatchTheDocumentedSdkUnits() {
        assertEquals("km", SignalType.ODOMETER.unit); assertEquals("L/100km", SignalType.FUEL_ECONOMY.unit)
        assertEquals("km/kWh", SignalType.EV_EFFICIENCY.unit); assertEquals("%", SignalType.EV_SOC.unit)
    }
}
