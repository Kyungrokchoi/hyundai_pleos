package io.moata.pleos.domain

import io.moata.pleos.core.vehicle.DataSource
import io.moata.pleos.core.vehicle.Quality
import io.moata.pleos.core.vehicle.SignalType
import io.moata.pleos.core.vehicle.VehicleSample
import io.moata.pleos.data.remote.CostRuleOut
import io.moata.pleos.data.remote.CostRuleIn
import io.moata.pleos.data.remote.ApiJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EfficiencyRulesTest {
    private fun rule(km: String?, source: String, vehicle: String = "ICE") =
        CostRuleOut(vehicleType = vehicle, efficiencyKmPerUnit = km, efficiencySource = source, driverIncluded = true, energyPolicy = "SEGMENT_SHARED")

    private fun sample(type: SignalType, value: Double?, quality: Quality = Quality.VERIFIED) =
        VehicleSample(type, value, type.unit, 0L, DataSource.PLEOS, quality, "2.3.2", if (value == null) "TIMEOUT" else null)

    @Test fun fuelTypeIsElectricIfAndOnlyIfTheCarIsAnEv() {
        assertEquals("ELECTRIC", EfficiencyRules.fuelType("EV", "GASOLINE"))
        assertEquals("DIESEL", EfficiencyRules.fuelType("ICE", "DIESEL"))
    }

    @Test fun theRuleSentToTheServerNeverCarriesAnEconomyNumber() {
        val json = ApiJson.encodeToString(CostRuleIn(vehicleType = "EV", fuelType = EfficiencyRules.fuelType("EV", "GASOLINE"), unitPriceKrw = "300"))
        assertTrue(json.contains("\"efficiency_source\":\"ESTIMATE\"") && json.contains("\"fuel_type\":\"ELECTRIC\""))
        assertFalse(json.contains("efficiency_value") || json.contains("efficiency_unit"))
    }

    @Test fun theMatchingSignalIsReadAtTheEnd() {
        assertEquals(SignalType.FUEL_ECONOMY, EfficiencyRules.signalFor("ICE"))
        assertEquals(SignalType.EV_EFFICIENCY, EfficiencyRules.signalFor("EV"))
    }

    @Test fun onlyAUsableReadingIsHandedToTheServer() {
        assertTrue(EfficiencyRules.shouldUpload(sample(SignalType.FUEL_ECONOMY, 6.2)))
        assertTrue(EfficiencyRules.shouldUpload(sample(SignalType.FUEL_ECONOMY, 6.2, Quality.ESTIMATED)))
        assertFalse(EfficiencyRules.shouldUpload(sample(SignalType.FUEL_ECONOMY, null, Quality.UNAVAILABLE)))
        assertFalse(EfficiencyRules.shouldUpload(sample(SignalType.FUEL_ECONOMY, -1.0, Quality.INVALID)))
    }

    @Test fun displayLineNamesTheSourceOfTheFigure() {
        assertEquals("연비 10.4 km/L · 표시연비 평균 추정", EfficiencyRules.displayLine(rule("10.4", "ESTIMATE")))
        assertEquals("연비 12.5 km/L · 차량 제공", EfficiencyRules.displayLine(rule("12.5", "PLEOS")))
        assertEquals("연비 12 km/L · 가상 데이터", EfficiencyRules.displayLine(rule("12", "DEMO")))
        assertEquals("전비 4.3 km/kWh · 표시연비 평균 추정", EfficiencyRules.displayLine(rule("4.3", "ESTIMATE", "EV")))
    }

    @Test fun figuresAreRoundedToOneDecimalWithoutTrailingZeros() {
        assertEquals("12", EfficiencyRules.figure("12.000"))
        assertEquals("12.5", EfficiencyRules.figure("12.500"))
        assertEquals("10.4", EfficiencyRules.figure("10.4375"))
        assertNull(EfficiencyRules.figure("abc")); assertNull(EfficiencyRules.figure(null))
    }

    @Test fun theLineIsHiddenWhileTheServerHasNoFigure() {
        assertNull(EfficiencyRules.displayLine(null))
        assertNull(EfficiencyRules.displayLine(rule(null, "ESTIMATE")))
        assertNull(EfficiencyRules.valueText(rule(null, "ESTIMATE")))
    }

    @Test fun anUnknownSourceStillShowsTheFigureWithoutAWrongLabel() {
        assertEquals("연비 9 km/L", EfficiencyRules.displayLine(rule("9", "SOMETHING_NEW")))
    }
}
