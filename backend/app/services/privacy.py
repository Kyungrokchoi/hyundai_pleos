from datetime import datetime, timedelta

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app.db import models as m


def _purge_audit(db: Session, entity_ids: list[str]) -> None:
    for i in range(0, len(entity_ids), 500):
        db.execute(delete(m.AuditEvent).where(m.AuditEvent.entity_id.in_(entity_ids[i:i + 500])))


def purge_trips(db: Session, trip_ids: list[str]) -> None:
    if not trip_ids:
        return
    settlement_ids = list(db.scalars(select(m.Settlement.id).where(m.Settlement.trip_id.in_(trip_ids))))
    interval_ids = list(db.scalars(select(m.ParticipantInterval.id).where(m.ParticipantInterval.trip_id.in_(trip_ids))))
    item_keys = [f"{t}:{i}" for t, i in db.execute(select(m.CostItem.trip_id, m.CostItem.id).where(m.CostItem.trip_id.in_(trip_ids)))]

    _purge_audit(db, [*trip_ids, *settlement_ids, *interval_ids, *item_keys])
    db.execute(delete(m.RewardEvent).where(m.RewardEvent.trip_id.in_(trip_ids)))
    for t in db.scalars(select(m.Trip).where(m.Trip.id.in_(trip_ids))):
        db.delete(t)
    db.flush()


def _drop_orphan_users(db: Session, user_ids: list[str]) -> int:
    n = 0
    for uid in user_ids:
        if db.scalar(select(func.count()).select_from(m.GroupMember).where(m.GroupMember.user_id == uid)):
            continue
        db.execute(delete(m.AuditEvent).where(m.AuditEvent.actor_id == uid))
        u = db.get(m.User, uid)
        if u is not None:
            db.delete(u)
            n += 1
    db.flush()
    return n


def purge_group(db: Session, group_id: str) -> None:
    trip_ids = list(db.scalars(select(m.Trip.id).where(m.Trip.group_id == group_id)))
    member_ids = list(db.scalars(select(m.GroupMember.user_id).where(m.GroupMember.group_id == group_id)))
    invite_ids = list(db.scalars(select(m.Invite.id).where(m.Invite.group_id == group_id)))
    purge_trips(db, trip_ids)
    _purge_audit(db, [group_id, *invite_ids])
    g = db.get(m.Group, group_id)
    if g is not None:
        db.delete(g)
    db.flush()
    _drop_orphan_users(db, member_ids)


def purge_expired(db: Session, now: datetime, retention_days: int) -> dict[str, int]:
    cutoff = now - timedelta(days=retention_days)
    trip_ids = list(db.scalars(select(m.Trip.id).where(m.Trip.created_at < cutoff)))
    purge_trips(db, trip_ids)
    idle = db.scalars(select(m.Group.id).where(m.Group.created_at < cutoff, ~select(m.Trip.id).where(m.Trip.group_id == m.Group.id).exists()))
    groups = list(idle)
    for gid in groups:
        purge_group(db, gid)
    tokens = db.execute(delete(m.AuthToken).where(m.AuthToken.expires_at < now)).rowcount or 0
    stale_users = list(db.scalars(select(m.User.id).where(m.User.created_at < cutoff)))
    users = _drop_orphan_users(db, stale_users)
    return {"trips": len(trip_ids), "groups": len(groups), "tokens": tokens, "users": users}
