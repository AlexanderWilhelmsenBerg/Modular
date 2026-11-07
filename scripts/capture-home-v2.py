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
    viewports = [(320, "no", "1.0"), (375, "no", "1.0"), (411, "no", "1.0"), (414, "yes", "1.0"), (768, "yes", "1.0"), (375, "no", "1.5")]
    for width, night, font in viewports:
        adb("shell", "wm", "size", f"{width}x900")
        adb("shell", "cmd", "uimode", "night", night)
        adb("shell", "settings", "put", "system", "font_scale", font)
        result = adb("shell", "am", "instrument", "-w", "-e", "class", test_class, runner, timeout=300)
        if "OK (" not in result:
            raise RuntimeError(f"Home v2 screenshots failed at {width}dp, font {font}:\n{result}")
        print(f"Captured Home v2 at {width}dp, font {font}")
    # Required evidence must be on the runner before the optional video starts.
    # Some emulator codecs disconnect ADB while recording; do not lose the PNGs.
    subprocess.run(["adb", "pull", "/sdcard/Pictures/ModularHomeV2/.", str(out)], check=True, timeout=60)
    for width, night, font in viewports:
        mode = "dark" if night == "yes" else "light"
        actions = out / f"home-actions-{width}dp-{mode}-font{font}.png"
        if not actions.is_file():
            raise RuntimeError(f"Missing full app-actions screenshot: {actions}")
    # Keep a short rail-press clip so reviewers can see the curve settle, not just its still frame.
    try:
        adb("shell", "wm", "size", "411x900")
        adb("shell", "cmd", "uimode", "night", "no")
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
        clip = "/sdcard/modular-home-v2-rail.mp4"
        # H.264 encoders require even dimensions; the reference viewport is 411px wide.
        recorder = subprocess.Popen(["adb", "shell", "screenrecord", "--size", "412x900", "--time-limit", "8", clip], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        time.sleep(0.5)
        result = adb("shell", "am", "instrument", "-w", "-e", "class", test_class + "#pressedRailShowsSelectedBubbleAndKeepsAppUnlaunched", runner, timeout=120)
        recorder.wait(timeout=12)
        if "OK (" in result and recorder.returncode == 0:
            subprocess.run(["adb", "pull", clip, str(out / "home-rail-411dp.mp4")], check=True, timeout=30)
        else:
            print("Rail video was unavailable; pressed-state PNG remains captured")
    except (OSError, subprocess.SubprocessError) as error:
        print(f"Rail video was unavailable: {error}")
finally:
    # Cleanup is best effort for a disconnected disposable emulator. Required
    # fixture/assertion/pull failures above still propagate and fail the job.
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
