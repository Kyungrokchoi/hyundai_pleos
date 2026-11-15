from __future__ import annotations

import bisect
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import schemas as s
from app.config import get_settings
from app.db import models as m

BADGES = {
    "FIRST_RIDE": ("첫 동행", "처음으로 함께 탄 이동을 마쳤어요."),
    "TRIO": ("셋이 함께", "세 명 이상이 한 차에서 정산을 마쳤어요."),
    "FIVE_TRIPS": ("다섯 번의 동행", "함께한 이동 기록이 5번 쌓였어요."),
    "CLEAN_SPLIT": ("한 번에 합의", "이의 없이 한 번에 정산에 합의했어요."),
}


def level_for(xp: int) -> int:
    return bisect.bisect_right(get_settings().level_thresholds, xp)


def _once(db: Session, obj) -> bool:
    try:
        with db.begin_nested():
            db.add(obj)
            db.flush()
        return True
    except IntegrityError:
        return False


MIN_RIDDEN_KM = Decimal("1")


def award_for_trip(db: Session, trip: m.Trip, riders_km: list[Decimal]) -> int:
    distance = (trip.odometer_end_km or 0) - (trip.odometer_start_km or 0)
    riders = [k for k in riders_km if k >= MIN_RIDDEN_KM]
    if not riders or distance <= 0:
        return 0
    people = 1 + len(riders)
    db.execute(select(m.Group.id).where(m.Group.id == trip.group_id).with_for_update())
    xp = get_settings().xp_per_verified_trip
    if not _once(db, m.RewardEvent(group_id=trip.group_id, trip_id=trip.id, rule_id="TRIP_COMPLETED", xp_delta=xp, data_mode=trip.data_mode)):
        return 0
    if db.get(m.CharacterProgress, trip.group_id) is None:
        _once(db, m.CharacterProgress(group_id=trip.group_id, xp=0))
    prog = db.scalar(select(m.CharacterProgress).where(m.CharacterProgress.group_id == trip.group_id)
                     .with_for_update().execution_options(populate_existing=True))
    before = level_for(prog.xp)
    prog.xp += xp
    prog.updated_at = m.utcnow()
    done = db.scalar(select(func.count()).select_from(m.RewardEvent).where(
        m.RewardEvent.group_id == trip.group_id, m.RewardEvent.rule_id == "TRIP_COMPLETED")) or 0
    earned = ["FIRST_RIDE"] if done == 1 else []
    earned += ["TRIO"] if people >= 3 else []
    earned += ["FIVE_TRIPS"] if done == 5 else []
    earned += ["CLEAN_SPLIT"] if _revisions(db, trip.id) == 1 and not _had_dispute(db, trip.id) else []
    for b in earned:
        _once(db, m.Badge(group_id=trip.group_id, badge_id=b))
    for lvl in range(max(before, 1) + 1, level_for(prog.xp) + 1):
        _once(db, m.DemoCoupon(group_id=trip.group_id, level=lvl, brand_label=f"MOATA 시연 쿠폰 · Lv.{lvl}"))
    return xp


def _revisions(db: Session, trip_id: str) -> int:
    return db.scalar(select(func.count()).select_from(m.Settlement).where(m.Settlement.trip_id == trip_id)) or 0


def _had_dispute(db: Session, trip_id: str) -> bool:
    return db.scalar(select(m.AuditEvent.id).join(m.Settlement, m.Settlement.id == m.AuditEvent.entity_id).where(
        m.Settlement.trip_id == trip_id, m.AuditEvent.action == "DISPUTE").limit(1)) is not None


def character_out(db: Session, group_id: str) -> s.CharacterOut:
    prog = db.get(m.CharacterProgress, group_id)
    xp = prog.xp if prog else 0
    th = get_settings().level_thresholds
    lvl = level_for(xp)
    badges = db.scalars(select(m.Badge).where(m.Badge.group_id == group_id).order_by(m.Badge.earned_at)).all()
    trips = db.scalar(select(func.count()).select_from(m.RewardEvent).where(m.RewardEvent.group_id == group_id)) or 0
    earned_at = {b.badge_id: b.earned_at for b in badges}
    catalogue = [s.BadgeStatusOut(id=bid, name_ko=name, description_ko=desc, earned=bid in earned_at, earned_at=earned_at.get(bid))
                 for bid, (name, desc) in BADGES.items()]
    return s.CharacterOut(
        group_id=group_id, xp=xp, level=lvl, level_xp_start=th[lvl - 1], next_level_xp=th[lvl] if lvl < len(th) else None,
        skin_id=prog.skin_id if prog else "default", mood="SLEEPY" if xp == 0 else ("PROUD" if lvl >= 3 else "HAPPY"),
        badges=[s.BadgeOut(id=b.badge_id, name_ko=BADGES[b.badge_id][0], description_ko=BADGES[b.badge_id][1], earned_at=b.earned_at) for b in badges],
        all_badges=catalogue, completed_trips=trips)


def coupons_out(db: Session, group_id: str) -> list[s.CouponOut]:
    rows = db.scalars(select(m.DemoCoupon).where(m.DemoCoupon.group_id == group_id).order_by(m.DemoCoupon.level)).all()
    return [s.CouponOut(id=r.id, brand_label=r.brand_label, status="DEMO_AVAILABLE") for r in rows]

