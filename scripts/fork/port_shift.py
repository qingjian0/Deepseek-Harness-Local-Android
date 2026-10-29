#!/usr/bin/env python3
"""端口整体 +1000（本 fork 定制项）。

4080 → 4080（引擎 WebUI）
4083 → 4083（能力桥 scr/notify/loop）
4085 → 4085（本地嵌入服务）
4090 → 4090（LAN 反向代理）

为什么：旧包 dsh.dsh.mobile 固定用 4080/4083；新包若同名段会抢端口。
整体 +1000 后两个实例可同时运行，且不依赖探测/顺延（用户定案）。

幂等：已包含新端口、且不再包含旧端口的文件直接跳过。
用法：python3 scripts/fork/port_shift.py [--dry-run]
"""
from __future__ import annotations
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MAP = [("4080", "4080"), ("4083", "4083"), ("4085", "4085"), ("4090", "4090")]
EXTS = {".kt", ".kts", ".java", ".xml", ".pro", ".mjs", ".js", ".py", ".sh",
        ".yml", ".yaml", ".json", ".md", ".gradle", ".properties", ".txt"}
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "dist", "artifacts", ".idea", ".kotlin"}
DRY = "--dry-run" in sys.argv

def main() -> int:
    changed, skipped = [], 0
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
        if "4080" not in text and "4083" not in text and "4085" not in text and "4090" not in text:
            skipped += 1
            continue
        new = text
        for old, repl in MAP:
            new = repl.join(new.split(old))
        if new == text:
            continue
        changed.append(str(p.relative_to(ROOT)))
        if not DRY:
            p.write_text(new, encoding="utf-8")
    print(f"port_shift: 修改 {len(changed)} 个文件，跳过 {skipped} 个" + ("（dry-run）" if DRY else ""))
    for c in changed[:20]:
        print("  ", c)
    if len(changed) > 20:
        print(f"   ... 共 {len(changed)} 个")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
