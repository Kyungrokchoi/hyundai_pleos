import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))
from app.services.tolls import haversine_m  # noqa: E402

GATES = Path(__file__).resolve().parent.parent / "backend" / "app" / "data" / "toll_gates.json"
OSRM = "https://router.project-osrm.org/route/v1/driving"
M_PER_DEG_LAT = 111_320.0

Path_ = list[tuple[float, float]]


def adb_path() -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidate = Path(home) / "platform-tools" / "adb.exe" if home else None
    return str(candidate) if candidate and candidate.exists() else (shutil.which("adb") or sys.exit("adb not found"))


def console(base: str, method: str, path: str, **params) -> None:
    url = f"{base}{path}?{urllib.parse.urlencode(params)}"
    try:
        urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=5).read()
    except urllib.error.HTTPError as err:
        sys.exit(f"Virtual Console {method} {path} -> HTTP {err.code}: {err.read().decode('utf-8', 'replace')[:120]} (is the heartbeat running?)")


def gate(name: str) -> dict:
    hits = [g for g in json.loads(GATES.read_text(encoding="utf-8")) if g.get("unit_code") and g["name"].replace("요금소", "") == name]
    if not hits:
        sys.exit(f"no gate with a unit code named {name!r} in {GATES}")
    return hits[0]


def road_path(s: dict, e: dict) -> Path_ | None:
    url = f"{OSRM}/{s['lon']},{s['lat']};{e['lon']},{e['lat']}?overview=full&geometries=geojson"
    try:
        reply = json.load(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "moata-demo/0.1"}), timeout=30))
        return [(lat, lon) for lon, lat in reply["routes"][0]["geometry"]["coordinates"]] if reply.get("code") == "Ok" else None
    except (OSError, ValueError, KeyError, IndexError):
        return None


def extend(path: Path_, lead_m: float) -> Path_:
    def beyond(origin: tuple[float, float], toward: tuple[float, float]) -> tuple[float, float]:
        dx = (origin[1] - toward[1]) * M_PER_DEG_LAT * math.cos(math.radians(origin[0]))
        dy = (origin[0] - toward[0]) * M_PER_DEG_LAT
        norm = math.hypot(dx, dy) or 1.0
        return (origin[0] + dy / norm * lead_m / M_PER_DEG_LAT, origin[1] + dx / norm * lead_m / (M_PER_DEG_LAT * math.cos(math.radians(origin[0]))))

    def far_enough(i: int, step: int) -> tuple[float, float]:
        j = i
        while 0 <= j + step < len(path) and haversine_m(*path[i], *path[j]) < 30:
            j += step
        return path[j]

    return [beyond(path[0], far_enough(0, 1)), *path, beyond(path[-1], far_enough(len(path) - 1, -1))]


def length_m(path: Path_) -> float:
    return sum(haversine_m(*a, *b) for a, b in zip(path, path[1:]))


def point_at(path: Path_, distance_m: float) -> tuple[float, float]:
    for a, b in zip(path, path[1:]):
        seg = haversine_m(*a, *b)
        if distance_m <= seg or (a, b) == (path[-2], path[-1]):
            f = 0.0 if seg == 0 else min(distance_m / seg, 1.0)
            return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        distance_m -= seg
    return path[-1]


def fix(adb: str, serial: str, lat: float, lon: float) -> None:
    subprocess.run([adb, "-s", serial, "emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}"], check=True, capture_output=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("start"), ap.add_argument("end")
    ap.add_argument("--steps", type=int, default=60)
    ap.add_argument("--delay", type=float, default=1.5, help="seconds between GPS fixes")
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--odo-start", type=float, help="odometer km at the start; the Virtual Console odometer then follows the drive")
    ap.add_argument("--km", type=float, help="distance to add to the odometer over the whole drive (default: the route's real length)")
    ap.add_argument("--console", default="http://127.0.0.1:8088")
    ap.add_argument("--lead-m", type=float, default=400, help="approach / exit distance added before the first and after the last gate")
    ap.add_argument("--settle", type=float, default=3, help="seconds to wait after the first fix so the app records the starting point")
    ap.add_argument("--park", action="store_true", help="only move the emulator to the starting point (run this BEFORE starting the trip, so the track has no jump)")
    ap.add_argument("--driving", action="store_true", help="also set gear Drive and a speed on the Virtual Console during the drive (gear Park, speed 0 afterwards)")
    ap.add_argument("--speed-kmh", type=int, default=80, help="speed reported while --driving (the fixes are replayed faster than real time, so this is only what the car 'says')")
    ap.add_argument("--straight", action="store_true", help="straight line between the gates instead of the road geometry")
    a = ap.parse_args()
    s, e, adb = gate(a.start), gate(a.end), adb_path()
    road = None if a.straight else road_path(s, e)
    if road is None:
        print("using a straight line" + ("" if a.straight else " (the routing server could not be reached)"), flush=True)
    path = extend(road or [(s["lat"], s["lon"]), (e["lat"], e["lon"])], a.lead_m)
    total = length_m(path)
    if a.park:
        fix(adb, a.serial, *path[0])
        print(f"parked {a.lead_m:.0f} m before {s['name']}")
        return
    km = a.km if a.km is not None else total / 1000
    print(f"{s['name']} ({s['unit_code']}) -> {e['name']} ({e['unit_code']}), {'road' if road else 'straight'} route {total / 1000:.1f} km, {a.steps} fixes", flush=True)
    if a.driving:
        console(a.console, "PUT", "/vehicle/gear", gear="Drive")
        console(a.console, "PUT", "/vehicle/controls", speed=a.speed_kmh, accelerator=0, brake=0, steering=0)
    try:
        for i in range(a.steps + 1):
            fix(adb, a.serial, *point_at(path, total * i / a.steps))
            if a.odo_start is not None:
                console(a.console, "POST", "/vehicle/odometer", traveled_distance_km=round(a.odo_start + km * i / a.steps, 1))
            time.sleep(a.settle if i == 0 else a.delay)
    finally:
        if a.driving:
            console(a.console, "PUT", "/vehicle/controls", speed=0, accelerator=0, brake=0, steering=0)
            console(a.console, "PUT", "/vehicle/gear", gear="Park")
            print("parked: gear Park, speed 0", flush=True)


if __name__ == "__main__":
    main()
