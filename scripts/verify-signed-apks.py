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
            output = subprocess.check_output([android_tool("apksigner"), "verify", "--verbose", "--print-certs", apk], text=True)
            # A signer can be reported for multiple signing schemes / SDK ranges.
            # Require one identity, rather than assuming exactly one matching line.
            matches = re.findall(r"^Signer #(\d+).*?certificate SHA-256 digest:\s*([a-fA-F0-9:]+)\s*$", output, re.MULTILINE)
            identities = {(number, digest.replace(":", "").lower()) for number, digest in matches}
            if len(identities) != 1 or next(iter(identities))[0] != "1":
                # apksigner prints certificates and public fingerprints, never private keys.
                print(output)
                raise SystemExit(f"Expected one distinct APK signing identity: {apk}")
            digest = next(iter(identities))[1]
            if len(digest) != 64:
                raise SystemExit(f"Invalid SHA-256 signer fingerprint: {apk}")
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
