package io.moata.pleos.data.remote

import io.moata.pleos.data.local.OutboxDao
import io.moata.pleos.data.local.OutboxEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.min

data class DrainReport(val sent: Int, val rejected: Int, val stoppedBecause: Stop?) {
    enum class Stop { OFFLINE, WAITING_BACKOFF, UNAUTHORIZED }
}

/**
 * Replays the outbox strictly in `seq` order; consecutive READING rows of one trip go as one batch (<=100).
 *  - Retryable: back off and stop, so later events never overtake an earlier one.
 *  - Rejected: mark FAILED and continue.
 *  - Unauthorized: stop without counting an attempt.
 * Resending after a lost response is safe because the server dedupes on client ids.
 */
class OutboxProcessor(
    private val dao: OutboxDao,
    private val transport: Transport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun drain(): DrainReport {
        var sent = 0
        var rejected = 0
        while (true) {
            val rows = dao.pending(PAGE)
            val head = rows.firstOrNull() ?: return DrainReport(sent, rejected, null)
            if (head.nextAttemptAt > now()) return DrainReport(sent, rejected, DrainReport.Stop.WAITING_BACKOFF)

            val group = if (head.kind == OutboxEvent.KIND_READING) {
                rows.takeWhile { it.kind == OutboxEvent.KIND_READING && it.tripId == head.tripId }.take(BATCH)
            } else listOf(head)

            val result = if (group.first().kind == OutboxEvent.KIND_READING) sendReadings(group) else {
                transport.send(head.method, head.path, head.body)
            }
            val seqs = group.map { it.seq }
            when (result) {
                is TransportResult.Success -> {
                    var failedIds = emptySet<String>()
                    if (head.kind == OutboxEvent.KIND_READING) {
                        // per-reading rejections (e.g. unknown unit) stay FAILED, not retried
                        val out = runCatching { ApiJson.decodeFromString<ReadingBatchOut>(result.body.orEmpty()) }.getOrNull()
                        failedIds = out?.rejected?.map { it.eventId }?.toSet().orEmpty()
                        out?.rejected?.forEach { rej ->
                            group.firstOrNull { it.eventId == rej.eventId }?.let {
                                dao.markFailed(listOf(it.seq), "${rej.code}: ${rej.messageKo}")
                                rejected++
                            }
                        }
                    }
                    dao.markDone(group.filter { it.eventId !in failedIds }.map { it.seq })
                    sent += group.size - failedIds.size
                }
                is TransportResult.Rejected -> {
                    dao.markFailed(seqs, "HTTP ${result.code} ${result.errorCode.orEmpty()} ${result.messageKo.orEmpty()}".trim())
                    rejected += group.size
                }
                is TransportResult.Retryable -> {
                    val attempts = head.attempts + 1
                    dao.recordRetry(seqs, attempts, now() + backoffMs(attempts), result.reason)
                    return DrainReport(sent, rejected, DrainReport.Stop.OFFLINE)
                }
                TransportResult.Unauthorized -> return DrainReport(sent, rejected, DrainReport.Stop.UNAUTHORIZED)
            }
        }
    }

    private suspend fun sendReadings(group: List<OutboxEvent>): TransportResult {
        val readings = group.map { ApiJson.decodeFromString<ReadingIn>(it.body.orEmpty()) }
        return transport.send("POST", "/trips/${group.first().tripId}/readings/batch", ApiJson.encodeToString(ReadingBatchIn(readings)))
    }

    companion object {
        const val PAGE = 200
        const val BATCH = 100
        /** 1s, 2s, 4s ... capped at 60s. */
        fun backoffMs(attempts: Int): Long = min(60_000L, 1_000L shl min(attempts - 1, 6))
    }
}
