#!/usr/bin/env python3
"""Keep add-on UI out of the launcher and its public framework libraries."""
import argparse
from pathlib import Path
from zipfile import ZipFile


def check(root: Path, apk: Path | None = None) -> list[str]:
    failures = []
    private_names = ("io.github.alexanderwilhelmsenberg.modular.filesui",
                     "io.github.alexanderwilhelmsenberg.modular.addon.files")
    for module in ("app", "module-api", "module-sdk"):
        source = root / module / "src/main"
        for file in source.rglob("*"):
            if file.suffix not in (".kt", ".java", ".aidl"):
                continue
            if any(name in file.read_text() for name in private_names):
                failures.append(f"Feature-specific code reference in {file.relative_to(root)}")
        gradle = (root / module / "build.gradle.kts").read_text()
        for private_module in ("module-files-ui", "addon-files"):
            if f'project(":{private_module}")' in gradle:
                failures.append(f"{module} depends on private add-on module {private_module}")
    host = (root / "app/src/main/java/io/github/alexanderwilhelmsenberg/modular/ui/ModuleWorkspacePage.kt").read_text()
    if any(name in host for name in ("WorkspacePageContract", "validateFilesPage", "FilesPage(")):
        failures.append("Core workspace host interprets feature-specific page data")
    if apk is not None:
        if not apk.is_file():
            failures.append(f"Missing Core APK: {apk}")
        else:
            prefixes = [b"L" + name.replace(".", "/").encode() + b"/" for name in private_names]
            with ZipFile(apk) as archive:
                for entry in archive.namelist():
                    if entry.startswith("classes") and entry.endswith(".dex"):
                        data = archive.read(entry)
                        if any(prefix in data for prefix in prefixes):
                            failures.append(f"Core APK contains private add-on classes in {entry}")
    return failures


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core-apk", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    failures = check(root, args.core_apk)
    if failures:
        raise SystemExit("\n".join(failures))
    print("Core and public SDK contain no add-on-specific UI dependencies")


if __name__ == "__main__":
    main()
