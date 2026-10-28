#!/usr/bin/env python3
"""包名迁移 app.dsh.mobile → dsh.dsh.mobile（本 fork 可选定制项）。

做的事（顺序固定）：
  1. 源码目录 app/src/main/java/app/dsh/mobile → dsh/dsh/mobile
  2. 全仓文本替换 app.dsh.mobile → dsh.dsh.mobile、app/dsh/mobile → dsh/dsh/mobile
  3. JNI 导出符号 Java_app_dsh_mobile_ → Java_dsh_dsh_mobile_
     （漏掉第 3 步的后果：System.loadLibrary 成功但首个 native 调用抛
       UnsatisfiedLinkError，引擎 spawn 直接崩溃 —— 1.5.0 真机事故）
  4. 校验：源码树里不得再有旧的 Java_app_dsh_mobile 符号

注意：这是**破坏性**变更（数据目录、与旧版不能覆盖安装）。
幂等：检测到新包名已生效则直接返回。
用法：python3 scripts/fork/rebrand.py [--dry-run]
"""
from __future__ import annotations
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "app" / "src" / "main" / "java"
OLD_PKG_DIR = SRC / "app" / "dsh" / "mobile"
NEW_PKG_DIR = SRC / "dsh" / "dsh" / "mobile"
EXTS = {".kt", ".java", ".xml", ".pro", ".mjs", ".js", ".py", ".sh", ".yml",
        ".yaml", ".json", ".md", ".gradle", ".kts", ".properties", ".txt"}
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "dist", "artifacts", ".idea", ".kotlin"}
DRY = "--dry-run" in sys.argv

def main() -> int:
    if not OLD_PKG_DIR.exists():
        print("rebrand: 旧包目录不存在（可能已迁移），跳过")
        return 0
    if DRY:
        print("rebrand: dry-run，将移动", OLD_PKG_DIR.relative_to(ROOT))
    else:
        NEW_PKG_DIR.parent.mkdir(parents=True, exist_ok=True)
        if NEW_PKG_DIR.exists():
            print("rebrand: 目标目录已存在，请先人工确认", NEW_PKG_DIR, file=sys.stderr)
            return 1
        OLD_PKG_DIR.rename(NEW_PKG_DIR)
        print("rebrand: 源码树已迁移")
    changed = 0
    for p in ROOT.rglob("*"):
        if not p.is_file():
            continue
        if any(part in SKIP_DIRS for part in p.relative_to(ROOT).parts[:-1]):
            continue
        if p.suffix not in EXTS:
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
    leftovers = [str(p.relative_to(ROOT)) for p in ROOT.rglob("dsh_pty.c")
                 if "Java_app_dsh_mobile" in p.read_text(encoding="utf-8")]
    if leftovers:
        print("rebrand: ⚠️ JNI 符号残留", leftovers, file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
