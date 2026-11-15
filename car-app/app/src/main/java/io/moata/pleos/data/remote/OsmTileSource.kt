package io.moata.pleos.data.remote

import io.moata.pleos.domain.TileKey
import io.moata.pleos.domain.TileSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request


class ByteLru(private val maxBytes: Int) {
    private val map = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var bytes = 0

    @Synchronized fun get(key: String): ByteArray? = map[key]

    @Synchronized fun put(key: String, value: ByteArray) {
        if (value.size > maxBytes) return
        map.put(key, value)?.let { bytes -= it.size }
        bytes += value.size
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) { val e = it.next(); bytes -= e.value.size; it.remove() }
    }

    @Synchronized fun size() = map.size
}





class OsmTileSource(
    private val http: OkHttpClient,
    private val cacheDir: File,
    private val baseUrl: String = "https://tile.openstreetmap.org",
    private val memory: ByteLru = ByteLru(8 * 1024 * 1024),
    private val maxFilesOnDisk: Int = MAX_FILES_ON_DISK,
) : TileSource {
    override suspend fun tile(key: TileKey): ByteArray? = withContext(Dispatchers.IO) {
        val name = "${key.z}-${key.x}-${key.y}.png"
        memory.get(name)?.let { return@withContext it }
        val onDisk = File(cacheDir, name)
        if (onDisk.exists()) {
            val b = runCatching { onDisk.readBytes() }.getOrNull()
            if (b != null && b.isNotEmpty()) { memory.put(name, b); return@withContext b }
        }
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/${key.z}/${key.x}/${key.y}.png").header("User-Agent", USER_AGENT).build()
        val body = try {
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
        } catch (e: IOException) {
            null
        } ?: return@withContext null
        if (body.isEmpty()) return@withContext null
        memory.put(name, body)
        runCatching { cacheDir.mkdirs(); onDisk.writeBytes(body); prune() }
        body
    }


    private fun prune() {
        val files = cacheDir.listFiles() ?: return
        if (files.size <= maxFilesOnDisk) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFilesOnDisk).forEach { it.delete() }
    }

    companion object {
        const val USER_AGENT = "MOATA-Pleos-demo/0.1"
        const val MAX_FILES_ON_DISK = 200
    }
}
