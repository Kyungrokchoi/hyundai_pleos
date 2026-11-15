package io.moata.pleos.data.remote

interface Transport {
    suspend fun send(method: String, path: String, body: String?): TransportResult
}

sealed interface TransportResult {
    data class Success(val code: Int, val body: String?) : TransportResult

    data class Rejected(val code: Int, val errorCode: String?, val messageKo: String?) : TransportResult

    data class Retryable(val reason: String) : TransportResult
    data object Unauthorized : TransportResult
}
