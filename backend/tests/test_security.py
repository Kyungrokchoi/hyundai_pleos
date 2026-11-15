from datetime import timedelta
from pathlib import Path

import yaml
from openapi_spec_validator import validate
from sqlalchemy import func, select

from app.db import models as m
from app.db.session import SessionLocal
from tests.helpers import P, Actor, Trip, odo, uid


def test_requests_without_or_with_bad_token_are_401(client):
    t = Trip(client, passengers=("B",))
    assert client.get(f"{P}/trips/{t.trip_id}/summary").status_code == 401
    assert client.get(f"{P}/trips/{t.trip_id}/summary", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.get(f"{P}/groups").status_code == 401


def test_outsider_and_pending_member_cannot_read_trip_or_group(client):
    t = Trip(client, passengers=("B",))
    outsider = Actor(client, "Eve")
    for path in (f"/trips/{t.trip_id}/summary", f"/groups/{t.group_id}", f"/groups/{t.group_id}/character", f"/groups/{t.group_id}/history"):
        assert outsider.get(path).status_code == 404, path
    pending = t.join("Pat", approve=False)
    assert pending.get(f"/trips/{t.trip_id}/summary").status_code == 404
    assert pending.post(f"/trips/{t.trip_id}/participants/me/board").status_code == 404


def test_passengers_cannot_use_driver_endpoints(client):
    t = Trip(client, passengers=("B", "C"))
    t.ready_and_start()
    b = t.riders["B"]
    t.board("B")
    for method, path, body in [
        ("post", f"/trips/{t.trip_id}/end", {"odometer": odo(1010, "MANUAL")}),
        ("post", f"/trips/{t.trip_id}/readings/batch", {"readings": [odo(1010, "MANUAL")]}),
        ("post", f"/trips/{t.trip_id}/cost-items", {"id": uid(), "kind": "TOLL", "amount_krw": 1, "allocation": "EQUAL"}),
        ("put", f"/trips/{t.trip_id}/cost-rule", t.rule),
        ("post", f"/trips/{t.trip_id}/settlements/preview", None),
        ("post", f"/trips/{t.trip_id}/cancel", None),
        ("post", f"/groups/{t.group_id}/invites", {}),
    ]:
        r = getattr(b, method)(path, body) if body is not None else getattr(b, method)(path)
        assert r.status_code == 403, (path, r.status_code, r.text)
    assert b.post(f"/groups/{t.group_id}/members/{t.riders['C'].id}/decision", {"decision": "REJECT"}).status_code == 403


def test_passenger_cannot_board_for_others_or_pick_the_odometer(client):
    t = Trip(client, passengers=("B", "C"))
    t.ready_and_start()
    cid = t.riders["C"].id
    assert t.riders["B"].post(f"/trips/{t.trip_id}/participants/{cid}/board").status_code == 403
    assert t.riders["B"].post(f"/trips/{t.trip_id}/participants/me/board", {"odometer_km": 1}).status_code == 403

    assert t.driver.post(f"/trips/{t.trip_id}/participants/{cid}/board", {"odometer_km": 1000}).status_code == 200


def test_cannot_approve_someone_elses_settlement(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(); t.board("B"); t.end(1024)
    sid = t.preview().json()["id"]
    eve = Actor(client, "Eve")
    assert eve.post(f"/settlements/{sid}/approvals", {"decision": "APPROVE"}).status_code == 404
    other = Trip(client, passengers=("Z",))
    assert other.riders["Z"].post(f"/settlements/{sid}/approvals", {"decision": "APPROVE"}).status_code == 404


def test_it02_invite_expired_tampered_overused_and_unacknowledged(client, clock):
    t = Trip(client, passengers=())
    inv = t.invite(ttl_minutes=10, max_uses=1)
    body = {"nickname": "D", "consent_version": "v1", "rules_ack": True}
    assert client.post(f"{P}/invites/accept", json={**body, "token": inv["token"][:-2] + "xx"}).status_code == 404
    assert client.post(f"{P}/invites/accept", json={**body, "token": inv["token"], "rules_ack": False}).status_code == 422
    assert client.post(f"{P}/invites/accept", json={**body, "token": inv["token"]}).status_code == 201
    over = client.post(f"{P}/invites/accept", json={**body, "nickname": "E", "token": inv["token"]})
    assert over.status_code == 410 and over.json()["code"] == "INVITE_EXPIRED"
    fresh = t.invite(ttl_minutes=10)
    clock.advance(11 * 60)
    gone = client.post(f"{P}/invites/preview", json={"token": fresh["token"]})
    assert gone.status_code == 410 and "새 QR" in gone.json()["message_ko"]

    with SessionLocal() as db:
        hashes = set(db.scalars(select(m.Invite.token_hash)))
        assert inv["token"] not in hashes and all(len(h) == 64 for h in hashes)
        assert inv["token"] not in set(db.scalars(select(m.AuthToken.token_hash)))


def test_invite_for_one_group_cannot_join_another_or_reach_trip(client):
    a, b = Trip(client, passengers=()), Trip(client, passengers=())
    tok = a.invite()["token"]
    r = client.post(f"{P}/invites/accept", json={"token": tok, "nickname": "X", "consent_version": "v1", "rules_ack": True}).json()
    x = Actor.__new__(Actor); x.c, x.id, x.token = client, r["user_id"], r["token"]
    assert x.get(f"/trips/{a.trip_id}/summary").status_code == 404
    assert x.get(f"/trips/{b.trip_id}/summary").status_code == 404


def test_rejected_member_cannot_rejoin(client):
    t = Trip(client, passengers=())
    p = t.join("R", approve=False)
    assert t.driver.post(f"/groups/{t.group_id}/members/{p.id}/decision", {"decision": "REJECT"}).status_code == 200
    again = client.post(f"{P}/invites/accept", headers=p.h, json={"token": t.invite()["token"], "consent_version": "v1", "rules_ack": True})
    assert again.status_code == 403


def test_input_validation_and_limits(client):
    t = Trip(client, passengers=("B",))
    big = {"readings": [odo(1, "MANUAL") for _ in range(501)]}
    assert t.driver.post(f"/trips/{t.trip_id}/readings/batch", big).status_code == 422
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {"id": uid(), "kind": "TOLL", "amount_krw": -5, "allocation": "EQUAL"}).status_code == 422
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {"id": uid(), "kind": "TOLL", "amount_krw": 10, "allocation": "EQUAL", "total_krw": 1}).status_code == 422
    assert client.post(f"{P}/auth/guest", json={"nickname": "", "consent_version": "v1"}).status_code == 422
    huge = client.post(f"{P}/auth/guest", content=b"x" * (300 * 1024), headers={"Content-Type": "application/json", "Content-Length": str(300 * 1024)})
    assert huge.status_code == 413
    t.ready_and_start()

    from app.db import models as m
    raw = ('{"readings":[{"event_id":"nan-event-001","type":"ODOMETER","value":NaN,"unit":"km",'
           f'"captured_at":"{m.utcnow().isoformat()}","source":"MANUAL","quality":"VERIFIED"}}]}}')
    r = client.post(f"{P}/trips/{t.trip_id}/readings/batch", content=raw, headers={**t.driver.h, "Content-Type": "application/json"})
    assert r.status_code in (200, 422), r.text
    if r.status_code == 200:
        assert r.json()["accepted"] == 0 and r.json()["rejected"][0]["code"] == "NUMBER_INVALID"


def test_client_cannot_inject_totals_or_user_ids(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(); t.board("B"); t.end(1024)
    r = t.driver.post(f"/trips/{t.trip_id}/settlements/preview", {"total_krw": 1})
    assert r.status_code == 200 and r.json()["total_krw"] == 3600
    bad = t.riders["B"].post(f"/trips/{t.trip_id}/participants/{t.driver.id}/board")
    assert bad.status_code in (403, 409)


def test_delete_group_removes_all_user_data(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(); t.board("B"); t.drive(1010); t.toll(); t.end(1024)
    t.approve_all(t.preview().json()["id"])
    assert t.riders["B"].delete(f"/groups/{t.group_id}").status_code == 403
    assert t.driver.delete(f"/groups/{t.group_id}").status_code == 204
    with SessionLocal() as db:
        for model in (m.Group, m.Trip, m.GroupMember, m.VehicleReading, m.Settlement, m.SettlementApproval, m.CostItem, m.RewardEvent,
                      m.Badge, m.CharacterProgress, m.Invite, m.ParticipantInterval, m.CostRule, m.User, m.AuthToken):
            assert db.scalar(select(func.count()).select_from(model)) == 0, model.__name__
        assert db.scalar(select(func.count()).select_from(m.AuditEvent)) == 0


def test_retention_purges_old_trips_only(client, clock):
    from app.services.privacy import purge_expired
    old = Trip(client, passengers=("B",))
    fresh = Trip(client, passengers=())
    with SessionLocal() as db:
        db.get(m.Trip, old.trip_id).created_at = clock.now - timedelta(days=100)
        db.get(m.Trip, fresh.trip_id).created_at = clock.now - timedelta(days=1)
        db.commit()
    with SessionLocal() as db:
        assert purge_expired(db, clock.now, 90)["trips"] == 1
        db.commit()
        assert db.get(m.Trip, old.trip_id) is None and db.get(m.Trip, fresh.trip_id) is not None


def test_guest_auth_can_be_disabled(client, monkeypatch):
    from app.config import get_settings
    monkeypatch.setattr(get_settings(), "allow_guest_auth", False)
    assert client.post(f"{P}/auth/guest", json={"nickname": "Z", "consent_version": "v1"}).status_code == 403


def test_errors_have_stable_korean_shape(client):
    r = client.get(f"{P}/groups")
    assert r.json().keys() == {"code", "message_ko", "detail"} and r.json()["code"] == "UNAUTHENTICATED"
    assert client.get(f"{P}/health").json()["status"] == "ok"


def test_cors_is_not_a_wildcard(client):
    ok = client.options(f"{P}/groups", headers={"Origin": "http://localhost:3000", "Access-Control-Request-Method": "POST"})
    assert ok.headers.get("access-control-allow-origin") == "http://localhost:3000"
    bad = client.options(f"{P}/groups", headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"})
    assert "access-control-allow-origin" not in bad.headers


ROOT = Path(__file__).resolve().parents[2]


def test_openapi_is_valid_and_matches_committed_contract(client):
    from app.main import app
    spec = app.openapi()
    validate(spec)
    committed = yaml.safe_load((ROOT / "contracts" / "openapi.yaml").read_text(encoding="utf-8"))
    assert committed == spec, "contracts/openapi.yaml is stale: run `python -m app.export_openapi ../contracts/openapi.yaml`"
