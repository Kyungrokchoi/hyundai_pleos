from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from datetime import timedelta

from sqlalchemy.orm import Session

from app.db import models as m

ENDPOINT = "https://data.ex.co.kr/openapi/toll/bhoinstIntoTollList"
HIT_TTL = timedelta(days=30)
MISS_TTL = timedelta(days=7)


def _http_get(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "moata-api/1.0"})
    with urllib.request.urlopen(req, timeout=10) as r:  # noqa: S310
        return r.read().decode("utf-8", "replace")


def parse_fare(text: str, vehicle_class: int) -> int | None:
    rows = json.loads(text).get("list") or []
    if not rows:
        return None
    raw = rows[0].get(f"nrmlKnd{vehicle_class}Amt")
    amount = int(raw) if raw not in (None, "") else 0
    return amount if amount > 0 else None


class FareLookup:

    def __init__(self, db: Session, key: str, vehicle_class: int = 1, max_remote: int = 8, fetch=None):
        self.db, self.key, self.vehicle_class, self.max_remote = db, key, vehicle_class, max_remote
        self.fetch = fetch or _http_get
        self.remote_calls = 0
        self.failed = False

    def __call__(self, dep: str, arr: str) -> int | None:
        row = self.db.get(m.TollFareCache, {"dep_code": dep, "arr_code": arr, "vehicle_class": self.vehicle_class})
        now = m.utcnow()
        if row is not None:
            ttl = HIT_TTL if row.amount_krw is not None else MISS_TTL
            if now - row.fetched_at < ttl:
                return row.amount_krw
        if not self.key or self.remote_calls >= self.max_remote:
            self.failed = True
            return row.amount_krw if row is not None else None
        self.remote_calls += 1
        query = urllib.parse.urlencode({"key": self.key, "type": "json", "dprtrTolofCd": dep, "arrvTolofCd": arr, "numOfRows": 1, "pageNo": 1})
        try:
            amount = parse_fare(self.fetch(f"{ENDPOINT}?{query}"), self.vehicle_class)
        except (OSError, ValueError, AttributeError):
            self.failed = True
            return row.amount_krw if row is not None else None
        if row is None:
            row = m.TollFareCache(dep_code=dep, arr_code=arr, vehicle_class=self.vehicle_class)
            self.db.add(row)
        row.amount_krw, row.fetched_at = amount, now
        self.db.commit()
        return amount
