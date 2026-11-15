package io.moata.pleos.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class LatLon(val lat: Double, val lon: Double)
data class PxPoint(val x: Double, val y: Double)
data class TileKey(val z: Int, val x: Int, val y: Int)


data class PlacedTile(val key: TileKey, val dx: Double, val dy: Double)


data class MapView(val zoom: Int, val left: Double, val top: Double)

fun interface TileSource {

    suspend fun tile(key: TileKey): ByteArray?
}


object RouteMap {
    const val TILE_PX = 256
    const val VIEW_W = 768
    const val VIEW_H = 384


    const val MAX_TILES = 12
    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 16


    const val PADDING = 0.15
    private const val MAX_LAT = 85.05112878

    fun worldPx(lat: Double, lon: Double, zoom: Int): PxPoint {
        val scale = TILE_PX * 2.0.pow(zoom)
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return PxPoint((lon + 180.0) / 360.0 * scale, (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * scale)
    }


    fun fit(points: List<LatLon>): MapView? {
        if (points.isEmpty()) return null
        val usableW = VIEW_W * (1 - 2 * PADDING)
        val usableH = VIEW_H * (1 - 2 * PADDING)
        for (z in MAX_ZOOM downTo MIN_ZOOM) {
            val px = points.map { worldPx(it.lat, it.lon, z) }
            val w = px.maxOf { it.x } - px.minOf { it.x }
            val h = px.maxOf { it.y } - px.minOf { it.y }
            if (w <= usableW && h <= usableH || z == MIN_ZOOM) return centred(z, px)
        }
        return null
    }

    private fun centred(zoom: Int, px: List<PxPoint>): MapView {
        val cx = (px.maxOf { it.x } + px.minOf { it.x }) / 2
        val cy = (px.maxOf { it.y } + px.minOf { it.y }) / 2
        val world = TILE_PX * 2.0.pow(zoom)
        return MapView(zoom, cx - VIEW_W / 2.0, (cy - VIEW_H / 2.0).coerceIn(0.0, maxOf(0.0, world - VIEW_H)))
    }


    fun tilesFor(view: MapView): List<PlacedTile> {
        val n = 1 shl view.zoom
        val x0 = floor(view.left / TILE_PX).toInt()
        val x1 = floor((view.left + VIEW_W - 1e-9) / TILE_PX).toInt()
        val y0 = floor(view.top / TILE_PX).toInt()
        val y1 = floor((view.top + VIEW_H - 1e-9) / TILE_PX).toInt()
        val out = ArrayList<PlacedTile>()
        for (ty in y0..y1) {
            if (ty !in 0 until n) continue
            for (tx in x0..x1) out += PlacedTile(TileKey(view.zoom, Math.floorMod(tx, n), ty), tx * TILE_PX - view.left, ty * TILE_PX - view.top)
        }
        return out
    }


    fun project(view: MapView, lat: Double, lon: Double): PxPoint =
        worldPx(lat, lon, view.zoom).let { PxPoint(it.x - view.left, it.y - view.top) }


    fun simplify(pts: List<PxPoint>, tolerancePx: Double = 1.5, maxPoints: Int = 600): List<PxPoint> {
        if (pts.size <= 2) return pts
        val keep = BooleanArray(pts.size).also { it[0] = true; it[pts.lastIndex] = true }
        val stack = ArrayDeque<IntArray>().also { it.addLast(intArrayOf(0, pts.lastIndex)) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var far = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val d = distanceToSegmentLine(pts[i], pts[a], pts[b])
                if (d > far) { far = d; at = i }
            }
            if (at != -1 && far > tolerancePx) { keep[at] = true; stack.addLast(intArrayOf(a, at)); stack.addLast(intArrayOf(at, b)) }
        }
        val out = pts.filterIndexed { i, _ -> keep[i] }
        if (out.size <= maxPoints || maxPoints < 2) return out
        val step = (out.size - 1).toDouble() / (maxPoints - 1)
        return List(maxPoints) { i -> out[(i * step).roundToInt().coerceAtMost(out.lastIndex)] }
    }

    private fun distanceToSegmentLine(p: PxPoint, a: PxPoint, b: PxPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        if (len == 0.0) return sqrt((p.x - a.x).let { it * it } + (p.y - a.y).let { it * it })
        return abs(dx * (a.y - p.y) - (a.x - p.x) * dy) / len
    }
}


data class RouteScene(
    val view: MapView, val tiles: List<PlacedTile>, val line: List<PxPoint>,
    val start: PxPoint, val end: PxPoint, val passes: List<PxPoint>,
)


fun routeScene(route: List<LatLon>, passes: List<LatLon>): RouteScene? {
    if (route.size < 2) return null
    val view = RouteMap.fit(route + passes) ?: return null
    val px = route.map { RouteMap.project(view, it.lat, it.lon) }
    return RouteScene(view, RouteMap.tilesFor(view), RouteMap.simplify(px), px.first(), px.last(), passes.map { RouteMap.project(view, it.lat, it.lon) })
}






suspend fun loadTiles(
    tiles: List<PlacedTile>, source: TileSource, concurrency: Int = 2, onTile: (TileKey, ByteArray) -> Unit = { _, _ -> },
): Map<TileKey, ByteArray> = coroutineScope {
    val gate = Semaphore(concurrency)
    tiles.take(RouteMap.MAX_TILES)
        .map { t -> async { gate.withPermit { fetchOrNull(source, t.key)?.also { onTile(t.key, it) }?.let { t.key to it } } } }
        .awaitAll().filterNotNull().toMap()
}

private suspend fun fetchOrNull(source: TileSource, key: TileKey): ByteArray? =
    try { source.tile(key) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
