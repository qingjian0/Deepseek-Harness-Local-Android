#!/usr/bin/env python3
"""Guard package identity and private-path separation from the official app."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
TRIAGE = (ROOT / "app/src/main/java/app/dsh/mobile/engine/EngineTriage.kt").read_text(encoding="utf-8")
VSCREEN = (ROOT / "app/src/main/assets/gates/vscreen").read_text(encoding="utf-8")
FAST = (ROOT / "app/src/main/assets/gates/fast").read_text(encoding="utf-8")
LAN = (ROOT / "app/src/main/java/app/dsh/mobile/engine/LanWebProxy.kt").read_text(encoding="utf-8")

checks = [
    ("fork install identity", 'applicationId = "dsh.dsh.mobile"' in GRADLE),
    ("upstream source namespace retained", 'namespace = "app.dsh.mobile"' in GRADLE),
    ("process triage uses fork package", 'PKG = "dsh.dsh.mobile"' in TRIAGE),
    ("vscreen APK lookup uses fork package", 'PKG="dsh.dsh.mobile"' in VSCREEN),
    ("vscreen fallback uses fork private path", "/data/user/0/dsh.dsh.mobile/files/dsh-home" in VSCREEN),
    ("fast fallback uses fork private path", "/data/user/0/dsh.dsh.mobile/files/dsh-home" in FAST),
    ("LAN engine log path derives from filesDir", 'File(ctx.filesDir, "engine/engine.log")' in LAN),
    ("LAN code has no old fixed app-private path", "/data/user/0/app.dsh.mobile" not in LAN),
]
failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("PASS " if ok else "FAIL ") + name)
print(f"\n{len(checks) - len(failed)} passed, {len(failed)} failed, {len(checks)} total")
raise SystemExit(1 if failed else 0)
