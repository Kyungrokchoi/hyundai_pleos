"""Race conditions that only exist with real row locks. Skipped on SQLite (single writer), run on PostgreSQL."""
import threading
from decimal import Decimal as D

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app.db import models as m
from app.db.session import SessionLocal, engine
from app.main import app
from app.services import rewards
from tests.helpers import P, Trip, uid

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="needs PostgreSQL row locks")


def run_parallel(n, fn):
    barrier, out = threading.Barrier(n), [None] * n

    def worker(i):
        barrier.wait()
        try:
            out[i] = fn(i)
        except Exception as e:  # noqa: BLE001 - collected and asserted by the caller
            out[i] = e
    ts = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    return out


def test_identical_trip_create_from_many_threads_never_500s(client):
    t = Trip(client, passengers=())
    body = {"id": uid(), "group_id": t.group_id, "data_mode": "MANUAL", "cost_rule": t.rule}
    res = run_parallel(12, lambda i: TestClient(app).post(f"{P}/trips", json=body, headers=t.driver.h).status_code)
    assert set(res) == {201}, res


def test_concurrent_xp_awards_are_not_lost(client):
    t = Trip(client, passengers=("B",))
    ids = []
    with SessionLocal() as db:
        for _ in range(6):
            tid = uid()
            db.add(m.Trip(id=tid, group_id=t.group_id, driver_user_id=t.driver.id, data_mode="MANUAL", state="CONFIRMED",
                          odometer_start_km=D(0), odometer_end_km=D(10)))
            ids.append(tid)
        db.commit()

    def award(i):
        with SessionLocal() as db:
            got = rewards.award_for_trip(db, db.get(m.Trip, ids[i]), [D(10)])
            db.commit()
            return got
    res = run_parallel(6, award)
    assert all(r == 10 for r in res), res
    with SessionLocal() as db:
        assert db.get(m.CharacterProgress, t.group_id).xp == 60                # lost updates would leave 10..20
        assert db.scalar(select(func.count()).select_from(m.RewardEvent)) == 6


def test_double_approve_and_double_end_from_threads_stay_consistent(client):
    t = Trip(client)
    t.ready_and_start(1000); t.board("B"); t.board("C")
    from tests.helpers import odo
    end = lambda i: TestClient(app).post(f"{P}/trips/{t.trip_id}/end", json={"odometer": odo(1024 + i, "MANUAL", event_id=f"race-end-{i:02d}-aaaa")}, headers=t.driver.h).status_code
    codes = run_parallel(8, end)
    assert sorted(codes).count(200) == 1 and set(codes) <= {200, 409}, codes     # exactly one end wins
    sid = t.preview().json()["id"]
    approve = lambda i: TestClient(app).post(f"{P}/settlements/{sid}/approvals", json={"decision": "APPROVE"},
                                             headers=[t.driver, *t.riders.values()][i % 3].h).status_code
    codes = run_parallel(9, approve)
    assert set(codes) <= {200, 409} and codes.count(200) >= 3, codes   # approvals racing the confirmation get 409 TRIP_LOCKED, never 500
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(m.RewardEvent)) == 1
    assert t.driver.get(f"/groups/{t.group_id}/character").json()["xp"] == 10
