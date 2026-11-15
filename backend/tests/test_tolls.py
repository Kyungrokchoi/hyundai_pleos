import json
from datetime import datetime, timedelta, timezone

import pytest

from app.config import get_settings
from app.db import models as m
from app.db.session import SessionLocal
from app.services import exco, tolls
from tests.helpers import Trip, uid

SEOUL = tolls.Gate("1", "서울TG", 37.5000, 127.0000, "101")
SEOUL_LANE2 = tolls.Gate("1b", "서울TG", 37.5001, 127.0001, "101")
SUWON = tolls.Gate("2", "수원IC", 37.2600, 127.0300, "102")
NOCODE = tolls.Gate("3", "이름만있음", 37.4000, 127.0100, None)
FARES = {("101", "102"): 4800}
MON_9AM_KST = datetime(2026, 11, 2, 0, 0)
NAMSAN = {"name": "남산터널", "lat": 37.5500, "lon": 126.9900, "radius_m": 300, "fee_krw": 2000,
          "weekdays": [0, 1, 2, 3, 4], "start_hour": 7, "end_hour": 21, "exempt_min_occupants": 3}


def pt(lat, lon, at=MON_9AM_KST):
    return tolls.Point(lat, lon, at)


def fare_of(a, b):
    return FARES.get((a, b))


@pytest.mark.parametrize("raw,expected", [("서울TG", "서울"), ("수원 IC", "수원"), ("서울 톨게이트", "서울"), ("칠곡요금소(진입)", "칠곡"),
                                          ("단성 출구", "단성"), ("TG안산", "안산"), ("고양 (남)", "고양"), ("IC", "IC")])
def test_names_are_normalised_for_matching(raw, expected):
    assert tolls.normalize_name(raw) == expected


def test_staying_inside_a_gate_counts_once_and_order_is_kept():
    track = [pt(37.5000, 127.0000), pt(37.5001, 127.0001), pt(37.4000, 127.0100), pt(37.2600, 127.0300)]
    assert [g.name for g in tolls.detect_passes(track, [SEOUL, SUWON])] == ["서울TG", "수원IC"]


def test_leaving_and_returning_counts_twice():
    track = [pt(37.5000, 127.0000), pt(37.4000, 127.0100), pt(37.5000, 127.0000)]
    assert len(tolls.detect_passes(track, [SEOUL])) == 2


def test_a_gate_between_two_sparse_fixes_is_still_detected():

    track = [pt(37.4950, 127.0000), pt(37.5050, 127.0000)]
    assert [g.name for g in tolls.detect_passes(track, [SEOUL])] == ["서울TG"]


def test_a_gate_off_to_the_side_of_the_segment_is_not_detected():
    track = [pt(37.4950, 127.0100), pt(37.5050, 127.0100)]
    assert tolls.detect_passes(track, [SEOUL]) == []


def test_two_gates_between_the_same_two_fixes_come_in_driving_order():
    north = tolls.Gate("9", "북쪽IC", 37.5300, 127.0000, "999")
    south_to_north = [pt(37.4950, 127.0000), pt(37.5350, 127.0000)]
    assert [g.name for g in tolls.detect_passes(south_to_north, [north, SEOUL])] == ["서울TG", "북쪽IC"]
    assert [g.name for g in tolls.detect_passes(south_to_north[::-1], [north, SEOUL])] == ["북쪽IC", "서울TG"]


def test_a_wild_jump_is_not_treated_as_driving_but_its_end_points_still_count():
    far = pt(37.4219983, -122.084)
    track = [far, pt(37.5000, 127.0000), pt(37.2600, 127.0300)]
    assert [g.name for g in tolls.detect_passes(track, [SEOUL, SUWON])] == ["서울TG", "수원IC"]
    assert tolls.detect_passes([far, pt(37.9, 127.9)], [SEOUL, SUWON]) == []


def test_a_jump_over_the_segment_limit_does_not_count_the_gates_it_crosses():
    jump = [pt(37.4400, 127.0000), pt(37.5400, 127.0000)]
    assert tolls.detect_passes(jump, [SEOUL]) == []
    walk = [pt(37.4900, 127.0000), pt(37.5100, 127.0000)]
    assert [g.name for g in tolls.detect_passes(walk, [SEOUL])] == ["서울TG"]


def test_a_single_fix_still_counts_when_it_is_inside_the_radius():
    assert [g.name for g in tolls.detect_passes([pt(37.5000, 127.0000)], [SEOUL, SUWON])] == ["서울TG"]
    assert tolls.detect_passes([], [SEOUL]) == []


def test_several_booths_of_one_unit_in_a_row_are_one_pass():
    track = [pt(37.5000, 127.0000), pt(37.5001, 127.0001)]
    assert len(tolls.detect_passes(track, [SEOUL, SEOUL_LANE2], radius_m=5)) == 1


def test_pair_with_known_fare_becomes_a_segment_and_unknown_is_reported():
    segs, unmatched = tolls.estimate_segments([SEOUL, SUWON], fare_of)
    assert [(s.entry, s.exit, s.amount_krw) for s in segs] == [("서울TG", "수원IC", 4800)] and unmatched == []
    segs, unmatched = tolls.estimate_segments([SUWON, SEOUL], fare_of)
    assert segs == [] and unmatched == ["수원IC", "서울TG"]


def test_the_same_unit_twice_is_never_priced_as_a_trip():
    asked = []

    def spy(a, b):
        asked.append((a, b))
        return 900

    segs, unmatched = tolls.estimate_segments([SEOUL, SEOUL_LANE2], spy)
    assert segs == [] and asked == [] and unmatched == ["서울TG", "서울TG"]


def test_gates_without_a_unit_code_are_never_priced():
    segs, unmatched = tolls.estimate_segments([SEOUL, NOCODE], lambda a, b: 1)
    assert segs == [] and unmatched == ["서울TG", "이름만있음"]


def test_after_a_paid_pair_the_next_gate_starts_a_new_trip():
    other = tolls.Gate("4", "대전IC", 36.3, 127.4, "152")
    segs, unmatched = tolls.estimate_segments([SEOUL, SUWON, other], fare_of)
    assert len(segs) == 1 and unmatched == ["대전IC"]


def test_zone_charge_window_occupancy_and_weekend():
    inside = [pt(37.5500, 126.9900)]
    assert [c.amount_krw for c in tolls.zone_charges(inside, [NAMSAN], occupants=1)] == [2000]
    assert tolls.zone_charges(inside, [NAMSAN], occupants=3) == []
    saturday = [pt(37.5500, 126.9900, datetime(2026, 11, 7, 0, 0))]
    assert tolls.zone_charges(saturday, [NAMSAN], occupants=1) == []
    night = [pt(37.5500, 126.9900, MON_9AM_KST + timedelta(hours=13))]
    assert tolls.zone_charges(night, [NAMSAN], occupants=1) == []
    assert len(tolls.zone_charges(inside * 5, [NAMSAN], occupants=None)) == 1


def test_timezone_aware_times_are_converted_to_utc():
    kst = timezone(timedelta(hours=9))
    assert tolls.to_naive_utc(datetime(2026, 11, 2, 9, 0, tzinfo=kst)) == MON_9AM_KST




def api_reply(**fares):
    row = {"dprtrTolofCd": "101", "arrvTolofCd": "102", **{f"nrmlKnd{k}Amt": v for k, v in fares.items()}}
    return json.dumps({"count": 1, "list": [row], "code": "SUCCESS"})


class FakeApi:
    def __init__(self, text):
        self.text, self.urls = text, []

    def __call__(self, url):
        self.urls.append(url)
        return self.text


def test_parse_fare_picks_the_vehicle_class_and_treats_missing_as_none():
    assert exco.parse_fare(api_reply(**{"1": "1900", "2": "2000"}), 2) == 2000
    assert exco.parse_fare(api_reply(**{"1": "1900"}), 7) is None
    assert exco.parse_fare(json.dumps({"count": 0, "list": None}), 1) is None
    assert exco.parse_fare(api_reply(**{"1": "0"}), 1) is None


def test_lookup_calls_the_api_once_then_serves_from_the_cache():
    api = FakeApi(api_reply(**{"1": "4800"}))
    with SessionLocal() as db:
        look = exco.FareLookup(db, "KEY", fetch=api)
        assert look("101", "102") == 4800 and look("101", "102") == 4800
        assert len(api.urls) == 1 and look.remote_calls == 1 and not look.failed
        assert "key=KEY" in api.urls[0] and "dprtrTolofCd=101" in api.urls[0] and "arrvTolofCd=102" in api.urls[0]
        assert db.get(m.TollFareCache, {"dep_code": "101", "arr_code": "102", "vehicle_class": 1}).amount_krw == 4800


def test_a_pair_without_a_fare_is_cached_too():
    api = FakeApi(json.dumps({"count": 0, "list": None}))
    with SessionLocal() as db:
        look = exco.FareLookup(db, "KEY", fetch=api)
        assert look("101", "999") is None and look("101", "999") is None
        assert len(api.urls) == 1 and not look.failed


def test_expired_cache_entries_are_refreshed():
    api = FakeApi(api_reply(**{"1": "5000"}))
    with SessionLocal() as db:
        db.add(m.TollFareCache(dep_code="101", arr_code="102", vehicle_class=1, amount_krw=4800,
                               fetched_at=m.utcnow() - exco.HIT_TTL - timedelta(days=1)))
        db.commit()
        assert exco.FareLookup(db, "KEY", fetch=api)("101", "102") == 5000


def test_network_failure_is_flagged_and_not_cached():
    def boom(url):
        raise TimeoutError("slow")
    with SessionLocal() as db:
        look = exco.FareLookup(db, "KEY", fetch=boom)
        assert look("101", "102") is None and look.failed
        assert db.get(m.TollFareCache, {"dep_code": "101", "arr_code": "102", "vehicle_class": 1}) is None


def test_per_request_limit_and_missing_key_never_call_out():
    api = FakeApi(api_reply(**{"1": "4800"}))
    with SessionLocal() as db:
        look = exco.FareLookup(db, "KEY", max_remote=1, fetch=api)
        assert look("101", "102") == 4800 and look("201", "202") is None and look.failed and len(api.urls) == 1
        nokey = exco.FareLookup(db, "", fetch=api)
        assert nokey("301", "302") is None and nokey.failed and len(api.urls) == 1




@pytest.fixture
def toll_env(tmp_path, monkeypatch):
    gates = [{"id": g.id, "name": g.name, "lat": g.lat, "lon": g.lon, "unit_code": g.code} for g in (SEOUL, SUWON, NOCODE)]
    (tmp_path / "toll_gates.json").write_text(json.dumps(gates), encoding="utf-8")
    (tmp_path / "toll_zones.json").write_text(json.dumps([NAMSAN]), encoding="utf-8")
    api = FakeApi(api_reply(**{"1": "4800"}))
    monkeypatch.setattr(exco, "_http_get", api)
    monkeypatch.setattr(get_settings(), "toll_data_dir", str(tmp_path))
    monkeypatch.setattr(get_settings(), "exco_key", "KEY")
    tolls.get_data.cache_clear()
    yield api
    tolls.get_data.cache_clear()


def body(points, **kw):
    return {"points": [{"lat": a, "lon": b, "captured_at": MON_9AM_KST.replace(tzinfo=timezone.utc).isoformat()} for a, b in points], **kw}


def test_estimate_endpoint_returns_highway_and_zone_lines(client, toll_env):
    t = Trip(client)
    r = t.driver.post(f"/trips/{t.trip_id}/tolls/estimate", body([(37.5, 127.0), (37.55, 126.99), (37.26, 127.03)], occupants=2))
    assert r.status_code == 200, r.text
    out = r.json()
    assert out["data_available"] and out["estimated"] is True and not out["incomplete"] and "OpenStreetMap" in out["attribution"]
    assert {(s["kind"], s["road_name"], s["amount_krw"]) for s in out["segments"]} == {("HIGHWAY", "서울TG→수원IC", 4800), ("ZONE", "남산터널", 2000)}
    assert out["total_krw"] == 6800 and len(toll_env.urls) == 1
    assert [(g["name"], g["lat"], g["lon"]) for g in out["passes"]] == [("서울TG", SEOUL.lat, SEOUL.lon), ("수원IC", SUWON.lat, SUWON.lon)]


def test_estimate_without_data_or_key_says_so(client, tmp_path, monkeypatch):
    monkeypatch.setattr(get_settings(), "toll_data_dir", str(tmp_path))
    monkeypatch.setattr(get_settings(), "exco_key", "")
    tolls.get_data.cache_clear()
    t = Trip(client)
    out = t.driver.post(f"/trips/{t.trip_id}/tolls/estimate", body([(37.5, 127.0)])).json()
    assert out["data_available"] is False and out["segments"] == [] and out["total_krw"] == 0
    tolls.get_data.cache_clear()


def test_estimate_marks_incomplete_when_the_fare_api_fails(client, toll_env, monkeypatch):
    def boom(url):
        raise TimeoutError("slow")
    monkeypatch.setattr(exco, "_http_get", boom)
    t = Trip(client)
    out = t.driver.post(f"/trips/{t.trip_id}/tolls/estimate", body([(37.5, 127.0), (37.26, 127.03)])).json()
    assert out["incomplete"] is True and out["segments"] == [] and out["unmatched_gates"] == ["서울TG", "수원IC"]


def test_only_the_driver_may_estimate_and_input_is_validated(client, toll_env):
    t = Trip(client)
    assert t.riders["B"].post(f"/trips/{t.trip_id}/tolls/estimate", body([(37.5, 127.0)])).status_code == 403
    assert t.driver.post(f"/trips/{t.trip_id}/tolls/estimate", {"points": []}).status_code == 422
    assert t.driver.post(f"/trips/{t.trip_id}/tolls/estimate", body([(91, 0)])).status_code == 422


def test_estimated_toll_item_keeps_road_and_flag(client):
    t = Trip(client)
    r = t.driver.post(f"/trips/{t.trip_id}/cost-items", {"id": uid(), "kind": "TOLL", "amount_krw": 4800, "allocation": "EQUAL", "road_name": "서울TG→수원IC", "estimated": True})
    assert r.status_code == 201, r.text
    item = r.json()["cost_items"][0]
    assert (item["road_name"], item["estimated"], item["input_source"]) == ("서울TG→수원IC", True, "ESTIMATE")


def test_settlement_lines_name_the_road_and_mark_estimates(client):
    t = Trip(client)
    t.ready_and_start(1000)
    t.board("B"), t.board("C")
    t.end(1024)
    est = {"kind": "TOLL", "allocation": "EQUAL", "estimated": True}
    manual = {"kind": "PARKING", "allocation": "EQUAL"}
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {**est, "id": uid(), "amount_krw": 1900, "road_name": "동수원→서울"}).status_code == 201
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {**manual, "id": uid(), "amount_krw": 3000}).status_code == 201
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {**est, "id": uid(), "amount_krw": 500, "road_name": ""}).status_code == 201
    r = t.preview()
    assert r.status_code == 200, r.text
    labels = sorted(line["label"] for line in r.json()["shares"][0]["items"])
    assert labels == sorted(["통행료 (동수원→서울) · 추정", "주차비", "통행료 · 추정"])


def test_old_style_item_without_new_fields_still_works(client):
    t = Trip(client)
    item = t.toll()["cost_items"][0]
    assert (item["road_name"], item["estimated"], item["input_source"]) == ("", False, "MANUAL")


def test_same_item_id_with_different_road_is_a_conflict(client):
    t = Trip(client)
    iid = uid()
    base = {"id": iid, "kind": "TOLL", "amount_krw": 1000, "allocation": "EQUAL"}
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {**base, "road_name": "A"}).status_code == 201
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {**base, "road_name": "A"}).status_code == 201
    assert t.driver.post(f"/trips/{t.trip_id}/cost-items", {**base, "road_name": "B"}).status_code == 409
