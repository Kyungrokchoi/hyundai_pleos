from __future__ import annotations

from datetime import timedelta
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import schemas as s
from app.config import get_settings
from app.db import models as m
from app.errors import ApiError, conflict, forbidden
from app.services import cost_engine as ce
from app.services import efficiency

ACCEPTS_READINGS = {"READY", "IN_PROGRESS", "ENDED", "REVIEW", "DISPUTED"}
PRE_DEPARTURE = ("INVITING", "READY")
LOCKED = {"CONFIRMED", "CANCELLED"}
FUTURE_SKEW = timedelta(minutes=5)
MAX_READING_AGE = timedelta(days=7)
MAX_READINGS_PER_TRIP = 50_000
MAX_ITEMS_PER_TRIP = 50
MAX_ITEMS_TOTAL_KRW = 1_000_000_000
MAX_BOARDINGS_PER_USER = 20

PLAUSIBLE = {"ODOMETER": (Decimal(0), Decimal(9_999_999)), "FUEL_ECONOMY": (Decimal("0.05"), Decimal(500)),
             "EV_EFFICIENCY": (Decimal("0.05"), Decimal(100))}

DISCLAIMERS = [
    "이 금액은 입력한 연비·단가로 계산한 추정치이며 실제 연료비·전기요금과 다를 수 있습니다.",
    "MOATA는 결제·송금을 하지 않습니다. 정산은 참여자끼리 합의하는 참고용 금액입니다.",
    "동승자 간 비용 분담이 모든 경우에 적법하다고 보장하지 않습니다. 영리 목적의 운송이 되지 않도록 유의하세요.",
]
MODE_NOTICE = {
    "DEMO": "DEMO / 가상 데이터입니다. 차량에서 측정한 값이 아닙니다.",
    "MANUAL": "직접 입력한 값이 포함되어 있습니다.",
    "ESTIMATE": "연비는 차량이 알려주지 않아 한국에너지공단 표시연비 평균으로 추정했습니다.",
    "PLEOS": "운전자의 차량 앱이 차량에서 읽어 보고한 값입니다(서버가 차량에서 직접 검증하지는 못합니다).",
}


def nz(d) -> Decimal | None:
    if d is None:
        return None
    d = Decimal(d)
    return d.quantize(Decimal(1)) if d == d.to_integral_value() else d.normalize()


def bump(trip: m.Trip) -> None:
    trip.revision += 1


def lock_trip(db: Session, trip_id: str) -> m.Trip | None:
    return db.scalar(select(m.Trip).where(m.Trip.id == trip_id).with_for_update().execution_options(populate_existing=True))


def require_open(trip: m.Trip) -> None:
    if trip.state in LOCKED:
        raise conflict("TRIP_LOCKED", "이미 확정되었거나 취소된 운행은 바꿀 수 없습니다.")


def normalize_reading(r: s.ReadingIn) -> tuple[Decimal | None, str | None]:
    if r.value is None:
        return None, None
    t, u = r.type, r.unit
    if t == s.ReadingType.ODOMETER:
        v, unit = ce.distance_to_km(r.value, u), "km"
        if v < 0:
            raise ce.CostInputError("DISTANCE_INVALID")
    elif t == s.ReadingType.FUEL_ECONOMY:
        v, unit = ce.fuel_economy_to_km_per_l(r.value, u), "km/L"
    elif t == s.ReadingType.EV_EFFICIENCY:
        v, unit = ce.ev_efficiency_to_km_per_kwh(r.value, u), "km/kWh"
    else:
        v = None
    if v is not None:
        lo, hi = PLAUSIBLE[t.value]
        if not lo <= v <= hi:
            raise ce.CostInputError("VALUE_IMPLAUSIBLE", f"{t.value}={v}")
        return v, unit
    if t == s.ReadingType.EV_SOC:
        v = ce.to_decimal(r.value)
        if u != "%" or not (0 <= v <= 100):
            raise ce.CostInputError("UNIT_UNKNOWN", u)
        return v, "%"
    raise ce.CostInputError("UNIT_UNKNOWN", u)


def ingest_reading(db: Session, trip: m.Trip, r: s.ReadingIn, now, exempt_cap: bool = False) -> tuple[str, s.ReadingRejected | None]:
    def reject(code: str, msg: str):
        return "rejected", s.ReadingRejected(event_id=r.event_id, code=code, message_ko=msg)

    if trip.state not in ACCEPTS_READINGS:
        return reject("TRIP_NOT_ACCEPTING", "이 운행은 지금 판독값을 받을 수 없습니다.")
    prior = db.scalar(select(m.VehicleReading).where(m.VehicleReading.trip_id == trip.id, m.VehicleReading.event_id == r.event_id))
    if prior is not None:
        if prior.type != r.type.value:
            return reject("EVENT_ID_CONFLICT", "같은 ID로 다른 종류의 판독값이 이미 있습니다.")
        return "duplicate", None
    if getattr(trip, "_reading_total", None) is None:
        trip._reading_total = reading_count(db, trip.id)
    if trip._reading_total >= MAX_READINGS_PER_TRIP and not exempt_cap:
        return reject("TRIP_READING_LIMIT", "한 운행에 저장할 수 있는 판독값 수를 넘었습니다.")
    if r.source.value != trip.data_mode:
        return reject("SOURCE_MISMATCH", f"이 운행의 데이터 출처는 {trip.data_mode}입니다. 다른 출처의 값은 받을 수 없습니다.")
    if r.captured_at.tzinfo is None:
        return reject("TIMESTAMP_INVALID", "측정 시각에 시간대(UTC)가 없습니다.")
    if r.captured_at > now + FUTURE_SKEW:
        return reject("TIMESTAMP_FUTURE", "측정 시각이 미래입니다.")
    if r.captured_at < now - MAX_READING_AGE:
        return reject("TIMESTAMP_INVALID", "측정 시각이 너무 오래되었습니다.")
    quality = r.quality.value
    error = r.error
    try:
        norm, norm_unit = normalize_reading(r)
    except ce.CostInputError as e:
        return reject(e.code, ce.MESSAGES_KO.get(e.code, "값이 올바르지 않습니다."))
    if norm is None and quality in ("VERIFIED", "ESTIMATED"):
        quality, error = "UNAVAILABLE", error or "value missing"
    if r.type == s.ReadingType.ODOMETER and norm is not None and quality in ("VERIFIED", "ESTIMATED"):
        prev = _max_odometer(db, trip.id)
        if prev is not None and norm < prev - Decimal("0.5"):
            quality, error = "INVALID", "ODOMETER_REGRESSION"
    db.add(m.VehicleReading(
        trip_id=trip.id, event_id=r.event_id, type=r.type.value, captured_at=r.captured_at,
        raw_value=None if r.value is None else Decimal(str(r.value)), raw_unit=r.unit,
        normalized_value=norm, normalized_unit=norm_unit, source=r.source.value, quality=quality,
        sdk_version=r.sdk_version, error=error,
    ))
    db.flush()
    trip._reading_total += 1
    return "accepted", None


def void_reading(db: Session, trip: m.Trip, event_id: str, actor: m.User) -> None:
    rd = db.scalar(select(m.VehicleReading).where(m.VehicleReading.trip_id == trip.id, m.VehicleReading.event_id == event_id))
    if rd is None:
        raise ApiError(404, "NOT_FOUND", "찾을 수 없거나 볼 수 있는 권한이 없습니다.")
    if trip.odometer_start_km is not None and rd.type == "ODOMETER" and rd.normalized_value == trip.odometer_start_km and rd.captured_at == trip.started_at:
        raise conflict("READING_IN_USE", "운행 시작 값으로 쓰인 판독값은 취소할 수 없습니다.")
    if trip.odometer_end_km is not None and rd.type == "ODOMETER" and rd.normalized_value == trip.odometer_end_km and rd.captured_at == trip.ended_at:
        raise conflict("READING_IN_USE", "운행 종료 값으로 쓰인 판독값은 취소할 수 없습니다.")
    before = rd.quality
    rd.quality, rd.error = "INVALID", "VOIDED_BY_DRIVER"
    bump(trip)
    db.add(m.AuditEvent(actor_id=actor.id, entity_type="reading", entity_id=f"{trip.id}:{event_id}", action="VOID", diff={"before": before}))


def _max_odometer(db: Session, trip_id: str) -> Decimal | None:
    top = db.scalar(select(func.max(m.VehicleReading.normalized_value)).where(
        m.VehicleReading.trip_id == trip_id, m.VehicleReading.type == "ODOMETER",
        m.VehicleReading.quality.in_(("VERIFIED", "ESTIMATED"))))
    return None if top is None else Decimal(top)


def reading_count(db: Session, trip_id: str) -> int:
    return db.scalar(select(func.count()).select_from(m.VehicleReading).where(m.VehicleReading.trip_id == trip_id)) or 0


def latest_reading(db: Session, trip_id: str, rtype: str) -> m.VehicleReading | None:
    return db.scalar(select(m.VehicleReading).where(
        m.VehicleReading.trip_id == trip_id, m.VehicleReading.type == rtype,
        m.VehicleReading.quality.in_(("VERIFIED", "ESTIMATED")), m.VehicleReading.normalized_value.is_not(None),
    ).order_by(m.VehicleReading.captured_at.desc()).limit(1))


def set_cost_rule(db: Session, trip: m.Trip, rule: s.CostRuleIn) -> m.CostRule:
    if rule.efficiency_source == ce.EfficiencySource.ESTIMATE:
        if rule.efficiency_value is not None or rule.efficiency_unit is not None:
            raise ApiError(422, "EFFICIENCY_SOURCE_MISMATCH", "추정 연비에는 값을 보낼 수 없습니다. 서버가 계산합니다.")
        if rule.fuel_type is None:
            raise ApiError(422, "FUEL_TYPE_REQUIRED", "연료 종류가 필요합니다.")
        if (rule.fuel_type == "ELECTRIC") != (rule.vehicle_type == ce.VehicleType.EV):
            raise ApiError(422, "FUEL_TYPE_MISMATCH", "차량 종류와 연료 종류가 맞지 않습니다.")
    elif rule.efficiency_source == ce.EfficiencySource.MANUAL:
        if rule.efficiency_value is None or rule.efficiency_unit is None:
            raise ApiError(422, "EFFICIENCY_REQUIRED", "직접 입력 방식에서는 연비/전비 값과 단위가 필요합니다.")
        eff = _normalized_efficiency(rule.vehicle_type, rule.efficiency_value, rule.efficiency_unit)
        lo, hi = ce.ICE_EFFICIENCY_RANGE if rule.vehicle_type == ce.VehicleType.ICE else ce.EV_EFFICIENCY_RANGE
        if not lo <= eff <= hi:
            raise ApiError(422, "EFFICIENCY_IMPLAUSIBLE", ce.MESSAGES_KO["EFFICIENCY_IMPLAUSIBLE"])
    elif rule.efficiency_value is not None:
        raise ApiError(422, "EFFICIENCY_SOURCE_MISMATCH", "차량/데모 출처의 연비는 직접 입력할 수 없습니다. 출처를 '직접 입력'으로 바꿔 주세요.")
    if rule.efficiency_source.value not in ("MANUAL", "ESTIMATE") and rule.efficiency_source.value != trip.data_mode:
        raise ApiError(422, "EFFICIENCY_SOURCE_MISMATCH", "연비 출처가 운행의 데이터 출처와 다릅니다.")
    cr = trip.cost_rule or m.CostRule(trip_id=trip.id)
    cr.vehicle_type = rule.vehicle_type.value
    cr.efficiency_value = rule.efficiency_value
    cr.efficiency_unit = rule.efficiency_unit
    cr.efficiency_source = rule.efficiency_source.value
    cr.fuel_type = rule.fuel_type
    cr.unit_price_krw = rule.unit_price_krw
    cr.unit_price_source = rule.unit_price_source
    cr.driver_included = rule.driver_included
    cr.energy_policy = rule.energy_policy.value
    trip.cost_rule = cr
    return cr


def _normalized_efficiency(vtype, value, unit) -> Decimal:
    try:
        if vtype == ce.VehicleType.ICE:
            return ce.fuel_economy_to_km_per_l(value, unit)
        return ce.ev_efficiency_to_km_per_kwh(value, unit)
    except ce.CostInputError as e:
        raise ApiError(422, e.code, ce.MESSAGES_KO.get(e.code, "연비/전비 단위가 올바르지 않습니다.")) from e


def effective_efficiency(db: Session, trip: m.Trip) -> tuple[Decimal | None, ce.EfficiencySource | None]:
    cr = trip.cost_rule
    if cr is None:
        return None, None
    if cr.efficiency_source == "MANUAL":
        if cr.efficiency_value is None or cr.efficiency_unit is None:
            return None, None
        return _normalized_efficiency(ce.VehicleType(cr.vehicle_type), Decimal(cr.efficiency_value), cr.efficiency_unit), ce.EfficiencySource.MANUAL
    rtype = "FUEL_ECONOMY" if cr.vehicle_type == "ICE" else "EV_EFFICIENCY"
    rd = latest_reading(db, trip.id, rtype)
    if rd is not None:
        return Decimal(rd.normalized_value), ce.EfficiencySource(rd.source)
    if cr.efficiency_source == "ESTIMATE" and cr.fuel_type:
        rated = efficiency.rated_average(cr.fuel_type, get_settings().efficiency_data_dir)
        if rated is not None:
            return rated.value, ce.EfficiencySource.ESTIMATE
    return None, None


def make_ready(db: Session, trip: m.Trip) -> None:
    if trip.state == "READY":
        return
    if trip.state != "INVITING":
        raise conflict("INVALID_STATE", "초대 중인 운행만 출발 준비 상태로 바꿀 수 있습니다.")
    if trip.cost_rule is None or trip.cost_rule.unit_price_krw is None:
        raise ApiError(422, "COST_RULE_INCOMPLETE", "단가를 입력해 주세요.")
    joined = db.scalar(select(m.GroupMember.id).where(
        m.GroupMember.group_id == trip.group_id, m.GroupMember.status == "ACTIVE", m.GroupMember.user_id != trip.driver_user_id).limit(1))
    if joined is None:
        raise ApiError(422, "NO_PASSENGER", "함께 탈 승객이 1명 이상 승인되어야 합니다.")
    trip.state = "READY"
    bump(trip)


def _odometer_reading(db: Session, trip: m.Trip, r: s.ReadingIn, now) -> m.VehicleReading:
    if r.type != s.ReadingType.ODOMETER or r.value is None:
        raise ApiError(422, "ODOMETER_REQUIRED", "주행거리(ODOMETER) 값이 필요합니다.")
    if r.quality not in (s.Quality.VERIFIED, s.Quality.ESTIMATED):
        raise ApiError(422, "ODOMETER_UNUSABLE", "신뢰할 수 있는 주행거리 값이 필요합니다. 차량 값을 읽지 못했다면 직접 입력해 주세요.")
    status, rej = ingest_reading(db, trip, r, now, exempt_cap=True)
    if status == "rejected":
        raise ApiError(422, rej.code, rej.message_ko)
    rd = db.scalar(select(m.VehicleReading).where(m.VehicleReading.trip_id == trip.id, m.VehicleReading.event_id == r.event_id))
    if rd.quality == "INVALID" or rd.normalized_value is None:
        raise ApiError(422, "ODOMETER_UNUSABLE", "주행거리 값이 올바르지 않습니다(이전 값보다 작거나 비어 있음).")
    return rd


def start_trip(db: Session, trip: m.Trip, body: s.TripStartIn, now) -> None:
    if trip.state == "IN_PROGRESS" and trip.odometer_start_km is not None and _has_event(db, trip, body.odometer.event_id):
        return
    if trip.state != "READY":
        raise conflict("INVALID_STATE", "출발 준비가 끝난 운행만 시작할 수 있습니다.")
    if db.scalar(select(m.Trip.id).where(m.Trip.group_id == trip.group_id, m.Trip.state == "IN_PROGRESS", m.Trip.id != trip.id).limit(1)):
        raise conflict("TRIP_IN_PROGRESS", "이 그룹에 이미 진행 중인 운행이 있습니다.")
    rd = _odometer_reading(db, trip, body.odometer, now)
    trip.state = "IN_PROGRESS"
    trip.started_at = rd.captured_at
    trip.odometer_start_km = rd.normalized_value
    for iv in trip.intervals:
        if iv.onboard_at and iv.odometer_on_km is None:
            iv.odometer_on_km, iv.boundary_quality = rd.normalized_value, "VERIFIED"
    bump(trip)


def end_trip(db: Session, trip: m.Trip, body: s.TripEndIn, now) -> None:
    if trip.state in ("ENDED", "REVIEW", "DISPUTED", "CONFIRMED"):
        if _has_event(db, trip, body.odometer.event_id):
            return
        raise conflict("ALREADY_ENDED", "이미 종료된 운행입니다.")
    if trip.state != "IN_PROGRESS":
        raise conflict("INVALID_STATE", "진행 중인 운행만 종료할 수 있습니다.")
    rd = _odometer_reading(db, trip, body.odometer, now)
    end_km = Decimal(rd.normalized_value)
    if end_km < Decimal(trip.odometer_start_km) or end_km - Decimal(trip.odometer_start_km) > ce.MAX_TRIP_KM:
        raise ApiError(422, "DISTANCE_INVALID", ce.MESSAGES_KO["DISTANCE_INVALID"])
    trip.state, trip.ended_at, trip.odometer_end_km = "ENDED", rd.captured_at, end_km
    for iv in trip.intervals:
        if iv.onboard_at and iv.offboard_at is None:
            iv.offboard_at, iv.odometer_off_km, iv.closed_by = rd.captured_at, end_km, "TRIP_END"
    bump(trip)


def _has_event(db: Session, trip: m.Trip, event_id: str) -> bool:
    return db.scalar(select(m.VehicleReading.id).where(m.VehicleReading.trip_id == trip.id, m.VehicleReading.event_id == event_id,
                                                       m.VehicleReading.type == "ODOMETER")) is not None


def cancel_trip(trip: m.Trip) -> None:
    if trip.state in ("ENDED", "REVIEW", "DISPUTED", "CONFIRMED"):
        raise conflict("INVALID_STATE", "종료된 운행은 취소할 수 없습니다.")
    trip.state = "CANCELLED"
    bump(trip)


def _boundary(db: Session, trip: m.Trip, now) -> tuple[Decimal | None, str]:
    rd = latest_reading(db, trip.id, "ODOMETER")
    if rd is None:
        return None, "UNAVAILABLE"
    stale = (now - rd.captured_at).total_seconds() > get_settings().stale_reading_seconds
    return Decimal(rd.normalized_value), "ESTIMATED" if stale else "VERIFIED"


def board(db: Session, trip: m.Trip, member: m.GroupMember, actor: m.User, body: s.BoardingIn, now) -> m.ParticipantInterval | None:
    _guard_boarding(trip, member, actor, body)
    open_iv = _open_interval(trip, member.user_id)
    if open_iv:
        return open_iv
    if sum(1 for i in trip.intervals if i.user_id == member.user_id) >= MAX_BOARDINGS_PER_USER:
        raise conflict("TOO_MANY_BOARDINGS", "한 운행에서 탑승·하차를 너무 많이 반복했습니다.")
    by = "USER" if actor.id == member.user_id else "DRIVER"
    if trip.state in PRE_DEPARTURE:
        iv = m.ParticipantInterval(trip_id=trip.id, user_id=member.user_id, onboard_at=now, boundary_quality="VERIFIED", opened_by=by)
    else:
        km, q = (Decimal(body.odometer_km), "ESTIMATED") if body.odometer_km is not None else _boundary(db, trip, now)
        iv = m.ParticipantInterval(trip_id=trip.id, user_id=member.user_id, onboard_at=now, odometer_on_km=km, boundary_quality=q, opened_by=by)
    trip.intervals.append(iv)
    bump(trip)
    return iv


def alight(db: Session, trip: m.Trip, member: m.GroupMember, actor: m.User, body: s.BoardingIn, now) -> m.ParticipantInterval | None:
    _guard_boarding(trip, member, actor, body)
    iv = _open_interval(trip, member.user_id)
    if iv is None:
        raise conflict("NOT_ON_BOARD", "탑승 중이 아닙니다.")
    if trip.state in PRE_DEPARTURE:
        trip.intervals.remove(iv)
        bump(trip)
        return None
    km, q = (Decimal(body.odometer_km), "ESTIMATED") if body.odometer_km is not None else _boundary(db, trip, now)
    if body.undo and km is not None and iv.odometer_on_km is not None and Decimal(iv.odometer_on_km) == Decimal(km):


        trip.intervals.remove(iv)
        bump(trip)
        return None
    if km is not None and iv.odometer_on_km is not None and km < Decimal(iv.odometer_on_km):
        km = Decimal(iv.odometer_on_km)
    iv.offboard_at, iv.odometer_off_km = now, km
    iv.closed_by = "USER" if actor.id == member.user_id else "DRIVER"
    if q != "VERIFIED":
        iv.boundary_quality = q
    bump(trip)
    return iv


def _guard_boarding(trip: m.Trip, member: m.GroupMember, actor: m.User, body: s.BoardingIn) -> None:
    if trip.state not in (*PRE_DEPARTURE, "IN_PROGRESS"):
        raise conflict("TRIP_NOT_ACTIVE", "지금은 탑승·하차를 기록할 수 없는 상태입니다.")
    if member.user_id == trip.driver_user_id:
        raise conflict("DRIVER_IMPLICIT", "운전자는 자동으로 전 구간에 탑승한 것으로 계산됩니다.")
    if member.user_id != actor.id and actor.id != trip.driver_user_id:
        raise forbidden("본인 또는 운전자만 탑승·하차를 기록할 수 있습니다.")
    if body.odometer_km is not None and actor.id != trip.driver_user_id:
        raise forbidden("주행거리를 직접 지정하는 것은 운전자만 할 수 있습니다.")


def _open_interval(trip: m.Trip, user_id: str) -> m.ParticipantInterval | None:
    for iv in trip.intervals:
        if iv.user_id == user_id and iv.onboard_at and iv.offboard_at is None:
            return iv
    return None
