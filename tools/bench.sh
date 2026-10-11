#!/usr/bin/env bash
# Copyright 2026 Damien Dye
# SPDX-License-Identifier: Apache-2.0
#
# Test bench: phone emulator (Google Play image) + Android Auto Desktop Head Unit.
# See TESTING.md §3. Usage:
#   tools/bench.sh setup      # one-time: SDK packages + create the "auto-bench" emulator
#   tools/bench.sh phone      # start the phone emulator and wait for it to boot
#   tools/bench.sh install    # build (if needed) and install the debug APK on the emulator
#   tools/bench.sh car [cfg]  # start the head unit (cfg: default_1080p, default_sensors, default, …)
#   tools/bench.sh all        # phone + install + car
#   tools/bench.sh stop       # stop the head unit and the emulator
set -euo pipefail
cd "$(dirname "$0")/.."

ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
SM=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
AVDM=$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager
EMU=$ANDROID_HOME/emulator/emulator
ADB=$ANDROID_HOME/platform-tools/adb
DHU_DIR=$ANDROID_HOME/extras/google/auto
IMAGE="system-images;android-36;google_apis_playstore;x86_64"
CORES=${BENCH_CORES:-2}   # vCPUs for the emulator (override with BENCH_CORES=n)
# GPU mode. Default: host GPU with guest Vulkan disabled. Why:
#  - "-gpu host" with Vulkan on crashes on AMD Raven + Mesa 26.2: SIGSEGV in radeonsi
#    si_resource_from_memobj, via gfxstream ColorBufferGl::importMemory (Vulkan→GL memory import).
#  - "-gpu swiftshader_indirect" (software) needs writable+executable heap for its JIT, which
#    SELinux blocks (execheap; selinuxuser_execheap is off by default on Fedora), so it crashes too.
#    We don't turn that boolean on: it weakens protection for every user process.
# Override with BENCH_GPU=… and BENCH_GPU_FLAGS=…
GPU=${BENCH_GPU:-host}
GPU_FLAGS=${BENCH_GPU_FLAGS:--feature -Vulkan}
AVD=auto-bench
LOGDIR=${TMPDIR:-/tmp}/autonav-bench
mkdir -p "$LOGDIR"

serial() { $ADB devices | awk '/^emulator-/{print $1; exit}'; }

setup() {
  yes | $SM --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
  $SM --sdk_root="$ANDROID_HOME" "platform-tools" "emulator" "extras;google;auto" "$IMAGE"
  if ! $EMU -list-avds | grep -qx "$AVD"; then
    echo no | $AVDM create avd -n "$AVD" -k "$IMAGE" -d pixel_8 --force
    # Enough RAM and a hardware keyboard make the Play image much more usable.
    cfg="$HOME/.android/avd/$AVD.avd/config.ini"
    sed -i 's/^hw.ramSize=.*/hw.ramSize=4096/; s/^hw.keyboard=.*/hw.keyboard=yes/; s/^hw.cpu.ncore=.*/hw.cpu.ncore='"$CORES"'/' "$cfg"
    grep -q '^hw.cpu.ncore' "$cfg" || echo "hw.cpu.ncore=$CORES" >> "$cfg"
    grep -q '^hw.ramSize' "$cfg" || echo 'hw.ramSize=4096' >> "$cfg"
    grep -q '^hw.keyboard' "$cfg" || echo 'hw.keyboard=yes' >> "$cfg"
  fi
  if ldd "$DHU_DIR/desktop-head-unit" | grep -q "not found"; then
    echo "The head unit needs the LLVM C++ runtime:  sudo dnf install libcxx libcxxabi   (Debian/Ubuntu: libc++1 libc++abi1)"
  fi
}

phone() {
  if [ -n "$(serial)" ]; then echo "Emulator already running: $(serial)"; return; fi
  # setsid: fully detached, so closing the terminal or tool that started it doesn't kill it.
  # shellcheck disable=SC2086  # GPU_FLAGS is intentionally word-split
  setsid nohup "$EMU" -avd "$AVD" -cores "$CORES" -gpu "$GPU" $GPU_FLAGS -no-snapshot-save -netdelay none -netspeed full \
    >"$LOGDIR/emulator.log" 2>&1 < /dev/null &
  echo "Starting emulator (log: $LOGDIR/emulator.log)…"
  $ADB wait-for-device
  until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
  echo "Emulator booted: $(serial)"
}

install() {
  apk=app/build/outputs/apk/debug/app-debug.apk
  [ -f "$apk" ] || ./gradlew :app:assembleDebug
  $ADB -s "$(serial)" install -r -g "$apk"
  echo "Installed AutoNavAlerts (runtime permissions pre-granted with -g)."
}

car() {
  cfg=${1:-default_1080p}
  $ADB -s "$(serial)" forward tcp:5277 tcp:5277
  echo "Starting head unit with config/$cfg.ini (log: $LOGDIR/dhu.log). In Android Auto on the phone: ⋮ → Start head unit server first."
  ( cd "$DHU_DIR" && nohup ./desktop-head-unit -c "config/$cfg.ini" >"$LOGDIR/dhu.log" 2>&1 & )
}

stop() {
  pkill -f desktop-head-unit || true
  [ -n "$(serial)" ] && $ADB -s "$(serial)" emu kill || true
}

case "${1:-}" in
  setup) setup ;;
  phone) phone ;;
  install) install ;;
  car) car "${2:-}" ;;
  all) phone; install; car "${2:-}" ;;
  stop) stop ;;
  *) sed -n '5,12p' "$0"; exit 1 ;;
esac
