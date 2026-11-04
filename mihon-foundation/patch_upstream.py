#!/usr/bin/env python3
"""Narrow, pinned changes to the genuine Mihon source tree for a safe side-by-side test.

This code does not emulate Mihon's engine. GitHub Actions checks out the original
Mihon repository at the exact commit declared in UPSTREAM_SHA, then applies this
small branding / application-ID overlay before building it.
"""
from pathlib import Path
import argparse
import re
import subprocess

UPSTREAM_SHA = "8794e89a866a3de578c02cefac1e609bcff5f092"
NEW_APP_ID = "com.aistudio.mangareader.mihoncore"
NEW_APP_NAME = "Manga D UI"


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    if text.count(old) != 1:
        raise RuntimeError(f"Unsafe upstream layout in {path}: expected one match for {old!r}, got {text.count(old)}")
    return text.replace(old, new, 1)


def patch(root: Path, check_commit: bool = True) -> None:
    if not root.is_dir():
        raise RuntimeError(f"Missing Mihon checkout: {root}")
    if check_commit:
        required = [
            "app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt",
            "source-api/src/main/kotlin/eu/kanade/tachiyomi/source/SourceFactory.kt",
            "core/common/src/main/kotlin/eu/kanade/tachiyomi/network/OkHttpExtensions.kt",
        ]
        for rel in required:
            if not (root / rel).is_file():
                raise RuntimeError(f"Incomplete Mihon source checkout: missing {rel}")
        commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
        if commit != UPSTREAM_SHA:
            raise RuntimeError(f"Wrong Mihon commit: {commit}; expected {UPSTREAM_SHA}")

    gradle = root / "app/build.gradle.kts"
    strings = root / "i18n/src/commonMain/moko-resources/base/strings.xml"
    before_gradle = gradle.read_text(encoding="utf-8")
    before_strings = strings.read_text(encoding="utf-8")
    # Changing the APK package ensures old Manga D and official Mihon data stay untouched.
    old_id = 'applicationId = "app.mihon"'
    new_id = f'applicationId = "{NEW_APP_ID}"'
    if new_id not in before_gradle:
        after_gradle = replace_once(before_gradle, old_id, new_id, gradle)
    else:
        if before_gradle.count(new_id) != 1:
            raise RuntimeError("More than one Manga D app ID found")
        after_gradle = before_gradle

    old_label = '<string name="app_name" translatable="false">Mihon</string>'
    new_label = f'<string name="app_name" translatable="false">{NEW_APP_NAME}</string>'
    if new_label not in before_strings:
        after_strings = replace_once(before_strings, old_label, new_label, strings)
    else:
        if before_strings.count(new_label) != 1:
            raise RuntimeError("More than one Manga D label found")
        after_strings = before_strings

    if after_gradle != before_gradle:
        gradle.write_text(after_gradle, encoding="utf-8")
    if after_strings != before_strings:
        strings.write_text(after_strings, encoding="utf-8")
    if new_id not in gradle.read_text(encoding="utf-8"):
        raise AssertionError("Application ID modification failed")
    if new_label not in strings.read_text(encoding="utf-8"):
        raise AssertionError("App label modification failed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mihon_checkout", type=Path)
    parser.add_argument("--allow-fixture", action="store_true", help="Unit tests only: skip checkout SHA verification")
    args = parser.parse_args()
    patch(args.mihon_checkout, check_commit=not args.allow_fixture)
    print(f"Mihon foundation patched: app_id={NEW_APP_ID}, name={NEW_APP_NAME}")


if __name__ == "__main__":
    main()
