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
import io.moata.pleos.data.remote.FuelPriceOut
import io.moata.pleos.data.remote.GroupCreateIn
import io.moata.pleos.data.remote.GroupOut
import io.moata.pleos.data.remote.GuestAuthIn
import io.moata.pleos.data.remote.HistoryItem
import io.moata.pleos.data.remote.InviteCreateIn
import io.moata.pleos.data.remote.InviteOut
import io.moata.pleos.data.remote.ReadingBatchIn
import io.moata.pleos.data.remote.ReadingBatchOut
import io.moata.pleos.data.remote.ReadingIn
import io.moata.pleos.data.remote.TollEstimateIn
import io.moata.pleos.data.remote.TollEstimateOut
import io.moata.pleos.data.remote.TollPointIn
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





@OptIn(ExperimentalSerializationApi::class)
class ContractTest {
    private val strict = Json { namingStrategy = JsonNamingStrategy.SnakeCase; ignoreUnknownKeys = false; explicitNulls = false }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/contract/$name")!!.bufferedReader().readText()

    @Test fun authGroupInviteBatchError() {
        assertEquals("운전자", strict.decodeFromString<AuthOut>(fixture("auth_out.json")).nickname)
        val g = strict.decodeFromString<GroupOut>(fixture("group_out.json"))
        assertEquals(listOf("ACTIVE", "PENDING"), g.members.map { it.status })
        val inv = strict.decodeFromString<InviteOut>(fixture("invite_out.json"))
        assertTrue(inv.joinPath.startsWith("/join#"))
        val b = strict.decodeFromString<ReadingBatchOut>(fixture("reading_batch_out.json"))
        assertEquals(1, b.rejected.size); assertEquals("UNIT_UNKNOWN", b.rejected[0].code)
        assertEquals("STATE_CONFLICT", strict.decodeFromString<ErrorOut>(fixture("error_out.json")).code)
    }

    @Test fun tripSummaryWithSettlementDecodesCompletely() {
        val t = strict.decodeFromString<TripSummary>(fixture("trip_summary.json"))
        assertEquals("REVIEW", t.state); assertEquals("DEMO", t.dataMode); assertEquals(17, t.revision)
        assertEquals("24", t.distanceKm)
        assertEquals("12", t.costRule!!.efficiencyKmPerUnit)
        assertEquals("ESTIMATE", t.costRule!!.efficiencySource); assertEquals("GASOLINE", t.costRule!!.fuelType)
        assertNull(t.costRule!!.efficiencyValue)
        assertEquals("OPINET", t.costRule!!.unitPriceSource)
        val item = t.costItems.single()
        assertEquals(2400, item.amountKrw)
        assertEquals("서울TG→수원IC", item.roadName); assertTrue(item.estimated); assertEquals("ESTIMATE", item.inputSource)
        val iv = t.participants[1].intervals.single()
        assertEquals("VERIFIED", iv.boundaryQuality); assertEquals("USER", iv.closedBy)
        val s = t.settlement!!
        assertEquals(6000, s.totalKrw)
        assertEquals(s.totalKrw, s.shares.sumOf { it.totalKrw })
        assertEquals(s.energyTotalKrw, s.shares.sumOf { it.energyKrw })
        assertEquals("DISPUTED", s.shares[1].approval); assertEquals("하차 위치가 달라요", s.shares[1].comment)
        assertNull(s.shares[0].comment)
        assertTrue(s.equation.contains("1,800원/L"))
        assertEquals("1", s.engineVersion)
    }

    @Test fun characterHistoryCoupons() {
        val c = strict.decodeFromString<CharacterOut>(fixture("character_out.json"))
        assertEquals(2, c.level); assertEquals(70, c.nextLevelXp); assertEquals("PROUD", c.mood)
        assertEquals(listOf("FIRST_RIDE", "TRIO", "FIVE_TRIPS", "CLEAN_SPLIT"), c.allBadges.map { it.id })
        assertEquals(listOf(true, false, false, false), c.allBadges.map { it.earned })
        assertNull(c.allBadges[1].earnedAt); assertEquals("2026-11-02T03:00:00Z", c.allBadges[0].earnedAt)
        assertEquals(c.badges.map { it.id }, c.allBadges.filter { it.earned }.map { it.id })
        val h = strict.decodeFromString(ListSerializer(HistoryItem.serializer()), fixture("history.json"))
        assertEquals(listOf(6000, null), h.map { it.totalKrw }); assertNull(h[1].startedAt)
        val k = strict.decodeFromString(ListSerializer(CouponOut.serializer()), fixture("coupons.json")).single()
        assertFalse(k.exchangeable); assertTrue(k.disclaimerKo.contains("교환"))
    }

    @Test fun fuelPriceAndTollEstimateDecodeStrictly() {
        val p = strict.decodeFromString<FuelPriceOut>(fixture("fuel_price_out.json"))
        assertEquals(1855, p.priceKrw); assertEquals("OPINET", p.source); assertEquals(-0.04, p.diffKrw!!, 1e-9)
        val e = strict.decodeFromString<TollEstimateOut>(fixture("toll_estimate_out.json"))
        assertEquals(listOf("HIGHWAY", "ZONE"), e.segments.map { it.kind })
        assertEquals(e.totalKrw, e.segments.sumOf { it.amountKrw })
        assertTrue(e.dataAvailable); assertEquals(listOf("대전IC"), e.unmatchedGates)
        assertTrue(e.incomplete)
        assertEquals(listOf("서울TG", "수원IC"), e.passes.map { it.name })
        assertEquals(37.26, e.passes.last().lat, 1e-9)
    }

    @Test fun appToleratesAdditiveServerFields() {
        val withExtra = fixture("auth_out.json").replace("}", ""","new_field":1}""")
        assertNotNull(ApiJson.decodeFromString<AuthOut>(withExtra))
    }


    private fun keys(json: String) = Json.parseToJsonElement(json).jsonObject.keys

    @Test fun requestBodiesUseExactlyTheServerFieldNames() {
        val reading = ReadingIn("ev-0001-aaaa", "FUEL_ECONOMY", 6.2, "L/100km", "2026-11-02T01:02:03Z", "PLEOS", "VERIFIED", "2.3.2", null)
        assertEquals(setOf("event_id", "type", "value", "unit", "captured_at", "source", "quality", "sdk_version"), keys(ApiJson.encodeToString(reading)))
        val unavailable = reading.copy(value = null, quality = "UNAVAILABLE", error = "TIMEOUT: no callback")
        assertEquals(setOf("event_id", "type", "unit", "captured_at", "source", "quality", "sdk_version", "error"), keys(ApiJson.encodeToString(unavailable)))
        assertEquals(setOf("readings"), keys(ApiJson.encodeToString(ReadingBatchIn(listOf(reading)))))
        assertEquals(setOf("odometer"), keys(ApiJson.encodeToString(TripStartIn(reading))))
        val rule = CostRuleIn(vehicleType = "ICE", fuelType = "GASOLINE", unitPriceKrw = "1800")
        assertEquals(setOf("vehicle_type", "fuel_type", "efficiency_source", "unit_price_krw", "unit_price_source", "driver_included", "energy_policy"), keys(ApiJson.encodeToString(rule)))
        assertEquals(setOf("id", "group_id", "vehicle_alias", "data_mode", "cost_rule"), keys(ApiJson.encodeToString(TripCreateIn("t", "g", "", "PLEOS", rule))))
        assertEquals(setOf("id", "kind", "amount_krw", "allocation", "note", "road_name", "estimated"), keys(ApiJson.encodeToString(CostItemIn("i", "TOLL", 2400, "EQUAL", ""))))
        val track = TollEstimateIn(listOf(TollPointIn(37.5, 127.0, "2026-11-12T00:00:00Z")), 1, 2)
        assertEquals(setOf("points", "vehicle_class", "occupants"), keys(ApiJson.encodeToString(track)))
        assertEquals(setOf("lat", "lon", "captured_at"), keys(ApiJson.encodeToString(track.points.single())))
        assertEquals(setOf("points", "vehicle_class"), keys(ApiJson.encodeToString(track.copy(occupants = null))))
        assertEquals(setOf("decision", "comment"), keys(ApiJson.encodeToString(ApprovalIn("APPROVE"))))
        assertEquals(setOf("nickname", "consent_version"), keys(ApiJson.encodeToString(GuestAuthIn("a", "v1"))))
        assertEquals(setOf("id", "name"), keys(ApiJson.encodeToString(GroupCreateIn("g", "n"))))
        assertEquals(setOf("trip_id", "ttl_minutes", "max_uses"), keys(ApiJson.encodeToString(InviteCreateIn("t"))))
        assertEquals(setOf("reason"), keys(ApiJson.encodeToString(BoardingIn())))
    }

    @Test fun unitPriceIsSentAsAStringAndNoEfficiencyNumberIsEverSent() {
        val rule = CostRuleIn(vehicleType = "ICE", fuelType = "DIESEL", unitPriceKrw = "1799.5")
        val o = Json.parseToJsonElement(ApiJson.encodeToString(rule)).jsonObject
        assertEquals("\"1799.5\"", o["unit_price_krw"].toString())
        assertEquals("\"ESTIMATE\"", o["efficiency_source"].toString()); assertEquals("\"DIESEL\"", o["fuel_type"].toString())
        assertFalse(o.containsKey("efficiency_value")); assertFalse(o.containsKey("efficiency_unit"))
    }
}
