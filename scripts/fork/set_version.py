#!/usr/bin/env python3
"""设置 versionName / versionCode（本 fork 定制项）。

用法：python3 scripts/fork/set_version.py 1.5.4 276
"""
from __future__ import annotations
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
GRADLE = ROOT / "app" / "build.gradle.kts"

def main() -> int:
    if len(sys.argv) < 3:
        print("用法: set_version.py <versionName> <versionCode>", file=sys.stderr)
        return 2
    name, code = sys.argv[1], sys.argv[2]
    text = GRADLE.read_text(encoding="utf-8")
    new = re.sub(r'versionName = "[^"]+"', f'versionName = "{name}"', text, count=1)
    new = re.sub(r'versionCode = \d+', f'versionCode = {code}', new, count=1)
    GRADLE.write_text(new, encoding="utf-8")
    print(f"set_version: versionName={name} versionCode={code}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
