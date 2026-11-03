"""Regression tests: energy policy, locking, readings, settlement and membership edge cases."""
from datetime import timedelta
from decimal import Decimal as D

from sqlalchemy import select

from app.db import models as m
from app.db.session import SessionLocal
from app.services import cost_engine as ce
from app.services import rewards, trips as trips_svc
from tests.helpers import P, Actor, Trip, odo, reading, uid


def _req(policy, driver_included, ivs):
    return ce.SplitRequest("A", D(0), D(100), [ce.RideInterval(u, D(a), D(b)) for u, a, b in ivs],
                           ce.EnergyInput(ce.VehicleType.ICE, D(10), D(1500), ce.DataSource.MANUAL),
                           driver_included=driver_included, energy_policy=policy)


def test_equal_split_never_makes_passengers_pay_for_empty_car_km():
    """Trip 0..100 km (15,000 KRW of fuel), B rides 40..60 only. The 80 km of empty car are the driver's."""
    for included in (False, True):
        shared = ce.compute_split(_req(ce.EnergyPolicy.SEGMENT_SHARED, included, [("B", 40, 60)]))
        equal = ce.compute_split(_req(ce.EnergyPolicy.EQUAL_SPLIT, included, [("B", 40, 60)]))
        mine = {s.user_id: s.energy_krw for s in equal.shares}
        assert mine["A"] >= 12000, (included, mine)                        # at least the 80 empty km (12,000 KRW)
        assert sum(mine.values()) == equal.energy_total_krw == 15000
        assert equal.shares[0].energy_krw >= shared.shares[0].energy_krw - 1500
    exc = ce.compute_split(_req(ce.EnergyPolicy.EQUAL_SPLIT, False, [("B", 40, 60)]))
    assert {s.user_id: s.total_krw for s in exc.shares} == {"A": 12000, "B": 3000}   # same as SEGMENT_SHARED here


def test_equation_shows_enough_digits_to_recompute():
    res = ce.compute_split(ce.SplitRequest(
        "A", D(0), D(24), [], ce.EnergyInput(ce.VehicleType.ICE, ce.fuel_economy_to_km_per_l("8.3", "L/100km"), D("1799.999"), ce.DataSource.PLEOS)))
    assert "12.048km/L" in res.equation and "1,799.999원/L" in res.equation


def test_second_session_sees_the_committed_state_after_taking_the_lock(client):
    """lock_trip must reload the row, not return the stale identity-map copy."""
    t = Trip(client, passengers=("B",))
    with SessionLocal() as a, SessionLocal() as b:
        trip_a = a.get(m.Trip, t.trip_id)
        assert trip_a.state == "INVITING"
        trip_b = trips_svc.lock_trip(b, t.trip_id)
        trip_b.state = "CANCELLED"
        b.commit()
        assert trips_svc.lock_trip(a, t.trip_id).state == "CANCELLED"


def test_two_trips_confirming_at_once_both_pay_xp(client):
    """Lost-update on character_progress: xp must be 20 after two confirmed trips, not 10."""
    a = Trip(client, passengers=("B",))
    with SessionLocal() as db:
        t1 = db.get(m.Trip, a.trip_id)
        t1.odometer_start_km, t1.odometer_end_km = D(0), D(10)
        t2 = m.Trip(id=uid(), group_id=a.group_id, driver_user_id=a.driver.id, data_mode="MANUAL", odometer_start_km=D(0), odometer_end_km=D(10))
        db.add(t2)
        db.flush()
        assert rewards.award_for_trip(db, t1, [D(10)]) == 10
        assert rewards.award_for_trip(db, t2, [D(10)]) == 10
        assert rewards.award_for_trip(db, t1, [D(10)]) == 0           # same trip again: nothing
        db.commit()
        assert db.get(m.CharacterProgress, a.group_id).xp == 20


def test_a_zero_km_boarding_earns_no_xp(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000)
    t.board("B")
    t.alight("B")                                                     # boarded and left at the same odometer
    t.end(1000)
    s = t.preview().json()
    t.approve_all(s["id"])
    assert t.driver.get(f"/groups/{t.group_id}/character").json()["xp"] == 0


def test_reading_cap_never_blocks_closing_the_trip_and_replays_stay_duplicates(client, monkeypatch):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000)
    monkeypatch.setattr(trips_svc, "MAX_READINGS_PER_TRIP", 3)
    batch = {"readings": [odo(1001 + i, "MANUAL", event_id=f"cap2-event-{i:03d}") for i in range(4)]}
    first = t.driver.post(f"/trips/{t.trip_id}/readings/batch", batch).json()
    assert first["accepted"] == 2
    replay = t.driver.post(f"/trips/{t.trip_id}/readings/batch", batch).json()
    assert replay["duplicates"] == 2 and {x["code"] for x in replay["rejected"]} == {"TRIP_READING_LIMIT"}
    assert t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": odo(1024, "MANUAL", event_id="end-after-cap")}).status_code == 200


def test_event_id_of_another_kind_cannot_become_the_start_odometer(client):
    t = Trip(client, passengers=("B",))
    assert t.driver.post(f"/trips/{t.trip_id}/ready").status_code == 200
    t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [reading("FUEL_ECONOMY", 12, "km/L", event_id="shared-event-id")]})
    r = t.driver.post(f"/trips/{t.trip_id}/start", {"odometer": odo(1000, "MANUAL", event_id="shared-event-id")})
    assert r.status_code == 422 and r.json()["code"] == "EVENT_ID_CONFLICT"
    assert t.summary()["state"] == "READY" and t.summary()["odometer_start_km"] is None


def test_only_one_trip_can_be_running_per_group(client):
    a = Trip(client, passengers=("B",))
    a.ready_and_start(1000)
    t2 = uid()
    assert a.driver.post("/trips", {"id": t2, "group_id": a.group_id, "data_mode": "MANUAL", "cost_rule": a.rule}).status_code == 409
    # a second trip created earlier cannot start either
    with SessionLocal() as db:
        db.add(m.Trip(id=t2, group_id=a.group_id, driver_user_id=a.driver.id, data_mode="MANUAL", state="READY"))
        db.add(m.CostRule(trip_id=t2, vehicle_type="ICE", efficiency_value=12, efficiency_unit="km/L", efficiency_source="MANUAL", unit_price_krw=1800))
        db.commit()
    r = a.driver.post(f"/trips/{t2}/start", {"odometer": odo(5, "MANUAL", event_id="second-start-1")})
    assert r.status_code == 409 and r.json()["code"] == "TRIP_IN_PROGRESS"


def test_a_typo_can_be_voided_so_the_trip_can_still_end(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000)
    t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [odo(10240, "MANUAL", event_id="typo-reading-1")]})   # meant 1024.0
    stuck = t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": odo(1024, "MANUAL", event_id="end-try-1")})
    assert stuck.status_code == 422                                   # below the typo: odometer regression
    assert t.driver.delete(f"/trips/{t.trip_id}/readings/typo-reading-1").status_code == 200
    assert t.driver.delete(f"/trips/{t.trip_id}/readings/does-not-exist-1").status_code == 404
    assert t.riders["B"].delete(f"/trips/{t.trip_id}/readings/typo-reading-1").status_code == 403
    assert t.driver.post(f"/trips/{t.trip_id}/end", {"odometer": odo(1024, "MANUAL", event_id="end-try-2")}).status_code == 200
    with SessionLocal() as db:
        assert db.scalar(select(m.AuditEvent).where(m.AuditEvent.action == "VOID")) is not None


def test_alighting_inside_the_tolerance_does_not_reverse_the_interval(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000)
    t.drive(1010)
    t.board("B")
    with SessionLocal() as db:                                         # sensor jitter: the next reading is 0.3 km lower
        db.add(m.VehicleReading(trip_id=t.trip_id, event_id="jitter-0001", type="ODOMETER", captured_at=m.utcnow() + timedelta(seconds=1),
                                raw_value=D("1009.7"), raw_unit="km", normalized_value=D("1009.7"), normalized_unit="km", source="MANUAL", quality="VERIFIED"))
        db.commit()
    t.alight("B")
    t.end(1024)
    s = t.preview()
    assert s.status_code == 200, s.text


def test_timestamps_far_in_the_past_are_rejected_not_a_500(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000)
    for ts in ("0001-01-01T00:00:00+09:00", "2000-01-01T00:00:00+00:00"):
        r = t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [{**odo(1005, "MANUAL", event_id=f"old-{ts[:4]}-aaaa"), "captured_at": ts}]})
        assert r.status_code == 200 and r.json()["rejected"][0]["code"] == "TIMESTAMP_INVALID"


def test_etag_changes_when_the_stale_data_warning_appears(client, clock):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000)
    t.drive(1005)
    fresh = t.driver.get(f"/trips/{t.trip_id}/summary")
    assert not any(i["code"] == "ODOMETER_STALE" for i in fresh.json()["issues"])
    clock.advance(900)
    again = t.driver.get(f"/trips/{t.trip_id}/summary", headers={"If-None-Match": fresh.headers["etag"]})
    assert again.status_code == 200 and any(i["code"] == "ODOMETER_STALE" for i in again.json()["issues"])


def test_concurrent_identical_trip_create_is_idempotent(client):
    t = Trip(client, passengers=())
    body = {"id": uid(), "group_id": t.group_id, "data_mode": "MANUAL", "cost_rule": t.rule}
    a, b = t.driver.post("/trips", body), t.driver.post("/trips", body)
    assert a.status_code == 201 and b.status_code == 201 and a.json()["id"] == b.json()["id"]


def test_disputed_settlement_can_be_reopened_without_changing_numbers(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000); t.board("B"); t.end(1024)
    s1 = t.preview().json()
    t.riders["B"].post(f"/settlements/{s1['id']}/approvals", {"decision": "DISPUTE", "comment": "확인 부탁해요"})
    s2 = t.preview().json()                                           # re-proposed with unchanged inputs
    assert s2["id"] == s1["id"] and s2["revision"] == 2 and s2["state"] == "PROPOSED"
    assert all(x["approval"] == "PENDING" for x in s2["shares"])
    assert t.approve_all(s2["id"])["state"] == "CONFIRMED"


def test_my_membership_tells_pending_from_rejected_and_hides_strangers(client):
    t = Trip(client, passengers=())
    pending = t.join("Pat", approve=False)
    assert pending.get(f"/groups/{t.group_id}/me").json()["status"] == "PENDING"
    t.driver.post(f"/groups/{t.group_id}/members/{pending.id}/decision", {"decision": "REJECT"})
    assert pending.get(f"/groups/{t.group_id}/me").json()["status"] == "REJECTED"
    assert Actor(client, "Eve").get(f"/groups/{t.group_id}/me").status_code == 404


def test_unexpected_errors_are_korean_json_without_details(client, monkeypatch):
    from fastapi.testclient import TestClient
    from app.main import app
    monkeypatch.setattr("app.api.groups.my_groups", lambda *a, **k: 1 / 0, raising=False)
    from app.api import groups
    monkeypatch.setattr(groups.rewards, "character_out", lambda *a, **k: 1 / 0)
    t = Trip(client, passengers=("B",))
    quiet = TestClient(app, raise_server_exceptions=False)
    r = quiet.get(f"{P}/groups/{t.group_id}/character", headers=t.driver.h)
    assert r.status_code == 500 and r.json()["code"] == "INTERNAL_ERROR" and "ZeroDivision" not in r.text


def test_put_cost_rule_answers_with_the_trip_summary_shape(client):
    """The car app decodes the PUT /cost-rule response as a TripSummary."""
    t = Trip(client, passengers=("B",))
    put = t.driver.put(f"/trips/{t.trip_id}/cost-rule", {**t.rule, "efficiency_value": 11, "efficiency_unit": "km/L"})
    assert put.status_code == 200
    assert set(put.json()) == set(t.summary())
    assert put.json()["cost_rule"]["efficiency_km_per_unit"] == "11"


def test_history_distance_is_tidy(client):
    t = Trip(client, passengers=("B",))
    t.ready_and_start(1000); t.board("B"); t.end(1024)
    assert t.driver.get(f"/groups/{t.group_id}/history").json()[0]["distance_km"] == "24"


def test_boarding_while_inviting_starts_at_the_first_snapshot(client):
    """Riders, or the driver for them, can board while the trip is still INVITING."""
    t = Trip(client)
    assert t.summary()["state"] == "INVITING"
    t.board("B")
    r = t.driver.post(f"/trips/{t.trip_id}/participants/{t.riders['C'].id}/board")
    assert r.status_code == 200, r.text
    assert {p["nickname"]: p["on_board"] for p in t.summary()["participants"] if not p["is_driver"]} == {"B": True, "C": True}
    t.ready_and_start(1000)
    t.drive(1012)
    t.toll(2400)
    t.end(1024)
    s = t.preview().json()
    assert t.totals(s) == {"A": 2000, "B": 2000, "C": 2000}
    with SessionLocal() as db:
        by_driver = [a.diff for a in db.scalars(select(m.AuditEvent).where(m.AuditEvent.entity_id == t.trip_id, m.AuditEvent.action == "BOARD"))]
    assert sorted(d["by_driver"] for d in by_driver) == [False, True]


def test_alight_before_departure_forgets_the_interval(client):
    t = Trip(client)
    t.board("B"); t.alight("B")
    t.board("C")
    t.ready_and_start(1000)
    t.end(1010)
    s = t.preview().json()
    assert set(t.totals(s)) == {"A", "C"} and sum(t.totals(s).values()) == s["total_krw"]


def test_pending_member_cannot_board_before_approval(client):
    t = Trip(client, passengers=())
    a = t.join("D", approve=False)
    assert a.post(f"/trips/{t.trip_id}/participants/me/board").status_code == 404


def test_cancelled_trip_has_nobody_on_board(client):
    t = Trip(client)
    t.board("B")
    assert t.driver.post(f"/trips/{t.trip_id}/cancel").status_code == 200
    assert not any(p["on_board"] for p in t.summary()["participants"])


def test_undo_that_lands_right_after_start_is_not_a_paid_ride(client):
    """An undo that arrives after /start but still at the start km is not a ride."""
    t = Trip(client)
    t.board("B"); t.board("C")
    t.ready_and_start(1000)
    r = t.riders["C"].post(f"/trips/{t.trip_id}/participants/me/alight", {"undo": True})   # odometer still at the start snapshot
    assert r.status_code == 200, r.text
    t.drive(1012); t.toll(2400); t.end(1024)
    s = t.preview().json()
    assert set(t.totals(s)) == {"A", "B"} and sum(t.totals(s).values()) == s["total_krw"]


def test_undo_after_the_car_moved_is_a_normal_alight(client):
    t = Trip(client, passengers=("B",))
    t.board("B"); t.ready_and_start(1000); t.drive(1005)
    assert t.riders["B"].post(f"/trips/{t.trip_id}/participants/me/alight", {"undo": True}).status_code == 200
    t.end(1010)
    assert t.totals(t.preview().json())["B"] > 0                     # rode 5 km


def test_driver_mark_after_rider_tap_keeps_one_honest_record(client):
    t = Trip(client)
    t.board("B")
    assert t.driver.post(f"/trips/{t.trip_id}/participants/{t.riders['B'].id}/board").status_code == 200   # duplicate mark from the driver
    assert t.driver.post(f"/trips/{t.trip_id}/participants/{t.riders['C'].id}/board").status_code == 200
    with SessionLocal() as db:
        rows = [a.diff for a in db.scalars(select(m.AuditEvent).where(m.AuditEvent.entity_id == t.trip_id, m.AuditEvent.action == "BOARD"))]
    assert sorted((d["user_id"] == t.riders["C"].id, d["by_driver"]) for d in rows) == [(False, False), (True, True)]
    opened = {p["nickname"]: [i["opened_by"] for i in p["intervals"]] for p in t.summary()["participants"] if not p["is_driver"]}
    assert opened == {"B": ["USER"], "C": ["DRIVER"]}


def test_returning_rider_is_pointed_at_todays_trip(client):
    """/me points a returning rider at the group's new trip."""
    t = Trip(client, passengers=("B",))
    t.board("B"); t.ready_and_start(1000); t.end(1010)
    t.approve_all(t.preview().json()["id"])
    me = t.riders["B"].get(f"/groups/{t.group_id}/me").json()
    assert me["status"] == "ACTIVE" and me["current_trip_id"] is None          # nothing open after CONFIRMED
    new_id = uid()
    assert t.driver.post("/trips", {"id": new_id, "group_id": t.group_id, "data_mode": "MANUAL", "cost_rule": t.rule}).status_code == 201
    assert t.riders["B"].get(f"/groups/{t.group_id}/me").json()["current_trip_id"] == new_id
    assert t.riders["B"].post(f"/trips/{new_id}/participants/me/board").status_code == 200   # and can board it without re-joining


def test_pending_member_is_not_told_about_trips(client):
    t = Trip(client, passengers=())
    a = t.join("D", approve=False)
    assert a.get(f"/groups/{t.group_id}/me").json()["current_trip_id"] is None
