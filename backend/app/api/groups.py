from datetime import timedelta

from fastapi import APIRouter, Depends, Header, Response
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import schemas as s
from app.api.deps import _bearer, active_member, audit, current_user, optional_user
from app.config import get_settings
from app.db import models as m
from app.db.session import get_db
from app.errors import ApiError, conflict, forbidden, not_found
from app.security import hash_token, new_token
from app.services import privacy, rewards
from app.services.settlements import latest_settlement
from app.services.trips import DISCLAIMERS, nz

router = APIRouter()


def _issue_token(db: Session, user: m.User) -> tuple[str, m.AuthToken]:
    plain = new_token()
    row = m.AuthToken(user_id=user.id, token_hash=hash_token(plain), expires_at=m.utcnow() + timedelta(days=get_settings().token_ttl_days))
    db.add(row)
    return plain, row


@router.post("/auth/guest", response_model=s.AuthOut, status_code=201, tags=["auth"])
def guest(body: s.GuestAuthIn, db: Session = Depends(get_db)):
    if not get_settings().allow_guest_auth:
        raise forbidden("이 서버에서는 임시 참여가 꺼져 있습니다.")
    user = m.User(nickname=body.nickname, consent_version=body.consent_version)
    db.add(user)
    db.flush()
    plain, row = _issue_token(db, user)
    db.commit()
    return s.AuthOut(user_id=user.id, nickname=user.nickname, token=plain, expires_at=row.expires_at)


@router.post("/auth/logout", status_code=204, tags=["auth"])
def logout(authorization: str | None = Header(None), user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    """Revokes the token presented on this request."""
    row = db.scalar(select(m.AuthToken).where(m.AuthToken.token_hash == hash_token(_bearer(authorization) or "")))
    if row is not None:
        row.revoked_at = m.utcnow()
        db.commit()
    return Response(status_code=204)


def _group_out(db: Session, g: m.Group, viewer_is_owner: bool) -> s.GroupOut:
    members = [mm for mm in g.members if mm.status == "ACTIVE" or (viewer_is_owner and mm.status == "PENDING")]
    return s.GroupOut(id=g.id, name=g.name, owner_user_id=g.owner_user_id, created_at=g.created_at,
                      members=[s.MemberOut(user_id=mm.user_id, nickname=mm.user.nickname, role=mm.role, status=s.MemberStatus(mm.status)) for mm in members])


@router.post("/groups", response_model=s.GroupOut, status_code=201, tags=["groups"])
def create_group(body: s.GroupCreateIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    gid = body.id or m.new_id()
    g = db.get(m.Group, gid)
    if g is not None:
        if g.owner_user_id != user.id:
            raise conflict("ID_TAKEN", "이미 사용 중인 그룹 ID입니다.")
        return _group_out(db, g, True)  # idempotent retry
    g = m.Group(id=gid, name=body.name, owner_user_id=user.id)
    g.members.append(m.GroupMember(user_id=user.id, role="OWNER", status="ACTIVE", rules_accepted_at=m.utcnow()))
    db.add(g)
    audit(db, user.id, "group", gid, "CREATE")
    try:
        db.commit()
    except IntegrityError:  # lost a race with an identical create
        db.rollback()
        g = db.get(m.Group, gid)
        if g is None or g.owner_user_id != user.id:
            raise conflict("ID_TAKEN", "이미 사용 중인 그룹 ID입니다.") from None
    return _group_out(db, g, True)


@router.get("/groups", response_model=list[s.GroupOut], tags=["groups"])
def my_groups(user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    gs = db.scalars(select(m.Group).join(m.GroupMember).where(m.GroupMember.user_id == user.id, m.GroupMember.status == "ACTIVE")
                    .order_by(m.Group.created_at)).all()
    return [_group_out(db, g, g.owner_user_id == user.id) for g in gs]


@router.get("/groups/{group_id}/me", response_model=s.MembershipOut, tags=["groups"])
def my_membership(group_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    """Only the caller's own row, in any status, so a waiting or rejected passenger can be told apart."""
    mem = db.scalar(select(m.GroupMember).where(m.GroupMember.group_id == group_id, m.GroupMember.user_id == user.id))
    if mem is None:
        raise not_found()
    current = None
    if mem.status == "ACTIVE":   # only ACTIVE members are told about trips
        current = db.scalar(select(m.Trip.id).where(m.Trip.group_id == group_id, m.Trip.state.not_in(("CONFIRMED", "CANCELLED", "DRAFT")))
                            .order_by(m.Trip.created_at.desc()).limit(1))
    return s.MembershipOut(group_id=group_id, user_id=user.id, role=mem.role, status=s.MemberStatus(mem.status), current_trip_id=current)


@router.get("/groups/{group_id}", response_model=s.GroupOut, tags=["groups"])
def get_group(group_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    active_member(db, group_id, user.id)
    g = db.get(m.Group, group_id)
    return _group_out(db, g, g.owner_user_id == user.id)


@router.delete("/groups/{group_id}", status_code=204, tags=["groups"])
def delete_group(group_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    g = db.get(m.Group, group_id)
    if g is None or active_member(db, group_id, user.id) is None:
        raise not_found("그룹")
    if g.owner_user_id != user.id:
        raise forbidden("그룹을 만든 사람만 삭제할 수 있습니다.")
    privacy.purge_group(db, group_id)
    db.commit()


def _owner(db: Session, group_id: str, user: m.User) -> m.Group:
    active_member(db, group_id, user.id)
    g = db.get(m.Group, group_id)
    if g.owner_user_id != user.id:
        raise forbidden("그룹을 만든 사람(운전자)만 할 수 있습니다.")
    return g


@router.post("/groups/{group_id}/invites", response_model=s.InviteOut, status_code=201, tags=["invites"])
def create_invite(group_id: str, body: s.InviteCreateIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    _owner(db, group_id, user)
    if body.trip_id is not None:
        trip = db.get(m.Trip, body.trip_id)
        if trip is None or trip.group_id != group_id or trip.state not in ("INVITING", "READY", "IN_PROGRESS"):
            raise ApiError(422, "TRIP_NOT_INVITABLE", "초대할 수 있는 운행이 아닙니다.")
    plain = new_token()
    inv = m.Invite(id=m.new_id(), group_id=group_id, trip_id=body.trip_id, token_hash=hash_token(plain), created_by=user.id,
                   expires_at=m.utcnow() + timedelta(minutes=body.ttl_minutes), max_uses=body.max_uses)
    db.add(inv)
    audit(db, user.id, "invite", inv.id, "CREATE", {"trip_id": body.trip_id, "ttl_minutes": body.ttl_minutes})
    db.commit()
    return s.InviteOut(invite_id=inv.id, token=plain, join_path=f"/join#{plain}", expires_at=inv.expires_at, max_uses=inv.max_uses)


@router.delete("/groups/{group_id}/invites/{invite_id}", status_code=204, tags=["invites"])
def revoke_invite(group_id: str, invite_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    """A leaked QR can be killed immediately."""
    _owner(db, group_id, user)
    inv = db.get(m.Invite, invite_id)
    if inv is None or inv.group_id != group_id:
        raise not_found()
    inv.revoked_at = inv.revoked_at or m.utcnow()
    audit(db, user.id, "invite", inv.id, "REVOKE")
    db.commit()
    return Response(status_code=204)


def _live_invite(db: Session, token: str) -> m.Invite:
    inv = db.scalar(select(m.Invite).where(m.Invite.token_hash == hash_token(token)))
    if inv is None:  # unknown or tampered token
        raise not_found("초대 링크")
    if inv.revoked_at is not None or inv.expires_at <= m.utcnow() or inv.used_count >= inv.max_uses:
        raise ApiError(410, "INVITE_EXPIRED", "초대 링크가 만료되었거나 더 이상 사용할 수 없습니다. 운전자에게 새 QR을 요청해 주세요.")
    return inv


@router.post("/invites/preview", response_model=s.InvitePreviewOut, tags=["invites"])
def preview_invite(body: s.InviteTokenIn, db: Session = Depends(get_db)):
    inv = _live_invite(db, body.token)
    g = db.get(m.Group, inv.group_id)
    owner = db.get(m.User, g.owner_user_id)
    rules = ["정산은 실제 주행거리를 기준으로 한 추정치입니다.", "탑승·하차 구간만큼만 비용을 나눕니다.",
             "통행료·주차비는 항목마다 정해진 방식(균등/거리 비례/운전자 부담)으로 나눕니다.",
             "모든 참가자가 금액을 확인하고 동의해야 정산이 확정됩니다."]
    trip = db.get(m.Trip, inv.trip_id) if inv.trip_id else None
    if trip and trip.cost_rule:
        rules.insert(1, "운전자도 비용을 함께 부담합니다." if trip.cost_rule.driver_included else "운전자는 비용을 부담하지 않고, 승객이 나눕니다.")
        if trip.data_mode == "DEMO":
            rules.insert(0, "DEMO / 가상 데이터로 진행하는 시연 운행입니다.")
    return s.InvitePreviewOut(group_name=g.name, driver_nickname=owner.nickname, trip_id=inv.trip_id, rules_ko=rules,
                              disclaimers=DISCLAIMERS, expires_at=inv.expires_at)


@router.post("/invites/accept", response_model=s.InviteAcceptOut, status_code=201, tags=["invites"])
def accept_invite(body: s.InviteAcceptIn, caller: m.User | None = Depends(optional_user), db: Session = Depends(get_db)):
    if not body.rules_ack:
        raise ApiError(422, "RULES_NOT_ACKNOWLEDGED", "정산 규칙을 확인하고 동의해야 참가할 수 있습니다.")
    inv = _live_invite(db, body.token)
    token_out = None
    user = caller
    if user is None:
        if not get_settings().allow_guest_auth:
            raise forbidden("이 서버에서는 임시 참여가 꺼져 있습니다.")
        if not body.nickname:
            raise ApiError(422, "NICKNAME_REQUIRED", "닉네임을 입력해 주세요.")
        user = m.User(nickname=body.nickname, consent_version=body.consent_version)
        db.add(user)
        db.flush()
        token_out, _ = _issue_token(db, user)
    mem = db.scalar(select(m.GroupMember).where(m.GroupMember.group_id == inv.group_id, m.GroupMember.user_id == user.id))
    if mem is not None and mem.status == "REJECTED":
        raise forbidden("이 그룹에 참가할 수 없습니다.")
    if mem is None or mem.status == "LEFT":
        taken = db.execute(update(m.Invite).where(m.Invite.id == inv.id, m.Invite.used_count < m.Invite.max_uses)
                           .values(used_count=m.Invite.used_count + 1))
        if taken.rowcount != 1:
            raise ApiError(410, "INVITE_EXPIRED", "초대 링크 사용 가능 횟수를 모두 사용했습니다.")
        if mem is None:
            mem = m.GroupMember(group_id=inv.group_id, user_id=user.id, role="MEMBER", status="PENDING")
            db.add(mem)
        mem.status, mem.left_at = "PENDING", None
        mem.rules_accepted_at = m.utcnow()
        audit(db, user.id, "group", inv.group_id, "JOIN_REQUEST", {"invite": inv.id})
    try:
        db.commit()
    except IntegrityError:  # same user double-submitted; the first request created the membership
        db.rollback()
        mem = db.scalar(select(m.GroupMember).where(m.GroupMember.group_id == inv.group_id, m.GroupMember.user_id == user.id))
        if mem is None:
            raise
    return s.InviteAcceptOut(user_id=user.id, token=token_out, group_id=inv.group_id, trip_id=inv.trip_id, member_status=s.MemberStatus(mem.status))


@router.post("/groups/{group_id}/members/{user_id}/decision", response_model=s.MemberOut, tags=["groups"])
def decide_member(group_id: str, user_id: str, body: s.MemberDecisionIn, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    _owner(db, group_id, user)
    mem = db.scalar(select(m.GroupMember).where(m.GroupMember.group_id == group_id, m.GroupMember.user_id == user_id))
    if mem is None or mem.role == "OWNER":
        raise not_found()
    if mem.status != "PENDING":  # decisions are final: no re-admitting rejected users or evicting active riders
        raise conflict("MEMBER_NOT_PENDING", "승인 대기 중인 참가자만 승인하거나 거절할 수 있습니다.")
    mem.status = "ACTIVE" if body.decision == "APPROVE" else "REJECTED"
    if mem.status == "ACTIVE":
        mem.joined_at = m.utcnow()
    audit(db, user.id, "group", group_id, f"MEMBER_{body.decision}", {"user_id": user_id})
    db.execute(update(m.Trip).where(m.Trip.group_id == group_id, m.Trip.state.in_(("INVITING", "READY", "IN_PROGRESS")))
               .values(revision=m.Trip.revision + 1))  # atomic bump so pollers see the roster change
    db.commit()
    return s.MemberOut(user_id=mem.user_id, nickname=mem.user.nickname, role=mem.role, status=s.MemberStatus(mem.status))


@router.get("/groups/{group_id}/character", response_model=s.CharacterOut, tags=["character"])
def character(group_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    active_member(db, group_id, user.id)
    return rewards.character_out(db, group_id)


@router.get("/groups/{group_id}/rewards", response_model=list[s.CouponOut], tags=["character"])
def coupons(group_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    active_member(db, group_id, user.id)
    return rewards.coupons_out(db, group_id)


@router.get("/groups/{group_id}/history", response_model=list[s.HistoryItem], tags=["character"])
def history(group_id: str, user: m.User = Depends(current_user), db: Session = Depends(get_db)):
    active_member(db, group_id, user.id)
    trips = db.scalars(select(m.Trip).where(m.Trip.group_id == group_id, m.Trip.state.in_(("ENDED", "REVIEW", "DISPUTED", "CONFIRMED")))
                       .order_by(m.Trip.created_at.desc()).limit(100)).all()
    xp = dict(db.execute(select(m.RewardEvent.trip_id, m.RewardEvent.xp_delta).where(m.RewardEvent.group_id == group_id)).all())
    out = []
    for t in trips:
        dist = (t.odometer_end_km - t.odometer_start_km) if t.odometer_end_km is not None and t.odometer_start_km is not None else None
        latest = latest_settlement(db, t.id)
        people = 1 + len({iv.user_id for iv in t.intervals if iv.onboard_at})
        out.append(s.HistoryItem(trip_id=t.id, started_at=t.started_at, state=s.TripState(t.state), data_mode=t.data_mode, distance_km=nz(dist),
                                 total_krw=latest.total_krw if latest else None, participant_count=people, xp_gained=xp.get(t.id, 0)))
    return out
