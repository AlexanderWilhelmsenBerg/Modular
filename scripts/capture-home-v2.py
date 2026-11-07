#!/usr/bin/env python3
"""Run Home v2 visual fixtures at reference viewport/font settings and collect PNGs."""
from pathlib import Path
import subprocess
import time

out = Path("build/ui-screenshots/home-v2")
out.mkdir(parents=True, exist_ok=True)
package = "io.github.alexanderwilhelmsenberg.modular.debug"
runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"
test_class = "io.github.alexanderwilhelmsenberg.modular.ui.HomeV2ScreenshotTest"


def adb(*args, timeout=120):
    return subprocess.check_output(["adb", *args], timeout=timeout, text=True)


try:
    adb("shell", "wm", "density", "160")
    for width, night, font in [(320, "no", "1.0"), (375, "no", "1.0"), (411, "no", "1.0"), (414, "yes", "1.0"), (768, "yes", "1.0"), (375, "no", "1.5")]:
        adb("shell", "wm", "size", f"{width}x900")
        adb("shell", "cmd", "uimode", "night", night)
        adb("shell", "settings", "put", "system", "font_scale", font)
        result = adb("shell", "am", "instrument", "-w", "-e", "class", test_class, runner, timeout=300)
        if "OK (" not in result:
            raise RuntimeError(f"Home v2 screenshots failed at {width}dp, font {font}:\n{result}")
        print(f"Captured Home v2 at {width}dp, font {font}")
    # Keep a short rail-press clip so reviewers can see the curve settle, not just its still frame.
    try:
        adb("shell", "wm", "size", "411x900")
        adb("shell", "cmd", "uimode", "night", "no")
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
        clip = "/sdcard/modular-home-v2-rail.mp4"
        recorder = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "8", clip], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        time.sleep(0.5)
        result = adb("shell", "am", "instrument", "-w", "-e", "class", test_class + "#pressedRailShowsSelectedBubbleAndKeepsAppUnlaunched", runner, timeout=120)
        recorder.wait(timeout=12)
        if "OK (" in result and recorder.returncode == 0:
            subprocess.run(["adb", "pull", clip, str(out / "home-rail-411dp.mp4")], check=True, timeout=30)
        else:
            print("Rail video was unavailable; pressed-state PNG remains captured")
    except (OSError, subprocess.SubprocessError) as error:
        print(f"Rail video was unavailable: {error}")
    subprocess.run(["adb", "pull", "/sdcard/Pictures/ModularHomeV2/.", str(out)], check=True, timeout=60)
finally:
    adb("shell", "wm", "size", "reset")
    adb("shell", "wm", "density", "reset")
    adb("shell", "cmd", "uimode", "night", "no")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
