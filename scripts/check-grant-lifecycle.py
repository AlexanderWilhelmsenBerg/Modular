#!/usr/bin/env python3
"""Check grant persistence in isolation before the full Core device suite."""
from pathlib import Path
import re
import subprocess

report = Path("build/reports/device/grant-lifecycle-isolated.log")
report.parent.mkdir(parents=True, exist_ok=True)
result = subprocess.run(
    ["adb", "shell", "am", "instrument", "-w", "-e", "class",
     "io.github.alexanderwilhelmsenberg.modular.security.DataStoreModuleGrantStateStoreLifecycleTest",
     "io.github.alexanderwilhelmsenberg.modular.debug.test/androidx.test.runner.AndroidJUnitRunner"],
    capture_output=True, text=True, timeout=120,
)
output = result.stdout + "\n" + result.stderr
report.write_text(output)
if result.returncode or not re.search(r"^OK \(1 test\)\s*$", output, re.MULTILINE):
    print(output, flush=True)
    raise SystemExit("Isolated grant lifecycle check failed")
print("Isolated grant lifecycle check passed", flush=True)
