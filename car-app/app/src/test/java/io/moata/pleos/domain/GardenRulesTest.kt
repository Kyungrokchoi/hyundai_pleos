package io.moata.pleos.domain

import io.moata.pleos.data.remote.BadgeOut
import io.moata.pleos.data.remote.BadgeStatusOut
import io.moata.pleos.data.remote.CharacterOut
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GardenRulesTest {
    @Test fun xpFractionFollowsTheLevelSpan() {
        assertEquals(0.5f, GardenRules.xpFraction(40, 30, 50), 0.0001f)
        assertEquals(0f, GardenRules.xpFraction(30, 30, 70), 0f)
        assertEquals(10f / 30f, GardenRules.xpFraction(10, 0, 30), 0.0001f)
    }

    @Test fun xpFractionIsClampedAndSafe() {
        assertEquals(1f, GardenRules.xpFraction(500, 30, 70), 0f)
        assertEquals(0f, GardenRules.xpFraction(5, 30, 70), 0f)
        assertEquals(1f, GardenRules.xpFraction(310, 310, null), 0f)
        assertEquals(0f, GardenRules.xpFraction(40, 40, 40), 0f)
    }

    @Test fun onlyTheFirstThreeRowsShowUntilExpanded() {
        val rows = (1..7).toList()
        assertEquals(listOf(1, 2, 3), GardenRules.visibleRows(rows, expanded = false))
        assertEquals(rows, GardenRules.visibleRows(rows, expanded = true))
        assertEquals(4, GardenRules.hiddenCount(rows.size))
        assertEquals(listOf(1, 2), GardenRules.visibleRows(listOf(1, 2), expanded = false))
        assertEquals(0, GardenRules.hiddenCount(3)); assertEquals(0, GardenRules.hiddenCount(0))
        assertEquals(1, GardenRules.hiddenCount(4))
    }

    private fun character(badges: List<BadgeOut> = emptyList(), all: List<BadgeStatusOut> = emptyList()) =
        CharacterOut(groupId = "g", xp = 0, level = 1, levelXpStart = 0, nextLevelXp = 30, skinId = "default", mood = "SLEEPY",
            badges = badges, allBadges = all, completedTrips = 0)

    @Test fun badgesListTheWholeCatalogueWithTheEarnedFlag() {
        val rows = GardenRules.badgeRows(character(all = listOf(
            BadgeStatusOut("A", "첫 동행", "처음", earned = true, earnedAt = "2026-11-02T03:00:00Z"),
            BadgeStatusOut("B", "셋이 함께", "셋", earned = false),
        )))
        assertEquals(listOf("첫 동행", "셋이 함께"), rows.map { it.name })
        assertEquals(listOf(true, false), rows.map { it.earned })
    }

    @Test fun anOlderServerWithOnlyEarnedBadgesStillWorks() {
        val rows = GardenRules.badgeRows(character(badges = listOf(BadgeOut("A", "첫 동행", "처음", "2026-11-02T03:00:00Z"))))
        assertEquals(1, rows.size); assertEquals(true, rows.single().earned)
        assertEquals(emptyList<GardenRules.BadgeRow>(), GardenRules.badgeRows(character()))
    }

    @Test fun dateIsShownInTheDevicesTimeZone() {
        val seoul = ZoneId.of("Asia/Seoul")
        assertEquals("2026.11.12", GardenRules.dateLabel("2026-11-12T03:00:00Z", seoul))
        assertEquals("2026.11.13", GardenRules.dateLabel("2026-11-12T15:30:00Z", seoul))
        assertEquals("2026.11.12", GardenRules.dateLabel("2026-11-12T15:30:00Z", ZoneId.of("UTC")))
        assertEquals("-", GardenRules.dateLabel(null, seoul)); assertEquals("-", GardenRules.dateLabel("  ", seoul))
        assertEquals("2026.11.12", GardenRules.dateLabel("2026-11-12 garbage", seoul))
    }

    @Test fun titleSuffixAppearsOnlyWithAGroup() {
        assertEquals(" with Lunch", HeaderRules.groupSuffix("Lunch"))
        assertEquals(" with 점심 카풀", HeaderRules.groupSuffix("  점심 카풀 "))
        assertNull(HeaderRules.groupSuffix(null)); assertNull(HeaderRules.groupSuffix("")); assertNull(HeaderRules.groupSuffix("   "))
        assertFalse(HeaderRules.groupSuffix("Lunch")!!.startsWith("모아타"))
    }

    @Test fun titleTapClosesAConfirmedTripAndLeavesEveryOtherTripOpen() {
        assertEquals(HeaderRules.HomeAction.FINISH_TRIP, HeaderRules.homeAction(hasTrip = true, summaryState = "CONFIRMED"))
        for (state in listOf("INVITING", "READY", "IN_PROGRESS", "ENDED", "REVIEW", "DISPUTED", "CANCELLED", null)) {
            assertEquals("state $state", HeaderRules.HomeAction.GO_HOME, HeaderRules.homeAction(hasTrip = true, summaryState = state))
        }
        assertEquals(HeaderRules.HomeAction.GO_HOME, HeaderRules.homeAction(hasTrip = false, summaryState = "CONFIRMED"))
    }
}
