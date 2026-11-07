#!/usr/bin/env python3
"""Capture Core Most Used Compose fixtures at representative device sizes and font scales."""
from pathlib import Path
import subprocess


OUT = Path("build/ui-screenshots/core-most-used")
OUT.mkdir(parents=True, exist_ok=True)
PACKAGE = "io.github.alexanderwilhelmsenberg.modular.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "io.github.alexanderwilhelmsenberg.modular.ui.CoreMostUsedScreenshotTest"
VIEWPORTS = [
    (320, "no", "1.0"),
    (375, "no", "1.0"),
    (414, "yes", "1.0"),
    (768, "yes", "1.0"),
    (375, "no", "1.5"),
]
SCENES = ("disabled-card", "enabled-pins-access-off", "access-on-no-history")


def adb(*args, timeout=120):
    return subprocess.check_output(["adb", *args], timeout=timeout, text=True)


try:
    adb("shell", "wm", "density", "160")
    for width, night, font in VIEWPORTS:
        adb("shell", "wm", "size", f"{width}x900")
        adb("shell", "cmd", "uimode", "night", night)
        adb("shell", "settings", "put", "system", "font_scale", font)
        result = adb("shell", "am", "instrument", "-w", "-e", "class", TEST_CLASS, RUNNER, timeout=300)
        if "OK (3 tests)" not in result:
            raise RuntimeError(f"Core Most Used screenshots failed at {width}dp, font {font}:\n{result}")
        print(f"Captured Core Most Used at {width}dp, font {font}")
    subprocess.run(
        ["adb", "pull", "/sdcard/Pictures/ModularCoreMostUsed/.", str(OUT)],
        check=True,
        timeout=60,
    )
    for width, night, font in VIEWPORTS:
        mode = "dark" if night == "yes" else "light"
        for scene in SCENES:
            screenshot = OUT / f"core-most-used-{scene}-{width}dp-{mode}-font{font}.png"
            if not screenshot.is_file() or screenshot.stat().st_size == 0:
                raise RuntimeError(f"Missing Core Most Used screenshot: {screenshot}")
finally:
    for command in [
        ("wm", "size", "reset"),
        ("wm", "density", "reset"),
        ("cmd", "uimode", "night", "no"),
        ("settings", "put", "system", "font_scale", "1.0"),
    ]:
        try:
            adb("shell", *command, timeout=15)
        except (OSError, subprocess.SubprocessError) as error:
            print(f"Could not restore emulator setting ({' '.join(command)}): {error}")
