package io.moata.pleos.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp


object MoaIcons {
    val Back = line("back") { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
    val ChevronDown = line("chevron-down") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val ChevronUp = line("chevron-up") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val Check = line("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val Backspace = line("backspace") {
        moveTo(9f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(9f, 19f); lineTo(3f, 12f); close()
        moveTo(12f, 9f); lineTo(17f, 15f); moveTo(17f, 9f); lineTo(12f, 15f)
    }

    private fun line(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()
}
