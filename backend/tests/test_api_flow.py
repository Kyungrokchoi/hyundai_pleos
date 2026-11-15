
from sqlalchemy import func, select

from app.db import models as m
from app.db.session import SessionLocal
from tests.helpers import Trip, odo, reading


def test_it01_two_passengers_join_drive_split_and_approve(client):
    t = Trip(client)
    t.ready_and_start(1000)
    t.board("B"); t.board("C")
    t.drive(1012)
    t.toll(2400)
    t.end(1024)
    st = t.preview()
    assert st.status_code == 200, st.text
    s = st.json()
    assert (s["total_krw"], s["energy_total_krw"], s["items_total_krw"]) == (6000, 3600, 2400)
    assert t.totals(s) == {"A": 2000, "B": 2000, "C": 2000}
    assert s["equation"].startswith("24km ÷ 12km/L × 1,800원/L = 3,600원")
    assert s["efficiency_source"] == "MANUAL" and s["state"] == "PROPOSED"
    assert t.summary()["state"] == "REVIEW"

    done = t.approve_all(s["id"])
    assert done["state"] == "CONFIRMED"

    seen = [t.summary(who) for who in (t.driver, *t.riders.values())]
    assert {x["settlement"]["id"] for x in seen} == {s["id"]} and {x["revision"] for x in seen} == {seen[0]["revision"]}
    assert {x["distance_km"] for x in seen} == {"24"}
    ch = t.driver.get(f"/groups/{t.group_id}/character").json()
    assert ch["xp"] == 10 and ch["level"] == 1 and {b["id"] for b in ch["badges"]} >= {"FIRST_RIDE", "TRIO", "CLEAN_SPLIT"}


def test_passenger_leaves_halfway_share_changes_and_is_explained(client):
    t = Trip(client)
    t.ready_and_start(1000)
    t.board("B"); t.board("C")
    t.drive(1010)
    t.alight("C")
    t.drive(1020)
    t.toll(2400)
    t.end(1024)
    s = t.preview().json()
    assert t.totals(s) == {"A": 2350, "B": 2350, "C": 1300}
    assert [g["energy_krw"] for g in s["segments"]] == [1500, 2100]
    assert [len(g["payers"]) for g in s["segments"]] == [3, 2]
    assert next(x for x in s["shares"] if x["user_id"] == t.riders["C"].id)["ridden_km"] == "10"


def test_duplicate_finish_and_retries_never_double_anything(client):
    t = Trip(client)
    t.ready_and_start(1000)
    t.board("B"); t.board("C")
    t.toll(2400, item_id="toll-fixed-1")
    first = t.end(1024)
    again = t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": odo(1024, "MANUAL", event_id="end-" + t.trip_id[:8])})
    assert again.status_code == 200 and again.json()["revision"] == first["revision"]
    other = t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": odo(1030, "MANUAL")})
    assert other.status_code == 409 and other.json()["code"] == "ALREADY_ENDED"
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {"id": "toll-fixed-1", "kind": "TOLL", "amount_krw": 2400, "allocation": "EQUAL"}).status_code == 201
    assert len(t.summary()["cost_items"]) == 1
    s1 = t.preview().json()
    s2 = t.preview().json()
    assert s1["id"] == s2["id"] and s1["revision"] == s2["revision"] == 1
    t.approve_all(s1["id"])
    t.driver.post(f"/settlements/{s1['id']}/approvals", {"decision": "APPROVE"})
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.RewardEvent)) == 1
        assert db.scalar(select(func.count()).select_from(m.Settlement)) == 1
    assert t.driver.get(f"/groups/{t.group_id}/character").json()["xp"] == 10


def test_it03_offline_sync_replay_is_idempotent(client):
    t = Trip(client)
    t.ready_and_start(1000)
    batch = {"readings": [odo(1000 + i, "MANUAL", event_id=f"ev-{i:03d}-aaaa") for i in range(1, 6)]}
    r1 = t.driver.post(f"/trips/{t.trip_id}/readings/batch", batch).json()
    r2 = t.driver.post(f"/trips/{t.trip_id}/readings/batch", batch).json()
    assert (r1["accepted"], r1["duplicates"]) == (5, 0) and (r2["accepted"], r2["duplicates"]) == (0, 5)
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.VehicleReading)) == 6


def test_ev_and_ice_use_separate_units_and_sources(client):
    ev_rule = {"vehicle_type": "EV", "efficiency_value": 5, "efficiency_unit": "km/kWh", "efficiency_source": "MANUAL",
               "unit_price_krw": 250, "driver_included": True, "energy_policy": "SEGMENT_SHARED"}
    t = Trip(client, rule=ev_rule, passengers=("B",))
    t.ready_and_start(0)
    t.board("B")
    t.end(60)
    s = t.preview().json()
    assert s["energy_total_krw"] == 3000 and s["consumption_unit"] == "kWh" and "km/kWh" in s["equation"] and "원/kWh" in s["equation"]

    bad = t.driver.put(f"/trips/{t.trip_id}/cost-rule", {**ev_rule, "efficiency_unit": "L/100km"})
    assert bad.status_code == 422 and bad.json()["code"] == "UNIT_UNKNOWN"


def test_pleos_efficiency_comes_from_sdk_unit_l_per_100km(client):
    rule = {"vehicle_type": "ICE", "efficiency_source": "PLEOS", "unit_price_krw": 1800, "driver_included": True, "energy_policy": "SEGMENT_SHARED"}
    t = Trip(client, data_mode="PLEOS", rule=rule, passengers=("B", "C"))
    t.ready_and_start(1000)
    t.board("B"); t.board("C")

    t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [reading("FUEL_ECONOMY", 8.333333, "L/100km", "PLEOS", event_id="fuel-1-aaaa")]})
    t.toll(2400)
    t.end(1024)
    s = t.preview().json()
    assert s["efficiency_source"] == "PLEOS" and s["total_krw"] == 6000
    rule_out = t.summary()["cost_rule"]
    assert rule_out["efficiency_value"] is None and rule_out["efficiency_source"] == "PLEOS"
    assert rule_out["efficiency_km_per_unit"] == "12"


def test_sdk_unavailable_degrades_gracefully_without_fabricating(client):
    rule = {"vehicle_type": "ICE", "efficiency_source": "PLEOS", "unit_price_krw": 1800, "driver_included": True, "energy_policy": "SEGMENT_SHARED"}
    t = Trip(client, data_mode="PLEOS", rule=rule, passengers=("B",))
    t.ready_and_start(1000)
    t.board("B")
    t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [
        reading("FUEL_ECONOMY", None, "L/100km", "PLEOS", quality="UNAVAILABLE", event_id="fuel-fail-aaaa", error="onFailed: ERROR_SECURITY_PERMISSION")]})
    t.end(1024)
    sm = t.summary()
    codes = {i["code"] for i in sm["issues"]}
    assert {"FUEL_ECONOMY_UNAVAILABLE", "EFFICIENCY_MISSING"} <= codes
    blocked = t.preview()
    assert blocked.status_code == 422 and blocked.json()["code"] == "SETTLEMENT_BLOCKED"

    lie = t.driver.put(f"/trips/{t.trip_id}/cost-rule", {**rule, "efficiency_value": 12, "efficiency_unit": "km/L"})
    assert lie.status_code == 422 and lie.json()["code"] == "EFFICIENCY_SOURCE_MISMATCH"

    ok = t.driver.put(f"/trips/{t.trip_id}/cost-rule", {**rule, "efficiency_source": "MANUAL", "efficiency_value": 12, "efficiency_unit": "km/L"})
    assert ok.status_code == 200
    s = t.preview().json()
    assert s["efficiency_source"] == "MANUAL" and s["energy_total_krw"] == 3600


def test_demo_data_is_labelled_everywhere_and_cannot_mix_with_real(client):
    rule = {"vehicle_type": "ICE", "efficiency_source": "DEMO", "unit_price_krw": 1800, "driver_included": True, "energy_policy": "SEGMENT_SHARED"}
    t = Trip(client, data_mode="DEMO", rule=rule, passengers=("B",))
    sm = t.summary(t.riders["B"])
    assert sm["data_mode"] == "DEMO" and any(i["code"] == "DEMO_DATA" for i in sm["issues"]) and any("DEMO" in d for d in sm["disclaimers"])
    t.ready_and_start(1000)
    r = t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [odo(1005, "PLEOS", event_id="real-in-demo-1")]}).json()
    assert r["accepted"] == 0 and r["rejected"][0]["code"] == "SOURCE_MISMATCH"


def test_odometer_regression_is_flagged_and_excluded(client):
    t = Trip(client)
    t.ready_and_start(1000)
    t.drive(1010)
    t.drive(900, event_id="regress-1-aaaa")
    sm = t.summary()
    assert any(i["code"] == "ODOMETER_REGRESSION" for i in sm["issues"])
    assert sm["latest_odometer"]["km"] == "1010"
    end = t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": odo(900, "MANUAL", quality="VERIFIED", event_id="end-bad-aaaa")})
    assert end.status_code == 422


def test_stale_boundary_is_marked_estimated(client, clock):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000)
    t.drive(1005)
    clock.advance(600)
    sm = t.board("B")
    iv = next(p for p in sm["participants"] if p["nickname"] == "B")["intervals"][0]
    assert iv["boundary_quality"] == "ESTIMATED"
    assert any(i["code"] == "INTERVAL_BOUNDARY_ESTIMATED" for i in sm["issues"])


def test_dispute_then_new_revision_then_confirm(client):
    t = Trip(client)
    t.ready_and_start(1000)
    t.board("B"); t.board("C")
    t.end(1024)
    s1 = t.preview().json()
    assert t.riders["B"].post(f"/settlements/{s1['id']}/approvals", {"decision": "DISPUTE", "comment": "통행료가 빠졌어요"}).json()["state"] == "DISPUTED"
    assert t.summary()["state"] == "DISPUTED"
    t.toll(2400)
    stale = t.riders["C"].post(f"/settlements/{s1['id']}/approvals", {"decision": "APPROVE"})
    assert stale.status_code == 409 and stale.json()["code"] == "SETTLEMENT_OUTDATED"
    s2 = t.preview().json()
    assert s2["revision"] == 2 and s2["total_krw"] == 6000 and s2["id"] != s1["id"]
    assert t.driver.post(f"/settlements/{s1['id']}/approvals", {"decision": "APPROVE"}).json()["code"] == "STALE_REVISION"
    done = t.approve_all(s2["id"])
    assert done["state"] == "CONFIRMED"
    assert "CLEAN_SPLIT" not in {b["id"] for b in t.driver.get(f"/groups/{t.group_id}/character").json()["badges"]}
    ch = t.driver.get(f"/groups/{t.group_id}/character").json()
    status = {b["id"]: b["earned"] for b in ch["all_badges"]}
    assert list(status) == ["FIRST_RIDE", "TRIO", "FIVE_TRIPS", "CLEAN_SPLIT"]
    assert status["CLEAN_SPLIT"] is False and status["FIRST_RIDE"] is True
    assert {b["id"] for b in ch["badges"]} == {k for k, v in status.items() if v}
    assert all((b["earned_at"] is not None) == b["earned"] for b in ch["all_badges"])


def test_new_group_shows_every_badge_unearned(client):
    t = Trip(client)
    ch = t.driver.get(f"/groups/{t.group_id}/character").json()
    assert ch["badges"] == [] and len(ch["all_badges"]) == 4 and not any(b["earned"] for b in ch["all_badges"])


def test_solo_trip_cannot_start(client):
    t = Trip(client, passengers=())
    r = t.driver.post(f"/trips/{t.trip_id}/ready")
    assert r.status_code == 422 and r.json()["code"] == "NO_PASSENGER"


def test_state_machine_guards(client):
    t = Trip(client, passengers=("B",))
    assert t.driver.post(f"/trips/{t.trip_id}/start", {"odometer": odo(1, "MANUAL")}).status_code == 409
    assert t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": odo(2, "MANUAL")}).status_code == 409
    t.ready_and_start(1000)
    assert t.driver.post(f"/trips/{t.trip_id}/settlements/preview").status_code == 409
    assert t.driver.post(f"/trips/{t.trip_id}/cancel").json()["state"] == "CANCELLED"
    assert t.driver.post(f"/trips/{t.trip_id}/cancel").status_code == 200
    assert t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [odo(1100, "MANUAL")]}).status_code == 409


def test_driver_can_correct_a_boundary_with_audit(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000)
    t.board("B")
    t.alight("B")
    t.end(1024)
    iv = next(p for p in t.summary()["participants"] if p["nickname"] == "B")["intervals"][0]
    r = t.driver.patch(f"/trips/{t.trip_id}/intervals/{iv['id']}", {"odometer_off_km": 1012, "reason": "하차 지점 정정"})
    assert r.status_code == 200
    out = next(p for p in r.json()["participants"] if p["nickname"] == "B")["intervals"][0]
    assert out["odometer_off_km"] == "1012" and out["boundary_quality"] == "ESTIMATED"
    with SessionLocal() as db:
        ev = db.scalar(select(m.AuditEvent).where(m.AuditEvent.action == "CORRECT"))
        assert ev is not None and ev.diff["reason"] == "하차 지점 정정"


def test_etag_polling(client):
    t = Trip(client, passengers=("B",))
    r = t.driver.get(f"/trips/{t.trip_id}/summary")
    etag = r.headers["etag"]
    assert t.driver.get(f"/trips/{t.trip_id}/summary", headers={"If-None-Match": etag}).status_code == 304
    t.toll(100)
    assert t.driver.get(f"/trips/{t.trip_id}/summary", headers={"If-None-Match": etag}).status_code == 200
