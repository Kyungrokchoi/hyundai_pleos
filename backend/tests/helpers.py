import uuid
P = "/api/v1"


def uid() -> str:
    return uuid.uuid4().hex


class Actor:
    def __init__(self, client, name: str):
        self.c, self.name = client, name
        r = client.post(f"{P}/auth/guest", json={"nickname": name, "consent_version": "v1"})
        assert r.status_code == 201, r.text
        self.id, self.token = r.json()["user_id"], r.json()["token"]

    @property
    def h(self):
        return {"Authorization": f"Bearer {self.token}"}

    def get(self, path, **kw):
        return self.c.get(P + path, headers={**self.h, **kw.pop("headers", {})}, **kw)

    def post(self, path, json=None, **kw):
        return self.c.post(P + path, json=json, headers=self.h, **kw)

    def put(self, path, json=None):
        return self.c.put(P + path, json=json, headers=self.h)

    def patch(self, path, json=None):
        return self.c.patch(P + path, json=json, headers=self.h)

    def delete(self, path):
        return self.c.delete(P + path, headers=self.h)


def reading(rtype, value, unit, source="MANUAL", quality="VERIFIED", captured_at=None, event_id=None, error=None):
    from app.db import models as m
    return {"event_id": event_id or uid(), "type": rtype, "value": value, "unit": unit,
            "captured_at": (captured_at or m.utcnow()).isoformat(), "source": source, "quality": quality,
            "sdk_version": "2.3.2" if source == "PLEOS" else None, "error": error}


def odo(km, source="MANUAL", quality="VERIFIED", captured_at=None, event_id=None):
    return reading("ODOMETER", km, "km", source, quality, captured_at, event_id)


MANUAL_ICE_RULE = {"vehicle_type": "ICE", "efficiency_value": 12, "efficiency_unit": "km/L", "efficiency_source": "MANUAL",
                   "unit_price_krw": 1800, "driver_included": True, "energy_policy": "SEGMENT_SHARED"}


class Trip:

    def __init__(self, client, data_mode="MANUAL", rule=None, passengers=("B", "C")):
        self.c = client
        self.driver = Actor(client, "A")
        self.group_id, self.trip_id = uid(), uid()
        assert self.driver.post("/groups", {"id": self.group_id, "name": "퇴근 카풀"}).status_code == 201
        self.rule = rule or MANUAL_ICE_RULE
        r = self.driver.post("/trips", {"id": self.trip_id, "group_id": self.group_id, "data_mode": data_mode, "cost_rule": self.rule})
        assert r.status_code == 201, r.text
        self.data_mode = data_mode
        self.riders = {}
        for name in passengers:
            self.riders[name] = self.join(name)

    def invite(self, **kw):
        r = self.driver.post(f"/groups/{self.group_id}/invites", {"trip_id": self.trip_id, **kw})
        assert r.status_code == 201, r.text
        return r.json()

    def join(self, name, approve=True) -> Actor:
        tok = self.invite()["token"]
        assert self.c.post(f"{P}/invites/preview", json={"token": tok}).status_code == 200
        r = self.c.post(f"{P}/invites/accept", json={"token": tok, "nickname": name, "consent_version": "v1", "rules_ack": True})
        assert r.status_code == 201, r.text
        a = Actor.__new__(Actor)
        a.c, a.name, a.id, a.token = self.c, name, r.json()["user_id"], r.json()["token"]
        assert r.json()["member_status"] == "PENDING"
        if approve:
            d = self.driver.post(f"/groups/{self.group_id}/members/{a.id}/decision", {"decision": "APPROVE"})
            assert d.status_code == 200, d.text
        return a

    def summary(self, who=None):
        r = (who or self.driver).get(f"/trips/{self.trip_id}/summary")
        assert r.status_code == 200, r.text
        return r.json()

    def ready_and_start(self, start_km=1000):
        assert self.driver.post(f"/trips/{self.trip_id}/ready").status_code == 200
        r = self.driver.post(f"/trips/{self.trip_id}/start", {"odometer": odo(start_km, self.data_mode, event_id="start-" + self.trip_id[:8])})
        assert r.status_code == 200, r.text
        return r.json()

    def drive(self, km, event_id=None):
        r = self.driver.post(f"/trips/{self.trip_id}/readings/batch", {"readings": [odo(km, self.data_mode, event_id=event_id)]})
        assert r.status_code == 200, r.text
        return r.json()

    def board(self, name):
        r = self.riders[name].post(f"/trips/{self.trip_id}/participants/me/board")
        assert r.status_code == 200, r.text
        return r.json()

    def alight(self, name):
        r = self.riders[name].post(f"/trips/{self.trip_id}/participants/me/alight")
        assert r.status_code == 200, r.text
        return r.json()

    def end(self, end_km=1024):
        r = self.driver.post(f"/trips/{self.trip_id}/end", {"odometer": odo(end_km, self.data_mode, event_id="end-" + self.trip_id[:8])})
        assert r.status_code == 200, r.text
        return r.json()

    def toll(self, amount=2400, allocation="EQUAL", item_id=None):
        r = self.driver.post(f"/trips/{self.trip_id}/cost-items", {"id": item_id or uid(), "kind": "TOLL", "amount_krw": amount, "allocation": allocation, "note": "고속도로 통행료"})
        assert r.status_code == 201, r.text
        return r.json()

    def preview(self):
        return self.driver.post(f"/trips/{self.trip_id}/settlements/preview")

    def approve_all(self, sid):
        out = None
        for who in [self.driver, *self.riders.values()]:
            out = who.post(f"/settlements/{sid}/approvals", {"decision": "APPROVE"})
            assert out.status_code == 200, out.text
        return out.json()

    def totals(self, settlement):
        names = {self.driver.id: "A", **{a.id: n for n, a in self.riders.items()}}
        return {names[s["user_id"]]: s["total_krw"] for s in settlement["shares"]}
