#!/usr/bin/env python3
"""Capture the installed Files Home page on API 35 after the cross-APK test passes."""
from pathlib import Path
import subprocess


OUT = Path("build/ui-screenshots/files")
RUNNER = "io.github.alexanderwilhelmsenberg.modular.debug.test/androidx.test.runner.AndroidJUnitRunner"
TEST = "io.github.alexanderwilhelmsenberg.modular.capabilities.FilesCrossApkHomeTest"
VISUAL_TEST = "io.github.alexanderwilhelmsenberg.modular.ui.FilesPageVisualFixtureTest"
MATRIX = [
    (320, "LIGHT", "1.0"),
    (414, "LIGHT", "1.0"),
    (414, "DARK", "1.0"),
    (375, "AMOLED", "1.5"),
    (768, "DARK", "1.0"),
]


def adb(*args, timeout=120):
    return subprocess.check_output(["adb", *args], timeout=timeout, text=True)


try:
    OUT.mkdir(parents=True, exist_ok=True)
    adb("shell", "wm", "density", "160")
    for width, theme, font in MATRIX:
        adb("shell", "wm", "size", f"{width}x900")
        adb("shell", "cmd", "uimode", "night", "yes" if theme != "LIGHT" else "no")
        adb("shell", "settings", "put", "system", "font_scale", font)
        result = adb("shell", "am", "instrument", "-w", "-e", "class", TEST,
                     "-e", "files_capture", "true", "-e", "files_theme", theme,
                     RUNNER, timeout=300)
        if "OK (1 test)" not in result:
            try:
                adb("pull", "/sdcard/Pictures/ModularFiles/.", str(OUT), timeout=60)
            except (OSError, subprocess.SubprocessError) as error:
                print(f"Could not preserve failed Files capture: {error}", flush=True)
            raise RuntimeError(f"Files capture test failed for {width}dp/{theme}/font {font}:\n{result}")
        visual = adb("shell", "am", "instrument", "-w", "-e", "class", VISUAL_TEST,
                     "-e", "files_theme", theme, RUNNER, timeout=300)
        if "OK (2 tests)" not in visual:
            raise RuntimeError(f"Files visual fixture failed for {width}dp/{theme}/font {font}:\n{visual}")
        adb("pull", "/sdcard/Pictures/ModularFiles/.", str(OUT), timeout=60)
        expected = [f"files-overview-{width}dp-{theme.lower()}-font{font}.png"]
        expected += [f"files-fixture-{scene}-{width}dp-{theme.lower()}-font{font}.png"
                     for scene in ("browser", "browser-details", "review", "trash", "map", "settings",
                                   "browser-menu", "folder-menu", "folder-appearance", "clean-progress", "clean-sheet")]
        for filename in expected:
            screenshot = OUT / filename
            if not screenshot.is_file() or screenshot.stat().st_size == 0:
                raise RuntimeError(f"Missing Files screenshot: {screenshot}")
            print(f"Captured {screenshot}", flush=True)
finally:
    for command in (
        ("wm", "size", "reset"),
        ("wm", "density", "reset"),
        ("cmd", "uimode", "night", "no"),
        ("settings", "put", "system", "font_scale", "1.0"),
    ):
        try:
            adb("shell", *command, timeout=15)
        except (OSError, subprocess.SubprocessError) as error:
            print(f"Could not restore emulator setting ({' '.join(command)}): {error}")
