"""Regression coverage for the SDK 37 signer-output format seen in Android CI."""
from pathlib import Path
import runpy
import unittest

identity = runpy.run_path(str(Path(__file__).with_name("verify-signed-apks.py")))["signing_identity"]
CERT = "d129837712e0ec90bf689bf027917b14ef9c2eac5f0051b83fb6eeeb2f81d373"
KEY = "613abcbb49e11380373422294c48cb3febbc4a1a37c09acbf61caa4af4029e24"


def report(prefix):
    return (f"{prefix} certificate SHA-256 digest: {CERT}\n"
            f"{prefix} public key SHA-256 digest: {KEY}\n")


class SignerOutputTest(unittest.TestCase):
    def test_sdk37_scheme_label(self):
        self.assertEqual((CERT, KEY), identity("Number of signers: 1\n" + report("V2 Signer:")))

    def test_legacy_numbered_label(self):
        self.assertEqual((CERT, KEY), identity("Number of signers: 1\n" + report("Signer #1")))

    def test_same_identity_repeated_across_schemes(self):
        self.assertEqual((CERT, KEY), identity("Number of signers: 1\n" + report("V2 Signer:") + report("V3 Signer:")))

    def test_multiple_signers_are_rejected(self):
        with self.assertRaises(ValueError):
            identity("Number of signers: 2\n" + report("Signer #1"))

    def test_conflicting_certificates_are_rejected(self):
        with self.assertRaises(ValueError):
            identity("Number of signers: 1\n" + report("V2 Signer:") + report("V3 Signer:").replace(CERT, "a" * 64))

    def test_missing_information_is_rejected(self):
        with self.assertRaises(ValueError):
            identity("")


if __name__ == "__main__":
    unittest.main()
