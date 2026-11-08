#!/usr/bin/env python3
"""Capture every native settings page across the viewport, font, and theme matrix."""
from pathlib import Path
import subprocess


OUT = Path("build/ui-screenshots/settings")
OUT.mkdir(parents=True, exist_ok=True)
PACKAGE = "io.github.alexanderwilhelmsenberg.modular.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "io.github.alexanderwilhelmsenberg.modular.ui.SettingsScreenshotTest"
REMOTE = "/sdcard/Pictures/ModularSettings"
VIEWPORTS = [(320, "1.0"), (375, "1.0"), (414, "1.0"), (768, "1.0"), (375, "1.5")]
THEMES = ("light", "dark", "amoled")
SCENES = ("root", "productivity", "appearance", "addons", "advanced")


def adb(*args, timeout=120):
    return subprocess.check_output(["adb", *args], timeout=timeout, text=True)


try:
    # Clear old captures so a missed test cannot pass the presence check.
    adb("shell", "rm", "-rf", REMOTE)
    adb("shell", "wm", "density", "160")
    for width, font in VIEWPORTS:
        adb("shell", "wm", "size", f"{width}x900")
        adb("shell", "settings", "put", "system", "font_scale", font)
        for theme in THEMES:
            adb("shell", "cmd", "uimode", "night", "no" if theme == "light" else "yes")
            result = adb(
                "shell", "am", "instrument", "-w", "-e", "class", TEST_CLASS,
                "-e", "settingsTheme", theme, RUNNER, timeout=300,
            )
            if "OK (5 tests)" not in result:
                raise RuntimeError(
                    f"Settings screenshots failed at {width}dp, font {font}, {theme}:\n{result}"
                )
            print(f"Captured settings at {width}dp, font {font}, {theme}")
    subprocess.run(["adb", "pull", REMOTE + "/.", str(OUT)], check=True, timeout=90)
    for width, font in VIEWPORTS:
        for theme in THEMES:
            for scene in SCENES:
                screenshot = OUT / f"settings-{scene}-{width}dp-{theme}-font{font}.png"
                if not screenshot.is_file() or screenshot.stat().st_size == 0:
                    raise RuntimeError(f"Missing settings screenshot: {screenshot}")
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
