from __future__ import annotations

import json
import threading
import urllib.parse
import urllib.request
from dataclasses import dataclass

BASE = "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json"


class PlaceUnavailable(Exception):
    pass


@dataclass(frozen=True)
class Place:
    address: str
    short: str


_lock = threading.Lock()
_cache: dict[tuple[float, float], Place] = {}


def _http_get(url: str, key: str) -> str:
    req = urllib.request.Request(url, headers={"Authorization": f"KakaoAK {key}", "User-Agent": "moata-api/1.0"})
    with urllib.request.urlopen(req, timeout=8) as r:  # noqa: S310
        return r.read().decode("utf-8", "replace")


def reset() -> None:
    with _lock:
        _cache.clear()


def parse(text: str) -> Place:
    try:
        docs = json.loads(text).get("documents") or []
    except (ValueError, AttributeError):
        raise PlaceUnavailable("bad response") from None
    doc = next((d for d in docs if d.get("region_type") == "B"), None) or next(iter(docs), None)
    if not doc:
        raise PlaceUnavailable("no region")
    parts = [str(doc.get(f"region_{i}depth_name") or "").strip() for i in (1, 2, 3)]
    address = str(doc.get("address_name") or " ".join(p for p in parts if p)).strip()
    city = parts[1].split()[-1] if parts[1] else parts[0]
    short = " ".join(p for p in (city, parts[2]) if p) or address
    if not address:
        raise PlaceUnavailable("empty region")
    return Place(address=address, short=short)


def lookup(key: str, lat: float, lon: float, fetch=None) -> Place:
    if not key:
        raise PlaceUnavailable("no key")
    cell = (round(lat, 3), round(lon, 3))
    with _lock:
        hit = _cache.get(cell)
    if hit:
        return hit
    url = f"{BASE}?{urllib.parse.urlencode({'x': f'{lon:.6f}', 'y': f'{lat:.6f}'})}"
    try:
        text = (fetch or _http_get)(url, key)
    except Exception as e:  # noqa: BLE001
        raise PlaceUnavailable(type(e).__name__) from None
    place = parse(text)
    with _lock:
        _cache[cell] = place
    return place
