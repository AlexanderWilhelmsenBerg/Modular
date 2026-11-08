#!/usr/bin/env python3
"""Capture real Home, All apps, and a bound Android widget across display modes."""
from pathlib import Path
import subprocess


OUT = Path("build/ui-screenshots/workspace")
OUT.mkdir(parents=True, exist_ok=True)
RUNNER = "io.github.alexanderwilhelmsenberg.modular.debug.test/androidx.test.runner.AndroidJUnitRunner"
TEST = "io.github.alexanderwilhelmsenberg.modular.ui.WorkspaceScreenshotTest"
PACKAGE = "io.github.alexanderwilhelmsenberg.modular.debug"
MATRIX = [
    (320, "LIGHT", "1.0", False),
    (375, "LIGHT", "1.0", False),
    (414, "DARK", "1.0", False),
    (768, "DARK", "1.0", False),
    (375, "LIGHT", "1.5", False),
    (375, "AMOLED", "1.0", False),
    (414, "LIGHT", "1.0", True),
]


def adb(*args, timeout=120):
    return subprocess.check_output(["adb", *args], timeout=timeout, text=True)


try:
    adb("shell", "wm", "density", "160")
    for width, theme, font, rtl in MATRIX:
        adb("shell", "wm", "size", f"{width}x900")
        adb("shell", "cmd", "uimode", "night", "yes" if theme != "LIGHT" else "no")
        adb("shell", "settings", "put", "system", "font_scale", font)
        # App-locales dispatches a real configuration change; merely writing
        # debug.force_rtl in Settings does not update the system property/layout.
        if rtl:
            adb("shell", "cmd", "locale", "set-app-locales", PACKAGE, "--locales", "ar")
        else:
            adb("shell", "cmd", "locale", "set-app-locales", PACKAGE)
        result = adb("shell", "am", "instrument", "-w", "-e", "class", TEST,
                     "-e", "workspace_theme", theme, RUNNER, timeout=300)
        if "OK (2 tests)" not in result:
            raise RuntimeError(f"Workspace fixture failed at {width}dp/{theme}/{font}/rtl={rtl}:\n{result}")
        subprocess.run(["adb", "pull", "/sdcard/Pictures/ModularWorkspace/.", str(OUT)],
                       check=True, timeout=60)
        direction = "rtl" if rtl else "ltr"
        for scene in ("home", "all-apps", "widget"):
            file = OUT / f"workspace-{scene}-{width}dp-{theme.lower()}-font{font}-{direction}.png"
            if not file.is_file() or file.stat().st_size == 0:
                raise RuntimeError(f"Missing workspace screenshot: {file}")
        print(f"Captured workspace at {width}dp/{theme}/{font}/rtl={rtl}", flush=True)
finally:
    for command in (
        ("wm", "size", "reset"),
        ("wm", "density", "reset"),
        ("cmd", "uimode", "night", "no"),
        ("settings", "put", "system", "font_scale", "1.0"),
        ("cmd", "locale", "set-app-locales", PACKAGE),
    ):
        try:
            adb("shell", *command, timeout=15)
        except (OSError, subprocess.SubprocessError) as error:
            print(f"Could not restore emulator setting ({' '.join(command)}): {error}")
