#!/usr/bin/env python3
"""Capture and inspect real add-on/Core settings on the CI emulator."""
from pathlib import Path
import subprocess
import re
import time
import xml.etree.ElementTree as ET

out = Path("build/ui-screenshots")
out.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=20)


def capture(name, start_args, expected):
    adb("shell", "am", "start", "-W", *start_args)
    deadline = time.monotonic() + 30
    last_text = ""
    while time.monotonic() < deadline:
        adb("shell", "uiautomator", "dump", "/sdcard/modular-settings.xml")
        xml = adb("shell", "cat", "/sdcard/modular-settings.xml")
        root = ET.fromstring(xml)
        last_text = " ".join(
            node.get("text", "") + " " + node.get("content-desc", "")
            for node in root.iter("node")
        )
        if name.startswith("most-used"):
            later = next((node for node in root.iter("node") if node.get("text") == "Not now"
                          and node.get("resource-id") == "android:id/button2"), None)
            if later is not None:
                # Review the genuine first-open prompt before choosing its explicit
                # decline action, then capture the ordinary settings underneath.
                (out / (name + "-setup.xml")).write_bytes(xml)
                (out / (name + "-setup.png")).write_bytes(adb("exec-out", "screencap", "-p"))
                bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", later.get("bounds", ""))
                if bounds is None:
                    raise RuntimeError("Missing first-open prompt button bounds")
                left, top, right, bottom = map(int, bounds.groups())
                adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                continue
        if all(value.casefold() in last_text.casefold() for value in expected):
            (out / (name + ".xml")).write_bytes(xml)
            (out / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
            print("Visible settings verified:", name)
            return
        time.sleep(0.5)
    (out / (name + "-failure.xml")).write_bytes(xml)
    (out / (name + "-failure.png")).write_bytes(adb("exec-out", "screencap", "-p"))
    raise RuntimeError(f"Missing settings controls in {name}: {expected}; visible: {last_text}")


try:
    # dp widths at mdpi: narrow phone, common phones, and tablet; native light/dark.
    adb("shell", "wm", "density", "160")
    for width, mode, font_scale in [(320, "no", "1.0"), (375, "no", "1.0"), (414, "yes", "1.0"), (768, "yes", "1.0"), (375, "no", "1.3")]:
        adb("shell", "wm", "size", f"{width}x900")
        adb("shell", "cmd", "uimode", "night", mode)
        adb("shell", "settings", "put", "system", "font_scale", font_scale)
        suffix = f"-{width}dp-{'dark' if mode == 'yes' else 'light'}-font{font_scale}"
        capture("most-used" + suffix, ["-n", "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.debug/io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.UsageAccessActivity"], ["Open Usage Access settings", "Configure in Modular"])
        capture("alphabetical" + suffix, ["-n", "io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical.debug/io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical.SortSettingsActivity"], ["Configure in Modular"])
        capture("core-most-used" + suffix, ["-a", "io.github.alexanderwilhelmsenberg.modular.action.OPEN_MODULE_SETTINGS", "-p", "io.github.alexanderwilhelmsenberg.modular.debug", "--es", "moduleComponent", "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.debug/io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused.MostUsedSortService"], ["Recent days", "Use on Home"])
finally:
    adb("shell", "wm", "size", "reset")
    adb("shell", "wm", "density", "reset")
    adb("shell", "cmd", "uimode", "night", "no")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    adb("shell", "am", "force-stop", "io.github.alexanderwilhelmsenberg.modular.debug")
