#!/usr/bin/env bash
# Copyright 2026 Damien Dye
# SPDX-License-Identifier: Apache-2.0
#
# Checks the Google issues listed in tools/google-issues.txt for a status change or a new update
# (comment, edit) since they were last seen. See DESIGN.md §16 #21. Reads Google's public issue
# JSON, no login. Exits 10 when something changed, 0 when nothing did. Usage:
#   tools/check-google-issues.sh            # report changes
#   tools/check-google-issues.sh --update   # also rewrite google-issues.txt with what was found
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - "${1:-}" <<'PY'
import json, sys, time, urllib.request

STATUS = {1: "NEW", 2: "ASSIGNED", 3: "ACCEPTED", 4: "FIXED", 5: "VERIFIED", 6: "NOT_REPRODUCIBLE",
          7: "INTENDED_BEHAVIOR", 8: "OBSOLETE", 9: "INFEASIBLE", 10: "DUPLICATE"}
path = "tools/google-issues.txt"
lines = open(path).read().splitlines()
out, changed = [], False
for line in lines:
    if not line.strip() or line.startswith("#"):
        out.append(line)
        continue
    iid, status, modified, what = line.split(None, 3)
    url = f"https://issuetracker.google.com/action/issues/{iid}"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    text = urllib.request.urlopen(req, timeout=30).read().decode()
    # The body starts with an anti-JSON-hijacking line ")]}'".
    issue = json.loads(text[text.index("\n") + 1:])[0][1][22]
    now_status = STATUS.get(issue[2][2], str(issue[2][2]))
    now_modified = str(issue[5][0])
    title = issue[2][5]
    when = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(int(now_modified)))
    if now_status != status or now_modified != modified:
        changed = True
        what_changed = f"status {status} -> {now_status}" if now_status != status else "updated (comment or edit)"
        print(f"CHANGED {iid}: {what_changed}, last modified {when}\n  {title}\n  https://issuetracker.google.com/issues/{iid}")
    else:
        print(f"same    {iid}: {now_status}, last modified {when}")
    out.append(f"{iid:<12} {now_status:<11} {now_modified:<26} {what}")
if sys.argv[1] == "--update":
    open(path, "w").write("\n".join(out) + "\n")
sys.exit(10 if changed else 0)
PY
