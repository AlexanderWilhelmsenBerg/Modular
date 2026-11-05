#!/usr/bin/env python3
"""Verify installed IDs and signers before distributing APKs (no private key access)."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def android_tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    candidates = list((sdk / "build-tools").glob("*/" + name))
    if not candidates:
        raise SystemExit(f"Android SDK tool missing: {name}")
    return max(candidates, key=lambda path: [int(n) for n in re.findall(r"\d+", path.parent.name)])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-certificates", type=Path)
    args = parser.parse_args()
    expected = json.loads(args.expected_certificates.read_text()) if args.expected_certificates else None
    signers = {}
    for module, base_id in (
        ("app", "io.github.alexanderwilhelmsenberg.modular"),
        ("reference-sort-alphabetical", "io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical"),
    ):
        for variant in ("debug", "release"):
            apk = Path(module) / f"build/outputs/apk/{variant}/{module}-{variant}.apk"
            output = subprocess.check_output([android_tool("apksigner"), "verify", "--print-certs", apk], text=True)
            digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", output)
            if len(digests) != 1:
                raise SystemExit(f"Expected exactly one APK signer: {apk}")
            digest = digests[0].lower()
            if expected and digest != expected[variant]["sha256"]:
                raise SystemExit(f"Unexpected {variant} signing certificate: {apk}")
            if variant in signers and signers[variant] != digest:
                raise SystemExit(f"Core and reference module have different {variant} signers")
            signers[variant] = digest
            badging = subprocess.check_output([android_tool("aapt2"), "dump", "badging", apk], text=True)
            identity = base_id + (".debug" if variant == "debug" else "")
            match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
            if match is None or match.group(1) != identity:
                raise SystemExit(f"Wrong application ID: {apk}")
            debuggable = "application-debuggable" in badging
            if debuggable != (variant == "debug"):
                raise SystemExit(f"Wrong debuggable flag: {apk}")
            print(f"Verified {identity}: {variant} signer {digest}")
    if signers["debug"] == signers["release"]:
        raise SystemExit("Debug and release must have different signing certificates")


if __name__ == "__main__":
    main()
