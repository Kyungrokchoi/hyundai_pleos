package io.moata.pleos.domain

import io.moata.pleos.ui.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class ThemeContrastTest {
    private fun ok(name: String, fg: Long, bg: Long, min: Double = 4.5) {
        val r = Tokens.contrast(fg, bg)
        assertTrue("$name = %.2f:1 < $min".format(r), r >= min)
    }

    @Test fun textPairsReachAA() {
        ok("paper/ink", Tokens.PAPER, Tokens.INK)
        ok("paper/surface", Tokens.PAPER, Tokens.SURFACE)
        ok("paper/surfaceHi", Tokens.PAPER, Tokens.SURFACE_HI)
        ok("muted/ink", Tokens.MUTED, Tokens.INK)
        ok("muted/surface", Tokens.MUTED, Tokens.SURFACE)
        ok("yellow/ink", Tokens.MOA_YELLOW, Tokens.INK)
        ok("yellow/surface", Tokens.MOA_YELLOW, Tokens.SURFACE)
        ok("tealText/ink", Tokens.TEAL_TEXT, Tokens.INK)
        ok("tealText/surface", Tokens.TEAL_TEXT, Tokens.SURFACE)
        ok("coralText/ink", Tokens.CORAL_TEXT, Tokens.INK)
        ok("coralText/surface", Tokens.CORAL_TEXT, Tokens.SURFACE)
    }

    @Test fun inkTextOnFillsReachAA() {
        ok("ink on yellow button", Tokens.INK, Tokens.MOA_YELLOW)
        ok("ink on teal pill", Tokens.INK, Tokens.ROUTE_TEAL)
        ok("ink on coralText fill (danger/alert)", Tokens.INK, Tokens.CORAL_TEXT)
        ok("ink on tealText fill (message bar)", Tokens.INK, Tokens.TEAL_TEXT)
        ok("ink on muted pill", Tokens.INK, Tokens.MUTED)
    }

    @Test fun messageBarButtonsUseInkOnTheColouredBar() {

        ok("banner button ink on coral", Tokens.INK, Tokens.CORAL_TEXT)
        ok("banner button ink on teal", Tokens.INK, Tokens.TEAL_TEXT)
        assertTrue(Tokens.contrast(Tokens.PAPER, Tokens.CORAL_TEXT) < 3.0)
    }

    @Test fun rideLaneSpansAreVisibleNonText() {

        ok("lane span tealText on surfaceHi track", Tokens.TEAL_TEXT, Tokens.SURFACE_HI, min = 3.0)
    }

    @Test fun earnedBadgeGreenIsReadableAndVisible() {

        ok("green on surface card", Tokens.OK_GREEN, Tokens.SURFACE, min = 3.0)
        ok("green on surfaceHi", Tokens.OK_GREEN, Tokens.SURFACE_HI, min = 3.0)
        ok("green as text on surface", Tokens.OK_GREEN, Tokens.SURFACE)
        ok("ink on green fill", Tokens.INK, Tokens.OK_GREEN)
        ok("muted empty-box frame on surface", Tokens.MUTED, Tokens.SURFACE, min = 3.0)
    }

    @Test fun xpGaugeYellowStandsOutFromItsTrack() {
        ok("gauge fill yellow on track", Tokens.MOA_YELLOW, Tokens.SURFACE_HI, min = 3.0)
    }

    @Test fun circuitGlyphRoadIsVisibleOnTheRowCard() {
        ok("light road on row card", Tokens.PAPER, Tokens.SURFACE_HI, min = 3.0)
        ok("dark road edge against the light road", Tokens.INK, Tokens.PAPER, min = 3.0)
    }

    @Test fun documentsWhyBrandCoralIsNotUsedForText() {

        assertTrue(Tokens.contrast(Tokens.ALERT_CORAL, Tokens.INK) < 4.5)
    }

    @Test fun inCarSizeFloors() {
        assertEquals(26, Tokens.MIN_TEXT_SP); assertEquals(88, Tokens.MIN_TOUCH_DP)
    }
}
