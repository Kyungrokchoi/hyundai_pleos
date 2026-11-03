"""Settlement revisions, approvals and confirmation."""
from __future__ import annotations

from dataclasses import asdict
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import schemas as s
from app.db import models as m
from app.errors import ApiError, conflict, forbidden
from app.services import cost_engine as ce
from app.services import rewards
from app.services.trips import DISCLAIMERS, MODE_NOTICE, effective_efficiency

PROPOSABLE = {"ENDED", "REVIEW", "DISPUTED"}
ITEM_LABEL = {"TOLL": "통행료", "PARKING": "주차비", "OTHER": "기타"}


def participant_ids(trip: m.Trip) -> list[str]:
    return [trip.driver_user_id, *sorted({iv.user_id for iv in trip.intervals if iv.onboard_at})]


def build_request(db: Session, trip: m.Trip) -> tuple[ce.SplitRequest | None, list[s.Issue]]:
    """Engine input from stored data, or the issues blocking it."""
    blocking: list[s.Issue] = []

    def block(code: str, msg: str):
        blocking.append(s.Issue(code=code, message_ko=msg))

    cr = trip.cost_rule
    if trip.odometer_start_km is None or trip.odometer_end_km is None:
        block("ODOMETER_MISSING", "시작/종료 주행거리 기록이 없습니다.")
    if cr is None or cr.unit_price_krw is None:
        block("PRICE_MISSING", "연료/전기 단가가 입력되지 않았습니다.")
    eff, eff_source = effective_efficiency(db, trip) if cr else (None, None)
    if cr is not None and eff is None:
        block("EFFICIENCY_MISSING", "연비/전비를 확인할 수 없습니다. 차량 값을 읽지 못했다면 직접 입력해 주세요.")
    ivs: list[ce.RideInterval] = []
    for iv in trip.intervals:
        if not iv.onboard_at:
            continue
        if iv.odometer_on_km is None or iv.odometer_off_km is None:
            block("INTERVAL_BOUNDARY_MISSING", "탑승·하차 지점의 주행거리가 없는 구간이 있습니다. 운전자가 교정해 주세요.")
            continue
        ivs.append(ce.RideInterval(iv.user_id, Decimal(iv.odometer_on_km), Decimal(iv.odometer_off_km)))
    if blocking:
        return None, blocking
    req = ce.SplitRequest(
        driver_id=trip.driver_user_id, start_km=Decimal(trip.odometer_start_km), end_km=Decimal(trip.odometer_end_km),
        intervals=ivs,
        energy=ce.EnergyInput(ce.VehicleType(cr.vehicle_type), eff, Decimal(cr.unit_price_krw), eff_source, ce.DataSource.MANUAL),
        driver_included=cr.driver_included, energy_policy=ce.EnergyPolicy(cr.energy_policy),
        items=[ce.CostItemInput(i.id, i.kind, i.amount_krw, ce.Allocation(i.allocation), i.note or ITEM_LABEL[i.kind]) for i in trip.items],
    )
    return req, []


def current_hash(db: Session, trip: m.Trip) -> str | None:
    req, _ = build_request(db, trip)
    return ce.request_hash(req) if req else None


def latest_settlement(db: Session, trip_id: str) -> m.Settlement | None:
    return db.scalar(select(m.Settlement).where(m.Settlement.trip_id == trip_id, m.Settlement.state != "SUPERSEDED")
                     .order_by(m.Settlement.revision.desc()).limit(1))


def propose(db: Session, trip: m.Trip, actor: m.User) -> m.Settlement:
    if trip.state not in PROPOSABLE:
        raise conflict("INVALID_STATE", "종료된 운행에서만 정산안을 만들 수 있습니다.")
    req, blocking = build_request(db, trip)
    if req is None:
        raise ApiError(422, "SETTLEMENT_BLOCKED", blocking[0].message_ko, ",".join(b.code for b in blocking))
    try:
        res = ce.compute_split(req)
    except ce.CostInputError as e:
        raise ApiError(422, e.code, ce.MESSAGES_KO.get(e.code, "입력값이 올바르지 않습니다."), e.detail or None) from e

    latest = latest_settlement(db, trip.id)
    existing = db.scalar(select(m.Settlement).where(m.Settlement.trip_id == trip.id, m.Settlement.input_hash == res.input_hash))
    top = db.scalar(select(func.max(m.Settlement.revision)).where(m.Settlement.trip_id == trip.id)) or 0
    if existing is not None and latest is not None and existing.id == latest.id:
        if existing.state != "DISPUTED":
            return existing  # same input: keep revision and approvals
        # disputed but inputs unchanged: reopen so everyone approves again
        existing.revision, existing.state = top + 1, "PROPOSED"
        existing.approvals.clear()
        trip.state = "REVIEW"
        trip.revision += 1
        db.add(m.AuditEvent(actor_id=actor.id, entity_type="settlement", entity_id=existing.id, action="REOPEN", diff={"revision": existing.revision}))
        return existing
    for old in db.scalars(select(m.Settlement).where(m.Settlement.trip_id == trip.id, m.Settlement.state != "SUPERSEDED")):
        old.state = "SUPERSEDED"
    labels = {i.id: (i.note or ITEM_LABEL[i.kind]) for i in trip.items}
    nick = {u.id: u.nickname for u in db.scalars(select(m.User).where(m.User.id.in_(participant_ids(trip))))}
    payload = {"engine": asdict(res), "nicknames": nick, "item_labels": labels, "driver_included": req.driver_included,
               "energy_policy": req.energy_policy.value, "vehicle_type": req.energy.vehicle_type.value}
    if existing is not None:  # input matches an older revision (input_hash is unique): reuse that row
        existing.revision, existing.state, existing.result = top + 1, "PROPOSED", payload
        existing.approvals.clear()
        st = existing
    else:
        st = m.Settlement(trip_id=trip.id, revision=top + 1, input_hash=res.input_hash, total_krw=res.total_krw,
                          result=payload, created_by=actor.id)
        db.add(st)
    db.flush()
    trip.state = "REVIEW"
    trip.revision += 1
    db.add(m.AuditEvent(actor_id=actor.id, entity_type="settlement", entity_id=st.id, action="PROPOSE",
                        diff={"revision": st.revision, "total_krw": res.total_krw, "hash": res.input_hash}))
    return st


def decide(db: Session, st: m.Settlement, user: m.User, decision: s.Decision, comment: str, now) -> None:
    trip = db.get(m.Trip, st.trip_id)
    latest = latest_settlement(db, trip.id)
    if latest is None or latest.id != st.id:
        raise conflict("STALE_REVISION", "더 새로운 정산안이 있습니다. 화면을 새로고침해 주세요.")
    if trip.state == "CONFIRMED" or st.state == "CONFIRMED":
        raise conflict("TRIP_LOCKED", "이미 확정된 정산입니다.")
    if user.id not in st.result["nicknames"]:
        raise forbidden("이 운행의 참가자만 승인하거나 이의를 제기할 수 있습니다.")
    if current_hash(db, trip) != st.input_hash:
        raise conflict("SETTLEMENT_OUTDATED", "입력이 바뀌어 정산안을 다시 만들어야 합니다. 운전자에게 요청해 주세요.")
    row = next((a for a in st.approvals if a.user_id == user.id), None)
    if row is None:
        row = m.SettlementApproval(settlement_id=st.id, user_id=user.id, decision=decision.value, comment=comment, decided_at=now)
        st.approvals.append(row)
    else:
        row.decision, row.comment, row.decided_at = decision.value, comment, now
    db.add(m.AuditEvent(actor_id=user.id, entity_type="settlement", entity_id=st.id, action=decision.value, diff={"comment": comment}))
    _recompute(db, st, trip)


def _recompute(db: Session, st: m.Settlement, trip: m.Trip) -> None:
    by_user = {a.user_id: a.decision for a in st.approvals}
    everyone = list(st.result["nicknames"])
    if any(v == "DISPUTE" for v in by_user.values()):
        st.state, trip.state = "DISPUTED", "DISPUTED"
    elif all(by_user.get(u) == "APPROVE" for u in everyone):
        st.state, trip.state = "CONFIRMED", "CONFIRMED"
        db.flush()
        riders_km = [Decimal(sh["ridden_km"]) for sh in st.result["engine"]["shares"] if not sh["is_driver"]]
        rewards.award_for_trip(db, trip, riders_km)
    else:
        st.state, trip.state = "PROPOSED", "REVIEW"
    trip.revision += 1


def to_out(st: m.Settlement) -> s.SettlementOut:
    eng, nick = st.result["engine"], st.result["nicknames"]
    labels = st.result.get("item_labels", {})
    decisions = {a.user_id: a for a in st.approvals}

    def approval(uid: str) -> tuple[s.ApprovalState, str | None]:
        a = decisions.get(uid)
        if a is None:
            return s.ApprovalState.PENDING, None
        return (s.ApprovalState.APPROVED if a.decision == "APPROVE" else s.ApprovalState.DISPUTED), (a.comment or None)

    warnings = [s.Issue(code=c, message_ko=ce.MESSAGES_KO.get(c, c)) for c in eng["warnings"]]
    notice = MODE_NOTICE.get(eng["efficiency_source"])
    shares = []
    for sh in eng["shares"]:
        state, comment = approval(sh["user_id"])
        shares.append(s.ShareOut(
            user_id=sh["user_id"], nickname=nick.get(sh["user_id"], "?"), is_driver=sh["is_driver"], total_krw=sh["total_krw"],
            energy_krw=sh["energy_krw"], items=[s.ShareLineOut(item_id=k, label=labels.get(k, k), amount_krw=v) for k, v in sh["items_krw"].items()],
            ridden_km=sh["ridden_km"], owed_to_driver_krw=sh["owed_to_driver_krw"], approval=state, comment=comment))
    return s.SettlementOut(
        id=st.id, trip_id=st.trip_id, revision=st.revision, state=s.SettlementState(st.state), total_krw=eng["total_krw"],
        energy_total_krw=eng["energy_total_krw"], items_total_krw=eng["items_total_krw"], distance_km=eng["distance_km"],
        consumption=eng["consumption"], consumption_unit=eng["consumption_unit"], equation=eng["equation"],
        efficiency_source=ce.DataSource(eng["efficiency_source"]), driver_included=st.result["driver_included"],
        energy_policy=ce.EnergyPolicy(st.result["energy_policy"]),
        segments=[s.SegmentOut(index=g["index"], start_km=g["start_km"], end_km=g["end_km"], length_km=g["length_km"],
                               payers=list(g["payers"]), energy_krw=g["energy_krw"]) for g in eng["segments"]],
        shares=shares, warnings=warnings, disclaimers=[*DISCLAIMERS, *([notice] if notice else [])],
        created_at=st.created_at, engine_version=eng["engine_version"])
