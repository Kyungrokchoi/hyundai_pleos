package io.moata.pleos.data

import io.moata.pleos.FakeOutboxDao
import io.moata.pleos.FakeTransport
import io.moata.pleos.FakeTripDao
import io.moata.pleos.core.vehicle.DataSource
import io.moata.pleos.core.vehicle.Quality
import io.moata.pleos.core.vehicle.SignalType
import io.moata.pleos.core.vehicle.VehicleSample
import io.moata.pleos.data.local.OutboxEvent
import io.moata.pleos.data.remote.CostRuleIn
import io.moata.pleos.data.remote.DrainReport
import io.moata.pleos.data.remote.OutboxProcessor
import io.moata.pleos.data.remote.ReadingBatchIn
import io.moata.pleos.data.remote.ApiJson
import io.moata.pleos.data.remote.TransportResult
import io.moata.pleos.domain.Throttle
import io.moata.pleos.domain.TripRepository
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun req(id: String, path: String = "/x/$id", tripId: String? = "t1") =
    OutboxEvent(eventId = id, kind = OutboxEvent.KIND_REQUEST, tripId = tripId, method = "POST", path = path, body = "{}", createdAt = 0)

private fun reading(id: String, v: Double? = 1.0) = OutboxEvent(
    eventId = id, kind = OutboxEvent.KIND_READING, tripId = "t1", method = "POST", path = "/trips/t1/readings/batch", createdAt = 0,
    body = """{"event_id":"$id","type":"ODOMETER","value":${v ?: "null"},"unit":"km","captured_at":"2026-11-02T00:00:00Z","source":"PLEOS","quality":"VERIFIED"}""",
)

class OutboxProcessorTest {
    private var clock = 1_000_000L
    private val dao = FakeOutboxDao()
    private val net = FakeTransport()
    private val proc = OutboxProcessor(dao, net) { clock }

    @Test fun enqueueingTheSameEventIdTwiceKeepsOneRow() = runTest {
        assertTrue(dao.insert(req("a")) > 0)
        assertEquals(-1, dao.insert(req("a")))
        assertEquals(1, dao.rows.size)
    }

    @Test fun eventsAreReplayedStrictlyInOrder() = runTest {
        listOf("a", "b", "c").forEach { dao.insert(req(it)) }
        val r = proc.drain()
        assertEquals(listOf("/x/a", "/x/b", "/x/c"), net.sent.map { it.path })
        assertEquals(3, r.sent); assertNull(r.stoppedBecause)
        assertTrue(dao.rows.all { it.state == "DONE" })
    }

    @Test fun retryableFailureStopsThePipelineAndBacksOff() = runTest {
        dao.insert(req("a")); dao.insert(req("b"))
        net.enqueue(TransportResult.Retryable("NETWORK: down"))
        val first = proc.drain()
        assertEquals(DrainReport.Stop.OFFLINE, first.stoppedBecause)
        assertEquals(listOf("/x/a"), net.sent.map { it.path })             // b did not overtake a
        val a = dao.rows.first { it.eventId == "a" }
        assertEquals(1, a.attempts); assertEquals("NETWORK: down", a.lastError); assertEquals(clock + 1_000, a.nextAttemptAt)

        assertEquals(DrainReport.Stop.WAITING_BACKOFF, proc.drain().stoppedBecause)   // too early: nothing sent
        assertEquals(1, net.sent.size)

        clock += 1_001                                                    // backoff elapsed, network is back
        val ok = proc.drain()
        assertEquals(2, ok.sent)
        assertEquals(listOf("/x/a", "/x/a", "/x/b"), net.sent.map { it.path })
        assertTrue(dao.rows.all { it.state == "DONE" })
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L), (1..3).map { OutboxProcessor.backoffMs(it) })
        assertEquals(60_000L, OutboxProcessor.backoffMs(50))
    }

    @Test fun serverRejectionIsKeptAsFailedAndLaterEventsStillFlow() = runTest {
        dao.insert(req("a")); dao.insert(req("b"))
        net.enqueue(TransportResult.Rejected(409, "STATE_CONFLICT", "이미 시작된 운행이에요."))
        val r = proc.drain()
        assertEquals(1, r.rejected); assertEquals(1, r.sent)
        val a = dao.rows.first { it.eventId == "a" }
        assertEquals("FAILED", a.state); assertTrue(a.lastError!!.contains("STATE_CONFLICT"))
        assertEquals("DONE", dao.rows.first { it.eventId == "b" }.state)
    }

    @Test fun unauthorizedStopsWithoutBurningAttempts() = runTest {
        dao.insert(req("a"))
        net.enqueue(TransportResult.Unauthorized)
        assertEquals(DrainReport.Stop.UNAUTHORIZED, proc.drain().stoppedBecause)
        assertEquals(0, dao.rows[0].attempts); assertEquals("PENDING", dao.rows[0].state)
    }

    @Test fun readingsAreBatchedInOrderPerTrip() = runTest {
        dao.insert(req("start")); listOf("r1", "r2", "r3").forEach { dao.insert(reading(it)) }
        net.enqueue(TransportResult.Success(200, "{}"), TransportResult.Success(200, """{"accepted":3,"duplicates":0,"rejected":[]}"""))
        proc.drain()
        assertEquals(listOf("/x/start", "/trips/t1/readings/batch"), net.sent.map { it.path })
        val batch = ApiJson.decodeFromString<ReadingBatchIn>(net.sent[1].body!!)
        assertEquals(listOf("r1", "r2", "r3"), batch.readings.map { it.eventId })
        assertTrue(dao.rows.all { it.state == "DONE" })
    }

    @Test fun aRejectedReadingIsFailedWhileTheRestAreDone() = runTest {
        listOf("r1", "r2").forEach { dao.insert(reading(it)) }
        net.enqueue(TransportResult.Success(200, """{"accepted":1,"duplicates":0,"rejected":[{"event_id":"r2","code":"UNIT_UNKNOWN","message_ko":"알 수 없는 단위입니다."}]}"""))
        val r = proc.drain()
        assertEquals("DONE", dao.rows.first { it.eventId == "r1" }.state)
        assertEquals("FAILED", dao.rows.first { it.eventId == "r2" }.state)
        assertEquals(1, r.rejected)
    }

    @Test fun resendingAfterALostResponseIsHarmlessBecauseIdsAreStable() = runTest {
        dao.insert(reading("r1"))
        net.enqueue(TransportResult.Retryable("NETWORK: timeout after server processed it"))
        proc.drain()
        clock += 5_000
        net.default = TransportResult.Success(200, """{"accepted":0,"duplicates":1,"rejected":[]}""")   // server says: already have it
        proc.drain()
        val ids = net.sent.map { ApiJson.decodeFromString<ReadingBatchIn>(it.body!!).readings.single().eventId }
        assertEquals(listOf("r1", "r1"), ids)                                  // same event_id both times
        assertEquals("DONE", dao.rows.single().state)
    }
}

class TripRepositoryTest {
    private val trips = FakeTripDao()
    private val outbox = FakeOutboxDao()
    private var t = 10_000L
    private var n = 0
    private fun repo(d: FakeTripDao = trips, o: FakeOutboxDao = outbox) = TripRepository(d, o, { t++ }, { "ev-${n++}" })
    private val rule = CostRuleIn(vehicleType = "ICE", efficiencyValue = "12", efficiencyUnit = "km/L", unitPriceKrw = "1800")
    private fun odo(km: Double?, q: Quality = Quality.VERIFIED) =
        VehicleSample(SignalType.ODOMETER, km, "km", t, DataSource.PLEOS, q, "2.3.2", if (km == null) "TIMEOUT" else null)

    @Test fun creatingTheSameOrAnotherTripWhileOneIsActiveNeverDuplicates() = runTest {
        val r = repo()
        val a = r.createTrip("trip-1", "g1", "PLEOS", "ICE", rule)
        val again = r.createTrip("trip-1", "g1", "PLEOS", "ICE", rule)
        val other = r.createTrip("trip-2", "g1", "PLEOS", "ICE", rule)       // e.g. the screen was re-entered after a restart
        assertEquals(a, again); assertEquals("trip-1", other.id)
        assertEquals(1, trips.trips.size)
        assertEquals(1, outbox.rows.count { it.path == "/trips" })
    }

    @Test fun anActiveTripSurvivesProcessDeathAndIsRecovered() = runTest {
        repo().createTrip("trip-1", "g1", "PLEOS", "ICE", rule)
        val reborn = repo()                                                   // new process, same database
        val rec = reborn.active()
        assertNotNull(rec); assertEquals("trip-1", rec!!.id); assertEquals("INVITING", rec.state)
    }

    @Test fun startAndEndAreIdempotent() = runTest {
        val r = repo()
        r.createTrip("trip-1", "g1", "PLEOS", "ICE", rule); r.markReady("trip-1")
        r.start("trip-1", odo(100.0)); r.start("trip-1", odo(100.0))
        assertEquals(1, outbox.rows.count { it.path.endsWith("/start") })
        assertEquals(100.0, trips.trips["trip-1"]!!.odometerStartKm!!, 0.0)
        r.end("trip-1", odo(124.0)); r.end("trip-1", odo(124.0))
        assertEquals(1, outbox.rows.count { it.path.endsWith("/end") })
        assertEquals("ENDED", trips.trips["trip-1"]!!.state)
        assertNull(trips.active())
    }

    @Test fun readingsAreOnlyStoredWhileInProgressAndKeepFailures() = runTest {
        val r = repo()
        r.createTrip("trip-1", "g1", "PLEOS", "ICE", rule)
        r.recordReading("trip-1", odo(1.0))                                   // INVITING: ignored
        assertEquals(0, outbox.readingCount("trip-1"))
        r.markReady("trip-1"); r.start("trip-1", odo(100.0))
        r.recordReading("trip-1", odo(101.5)); r.recordReading("trip-1", odo(null, Quality.UNAVAILABLE))
        assertEquals(2, outbox.readingCount("trip-1"))
        assertEquals(101.5, trips.trips["trip-1"]!!.lastOdometerKm!!, 0.0)    // a failed read does not move the odometer
        val failed = outbox.rows.last { it.kind == "READING" }
        assertTrue(failed.body!!.contains("\"quality\":\"UNAVAILABLE\"") && failed.body!!.contains("TIMEOUT"))
        r.end("trip-1", odo(110.0)); r.recordReading("trip-1", odo(111.0))    // after END: ignored
        assertEquals(2, outbox.readingCount("trip-1"))
    }

    @Test fun uploadedReadingCarriesRawValueUnitSourceAndUtcTime() {
        val s = VehicleSample(SignalType.FUEL_ECONOMY, 6.2, "L/100km", 1_762_041_600_000L, DataSource.PLEOS, Quality.VERIFIED, "2.3.2")
        val ri = TripRepository.toReading(s, "e1")
        assertEquals(6.2, ri.value!!, 0.0); assertEquals("L/100km", ri.unit); assertEquals("PLEOS", ri.source)
        assertEquals("2025-11-02T00:00:00Z", ri.capturedAt)
        assertEquals("2.3.2", ri.sdkVersion)
    }

    @Test fun cancelIsRecordedOnceAndFreesTheSlot() = runTest {
        val r = repo()
        r.createTrip("trip-1", "g1", "PLEOS", "ICE", rule)
        r.cancel("trip-1"); r.cancel("trip-1")
        assertEquals(1, outbox.rows.count { it.path.endsWith("/cancel") })
        assertNull(trips.active())
        assertNotEquals("trip-1", r.createTrip("trip-2", "g1", "PLEOS", "ICE", rule).id.takeIf { false })
        assertEquals("trip-2", trips.active()!!.id)
    }
}

class ThrottleTest {
    @Test fun allowsOneReadingPerInterval() {
        val th = Throttle(5_000)
        assertTrue(th.shouldRecord(0)); assertTrue(!th.shouldRecord(4_999)); assertTrue(th.shouldRecord(5_000))
    }
}

class RecoveryAndRecorderTest {
    private val trips = FakeTripDao()
    private val outbox = FakeOutboxDao()
    private var t = 10_000L
    private val rule = CostRuleIn(vehicleType = "ICE", efficiencyValue = "12", efficiencyUnit = "km/L", unitPriceKrw = "1800")
    private fun odo(km: Double) = VehicleSample(SignalType.ODOMETER, km, "km", t, DataSource.PLEOS, Quality.VERIFIED, "2.3.2")

    @Test fun anEndedTripWaitingForSettlementIsStillRecoveredAfterARestart() = runTest {
        val r = TripRepository(trips, outbox, { t++ }, { "x" })
        r.createTrip("trip-1", "g1", "PLEOS", "ICE", rule); r.markReady("trip-1"); r.start("trip-1", odo(1.0)); r.end("trip-1", odo(25.0))
        assertNull(r.active())                                               // not recording, so a new trip may be created
        assertEquals("ENDED", TripRepository(trips, outbox, { t++ }).open()!!.state)   // but the settlement is still reachable
        r.close("trip-1", confirmed = false)
        assertNull(r.open())                                                 // closed locally: no longer offered
        assertEquals("CLOSED", trips.trips["trip-1"]!!.state)
    }

    @Test fun recorderReadsOnAHeartbeatSoParkedCarsDoNotGoStale() = runTest {
        var now = 0L
        val repo = TripRepository(trips, outbox, { now }, { "ev-$now" })
        repo.createTrip("trip-1", "g1", "PLEOS", "ICE", rule); repo.markReady("trip-1"); repo.start("trip-1", odo(100.0))
        val silent = object : io.moata.pleos.core.vehicle.VehicleDataSource {          // listener never fires (value unchanged)
            override val source = DataSource.PLEOS
            override suspend fun connect() = Result.success(Unit)
            override suspend fun read(type: SignalType) = odo(100.0).copy(capturedAtEpochMs = now)
            override fun observeOdometer() = kotlinx.coroutines.flow.emptyFlow<VehicleSample>()
            override suspend fun disconnect() = Unit
        }
        val rec = io.moata.pleos.domain.TripRecorder(repo, backgroundScope, minIntervalMs = 5_000, heartbeatMs = 15_000, now = { now })
        rec.start("trip-1", silent)
        rec.start("trip-1", silent)                                           // a second start must not double-subscribe
        for (i in 1..4) { now += 15_000; advanceTimeBy(15_000); runCurrent() }
        assertEquals(4, outbox.readingCount("trip-1"))
        rec.stop()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, outbox.readingCount("trip-1"))                        // nothing recorded after stop()
    }
}
