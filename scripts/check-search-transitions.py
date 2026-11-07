#!/usr/bin/env python3
"""Fail on any intermittent search-position regression in fresh test activities."""
from pathlib import Path
import re
import subprocess

test_class = "io.github.alexanderwilhelmsenberg.modular.ui.CoreHomeTest"
tests = ",".join(
    f"{test_class}#{name}" for name in (
        "bottomControlsStayBelowSectionedRowsAndSearchHidesRail",
        "searchFromRetainedLateKeyShowsFirstResultAndRestoresAnchor",
    )
)
report = Path("build/reports/search-transitions.log")
report.parent.mkdir(parents=True, exist_ok=True)
with report.open("w") as log:
    for round_number in range(1, 11):
        result = subprocess.run(
            ["adb", "shell", "am", "instrument", "-w", "-e", "class", tests,
             "io.github.alexanderwilhelmsenberg.modular.debug.test/androidx.test.runner.AndroidJUnitRunner"],
            capture_output=True, text=True, timeout=120,
        )
        log.write(f"Round {round_number}\n{result.stdout}\n{result.stderr}\n")
        log.flush()
        if result.returncode or not re.search(r"^OK \(2 tests\)\s*$", result.stdout, re.MULTILINE):
            print(result.stdout, result.stderr, flush=True)
            raise SystemExit(f"Search transition regression failed in round {round_number}")
        print(f"Search transition round {round_number}/10 passed", flush=True)
