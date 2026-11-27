#!/usr/bin/env python3
"""Source-contract checks for the manual direct-APK publishing job."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
workflow = (ROOT / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
checks = [
    ("manual dispatch exposes the direct APK toggle", "publish_direct_apk:" in workflow and "type: boolean" in workflow),
    ("direct APK publishing defaults on for manual runs", "default: true" in workflow),
    ("publishing job is restricted to workflow_dispatch", "github.event_name == 'workflow_dispatch'" in workflow and "&& inputs.publish_direct_apk" in workflow),
    ("publishing waits for successful APK build", "needs: [build-apk]" in workflow and "needs.build-apk.result == 'success'" in workflow),
    ("publishing has contents write permission", "publish-ci-apk:" in workflow and "contents: write" in workflow),
    ("job downloads the existing APK matrix artifacts", "pattern: dsh-android-apk-*" in workflow and "merge-multiple: true" in workflow),
    ("job rejects missing APK files", "find apk -maxdepth 1 -type f -name '*.apk'" in workflow),
    ("APK files are published as standalone assets", "files: apk/*.apk" in workflow),
    ("CI release is rolling prerelease and not latest", "tag_name: ci-apk" in workflow and "prerelease: true" in workflow and "make_latest: false" in workflow),
    ("existing JVM unit-test step remains in build workflow", ":app:testDebugUnitTest" in workflow),
    ("existing formal release job remains", "publish-release:" in workflow),
]
failed = 0
for label, ok in checks:
    print(("PASS" if ok else "FAIL") + "  " + label)
    failed += not ok
print(f"passed={len(checks) - failed} failed={failed}")
sys.exit(1 if failed else 0)
