import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))
from app.services.tolls import normalize_name  # noqa: E402

UA = {"User-Agent": "moata-dev/0.1"}
OUT = ROOT / "backend" / "app" / "data"


def env(name: str) -> str:
    for line in (ROOT / "backend" / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} missing in backend/.env")


def get(url: str, data: bytes | None = None) -> dict:
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=UA), timeout=120) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code not in (429, 502, 503, 504) or attempt == 3:
                raise
            print(f"HTTP {e.code} from {urllib.parse.urlsplit(url).netloc}, retry {attempt + 1}", flush=True)
            time.sleep(10 * (attempt + 1))
    raise AssertionError


def fetch_units(key: str) -> list[dict]:
    rows, page = [], 1
    while True:
        d = get("https://data.ex.co.kr/openapi/basicinfo/unitList?" + urllib.parse.urlencode({"key": key, "type": "json", "numOfRows": 99, "pageNo": page}))
        batch = d.get("unitLists") or []
        rows += [{"code": r["unitCode"], "name": r["unitName"], "operator": r["exDivName"], "open": r["openClName"]} for r in batch]
        if not batch or len(rows) >= int(d["count"]):
            return rows
        page += 1


def fetch_osm_gates() -> list[dict]:
    q = '[out:json][timeout:90];area["ISO3166-1"="KR"]->.k;(node["barrier"="toll_booth"](area.k);node["highway"="toll_gantry"](area.k););out tags center;'
    d = get("https://overpass-api.de/api/interpreter", urllib.parse.urlencode({"data": q}).encode())
    return [{"id": str(e["id"]), "name": e["tags"].get("name", ""), "lat": e["lat"], "lon": e["lon"], "kind": e["tags"].get("highway") or e["tags"].get("barrier")}
            for e in d["elements"]]


if __name__ == "__main__":
    cached = OUT / "toll_units.json"
    units = json.loads(cached.read_text(encoding="utf-8")) if cached.exists() else fetch_units(env("MOATA_EXCO_KEY"))
    if not cached.exists():
        OUT.mkdir(parents=True, exist_ok=True)
        cached.write_text(json.dumps(units, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"units ready: {len(units)}", flush=True)
    saved = OUT / "toll_gates.json"
    if saved.exists() and "--refresh" not in sys.argv:
        gates = json.loads(saved.read_text(encoding="utf-8"))
    else:
        gates = fetch_osm_gates()
    by_name: dict[str, list[dict]] = {}
    for u in units:
        by_name.setdefault(normalize_name(u["name"]), []).append(u)
    named = [g for g in gates if g["name"]]
    for g in named:
        hit = by_name.get(normalize_name(g["name"]), [])
        g["unit_code"] = hit[0]["code"] if len(hit) == 1 else None
        g["ambiguous"] = len(hit) > 1
    (OUT / "toll_gates.json").write_text(json.dumps(named, ensure_ascii=False, indent=1), encoding="utf-8")
    ok = sum(1 for g in named if g["unit_code"])
    print(f"units={len(units)} osm_nodes={len(gates)} named={len(named)} matched={ok} ambiguous={sum(g['ambiguous'] for g in named)}")
    print("sample matched:", [(g["name"], g["unit_code"]) for g in named if g["unit_code"]][:6])
    print("sample unmatched:", [g["name"] for g in named if not g["unit_code"] and not g["ambiguous"]][:12])
