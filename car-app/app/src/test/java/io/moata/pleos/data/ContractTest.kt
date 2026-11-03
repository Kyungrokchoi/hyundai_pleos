package io.moata.pleos.data

import io.moata.pleos.data.remote.ApiJson
import io.moata.pleos.data.remote.ApprovalIn
import io.moata.pleos.data.remote.AuthOut
import io.moata.pleos.data.remote.BoardingIn
import io.moata.pleos.data.remote.CharacterOut
import io.moata.pleos.data.remote.CostItemIn
import io.moata.pleos.data.remote.CostRuleIn
import io.moata.pleos.data.remote.CouponOut
import io.moata.pleos.data.remote.ErrorOut
import io.moata.pleos.data.remote.GroupCreateIn
import io.moata.pleos.data.remote.GroupOut
import io.moata.pleos.data.remote.GuestAuthIn
import io.moata.pleos.data.remote.HistoryItem
import io.moata.pleos.data.remote.InviteCreateIn
import io.moata.pleos.data.remote.InviteOut
import io.moata.pleos.data.remote.ReadingBatchIn
import io.moata.pleos.data.remote.ReadingBatchOut
import io.moata.pleos.data.remote.ReadingIn
import io.moata.pleos.data.remote.TripCreateIn
import io.moata.pleos.data.remote.TripStartIn
import io.moata.pleos.data.remote.TripSummary
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures under src/test/resources/contract mirror backend/app/schemas.py. Decoding is strict, so a server field
 * the DTOs don't know breaks the build instead of being silently ignored.
 */
@OptIn(ExperimentalSerializationApi::class)
class ContractTest {
    private val strict = Json { namingStrategy = JsonNamingStrategy.SnakeCase; ignoreUnknownKeys = false; explicitNulls = false }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/contract/$name")!!.bufferedReader().readText()

    @Test fun authGroupInviteBatchError() {
        assertEquals("운전자", strict.decodeFromString<AuthOut>(fixture("auth_out.json")).nickname)
        val g = strict.decodeFromString<GroupOut>(fixture("group_out.json"))
        assertEquals(listOf("ACTIVE", "PENDING"), g.members.map { it.status })
        val inv = strict.decodeFromString<InviteOut>(fixture("invite_out.json"))
        assertTrue(inv.joinPath.startsWith("/join#"))                 // token travels in the fragment, not the query
        val b = strict.decodeFromString<ReadingBatchOut>(fixture("reading_batch_out.json"))
        assertEquals(1, b.rejected.size); assertEquals("UNIT_UNKNOWN", b.rejected[0].code)
        assertEquals("STATE_CONFLICT", strict.decodeFromString<ErrorOut>(fixture("error_out.json")).code)
    }

    @Test fun tripSummaryWithSettlementDecodesCompletely() {
        val t = strict.decodeFromString<TripSummary>(fixture("trip_summary.json"))
        assertEquals("REVIEW", t.state); assertEquals("DEMO", t.dataMode); assertEquals(17, t.revision)
        assertEquals("24", t.distanceKm)
        assertEquals("12", t.costRule!!.efficiencyKmPerUnit)
        assertEquals(2400, t.costItems.single().amountKrw)
        val iv = t.participants[1].intervals.single()
        assertEquals("VERIFIED", iv.boundaryQuality); assertEquals("USER", iv.closedBy)
        val s = t.settlement!!
        assertEquals(6000, s.totalKrw)
        assertEquals(s.totalKrw, s.shares.sumOf { it.totalKrw })           // shares conserve the total
        assertEquals(s.energyTotalKrw, s.shares.sumOf { it.energyKrw })
        assertEquals("DISPUTED", s.shares[1].approval); assertEquals("하차 위치가 달라요", s.shares[1].comment)
        assertNull(s.shares[0].comment)
        assertTrue(s.equation.contains("1,800원/L"))
        assertEquals("1", s.engineVersion)
    }

    @Test fun characterHistoryCoupons() {
        val c = strict.decodeFromString<CharacterOut>(fixture("character_out.json"))
        assertEquals(2, c.level); assertEquals(70, c.nextLevelXp); assertEquals("PROUD", c.mood)
        val h = strict.decodeFromString(ListSerializer(HistoryItem.serializer()), fixture("history.json"))
        assertEquals(listOf(6000, null), h.map { it.totalKrw }); assertNull(h[1].startedAt)
        val k = strict.decodeFromString(ListSerializer(CouponOut.serializer()), fixture("coupons.json")).single()
        assertFalse(k.exchangeable); assertTrue(k.disclaimerKo.contains("교환"))
    }

    @Test fun appToleratesAdditiveServerFields() {
        val withExtra = fixture("auth_out.json").replace("}", ""","new_field":1}""")
        assertNotNull(ApiJson.decodeFromString<AuthOut>(withExtra))
    }

    // request bodies may only use fields the server model knows (extra="forbid")
    private fun keys(json: String) = Json.parseToJsonElement(json).jsonObject.keys

    @Test fun requestBodiesUseExactlyTheServerFieldNames() {
        val reading = ReadingIn("ev-0001-aaaa", "FUEL_ECONOMY", 6.2, "L/100km", "2026-11-02T01:02:03Z", "PLEOS", "VERIFIED", "2.3.2", null)
        assertEquals(setOf("event_id", "type", "value", "unit", "captured_at", "source", "quality", "sdk_version"), keys(ApiJson.encodeToString(reading)))
        val unavailable = reading.copy(value = null, quality = "UNAVAILABLE", error = "TIMEOUT: no callback")
        assertEquals(setOf("event_id", "type", "unit", "captured_at", "source", "quality", "sdk_version", "error"), keys(ApiJson.encodeToString(unavailable)))
        assertEquals(setOf("readings"), keys(ApiJson.encodeToString(ReadingBatchIn(listOf(reading)))))
        assertEquals(setOf("odometer"), keys(ApiJson.encodeToString(TripStartIn(reading))))
        val rule = CostRuleIn("ICE", "6.5", "L/100km", "PLEOS", "1800", true, "SEGMENT_SHARED")
        assertEquals(setOf("vehicle_type", "efficiency_value", "efficiency_unit", "efficiency_source", "unit_price_krw", "driver_included", "energy_policy"), keys(ApiJson.encodeToString(rule)))
        assertEquals(setOf("id", "group_id", "vehicle_alias", "data_mode", "cost_rule"), keys(ApiJson.encodeToString(TripCreateIn("t", "g", "", "PLEOS", rule))))
        assertEquals(setOf("id", "kind", "amount_krw", "allocation", "note"), keys(ApiJson.encodeToString(CostItemIn("i", "TOLL", 2400, "EQUAL", ""))))
        assertEquals(setOf("decision", "comment"), keys(ApiJson.encodeToString(ApprovalIn("APPROVE"))))
        assertEquals(setOf("nickname", "consent_version"), keys(ApiJson.encodeToString(GuestAuthIn("a", "v1"))))
        assertEquals(setOf("id", "name"), keys(ApiJson.encodeToString(GroupCreateIn("g", "n"))))
        assertEquals(setOf("trip_id", "ttl_minutes", "max_uses"), keys(ApiJson.encodeToString(InviteCreateIn("t"))))
        assertEquals(setOf("reason"), keys(ApiJson.encodeToString(BoardingIn())))
    }

    @Test fun unitPriceAndEfficiencyAreSentAsStringsNotFloats() {
        val rule = CostRuleIn("ICE", "6.50", "L/100km", "MANUAL", "1799.5")
        val o = Json.parseToJsonElement(ApiJson.encodeToString(rule)).jsonObject
        assertEquals("\"1799.5\"", o["unit_price_krw"].toString()); assertEquals("\"6.50\"", o["efficiency_value"].toString())
    }
}
