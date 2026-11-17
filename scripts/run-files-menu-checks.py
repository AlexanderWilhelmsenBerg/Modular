#!/usr/bin/env python3
"""Exercise Files menu hit testing at four real emulator widths and retain screenshots."""
from pathlib import Path
import re
import subprocess

PACKAGE = "io.github.alexanderwilhelmsenberg.modular.addon.files.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "io.github.alexanderwilhelmsenberg.modular.addon.files.FilesMenuTouchTest"
MATRIX = ((320, "LIGHT", "1.0"), (375, "AMOLED", "1.5"), (414, "DARK", "1.0"), (768, "LIGHT", "1.0"))
REPORTS = Path("build/reports/device")
IMAGES = Path("build/ui-screenshots/files-menus")


def adb(*args: str, timeout: int = 30) -> str:
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stdout}\n{result.stderr}")
    return result.stdout


def main() -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    IMAGES.mkdir(parents=True, exist_ok=True)
    try:
        adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
        adb("shell", "wm", "density", "160")
        for width, theme, font in MATRIX:
            adb("shell", "wm", "size", f"{width}x900")
            adb("shell", "settings", "put", "system", "font_scale", font)
            adb("shell", "cmd", "uimode", "night", "no" if theme == "LIGHT" else "yes")
            output = adb("shell", "am", "instrument", "-w", "-e", "class", TEST,
                "-e", "files_capture", "true", "-e", "files_width", str(width),
                "-e", "files_theme", theme, RUNNER, timeout=180)
            (REPORTS / f"files-menu-{width}dp-{theme.lower()}.log").write_text(output)
            if not re.search(r"^OK \(2 tests\)\s*$", output, re.MULTILINE):
                raise RuntimeError(f"Files physical menu taps failed at {width}dp/{theme}:\n{output}")
            print(f"Files menu physical taps passed at {width}dp/{theme}/font {font}", flush=True)
        adb("pull", "/sdcard/Pictures/ModularFiles/.", str(IMAGES), timeout=60)
        for width, theme, _ in MATRIX:
            for scene in ("toolbar", "folder"):
                capture = IMAGES / f"files-menu-{scene}-{width}dp-{theme.lower()}.png"
                if not capture.is_file() or capture.stat().st_size == 0:
                    raise RuntimeError(f"Missing menu capture: {capture}")
    finally:
        for command in (("wm", "size", "reset"), ("wm", "density", "reset"),
                ("settings", "put", "system", "font_scale", "1.0"),
                ("cmd", "uimode", "night", "no"),
                ("appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "default")):
            adb("shell", *command)


if __name__ == "__main__":
    main()
