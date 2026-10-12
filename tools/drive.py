#!/usr/bin/env python3
# Copyright 2026 Damien Dye
# SPDX-License-Identifier: Apache-2.0
"""
Drive the emulator's GPS along real roads (TESTING.md §3), for free-drive testing of camera alerts
without the Navigation SDK's Simulate. The road route comes from the public OSRM demo router; the
default is a ~20 km loop around central London. Runs until stopped (Ctrl+C), looping the route.

  tools/drive.py                       # London loop at 30 mph
  tools/drive.py --mph 40 --once
  tools/drive.py --via "-0.1281,51.5080;-0.0754,51.5055"   # your own lon,lat waypoints
  tools/drive.py --via "…" --limits    # follow the posted limits: under, at and over them in turn

With --limits the speed limit of each road comes from OpenStreetMap (maxspeed, via Overpass) and the
car takes turns driving under it, at it and over it, changing every --stint metres. Roads with no
known limit keep the last one. Speed changes ramp at about 1.5 m/s² so the jumps look like a car.
"""
import argparse, json, math, os, subprocess, sys, time, urllib.parse, urllib.request

LONDON = ("-0.1281,51.5080;-0.0754,51.5055;-0.0803,51.5155;-0.1236,51.5308;"  # Trafalgar Sq, Tower Br, City, King's X
          "-0.1586,51.5229;-0.1527,51.5027;-0.1419,51.5014;-0.1281,51.5080")   # Baker St, Hyde Pk Cnr, Palace, back


def route(via):
    url = f"https://router.project-osrm.org/route/v1/driving/{via}?overview=full&geometries=geojson"
    with urllib.request.urlopen(url, timeout=30) as r:
        d = json.load(r)
    if d.get("code") != "Ok":
        sys.exit(f"Routing failed: {d.get('code')} {d.get('message', '')}")
    return d["routes"][0]["geometry"]["coordinates"]


def metres(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * 111320 * math.cos(lat), (b[1] - a[1]) * 110540)


def bearing(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.degrees(math.atan2((b[0] - a[0]) * math.cos(lat), b[1] - a[1])) % 360


OVERPASS = "https://overpass-api.de/api/interpreter"
MODES = {"under": lambda l: max(10.0, l - 6), "at": lambda l: l, "over": lambda l: l * 1.1 + 4}


def limits(coords):
    """[(lon, lat, mph, road bearing)] for points of OSM roads with a maxspeed in the route's bounding box."""
    lo, la = [c[0] for c in coords], [c[1] for c in coords]
    box = f"{min(la) - .003},{min(lo) - .003},{max(la) + .003},{max(lo) + .003}"
    q = f'[out:json][timeout:180];way({box})[highway~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential)(_link)?$"][maxspeed];out tags geom;'
    req = urllib.request.Request(OVERPASS, data=urllib.parse.urlencode({"data": q}).encode(),
                                 headers={"User-Agent": "AutoNavAlerts-bench/1.0", "Accept": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=240) as r:
                ways = json.load(r)["elements"]
            break
        except OSError as e:  # Overpass is busy now and then (429/504)
            print(f"Overpass: {e}; trying again", flush=True)
            time.sleep(15 * (attempt + 1))
    else:
        sys.exit("Couldn't get speed limits from Overpass")
    out = []
    for w in ways:
        mph = mph_of(w["tags"]["maxspeed"])
        if mph:
            geo = w["geometry"]
            for a, b in zip(geo, geo[1:]):  # fill in so no point is far from a sample
                pa, pb = (a["lon"], a["lat"]), (b["lon"], b["lat"])
                n = max(1, int(metres(pa, pb) // 10))
                h = bearing(pa, pb) if pa != pb else 0.0
                out += [(a["lon"] + (b["lon"] - a["lon"]) * k / n, a["lat"] + (b["lat"] - a["lat"]) * k / n, mph, h) for k in range(n)]
    print(f"{len(ways)} roads with a posted limit", flush=True)
    return out


def mph_of(v):
    v = v.split(";")[0].strip()
    if v.endswith("mph"):
        return float(v[:-3])
    if v == "national":
        return None
    try:
        return float(v) * 0.621371
    except ValueError:
        return {"GB:nsl_single": 60.0, "GB:nsl_dual": 70.0, "GB:motorway": 70.0}.get(v)


class LimitLookup:
    def __init__(self, samples):
        self.grid = {}
        for lon, lat, mph, h in samples:
            self.grid.setdefault((round(lon * 2000), round(lat * 2000)), []).append((lon, lat, mph, h))

    def at(self, lon, lat, heading):
        gx, gy = round(lon * 2000), round(lat * 2000)
        best = None
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for p in self.grid.get((gx + dx, gy + dy), ()):
                    d = metres((lon, lat), p[:2])
                    along = abs((p[3] - heading + 90) % 180 - 90) <= 30  # our road, not one crossing it
                    if d < 20 and along and (best is None or d < best[0]):
                        best = (d, p[2])
        return best[1] if best else None


def walk(coords, speed_at, hz):
    """(lon, lat, mps) every 1/hz s along the line, with the speed from speed_at(lon, lat, heading, dist)."""
    segs = [(a, b, metres(a, b), bearing(a, b)) for a, b in zip(coords, coords[1:]) if metres(a, b) > 0]
    i, off, dist, v = 0, 0.0, 0.0, None
    while i < len(segs):
        a, b, seg, h = segs[i]
        t = off / seg
        lon, lat = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
        want = speed_at(lon, lat, h, dist)
        v = want if v is None else (min(want, v + 1.5 / hz) if want > v else max(want, v - 1.5 / hz))
        yield lon, lat, v
        step = v / hz
        dist += step
        off += step
        while i < len(segs) and off > segs[i][2]:
            off -= segs[i][2]
            i += 1


def points(coords, step):
    """(lon, lat, heading) every `step` metres along the line."""
    carry = 0.0
    for a, b in zip(coords, coords[1:]):
        seg = metres(a, b)
        if seg == 0:
            continue
        h = bearing(a, b)
        d = carry
        while d <= seg:
            t = d / seg
            yield a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, h
            d += step
        carry = d - seg


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--via", default=LONDON, help="lon,lat;lon,lat;… waypoints")
    ap.add_argument("--mph", type=float, default=30)
    ap.add_argument("--hz", type=float, default=5, help="positions a second (smoother map with more)")
    ap.add_argument("--once", action="store_true", help="drive the route once instead of looping")
    ap.add_argument("--limits", action="store_true", help="follow the posted limits from OpenStreetMap, under/at/over in turn")
    ap.add_argument("--pattern", default="under,at,over", help="with --limits: the order of the stints")
    ap.add_argument("--stint", type=float, default=1500, help="with --limits: metres per stint")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    a = ap.parse_args()
    adb = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")
    coords = route(a.via)
    mps = a.mph * 0.44704
    step = mps / a.hz
    total = sum(metres(p, q) for p, q in zip(coords, coords[1:]))
    print(f"Route {total / 1000:.1f} km at {a.mph:g} mph (~{total / mps / 60:.0f} min a lap). Ctrl+C to stop.")
    if a.limits:
        look = LimitLookup(limits(coords))
        pattern = a.pattern.split(",")
        state = {"limit": a.mph, "said": None}

        def speed_at(lon, lat, h, dist):
            state["limit"] = look.at(lon, lat, h) or state["limit"]
            mode = pattern[int(dist // a.stint) % len(pattern)]
            mph = MODES[mode](state["limit"])
            if state["said"] != (mode, state["limit"]):
                state["said"] = (mode, state["limit"])
                print(f"{time.strftime('%H:%M:%S')} {dist / 1000:5.1f} km  {lat:.5f},{lon:.5f}  limit {state['limit']:.0f}  {mode} -> {mph:.0f} mph", flush=True)
            return mph * 0.44704
        fixes = lambda: walk(coords, speed_at, a.hz)
    else:
        fixes = lambda: ((lon, lat, mps) for lon, lat, _ in points(coords, step))
    while True:
        nxt = time.monotonic()
        for lon, lat, v in fixes():
            knots = v / 0.514444
            # geo fix <lon> <lat> <altitude m> <satellites> <speed in knots>. The emulator takes no heading
            # (and ignores "geo nmea"), so the phone works it out from the movement.
            subprocess.run([adb, "-s", a.serial, "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", "20", "12", f"{knots:.1f}"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            nxt += 1 / a.hz
            time.sleep(max(0.0, nxt - time.monotonic()))
        if a.once:
            break


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
