package io.moata.pleos.domain

import io.moata.pleos.core.vehicle.SignalType
import io.moata.pleos.core.vehicle.VehicleSample
import io.moata.pleos.data.local.LocalTrip
import io.moata.pleos.data.local.OutboxDao
import io.moata.pleos.data.local.OutboxEvent
import io.moata.pleos.data.local.TripDao
import io.moata.pleos.data.remote.ApiJson
import io.moata.pleos.data.remote.BoardingIn
import io.moata.pleos.data.remote.CostItemIn
import io.moata.pleos.data.remote.CostRuleIn
import io.moata.pleos.data.remote.ReadingIn
import io.moata.pleos.data.remote.TripCreateIn
import io.moata.pleos.data.remote.TripEndIn
import io.moata.pleos.data.remote.TripStartIn
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString

/**
 * Updates Room and appends to the outbox together. Event ids are deterministic, so a double tap or restart
 * cannot create a second trip, start or end.
 */
class TripRepository(
    private val trips: TripDao,
    private val outbox: OutboxDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun active(): LocalTrip? = trips.active()

    suspend fun open(): LocalTrip? = trips.open()

    /** Local only; the server record is untouched. */
    suspend fun close(tripId: String, confirmed: Boolean) = update(tripId) { it.copy(state = if (confirmed) "CONFIRMED" else "CLOSED") }

    /** Idempotent on id. While another trip is active, returns that one instead. */
    suspend fun createTrip(id: String, groupId: String, dataMode: String, vehicleType: String, rule: CostRuleIn): LocalTrip {
        trips.get(id)?.let { return it }
        trips.active()?.let { return it }
        val trip = LocalTrip(id, groupId, "INVITING", dataMode, vehicleType, createdAt = now())
        trips.upsert(trip)
        enqueue("trip-create:$id", id, "POST", "/trips", ApiJson.encodeToString(TripCreateIn(id, groupId, dataMode = dataMode, costRule = rule)))
        return trip
    }

    /** Called after the server accepted `ready`. */
    suspend fun markReady(tripId: String) = update(tripId) { if (it.state == "INVITING") it.copy(state = "READY") else it }

    suspend fun start(tripId: String, odometer: VehicleSample): LocalTrip? {
        val t = trips.get(tripId) ?: return null
        if (t.state == "IN_PROGRESS") return t                       // repeated tap / restart: no second start
        if (t.state != "READY") return t
        val updated = t.copy(state = "IN_PROGRESS", startedAt = now(), odometerStartKm = odometer.value, lastOdometerKm = odometer.value, lastOdometerAt = odometer.capturedAtEpochMs)
        trips.upsert(updated)
        enqueue("trip-start:$tripId", tripId, "POST", "/trips/$tripId/start", ApiJson.encodeToString(TripStartIn(toReading(odometer, "start-$tripId"))))
        return updated
    }

    suspend fun end(tripId: String, odometer: VehicleSample): LocalTrip? {
        val t = trips.get(tripId) ?: return null
        if (t.state != "IN_PROGRESS") return t                       // duplicate end: nothing new is queued
        val updated = t.copy(state = "ENDED", endedAt = now(), lastOdometerKm = odometer.value ?: t.lastOdometerKm)
        trips.upsert(updated)
        enqueue("trip-end:$tripId", tripId, "POST", "/trips/$tripId/end", ApiJson.encodeToString(TripEndIn(toReading(odometer, "end-$tripId"))))
        return updated
    }

    suspend fun cancel(tripId: String) {
        val t = trips.get(tripId) ?: return
        if (t.state in setOf("INVITING", "READY", "IN_PROGRESS")) {
            trips.upsert(t.copy(state = "CANCELLED", endedAt = now()))
            enqueue("trip-cancel:$tripId", tripId, "POST", "/trips/$tripId/cancel", null)
        }
    }

    /** Failed readings are queued too, so data quality is auditable server-side. */
    suspend fun recordReading(tripId: String, sample: VehicleSample) {
        val t = trips.get(tripId) ?: return
        if (t.state != "IN_PROGRESS") return
        val id = newId()
        enqueue(id, tripId, "POST", "/trips/$tripId/readings/batch", ApiJson.encodeToString(toReading(sample, id)), OutboxEvent.KIND_READING)
        if (sample.type == SignalType.ODOMETER && sample.usable) {
            trips.upsert(t.copy(lastOdometerKm = sample.value, lastOdometerAt = sample.capturedAtEpochMs))
        }
    }

    suspend fun board(tripId: String, userId: String, alight: Boolean, odometerKm: String? = null, reason: String = "") {
        val verb = if (alight) "alight" else "board"
        // one event per user/verb/minute stops double taps; the server also ignores repeats
        val key = "$verb:$tripId:$userId:${now() / 60_000}"
        enqueue(key, tripId, "POST", "/trips/$tripId/participants/$userId/$verb", ApiJson.encodeToString(BoardingIn(odometerKm, reason)))
    }

    suspend fun addCostItem(tripId: String, item: CostItemIn) =
        enqueue("cost-item:${item.id}", tripId, "POST", "/trips/$tripId/cost-items", ApiJson.encodeToString(item))

    suspend fun deleteCostItem(tripId: String, itemId: String) =
        enqueue("cost-item-del:$itemId", tripId, "DELETE", "/trips/$tripId/cost-items/$itemId", null)

    suspend fun markReviewed(tripId: String, state: String) = update(tripId) { it.copy(state = state) }

    private suspend fun update(tripId: String, f: (LocalTrip) -> LocalTrip) {
        trips.get(tripId)?.let { trips.upsert(f(it)) }
    }

    private suspend fun enqueue(eventId: String, tripId: String?, method: String, path: String, body: String?, kind: String = OutboxEvent.KIND_REQUEST) {
        outbox.insert(OutboxEvent(eventId = eventId, kind = kind, tripId = tripId, method = method, path = path, body = body, createdAt = now()))
    }

    companion object {
        /** Sends the raw SDK value and unit (e.g. 6.2 L/100km); the server normalises. */
        fun toReading(s: VehicleSample, eventId: String) = ReadingIn(
            eventId = eventId, type = s.type.name, value = s.value, unit = s.unit,
            capturedAt = Instant.ofEpochMilli(s.capturedAtEpochMs).toString(), source = s.source.name,
            quality = s.quality.name, sdkVersion = s.sdkVersion, error = s.error?.take(200),
        )
    }
}

/** At most one stored reading per `minIntervalMs`, so the outbox doesn't fill up. */
class Throttle(private val minIntervalMs: Long) {
    private var last = Long.MIN_VALUE
    fun shouldRecord(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < minIntervalMs) return false
        last = nowMs
        return true
    }
}
