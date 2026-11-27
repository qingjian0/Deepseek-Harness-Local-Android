#!/usr/bin/env python3
"""Regression guards for avoidable latency in Android agent execution paths."""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/HumanPace.kt")
LOOP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LocalLoopExecutor.kt")
A11Y = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt")
BRIDGE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt")


def read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


checks: list[tuple[str, bool]] = []
pace, loop, a11y, bridge = map(read, (PACE, LOOP, A11Y, BRIDGE))

checks.append(("human-like pacing is disabled by default", "const val DEFAULT_ENABLED = false" in pace))
checks.append(("local loop uses the shared opt-in default",
               'plan.optBoolean("humanPace", HumanPace.DEFAULT_ENABLED)' in loop))

batch_start = a11y.index("fun runBatch(")
batch_end = a11y.index("\n    /**", batch_start)
batch = a11y[batch_start:batch_end]
checks.append(("batch API defaults human pacing off",
               "humanPace: Boolean = HumanPace.DEFAULT_ENABLED" in batch))
checks.append(("batch delay is conditional, not unconditional",
               "if (humanPace) HumanPace.pause(HumanPace.stepPauseMs())" in batch
               and batch.count("HumanPace.pause(") == 1))
checks.append(("bridge forwards explicit humanPace opt-in",
               'obj.optBoolean("humanPace", HumanPace.DEFAULT_ENABLED)' in bridge))

screen_exec_start = bridge.index("private val screenExec")
screen_exec_end = bridge.index("/** 节点树 IPC 的默认上限", screen_exec_start)
screen_exec = bridge[screen_exec_start:screen_exec_end]
checks.append(("screen IPC worker queue is bounded",
               "ThreadPoolExecutor(" in screen_exec
               and "ArrayBlockingQueue<Runnable>(2)" in screen_exec))
screen_read_start = bridge.index("private fun <T> screenReadOnce")
screen_read_end = bridge.index("\n    /**", screen_read_start)
screen_read = bridge[screen_read_start:screen_read_end]
checks.append(("timed-out screen IPC is cancelled and purged",
               "future.cancel(true)" in screen_read and "screenExec.purge()" in screen_read))

failed = 0
for label, ok in checks:
    print(("PASS" if ok else "FAIL") + "  " + label)
    if not ok:
        failed += 1
print(f"passed={len(checks) - failed} failed={failed}")
sys.exit(1 if failed else 0)
