package io.moata.pleos.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class MoaMood { HAPPY, SLEEPY, PROUD, WORRIED }


@Composable
fun Moa(
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    mood: MoaMood = MoaMood.HAPPY,
    animate: Boolean = false,
    description: String? = null,
) {
    val bob = if (animate) {
        val t = rememberInfiniteTransition(label = "moa-bob")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "bob")
        v
    } else 0f
    Box(modifier.size(size).aspectRatio(1f).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics { })) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val lift = -bob * w * 0.02f

            for (cx in listOf(0.30f, 0.70f)) {
                val c = Offset(w * cx, w * 0.90f)
                drawCircle(Muted, w * 0.085f, c)
                drawCircle(SurfaceHi, w * 0.032f, c)
            }

            drawRoundRect(MoaYellow, Offset(w * 0.08f, w * 0.18f + lift), Size(w * 0.84f, w * 0.68f), CornerRadius(w * 0.34f))
            drawRoundRect(Color(0x33FFFFFF), Offset(w * 0.16f, w * 0.24f + lift), Size(w * 0.36f, w * 0.14f), CornerRadius(w * 0.07f))

            val pin = Path().apply {
                moveTo(w * 0.50f, w * 0.20f + lift)
                cubicTo(w * 0.40f, w * 0.12f + lift, w * 0.42f, w * 0.02f + lift, w * 0.50f, w * 0.02f + lift)
                cubicTo(w * 0.58f, w * 0.02f + lift, w * 0.60f, w * 0.12f + lift, w * 0.50f, w * 0.20f + lift)
            }
            drawPath(pin, RouteTeal)

            val eyeY = w * 0.46f + lift
            val eyes = listOf(Offset(w * 0.36f, eyeY), Offset(w * 0.64f, eyeY))
            val line = Stroke(w * 0.028f, cap = StrokeCap.Round)
            when (mood) {
                MoaMood.SLEEPY -> eyes.forEach { drawLine(Ink, it + Offset(-w * 0.04f, 0f), it + Offset(w * 0.04f, 0f), w * 0.028f, StrokeCap.Round) }
                else -> eyes.forEach { drawCircle(Ink, w * 0.04f, it); drawCircle(Paper, w * 0.012f, it + Offset(w * 0.012f, -w * 0.012f)) }
            }

            val my = w * 0.60f + lift
            val mouth = Path().apply {
                when (mood) {
                    MoaMood.HAPPY, MoaMood.PROUD -> { moveTo(w * 0.42f, my); quadraticTo(w * 0.50f, my + w * 0.08f, w * 0.58f, my) }
                    MoaMood.SLEEPY -> { moveTo(w * 0.45f, my + w * 0.02f); lineTo(w * 0.55f, my + w * 0.02f) }
                    MoaMood.WORRIED -> { moveTo(w * 0.42f, my + w * 0.05f); quadraticTo(w * 0.50f, my - w * 0.03f, w * 0.58f, my + w * 0.05f) }
                }
            }
            drawPath(mouth, Ink, style = line)
            if (mood == MoaMood.PROUD) eyes.forEach { drawCircle(Color(0x66E5484D), w * 0.035f, it + Offset(if (it.x < w / 2) -w * 0.07f else w * 0.07f, w * 0.07f)) }
        }
    }
}
