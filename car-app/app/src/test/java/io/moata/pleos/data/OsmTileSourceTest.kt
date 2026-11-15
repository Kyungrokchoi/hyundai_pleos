package io.moata.pleos.data

import io.moata.pleos.data.remote.ByteLru
import io.moata.pleos.data.remote.OsmTileSource
import io.moata.pleos.domain.TileKey
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmTileSourceTest {
    private val dirs = mutableListOf<File>()
    private fun dir() = createTempDirectory("osm").toFile().also { dirs += it }
    @After fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    private val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
    private val key = TileKey(14, 13974, 6342)

    private class Server(val code: Int = 200, val body: ByteArray, val fail: Boolean = false) {
        val seen = mutableListOf<Request>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen += chain.request()
            if (fail) throw IOException("offline")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("image/png".toMediaType())).build()
        }).build()
    }

    @Test fun fetchesTheRightUrlWithADescriptiveUserAgent() {
        val s = Server(body = png)
        val got = runBlocking { OsmTileSource(s.client, dir(), "https://tiles.test").tile(key) }
        assertArrayEquals(png, got)
        assertEquals("https://tiles.test/14/13974/6342.png", s.seen.single().url.toString())
        assertEquals(OsmTileSource.USER_AGENT, s.seen.single().header("User-Agent"))
        assertTrue(OsmTileSource.USER_AGENT.contains("MOATA"))
    }

    @Test fun aTileIsRequestedOnceThenServedFromMemory() {
        val s = Server(body = png)
        val src = OsmTileSource(s.client, dir(), "https://tiles.test")
        runBlocking { repeat(3) { assertArrayEquals(png, src.tile(key)) } }
        assertEquals(1, s.seen.size)
    }

    @Test fun theDiskCacheSurvivesANewInstance() {
        val d = dir()
        runBlocking { OsmTileSource(Server(body = png).client, d, "https://tiles.test").tile(key) }
        val second = Server(body = byteArrayOf(9), fail = true)
        assertArrayEquals(png, runBlocking { OsmTileSource(second.client, d, "https://tiles.test").tile(key) })
        assertTrue("no request needed", second.seen.isEmpty())
    }

    @Test fun refusedOrOfflineGivesNullAndIsNotCached() {
        val d = dir()
        assertNull(runBlocking { OsmTileSource(Server(code = 403, body = png).client, d, "https://tiles.test").tile(key) })
        assertNull(runBlocking { OsmTileSource(Server(body = png, fail = true).client, d, "https://tiles.test").tile(key) })
        assertTrue(d.listFiles().isNullOrEmpty())
        assertArrayEquals(png, runBlocking { OsmTileSource(Server(body = png).client, d, "https://tiles.test").tile(key) })
    }

    @Test fun theDiskFolderIsPruned() {
        val d = dir()
        val src = OsmTileSource(Server(body = png).client, d, "https://tiles.test", maxFilesOnDisk = 3)
        runBlocking { (0 until 8).forEach { src.tile(TileKey(10, it, 0)) } }
        assertTrue("${d.listFiles()!!.size} files", d.listFiles()!!.size <= 3)
    }

    @Test fun theMemoryCacheEvictsLeastRecentlyUsedWithinItsByteBudget() {
        val lru = ByteLru(10)
        lru.put("a", ByteArray(4))
        lru.put("b", ByteArray(4))
        lru.get("a")
        lru.put("c", ByteArray(4))
        assertNull(lru.get("b"))
        assertTrue(lru.get("a") != null && lru.get("c") != null)
        lru.put("huge", ByteArray(11))
        assertNull(lru.get("huge"))
        assertEquals(2, lru.size())
    }
}
