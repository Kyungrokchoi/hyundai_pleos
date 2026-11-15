package io.moata.pleos.data

import io.moata.pleos.data.remote.ApiClient
import io.moata.pleos.data.remote.TransportResult
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class ApiClientThreadTest {
    @Test
    fun responseBodyIsNeverReadOnTheCallingThread() {
        var readOn: String? = null
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = 2L
                    override fun source(): BufferedSource { readOn = Thread.currentThread().name; return Buffer().writeUtf8("{}") }
                }).build()
        }).build()
        val client = ApiClient("http://127.0.0.1:1", { null }, http)
        val callerPool = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            val result = runBlocking { withContext(callerPool.asCoroutineDispatcher()) { client.send("GET", "/health", null) } }
            assertTrue(result is TransportResult.Success)
            assertNotEquals("body was read on the calling (main-like) thread", "fake-main", readOn)
            assertEquals(true, readOn?.startsWith("DefaultDispatcher"))
        } finally {
            callerPool.shutdownNow()
        }
    }
}
