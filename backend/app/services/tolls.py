from __future__ import annotations

import json
import math
import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path

KST = timedelta(hours=9)
_SUFFIX = re.compile(r"(톨게이트|요금소|영업소|TG|IC|JC|출구|입구|진입|진출|하이패스)$", re.IGNORECASE)
_PREFIX = re.compile(r"^(톨게이트|TG|IC)", re.IGNORECASE)
_PAREN = re.compile(r"[\(\[（][^)\]）]*[\)\]）]")


@dataclass(frozen=True)
class Gate:
    id: str
    name: str
    lat: float
    lon: float
    code: str | None = None


@dataclass(frozen=True)
class Point:
    lat: float
    lon: float
    at: datetime


@dataclass(frozen=True)
class Segment:
    entry: str
    exit: str
    amount_krw: int


@dataclass(frozen=True)
class ZoneCharge:
    name: str
    amount_krw: int


FareOf = Callable[[str, str], int | None]


def to_naive_utc(t: datetime) -> datetime:
    return t.astimezone(timezone.utc).replace(tzinfo=None) if t.tzinfo else t


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 6_371_000 * 2 * math.asin(math.sqrt(a))


def normalize_name(name: str) -> str:
    n = re.sub(r"\s+", "", _PAREN.sub("", name or ""))
    while True:
        stripped = _PREFIX.sub("", _SUFFIX.sub("", n))
        if stripped == n or not stripped:
            return n
        n = stripped


MAX_SEGMENT_M = 5_000.0
_M_PER_DEG_LAT = 110_540.0
_M_PER_DEG_LON_AT_EQUATOR = 111_320.0


def _gates_along(p: Point, q: Point, gates: list[Gate], radius_m: float) -> list[Gate]:
    kx = math.cos(math.radians(p.lat)) * _M_PER_DEG_LON_AT_EQUATOR
    qx, qy = (q.lon - p.lon) * kx, (q.lat - p.lat) * _M_PER_DEG_LAT
    seg2 = qx * qx + qy * qy
    lat_pad, lon_pad = radius_m / 100_000, radius_m / 70_000
    lo_lat, hi_lat = min(p.lat, q.lat) - lat_pad, max(p.lat, q.lat) + lat_pad
    lo_lon, hi_lon = min(p.lon, q.lon) - lon_pad, max(p.lon, q.lon) + lon_pad
    hits: list[tuple[float, Gate]] = []
    for g in gates:
        if not (lo_lat <= g.lat <= hi_lat and lo_lon <= g.lon <= hi_lon):
            continue
        gx, gy = (g.lon - p.lon) * kx, (g.lat - p.lat) * _M_PER_DEG_LAT
        t = 0.0 if seg2 == 0 else max(0.0, min(1.0, (gx * qx + gy * qy) / seg2))
        if math.hypot(gx - t * qx, gy - t * qy) <= radius_m:
            hits.append((t, g))
    return [g for _, g in sorted(hits, key=lambda h: h[0])]


def detect_passes(points: list[Point], gates: list[Gate], radius_m: float = 80) -> list[Gate]:
    pairs: list[tuple[Point, Point]] = [(p, p) for p in points] if len(points) == 1 else []
    for p, q in zip(points, points[1:]):

        pairs += [(p, q)] if haversine_m(p.lat, p.lon, q.lat, q.lon) <= MAX_SEGMENT_M else [(p, p), (q, q)]
    passes: list[Gate] = []
    inside: Gate | None = None
    for p, q in pairs:
        hits = _gates_along(p, q, gates, radius_m)
        if not hits:
            inside = None
            continue
        for gate in hits:
            if inside is None or inside.id != gate.id:
                same_unit_next_lane = inside is not None and gate.code is not None and inside.code == gate.code
                inside = gate
                if not same_unit_next_lane:
                    passes.append(gate)
        if inside is not None and haversine_m(q.lat, q.lon, inside.lat, inside.lon) > radius_m:
            inside = None
    return passes


def estimate_segments(passes: list[Gate], fare_of: FareOf) -> tuple[list[Segment], list[str]]:
    segs: list[Segment] = []
    unmatched: list[str] = []
    i = 0
    while i < len(passes):
        if i + 1 < len(passes) and passes[i].code and passes[i + 1].code and passes[i].code != passes[i + 1].code:

            a, b = passes[i], passes[i + 1]
            amount = fare_of(a.code, b.code)
            if amount is not None:
                segs.append(Segment(a.name, b.name, amount))
                i += 2
                continue
        unmatched.append(passes[i].name)
        i += 1
    return segs, unmatched


def zone_charges(points: list[Point], zones: list[dict], occupants: int | None) -> list[ZoneCharge]:
    out: list[ZoneCharge] = []
    for z in zones:
        exempt = z.get("exempt_min_occupants")
        if exempt is not None and occupants is not None and occupants >= exempt:
            continue
        for p in points:
            local = p.at + KST
            if (haversine_m(p.lat, p.lon, z["lat"], z["lon"]) <= z["radius_m"] and local.weekday() in z["weekdays"]
                    and z["start_hour"] <= local.hour < z["end_hour"]):
                out.append(ZoneCharge(z["name"], int(z["fee_krw"])))
                break
    return out


def load_gates(path: Path) -> list[Gate]:
    if not path.exists():
        return []
    return [Gate(str(g["id"]), g["name"], float(g["lat"]), float(g["lon"]), g.get("unit_code")) for g in json.loads(path.read_text(encoding="utf-8"))]


def load_zones(path: Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else []


@dataclass(frozen=True)
class TollData:
    gates: list[Gate]
    zones: list[dict]


@lru_cache(maxsize=4)
def get_data(data_dir: str) -> TollData:
    d = Path(data_dir) if data_dir else Path(__file__).resolve().parent.parent / "data"
    return TollData([g for g in load_gates(d / "toll_gates.json") if g.code], load_zones(d / "toll_zones.json"))
