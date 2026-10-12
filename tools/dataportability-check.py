#!/usr/bin/env python3
# Copyright 2026 Damien Dye
# SPDX-License-Identifier: Apache-2.0
"""
Calls Google's Data Portability API directly, outside the app, to see what Google returns for
an account's labeled places (Home and Work; DESIGN.md §8.2a). Standard library only.

Getting a token (one of):
  --client-secret FILE   a "Desktop app" OAuth client's JSON from the Cloud project that owns the
                         app's consent screen; opens the browser for consent on this PC.
  --token TOKEN          an access token you already have (valid for about an hour).
  --token-file FILE      read the token from FILE; a browser consent also saves its token there.
  --no-browser           print the consent URL instead of opening the browser.
  --prompt none          reuse an active grant (e.g. 30-day access) without any consent page.

Commands:
  check                  what access the token has (accessType:check)
  run [--resources R..]  initiate -> poll -> download -> print the JSON inside the zips
  initiate / state JOB / download JOB   the same, one step at a time
  reset                  authorization:reset, so the account can consent and export again

Example:
  tools/dataportability-check.py --client-secret ~/.config/autonavalerts/desktop-oauth.json run
"""
import argparse, http.server, json, secrets, sys, threading, time, urllib.parse, urllib.request, webbrowser, zipfile, io

API = "https://dataportability.googleapis.com/v1"
SCOPE_PREFIX = "https://www.googleapis.com/auth/dataportability."
DEFAULT_RESOURCES = ["maps.aliased_places"]


def oauth_desktop(secret_path, resources, open_browser=True, prompt="consent"):
    """Installed-app flow with a loopback redirect, as Google documents for desktop apps."""
    cfg = json.load(open(secret_path))
    cfg = cfg.get("installed") or cfg.get("web") or cfg
    code_holder = {}

    class H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            code_holder.update({k: v[0] for k, v in q.items()})
            self.send_response(200); self.end_headers()
            self.wfile.write(b"You can close this tab and go back to the terminal.")
        def log_message(self, *a): pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), H)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    redirect = f"http://127.0.0.1:{port}/"
    state = secrets.token_urlsafe(16)
    url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
        "client_id": cfg["client_id"], "redirect_uri": redirect, "response_type": "code",
        "scope": " ".join(SCOPE_PREFIX + r for r in resources), "state": state, "access_type": "online", "prompt": prompt,
    })
    print("Opening the browser for consent (sign in with the account whose Home/Work you want):\n  " + url)
    if open_browser:
        webbrowser.open(url)
    while "code" not in code_holder and "error" not in code_holder:
        time.sleep(0.5)
    srv.shutdown()
    if "error" in code_holder:
        sys.exit("Consent failed: " + code_holder["error"])
    if code_holder.get("state") != state:
        sys.exit("OAuth state mismatch")
    body = urllib.parse.urlencode({
        "code": code_holder["code"], "client_id": cfg["client_id"], "client_secret": cfg.get("client_secret", ""),
        "redirect_uri": redirect, "grant_type": "authorization_code",
    }).encode()
    tok = json.load(urllib.request.urlopen(urllib.request.Request(cfg.get("token_uri", "https://oauth2.googleapis.com/token"), body)))
    print("Granted scopes:", tok.get("scope"))
    return tok["access_token"], tok.get("scope", "")


def call(token, path, body=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, method=method or ("POST" if data is not None else "GET"))
    req.add_header("Authorization", "Bearer " + token)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text.strip() else {})
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {"raw": text}


def show(status, obj):
    print(f"HTTP {status}\n" + json.dumps(obj, indent=2)[:4000])


def download(urls):
    for i, u in enumerate(urls):
        raw = urllib.request.urlopen(u).read()
        print(f"\n== file {i}: {len(raw)} bytes")
        if raw[:2] == b"PK":
            z = zipfile.ZipFile(io.BytesIO(raw))
            for n in z.namelist():
                print(f"-- {n}")
                if n.lower().endswith(".json"):
                    print(z.read(n).decode()[:6000])
        else:
            print(raw.decode(errors="replace")[:6000])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--client-secret")
    ap.add_argument("--token")
    ap.add_argument("--no-browser", action="store_true")
    ap.add_argument("--token-file")
    ap.add_argument("--prompt", default="consent", choices=["consent", "none", "select_account"])
    ap.add_argument("--resources", nargs="+", default=DEFAULT_RESOURCES)
    ap.add_argument("cmd", choices=["check", "run", "initiate", "state", "download", "reset"])
    ap.add_argument("job", nargs="?")
    a = ap.parse_args()
    granted = None
    if a.token:
        token = a.token
    elif a.token_file and not a.client_secret:
        token = open(a.token_file).read().strip()
    elif a.client_secret:
        token, granted = oauth_desktop(a.client_secret, a.resources, not a.no_browser, a.prompt)
        if a.token_file:
            import os
            with open(os.open(a.token_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
                f.write(token)
    else:
        sys.exit("Give --client-secret FILE, --token TOKEN or --token-file FILE")
    if granted is not None:
        # Only the resources the user actually ticked on the consent page.
        allowed = [r for r in a.resources if SCOPE_PREFIX + r in granted.split()]
        if allowed != a.resources:
            print("Not granted, skipped:", [r for r in a.resources if r not in allowed])
        a.resources = allowed or sys.exit("None of the requested resources was granted")

    if a.cmd == "check":
        show(*call(token, "/accessType:check", {}))
    elif a.cmd == "reset":
        show(*call(token, "/authorization:reset", {}))
    elif a.cmd == "initiate":
        show(*call(token, "/portabilityArchive:initiate", {"resources": a.resources}))
    elif a.cmd == "state":
        show(*call(token, f"/archiveJobs/{a.job}/portabilityArchiveState"))
    elif a.cmd == "download":
        s, st = call(token, f"/archiveJobs/{a.job}/portabilityArchiveState"); show(s, st)
        download(st.get("urls", []))
    elif a.cmd == "run":
        s, r = call(token, "/portabilityArchive:initiate", {"resources": a.resources}); show(s, r)
        if s == 409:
            sys.exit("The grant was already used for an export: run `reset`, consent again, then `run`.")
        if s != 200:
            sys.exit(1)
        job = r["archiveJobId"]
        for _ in range(60):
            s, st = call(token, f"/archiveJobs/{job}/portabilityArchiveState")
            print("state:", st.get("state"), st.get("urls") and f"{len(st['urls'])} url(s)")
            if st.get("state") == "COMPLETE":
                download(st["urls"]); break
            if st.get("state") in ("FAILED", "CANCELLED"):
                show(s, st); break
            time.sleep(3)


if __name__ == "__main__":
    main()
