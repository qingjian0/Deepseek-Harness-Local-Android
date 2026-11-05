#!/usr/bin/env python3
"""Regression guard for the default AGENTS.md prompt toggle."""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SEED = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt")
SUP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt")
SETTINGS = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/SettingsActivity.kt")
LAYOUT = os.path.join(ROOT, "app/src/main/res/layout/activity_settings.xml")

def read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()

seed, sup, settings, layout = map(read, (SEED, SUP, SETTINGS, LAYOUT))
ok = fail = 0

def check(name: str, condition: bool, detail: str = "") -> None:
    global ok, fail
    if condition:
        ok += 1
        print(f"  ✓ {name}")
    else:
        fail += 1
        print(f"  ✗ {name}" + (f" -- {detail}" if detail else ""))

print("=== 1. Backward-compatible default ===")
check("missing preference defaults to enabled", '.getBoolean(KEY_DEFAULT_SEED_ENABLED, true)' in seed)
check("stable preference key exists", 'KEY_DEFAULT_SEED_ENABLED = "agent_default_prompt_enabled"' in seed)

print("\n=== 2. Disable removes only the managed section ===")
check("disabled state is checked before seed generation", seed.find("if (!isEnabled(ctx))") < seed.find("write(file, render(ctx)"))
check("managed seed is removed when disabled", "removeManagedSeed(file, existing)" in seed)
check("user section is extracted before deleting managed file", "extractUserSection(existing)" in seed and "file.delete()" in seed)
check("non-empty user content is written back", "write(file, user + \"\\n\")" in seed)
check("custom instructions remain editable while disabled", "if (!isEnabled(ctx))" in seed and "write(file, body + \"\\n\")" in seed)

print("\n=== 3. UI and runtime wiring ===")
check("settings switch reads persisted state", "swAgentPrompt.isChecked = AgentContextSeed.isEnabled(this)" in settings)
check("settings switch persists changes", "AgentContextSeed.setEnabled(this, enabled)" in settings)
check("switch appears before custom instructions row", layout.find('swAgentPromptSeed') < layout.find('android:id="@+id/rowUserPrompt'))
check("engine startup still has one seed entry point", sup.count("AgentContextSeed.ensure(ctx)") == 1)

print("\n" + "-" * 48)
print(f"通过 {ok} / 失败 {fail}")
sys.exit(1 if fail else 0)
