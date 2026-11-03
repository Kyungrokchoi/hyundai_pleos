"""Builds the single read model (TripSummary) every client renders."""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import schemas as s
from app.config import get_settings
from app.db import models as m
from app.services import cost_engine as ce
from app.services import settlements as st
from app.services.trips import DISCLAIMERS, MODE_NOTICE, PRE_DEPARTURE, effective_efficiency, latest_reading, nz

RIDING_STATES = (*PRE_DEPARTURE, "IN_PROGRESS")


def _issue(code: str, msg: str) -> s.Issue:
    return s.Issue(code=code, message_ko=msg)


def build_summary(db: Session, trip: m.Trip, now, viewer_id: str) -> s.TripSummary:
    group = db.get(m.Group, trip.group_id)
    members = db.scalars(select(m.GroupMember).where(m.GroupMember.group_id == trip.group_id, m.GroupMember.status.in_(("ACTIVE", "PENDING")))
                         .order_by(m.GroupMember.joined_at)).all()
    ivs: dict[str, list[m.ParticipantInterval]] = {}
    for iv in trip.intervals:
        ivs.setdefault(iv.user_id, []).append(iv)

    participants = []
    for mem in sorted(members, key=lambda x: x.user_id != trip.driver_user_id):  # driver first
        if mem.status == "PENDING" and viewer_id not in (trip.driver_user_id, mem.user_id):
            continue
        mine = ivs.get(mem.user_id, [])
        participants.append(s.ParticipantOut(
            user_id=mem.user_id, nickname=mem.user.nickname, is_driver=mem.user_id == trip.driver_user_id,
            member_status=s.MemberStatus(mem.status),
            on_board=trip.state in RIDING_STATES and any(i.onboard_at and not i.offboard_at for i in mine),  # a cancelled trip has nobody aboard
            intervals=[s.IntervalOut(
                id=i.id, user_id=i.user_id, onboard_at=i.onboard_at, offboard_at=i.offboard_at, odometer_on_km=nz(i.odometer_on_km),
                odometer_off_km=nz(i.odometer_off_km), boundary_quality=s.Quality(i.boundary_quality),
                closed_by=i.closed_by, opened_by=i.opened_by) for i in mine]))

    odo = latest_reading(db, trip.id, "ODOMETER")
    latest = None
    if odo is not None:
        latest = s.LatestOdometerOut(km=nz(odo.normalized_value), captured_at=odo.captured_at, source=ce.DataSource(odo.source),
                                     quality=s.Quality(odo.quality), age_seconds=max(0, int((now - odo.captured_at).total_seconds())))

    cr = trip.cost_rule
    rule_out = None
    if cr is not None:
        eff, _ = effective_efficiency(db, trip)
        rule_out = s.CostRuleOut(
            vehicle_type=ce.VehicleType(cr.vehicle_type), efficiency_value=nz(cr.efficiency_value), efficiency_unit=cr.efficiency_unit,
            efficiency_km_per_unit=nz(eff.quantize(ce.Decimal("0.001"))) if eff is not None else None,
            efficiency_source=ce.DataSource(cr.efficiency_source), unit_price_krw=nz(cr.unit_price_krw),
            driver_included=cr.driver_included, energy_policy=ce.EnergyPolicy(cr.energy_policy))

    settlement = st.latest_settlement(db, trip.id)
    distance = None
    if trip.odometer_start_km is not None:
        end = trip.odometer_end_km if trip.odometer_end_km is not None else (odo.normalized_value if odo is not None else None)
        distance = nz(ce.Decimal(end) - ce.Decimal(trip.odometer_start_km)) if end is not None else None

    return s.TripSummary(
        id=trip.id, group_id=trip.group_id, group_name=group.name, state=s.TripState(trip.state), data_mode=ce.DataSource(trip.data_mode),
        vehicle_alias=trip.vehicle_alias, driver_user_id=trip.driver_user_id, revision=trip.revision, started_at=trip.started_at,
        ended_at=trip.ended_at, odometer_start_km=nz(trip.odometer_start_km), odometer_end_km=nz(trip.odometer_end_km),
        distance_km=distance, latest_odometer=latest, cost_rule=rule_out,
        cost_items=[s.CostItemOut(id=i.id, kind=s.ItemKind(i.kind), amount_krw=i.amount_krw, allocation=ce.Allocation(i.allocation),
                                  note=i.note, created_by=i.created_by) for i in trip.items],
        participants=participants, settlement=st.to_out(settlement) if settlement else None,
        issues=_issues(db, trip, latest, settlement), disclaimers=[*DISCLAIMERS, *([MODE_NOTICE[trip.data_mode]])])


def _issues(db: Session, trip: m.Trip, latest: s.LatestOdometerOut | None, settlement: m.Settlement | None) -> list[s.Issue]:
    out: list[s.Issue] = []
    if trip.data_mode == "DEMO":
        out.append(_issue("DEMO_DATA", "DEMO / 가상 데이터입니다. 차량에서 측정한 값이 아닙니다."))
    elif trip.data_mode == "MANUAL":
        out.append(_issue("MANUAL_DATA", "주행거리를 직접 입력한 값으로 계산합니다."))
    if trip.state == "IN_PROGRESS":
        if latest is None:
            out.append(_issue("ODOMETER_MISSING", "아직 주행거리 값을 받지 못했습니다."))
        elif latest.age_seconds > get_settings().stale_reading_seconds:
            out.append(_issue("ODOMETER_STALE", "차량 데이터가 오래되었습니다. 차량 앱의 연결을 확인해 주세요."))
    bad = db.scalar(select(m.VehicleReading.id).where(m.VehicleReading.trip_id == trip.id, m.VehicleReading.type == "ODOMETER",
                                                      m.VehicleReading.quality == "INVALID").limit(1))
    if bad:
        out.append(_issue("ODOMETER_REGRESSION", "주행거리가 이전 값보다 줄어든 기록이 있어 계산에서 제외했습니다."))
    for rtype, code, label in (("FUEL_ECONOMY", "FUEL_ECONOMY_UNAVAILABLE", "연비"), ("EV_EFFICIENCY", "EV_EFFICIENCY_UNAVAILABLE", "전비")):
        failed = db.scalar(select(m.VehicleReading.id).where(m.VehicleReading.trip_id == trip.id, m.VehicleReading.type == rtype,
                                                             m.VehicleReading.quality.in_(("UNAVAILABLE", "INVALID"))).limit(1))
        if failed and latest_reading(db, trip.id, rtype) is None:
            out.append(_issue(code, f"차량에서 {label}를 읽지 못했습니다. 직접 입력한 값으로 계산해야 합니다."))
    if any(i.boundary_quality != "VERIFIED" for i in trip.intervals):
        out.append(_issue("INTERVAL_BOUNDARY_ESTIMATED", "일부 탑승·하차 지점은 정확한 측정값이 아니라 추정·수정된 값입니다."))
    if trip.state in ("ENDED", "REVIEW", "DISPUTED"):
        _, blocking = st.build_request(db, trip)
        out += blocking
        if settlement is not None and settlement.state in ("PROPOSED", "DISPUTED") and st.current_hash(db, trip) not in (None, settlement.input_hash):
            out.append(_issue("SETTLEMENT_OUTDATED", "입력이 바뀌어 정산안을 다시 만들어야 합니다."))
    return out
