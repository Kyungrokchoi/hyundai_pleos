import json
from decimal import Decimal

import pytest

from app.config import get_settings
from app.services import efficiency
from tests.helpers import MANUAL_ICE_RULE, Trip, reading, uid


def rows(*specs):
    return [{"company": "c", "model": "m", "kind": kind, "type": "t", "fuel": fuel, "year": 2024, "display_eff": eff} for fuel, kind, eff in specs]


@pytest.fixture
def data_dir(tmp_path):
    efficiency.clear_cache()
    (tmp_path / "car_efficiency.json").write_text(json.dumps(rows(
        ("휘발유", "승용차", 10.0), ("휘발유", "승용차", 11.0), ("휘발유", "화물차", 5.0), ("휘발유", "승용차", None),
        ("경유", "승용차", 13.0), ("전기", "승용차", 4.0), ("전기", "승용차", 5.0),
    ), ensure_ascii=False), encoding="utf-8")
    yield str(tmp_path)
    efficiency.clear_cache()


def test_average_uses_only_passenger_cars_of_that_fuel_with_a_rating(data_dir):
    a = efficiency.rated_average("GASOLINE", data_dir)
    assert (a.value, a.unit, a.sample_size) == (Decimal("10.5"), "km/L", 2)


def test_electric_uses_km_per_kwh(data_dir):
    a = efficiency.rated_average("ELECTRIC", data_dir)
    assert (a.value, a.unit) == (Decimal("4.5"), "km/kWh")


def test_mean_is_rounded_to_one_decimal(data_dir, tmp_path):
    (tmp_path / "car_efficiency.json").write_text(json.dumps(rows(("경유", "승용차", 10.0), ("경유", "승용차", 10.1), ("경유", "승용차", 10.2))), encoding="utf-8")
    efficiency.clear_cache()
    assert efficiency.rated_average("DIESEL", data_dir).value == Decimal("10.1")


def test_unknown_fuel_or_empty_dataset_gives_none(data_dir, tmp_path):
    assert efficiency.rated_average("LPG", data_dir) is None
    assert efficiency.rated_average("COAL", data_dir) is None
    efficiency.clear_cache()
    assert efficiency.rated_average("GASOLINE", str(tmp_path / "missing")) is None




ESTIMATE_RULE = {"vehicle_type": "ICE", "efficiency_source": "ESTIMATE", "fuel_type": "GASOLINE", "unit_price_krw": 1800,
                 "driver_included": True, "energy_policy": "SEGMENT_SHARED"}


@pytest.fixture
def rated(tmp_path, monkeypatch):
    (tmp_path / "car_efficiency.json").write_text(json.dumps(rows(("휘발유", "승용차", 11.0), ("휘발유", "승용차", 13.0), ("전기", "승용차", 5.0)), ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(get_settings(), "efficiency_data_dir", str(tmp_path))
    efficiency.clear_cache()
    yield
    efficiency.clear_cache()


def test_estimate_rule_shows_the_rated_average_with_no_input(client, rated):
    t = Trip(client, rule=ESTIMATE_RULE)
    rule = t.summary()["cost_rule"]
    assert (rule["efficiency_km_per_unit"], rule["efficiency_source"], rule["fuel_type"]) == ("12", "ESTIMATE", "GASOLINE")
    assert rule["efficiency_value"] is None


def test_estimate_flows_through_to_the_settlement(client, rated):
    t = Trip(client, rule=ESTIMATE_RULE)
    t.ready_and_start(1000)
    t.drive(1010)
    t.end(1024)
    sm = t.preview().json()
    assert sm["efficiency_source"] == "ESTIMATE" and sm["energy_total_krw"] == 3600
    assert any("표시연비 평균" in d for d in sm["disclaimers"])


def test_a_reading_from_the_car_wins_over_the_estimate(client, rated):
    t = Trip(client, data_mode="PLEOS", rule=ESTIMATE_RULE)
    t.ready_and_start(1000)
    r = t.driver.post(f"/trips/{t.trip_id}/readings/batch", {"readings": [reading("FUEL_ECONOMY", 8, "L/100km", source="PLEOS")]})
    assert r.status_code == 200 and r.json()["accepted"] == 1, r.text
    rule = t.summary()["cost_rule"]
    assert (rule["efficiency_km_per_unit"], rule["efficiency_source"]) == ("12.5", "PLEOS")


def test_estimate_without_a_dataset_blocks_the_settlement_instead_of_guessing(client, tmp_path, monkeypatch):
    monkeypatch.setattr(get_settings(), "efficiency_data_dir", str(tmp_path))
    efficiency.clear_cache()
    t = Trip(client, rule=ESTIMATE_RULE)
    assert t.summary()["cost_rule"]["efficiency_km_per_unit"] is None
    t.ready_and_start(1000)
    t.end(1024)
    r = t.preview()
    assert r.status_code == 422 and r.json()["code"] == "SETTLEMENT_BLOCKED"
    efficiency.clear_cache()


@pytest.mark.parametrize("patch,code", [({"fuel_type": None}, "FUEL_TYPE_REQUIRED"), ({"fuel_type": "ELECTRIC"}, "FUEL_TYPE_MISMATCH"),
                                        ({"vehicle_type": "EV"}, "FUEL_TYPE_MISMATCH"),
                                        ({"efficiency_value": 12, "efficiency_unit": "km/L"}, "EFFICIENCY_SOURCE_MISMATCH")])
def test_estimate_rule_validation(client, rated, patch, code):
    t = Trip(client, rule=ESTIMATE_RULE)
    r = t.driver.put(f"/trips/{t.trip_id}/cost-rule", {**ESTIMATE_RULE, **patch})
    assert r.status_code == 422 and r.json()["code"] == code, r.text


def test_ev_estimate_uses_km_per_kwh(client, rated):
    t = Trip(client, rule={**ESTIMATE_RULE, "vehicle_type": "EV", "fuel_type": "ELECTRIC", "unit_price_krw": 300})
    rule = t.summary()["cost_rule"]
    assert (rule["efficiency_km_per_unit"], rule["efficiency_source"]) == ("5", "ESTIMATE")


def test_manual_rules_still_work_unchanged(client):
    t = Trip(client, rule=MANUAL_ICE_RULE)
    assert t.summary()["cost_rule"]["efficiency_source"] == "MANUAL"
