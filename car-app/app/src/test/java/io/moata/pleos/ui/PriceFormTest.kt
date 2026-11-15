package io.moata.pleos.ui

import io.moata.pleos.data.remote.FuelPriceOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceFormTest {
    private val avg = FuelPriceOut(fuel = "GASOLINE", region = "전국", priceKrw = 1855, fetchedAt = "2026-11-12T06:00:00Z")

    @Test fun anEmptyLiquidFuelFormWantsAnAutoPriceButAnEvDoesNot() {
        assertTrue(TripForm().wantsAutoPrice())
        assertFalse(TripForm(vehicleType = "EV").wantsAutoPrice())
    }

    @Test fun autoPriceIsMarkedOpinetAndRefreshedNextTime() {
        val f = TripForm().withAutoPrice(avg)
        assertEquals("1855", f.unitPrice); assertEquals(PriceSource.OPINET, f.unitPriceSource)
        assertTrue("an auto price is a daily average, so it is looked up again", f.wantsAutoPrice())
    }

    @Test fun typingTakesOverAndIsNeverReplaced() {
        val typed = TripForm().withAutoPrice(avg).withTypedPrice("1900")
        assertEquals("1900", typed.unitPrice); assertEquals(PriceSource.MANUAL, typed.unitPriceSource)
        assertFalse(typed.wantsAutoPrice())
        assertEquals(PriceSource.MANUAL, TripForm().withTypedPrice("").unitPriceSource)
    }

    @Test fun aFailedFetchDowngradesARememberedAutoPriceAndLeavesOthersAlone() {
        val remembered = TripForm().withAutoPrice(avg).afterFailedFetch()
        assertEquals("1855", remembered.unitPrice); assertEquals(PriceSource.MANUAL, remembered.unitPriceSource)
        assertEquals(TripForm(), TripForm().afterFailedFetch())
        val typed = TripForm().withTypedPrice("1900")
        assertEquals(typed, typed.afterFailedFetch())
    }

    @Test fun labelNamesTheRegionAndThatItWasFilledAutomatically() {
        assertEquals("오피넷 전국 평균 · 자동 입력", fuelPriceLabel(avg))
        assertEquals("오피넷 서울 평균 · 자동 입력", fuelPriceLabel(avg.copy(region = "서울")))
    }

    @Test fun opinetSourceHasAReadableLabel() = assertTrue(sourceKo("OPINET").startsWith("OPINET"))
}
