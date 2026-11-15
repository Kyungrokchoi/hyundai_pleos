package io.moata.pleos

import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Fuel
import ai.pleos.playground.vehicle.api.Odometer
import io.moata.pleos.core.vehicle.PleosApis
import io.moata.pleos.data.local.LocalGroup
import io.moata.pleos.data.local.LocalTrip
import io.moata.pleos.data.local.OutboxDao
import io.moata.pleos.data.local.OutboxEvent
import io.moata.pleos.data.local.TripDao
import io.moata.pleos.data.remote.Transport
import io.moata.pleos.data.remote.TransportResult
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map





class FakeSdk : PleosApis {
    var initThrows: Throwable? = null
    var initialized = 0
    var released = 0
    val calls = mutableListOf<String>()
    val registered = mutableListOf<Any?>()
    val unregistered = mutableListOf<Any?>()
    private val behaviors = mutableMapOf<String, (Array<Any?>) -> Unit>()

    @Suppress("UNCHECKED_CAST")
    fun complete(method: String, value: Any?) { behaviors[method] = { a -> (a[0] as (Any?) -> Unit)(value) } }
    @Suppress("UNCHECKED_CAST")
    fun fail(method: String, e: Exception) { behaviors[method] = { a -> (a[1] as (Exception) -> Unit)(e) } }
    fun throwing(method: String, t: Throwable) { behaviors[method] = { throw t } }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(c: Class<T>): T = Proxy.newProxyInstance(c.classLoader, arrayOf(c)) { self, m, args ->
        val a = args ?: emptyArray()
        if (m.declaringClass == Any::class.java) return@newProxyInstance when (m.name) {
            "hashCode" -> System.identityHashCode(self); "equals" -> self === a[0]; else -> "FakeSdk.${c.simpleName}"
        }
        calls += m.name
        when (m.name) {
            "registerDistanceDriven" -> registered += a[0]
            "unregisterDistanceDriven" -> unregistered += a[0]
            else -> behaviors[m.name]?.invoke(a)
        }
        null
    } as T

    override fun initialize() { initThrows?.let { throw it }; initialized++ }
    override fun release() { released++ }
    override val odometer: Odometer = proxy(Odometer::class.java)
    override val fuel: Fuel = proxy(Fuel::class.java)
    override val evBattery: EvBattery = proxy(EvBattery::class.java)
}

class FakeOutboxDao : OutboxDao {
    val rows = mutableListOf<OutboxEvent>()
    private var seq = 0L
    private val pending = MutableStateFlow(0)
    private val failedCount = MutableStateFlow(0)
    private fun refresh() { pending.value = rows.count { it.state == "PENDING" }; failedCount.value = rows.count { it.state == "FAILED" } }
    private fun patch(seqs: List<Long>, f: (OutboxEvent) -> OutboxEvent) {
        rows.replaceAll { if (it.seq in seqs) f(it) else it }; refresh()
    }
    override suspend fun insert(e: OutboxEvent): Long {
        if (rows.any { it.eventId == e.eventId }) return -1
        rows += e.copy(seq = ++seq); refresh(); return seq
    }
    override suspend fun pending(limit: Int) = rows.filter { it.state == "PENDING" }.sortedBy { it.seq }.take(limit)
    override suspend fun markDone(seqs: List<Long>) = patch(seqs) { it.copy(state = "DONE", lastError = null) }
    override suspend fun markFailed(seqs: List<Long>, error: String) = patch(seqs) { it.copy(state = "FAILED", lastError = error) }
    override suspend fun recordRetry(seqs: List<Long>, attempts: Int, next: Long, error: String) =
        patch(seqs) { it.copy(attempts = attempts, nextAttemptAt = next, lastError = error) }
    override fun pendingCount(): Flow<Int> = pending
    override fun failedCount(): Flow<Int> = failedCount.map { it }
    override suspend fun failed() = rows.filter { it.state == "FAILED" }
    override suspend fun dropFailedFor(tripId: String): Int { val n = rows.count { it.tripId == tripId && it.state == "FAILED" }; rows.removeAll { it.tripId == tripId && it.state == "FAILED" }; return n }
    override suspend fun requeueFailed() = patch(rows.filter { it.state == "FAILED" }.map { it.seq }) { it.copy(state = "PENDING", attempts = 0, nextAttemptAt = 0) }
    override suspend fun readingCount(tripId: String) = rows.count { it.tripId == tripId && it.kind == "READING" }
}

class FakeTripDao : TripDao {
    val trips = linkedMapOf<String, LocalTrip>()
    val groups = linkedMapOf<String, LocalGroup>()
    override suspend fun active() = trips.values.filter { it.isActive }.maxByOrNull { it.createdAt }
    override suspend fun open() = trips.values.filter { it.state in setOf("INVITING", "READY", "IN_PROGRESS", "ENDED", "REVIEW", "DISPUTED") }.maxByOrNull { it.createdAt }
    override suspend fun get(id: String) = trips[id]
    override suspend fun highestOdometer(exclude: String, mode: String) =
        trips.values.filter { it.id != exclude && it.dataMode == mode }.mapNotNull { it.lastOdometerKm }.maxOrNull()
    override suspend fun upsert(t: LocalTrip) { trips[t.id] = t }
    override suspend fun groups() = groups.values.sortedBy { it.name }
    override suspend fun upsertGroups(groups: List<LocalGroup>) { groups.forEach { this.groups[it.id] = it } }
    override suspend fun clearTrips() { trips.clear() }
}

class FakeTransport(private val script: MutableList<TransportResult> = mutableListOf()) : Transport {
    data class Sent(val method: String, val path: String, val body: String?)
    val sent = mutableListOf<Sent>()
    var default: TransportResult = TransportResult.Success(200, "{}")
    fun enqueue(vararg r: TransportResult) { script += r }
    override suspend fun send(method: String, path: String, body: String?): TransportResult {
        sent += Sent(method, path, body)
        return if (script.isNotEmpty()) script.removeAt(0) else default
    }
}
