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

Realism (all off by default; a clean centreline at a steady rate is the one thing a real phone never gives you):
  --noise 6                  position wander of ~6 m that drifts slowly (AR(1), α 0.9), not white jitter
  --speed-noise 5            the reported speed varies by ±5 %
  --dropout 3,45             no fixes for 3 s every 45 s (a tunnel, a bridge)
  --rate-jitter              irregular fix spacing (±40 % around --hz), as the fused provider delivers it
  --hold 60                  sit at the start point for 60 s before moving (a parked car; add --noise for parked wander)
  --stop-at "lon,lat" --for 30   stop at that point on the route for 30 s, then carry on
  --crawl-mph 3 --from "lon,lat" --to "lon,lat"   crawl at 3 mph between those points, --mph elsewhere

Car feed (the DHU started with tools/bench.sh car default_sensors, TESTING.md §6.4a):
  --car-speed / --car-location   mirror every fix to the head unit's sensor console (speed <m/s>,
                             location lat lon acc alt speed bearing), appended to $TMPDIR/autonav-bench/dhu.in
  --car-lag 300              the car feed arrives 300 ms late; --car-noise 3: its own wander;
  --car-dropout 5,60         the car feed (only) stops for 5 s every 60 s; --car-only: feed the car, not the emulator

Recording and replay (the app's drive-recording CSV layout, docs/SETTINGS.md §1.8):
  --record out.csv           write every fix sent
  --replay drive.csv [--speed 2]   drive the emulator from a recording (rows with used=1) at real time or 2×.
                             Only position and speed reach the emulator: the recorded bearing and accuracy are
                             ignored by "geo fix", so the JVM replay (TESTING.md G10) covers those.
  --bearing                  experimental: send the heading too, through the emulator's gRPC setGps (grpcurl,
                             port = console port + 3000, usually 8554); falls back to geo fix if that fails.
"""
import argparse, collections, csv, json, math, os, random, shutil, subprocess, sys, time, urllib.parse, urllib.request

LONDON = ("-0.1281,51.5080;-0.0754,51.5055;-0.0803,51.5155;-0.1236,51.5308;"  # Trafalgar Sq, Tower Br, City, King's X
          "-0.1586,51.5229;-0.1527,51.5027;-0.1419,51.5014;-0.1281,51.5080")   # Baker St, Hyde Pk Cnr, Palace, back

RECORD_COLUMNS = ("t_ms,kind,src,used,lat,lon,acc_m,spd_mps,brg_deg,has_brg,alt_m,fix_t_ms,"
                  "eng_spd_mps,eng_hdg_deg,hdg_src,car_raw_mps,car_disp_mps").split(",")


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


def now():
    return time.strftime("%H:%M:%S")


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


# One position to send: t = seconds since the drive started, hdg = the road's bearing there.
Fix = collections.namedtuple("Fix", "t lon lat mps hdg")


def drive(coords, speed_at, dt_at):
    """A Fix every dt_at() seconds along the line, with the speed from speed_at(lon, lat, heading, dist, t, v).
    Speed changes ramp at 1.5 m/s²; a wanted speed of 0 stops the car where it is (time still passes)."""
    segs = [(a, b, metres(a, b), bearing(a, b)) for a, b in zip(coords, coords[1:]) if metres(a, b) > 0]
    i, off, dist, v, t = 0, 0.0, 0.0, None, 0.0
    while i < len(segs):
        a, b, seg, h = segs[i]
        f = off / seg
        lon, lat = a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        dt = dt_at()
        want = speed_at(lon, lat, h, dist, t, v or 0.0)
        v = want if v is None else (min(want, v + 1.5 * dt) if want > v else max(want, v - 1.5 * dt))
        yield Fix(t, lon, lat, v, h)
        step = v * dt
        dist += step
        off += step
        t += dt
        while i < len(segs) and off > segs[i][2]:
            off -= segs[i][2]
            i += 1


def along(coords, p):
    """(distance along the line of the point on it nearest p, how far p is from the line) in metres."""
    best, cum = (float("inf"), 0.0), 0.0
    for a, b in zip(coords, coords[1:]):
        seg = metres(a, b)
        if seg == 0:
            continue
        lat = math.radians(a[1])
        ex, ey = (b[0] - a[0]) * 111320 * math.cos(lat), (b[1] - a[1]) * 110540
        px, py = (p[0] - a[0]) * 111320 * math.cos(lat), (p[1] - a[1]) * 110540
        f = max(0.0, min(1.0, (px * ex + py * ey) / (seg * seg)))
        off = math.hypot(px - f * ex, py - f * ey)
        if off < best[0]:
            best = (off, cum + f * seg)
        cum += seg
    return best[1], best[0]


def lonlat(s):
    try:
        lon, lat = (float(x) for x in s.split(","))
    except ValueError:
        raise argparse.ArgumentTypeError(f'"{s}" is not lon,lat')
    return lon, lat


def on_route(coords, p, what):
    d, off = along(coords, p)
    if off > 50:
        print(f"{what} {p[0]:.5f},{p[1]:.5f} is {off:.0f} m off the route (using the nearest point, {d / 1000:.2f} km in)", flush=True)
    return d


class StopAt:
    """Brake to a halt at a point on the route, wait, then carry on."""

    def __init__(self, dist, seconds):
        self.dist, self.seconds, self.until, self.done = dist, seconds, None, False

    def want(self, normal, dist, t, v):
        if self.done:
            return normal
        if self.until is None:
            if dist < self.dist - v * v / 3.0:  # braking distance at 1.5 m/s²
                return normal
            if v <= 0.0:
                self.until = t + self.seconds
                print(f"{now()} Stopped {dist / 1000:5.2f} km in for {self.seconds:g} s", flush=True)
            return 0.0
        if t < self.until:
            return 0.0
        self.done = True
        print(f"{now()} Moving on", flush=True)
        return normal


class Crawl:
    def __init__(self, d1, d2, mps):
        self.d1, self.d2, self.mps, self.inside = min(d1, d2), max(d1, d2), mps, False

    def want(self, normal, dist):
        inside = self.d1 <= dist <= self.d2
        if inside != self.inside:
            self.inside = inside
            print(f"{now()} {'Crawling at %.0f mph' % (self.mps / 0.44704) if inside else 'Back to normal speed'} {dist / 1000:5.2f} km in", flush=True)
        return min(normal, self.mps) if inside else normal


class Wander:
    """AR(1) position noise in metres, per axis: x = α·x + σ·√(1−α²)·N(0,1), so the wander's standard deviation is σ."""

    def __init__(self, sigma, alpha=0.9):
        self.sigma, self.alpha, self.k = sigma, alpha, sigma * math.sqrt(1 - alpha * alpha)
        self.e = self.n = 0.0

    def apply(self, lon, lat):
        if not self.sigma:
            return lon, lat
        self.e = self.alpha * self.e + self.k * random.gauss(0, 1)
        self.n = self.alpha * self.n + self.k * random.gauss(0, 1)
        return lon + self.e / (111320 * math.cos(math.radians(lat))), lat + self.n / 110540


class Dropout:
    """No fixes for `seconds` every `every` seconds (the first gap starts at t = every)."""

    def __init__(self, spec, what):
        self.seconds = self.every = 0.0
        self.what, self.active = what, False
        if spec:
            try:
                self.seconds, self.every = (float(x) for x in spec.split(","))
            except ValueError:
                sys.exit(f"--dropout wants S,EVERY seconds, not {spec!r}")
            if self.seconds <= 0 or self.every <= self.seconds:
                sys.exit("--dropout: EVERY must be longer than S")

    def dropped(self, t):
        if not self.every:
            return False
        active = t >= self.every and (t % self.every) < self.seconds
        if active != self.active:
            self.active = active
            print(f"{now()} {self.what} fixes {'stop for %g s' % self.seconds if active else 'back'}", flush=True)
        return active


class Emulator:
    """geo fix (no heading), or the gRPC setGps with a heading when --bearing and grpcurl work."""

    def __init__(self, adb, serial, want_bearing):
        self.adb, self.serial, self.grpc = adb, serial, None
        if want_bearing:
            tool = shutil.which("grpcurl")
            port = int(serial.rsplit("-", 1)[1]) + 3000 if serial.startswith("emulator-") and serial.rsplit("-", 1)[1].isdigit() else 8554
            if tool:
                self.grpc = (tool, f"localhost:{port}")
            else:
                print("--bearing: grpcurl not found; sending geo fix (no heading)", flush=True)

    def send(self, lon, lat, mps, hdg):
        if self.grpc:
            tool, addr = self.grpc
            body = json.dumps({"latitude": lat, "longitude": lon, "speed": mps, "bearing": hdg, "altitude": 20, "satellites": 12})
            try:
                r = subprocess.run([tool, "-plaintext", "-d", body, addr, "android.emulation.control.EmulatorController/setGps"],
                                   capture_output=True, text=True, timeout=5)
                if r.returncode == 0:
                    return
                why = (r.stderr or r.stdout).strip().splitlines()[-1:] or ["no output"]
            except (OSError, subprocess.TimeoutExpired) as e:
                why = [str(e)]
            print(f"--bearing: setGps on {addr} failed ({why[0]}); falling back to geo fix (no heading)", flush=True)
            self.grpc = None
        # geo fix <lon> <lat> <altitude m> <satellites> <speed in knots>. The emulator takes no heading
        # (and ignores "geo nmea"), so the phone works it out from the movement.
        subprocess.run([self.adb, "-s", self.serial, "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", "20", "12", f"{mps / 0.514444:.1f}"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


class CarFeed:
    """Lines for the DHU's sensor console, appended to the file bench.sh keeps it reading (optionally late)."""

    def __init__(self, path, speed, location, lag_ms):
        if not os.path.exists(path):
            sys.exit(f"The head unit isn't running ({path} is missing): tools/bench.sh car default_sensors")
        self.path, self.speed, self.location, self.lag = path, speed, location, lag_ms / 1000.0
        self.queue = collections.deque()

    def send(self, lat, lon, mps, hdg, acc):
        due = time.monotonic() + self.lag
        if self.speed:
            self.queue.append((due, f"speed {mps:.2f}"))
        if self.location:
            self.queue.append((due, f"location {lat:.6f} {lon:.6f} {acc:.0f} 20 {mps:.2f} {hdg:.0f}"))
        self.flush()

    def next_due(self):
        return self.queue[0][0] if self.queue else None

    def flush(self, everything=False):
        t = time.monotonic()
        lines = []
        while self.queue and (everything or self.queue[0][0] <= t):
            lines.append(self.queue.popleft()[1])
        if lines:
            with open(self.path, "a") as f:
                f.write("".join(l + "\n" for l in lines))


class Recorder:
    def __init__(self, path):
        self.f = open(path, "w", newline="")
        self.w = csv.writer(self.f)
        self.w.writerow(RECORD_COLUMNS)

    def write(self, lat, lon, acc, mps, hdg, car_mps):
        t = int(time.time() * 1000)
        self.w.writerow([t, "fix", "sim", 1, f"{lat:.6f}", f"{lon:.6f}", f"{acc:.0f}", f"{mps:.2f}", f"{hdg:.1f}", 1, 20, t,
                         "", "", "", "" if car_mps is None else f"{car_mps:.2f}", ""])
        self.f.flush()

    def close(self):
        self.f.close()


def replay(path, speed):
    """Fixes from a drive recording: the rows the app used (used=1) with a position, at the recorded intervals / speed."""
    with open(path, newline="") as f:
        rows = [r for r in csv.DictReader(f) if r.get("lat") and r.get("lon") and r.get("used", "1") == "1"]
    if not rows:
        sys.exit(f"{path}: no used=1 rows with a position")
    t0, t, last = int(rows[0]["t_ms"]), 0.0, None
    for r in rows:
        if last is not None:
            gap = (int(r["t_ms"]) - last) / 1000.0 / speed
            if gap > 30:
                print(f"{now()} {gap * speed:.0f} s gap in the recording, played as 30 s", flush=True)
                gap = 30.0
            t += max(0.0, gap)
        last = int(r["t_ms"])
        yield Fix(t, float(r["lon"]), float(r["lat"]), float(r["spd_mps"] or 0) * speed, float(r["brg_deg"] or 0))


def hold_first(fixes, seconds, dt_at):
    """The first fix repeated, stationary, for `seconds`; then the drive with its clock shifted."""
    first = next(fixes)
    print(f"{now()} Parked at {first.lat:.5f},{first.lon:.5f} for {seconds:g} s", flush=True)
    t = 0.0
    while t < seconds:
        yield first._replace(t=t, mps=0.0)
        t += dt_at()
    print(f"{now()} Moving", flush=True)
    yield first._replace(t=t)
    for f in fixes:
        yield f._replace(t=f.t + t)


def wait_until(deadline, car):
    """Sleep until the next fix is due, sending any lagged car lines that fall due meanwhile."""
    while True:
        t = time.monotonic()
        if car:
            car.flush()
        if t >= deadline:
            return
        due = car.next_due() if car else None
        time.sleep(max(0.0, (min(deadline, due) if due is not None else deadline) - t))


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
    g = ap.add_argument_group("realism (all off by default)")
    g.add_argument("--noise", type=float, default=0, metavar="M", help="position wander of about M metres, drifting slowly (AR(1))")
    g.add_argument("--speed-noise", type=float, default=0, metavar="P", help="the reported speed varies by ±P%%")
    g.add_argument("--dropout", metavar="S,EVERY", help="no fixes for S seconds every EVERY seconds")
    g.add_argument("--rate-jitter", action="store_true", help="irregular fix spacing, ±40%% around --hz")
    g.add_argument("--hold", type=float, default=0, metavar="N", help="sit at the start point for N seconds before moving")
    g.add_argument("--stop-at", type=lonlat, metavar="LON,LAT", help="stop at that point on the route for --for seconds")
    g.add_argument("--for", dest="stop_for", type=float, default=30, metavar="N", help="with --stop-at: seconds stopped (default 30)")
    g.add_argument("--crawl-mph", type=float, metavar="N", help="crawl at N mph between --from and --to")
    g.add_argument("--from", dest="crawl_from", type=lonlat, metavar="LON,LAT", help="with --crawl-mph: where the crawl starts")
    g.add_argument("--to", dest="crawl_to", type=lonlat, metavar="LON,LAT", help="with --crawl-mph: where it ends")
    c = ap.add_argument_group("car feed (DHU on the default_sensors profile)")
    c.add_argument("--car-speed", action="store_true", help="send each fix's speed to the head unit's console (speed <m/s>)")
    c.add_argument("--car-location", action="store_true", help="send each fix's position to the head unit's console (location lat lon acc alt speed bearing)")
    c.add_argument("--car-lag", type=float, default=0, metavar="MS", help="the car feed arrives MS milliseconds late")
    c.add_argument("--car-noise", type=float, default=0, metavar="M", help="the car feed's own position wander, M metres")
    c.add_argument("--car-dropout", metavar="S,EVERY", help="the car feed only: no reports for S seconds every EVERY seconds")
    c.add_argument("--car-only", action="store_true", help="feed the car and leave the emulator's GPS alone")
    r = ap.add_argument_group("recording and replay")
    r.add_argument("--record", metavar="OUT.CSV", help="write every fix sent, in the app's drive-recording layout")
    r.add_argument("--replay", metavar="DRIVE.CSV", help="drive from a recording instead of a route (used=1 rows; position and "
                   "speed only — the emulator ignores the recorded bearing and accuracy); plays once")
    r.add_argument("--speed", type=float, default=1.0, metavar="X", help="with --replay: play X times faster (speeds scaled to match)")
    r.add_argument("--bearing", action="store_true", help="experimental: send the heading too through the emulator's gRPC setGps "
                   "(needs grpcurl); falls back to geo fix")
    a = ap.parse_args()
    if a.crawl_mph is not None and not (a.crawl_from and a.crawl_to):
        ap.error("--crawl-mph needs --from and --to")
    if a.car_only and not (a.car_speed or a.car_location):
        ap.error("--car-only needs --car-speed and/or --car-location")
    if a.car_lag < 0 or a.speed <= 0 or a.hz <= 0:
        ap.error("--car-lag, --speed and --hz must be positive")
    adb = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")
    hz_dt = 1 / a.hz
    dt_at = (lambda: hz_dt * random.uniform(0.6, 1.4)) if a.rate_jitter else (lambda: hz_dt)

    if a.replay:
        fixes = lambda: replay(a.replay, a.speed)
        once = True
        print(f"Replaying {a.replay} at {a.speed:g}× (position and speed only). Ctrl+C to stop.")
    else:
        coords = route(a.via)
        mps = a.mph * 0.44704
        total = sum(metres(p, q) for p, q in zip(coords, coords[1:]))
        print(f"Route {total / 1000:.1f} km at {a.mph:g} mph (~{total / mps / 60:.0f} min a lap). Ctrl+C to stop.")
        if a.limits:
            look = LimitLookup(limits(coords))
            pattern = a.pattern.split(",")
            state = {"limit": a.mph, "said": None}

            def base(lon, lat, h, dist):
                state["limit"] = look.at(lon, lat, h) or state["limit"]
                mode = pattern[int(dist // a.stint) % len(pattern)]
                mph = MODES[mode](state["limit"])
                if state["said"] != (mode, state["limit"]):
                    state["said"] = (mode, state["limit"])
                    print(f"{now()} {dist / 1000:5.1f} km  {lat:.5f},{lon:.5f}  limit {state['limit']:.0f}  {mode} -> {mph:.0f} mph", flush=True)
                return mph * 0.44704
        else:
            base = lambda lon, lat, h, dist: mps
        crawl = Crawl(on_route(coords, a.crawl_from, "--from"), on_route(coords, a.crawl_to, "--to"), a.crawl_mph * 0.44704) if a.crawl_mph is not None else None
        stop = StopAt(on_route(coords, a.stop_at, "--stop-at"), a.stop_for) if a.stop_at else None

        def speed_at(lon, lat, h, dist, t, v):
            want = base(lon, lat, h, dist)
            if crawl:
                want = crawl.want(want, dist)
            if stop:
                want = stop.want(want, dist, t, v)
            return want
        fixes = lambda: drive(coords, speed_at, dt_at)
        once = a.once

    car = None
    if a.car_speed or a.car_location:
        car = CarFeed(os.path.join(os.environ.get("TMPDIR", "/tmp"), "autonav-bench", "dhu.in"), a.car_speed, a.car_location, a.car_lag)
        print(f"Car feed: {'speed ' if a.car_speed else ''}{'location ' if a.car_location else ''}-> {car.path}"
              f"{' %g ms late' % a.car_lag if a.car_lag else ''}", flush=True)
    emu = Emulator(adb, a.serial, a.bearing)
    rec = Recorder(a.record) if a.record else None
    noise, car_noise = Wander(a.noise), Wander(a.car_noise)
    drop, car_drop = Dropout(a.dropout, "phone"), Dropout(a.car_dropout, "car")
    acc = a.noise if a.noise else 5.0
    T, nxt, lap = 0.0, time.monotonic(), 0
    try:
        while True:
            last_t = 0.0
            for f in (hold_first(fixes(), a.hold, dt_at) if a.hold and lap == 0 else fixes()):
                dt = f.t - last_t
                last_t = f.t
                T += dt
                nxt += dt
                wait_until(nxt, car)
                lon, lat = noise.apply(f.lon, f.lat)
                v = f.mps * (1 + random.uniform(-a.speed_noise, a.speed_noise) / 100) if a.speed_noise else f.mps
                v = max(0.0, v)
                car_v = None
                if car and not car_drop.dropped(T):
                    clon, clat = car_noise.apply(f.lon, f.lat)
                    car.send(clat, clon, v, f.hdg, a.car_noise if a.car_noise else 5.0)
                    car_v = v
                if not drop.dropped(T):
                    if not a.car_only:
                        emu.send(lon, lat, v, f.hdg)
                    if rec:
                        rec.write(lat, lon, acc, v, f.hdg, car_v)
            lap += 1
            if once:
                break
            nxt += hz_dt
    finally:
        if car:
            car.flush(everything=True)
        if rec:
            rec.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
