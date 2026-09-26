#!/usr/bin/env bash
# Connection-setup benchmark (see ConnectionSetupBench). Each am-instrument run is a fresh process.
#   fresh:   pm clear before every launch -> no persisted Cronet state (first launch ever)
#   restart: persisted state from the previous launch kept (normal app restart)
# Usage: scripts/bench-connection-setup.sh [runs=10] > results.csv
set -euo pipefail
RUNS=${1:-10}
cd "$(dirname "$0")/.."
./gradlew -q :instrumentation-tests:installDebug :instrumentation-tests:installDebugAndroidTest >&2
# Emulator Wi-Fi (netsimd) adds 0-500 ms per packet to internet traffic; use the eth0 path.
adb shell svc wifi disable
RUNNER=sarie.instrumentation.test/androidx.test.runner.AndroidJUnitRunner
CLASS=sarie.instrumentation.bench.ConnectionSetupBench
echo "label,launch,host,stack,phase,position,proto,reused,dns,conn,tls,ttfb,wall"
run() { # label launch
  adb logcat -c
  adb shell am instrument -w -e label "$1" -e launch "$2" -e class "$CLASS" "$RUNNER" >&2
  adb logcat -d -s SarieBench:I | grep -o "BENCH,$1,$2,.*" | cut -d, -f2-
}
for i in $(seq 1 "$RUNS"); do adb shell pm clear sarie.instrumentation >&2; run fresh "$i"; done
adb shell pm clear sarie.instrumentation >&2
run warmup 0 >/dev/null # seeds persisted state
for i in $(seq 1 "$RUNS"); do run restart "$i"; done
