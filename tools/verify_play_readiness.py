#!/usr/bin/env python3

from __future__ import annotations

import re
import struct
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
failures: list[str] = []
external_actions: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)


def read(relative: str) -> str:
    path = ROOT / relative
    check(path.is_file(), f"Missing required file: {relative}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def png_info(relative: str) -> tuple[int, int, int] | None:
    path = ROOT / relative
    if not path.is_file():
        failures.append(f"Missing required image: {relative}")
        return None
    data = path.read_bytes()[:29]
    if len(data) < 29 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        failures.append(f"Not a valid PNG file: {relative}")
        return None
    width, height = struct.unpack(">II", data[16:24])
    return width, height, data[25]


def check_exact_png(relative: str, size: tuple[int, int], color_types: set[int]) -> None:
    info = png_info(relative)
    if info is None:
        return
    width, height, color_type = info
    check((width, height) == size, f"{relative} is {width}x{height}; expected {size[0]}x{size[1]}")
    check(color_type in color_types, f"{relative} has unsupported PNG color type {color_type}")


build_gradle = read("app/build.gradle.kts")
check(bool(re.search(r"\bapplicationId\s*=\s*\"com\.imageshare\.app\"", build_gradle)), "Application ID is not com.imageshare.app")
for field, expected in (("compileSdk", 36), ("targetSdk", 36), ("minSdk", 29), ("versionCode", 1)):
    match = re.search(rf"\b{field}\s*=\s*(\d+)", build_gradle)
    check(bool(match) and int(match.group(1)) == expected, f"{field} must be {expected}")
check("versionName = \"1.0.0\"" in build_gradle, "versionName must be 1.0.0")
check("isMinifyEnabled = true" in build_gradle and "isShrinkResources = true" in build_gradle, "Release R8 and resource shrinking must be enabled")
check("validateReleaseSigning" in build_gradle, "Release signing validation is not wired")

manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
read("app/src/main/AndroidManifest.xml")
if manifest_path.is_file():
    root = ET.parse(manifest_path).getroot()
    android_ns = "{http://schemas.android.com/apk/res/android}"
    permissions = {element.get(android_ns + "name") for element in root.findall("uses-permission")}
    banned = {
        "android.permission.INTERNET",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "com.google.android.gms.permission.AD_ID",
        "android.permission.QUERY_ALL_PACKAGES",
    }
    unexpected = sorted(permissions & banned)
    check(not unexpected, f"Manifest contains banned permissions: {chr(44).join(unexpected)}")
    check("android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" in permissions, "mediaProcessing foreground-service permission is missing")

metadata_root = "fastlane/metadata/android/en-US"
for filename, limit in (("title.txt", 30), ("short_description.txt", 80), ("full_description.txt", 4000)):
    text = read(f"{metadata_root}/{filename}").rstrip("\r\n")
    check(0 < len(text) <= limit, f"{filename} is {len(text)} characters; limit is {limit}")

full_description = read(f"{metadata_root}/full_description.txt")
check("no internet permission" in full_description.lower(), "Full description must disclose the lack of INTERNET permission")
check(not any(codec in full_description for codec in ("HEIF", "AVIF")), "Full description advertises an unshipping output codec")

for changelog in (ROOT / metadata_root / "changelogs").glob("*.txt"):
    text = changelog.read_text(encoding="utf-8").rstrip("\r\n")
    check(0 < len(text) <= 500, f"{changelog.relative_to(ROOT)} is {len(text)} characters; limit is 500")

check_exact_png(f"{metadata_root}/images/icon.png", (512, 512), {2, 6})
check_exact_png(f"{metadata_root}/images/featureGraphic.png", (1024, 500), {2})

screenshots = sorted((ROOT / metadata_root / "images/phoneScreenshots").glob("*.png"))
check(len(screenshots) >= 2, "At least two phone screenshots are required")
for screenshot in screenshots:
    info = png_info(str(screenshot.relative_to(ROOT)))
    if info is None:
        continue
    width, height, _ = info
    check(320 <= width <= 3840 and 320 <= height <= 3840, f"{screenshot.name} dimensions are outside the 320 to 3840 px range")
    check(max(width, height) / min(width, height) <= 2, f"{screenshot.name} exceeds the 2:1 aspect-ratio limit")

privacy = read("docs/PRIVACY_POLICY.md")
check("Last updated: 2026-07-27" in privacy, "Privacy policy revision date is stale")
check("github.com/adsamcik/ImageShare" in privacy, "Privacy policy contact mechanism is missing")

store_listing = read("docs/STORE_LISTING.md")
if "<ACCOUNT REQUIRED: insert the monitored Play support email>" in store_listing:
    external_actions.append("Add the monitored support email in Play Console and docs/STORE_LISTING.md")
if "verify after enabling GitHub Pages" in store_listing:
    external_actions.append("Enable GitHub Pages and verify the public privacy-policy URL returns HTTP 200")

release_workflow = read(".github/workflows/play-release.yml")
check("workflow_dispatch:" in release_workflow, "Play bundle workflow must be manually dispatched")
for automated_trigger in ("push:", "pull_request:", "schedule:", "release:"):
    check(
        f"\n  {automated_trigger}" not in release_workflow,
        f"Play bundle workflow must not use automatic trigger: {automated_trigger}",
    )
for forbidden in (
    "upload-google-play",
    "gradle-play-publisher",
    "fastlane supply",
    "serviceAccountJson",
):
    check(
        forbidden not in release_workflow,
        f"Play bundle workflow contains automatic publishing integration: {forbidden}",
    )

if failures:
    print("Play readiness: FAIL")
    for failure in failures:
        print(f"  - {failure}")
    raise SystemExit(1)

print("Play readiness repository checks: PASS")
if external_actions:
    print("Play Console or account actions still required:")
    for action in external_actions:
        print(f"  - {action}")
    if "--strict-external" in sys.argv[1:]:
        raise SystemExit(2)
