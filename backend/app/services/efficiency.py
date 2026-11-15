from __future__ import annotations

import json
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from functools import lru_cache
from pathlib import Path

FUEL_NAMES = {"GASOLINE": "휘발유", "DIESEL": "경유", "LPG": "LPG", "ELECTRIC": "전기"}
CAR_KIND = "승용차"


@dataclass(frozen=True)
class RatedAverage:
    fuel: str
    value: Decimal
    unit: str
    sample_size: int


@lru_cache(maxsize=4)
def _rows(data_dir: str) -> tuple[dict, ...]:
    d = Path(data_dir) if data_dir else Path(__file__).resolve().parent.parent / "data"
    path = d / "car_efficiency.json"
    return tuple(json.loads(path.read_text(encoding="utf-8"))) if path.exists() else ()


def clear_cache() -> None:
    _rows.cache_clear()


def rated_average(fuel: str, data_dir: str = "") -> RatedAverage | None:
    name = FUEL_NAMES.get(fuel)
    values = [r["display_eff"] for r in _rows(data_dir) if r.get("fuel") == name and r.get("kind") == CAR_KIND and r.get("display_eff")]
    if not name or not values:
        return None
    mean = (Decimal(str(sum(values))) / len(values)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return RatedAverage(fuel, mean, "km/kWh" if fuel == "ELECTRIC" else "km/L", len(values))
