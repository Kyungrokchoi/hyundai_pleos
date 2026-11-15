package io.moata.pleos.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy


@OptIn(ExperimentalSerializationApi::class)
val ApiJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable data class GuestAuthIn(val nickname: String, val consentVersion: String)
@Serializable data class GroupCreateIn(val id: String, val name: String)
@Serializable data class InviteCreateIn(val tripId: String? = null, val ttlMinutes: Int = 30, val maxUses: Int = 5)
@Serializable data class MemberDecisionIn(val decision: String)

@Serializable data class CostRuleIn(
    val vehicleType: String,
    val fuelType: String,
    val efficiencySource: String = "ESTIMATE",
    val unitPriceKrw: String? = null,
    val driverIncluded: Boolean = true,
    val energyPolicy: String = "SEGMENT_SHARED",
    val unitPriceSource: String = "MANUAL",
)
@Serializable data class TripCreateIn(
    val id: String, val groupId: String, val vehicleAlias: String = "", val dataMode: String, val costRule: CostRuleIn,
)
@Serializable data class ReadingIn(
    val eventId: String, val type: String, val value: Double? = null, val unit: String, val capturedAt: String,
    val source: String, val quality: String, val sdkVersion: String? = null, val error: String? = null,
)
@Serializable data class ReadingBatchIn(val readings: List<ReadingIn>)
@Serializable data class TripStartIn(val odometer: ReadingIn)
@Serializable data class TripEndIn(val odometer: ReadingIn)
@Serializable data class BoardingIn(val odometerKm: String? = null, val reason: String = "")
@Serializable data class CostItemIn(
    val id: String, val kind: String, val amountKrw: Int, val allocation: String = "EQUAL", val note: String = "",
    val roadName: String = "", val estimated: Boolean = false,
)
@Serializable data class TollPointIn(val lat: Double, val lon: Double, val capturedAt: String)
@Serializable data class TollEstimateIn(val points: List<TollPointIn>, val vehicleClass: Int = 1, val occupants: Int? = null)
@Serializable data class ApprovalIn(val decision: String, val comment: String = "")

@Serializable data class AuthOut(val userId: String, val nickname: String, val token: String, val expiresAt: String)
@Serializable data class MemberOut(val userId: String, val nickname: String, val role: String, val status: String)
@Serializable data class GroupOut(val id: String, val name: String, val ownerUserId: String, val createdAt: String, val members: List<MemberOut> = emptyList())
@Serializable data class InviteOut(val inviteId: String, val token: String, val joinPath: String, val expiresAt: String, val maxUses: Int)
@Serializable data class ReadingRejected(val eventId: String, val code: String, val messageKo: String)
@Serializable data class ReadingBatchOut(val accepted: Int, val duplicates: Int, val rejected: List<ReadingRejected> = emptyList())
@Serializable data class Issue(val code: String, val messageKo: String)

@Serializable data class IntervalOut(
    val id: String, val userId: String, val onboardAt: String? = null, val offboardAt: String? = null,
    val odometerOnKm: String? = null, val odometerOffKm: String? = null, val boundaryQuality: String, val closedBy: String? = null,
    val openedBy: String? = null,
)
@Serializable data class ParticipantOut(
    val userId: String, val nickname: String, val isDriver: Boolean, val memberStatus: String, val onBoard: Boolean,
    val intervals: List<IntervalOut> = emptyList(),
)
@Serializable data class LatestOdometerOut(val km: String, val capturedAt: String, val source: String, val quality: String, val ageSeconds: Int)
@Serializable data class CostRuleOut(
    val vehicleType: String, val efficiencyValue: String? = null, val efficiencyUnit: String? = null,
    val efficiencyKmPerUnit: String? = null, val efficiencySource: String, val fuelType: String? = null, val unitPriceKrw: String? = null,
    val unitPriceSource: String = "MANUAL",
    val driverIncluded: Boolean, val energyPolicy: String,
)
@Serializable data class CostItemOut(
    val id: String, val kind: String, val amountKrw: Int, val allocation: String, val note: String,
    val roadName: String = "", val estimated: Boolean = false, val inputSource: String = "MANUAL", val createdBy: String,
)
@Serializable data class FuelPriceOut(
    val fuel: String, val region: String, val priceKrw: Int, val diffKrw: Double? = null, val fetchedAt: String,
    val cached: Boolean = false, val source: String = "OPINET", val attribution: String = "",
)
@Serializable data class PlaceOut(val address: String, val short: String)
@Serializable data class TollSegmentOut(val kind: String, val roadName: String, val amountKrw: Int)
@Serializable data class TollPassOut(val name: String, val lat: Double, val lon: Double)
@Serializable data class TollEstimateOut(
    val segments: List<TollSegmentOut> = emptyList(), val passes: List<TollPassOut> = emptyList(),
    val totalKrw: Int = 0, val unmatchedGates: List<String> = emptyList(),
    val dataAvailable: Boolean = false, val incomplete: Boolean = false, val estimated: Boolean = true, val attribution: String = "",
)
@Serializable data class ShareLineOut(val itemId: String, val label: String, val amountKrw: Int)
@Serializable data class SegmentOut(val index: Int, val startKm: String, val endKm: String, val lengthKm: String, val payers: List<String>, val energyKrw: Int)
@Serializable data class ShareOut(
    val userId: String, val nickname: String, val isDriver: Boolean, val totalKrw: Int, val energyKrw: Int,
    val items: List<ShareLineOut> = emptyList(), val riddenKm: String, val owedToDriverKrw: Int, val approval: String, val comment: String? = null,
)
@Serializable data class SettlementOut(
    val id: String, val tripId: String, val revision: Int, val state: String, val totalKrw: Int, val energyTotalKrw: Int,
    val itemsTotalKrw: Int, val distanceKm: String, val consumption: String, val consumptionUnit: String, val equation: String,
    val efficiencySource: String, val driverIncluded: Boolean, val energyPolicy: String,
    val segments: List<SegmentOut> = emptyList(), val shares: List<ShareOut> = emptyList(),
    val warnings: List<Issue> = emptyList(), val disclaimers: List<String> = emptyList(), val createdAt: String, val engineVersion: String,
)
@Serializable data class TripSummary(
    val id: String, val groupId: String, val groupName: String, val state: String, val dataMode: String, val vehicleAlias: String,
    val driverUserId: String, val revision: Int, val startedAt: String? = null, val endedAt: String? = null,
    val odometerStartKm: String? = null, val odometerEndKm: String? = null, val distanceKm: String? = null,
    val latestOdometer: LatestOdometerOut? = null, val costRule: CostRuleOut? = null,
    val costItems: List<CostItemOut> = emptyList(), val participants: List<ParticipantOut> = emptyList(),
    val settlement: SettlementOut? = null, val issues: List<Issue> = emptyList(), val disclaimers: List<String> = emptyList(),
)
@Serializable data class BadgeOut(val id: String, val nameKo: String, val descriptionKo: String, val earnedAt: String)

@Serializable data class BadgeStatusOut(val id: String, val nameKo: String, val descriptionKo: String, val earned: Boolean, val earnedAt: String? = null)
@Serializable data class CharacterOut(
    val groupId: String, val name: String = "모아", val xp: Int, val level: Int, val levelXpStart: Int, val nextLevelXp: Int? = null,
    val skinId: String, val mood: String, val badges: List<BadgeOut> = emptyList(), val allBadges: List<BadgeStatusOut> = emptyList(),
    val completedTrips: Int, val noteKo: String = "",
)
@Serializable data class HistoryItem(
    val tripId: String, val startedAt: String? = null, val state: String, val dataMode: String, val distanceKm: String? = null,
    val totalKrw: Int? = null, val participantCount: Int, val xpGained: Int,
)
@Serializable data class CouponOut(val id: String, val brandLabel: String, val status: String, val exchangeable: Boolean = false, val disclaimerKo: String = "")
@Serializable data class ErrorOut(val code: String, val messageKo: String, val detail: String? = null)
