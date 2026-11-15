#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

ok_all = True


def check(cond: bool, label: str) -> None:
    global ok_all
    ok_all &= bool(cond)
    print(f"  [{'PASS' if cond else 'FAIL'}] {label}")


class Api:
    def __init__(self, base, name):
        self.base, self.token, self.name = base, None, name

    def call(self, method, path, body=None, expect=(200, 201, 204)):
        req = urllib.request.Request(self.base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {self.token}"} if self.token else {})})
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = r.read()
                status = r.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        data = json.loads(raw) if raw else None
        if status not in expect:
            sys.exit(f"{self.name} {method} {path} -> {status}: {data}")
        return data


def odo(km, eid):
    return {"event_id": eid, "type": "ODOMETER", "value": km, "unit": "km", "captured_at": datetime.now(timezone.utc).isoformat(),
            "source": "DEMO", "quality": "VERIFIED", "sdk_version": None, "error": None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8000/api/v1")
    base = ap.parse_args().base
    run = uuid.uuid4().hex[:8]

    print("0) 서버 상태")
    check(Api(base, "-").call("GET", "/health")["status"] == "ok", "health ok")

    print("1) 차량(운전자): 그룹·운행 만들기  [DEMO 데이터]")
    car = Api(base, "차량")
    a = car.call("POST", "/auth/guest", {"nickname": "민준", "consent_version": "v1"})
    car.token = a["token"]
    gid, tid = uuid.uuid4().hex, uuid.uuid4().hex
    car.call("POST", "/groups", {"id": gid, "name": f"퇴근 카풀 {run}"})
    rule = {"vehicle_type": "ICE", "efficiency_source": "MANUAL", "efficiency_value": 12, "efficiency_unit": "km/L",
            "unit_price_krw": 1800, "driver_included": True, "energy_policy": "SEGMENT_SHARED"}

    car.call("POST", "/trips", {"id": tid, "group_id": gid, "data_mode": "DEMO", "cost_rule": {**rule, "efficiency_source": "MANUAL"}})

    print("2) QR 초대 → 승객 2명 참가 → 운전자 승인")
    people = {}
    for nick in ("서연", "지호"):
        inv = car.call("POST", f"/groups/{gid}/invites", {"trip_id": tid, "ttl_minutes": 10, "max_uses": 3})
        prev = Api(base, nick).call("POST", "/invites/preview", {"token": inv["token"]})
        p = Api(base, nick)
        j = p.call("POST", "/invites/accept", {"token": inv["token"], "nickname": nick, "consent_version": "v1", "rules_ack": True})
        p.token, p.uid = j["token"], j["user_id"]
        check(j["member_status"] == "PENDING" and prev["group_name"].startswith("퇴근"), f"{nick} 참가 요청(승인 대기)")
        car.call("POST", f"/groups/{gid}/members/{p.uid}/decision", {"decision": "APPROVE"})
        people[nick] = p

    print("3) 출발: 주행거리 12000km, 승객 탑승")
    car.call("POST", f"/trips/{tid}/ready")
    car.call("POST", f"/trips/{tid}/start", {"odometer": odo(12000, f"s-{run}")})
    for p in people.values():
        p.call("POST", f"/trips/{tid}/participants/me/board")

    print("4) 주행 중: 거리 변화 → 모두 같은 기록을 본다 / 지호는 10km 지점에서 하차")
    car.call("POST", f"/trips/{tid}/readings/batch", {"readings": [odo(12005, f"r1-{run}"), odo(12010, f"r2-{run}")]})
    people["지호"].call("POST", f"/trips/{tid}/participants/me/alight")
    car.call("POST", f"/trips/{tid}/readings/batch", {"readings": [odo(12018, f"r3-{run}")]})
    views = [x.call("GET", f"/trips/{tid}/summary") for x in (car, *people.values())]
    check(len({v["revision"] for v in views}) == 1 and {v["latest_odometer"]["km"] for v in views} == {"12018"}, "3명이 같은 revision·같은 주행거리(12018km)를 봄")
    check(all(v["data_mode"] == "DEMO" for v in views), "모든 화면 데이터 출처 = DEMO")

    print("5) 통행료 2,400원(전원 균등) 입력 → 도착·종료")
    car.call("POST", f"/trips/{tid}/cost-items", {"id": uuid.uuid4().hex, "kind": "TOLL", "amount_krw": 2400, "allocation": "EQUAL", "note": "고속도로 통행료"})
    car.call("POST", f"/trips/{tid}/end", {"odometer": odo(12024, f"e-{run}")})
    again = car.call("POST", f"/trips/{tid}/end", {"odometer": odo(12024, f"e-{run}")})
    check(again["state"] == "ENDED", "종료 재시도(멱등) 같은 결과")

    print("6) 정산안 (서버가 계산)")
    st = car.call("POST", f"/trips/{tid}/settlements/preview")
    names = {a["user_id"]: "민준", **{p.uid: n for n, p in people.items()}}
    for s in st["shares"]:
        print(f"     {names[s['user_id']]:4} {s['total_krw']:>6,}원  (에너지 {s['energy_krw']:,} + 항목 {sum(i['amount_krw'] for i in s['items']):,}, 탑승 {s['ridden_km']}km)")
    print(f"     산식: {st['equation']}")
    shares = {names[s["user_id"]]: s["total_krw"] for s in st["shares"]}
    check(st["total_krw"] == 6000 and sum(shares.values()) == 6000, "합계 6,000원, 반올림으로 늘거나 줄지 않음")
    check(shares == {"민준": 2350, "서연": 2350, "지호": 1300}, "중도 하차 반영: 2,350 / 2,350 / 1,300")
    check(car.call("POST", f"/trips/{tid}/settlements/preview")["id"] == st["id"], "같은 입력 재요청 → 같은 정산(중복 없음)")

    print("7) 전원 확인 → 확정 → 모아 성장 (두 번 눌러도 XP는 한 번)")
    for who in (car, *people.values()):
        out = who.call("POST", f"/settlements/{st['id']}/approvals", {"decision": "APPROVE"})
    car.call("POST", f"/settlements/{st['id']}/approvals", {"decision": "APPROVE"}, expect=(200, 409))
    check(out["state"] == "CONFIRMED", "정산 확정")
    ch = car.call("GET", f"/groups/{gid}/character")
    check(ch["xp"] == 10 and ch["level"] == 1, f"모아 XP {ch['xp']} (운행당 1회), Lv.{ch['level']}")
    check(all(not c["exchangeable"] for c in car.call("GET", f"/groups/{gid}/rewards")), "쿠폰은 모두 교환 불가(시연용)")

    print("\n결과:", "ALL PASS" if ok_all else "FAILED")
    sys.exit(0 if ok_all else 1)


if __name__ == "__main__":
    main()
