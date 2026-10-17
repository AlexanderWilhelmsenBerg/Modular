#!/usr/bin/env bash
set -Eeuo pipefail

diagnostics_dir="build/ci-diagnostics"
mkdir -p "$diagnostics_dir"
stage="initialization"

collect_diagnostics() {
  local status=$?
  trap - EXIT
  if (( status != 0 )); then
    printf 'stage=%s\nexit_status=%s\n' "$stage" "$status" > "$diagnostics_dir/failure-stage.txt"
    echo "::error title=IPC smoke failure::Stage '$stage' failed (exit $status)"
    adb get-state > "$diagnostics_dir/adb-state.txt" 2>&1 || true
    adb shell getprop sys.boot_completed > "$diagnostics_dir/boot-completed.txt" 2>&1 || true
    adb shell dumpsys activity activities > "$diagnostics_dir/activities.txt" 2>&1 || true
    for package_name in \
      io.github.alexanderwilhelmsenberg.modular \
      io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical \
      io.github.alexanderwilhelmsenberg.modular.reference.home.minimal \
      io.github.alexanderwilhelmsenberg.modular.reference.drawer.basic; do
      adb shell dumpsys package "$package_name" \
        > "$diagnostics_dir/package-${package_name##*.}.txt" 2>&1 || true
    done
    timeout 15s adb logcat -d -v threadtime -t 3000 \
      > "$diagnostics_dir/logcat.txt" 2>&1 || true
  fi
  exit "$status"
}
trap collect_diagnostics EXIT

stage="assemble instrumentation and reference APKs"
echo "::group::$stage"
gradle \
  :reference-sort-alphabetical:assembleDebug \
  :reference-home-minimal:assembleDebug \
  :reference-drawer-basic:assembleDebug \
  :app:assembleDebugAndroidTest \
  --stacktrace
echo "::endgroup::"

stage="install alphabetical sort APK"
echo "::group::$stage"
adb install -r \
  reference-sort-alphabetical/build/outputs/apk/debug/reference-sort-alphabetical-debug.apk
echo "::endgroup::"

stage="install minimal Home APK"
echo "::group::$stage"
adb install -r \
  reference-home-minimal/build/outputs/apk/debug/reference-home-minimal-debug.apk
echo "::endgroup::"

stage="install basic Drawer APK"
adb install -r reference-drawer-basic/build/outputs/apk/debug/reference-drawer-basic-debug.apk

stage="cross-APK instrumentation tests"
echo "::group::$stage"
gradle :app:connectedDebugAndroidTest --stacktrace
echo "::endgroup::"
