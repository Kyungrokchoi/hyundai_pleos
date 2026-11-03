package io.moata.pleos.data.remote

interface Transport {
    suspend fun send(method: String, path: String, body: String?): TransportResult
}

sealed interface TransportResult {
    data class Success(val code: Int, val body: String?) : TransportResult
    /** 4xx other than 401/408/425/429. Retrying the same request cannot help. */
    data class Rejected(val code: Int, val errorCode: String?, val messageKo: String?) : TransportResult
    /** Offline, timeout, 5xx, 408/425/429: keep the event and try again later. */
    data class Retryable(val reason: String) : TransportResult
    data object Unauthorized : TransportResult
}
