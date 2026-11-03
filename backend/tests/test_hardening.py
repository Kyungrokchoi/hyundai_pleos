"""Input limits, abuse cases and data-deletion regressions."""
from datetime import timedelta

import pytest
from sqlalchemy import func, select

from app.db import models as m
from app.db.session import SessionLocal
from app.services import trips as trips_svc
from tests.helpers import P, Actor, Trip, odo, reading, uid


def started(passengers=("B",), **kw):
    t = Trip(kw.pop("client"), passengers=passengers, **kw)
    t.ready_and_start(1000)
    return t


def test_chunked_body_over_limit_is_413_not_parsed(client):
    def chunks():
        for _ in range(40):
            yield b"x" * 8192                       # 320 KB, no Content-Length header
    r = client.post(f"{P}/auth/guest", content=chunks(), headers={"Content-Type": "application/json"})
    assert r.status_code == 413 and r.json()["code"] == "PAYLOAD_TOO_LARGE"


def test_naive_timestamp_never_500s_and_does_not_poison_the_batch(client):
    t = started(client=client)
    good = odo(1005, "MANUAL", event_id="good-event-001")
    bad = {**odo(1006, "MANUAL", event_id="bad-event-0001"), "captured_at": "2026-11-02T09:00:00"}   # no timezone
    r = t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [bad, good]})
    assert r.status_code == 200
    assert r.json()["accepted"] == 1 and r.json()["rejected"][0]["code"] == "TIMESTAMP_INVALID"
    end = t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": bad})
    assert end.status_code == 422 and end.json()["code"] == "TIMESTAMP_INVALID"


@pytest.mark.parametrize("rtype,value,unit", [("FUEL_ECONOMY", 1e-30, "L/100km"), ("ODOMETER", 1e30, "km"), ("EV_EFFICIENCY", 1e9, "km/kWh")])
def test_sensor_glitches_are_rejected_not_stored(client, rtype, value, unit):
    t = started(client=client)
    r = t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [reading(rtype, value, unit, event_id=f"glitch-{rtype}")]})
    assert r.json()["rejected"][0]["code"] == "VALUE_IMPLAUSIBLE"
    assert t.summary()["state"] == "IN_PROGRESS"          # the summary still renders


def test_absurd_numbers_in_requests_are_422(client):
    t = started(client=client)
    rule = {**t.rule, "unit_price_krw": 10 ** 30}
    assert t.driver.put(f"/trips/{t.trip_id}/cost-rule", rule).status_code == 422
    assert t.driver.put(f"/trips/{t.trip_id}/cost-rule", {**t.rule, "efficiency_value": 500}).json()["code"] == "EFFICIENCY_IMPLAUSIBLE"
    assert t.driver.post(f"/trips/{t.trip_id}/participants/{t.riders['B'].id}/board", {"odometer_km": 10 ** 30}).status_code == 422
    assert t.summary()["state"] == "IN_PROGRESS"


def test_trip_longer_than_5000_km_is_refused_at_end(client):
    t = started(client=client)
    r = t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": odo(1000 + 5001, "MANUAL")})
    assert r.status_code == 422 and r.json()["code"] == "DISTANCE_INVALID"


def test_cost_item_caps_keep_every_sum_inside_int32(client):
    t = Trip(client, passengers=("B",))
    for i in range(10):
        assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {"id": f"big-item-{i:03d}", "kind": "OTHER", "amount_krw": 100_000_000, "allocation": "EQUAL"}).status_code == 201
    over = t.driver.post(f"/trips/{t.trip_id}/cost-items", {"id": "big-item-over", "kind": "OTHER", "amount_krw": 1, "allocation": "EQUAL"})
    assert over.status_code == 422 and over.json()["code"] == "ITEMS_TOTAL_TOO_LARGE"
    t2 = Trip(client, passengers=("B",))
    for i in range(50):
        assert t2.driver.post(f"/trips/{t2.trip_id}/cost-items", {"id": f"tiny-item-{i:03d}", "kind": "OTHER", "amount_krw": 1, "allocation": "EQUAL"}).status_code == 201
    assert t2.driver.post(f"/trips/{t2.trip_id}/cost-items", {"id": "tiny-item-051", "kind": "OTHER", "amount_krw": 1, "allocation": "EQUAL"}).json()["code"] == "TOO_MANY_ITEMS"


def test_cost_item_ids_do_not_collide_across_groups(client):
    a, b = Trip(client, passengers=("B",)), Trip(client, passengers=("B",))
    for t in (a, b):
        assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {"id": "shared-item-id", "kind": "TOLL", "amount_krw": 700, "allocation": "EQUAL"}).status_code == 201
    assert len(a.summary()["cost_items"]) == 1 and len(b.summary()["cost_items"]) == 1
    assert a.driver.delete(f"/trips/{a.trip_id}/cost-items/shared-item-id").status_code == 200
    assert len(a.summary()["cost_items"]) == 0 and len(b.summary()["cost_items"]) == 1   # the other group's item is untouched


def test_member_decisions_only_apply_to_pending_people(client):
    t = Trip(client, passengers=("B",))
    bid = t.riders["B"].id
    again = t.driver.post(f"/groups/{t.group_id}/members/{bid}/decision", {"decision": "REJECT"})
    assert again.status_code == 409 and again.json()["code"] == "MEMBER_NOT_PENDING"      # cannot evict an active rider
    p = t.join("P", approve=False)
    assert t.driver.post(f"/groups/{t.group_id}/members/{p.id}/decision", {"decision": "REJECT"}).status_code == 200
    assert t.driver.post(f"/groups/{t.group_id}/members/{p.id}/decision", {"decision": "APPROVE"}).status_code == 409   # no silent re-admission


def test_pending_applicants_are_visible_to_driver_and_self_only(client):
    t = Trip(client, passengers=("B",))
    t.join("Pending", approve=False)
    names = lambda sm: {x["nickname"] for x in sm["participants"]}
    assert "Pending" in names(t.summary())
    assert "Pending" not in names(t.summary(t.riders["B"]))
    assert t.driver.get(f"/groups/{t.group_id}").json()["members"][-1]["status"] == "PENDING"
    assert all(mm["status"] == "ACTIVE" for mm in t.riders["B"].get(f"/groups/{t.group_id}").json()["members"])


def test_invite_can_be_revoked_and_session_can_be_ended(client):
    t = Trip(client, passengers=())
    inv = t.invite()
    assert t.driver.delete(f"/groups/{t.group_id}/invites/{inv['invite_id']}").status_code == 204
    assert client.post(f"{P}/invites/preview", json={"token": inv["token"]}).status_code == 410
    eve = Actor(client, "Eve")
    assert eve.delete(f"/groups/{t.group_id}/invites/{inv['invite_id']}").status_code == 404      # not even visible to outsiders
    assert eve.post("/auth/logout").status_code == 204
    assert eve.get("/groups").status_code == 401                                                  # the token no longer works


def test_missing_and_foreign_ids_look_identical(client):
    a, b = Trip(client, passengers=()), Trip(client, passengers=())
    missing = a.driver.get(f"/trips/{uid()}/summary")
    foreign = a.driver.get(f"/trips/{b.trip_id}/summary")
    group = a.driver.get(f"/groups/{b.group_id}")
    assert missing.status_code == foreign.status_code == group.status_code == 404
    assert missing.json() == foreign.json() == group.json()


@pytest.mark.parametrize("nick", ["‮evil", "zero​width", "nul\x00byte", "   ", "​", "line\nbreak"])
def test_hostile_nicknames_are_refused(client, nick):
    assert client.post(f"{P}/auth/guest", json={"nickname": nick, "consent_version": "v1"}).status_code == 422


def test_nicknames_are_normalised(client):
    r = client.post(f"{P}/auth/guest", json={"nickname": "ＡＢＣ서연", "consent_version": "v1"})   # full-width letters
    assert r.status_code == 201 and r.json()["nickname"] == "ABC서연"
    assert client.post(f"{P}/auth/guest", json={"nickname": "지호 🚗", "consent_version": "v1"}).status_code == 201


def test_boarding_cycles_are_capped(client):
    t = started(client=client)
    for i in range(trips_svc.MAX_BOARDINGS_PER_USER):
        t.board("B"); t.alight("B")
    r = t.riders["B"].post(f"/trips/{t.trip_id}/participants/me/board")
    assert r.status_code == 409 and r.json()["code"] == "TOO_MANY_BOARDINGS"


def test_reading_count_per_trip_is_capped(client, monkeypatch):
    t = started(client=client)
    monkeypatch.setattr(trips_svc, "MAX_READINGS_PER_TRIP", 3)
    r = t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [odo(1001 + i, "MANUAL", event_id=f"cap-event-{i:03d}") for i in range(5)]}).json()
    assert r["accepted"] == 2 and {x["code"] for x in r["rejected"]} == {"TRIP_READING_LIMIT"}      # start snapshot + 2 = 3


def test_pleos_provenance_is_described_as_reported_by_the_vehicle_app(client):
    rule = {"vehicle_type": "ICE", "efficiency_source": "PLEOS", "unit_price_krw": 1800, "driver_included": True, "energy_policy": "SEGMENT_SHARED"}
    t = Trip(client, data_mode="PLEOS", rule=rule, passengers=("B",))
    notice = [d for d in t.summary()["disclaimers"] if "차량 앱이" in d]
    assert notice and "보고" in notice[0] and "직접 검증하지는 못" in notice[0]


def test_security_headers_present(client):
    r = client.get(f"{P}/health")
    assert r.headers["x-content-type-options"] == "nosniff" and r.headers["cache-control"] == "no-store"


def test_docs_can_be_disabled(monkeypatch):
    from app.config import get_settings
    from app.main import create_app
    monkeypatch.setattr(get_settings(), "enable_docs", False)
    from fastapi.testclient import TestClient
    c = TestClient(create_app())
    assert c.get(f"{P}/docs").status_code == 404 and c.get(f"{P}/openapi.json").status_code == 404


def test_double_submitted_join_does_not_500(client):
    t = Trip(client, passengers=())
    tok = t.invite(max_uses=5)["token"]
    body = {"token": tok, "nickname": "Dup", "consent_version": "v1", "rules_ack": True}
    first = client.post(f"{P}/invites/accept", json=body)
    again = client.post(f"{P}/invites/accept", headers={"Authorization": f"Bearer {first.json()['token']}"}, json={**body, "nickname": None})
    assert first.status_code == 201 and again.status_code == 201 and again.json()["user_id"] == first.json()["user_id"]


def test_group_delete_also_removes_correction_and_cost_item_audit_trails(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(); t.board("B"); t.alight("B"); t.toll(); t.end(1024)
    iv = next(p for p in t.summary()["participants"] if p["nickname"] == "B")["intervals"][0]
    t.driver.patch(f"/trips/{t.trip_id}/intervals/{iv['id']}", {"odometer_off_km": 1010, "reason": "연락처 010-1234-5678 로 확인"})
    t.driver.delete(f"/trips/{t.trip_id}/cost-items/{t.summary()['cost_items'][0]['id']}")
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.AuditEvent)) > 0
    assert t.driver.delete(f"/groups/{t.group_id}").status_code == 204
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.AuditEvent)) == 0     # free-text reasons included


def test_retention_removes_idle_groups_expired_tokens_and_unjoined_guests(client, clock):
    from app.services.privacy import purge_expired
    old = Trip(client, passengers=("B",))
    loner = Actor(client, "Loner")                    # a guest that never joined anything
    with SessionLocal() as db:
        db.get(m.Trip, old.trip_id).created_at = clock.now - timedelta(days=100)
        db.get(m.Group, old.group_id).created_at = clock.now - timedelta(days=100)
        db.get(m.User, loner.id).created_at = clock.now - timedelta(days=100)
        for tok in db.scalars(select(m.AuthToken)):
            tok.expires_at = clock.now - timedelta(days=1)
        db.commit()
    with SessionLocal() as db:
        out = purge_expired(db, clock.now, 90)
        db.commit()
    assert out["trips"] == 1 and out["groups"] == 1 and out["tokens"] >= 1 and out["users"] >= 1
    with SessionLocal() as db:
        for model in (m.Trip, m.Group, m.GroupMember, m.User, m.AuthToken, m.AuditEvent, m.Invite):
            assert db.scalar(select(func.count()).select_from(model)) == 0, model.__name__
    assert loner.get("/groups").status_code == 401
