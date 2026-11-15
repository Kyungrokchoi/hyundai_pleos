package io.moata.pleos.ui

import io.moata.pleos.data.remote.IntervalOut
import io.moata.pleos.data.remote.ParticipantOut
import io.moata.pleos.data.remote.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RideLanesTest {
    private fun iv(on: String?, off: String?) = IntervalOut(id = "i", userId = "u", odometerOnKm = on, odometerOffKm = off, boundaryQuality = "VERIFIED")
    private fun p(name: String, driver: Boolean = false, vararg ivs: IntervalOut) =
        ParticipantOut(userId = name, nickname = name, isDriver = driver, memberStatus = "ACTIVE", onBoard = false, intervals = ivs.toList())
    private fun trip(vararg ps: ParticipantOut, start: String? = "1000", dist: String? = "24") = TripSummary(
        id = "t", groupId = "g", groupName = "n", state = "ENDED", dataMode = "PLEOS", vehicleAlias = "car", driverUserId = "A",
        revision = 1, odometerStartKm = start, distanceKm = dist, participants = ps.toList(),
    )

    @Test fun driverRidesEverythingPassengersTheirSpans() {
        val (total, lanes) = lanesFrom(trip(p("B", false, iv("1000", "1016")), p("A", true), p("C", false, iv("1000", "1024"))))!!
        assertEquals(24.0, total, 0.0)
        assertEquals(listOf("A", "B", "C"), lanes.map { it.name })
        assertEquals(listOf(0.0 to 24.0), lanes[0].spans)
        assertEquals(16.0, lanes[1].km, 1e-9)
        assertEquals(16.0, lanes[1].exitBefore(total)!!, 1e-9)
        assertNull(lanes[2].exitBefore(total))
    }

    @Test fun reboardingGivesTwoSpansAndTheSum() {
        val (_, lanes) = lanesFrom(trip(p("A", true), p("B", false, iv("1000", "1005"), iv("1010", "1024"))))!!
        assertEquals(listOf(0.0 to 5.0, 10.0 to 24.0), lanes[1].spans)
        assertEquals(19.0, lanes[1].km, 1e-9)
    }

    @Test fun missingBoundaryIsFlaggedNotGuessed() {
        val (_, lanes) = lanesFrom(trip(p("A", true), p("B", false, iv("1000", null))))!!
        assertFalse(lanes[1].complete)
        assertEquals(0.0, lanes[1].km, 0.0)
    }

    @Test fun noLanesBeforeBothEndsAreKnown() {
        assertNull(lanesFrom(trip(p("A", true), start = null)))
        assertNull(lanesFrom(trip(p("A", true), dist = null)))
    }

    @Test fun peopleWhoNeverBoardedAreNotDrawn() {
        val (_, lanes) = lanesFrom(trip(p("A", true), p("B")))!!
        assertEquals(listOf("A"), lanes.map { it.name })
    }

    @Test fun driverRecordedBoardingIsCarriedToTheLane() {
        val byDriver = IntervalOut(id = "i", userId = "u", odometerOnKm = "1000", odometerOffKm = "1024", boundaryQuality = "VERIFIED", openedBy = "DRIVER")
        val (_, lanes) = lanesFrom(trip(p("A", true), p("B", false, byDriver), p("C", false, iv("1000", "1024"))))!!
        assertEquals(listOf(false, true, false), lanes.map { it.byDriver })
    }

    @Test fun kmLabelDropsTrailingZeros() {
        assertEquals("24", kmLabel("24.000")); assertEquals("16.5", kmLabel("16.500")); assertEquals("?", kmLabel(null))
    }
}


class ImplausibleOdometerTest {
    @Test fun zeroIsNeverAStartOrEnd() {
        org.junit.Assert.assertTrue(io.moata.pleos.core.vehicle.SampleRules.implausibleOdometer(0.0, null))
        org.junit.Assert.assertTrue(io.moata.pleos.core.vehicle.SampleRules.implausibleOdometer(0.0, 50224.0))
    }
    @Test fun goingBackwardsBelowWhatThisCarReportedIsRejected() {
        org.junit.Assert.assertTrue(io.moata.pleos.core.vehicle.SampleRules.implausibleOdometer(50100.0, 50224.0))
        assertFalse(io.moata.pleos.core.vehicle.SampleRules.implausibleOdometer(50224.2, 50224.5))
        assertFalse(io.moata.pleos.core.vehicle.SampleRules.implausibleOdometer(50230.0, 50224.0))
        assertFalse(io.moata.pleos.core.vehicle.SampleRules.implausibleOdometer(12.0, null))
    }
}
