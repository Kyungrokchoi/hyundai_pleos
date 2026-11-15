package io.moata.pleos.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.moata.pleos.data.remote.PlaceOut
import io.moata.pleos.domain.LatLon
import io.moata.pleos.domain.PxPoint
import io.moata.pleos.domain.RouteMap
import io.moata.pleos.domain.TileKey
import io.moata.pleos.domain.TileSource
import io.moata.pleos.domain.TrackPoint
import io.moata.pleos.domain.loadTiles
import io.moata.pleos.domain.routeScene
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"





@Composable
fun RouteMapCard(route: List<TrackPoint>, start: PlaceOut?, end: PlaceOut?, tiles: TileSource) {
    val scene = remember(route) { routeScene(route.map { LatLon(it.lat, it.lon) }, emptyList()) } ?: return
    val images = remember(scene) { mutableStateMapOf<TileKey, ImageBitmap>() }
    LaunchedEffect(scene) {
        withContext(Dispatchers.Default) {
            loadTiles(scene.tiles, tiles) { key, bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { images[key] = it.asImageBitmap() } }
        }
    }
    val measurer = rememberTextMeasurer()
    val description = "지나온 길 지도" + listOfNotNull(start?.address?.let { ", 출발 $it" }, end?.address?.let { ", 도착 $it" }).joinToString("")
    Section("지나온 길") {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(RouteMap.VIEW_W.toFloat() / RouteMap.VIEW_H).clip(RoundedCornerShape(24.dp)).background(SurfaceHi)
                .semantics { contentDescription = description },
        ) {
            val k = size.width / RouteMap.VIEW_W
            val tilePx = ceil(RouteMap.TILE_PX * k).toInt()
            scene.tiles.forEach { t ->
                images[t.key]?.let { drawImage(it, dstOffset = IntOffset((t.dx * k).roundToInt(), (t.dy * k).roundToInt()), dstSize = IntSize(tilePx, tilePx)) }
            }
            val path = Path().apply { scene.line.forEachIndexed { i, p -> if (i == 0) moveTo((p.x * k).toFloat(), (p.y * k).toFloat()) else lineTo((p.x * k).toFloat(), (p.y * k).toFloat()) } }
            drawPath(path, Color.White, style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, RouteTeal, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            listOf(scene.start, scene.end).forEachIndexed { i, p ->
                dot(p, k, Ink, radius = 24.dp.toPx())
                val label = measurer.measure("${i + 1}", TextStyle(color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                drawText(label, topLeft = Offset((p.x * k).toFloat() - label.size.width / 2f, (p.y * k).toFloat() - label.size.height / 2f))
            }
        }
        Text("1 : ${start?.address ?: "출발지"}", style = MaterialTheme.typography.bodyMedium, color = Paper)
        Text("2 : ${end?.address ?: "도착지"}", style = MaterialTheme.typography.bodyMedium, color = Paper)
    }
}

private fun DrawScope.dot(p: PxPoint, k: Float, fill: Color, radius: Float = 16.dp.toPx()) {
    val c = Offset((p.x * k).toFloat(), (p.y * k).toFloat())
    drawCircle(Color.White, radius + 4.dp.toPx(), c)
    drawCircle(fill, radius, c)
}
