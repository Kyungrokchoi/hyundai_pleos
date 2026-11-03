"""Cost engine tests, including hypothesis invariants."""
from decimal import Decimal as D
from fractions import Fraction

import pytest
from hypothesis import given, settings, strategies as st

from app.services.cost_engine import (
    Allocation, CostInputError, CostItemInput, DataSource, EnergyInput, EnergyPolicy,
    RideInterval, SplitRequest, VehicleType, allocate, compute_split, distance_to_km,
    ev_efficiency_to_km_per_kwh, fuel_economy_to_km_per_l, request_hash,
)

ICE = EnergyInput(VehicleType.ICE, D("12"), D("1800"), DataSource.MANUAL)
EV = EnergyInput(VehicleType.EV, D("5"), D("250"), DataSource.MANUAL)


def req(intervals=(), energy=ICE, start="0", end="24", items=(), **kw):
    return SplitRequest(
        driver_id="A", start_km=D(start), end_km=D(end),
        intervals=[RideInterval(u, D(a), D(b)) for u, a, b in intervals],
        energy=energy, items=list(items), **kw,
    )


def totals(res):
    return {s.user_id: s.total_krw for s in res.shares}


FULL = [("B", "0", "24"), ("C", "0", "24")]
TOLL = CostItemInput("toll-1", "TOLL", 2400, Allocation.EQUAL)


def test_set01_full_ride_three_people_acceptance_example():
    res = compute_split(req(FULL, items=[TOLL]))
    assert res.energy_total_krw == 3600 and res.total_krw == 6000
    assert totals(res) == {"A": 2000, "B": 2000, "C": 2000}
    assert res.equation.startswith("24km ÷ 12km/L × 1,800원/L = 3,600원")
    assert res.consumption == "2" and res.consumption_unit == "L"


def test_set02_passenger_leaves_midway_energy_only():
    res = compute_split(req([("B", "0", "24"), ("C", "0", "10")]))
    assert totals(res) == {"A": 1550, "B": 1550, "C": 500}
    assert [s.energy_krw for s in res.segments] == [1500, 2100]


def test_set03_midway_leave_plus_equal_toll():
    res = compute_split(req([("B", "0", "24"), ("C", "0", "10")], items=[TOLL]))
    assert totals(res) == {"A": 2350, "B": 2350, "C": 1300}
    assert res.total_krw == 6000


def test_set04_ev_energy_cost():
    res = compute_split(req(FULL, energy=EV))  # 24 km / 5 km/kWh * 250
    assert res.energy_total_krw == 1200
    res60 = compute_split(req([("B", "0", "60")], energy=EV, end="60"))
    assert res60.energy_total_krw == 3000 and res60.consumption_unit == "kWh"
    assert "km/kWh" in res60.equation


def test_set05_remainder_conserves_total_and_is_deterministic():
    assert allocate(1000, {"a": Fraction(1), "b": Fraction(1), "c": Fraction(1)}) == {"a": 334, "b": 333, "c": 333}
    shuffled = {"c": Fraction(1), "a": Fraction(1), "b": Fraction(1)}
    assert allocate(1000, shuffled) == {"a": 334, "b": 333, "c": 333}


@pytest.mark.parametrize("bad", [
    dict(start="10", end="5"),  # distance going backwards
    dict(start="-1", end="5"),
])
def test_set06_distance_rejected(bad):
    with pytest.raises(CostInputError) as e:
        compute_split(req(**bad))
    assert e.value.code == "DISTANCE_INVALID"


@pytest.mark.parametrize("eff,code", [
    ("0", "EFFICIENCY_INVALID"), ("-3", "EFFICIENCY_INVALID"), ("NaN", "NUMBER_INVALID"),
    ("Infinity", "NUMBER_INVALID"), ("500", "EFFICIENCY_IMPLAUSIBLE"),
])
def test_set06_efficiency_rejected(eff, code):
    energy = EnergyInput(VehicleType.ICE, D(eff), D("1800"), DataSource.MANUAL)
    with pytest.raises(CostInputError) as e:
        compute_split(req(FULL, energy=energy))
    assert e.value.code == code


def test_set06_negative_price_and_amounts_rejected():
    with pytest.raises(CostInputError) as e:
        compute_split(req(FULL, energy=EnergyInput(VehicleType.ICE, D("12"), D("-1"), DataSource.MANUAL)))
    assert e.value.code == "PRICE_INVALID"
    with pytest.raises(CostInputError) as e:
        compute_split(req(FULL, items=[CostItemInput("x", "TOLL", -5, Allocation.EQUAL)]))
    assert e.value.code == "AMOUNT_INVALID"
    with pytest.raises(CostInputError):
        compute_split(req(FULL, items=[CostItemInput("x", "TOLL", True, Allocation.EQUAL)]))  # bool is not money


def test_set07_driver_included_vs_excluded():
    inc = compute_split(req(FULL))
    exc = compute_split(req(FULL, driver_included=False))
    assert totals(inc) == {"A": 1200, "B": 1200, "C": 1200}
    assert totals(exc) == {"A": 0, "B": 1800, "C": 1800}
    assert sum(totals(exc).values()) == 3600


def test_set07_driver_alone_segment_always_charged_to_driver():
    # B rides 10..24 only, driver excluded -> the empty-car first 10 km is the driver's
    res = compute_split(req([("B", "10", "24")], driver_included=False))
    assert totals(res)["A"] == 1500 and totals(res)["B"] == 2100


def test_set08_interval_validation():
    for ivs, code in [
        ([("B", "10", "5")], "INTERVAL_REVERSED"),
        ([("B", "0", "30")], "INTERVAL_OUT_OF_RANGE"),
        ([("B", "0", "10"), ("B", "8", "20")], "INTERVAL_OVERLAP"),
        ([("A", "0", "10")], "DRIVER_AS_RIDER"),
    ]:
        with pytest.raises(CostInputError) as e:
            compute_split(req(ivs))
        assert e.value.code == code


def test_rider_boards_twice_is_supported():
    res = compute_split(req([("B", "0", "6"), ("B", "12", "24")]))
    assert sum(totals(res).values()) == 3600 and totals(res)["B"] > 0


def test_zero_distance_and_no_passengers():
    z = compute_split(req([("B", "100", "100"), ("C", "100", "100")], start="100", end="100", items=[TOLL]))
    assert z.energy_total_krw == 0 and z.total_krw == 2400
    assert totals(z) == {"A": 800, "B": 800, "C": 800}
    alone = compute_split(req([], items=[CostItemInput("p", "PARKING", 3000, Allocation.EQUAL)]))
    assert totals(alone) == {"A": 6600}


def test_toll_policies_are_explicit():
    base = [("B", "0", "24"), ("C", "0", "10")]
    d_only = compute_split(req(base, items=[CostItemInput("t", "TOLL", 2400, Allocation.DRIVER_ONLY)]))
    assert totals(d_only) == {"A": 1550 + 2400, "B": 1550, "C": 500}
    weighted = compute_split(req(base, items=[CostItemInput("t", "TOLL", 2400, Allocation.DISTANCE_WEIGHTED)]))
    assert sum(s.items_krw["t"] for s in weighted.shares) == 2400
    by = {s.user_id: s.items_krw["t"] for s in weighted.shares}
    assert by["A"] == by["B"] > by["C"]  # 24 : 24 : 10 km


def test_distance_weighted_item_with_zero_distance_warns():
    res = compute_split(req([("B", "5", "5"), ("C", "5", "5")], start="5", end="5",
                            items=[CostItemInput("t", "TOLL", 900, Allocation.DISTANCE_WEIGHTED)]))
    assert "ZERO_DISTANCE_FALLBACK" in res.warnings and res.total_krw == 900


def test_equal_split_policy_ignores_ride_distance():
    res = compute_split(req([("B", "0", "24"), ("C", "0", "10")], energy_policy=EnergyPolicy.EQUAL_SPLIT))
    assert totals(res) == {"A": 1200, "B": 1200, "C": 1200}


def test_set10_hash_is_stable_and_order_independent():
    a = req([("B", "0", "24"), ("C", "0", "10")], items=[TOLL])
    b = req([("C", "0", "10"), ("B", "0", "24.0")], items=[TOLL])  # reordered, 24 vs 24.0
    assert request_hash(a) == request_hash(b)
    assert compute_split(a) == compute_split(b)
    c = req([("B", "0", "24"), ("C", "0", "11")], items=[TOLL])
    assert request_hash(a) != request_hash(c)


def test_unit_conversions_match_pleos_sdk_units():
    # Vehicle SDK 2.3.2 reports fuel economy in L/100 km and EV efficiency in km/kWh
    assert fuel_economy_to_km_per_l(D("8.333333333"), "L/100km").quantize(D("0.01")) == D("12.00")
    assert fuel_economy_to_km_per_l("12", "km/L") == D("12")
    assert ev_efficiency_to_km_per_kwh("5", "km/kWh") == D("5")
    assert ev_efficiency_to_km_per_kwh("200", "Wh/km") == D("5")
    assert distance_to_km("24000", "m") == D("24")
    for fn, unit in [(fuel_economy_to_km_per_l, "mpg"), (ev_efficiency_to_km_per_kwh, "mi/kWh")]:
        with pytest.raises(CostInputError) as e:
            fn("5", unit)
        assert e.value.code == "UNIT_UNKNOWN"
    with pytest.raises(CostInputError):
        fuel_economy_to_km_per_l("0", "L/100km")


ids = st.sampled_from(["B", "C", "D", "E"])


@st.composite
def scenarios(draw):
    end = draw(st.integers(0, 400))
    ivs = []
    for uid in draw(st.lists(ids, unique=True, max_size=4)):
        a = draw(st.integers(0, end))
        b = draw(st.integers(a, end))
        ivs.append((uid, str(a), str(b)))
    items = [
        CostItemInput(f"i{n}", "TOLL", draw(st.integers(0, 50000)), draw(st.sampled_from(list(Allocation))))
        for n in range(draw(st.integers(0, 3)))
    ]
    eff = EnergyInput(VehicleType.ICE, D(draw(st.integers(5, 40))), D(draw(st.integers(0, 3000))), DataSource.MANUAL)
    return req(ivs, energy=eff, end=str(end), items=items,
               driver_included=draw(st.booleans()),
               energy_policy=draw(st.sampled_from(list(EnergyPolicy))))


@settings(max_examples=300, deadline=None)
@given(scenarios())
def test_property_money_is_conserved_and_non_negative(r):
    res = compute_split(r)
    assert sum(s.total_krw for s in res.shares) == res.total_krw
    assert res.total_krw == res.energy_total_krw + res.items_total_krw
    assert all(s.total_krw >= 0 and s.energy_krw >= 0 for s in res.shares)
    for it in r.items:
        assert sum(s.items_krw.get(it.item_id, 0) for s in res.shares) == it.amount_krw
    assert sum(s.energy_krw for s in res.shares) == res.energy_total_krw


@settings(max_examples=150, deadline=None)
@given(scenarios())
def test_property_input_order_does_not_change_result(r):
    flipped = SplitRequest(
        r.driver_id, r.start_km, r.end_km, list(reversed(list(r.intervals))), r.energy,
        r.driver_included, r.energy_policy, list(reversed(list(r.items))),
    )
    assert compute_split(r) == compute_split(flipped)


@settings(max_examples=150, deadline=None)
@given(scenarios())
def test_property_energy_share_is_close_to_exact(r):
    """Rounding never moves a person more than (segments + 1) KRW from the exact share."""
    res = compute_split(r)
    if r.energy_policy != EnergyPolicy.SEGMENT_SHARED or res.energy_total_krw == 0:
        return
    eff, price = Fraction(r.energy.efficiency), Fraction(r.energy.unit_price_krw)
    for s in res.shares:
        exact = Fraction(0)
        for seg in res.segments:
            if s.user_id in seg.payers:
                length = Fraction(D(seg.length_km))
                exact += length / eff * price / len(seg.payers)
        assert abs(s.energy_krw - exact) <= len(res.segments) + 1
