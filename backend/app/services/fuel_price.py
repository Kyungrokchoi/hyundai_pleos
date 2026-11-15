from __future__ import annotations

import json
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

FUELS = {"GASOLINE": "B027", "PREMIUM": "B034", "DIESEL": "D047", "LPG": "K015"}

SIDO = {"서울": "01", "경기": "02", "강원": "03", "충북": "04", "충남": "05", "전북": "06", "경북": "08", "경남": "09",
        "부산": "10", "제주": "11", "대구": "14", "인천": "15", "대전": "17", "울산": "18", "세종": "19",
        "전남": "20", "광주": "20", "전남광주": "20"}
SIDO_LABEL = {"20": "전남광주"}
BASE = "https://www.opinet.co.kr/api/"
TTL_SECONDS = 6 * 3600
_KST = timezone(timedelta(hours=9))


class FuelPriceUnavailable(Exception):
    pass


@dataclass(frozen=True)
class FuelPrice:
    fuel: str
    region: str
    price_krw: int
    diff_krw: float | None
    fetched_at: datetime
    cached: bool = False


_lock = threading.Lock()
_cache: dict[tuple[str, str], tuple[float, FuelPrice]] = {}
_calls: dict[str, int] = {}


def _http_get(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "moata-api/1.0"})
    with urllib.request.urlopen(req, timeout=8) as r:  # noqa: S310
        return r.read().decode("utf-8", "replace")


def reset() -> None:
    with _lock:
        _cache.clear()
        _calls.clear()


def calls_today() -> int:
    return _calls.get(datetime.now(_KST).date().isoformat(), 0)


def parse(text: str, prodcd: str) -> tuple[int, float | None]:
    try:
        rows = json.loads(text)["RESULT"]["OIL"]
    except (ValueError, KeyError, TypeError) as e:
        raise FuelPriceUnavailable("unreadable response") from e
    for row in rows:
        if row.get("PRODCD") == prodcd:
            try:
                price = round(float(row["PRICE"]))
            except (KeyError, TypeError, ValueError) as e:
                raise FuelPriceUnavailable("bad price") from e
            if not 300 <= price <= 5000:
                raise FuelPriceUnavailable("price out of range")
            diff = row.get("DIFF")
            try:
                return price, float(diff) if diff not in (None, "") else None
            except ValueError:
                return price, None
    raise FuelPriceUnavailable("empty result")


def get_price(key: str, fuel: str, region: str | None, daily_limit: int, fetch=None) -> FuelPrice:
    fetch = fetch or _http_get
    if not key:
        raise FuelPriceUnavailable("no key configured")
    prodcd = FUELS[fuel]
    sido = SIDO.get(region or "")
    ck = (prodcd, sido or "ALL")
    now = time.monotonic()
    with _lock:
        hit = _cache.get(ck)
        if hit and now - hit[0] < TTL_SECONDS:
            return FuelPrice(hit[1].fuel, hit[1].region, hit[1].price_krw, hit[1].diff_krw, hit[1].fetched_at, cached=True)
        today = datetime.now(_KST).date().isoformat()
        if _calls.get(today, 0) >= daily_limit:
            raise FuelPriceUnavailable("daily call limit reached")
        _calls[today] = _calls.get(today, 0) + 1
    params = {"out": "json", "certkey": key, "prodcd": prodcd, **({"sido": sido} if sido else {})}
    url = BASE + ("avgSidoPrice.do" if sido else "avgAllPrice.do") + "?" + urllib.parse.urlencode(params)
    try:
        text = fetch(url)
    except OSError as e:
        raise FuelPriceUnavailable("network error") from e
    price, diff = parse(text, prodcd)
    out = FuelPrice(fuel, SIDO_LABEL.get(sido, region) if sido else "전국", price, diff, datetime.now(timezone.utc))
    with _lock:
        _cache[ck] = (now, out)
    return out
