#!/usr/bin/env python3
"""Run the Files device tests with storage access fixed before each instrumentation process."""

from pathlib import Path
import re
import subprocess


PACKAGE = "io.github.alexanderwilhelmsenberg.modular.addon.files.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
BASE_CLASS = "io.github.alexanderwilhelmsenberg.modular.addon.files."
REPORT_DIR = Path("build/reports/device")
BROAD_TESTS = (
    "DirectStorageSafetyTest",
    "MarkdownRealDocumentTest",
    "PdfRealDocumentTest",
    "FilesNativeDocumentUiTest",
    "FilesReviewPaginationTest",
    "FilesActivityInsetsTest",
    "FilesScrollLifecycleTest",
    "FilesPageVisualFixtureTest",
    "FilesHandoffNonceTest",
    "ArchiveRealStorageTest",
)
EXPECTED_COUNTS = {"FilesReviewPaginationTest": 15, "DirectStorageSafetyTest": 2, "FilesPageVisualFixtureTest": 2}


def adb(*args: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stdout}\n{result.stderr}")
    return result


def run_test(name: str) -> None:
    report = REPORT_DIR / f"addon-files-{name}.log"
    try:
        result = subprocess.run(
            ["adb", "shell", "am", "instrument", "-w", "-e", "class", BASE_CLASS + name, RUNNER],
            capture_output=True, text=True, timeout=300,
        )
    except subprocess.TimeoutExpired as error:
        partial = (error.stdout or b"").decode(errors="replace") if isinstance(error.stdout, bytes) else (error.stdout or "")
        report.write_text(partial + "\nINSTRUMENTATION TIMED OUT\n")
        raise RuntimeError(f"Files {name} instrumentation timed out; see {report}") from error
    output = result.stdout + "\n" + result.stderr
    report.write_text(output)
    print(f"Files {name} instrumentation result: {report}", flush=True)
    expected_count = EXPECTED_COUNTS.get(name, 1)
    success = re.search(rf"^OK \({expected_count} tests?\)\s*$", result.stdout, re.MULTILINE)
    if result.returncode or not success or "INSTRUMENTATION_STATUS_CODE: -2" in output:
        print(output, flush=True)
        raise RuntimeError(f"Files {name} instrumentation did not pass")


def main() -> None:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    failures: list[str] = []
    # Changing this app-op can kill the target process. Do it only between runs,
    # after the APKs are installed and before the next instrumentation starts.
    try:
        try:
            adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
            for name in BROAD_TESTS:
                try:
                    run_test(name)
                except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                    failures.append(f"{name}: {error}")
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            failures.append(f"Broad-access setup: {error}")

        try:
            adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "ignore")
            run_test("SelectedFolderSafTest")
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            failures.append(f"SelectedFolderSafTest: {error}")
    finally:
        try:
            adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "default")
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            failures.append(f"App-op restore: {error}")
    if failures:
        report = REPORT_DIR / "addon-files-summary.log"
        report.write_text("\n".join(failures) + "\n")
        raise RuntimeError(f"Files device tests failed; see {report}:\n" + "\n".join(failures))
    print("All eleven Files device test classes passed", flush=True)


if __name__ == "__main__":
    main()
