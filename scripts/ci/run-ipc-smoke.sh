#!/usr/bin/env bash
set -Eeuo pipefail
mkdir -p build/ci-diagnostics

collect_diagnostics() {
  local status=$?
  trap - EXIT
  if (( status != 0 )); then
    echo "=== Collecting emulator diagnostics (exit $status) ==="
    adb get-state > build/ci-diagnostics/adb-state.txt 2>&1 || true
    adb shell getprop > build/ci-diagnostics/getprop.txt 2>&1 || true
    adb shell dumpsys activity activities > build/ci-diagnostics/activities.txt 2>&1 || true
    for package_name in       io.github.alexanderwilhelmsenberg.modular       io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical       io.github.alexanderwilhelmsenberg.modular.reference.home.minimal; do
      adb shell dumpsys package "$package_name"         > "build/ci-diagnostics/package-${package_name##*.}.txt" 2>&1 || true
    done
    adb logcat -d -v threadtime > build/ci-diagnostics/logcat.txt 2>&1 || true
    find app/build/outputs/androidTest-results/connected -type f -name '*.xml'       -exec cp {} build/ci-diagnostics/ \; 2>/dev/null || true
  fi
  exit "$status"
}
trap collect_diagnostics EXIT

gradle :reference-sort-alphabetical:assembleDebug :reference-home-minimal:assembleDebug :app:assembleDebugAndroidTest --stacktrace
adb install -r reference-sort-alphabetical/build/outputs/apk/debug/reference-sort-alphabetical-debug.apk
adb install -r reference-home-minimal/build/outputs/apk/debug/reference-home-minimal-debug.apk
gradle :app:connectedDebugAndroidTest --stacktrace
