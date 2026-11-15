from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from enum import Enum
from fractions import Fraction
from typing import Sequence

ENGINE_VERSION = "1"
SUSPICIOUS_TRIP_KM = Decimal("3000")
MAX_TRIP_KM = Decimal("5000")


ICE_EFFICIENCY_RANGE = (Decimal("1"), Decimal("60"))
EV_EFFICIENCY_RANGE = (Decimal("0.5"), Decimal("20"))


class VehicleType(str, Enum):
    ICE = "ICE"
    EV = "EV"


class EnergyPolicy(str, Enum):
    SEGMENT_SHARED = "SEGMENT_SHARED"
    EQUAL_SPLIT = "EQUAL_SPLIT"


class Allocation(str, Enum):
    EQUAL = "EQUAL"
    DISTANCE_WEIGHTED = "DISTANCE_WEIGHTED"
    DRIVER_ONLY = "DRIVER_ONLY"


class DataSource(str, Enum):
    PLEOS = "PLEOS"
    MANUAL = "MANUAL"
    DEMO = "DEMO"


class EfficiencySource(str, Enum):
    PLEOS = "PLEOS"
    MANUAL = "MANUAL"
    DEMO = "DEMO"
    ESTIMATE = "ESTIMATE"


MESSAGES_KO = {
    "NUMBER_INVALID": "숫자가 올바르지 않습니다(NaN/무한대/형식 오류).",
    "DISTANCE_INVALID": "주행거리가 올바르지 않습니다. 종료 거리는 시작 거리보다 작을 수 없습니다.",
    "EFFICIENCY_INVALID": "연비/전비는 0보다 커야 합니다.",
    "EFFICIENCY_IMPLAUSIBLE": "연비/전비 값이 일반적인 범위를 벗어났습니다. 단위와 값을 확인해 주세요.",
    "PRICE_INVALID": "단가는 0 이상이어야 합니다.",
    "AMOUNT_INVALID": "금액은 0 이상의 정수(원)여야 합니다.",
    "INTERVAL_REVERSED": "하차 지점이 탑승 지점보다 앞섭니다.",
    "INTERVAL_OUT_OF_RANGE": "탑승 구간이 운행 구간을 벗어났습니다.",
    "INTERVAL_OVERLAP": "같은 사람의 탑승 구간이 겹칩니다.",
    "DRIVER_AS_RIDER": "운전자는 승객 구간으로 등록할 수 없습니다.",
    "ITEM_DUPLICATE": "같은 비용 항목이 중복되었습니다.",
    "UNIT_UNKNOWN": "알 수 없는 단위입니다.",
    "ZERO_DISTANCE_FALLBACK": "주행거리가 0km여서 거리 가중 항목을 균등 분담으로 계산했습니다.",
    "VALUE_IMPLAUSIBLE": "센서 값이 일반적인 범위를 벗어나 사용하지 않았습니다.",
    "DISTANCE_SUSPICIOUS": "주행거리가 비정상적으로 큽니다. 값을 확인해 주세요.",
}


class CostInputError(ValueError):
    def __init__(self, code: str, detail: str = ""):
        self.code = code
        self.detail = detail
        super().__init__(f"{code}: {MESSAGES_KO.get(code, code)} {detail}".strip())


def to_decimal(value: object) -> Decimal:
    if isinstance(value, bool):
        raise CostInputError("NUMBER_INVALID")
    try:
        d = value if isinstance(value, Decimal) else Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise CostInputError("NUMBER_INVALID", repr(value)) from None
    if not d.is_finite():
        raise CostInputError("NUMBER_INVALID", repr(value))
    return d


def to_krw(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise CostInputError("AMOUNT_INVALID", repr(value))
    return value


def distance_to_km(value: object, unit: str) -> Decimal:
    d = to_decimal(value)
    factors = {"km": Decimal(1), "m": Decimal("0.001"), "mi": Decimal("1.609344")}
    if unit not in factors:
        raise CostInputError("UNIT_UNKNOWN", unit)
    return d * factors[unit]


def fuel_economy_to_km_per_l(value: object, unit: str) -> Decimal:
    d = to_decimal(value)
    if d <= 0:
        raise CostInputError("EFFICIENCY_INVALID")
    if unit == "km/L":
        return d
    if unit == "L/100km":
        return Decimal(100) / d
    raise CostInputError("UNIT_UNKNOWN", unit)


def ev_efficiency_to_km_per_kwh(value: object, unit: str) -> Decimal:
    d = to_decimal(value)
    if d <= 0:
        raise CostInputError("EFFICIENCY_INVALID")
    if unit == "km/kWh":
        return d
    if unit == "Wh/km":
        return Decimal(1000) / d
    if unit == "kWh/100km":
        return Decimal(100) / d
    raise CostInputError("UNIT_UNKNOWN", unit)


@dataclass(frozen=True)
class RideInterval:
    user_id: str
    on_km: Decimal
    off_km: Decimal


@dataclass(frozen=True)
class CostItemInput:
    item_id: str
    kind: str
    amount_krw: int
    allocation: Allocation
    label: str = ""


@dataclass(frozen=True)
class EnergyInput:
    vehicle_type: VehicleType
    efficiency: Decimal
    unit_price_krw: Decimal
    efficiency_source: EfficiencySource
    price_source: DataSource = DataSource.MANUAL


@dataclass(frozen=True)
class SplitRequest:
    driver_id: str
    start_km: Decimal
    end_km: Decimal
    intervals: Sequence[RideInterval]
    energy: EnergyInput
    driver_included: bool = True
    energy_policy: EnergyPolicy = EnergyPolicy.SEGMENT_SHARED
    items: Sequence[CostItemInput] = ()


@dataclass(frozen=True)
class SegmentResult:
    index: int
    start_km: str
    end_km: str
    length_km: str
    payers: tuple[str, ...]
    energy_krw: int
    per_user_krw: dict[str, int]


@dataclass(frozen=True)
class UserShare:
    user_id: str
    is_driver: bool
    total_krw: int
    energy_krw: int
    items_krw: dict[str, int]
    ridden_km: str
    owed_to_driver_krw: int


@dataclass(frozen=True)
class SplitResult:
    total_krw: int
    energy_total_krw: int
    items_total_krw: int
    distance_km: str
    consumption: str
    consumption_unit: str
    equation: str
    segments: tuple[SegmentResult, ...]
    shares: tuple[UserShare, ...]
    warnings: tuple[str, ...]
    efficiency_source: str
    engine_version: str = ENGINE_VERSION
    input_hash: str = ""


def _round_half_up(f: Fraction) -> int:
    return (2 * f.numerator + f.denominator) // (2 * f.denominator)


def allocate(total: int, weights: dict[str, Fraction]) -> dict[str, int]:
    if total < 0:
        raise CostInputError("AMOUNT_INVALID", str(total))
    if not weights:
        raise ValueError("allocate() needs at least one payee")
    w_sum = sum(weights.values(), Fraction(0))
    if w_sum <= 0:
        weights = {k: Fraction(1) for k in weights}
        w_sum = Fraction(len(weights))
    exact = {k: Fraction(total) * w / w_sum for k, w in weights.items()}
    base = {k: e.numerator // e.denominator for k, e in exact.items()}
    left = total - sum(base.values())
    order = sorted(exact, key=lambda k: (-(exact[k] - base[k]), k))
    for k in order[:left]:
        base[k] += 1
    return base


def _validate(req: SplitRequest) -> list[str]:
    warnings: list[str] = []
    start, end = to_decimal(req.start_km), to_decimal(req.end_km)
    if start < 0 or end < start or end - start > MAX_TRIP_KM:
        raise CostInputError("DISTANCE_INVALID", f"{start}->{end}")
    if end - start > SUSPICIOUS_TRIP_KM:
        warnings.append("DISTANCE_SUSPICIOUS")

    eff, price = to_decimal(req.energy.efficiency), to_decimal(req.energy.unit_price_krw)
    if eff <= 0:
        raise CostInputError("EFFICIENCY_INVALID")
    lo, hi = ICE_EFFICIENCY_RANGE if req.energy.vehicle_type == VehicleType.ICE else EV_EFFICIENCY_RANGE
    if not lo <= eff <= hi:
        raise CostInputError("EFFICIENCY_IMPLAUSIBLE", str(eff))
    if price < 0:
        raise CostInputError("PRICE_INVALID")

    seen: dict[str, list[RideInterval]] = {}
    for iv in req.intervals:
        if iv.user_id == req.driver_id:
            raise CostInputError("DRIVER_AS_RIDER", iv.user_id)
        on, off = to_decimal(iv.on_km), to_decimal(iv.off_km)
        if off < on:
            raise CostInputError("INTERVAL_REVERSED", iv.user_id)
        if on < start or off > end:
            raise CostInputError("INTERVAL_OUT_OF_RANGE", iv.user_id)
        seen.setdefault(iv.user_id, []).append(iv)
    for uid, ivs in seen.items():
        ivs = sorted(ivs, key=lambda i: (i.on_km, i.off_km))
        for a, b in zip(ivs, ivs[1:]):
            if b.on_km < a.off_km:
                raise CostInputError("INTERVAL_OVERLAP", uid)

    ids = [i.item_id for i in req.items]
    if len(ids) != len(set(ids)):
        raise CostInputError("ITEM_DUPLICATE")
    for it in req.items:
        to_krw(it.amount_krw)
    return warnings


def _fmt_num(f: Fraction | Decimal, places: int = 2) -> str:
    q = Decimal(f.numerator) / Decimal(f.denominator) if isinstance(f, Fraction) else f
    s = f"{q:.{places}f}"
    if "." in s:
        s = s.rstrip("0").rstrip(".")
    return s or "0"


def _fmt_price(f: Fraction) -> str:
    whole = f.numerator // f.denominator
    return f"{whole:,}" if f.denominator == 1 else f"{whole:,}{_fmt_num(f - whole, 3)[1:]}"


def _fmt_krw(n: int) -> str:
    return f"{n:,}원"


def compute_split(req: SplitRequest) -> SplitResult:
    warnings = _validate(req)
    start, end = Fraction(to_decimal(req.start_km)), Fraction(to_decimal(req.end_km))
    distance = end - start
    eff = Fraction(to_decimal(req.energy.efficiency))
    price = Fraction(to_decimal(req.energy.unit_price_krw))
    driver = req.driver_id


    consumption = distance / eff
    energy_total = _round_half_up(consumption * price)

    ivs = [(i.user_id, Fraction(to_decimal(i.on_km)), Fraction(to_decimal(i.off_km))) for i in req.intervals]
    cuts = sorted({start, end, *[p for _, a, b in ivs for p in (a, b)]})
    spans = [(a, b) for a, b in zip(cuts, cuts[1:]) if b > a]

    def riders_in(a: Fraction, b: Fraction) -> list[str]:
        return sorted({u for u, on, off in ivs if on <= a and off >= b})

    def payers_of(a: Fraction, b: Fraction) -> list[str]:
        riders = riders_in(a, b)
        if riders:
            return ([driver] if req.driver_included else []) + riders
        return [driver]

    participants = sorted({u for u, _, _ in ivs})
    everyone = ([driver] if (req.driver_included or not participants) else []) + participants

    segments: list[SegmentResult] = []
    energy_by_user: dict[str, int] = {u: 0 for u in [driver, *participants]}
    km_by_user: dict[str, Fraction] = {u: Fraction(0) for u in energy_by_user}


    seg_energy = allocate(energy_total, {f"{i:06d}": b - a for i, (a, b) in enumerate(spans)}) if spans else {}
    if req.energy_policy == EnergyPolicy.EQUAL_SPLIT:

        pool = 0
        for i, (a, b) in enumerate(spans):
            e = seg_energy[f"{i:06d}"]
            if riders_in(a, b):
                pool += e
                payers = tuple(everyone)
            else:
                energy_by_user[driver] += e
                payers = (driver,)
            for u in payers_of(a, b):
                km_by_user[u] += b - a
            segments.append(SegmentResult(i, _fmt_num(a), _fmt_num(b), _fmt_num(b - a), payers, e, {}))
        for u, v in allocate(pool, {u: Fraction(1) for u in everyone}).items():
            energy_by_user[u] += v
    else:
        for i, (a, b) in enumerate(spans):
            payers = payers_of(a, b)
            part = allocate(seg_energy[f"{i:06d}"], {u: Fraction(1) for u in payers})
            for u, v in part.items():
                energy_by_user[u] += v
                km_by_user[u] += b - a
            segments.append(SegmentResult(i, _fmt_num(a), _fmt_num(b), _fmt_num(b - a), tuple(payers), seg_energy[f"{i:06d}"], part))

    items_by_user: dict[str, dict[str, int]] = {u: {} for u in energy_by_user}
    for it in sorted(req.items, key=lambda x: x.item_id):
        if it.allocation == Allocation.DRIVER_ONLY:
            weights = {driver: Fraction(1)}
        elif it.allocation == Allocation.DISTANCE_WEIGHTED and sum(km_by_user.values()) > 0:
            weights = {u: km for u, km in km_by_user.items() if km > 0}
        else:
            if it.allocation == Allocation.DISTANCE_WEIGHTED:
                warnings.append("ZERO_DISTANCE_FALLBACK")
            weights = {u: Fraction(1) for u in everyone}
        for u, v in allocate(it.amount_krw, weights).items():
            items_by_user[u][it.item_id] = v

    order = [driver, *participants]
    shares = []
    for u in order:
        i_sum = sum(items_by_user[u].values())
        total_u = energy_by_user[u] + i_sum
        shares.append(UserShare(
            user_id=u, is_driver=(u == driver), total_krw=total_u, energy_krw=energy_by_user[u],
            items_krw=dict(sorted(items_by_user[u].items())), ridden_km=_fmt_num(km_by_user[u]),
            owed_to_driver_krw=0 if u == driver else total_u,
        ))
    items_total = sum(i.amount_krw for i in req.items)
    total = energy_total + items_total
    assert sum(s.total_krw for s in shares) == total, "conservation violated"

    unit = "L" if req.energy.vehicle_type == VehicleType.ICE else "kWh"
    eff_unit = "km/L" if unit == "L" else "km/kWh"
    price_unit = f"원/{unit}"
    equation = (
        f"{_fmt_num(distance)}km ÷ {_fmt_num(eff, 3)}{eff_unit} × {_fmt_price(price)}{price_unit}"
        f" = {_fmt_krw(energy_total)}"
    )
    if items_total:
        equation += f" + 통행료·주차비 등 {_fmt_krw(items_total)} = {_fmt_krw(total)}"

    result = SplitResult(
        total_krw=total, energy_total_krw=energy_total, items_total_krw=items_total,
        distance_km=_fmt_num(distance), consumption=_fmt_num(consumption, 3), consumption_unit=unit,
        equation=equation, segments=tuple(segments), shares=tuple(shares),
        warnings=tuple(dict.fromkeys(warnings)), efficiency_source=req.energy.efficiency_source.value,
    )
    return SplitResult(**{**result.__dict__, "input_hash": request_hash(req)})


def _canon(d: object) -> str:
    return format(to_decimal(d).normalize(), "f")


def request_hash(req: SplitRequest) -> str:
    payload = {
        "v": ENGINE_VERSION, "driver": req.driver_id,
        "start": _canon(req.start_km), "end": _canon(req.end_km),
        "intervals": sorted([i.user_id, _canon(i.on_km), _canon(i.off_km)] for i in req.intervals),
        "energy": [req.energy.vehicle_type.value, _canon(req.energy.efficiency),
                   _canon(req.energy.unit_price_krw), req.energy.efficiency_source.value],
        "driver_included": req.driver_included, "policy": req.energy_policy.value,
        "items": sorted([i.item_id, i.kind, i.amount_krw, i.allocation.value] for i in req.items),
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
