from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import (JSON, BigInteger, Boolean, DateTime, ForeignKey, Index, Integer, Numeric, String, UniqueConstraint,
                        false, text)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy.types import TypeDecorator


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def new_id() -> str:
    return uuid.uuid4().hex


class UTCDateTime(TypeDecorator):
    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is not None and value.tzinfo is None:
            raise ValueError("naive datetime not allowed")
        return value.astimezone(timezone.utc) if value is not None else None

    def process_result_value(self, value, dialect):
        if value is not None and value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value


class Base(DeclarativeBase):
    type_annotation_map = {datetime: UTCDateTime()}


KM = Numeric(14, 3)
RATE = Numeric(14, 4)


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    nickname: Mapped[str] = mapped_column(String(20))
    consent_version: Mapped[str] = mapped_column(String(20))
    created_at: Mapped[datetime] = mapped_column(default=utcnow)


class AuthToken(Base):
    __tablename__ = "auth_tokens"
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    expires_at: Mapped[datetime]
    revoked_at: Mapped[datetime | None] = mapped_column(default=None)


class Group(Base):
    __tablename__ = "groups"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(30))
    owner_user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(default=utcnow)
    members: Mapped[list["GroupMember"]] = relationship(cascade="all, delete-orphan", order_by="GroupMember.joined_at")


class GroupMember(Base):
    __tablename__ = "group_members"
    __table_args__ = (UniqueConstraint("group_id", "user_id"),)
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    role: Mapped[str] = mapped_column(String(10), default="MEMBER")
    status: Mapped[str] = mapped_column(String(10), default="PENDING")
    rules_accepted_at: Mapped[datetime | None] = mapped_column(default=None)
    joined_at: Mapped[datetime] = mapped_column(default=utcnow)
    left_at: Mapped[datetime | None] = mapped_column(default=None)
    user: Mapped[User] = relationship()


class Invite(Base):
    __tablename__ = "invites"
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), index=True)
    trip_id: Mapped[str | None] = mapped_column(String(64), default=None)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    created_by: Mapped[str] = mapped_column(ForeignKey("users.id"))
    expires_at: Mapped[datetime]
    max_uses: Mapped[int] = mapped_column(Integer)
    used_count: Mapped[int] = mapped_column(Integer, default=0)
    revoked_at: Mapped[datetime | None] = mapped_column(default=None)


class Trip(Base):
    __tablename__ = "trips"
    __table_args__ = (Index("uq_trip_one_in_progress", "group_id", unique=True,
                            postgresql_where=text("state = 'IN_PROGRESS'"), sqlite_where=text("state = 'IN_PROGRESS'")),)
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), index=True)
    driver_user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    vehicle_alias: Mapped[str] = mapped_column(String(20), default="")
    state: Mapped[str] = mapped_column(String(12), default="INVITING")
    data_mode: Mapped[str] = mapped_column(String(8))
    started_at: Mapped[datetime | None] = mapped_column(default=None)
    ended_at: Mapped[datetime | None] = mapped_column(default=None)
    odometer_start_km: Mapped[object | None] = mapped_column(KM, default=None)
    odometer_end_km: Mapped[object | None] = mapped_column(KM, default=None)
    revision: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(default=utcnow)
    cost_rule: Mapped["CostRule | None"] = relationship(cascade="all, delete-orphan", uselist=False)
    intervals: Mapped[list["ParticipantInterval"]] = relationship(cascade="all, delete-orphan", order_by="ParticipantInterval.created_at")
    items: Mapped[list["CostItem"]] = relationship(cascade="all, delete-orphan", order_by="CostItem.created_at")


class CostRule(Base):
    __tablename__ = "cost_rules"
    trip_id: Mapped[str] = mapped_column(ForeignKey("trips.id", ondelete="CASCADE"), primary_key=True)
    vehicle_type: Mapped[str] = mapped_column(String(4))
    efficiency_value: Mapped[object | None] = mapped_column(RATE, default=None)
    efficiency_unit: Mapped[str | None] = mapped_column(String(12), default=None)
    efficiency_source: Mapped[str] = mapped_column(String(8), default="MANUAL")
    fuel_type: Mapped[str | None] = mapped_column(String(8), default=None)
    unit_price_krw: Mapped[object | None] = mapped_column(RATE, default=None)
    unit_price_source: Mapped[str] = mapped_column(String(8), default="MANUAL", server_default="MANUAL")
    driver_included: Mapped[bool] = mapped_column(Boolean, default=True)
    energy_policy: Mapped[str] = mapped_column(String(16), default="SEGMENT_SHARED")


class ParticipantInterval(Base):
    __tablename__ = "participant_intervals"
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    trip_id: Mapped[str] = mapped_column(ForeignKey("trips.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    onboard_at: Mapped[datetime | None] = mapped_column(default=None)
    offboard_at: Mapped[datetime | None] = mapped_column(default=None)
    odometer_on_km: Mapped[object | None] = mapped_column(KM, default=None)
    odometer_off_km: Mapped[object | None] = mapped_column(KM, default=None)
    boundary_quality: Mapped[str] = mapped_column(String(12), default="VERIFIED")
    closed_by: Mapped[str | None] = mapped_column(String(10), default=None)
    opened_by: Mapped[str | None] = mapped_column(String(10), default=None)
    created_at: Mapped[datetime] = mapped_column(default=utcnow)


class VehicleReading(Base):
    __tablename__ = "vehicle_readings"
    __table_args__ = (UniqueConstraint("trip_id", "event_id"), Index("ix_readings_trip_type_time", "trip_id", "type", "captured_at"))
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    trip_id: Mapped[str] = mapped_column(ForeignKey("trips.id", ondelete="CASCADE"))
    event_id: Mapped[str] = mapped_column(String(64))
    type: Mapped[str] = mapped_column(String(14))
    captured_at: Mapped[datetime]
    raw_value: Mapped[object | None] = mapped_column(Numeric(18, 6), default=None)
    raw_unit: Mapped[str] = mapped_column(String(12))
    normalized_value: Mapped[object | None] = mapped_column(Numeric(18, 6), default=None)
    normalized_unit: Mapped[str | None] = mapped_column(String(12), default=None)
    source: Mapped[str] = mapped_column(String(8))
    quality: Mapped[str] = mapped_column(String(12))
    sdk_version: Mapped[str | None] = mapped_column(String(20), default=None)
    error: Mapped[str | None] = mapped_column(String(200), default=None)
    received_at: Mapped[datetime] = mapped_column(default=utcnow)


class CostItem(Base):
    __tablename__ = "cost_items"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    trip_id: Mapped[str] = mapped_column(ForeignKey("trips.id", ondelete="CASCADE"), primary_key=True)
    kind: Mapped[str] = mapped_column(String(8))
    amount_krw: Mapped[int] = mapped_column(Integer)
    allocation: Mapped[str] = mapped_column(String(20))
    note: Mapped[str] = mapped_column(String(60), default="")
    road_name: Mapped[str] = mapped_column(String(40), default="", server_default="")
    estimated: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    created_by: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(default=utcnow)


class TollFareCache(Base):
    __tablename__ = "toll_fare_cache"
    dep_code: Mapped[str] = mapped_column(String(8), primary_key=True)
    arr_code: Mapped[str] = mapped_column(String(8), primary_key=True)
    vehicle_class: Mapped[int] = mapped_column(Integer, primary_key=True)
    amount_krw: Mapped[int | None] = mapped_column(Integer, default=None)
    fetched_at: Mapped[datetime] = mapped_column(default=utcnow)


class Settlement(Base):
    __tablename__ = "settlements"
    __table_args__ = (UniqueConstraint("trip_id", "revision"), UniqueConstraint("trip_id", "input_hash"))
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    trip_id: Mapped[str] = mapped_column(ForeignKey("trips.id", ondelete="CASCADE"), index=True)
    revision: Mapped[int] = mapped_column(Integer)
    state: Mapped[str] = mapped_column(String(12), default="PROPOSED")
    input_hash: Mapped[str] = mapped_column(String(64))
    total_krw: Mapped[int] = mapped_column(BigInteger)
    result: Mapped[dict] = mapped_column(JSON)
    created_by: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(default=utcnow)
    approvals: Mapped[list["SettlementApproval"]] = relationship(cascade="all, delete-orphan")


class SettlementApproval(Base):
    __tablename__ = "settlement_approvals"
    __table_args__ = (UniqueConstraint("settlement_id", "user_id"),)
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    settlement_id: Mapped[str] = mapped_column(ForeignKey("settlements.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    decision: Mapped[str] = mapped_column(String(8))
    comment: Mapped[str] = mapped_column(String(100), default="")
    decided_at: Mapped[datetime] = mapped_column(default=utcnow)


class CharacterProgress(Base):
    __tablename__ = "character_progress"
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), primary_key=True)
    xp: Mapped[int] = mapped_column(Integer, default=0)
    skin_id: Mapped[str] = mapped_column(String(20), default="default")
    updated_at: Mapped[datetime] = mapped_column(default=utcnow)


class RewardEvent(Base):
    __tablename__ = "reward_events"
    __table_args__ = (UniqueConstraint("trip_id", "rule_id"),)
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), index=True)
    trip_id: Mapped[str] = mapped_column(String(64))
    rule_id: Mapped[str] = mapped_column(String(30))
    xp_delta: Mapped[int] = mapped_column(Integer)
    data_mode: Mapped[str] = mapped_column(String(8))
    created_at: Mapped[datetime] = mapped_column(default=utcnow)


class Badge(Base):
    __tablename__ = "badges"
    __table_args__ = (UniqueConstraint("group_id", "badge_id"),)
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), index=True)
    badge_id: Mapped[str] = mapped_column(String(30))
    earned_at: Mapped[datetime] = mapped_column(default=utcnow)


class DemoCoupon(Base):
    __tablename__ = "demo_coupons"
    __table_args__ = (UniqueConstraint("group_id", "level"),)
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    group_id: Mapped[str] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), index=True)
    level: Mapped[int] = mapped_column(Integer)
    brand_label: Mapped[str] = mapped_column(String(40))
    created_at: Mapped[datetime] = mapped_column(default=utcnow)


class AuditEvent(Base):
    __tablename__ = "audit_events"
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    actor_id: Mapped[str | None] = mapped_column(String(32), index=True)
    entity_type: Mapped[str] = mapped_column(String(20))
    entity_id: Mapped[str] = mapped_column(String(140), index=True)
    action: Mapped[str] = mapped_column(String(40))
    at: Mapped[datetime] = mapped_column(default=utcnow)
    diff: Mapped[dict | None] = mapped_column(JSON, default=None)
