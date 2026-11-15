from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import schemas as s
from app.api.deps import active_member, audit, current_user, locked_driver_trip, locked_member_trip, trip_for_driver, trip_for_member
from app.config import get_settings
from app.db import models as m
from app.db.session import get_db
from app.errors import ApiError, conflict, forbidden, not_found
from app.services import cost_engine as ce
from app.services import exco, tolls
from app.services import trips as svc
from app.services.summary import build_summary

router = APIRouter(tags=["trips"])


def _out(db: Session, trip: m.Trip, user: m.User) -> s.TripSummary:
    return build_summary(db, trip, m.utcnow(), user.id)


@router.post("/trips", response_model=s.TripSummary, status_code=201)
def create_trip(body: s.TripCreateIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    mem = active_member(db, body.group_id, user.id)
    if mem.role != "OWNER":
        raise forbidden("그룹을 만든 사람(운전자)만 운행을 만들 수 있습니다.")
    existing = db.get(m.Trip, body.id)
    if existing is not None:
        if existing.group_id != body.group_id or existing.driver_user_id != user.id:
            raise conflict("ID_TAKEN", "이미 사용 중인 운행 ID입니다.")
        return _out(db, existing, user)
    busy = db.scalar(select(m.Trip.id).where(m.Trip.group_id == body.group_id, m.Trip.state == "IN_PROGRESS").limit(1))
    if busy:
        raise conflict("TRIP_IN_PROGRESS", "이미 진행 중인 운행이 있습니다.")
    trip = m.Trip(id=body.id, group_id=body.group_id, driver_user_id=user.id, vehicle_alias=body.vehicle_alias, data_mode=body.data_mode.value)
    db.add(trip)
    svc.set_cost_rule(db, trip, body.cost_rule)
    audit(db, user.id, "trip", trip.id, "CREATE", {"data_mode": trip.data_mode})
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        trip = db.get(m.Trip, body.id)
        if trip is None or trip.group_id != body.group_id or trip.driver_user_id != user.id:
            raise conflict("ID_TAKEN", "이미 사용 중인 운행 ID입니다.") from None
    return _out(db, trip, user)


@router.get("/trips/{trip_id}/summary", response_model=s.TripSummary)
def summary(trip_id: str, request: Request, response: Response, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip, _ = trip_for_member(db, trip_id, user)
    latest = svc.latest_reading(db, trip.id, "ODOMETER") if trip.state == "IN_PROGRESS" else None
    stale = trip.state == "IN_PROGRESS" and (latest is None or (m.utcnow() - latest.captured_at).total_seconds() > get_settings().stale_reading_seconds)
    etag = f'"{trip.revision}{"s" if stale else ""}"'
    headers = {"ETag": etag, "Cache-Control": "no-cache"}
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers=headers)
    response.headers.update(headers)
    return _out(db, trip, user)


@router.put("/trips/{trip_id}/cost-rule", response_model=s.TripSummary)
def put_cost_rule(trip_id: str, body: s.CostRuleIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.require_open(trip)
    before = trip.cost_rule and {"unit_price_krw": str(trip.cost_rule.unit_price_krw), "driver_included": trip.cost_rule.driver_included}
    svc.set_cost_rule(db, trip, body)
    svc.bump(trip)
    audit(db, user.id, "trip", trip.id, "COST_RULE", {"before": before, "after": {"unit_price_krw": str(body.unit_price_krw), "driver_included": body.driver_included}})
    db.commit()
    return _out(db, trip, user)


@router.post("/trips/{trip_id}/ready", response_model=s.TripSummary)
def ready(trip_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.make_ready(db, trip)
    db.commit()
    return _out(db, trip, user)


@router.post("/trips/{trip_id}/start", response_model=s.TripSummary)
def start(trip_id: str, body: s.TripStartIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.start_trip(db, trip, body, m.utcnow())
    audit(db, user.id, "trip", trip.id, "START")
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise conflict("TRIP_IN_PROGRESS", "이 그룹에 이미 진행 중인 운행이 있습니다.") from None
    return _out(db, trip, user)


@router.post("/trips/{trip_id}/end", response_model=s.TripSummary)
def end(trip_id: str, body: s.TripEndIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.end_trip(db, trip, body, m.utcnow())
    audit(db, user.id, "trip", trip.id, "END")
    db.commit()
    return _out(db, trip, user)


@router.post("/trips/{trip_id}/cancel", response_model=s.TripSummary)
def cancel(trip_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    if trip.state != "CANCELLED":
        svc.cancel_trip(trip)
        audit(db, user.id, "trip", trip.id, "CANCEL")
    db.commit()
    return _out(db, trip, user)


@router.post("/trips/{trip_id}/readings/batch", response_model=s.ReadingBatchOut)
def readings(trip_id: str, body: s.ReadingBatchIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.require_open(trip)
    now, acc, dup, rej = m.utcnow(), 0, 0, []
    for r in body.readings:
        status, why = svc.ingest_reading(db, trip, r, now)
        if status == "accepted":
            acc += 1
        elif status == "duplicate":
            dup += 1
        else:
            rej.append(why)
    if acc:
        svc.bump(trip)
    db.commit()
    return s.ReadingBatchOut(accepted=acc, duplicates=dup, rejected=rej)


def _target(trip: m.Trip, user_id: str, user: m.User, db: Session) -> m.GroupMember:
    uid = user.id if user_id == "me" else user_id
    mem = db.scalar(select(m.GroupMember).where(m.GroupMember.group_id == trip.group_id, m.GroupMember.user_id == uid))
    if mem is None or mem.status != "ACTIVE":
        raise not_found("참가자")
    return mem


@router.post("/trips/{trip_id}/participants/{user_id}/board", response_model=s.TripSummary)
def board(trip_id: str, user_id: str, body: s.BoardingIn | None = None, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_member_trip(db, trip_id, user)
    mem = _target(trip, user_id, user, db)
    before = len(trip.intervals)
    svc.board(db, trip, mem, user, body or s.BoardingIn(), m.utcnow())
    if len(trip.intervals) > before:
        audit(db, user.id, "trip", trip.id, "BOARD", {"user_id": mem.user_id, "by_driver": user.id != mem.user_id})
    db.commit()
    return _out(db, trip, user)


@router.post("/trips/{trip_id}/participants/{user_id}/alight", response_model=s.TripSummary)
def alight(trip_id: str, user_id: str, body: s.BoardingIn | None = None, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_member_trip(db, trip_id, user)
    mem = _target(trip, user_id, user, db)
    svc.alight(db, trip, mem, user, body or s.BoardingIn(), m.utcnow())
    audit(db, user.id, "trip", trip.id, "ALIGHT", {"user_id": mem.user_id, "by_driver": user.id != mem.user_id})
    db.commit()
    return _out(db, trip, user)


@router.patch("/trips/{trip_id}/intervals/{interval_id}", response_model=s.TripSummary)
def correct_interval(trip_id: str, interval_id: str, body: s.IntervalCorrectionIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.require_open(trip)
    iv = next((i for i in trip.intervals if i.id == interval_id), None)
    if iv is None:
        raise not_found("탑승 구간")
    on = body.odometer_on_km if body.odometer_on_km is not None else iv.odometer_on_km
    off = body.odometer_off_km if body.odometer_off_km is not None else iv.odometer_off_km
    if trip.odometer_start_km is not None and trip.odometer_end_km is not None and on is not None and off is not None:
        if not (trip.odometer_start_km <= on <= off <= trip.odometer_end_km):
            raise ApiError(422, "INTERVAL_OUT_OF_RANGE", ce.MESSAGES_KO["INTERVAL_OUT_OF_RANGE"])
    diff = {"reason": body.reason, "before": [str(iv.odometer_on_km), str(iv.odometer_off_km)], "after": [str(on), str(off)]}
    iv.odometer_on_km, iv.odometer_off_km = on, off
    iv.boundary_quality = "ESTIMATED"
    svc.bump(trip)
    audit(db, user.id, "interval", iv.id, "CORRECT", diff)
    db.commit()
    return _out(db, trip, user)


@router.post("/trips/{trip_id}/cost-items", response_model=s.TripSummary, status_code=201)
def add_item(trip_id: str, body: s.CostItemIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.require_open(trip)
    old = db.get(m.CostItem, {"id": body.id, "trip_id": trip.id})
    if old is not None:
        same = (old.kind, old.amount_krw, old.allocation, old.road_name, old.estimated) == (
            body.kind.value, body.amount_krw, body.allocation.value, body.road_name, body.estimated)
        if not same:
            raise conflict("ID_TAKEN", "같은 ID로 다른 비용 항목이 이미 있습니다.")
        return _out(db, trip, user)
    if len(trip.items) >= svc.MAX_ITEMS_PER_TRIP:
        raise ApiError(422, "TOO_MANY_ITEMS", "비용 항목이 너무 많습니다.")
    if sum(i.amount_krw for i in trip.items) + body.amount_krw > svc.MAX_ITEMS_TOTAL_KRW:
        raise ApiError(422, "ITEMS_TOTAL_TOO_LARGE", "비용 항목의 합계가 허용 범위를 넘었습니다.")
    trip.items.append(m.CostItem(id=body.id, kind=body.kind.value, amount_krw=body.amount_krw, allocation=body.allocation.value, note=body.note,
                                 road_name=body.road_name, estimated=body.estimated, created_by=user.id))
    svc.bump(trip)
    audit(db, user.id, "cost_item", f"{trip.id}:{body.id}", "ADD", {"kind": body.kind.value, "amount_krw": body.amount_krw, "allocation": body.allocation.value})
    db.commit()
    return _out(db, trip, user)


TOLL_ATTRIBUTION = "영업소 위치 © OpenStreetMap contributors (ODbL), 요금: 한국도로공사 오픈API. 추정값이며 실제 하이패스 청구액과 다를 수 있습니다."


@router.post("/trips/{trip_id}/tolls/estimate", response_model=s.TollEstimateOut)
def estimate_tolls(trip_id: str, body: s.TollEstimateIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip_for_driver(db, trip_id, user)
    cfg = get_settings()
    data = tolls.get_data(cfg.toll_data_dir)
    fares = exco.FareLookup(db, cfg.exco_key, body.vehicle_class, cfg.exco_max_lookups_per_estimate)
    pts = [tolls.Point(p.lat, p.lon, tolls.to_naive_utc(p.captured_at)) for p in body.points]
    passes = tolls.detect_passes(pts, data.gates, cfg.toll_gate_radius_m)
    segs, unmatched = tolls.estimate_segments(passes, fares)
    out = [s.TollSegmentOut(kind="HIGHWAY", road_name=f"{g.entry}→{g.exit}"[:40], amount_krw=g.amount_krw) for g in segs]
    out += [s.TollSegmentOut(kind="ZONE", road_name=z.name[:40], amount_krw=z.amount_krw) for z in tolls.zone_charges(pts, data.zones, body.occupants)]
    return s.TollEstimateOut(segments=out, passes=[s.TollPassOut(name=g.name, lat=g.lat, lon=g.lon) for g in passes],
                             total_krw=sum(o.amount_krw for o in out), unmatched_gates=unmatched,
                             data_available=bool(data.gates and cfg.exco_key) or bool(data.zones), incomplete=fares.failed,
                             attribution=TOLL_ATTRIBUTION)


@router.delete("/trips/{trip_id}/cost-items/{item_id}", response_model=s.TripSummary)
def delete_item(trip_id: str, item_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.require_open(trip)
    item = next((i for i in trip.items if i.id == item_id), None)
    if item is not None:
        trip.items.remove(item)
        svc.bump(trip)
        audit(db, user.id, "cost_item", f"{trip.id}:{item_id}", "DELETE", {"kind": item.kind, "amount_krw": item.amount_krw})
    db.commit()
    return _out(db, trip, user)


@router.delete("/trips/{trip_id}/readings/{event_id}", response_model=s.TripSummary)
def void_reading(trip_id: str, event_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    trip = locked_driver_trip(db, trip_id, user)
    svc.require_open(trip)
    svc.void_reading(db, trip, event_id, user)
    db.commit()
    return _out(db, trip, user)
