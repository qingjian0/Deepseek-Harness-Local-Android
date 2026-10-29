#!/usr/bin/env python3
"""包名迁移 app.dsh.mobile → dsh.dsh.mobile（本 fork 可选定制项）。

顺序：① 文本替换（幂等，可重复跑）→ ② 源码树迁移 → ③ JNI 符号残留校验。
注意：gates 脚本（fast/parallel/…）与 .tmpl / NOTICE 等无扩展名文件也必须覆盖，
      否则 DSH_HOME 默认路径与 package 声明会留在旧包名上（1.5.5 实测漏过）。
"""
from __future__ import annotations
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "app" / "src" / "main" / "java"
OLD_PKG_DIR = SRC / "app" / "dsh" / "mobile"
NEW_PKG_DIR = SRC / "dsh" / "dsh" / "mobile"
EXTS = {".kt", ".kts", ".java", ".xml", ".pro", ".mjs", ".js", ".py", ".sh", ".yml",
        ".yaml", ".json", ".md", ".gradle", ".properties", ".txt", ".tmpl",
        ".c", ".cc", ".cpp", ".h", ".hpp"}
EXTRA_NAMES = {"fast", "parallel", "scr", "notify", "say", "loop", "psx", "killx", "NOTICE"}
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "dist", "artifacts", ".idea", ".kotlin", "scripts"}
DRY = "--dry-run" in sys.argv

def main() -> int:
    changed = 0
    for p in ROOT.rglob("*"):
        if not p.is_file():
            continue
        rel_parts = p.relative_to(ROOT).parts[:-1]
        if any(part in SKIP_DIRS for part in rel_parts):
            continue
        if p.suffix not in EXTS and p.name not in EXTRA_NAMES:
            continue
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        new = text.replace("app.dsh.mobile", "dsh.dsh.mobile").replace("app/dsh/mobile", "dsh/dsh/mobile")
        new = new.replace("Java_app_dsh_mobile_", "Java_dsh_dsh_mobile_")
        if new != text:
            changed += 1
            if not DRY:
                p.write_text(new, encoding="utf-8")
    print(f"rebrand: 文本替换 {changed} 个文件")
    if OLD_PKG_DIR.exists():
        if DRY:
            print("rebrand: dry-run，将迁移", OLD_PKG_DIR.relative_to(ROOT))
        elif NEW_PKG_DIR.exists():
            print("rebrand: 目标目录已存在，请人工确认", file=sys.stderr)
            return 1
        else:
            OLD_PKG_DIR.rename(NEW_PKG_DIR)
            print("rebrand: 源码树已迁移")
    leftovers = []
    for p in ROOT.rglob("*"):
        if not p.is_file() or p.suffix not in {".c", ".cc", ".cpp", ".h", ".hpp"}:
            continue
        if "Java_app_dsh_mobile" in p.read_text(encoding="utf-8", errors="ignore"):
            leftovers.append(str(p.relative_to(ROOT)))
    if leftovers:
        print("rebrand: ⚠️ JNI 符号残留", leftovers, file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
