#!/usr/bin/env python3
"""Guard the runtime-install recovery path against redundant full-tree I/O.

cleanupInstallResidue must not pre-walk engine.staging just to calculate a log
size before deleteRecursively() performs its own traversal. This is a source
contract probe; device timing remains a separate acceptance test.
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SOURCE = os.path.join(
    ROOT, "app/src/main/java/app/dsh/mobile/engine/RuntimeInstaller.kt"
)

with open(SOURCE, encoding="utf-8") as f:
    src = f.read()

failures: list[str] = []


def check(name: str, ok: bool) -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    if not ok:
        failures.append(name)


start = src.find("private fun cleanupInstallResidue()")
end = src.find("\n    /**", start + 1)
body = src[start:end] if start >= 0 and end > start else ""

check("cleanupInstallResidue exists", bool(body))
check("orphan staging is still removed recursively", "staging.deleteRecursively()" in body)
check("swap log still protects recovery evidence", "if (swapLogFile().isFile) return" in body)
check("no full-tree size pre-scan remains", "sizeOf(" not in body and "walkTopDown()" not in body)
check("orphan runtime zip cleanup remains", 'File(ctx.cacheDir, "runtime.zip")' in body and "zip.delete()" in body)
check("obsolete sizeOf helper is removed", not re.search(r"private fun sizeOf\(", src))
check("install mutex remains in place", "synchronized(INSTALL_LOCK)" in src)
check("staging integrity validation remains", "check(isRootCompleteAt(staging))" in src)
check("swap intent log remains before root mutation", "writeSwapLog(pendingLog)" in src)
check("phase timing helper uses monotonic elapsed realtime", "SystemClock.elapsedRealtime()" in src)
for phase in (
    "recover-interrupted-swap",
    "recover-pending-health",
    "cleanup-install-residue",
    "read-manifest",
    "probe-installed-runtime",
    "copy-bundled-runtime",
    "download-runtime",
    "verify-runtime-archive-abi",
    "unzip-runtime",
    "restore-executable-bits",
    "validate-staging-runtime",
):
    check(f"timing covers {phase}", f'measurePhase("{phase}")' in src)

if failures:
    print(f"FAILED ({len(failures)}): {failures}")
    sys.exit(1)
print("All runtime-install I/O efficiency checks passed.")
