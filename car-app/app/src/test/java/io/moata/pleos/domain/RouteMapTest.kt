package io.moata.pleos.domain

import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapTest {
    private fun ll(lat: Double, lon: Double) = LatLon(lat, lon)

    @Test fun worldPixelsMatchKnownMercatorPoints() {
        assertEquals(128.0, RouteMap.worldPx(0.0, 0.0, 0).x, 1e-6)
        assertEquals(128.0, RouteMap.worldPx(0.0, 0.0, 0).y, 1e-6)
        assertEquals(0.0, RouteMap.worldPx(0.0, -180.0, 3).x, 1e-6)
        assertEquals(256.0 * 8, RouteMap.worldPx(0.0, 180.0, 3).x, 1e-6)
        assertEquals(0.0, RouteMap.worldPx(85.05112878, 0.0, 4).y, 0.5)
        assertEquals(RouteMap.worldPx(89.9, 0.0, 4).y, RouteMap.worldPx(85.05112878, 0.0, 4).y, 1e-6)
    }

    @Test fun aSinglePointGetsTheClosestZoom() = assertEquals(RouteMap.MAX_ZOOM, RouteMap.fit(listOf(ll(37.3, 127.0)))!!.zoom)

    @Test fun noPointsNoView() = assertNull(RouteMap.fit(emptyList()))

    @Test fun everyPointFitsInsideThePaddedViewAndNoCloserZoomWould() {
        val route = listOf(ll(37.2975, 127.0475), ll(37.3365, 127.0750), ll(37.3653, 127.1034))
        val view = RouteMap.fit(route)!!
        val pad = RouteMap.PADDING
        route.forEach {
            val p = RouteMap.project(view, it.lat, it.lon)
            assertTrue("x ${p.x}", p.x >= RouteMap.VIEW_W * pad - 1 && p.x <= RouteMap.VIEW_W * (1 - pad) + 1)
            assertTrue("y ${p.y}", p.y >= RouteMap.VIEW_H * pad - 1 && p.y <= RouteMap.VIEW_H * (1 - pad) + 1)
        }
        val closer = route.map { RouteMap.worldPx(it.lat, it.lon, view.zoom + 1) }
        val spanX = closer.maxOf { it.x } - closer.minOf { it.x }
        val spanY = closer.maxOf { it.y } - closer.minOf { it.y }
        assertTrue("one zoom closer must not fit", spanX > RouteMap.VIEW_W * (1 - 2 * pad) || spanY > RouteMap.VIEW_H * (1 - 2 * pad))
    }

    @Test fun aWholeContinentFallsBackToTheLowestZoomInsteadOfFailing() {
        val view = RouteMap.fit(listOf(ll(-40.0, -170.0), ll(60.0, 170.0)))!!
        assertEquals(RouteMap.MIN_ZOOM, view.zoom)
    }

    @Test fun aViewNeverNeedsMoreTilesThanTheCeiling() {
        val rnd = Random(7)
        repeat(2_000) {
            val lat = rnd.nextDouble(-84.0, 84.0)
            val lon = rnd.nextDouble(-179.9, 179.9)
            val route = listOf(ll(lat, lon), ll((lat + rnd.nextDouble(0.0, 2.0)).coerceAtMost(85.0), lon + rnd.nextDouble(0.0, 3.0)))
            val tiles = RouteMap.tilesFor(RouteMap.fit(route)!!)
            assertTrue("${tiles.size} tiles for $route", tiles.size in 1..RouteMap.MAX_TILES)
        }
    }

    @Test fun tileKeysStayInsideTheWorldEvenAcrossTheAntimeridian() {
        val view = RouteMap.fit(listOf(ll(10.0, 179.99), ll(10.001, -179.99)))!!
        val n = 1 shl view.zoom
        val tiles = RouteMap.tilesFor(view)
        assertTrue(tiles.all { it.key.x in 0 until n && it.key.y in 0 until n })
    }

    @Test fun placedTilesCoverTheWholeViewWithoutGaps() {
        val view = RouteMap.fit(listOf(ll(37.2975, 127.0475), ll(37.3653, 127.1034)))!!
        val tiles = RouteMap.tilesFor(view)
        assertTrue(tiles.minOf { it.dx } <= 0.0 && tiles.minOf { it.dy } <= 0.0)
        assertTrue(tiles.maxOf { it.dx } + RouteMap.TILE_PX >= RouteMap.VIEW_W && tiles.maxOf { it.dy } + RouteMap.TILE_PX >= RouteMap.VIEW_H)
    }

    @Test fun aStraightLineCollapsesToItsEndsAndACornerSurvives() {
        val line = List(1_000) { PxPoint(it.toDouble(), it * 0.5) }
        assertEquals(listOf(line.first(), line.last()), RouteMap.simplify(line))
        val bent = List(50) { PxPoint(it.toDouble(), 0.0) } + List(50) { PxPoint(49.0, (it + 1).toDouble()) }
        val s = RouteMap.simplify(bent)
        assertTrue(PxPoint(49.0, 0.0) in s)
        assertEquals(bent.first(), s.first())
        assertEquals(bent.last(), s.last())
    }

    @Test fun simplifyHonoursTheHardCapAndKeepsOrder() {
        val wiggle = List(5_000) { PxPoint(it.toDouble(), if (it % 2 == 0) 0.0 else 20.0) }
        val s = RouteMap.simplify(wiggle, maxPoints = 300)
        assertEquals(300, s.size)
        assertEquals(wiggle.first(), s.first())
        assertEquals(wiggle.last(), s.last())
        assertEquals(s.sortedBy { it.x }, s)
    }

    @Test fun routeSceneNeedsTwoPointsAndProjectsTheGates() {
        assertNull(routeScene(listOf(ll(37.3, 127.0)), emptyList()))
        val route = listOf(ll(37.2975, 127.0475), ll(37.3653, 127.1034))
        val scene = routeScene(route, listOf(route.first(), route.last()))!!
        assertEquals(2, scene.passes.size)
        assertTrue(abs(scene.passes[0].x - scene.start.x) < 1e-6 && abs(scene.passes[1].y - scene.end.y) < 1e-6)
        assertTrue(scene.tiles.size <= RouteMap.MAX_TILES)
    }

    private class Counting(val fail: (TileKey) -> Boolean = { false }) : TileSource {
        val asked = mutableListOf<TileKey>()
        override suspend fun tile(key: TileKey): ByteArray? {
            asked += key
            if (fail(key)) throw IllegalStateException("boom")
            return byteArrayOf(1)
        }
    }

    @Test fun loadTilesNeverAsksForMoreThanTheCeiling() {
        val many = List(40) { PlacedTile(TileKey(5, it, 0), 0.0, 0.0) }
        val src = Counting()
        val out = runBlocking { loadTiles(many, src) }
        assertEquals(RouteMap.MAX_TILES, src.asked.size)
        assertEquals(RouteMap.MAX_TILES, out.size)
    }

    @Test fun aFailingTileIsMissingNotFatal() {
        val tiles = List(4) { PlacedTile(TileKey(5, it, 0), 0.0, 0.0) }
        val out = runBlocking { loadTiles(tiles, Counting(fail = { it.x == 2 })) }
        assertEquals(setOf(0, 1, 3), out.keys.map { it.x }.toSet())
    }

    @Test fun everyTileIsReportedAsItArrivesAndFailedOnesAreNot() {
        val tiles = List(4) { PlacedTile(TileKey(5, it, 0), 0.0, 0.0) }
        val seen = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val out = runBlocking { loadTiles(tiles, Counting(fail = { it.x == 2 })) { key, bytes -> assertEquals(1, bytes.size); seen += key.x } }
        assertEquals(setOf(0, 1, 3), seen.toSet())
        assertEquals(seen.toSet(), out.keys.map { it.x }.toSet())
    }

    @Test fun anOfflineSourceGivesAnEmptyMapNotAnError() {
        val offline = TileSource { null }
        assertTrue(runBlocking { loadTiles(List(3) { PlacedTile(TileKey(5, it, 0), 0.0, 0.0) }, offline) }.isEmpty())
    }

    @Test fun cancellationIsNotSwallowed() {
        val cancelling = TileSource { throw CancellationException("stop") }
        val r = runCatching { runBlocking { loadTiles(listOf(PlacedTile(TileKey(5, 1, 1), 0.0, 0.0)), cancelling) } }
        assertTrue(r.exceptionOrNull() is CancellationException)
    }
}
