from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from enum import Enum
import unicodedata
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, PlainSerializer, StringConstraints

from app.services.cost_engine import Allocation, DataSource, EfficiencySource, EnergyPolicy, VehicleType  # noqa: F401

Dec = Annotated[Decimal, PlainSerializer(lambda d: format(d, "f"), return_type=str, when_used="json")]
Id = Annotated[str, StringConstraints(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")]


def _clean_nickname(v: str) -> str:
    v = unicodedata.normalize("NFKC", v).strip()
    if any(unicodedata.category(c) in {"Cc", "Cf", "Cs", "Co", "Cn", "Zl", "Zp"} for c in v) or not any(c.isalnum() for c in v):
        raise ValueError("nickname has invalid characters")
    return v


Nickname = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=20), AfterValidator(_clean_nickname)]
Krw = Annotated[int, Field(ge=0, le=100_000_000)]


class Api(BaseModel):
    model_config = ConfigDict(extra="forbid", from_attributes=True)


class TripState(str, Enum):
    DRAFT = "DRAFT"
    INVITING = "INVITING"
    READY = "READY"
    IN_PROGRESS = "IN_PROGRESS"
    ENDED = "ENDED"
    REVIEW = "REVIEW"
    CONFIRMED = "CONFIRMED"
    INTERRUPTED = "INTERRUPTED"
    CANCELLED = "CANCELLED"
    DISPUTED = "DISPUTED"


class MemberStatus(str, Enum):
    PENDING = "PENDING"
    ACTIVE = "ACTIVE"
    REJECTED = "REJECTED"
    LEFT = "LEFT"


class ReadingType(str, Enum):
    ODOMETER = "ODOMETER"
    FUEL_ECONOMY = "FUEL_ECONOMY"
    EV_EFFICIENCY = "EV_EFFICIENCY"
    EV_SOC = "EV_SOC"


class Quality(str, Enum):
    VERIFIED = "VERIFIED"
    ESTIMATED = "ESTIMATED"
    UNAVAILABLE = "UNAVAILABLE"
    INVALID = "INVALID"


class ItemKind(str, Enum):
    TOLL = "TOLL"
    PARKING = "PARKING"
    OTHER = "OTHER"


class SettlementState(str, Enum):
    PROPOSED = "PROPOSED"
    CONFIRMED = "CONFIRMED"
    DISPUTED = "DISPUTED"
    SUPERSEDED = "SUPERSEDED"


class Decision(str, Enum):
    APPROVE = "APPROVE"
    DISPUTE = "DISPUTE"


class ApprovalState(str, Enum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    DISPUTED = "DISPUTED"


class GuestAuthIn(Api):
    nickname: Nickname
    consent_version: Annotated[str, StringConstraints(min_length=1, max_length=20)]


class AuthOut(Api):
    user_id: str
    nickname: str
    token: str = Field(description="Opaque bearer token. Shown once; only its hash is stored.")
    expires_at: datetime


class GroupCreateIn(Api):
    id: Id | None = Field(None, description="Client-generated id makes the call idempotent.")
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=30)]


class MemberOut(Api):
    user_id: str
    nickname: str
    role: Literal["OWNER", "MEMBER"]
    status: MemberStatus


class GroupOut(Api):
    id: str
    name: str
    owner_user_id: str
    created_at: datetime
    members: list[MemberOut] = []


class InviteCreateIn(Api):
    trip_id: Id | None = None
    ttl_minutes: Annotated[int, Field(ge=1, le=24 * 60)] = 30
    max_uses: Annotated[int, Field(ge=1, le=20)] = 5


class InviteOut(Api):
    invite_id: str
    token: str = Field(description="Plain token, returned only at creation. Put it in the URL fragment (#), not the query.")
    join_path: str = Field(description="Path for the passenger web, e.g. /join#<token>")
    expires_at: datetime
    max_uses: int


class InviteTokenIn(Api):
    token: Annotated[str, StringConstraints(min_length=16, max_length=128)]


class InvitePreviewOut(Api):
    group_name: str
    driver_nickname: str
    trip_id: str | None
    rules_ko: list[str] = Field(description="Cost-sharing rules the joiner must acknowledge.")
    disclaimers: list[str]
    expires_at: datetime


class InviteAcceptIn(InviteTokenIn):
    nickname: Nickname | None = Field(None, description="Required when the caller is not signed in.")
    consent_version: Annotated[str, StringConstraints(min_length=1, max_length=20)]
    rules_ack: bool = Field(description="Must be true: the joiner saw and accepted the cost rules.")


class InviteAcceptOut(Api):
    user_id: str
    token: str | None = Field(None, description="Bearer token for a newly created guest; null when the caller was already signed in.")
    group_id: str
    trip_id: str | None
    member_status: MemberStatus


class MembershipOut(Api):
    group_id: str
    user_id: str
    role: Literal["OWNER", "MEMBER"]
    status: MemberStatus
    current_trip_id: str | None = Field(None, description="The group's newest trip that is not finished (ACTIVE members only): where a returning rider belongs today.")


class MemberDecisionIn(Api):
    decision: Literal["APPROVE", "REJECT"]


FuelType = Literal["GASOLINE", "DIESEL", "LPG", "ELECTRIC"]


class CostRuleIn(Api):
    vehicle_type: VehicleType
    efficiency_value: Dec | None = Field(None, gt=0, le=100_000, description="Number in `efficiency_unit`. Only for MANUAL.")
    efficiency_unit: Literal["km/L", "L/100km", "km/kWh", "Wh/km", "kWh/100km"] | None = None
    efficiency_source: EfficiencySource = Field(
        EfficiencySource.MANUAL, description="ESTIMATE = nothing is typed in: the car's own reading is used if it reports one, otherwise the rated "
                                            "average for `fuel_type`. PLEOS/DEMO = readings only. MANUAL = `efficiency_value` is required.")
    fuel_type: FuelType | None = Field(None, description="Required for ESTIMATE; ELECTRIC if and only if vehicle_type is EV.")
    unit_price_krw: Dec | None = Field(None, ge=0, le=100_000, description="KRW per litre (ICE) or per kWh (EV).")
    unit_price_source: Literal["MANUAL", "OPINET"] = Field("MANUAL", description="OPINET = filled from GET /fuel-price, MANUAL = typed in.")
    driver_included: bool = True
    energy_policy: EnergyPolicy = EnergyPolicy.SEGMENT_SHARED


class CostRuleOut(Api):
    vehicle_type: VehicleType
    efficiency_value: Dec | None
    efficiency_unit: str | None
    efficiency_km_per_unit: Dec | None = Field(description="Normalised km/L (ICE) or km/kWh (EV) actually used.")
    efficiency_source: EfficiencySource = Field(description="Source of the figure actually used (a reading from the car wins over the rated average).")
    fuel_type: FuelType | None = None
    unit_price_krw: Dec | None
    unit_price_source: Literal["MANUAL", "OPINET"] = "MANUAL"
    driver_included: bool
    energy_policy: EnergyPolicy


class CostItemIn(Api):
    id: Id
    kind: ItemKind
    amount_krw: Krw
    allocation: Allocation = Allocation.EQUAL
    note: Annotated[str, StringConstraints(max_length=60)] = ""
    road_name: Annotated[str, StringConstraints(max_length=40)] = Field("", description="Road / tunnel the charge belongs to, e.g. `서울→수원`.")
    estimated: bool = Field(False, description="True when the amount was computed (toll estimate), not read off a receipt.")


class CostItemOut(Api):
    id: str
    kind: ItemKind
    amount_krw: int
    allocation: Allocation
    note: str
    road_name: str = ""
    estimated: bool = False
    input_source: Literal["MANUAL", "ESTIMATE"] = "MANUAL"
    created_by: str


class TollPointIn(Api):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    captured_at: datetime


class TollEstimateIn(Api):
    points: list[TollPointIn] = Field(min_length=1, max_length=5000, description="GPS track in time order.")
    vehicle_class: int = Field(1, ge=1, le=8, description="Korea Expressway Corp class: 1 = passenger car.")
    occupants: int | None = Field(None, ge=1, le=20, description="People in the car; used for occupancy-exempt city zones.")


class TollSegmentOut(Api):
    kind: Literal["HIGHWAY", "ZONE"]
    road_name: str
    amount_krw: int


class TollPassOut(Api):
    name: str
    lat: float
    lon: float


class TollEstimateOut(Api):
    segments: list[TollSegmentOut]
    passes: list[TollPassOut] = Field(description="Toll gates the track went through, in order (public gate data, not the driver's own points).")
    total_krw: int
    unmatched_gates: list[str] = Field(description="Gates the track passed that could not be paired with a known fare.")
    data_available: bool = Field(description="False when no gate data or fare API key is configured on the server.")
    incomplete: bool = Field(False, description="True when a fare lookup failed or hit the per-request limit: the total may be too low.")
    estimated: Literal[True] = True
    attribution: str


class TripCreateIn(Api):
    id: Id
    group_id: Id
    vehicle_alias: Annotated[str, StringConstraints(max_length=20)] = ""
    data_mode: DataSource = Field(description="PLEOS = values read from the vehicle SDK, MANUAL = typed in, DEMO = simulated.")
    cost_rule: CostRuleIn


class ReadingIn(Api):
    event_id: Id = Field(description="Client-generated; repeating it never creates a second record.")
    type: ReadingType
    value: float | None = Field(None, description="null when the signal was unavailable.")
    unit: Annotated[str, StringConstraints(max_length=12)]
    captured_at: datetime
    source: DataSource
    quality: Quality
    sdk_version: Annotated[str, StringConstraints(max_length=20)] | None = None
    error: Annotated[str, StringConstraints(max_length=200)] | None = Field(None, description="SDK onFailed text, if any.")


class ReadingBatchIn(Api):
    readings: Annotated[list[ReadingIn], Field(min_length=1, max_length=500)]


class ReadingRejected(Api):
    event_id: str
    code: str
    message_ko: str


class ReadingBatchOut(Api):
    accepted: int
    duplicates: int
    rejected: list[ReadingRejected]


class TripStartIn(Api):
    odometer: ReadingIn = Field(description="First ODOMETER snapshot (type must be ODOMETER).")


class TripEndIn(Api):
    odometer: ReadingIn = Field(description="Last ODOMETER snapshot (type must be ODOMETER).")


class BoardingIn(Api):
    odometer_km: Dec | None = Field(None, ge=0, le=9_999_999, description="Driver-only override; passengers use the server's latest reading.")
    reason: Annotated[str, StringConstraints(max_length=60)] = ""
    undo: bool = Field(False, description="Alight only: the rider's '잘못 눌렀어요'. Forgets the boarding if the car has not moved since it began.")


class IntervalOut(Api):
    id: str
    user_id: str
    onboard_at: datetime | None
    offboard_at: datetime | None
    odometer_on_km: Dec | None
    odometer_off_km: Dec | None
    boundary_quality: Quality
    closed_by: Literal["USER", "DRIVER", "TRIP_END"] | None
    opened_by: Literal["USER", "DRIVER"] | None = None


class IntervalCorrectionIn(Api):
    odometer_on_km: Dec | None = Field(None, ge=0, le=9_999_999)
    odometer_off_km: Dec | None = Field(None, ge=0, le=9_999_999)
    reason: Annotated[str, StringConstraints(min_length=2, max_length=60)]


class ParticipantOut(Api):
    user_id: str
    nickname: str
    is_driver: bool
    member_status: MemberStatus
    on_board: bool
    intervals: list[IntervalOut]


class LatestOdometerOut(Api):
    km: Dec
    captured_at: datetime
    source: DataSource
    quality: Quality
    age_seconds: int


class Issue(Api):
    code: str
    message_ko: str


class ShareLineOut(Api):
    item_id: str
    label: str
    amount_krw: int


class SegmentOut(Api):
    index: int
    start_km: str
    end_km: str
    length_km: str
    payers: list[str]
    energy_krw: int


class ShareOut(Api):
    user_id: str
    nickname: str
    is_driver: bool
    total_krw: int
    energy_krw: int
    items: list[ShareLineOut]
    ridden_km: str
    owed_to_driver_krw: int
    approval: ApprovalState
    comment: str | None = None


class SettlementOut(Api):
    id: str
    trip_id: str
    revision: int
    state: SettlementState
    total_krw: int
    energy_total_krw: int
    items_total_krw: int
    distance_km: str
    consumption: str
    consumption_unit: Literal["L", "kWh"]
    equation: str
    efficiency_source: EfficiencySource
    driver_included: bool
    energy_policy: EnergyPolicy
    segments: list[SegmentOut]
    shares: list[ShareOut]
    warnings: list[Issue]
    disclaimers: list[str]
    created_at: datetime
    engine_version: str


class TripSummary(Api):
    id: str
    group_id: str
    group_name: str
    state: TripState
    data_mode: DataSource
    vehicle_alias: str
    driver_user_id: str
    revision: int = Field(description="Increases on every change; also used as the ETag.")
    started_at: datetime | None
    ended_at: datetime | None
    odometer_start_km: Dec | None
    odometer_end_km: Dec | None
    distance_km: Dec | None
    latest_odometer: LatestOdometerOut | None
    cost_rule: CostRuleOut | None
    cost_items: list[CostItemOut]
    participants: list[ParticipantOut]
    settlement: SettlementOut | None
    issues: list[Issue] = Field(description="Warnings and blocking problems with the recorded data.")
    disclaimers: list[str]


class ApprovalIn(Api):
    decision: Decision
    comment: Annotated[str, StringConstraints(max_length=100)] = ""


class BadgeOut(Api):
    id: str
    name_ko: str
    description_ko: str
    earned_at: datetime


class BadgeStatusOut(Api):
    id: str
    name_ko: str
    description_ko: str
    earned: bool
    earned_at: datetime | None = None


class CharacterOut(Api):
    group_id: str
    name: str = "모아"
    xp: int
    level: int
    level_xp_start: int
    next_level_xp: int | None
    skin_id: str
    mood: Literal["SLEEPY", "HAPPY", "PROUD"]
    badges: list[BadgeOut] = Field(description="Earned badges only, oldest first.")
    all_badges: list[BadgeStatusOut] = Field(default_factory=list, description="Every badge in catalogue order with whether the group earned it.")
    completed_trips: int
    note_ko: str = "모아는 함께 정산을 마친 이동 기록으로만 자랍니다. 거리·속도와는 관계없어요."


class HistoryItem(Api):
    trip_id: str
    started_at: datetime | None
    state: TripState
    data_mode: DataSource
    distance_km: Dec | None
    total_krw: int | None
    participant_count: int
    xp_gained: int


class CouponOut(Api):
    id: str
    brand_label: str
    status: Literal["DEMO_AVAILABLE"]
    exchangeable: Literal[False] = False
    disclaimer_ko: str = "시연용 쿠폰입니다. 실제로 교환하거나 사용할 수 없습니다."


class ErrorOut(Api):
    code: str
    message_ko: str
    detail: str | None = None
