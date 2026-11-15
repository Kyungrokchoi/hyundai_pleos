package io.moata.pleos.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Rejected(val code: Int, val errorCode: String?, val messageKo: String) : ApiResult<Nothing>
    data class Offline(val reason: String) : ApiResult<Nothing>
    data object Unauthorized : ApiResult<Nothing>
}

class ApiClient(
    baseUrl: String,
    private val token: () -> String?,
    private val http: OkHttpClient = defaultClient(),
) : Transport {
    private val root = baseUrl.trimEnd('/') + "/api/v1"
    private val json = "application/json; charset=utf-8".toMediaType()


    override suspend fun send(method: String, path: String, body: String?): TransportResult = withContext(Dispatchers.IO) {
        val response = try {
            execute(method, path, body, null)
        } catch (e: IOException) {
            return@withContext TransportResult.Retryable("NETWORK: ${e::class.java.simpleName}: ${e.message}")
        }
        response.use { r ->
            val text = r.body.string()
            when {
                r.isSuccessful -> TransportResult.Success(r.code, text)
                r.code == 401 -> TransportResult.Unauthorized
                r.code in RETRYABLE || r.code >= 500 -> TransportResult.Retryable("HTTP ${r.code}")
                else -> {
                    val err = runCatching { ApiJson.decodeFromString<ErrorOut>(text) }.getOrNull()
                    TransportResult.Rejected(r.code, err?.code, err?.messageKo)
                }
            }
        }
    }

    suspend inline fun <reified T> call(method: String, path: String, bodyJson: String? = null): ApiResult<T> =
        when (val r = send(method, path, bodyJson)) {
            is TransportResult.Success -> try {
                @Suppress("UNCHECKED_CAST")
                ApiResult.Ok(if (T::class == Unit::class) Unit as T else ApiJson.decodeFromString<T>(r.body.orEmpty()))
            } catch (e: SerializationException) {
                ApiResult.Rejected(r.code, "BAD_RESPONSE", "서버 응답을 해석하지 못했어요.")
            }
            is TransportResult.Rejected -> ApiResult.Rejected(r.code, r.errorCode, r.messageKo ?: "요청이 거절되었어요 (${r.code}).")
            is TransportResult.Retryable -> ApiResult.Offline(r.reason)
            TransportResult.Unauthorized -> ApiResult.Unauthorized
        }

    suspend inline fun <reified B, reified T> post(path: String, body: B): ApiResult<T> =
        call("POST", path, ApiJson.encodeToString(body))

    suspend inline fun <reified T> get(path: String): ApiResult<T> = call("GET", path)

    private suspend fun execute(method: String, path: String, body: String?, etag: String?): Response {
        val req = Request.Builder().url(root + path).apply {
            token()?.let { header("Authorization", "Bearer $it") }
            etag?.let { header("If-None-Match", it) }
            val rb = body?.toRequestBody(json) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(json)
            method(method, rb)
        }.build()
        return http.newCall(req).await()
    }

    companion object {
        private val RETRYABLE = setOf(408, 425, 429)
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
    })
}
