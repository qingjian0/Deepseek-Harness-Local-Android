#!/usr/bin/env python3
"""Guard the fork's canonical APK application ID and its CI artifact verification."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
props = {}
for line in (ROOT / "fork.properties").read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()

assert props.get("applicationId") == "dsh.dsh.mobile", props.get("applicationId")
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
assert "applicationId = forkBuildIdentity.applicationId" in gradle
workflow = (ROOT / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
assert 'EXPECTED_APPLICATION_ID' in workflow
assert '--expect-application-id "$EXPECTED_APPLICATION_ID"' in workflow
verifier = (ROOT / "scripts/verify-apk-assets.py").read_text(encoding="utf-8")
assert "def manifest_package(" in verifier
assert "applicationId mismatch:" in verifier
assert re.search(r'add_argument\("--expect-application-id"', verifier)
print("PASS: canonical dsh.dsh.mobile is sourced from fork.properties and checked against APK manifest")
