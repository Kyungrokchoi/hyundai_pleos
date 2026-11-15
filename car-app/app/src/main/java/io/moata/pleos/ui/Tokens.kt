package io.moata.pleos.ui

import kotlin.math.pow





object Tokens {
    const val INK = 0xFF14213DL
    const val SURFACE = 0xFF1D2D50L
    const val SURFACE_HI = 0xFF26396AL
    const val PAPER = 0xFFFBF8F1L
    const val MUTED = 0xFFC3CCE0L
    const val MOA_YELLOW = 0xFFFFC83DL
    const val ROUTE_TEAL = 0xFF12A594L
    const val TEAL_TEXT = 0xFF3CD3C0L
    const val ALERT_CORAL = 0xFFE5484DL
    const val CORAL_TEXT = 0xFFFF8F93L
    const val OK_GREEN = 0xFF6FCF97L


    const val MIN_TEXT_SP = 26
    const val MIN_TOUCH_DP = 88


    fun contrast(a: Long, b: Long): Double {
        fun lum(c: Long): Double {
            fun ch(shift: Int): Double {
                val v = ((c shr shift) and 0xFF) / 255.0
                return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }
        val (hi, lo) = lum(a).let { la -> lum(b).let { lb -> if (la >= lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }
}
