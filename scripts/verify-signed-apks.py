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


def signing_identity(output):
    # SDK 37 prints "V2 Signer:" while older tools print "Signer #1".
    # Read the stable digest fields and validate the verifier's explicit count.
    counts = set(re.findall(r"^Number of signers:\s*(\d+)\s*$", output, re.MULTILINE))
    certificates = set(re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\b", output))
    public_keys = set(re.findall(r"public key SHA-256 digest:\s*([a-fA-F0-9]{64})\b", output))
    if counts != {"1"} or len(certificates) != 1 or len(public_keys) != 1:
        raise ValueError("Expected one APK signer, certificate, and public key")
    return next(iter(certificates)).lower(), next(iter(public_keys)).lower()


def artifact_modules(core_only=False, include_files=False):
    modules = [("app", "io.github.alexanderwilhelmsenberg.modular")]
    if include_files:
        modules.append(("addon-files", "io.github.alexanderwilhelmsenberg.modular.addon.files"))
    if not core_only:
        modules.extend((
            ("reference-sort-alphabetical", "io.github.alexanderwilhelmsenberg.modular.reference.sort.alphabetical"),
            ("addon-most-used", "io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused"),
        ))
    return modules


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-certificates", type=Path)
    parser.add_argument("--core-only", action="store_true", help="Verify only Core debug and release APKs")
    parser.add_argument("--include-files", action="store_true", help="Also verify Modular Files debug and release APKs")
    args = parser.parse_args()
    expected = json.loads(args.expected_certificates.read_text()) if args.expected_certificates else None
    signers = {}
    for module, base_id in artifact_modules(args.core_only, args.include_files):
        for variant in ("debug", "release"):
            apk = Path(module) / f"build/outputs/apk/{variant}/{module}-{variant}.apk"
            output = subprocess.check_output([android_tool("apksigner"), "verify", "--verbose", "--print-certs", apk], text=True)
            try:
                digest, public_key = signing_identity(output)
            except ValueError as error:
                print(output)  # Public certificate information only.
                raise SystemExit(f"{error}: {apk}") from error
            if expected and public_key != expected[variant]["publicKeySha256"]:
                raise SystemExit(f"Unexpected {variant} public signing key: {apk}")
            if "shared_key" in signers and signers["shared_key"] != public_key:
                raise SystemExit("All variants must use the requested shared private key")
            signers["shared_key"] = public_key
            if expected and digest != expected[variant]["sha256"]:
                raise SystemExit(f"Unexpected {variant} signing certificate: {apk}")
            if variant in signers and signers[variant] != digest:
                raise SystemExit(f"Core and official add-ons have different {variant} signers")
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
