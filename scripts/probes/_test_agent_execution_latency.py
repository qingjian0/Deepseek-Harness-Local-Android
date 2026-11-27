#!/usr/bin/env python3
"""Regression guards for avoidable latency in Android agent execution paths.

These are source-contract checks, not a substitute for Kotlin compilation or
on-device timing tests. Keep assertions independent of comment placement.
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PATHS = {
    "pace": "app/src/main/java/app/dsh/mobile/HumanPace.kt",
    "loop": "app/src/main/java/app/dsh/mobile/engine/LocalLoopExecutor.kt",
    "a11y": "app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt",
    "bridge": "app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt",
}


def read(key: str) -> str:
    with open(os.path.join(ROOT, PATHS[key]), encoding="utf-8") as f:
        return f.read()


pace, loop, a11y, bridge = (read(k) for k in ("pace", "loop", "a11y", "bridge"))
checks: list[tuple[str, bool]] = []

checks.append((
    "human-like pacing is disabled by default",
    bool(re.search(r"const\s+val\s+DEFAULT_ENABLED\s*=\s*false\b", pace)),
))
checks.append((
    "local loop uses the shared opt-in default",
    'plan.optBoolean("humanPace", HumanPace.DEFAULT_ENABLED)' in loop,
))
checks.append((
    "loop and bridge import HumanPace from the app package",
    "import app.dsh.mobile.HumanPace" in loop and
    "import app.dsh.mobile.HumanPace" in bridge,
))
checks.append((
    "local loop IPC worker queue is bounded",
    "private val ipc = ThreadPoolExecutor(" in loop and
    "ArrayBlockingQueue<Runnable>(1)" in loop and
    "ThreadPoolExecutor.AbortPolicy()" in loop,
))
checks.append((
    "timed-out local loop IPC is cancelled and purged",
    "future.cancel(true)" in loop and "ipc.purge()" in loop,
))
checks.append((
    "local loop IPC saturation is handled explicitly",
    "RejectedExecutionException" in loop and "ipc saturated" in loop,
))
checks.append((
    "batch API defaults human pacing off",
    bool(re.search(r"humanPace:\s*Boolean\s*=\s*HumanPace.DEFAULT_ENABLED", a11y)),
))
checks.append((
    "batch delay is conditional, not unconditional",
    "if (humanPace) HumanPace.pause(HumanPace.stepPauseMs())" in a11y and
    a11y.count("HumanPace.pause(") == 1,
))
checks.append((
    "bridge forwards explicit humanPace opt-in",
    'obj.optBoolean("humanPace", HumanPace.DEFAULT_ENABLED)' in bridge,
))
checks.append((
    "screen IPC worker queue is bounded",
    "private val screenExec = java.util.concurrent.ThreadPoolExecutor(" in bridge and
    "java.util.concurrent.ArrayBlockingQueue<Runnable>(2)" in bridge and
    "java.util.concurrent.ThreadPoolExecutor.AbortPolicy()" in bridge,
))
checks.append((
    "timed-out screen IPC is cancelled and purged",
    "future.cancel(true)" in bridge and "screenExec.purge()" in bridge,
))
checks.append((
    "screen IPC saturation is handled explicitly",
    "RejectedExecutionException" in bridge and "screen ipc saturated" in bridge,
))

failed = 0
for label, ok in checks:
    print(("PASS" if ok else "FAIL") + "  " + label)
    if not ok:
        failed += 1
print(f"passed={len(checks) - failed} failed={failed}")
sys.exit(1 if failed else 0)
