#!/usr/bin/env python3
"""Verify on-disk scan recovery across real target-process stops and a fresh Activity launch."""
from pathlib import Path
import json
import re
import subprocess
import time

PACKAGE = "io.github.alexanderwilhelmsenberg.modular.addon.files.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
CLASS = "io.github.alexanderwilhelmsenberg.modular.addon.files.FilesScanProcessRecoveryTest"
REPORTS = Path("build/reports/device")


def adb(*args: str, timeout: int = 30) -> str:
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stdout}\n{result.stderr}")
    return result.stdout


def instrument(method: str) -> None:
    output = adb("shell", "am", "instrument", "-w", "-e", "class", CLASS + "#" + method, RUNNER, timeout=180)
    (REPORTS / f"files-process-{method}.log").write_text(output)
    if not re.search(r"^OK \(1 test\)\s*$", output, re.MULTILINE):
        raise RuntimeError(output)


def main() -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    try:
        adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
        adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        instrument("preparePendingReadOnlyRequest")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/io.github.alexanderwilhelmsenberg.modular.addon.files.FilesActivity")
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            raw = adb("shell", "run-as", PACKAGE, "cat", "files/saved-clean-scan.json")
            state = json.loads(raw)
            if state.get("analysis"):
                break
            time.sleep(1)
        else:
            raise RuntimeError("Saved scan did not recover after a fresh Activity launch")
        # Starting the verifier replaces the target process again. The completed findings must survive it too.
        instrument("verifyRecoveredResultInAnotherProcess")
        print("Files read-only request and completed results survived real process boundaries", flush=True)
    finally:
        adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "default")


if __name__ == "__main__":
    main()
