#!/usr/bin/env bash
# Copyright 2026 Damien Dye
# SPDX-License-Identifier: Apache-2.0
#
# Checks whether a Navigation SDK version adds incident reporting to the Android Auto view
# (NavigationViewForAuto). See DESIGN.md §16 #21. Usage:
#   tools/check-nav-sdk-features.sh            # version from gradle/libs.versions.toml
#   tools/check-nav-sdk-features.sh 8.1.0      # a specific version (downloads it)
#   tools/check-nav-sdk-features.sh --latest   # newest published version
set -euo pipefail
cd "$(dirname "$0")/.."

repo=https://dl.google.com/android/maven2/com/google/android/libraries/navigation/navigation
case "${1:-}" in
  --latest) ver=$(curl -fsS "$repo/maven-metadata.xml" | grep -o '<version>[^<]*' | sed 's/<version>//' | grep -vE 'alpha|beta|rc' | tail -1) ;;
  "") ver=$(grep -E '^navigationSdk *=' gradle/libs.versions.toml | sed -E 's/.*"(.*)".*/\1/') ;;
  *) ver=$1 ;;
esac

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
echo "Navigation SDK $ver"
curl -fsS -o "$work/nav.aar" "$repo/$ver/navigation-$ver.aar"
unzip -q -o "$work/nav.aar" classes.jar -d "$work"

javap=$(command -v javap || ls -d "$HOME"/.gradle/jdks/*/bin/javap 2>/dev/null | head -1)
[ -x "$javap" ] || { echo "javap not found (install a JDK, or build once so Gradle downloads one)"; exit 2; }

auto=$("$javap" -cp "$work/classes.jar" com.google.android.libraries.navigation.NavigationViewForAuto)
phone=$("$javap" -cp "$work/classes.jar" com.google.android.libraries.navigation.NavigationView)
status=0
for m in setReportIncidentButtonEnabled showReportIncidentsPanel isIncidentReportingAvailable setTrafficIncidentCardsEnabled setTrafficPromptsEnabled; do
  a=$(grep -q " $m(" <<<"$auto" && echo yes || echo no)
  p=$(grep -q " $m(" <<<"$phone" && echo yes || echo no)
  printf '  %-34s phone: %-3s  Android Auto: %s\n' "$m" "$p" "$a"
  [ "$a" = yes ] && status=10
done
if [ $status -eq 10 ]; then
  echo "=> Android Auto now has incident reporting APIs. The app enables them automatically (IncidentReporting.kt);"
  echo "   update DESIGN.md §16 #21 and test on the DHU."
else
  echo "=> Still phone-only. Nothing to do."
fi
exit $status
