"""Regression coverage for the SDK 37 signer-output format seen in Android CI."""
from pathlib import Path
import runpy
import sys
import unittest
from unittest.mock import patch

verifier = runpy.run_path(str(Path(__file__).with_name("verify-signed-apks.py")))
identity = verifier["signing_identity"]
artifact_modules = verifier["artifact_modules"]
CERT = "d129837712e0ec90bf689bf027917b14ef9c2eac5f0051b83fb6eeeb2f81d373"
KEY = "613abcbb49e11380373422294c48cb3febbc4a1a37c09acbf61caa4af4029e24"


def report(prefix):
    return (f"{prefix} certificate SHA-256 digest: {CERT}\n"
            f"{prefix} public key SHA-256 digest: {KEY}\n")


class SignerOutputTest(unittest.TestCase):
    def test_core_only_selects_core(self):
        self.assertEqual(["app"], [module for module, _ in artifact_modules(core_only=True)])

    def test_default_checks_all_six_apks(self):
        self.assertEqual(["app", "reference-sort-alphabetical", "addon-most-used"],
                         [module for module, _ in artifact_modules()])

    def test_core_only_cli_verifies_two_core_variants(self):
        checked = []

        def fake_check_output(command, text):
            apk = Path(command[-1])
            checked.append(apk)
            self.assertEqual("app", apk.parts[0])
            variant = apk.parts[-2]
            if "apksigner" in str(command[0]):
                cert = "a" * 64 if variant == "debug" else "b" * 64
                return ("Number of signers: 1\n"
                        f"Signer #1 certificate SHA-256 digest: {cert}\n"
                        f"Signer #1 public key SHA-256 digest: {'c' * 64}\n")
            package = "io.github.alexanderwilhelmsenberg.modular"
            if variant == "debug":
                package += ".debug"
            return f"package: name='{package}'\n" + ("application-debuggable\n" if variant == "debug" else "")

        with patch.object(sys, "argv", ["verify-signed-apks.py", "--core-only"]), \
             patch("subprocess.check_output", side_effect=fake_check_output), \
             patch.dict(verifier["main"].__globals__, {"android_tool": lambda name: Path(name)}):
            verifier["main"]()
        self.assertEqual(["debug", "debug", "release", "release"],
                         [path.parts[-2] for path in checked])

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
