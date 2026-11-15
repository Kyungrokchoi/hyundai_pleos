import json
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "backend" / "app" / "data" / "car_efficiency.json"
ENDPOINT = "https://apis.data.go.kr/B553530/CAREFF/CAREFF_LIST"
PAGE = 100


def env(name: str) -> str:
    for line in (ROOT / "backend" / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} missing in backend/.env")


def number(text: str | None) -> float | None:
    try:
        return float(text) if text not in (None, "", "NULL") else None
    except ValueError:
        return None


def fetch_page(key: str, page: int) -> tuple[int, list[dict]]:
    url = ENDPOINT + "?" + urllib.parse.urlencode({"serviceKey": key, "pageNo": page, "numOfRows": PAGE})
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "moata-dev/0.1"}), timeout=60) as r:
        root = ET.fromstring(r.read())
    if root.findtext("header/resultCode") != "00":
        raise SystemExit(f"API error: {root.findtext('header/resultCode')} {root.findtext('header/resultMsg')}")
    rows = [{
        "company": i.findtext("COMP_NM"), "model": i.findtext("MODEL_NM"), "kind": i.findtext("CAR_KIND"), "type": i.findtext("CAR_TYPE"),
        "fuel": i.findtext("FUEL_NM"), "year": int(i.findtext("YEAR") or 0), "display_eff": number(i.findtext("DISPLAY_EFF")),
        "highway_eff": number(i.findtext("HIGHWAY_EFF")), "urban_eff": number(i.findtext("URBAN_EFF")),
    } for i in root.iterfind("body/items/item")]
    return int(root.findtext("body/totalCount")), rows


if __name__ == "__main__":
    key, rows, page = env("MOATA_DATA_GO_KEY"), [], 1
    while True:
        total, batch = fetch_page(key, page)
        rows += batch
        print(f"page {page}: {len(rows)}/{total}", flush=True)
        if not batch or len(rows) >= total:
            break
        page += 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
    fuels = {}
    for r in rows:
        fuels.setdefault((r["fuel"], r["kind"]), []).append(r["display_eff"])
    for (fuel, kind), v in sorted(fuels.items(), key=lambda kv: -len(kv[1])):
        vals = [x for x in v if x]
        print(f"{fuel} / {kind}: {len(v)} rows, {len(vals)} with DISPLAY_EFF" + (f", mean {sum(vals)/len(vals):.2f}" if vals else ""))
