package io.moata.pleos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(Tokens.INK)
val Surface1 = Color(Tokens.SURFACE)
val SurfaceHi = Color(Tokens.SURFACE_HI)
val Paper = Color(Tokens.PAPER)
val Muted = Color(Tokens.MUTED)
val MoaYellow = Color(Tokens.MOA_YELLOW)
val RouteTeal = Color(Tokens.ROUTE_TEAL)
val TealText = Color(Tokens.TEAL_TEXT)
val CoralText = Color(Tokens.CORAL_TEXT)

/** Spacing scale (dp). */
object Space { val xs = 6.dp; val s = 12.dp; val m = 24.dp; val l = 36.dp; val xl = 56.dp; val touch = Tokens.MIN_TOUCH_DP.dp }

// 160 dpi in-car display: body 28 sp, nothing below 26 sp, glanced-at numbers are huge.
private val Type = Typography(
    displayLarge = TextStyle(fontSize = 120.sp, lineHeight = 128.sp, fontWeight = FontWeight.ExtraBold),
    headlineLarge = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 30.sp, lineHeight = 42.sp),
    bodyMedium = TextStyle(fontSize = 28.sp, lineHeight = 40.sp),
    bodySmall = TextStyle(fontSize = Tokens.MIN_TEXT_SP.sp, lineHeight = 36.sp),
    labelLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = Tokens.MIN_TEXT_SP.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
)

private val Scheme = darkColorScheme(
    primary = MoaYellow, onPrimary = Ink,
    secondary = RouteTeal, onSecondary = Ink,
    tertiary = CoralText, onTertiary = Ink,
    background = Ink, onBackground = Paper,
    surface = Surface1, onSurface = Paper,
    surfaceVariant = SurfaceHi, onSurfaceVariant = Muted,
    error = CoralText, onError = Ink,
    outline = Muted,
)

/** The car UI is always high-contrast dark. */
@Composable
fun MoataTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val system = isSystemInDarkTheme() // ignored; the car UI is always dark
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
