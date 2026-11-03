from fastapi import Depends, Header
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db import models as m
from app.db.session import get_db
from app.errors import ApiError, forbidden, not_found
from app.security import hash_token
from app.services.trips import lock_trip


def _bearer(authorization: str | None) -> str | None:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip() or None
    return None


def optional_user(authorization: str | None = Header(None), db: Session = Depends(get_db)) -> m.User | None:
    token = _bearer(authorization)
    if not token:
        return None
    row = db.scalar(select(m.AuthToken).where(m.AuthToken.token_hash == hash_token(token)))
    if row is None or row.revoked_at is not None or row.expires_at <= m.utcnow():
        return None
    return db.get(m.User, row.user_id)


def current_user(user: m.User | None = Depends(optional_user)) -> m.User:
    if user is None:
        raise ApiError(401, "UNAUTHENTICATED", "로그인이 필요합니다. 다시 참여해 주세요.")
    return user


def active_member(db: Session, group_id: str, user_id: str) -> m.GroupMember:
    """404, not 403, so outsiders cannot tell the group exists."""
    mem = db.scalar(select(m.GroupMember).where(m.GroupMember.group_id == group_id, m.GroupMember.user_id == user_id))
    if mem is None or mem.status != "ACTIVE":
        raise not_found()
    return mem


def trip_for_member(db: Session, trip_id: str, user: m.User) -> tuple[m.Trip, m.GroupMember]:
    trip = db.get(m.Trip, trip_id)
    if trip is None:
        raise not_found()
    return trip, active_member(db, trip.group_id, user.id)


def trip_for_driver(db: Session, trip_id: str, user: m.User) -> m.Trip:
    trip, _ = trip_for_member(db, trip_id, user)
    if trip.driver_user_id != user.id:
        raise forbidden("운전자만 할 수 있는 작업입니다.")
    return trip


def audit(db: Session, actor: str | None, entity: str, entity_id: str, action: str, diff: dict | None = None) -> None:
    db.add(m.AuditEvent(actor_id=actor, entity_type=entity, entity_id=entity_id, action=action, diff=diff))



def locked_member_trip(db: Session, trip_id: str, user: m.User) -> m.Trip:
    """Authorise, then lock and reload the trip (populate_existing discards stale identity-map state)."""
    trip_for_member(db, trip_id, user)
    return lock_trip(db, trip_id)


def locked_driver_trip(db: Session, trip_id: str, user: m.User) -> m.Trip:
    trip_for_driver(db, trip_id, user)
    return lock_trip(db, trip_id)
