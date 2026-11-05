#!/usr/bin/env python3
"""Check shared-key signing without exporting any private key material."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-certificates", type=Path)
    parser.add_argument("--write-public", type=Path)
    args = parser.parse_args()
    store = os.environ["MODULAR_KEYSTORE_PATH"]
    certificates = {}
    for variant in ("debug", "release"):
        alias = "modular-" + variant
        cert = subprocess.check_output([
            "keytool", "-exportcert", "-alias", alias, "-keystore", store,
            "-storepass:env", "MODULAR_KEYSTORE_PASSWORD",
        ], stderr=subprocess.PIPE)
        pem = subprocess.run(["openssl", "x509", "-inform", "DER", "-pubkey", "-noout"],
                             input=cert, check=True, capture_output=True).stdout
        public_key = subprocess.run(["openssl", "pkey", "-pubin", "-outform", "DER"],
                                    input=pem, check=True, capture_output=True).stdout
        certificates[variant] = {
            "alias": alias,
            "sha256": hashlib.sha256(cert).hexdigest(),
            "publicKeySha256": hashlib.sha256(public_key).hexdigest(),
        }
    if certificates["debug"]["sha256"] == certificates["release"]["sha256"]:
        raise SystemExit("Debug and release certificates must differ")
    if certificates["debug"]["publicKeySha256"] != certificates["release"]["publicKeySha256"]:
        raise SystemExit("Debug and release certificates must use the same key")
    if args.expected_certificates and certificates != json.loads(args.expected_certificates.read_text()):
        raise SystemExit("Keystore does not match the expected public signing identities")
    if args.write_public:
        args.write_public.write_text(json.dumps(certificates, indent=2) + "\n")
    print("Verified one shared signing key with two distinct certificates.")


if __name__ == "__main__":
    main()
