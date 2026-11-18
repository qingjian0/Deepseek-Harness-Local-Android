#!/usr/bin/env python3
"""Static contract checks for developer-only prompt management."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/dsh/mobile"
RES = ROOT / "app/src/main/res"
failures = []

def check(label, condition):
    print(("PASS  " if condition else "FAIL  ") + label)
    if not condition:
        failures.append(label)

dev = (JAVA / "DeveloperOptionsActivity.kt").read_text(encoding="utf-8")
manager = (JAVA / "PromptManagementActivity.kt").read_text(encoding="utf-8")
settings = (JAVA / "SettingsActivity.kt").read_text(encoding="utf-8")
seed = (JAVA / "engine/AgentContextSeed.kt").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
dev_layout = ET.parse(RES / "layout/activity_developer_options.xml").getroot()
manager_layout = ET.parse(RES / "layout/activity_prompt_management.xml").getroot()
android_id = "{http://schemas.android.com/apk/res/android}id"

check("Developer options links to prompt management", "R.id.rowPromptManagement" in dev and "PromptManagementActivity::class.java" in dev)
check("Prompt management rejects launches when developer mode is off",
      "if (!DeveloperModePrefs.isEnabled(this))" in manager and "finish()" in manager)
check("Custom instructions reuse the existing editor", "UserPromptActivity::class.java" in manager)
check("Environment prompt toggle uses AgentContextSeed", "AgentContextSeed.isAppSeedEnabled(this)" in manager and "AgentContextSeed.setAppSeedEnabled(this, enabled)" in manager)
check("Environment prompt defaults to enabled", '.getBoolean(KEY_APP_SEED_ENABLED, true)' in seed)
check("Environment prompt OFF preserves the user section",
      'if (!isAppSeedEnabled(ctx))' in seed and "extractUserSection(existing)" in seed and "write(file, userSection(userContent))" in seed)
check("Re-enabling does not nest the existing user section",
      "val legacyUserContent = if (existing.contains(USER_SECTION_BEGIN))" in seed)
check("Writing user instructions normalizes existing sections first",
      "fun writeUserSection" in seed and "ensure(ctx)\n        val file" in seed)
check("Ordinary settings hide the old custom-instructions entry",
      "rowUserPrompt).visibility = View.GONE" in settings)
check("Prompt management activity is non-exported",
      'android:name=".PromptManagementActivity"' in manifest and
      'android:exported="false"' in manifest.split('android:name=".PromptManagementActivity"', 1)[1].split("/>", 1)[0])
dev_ids = {node.get(android_id) for node in dev_layout.iter()}
manager_ids = {node.get(android_id) for node in manager_layout.iter()}
custom_layout = ET.parse(RES / "layout/item_prompt_management_custom.xml").getroot()
manager_ids.update(node.get(android_id) for node in custom_layout.iter())
check("Developer page contains prompt management row", "@+id/rowPromptManagement" in dev_ids)
check("Prompt page exposes all five prompt layers",
      {"@+id/swPromptEnvironment", "@+id/rowPromptCustom", "@+id/rowPromptUpstream",
       "@+id/rowPromptSession", "@+id/rowPromptTools"}.issubset(manager_ids))
for locale in ("values/strings.xml", "values-zh-rCN/strings.xml"):
    strings = (RES / locale).read_text(encoding="utf-8")
    check(f"{locale} contains localized prompt-management labels",
          'name="prompt_management_title"' in strings and
          'name="prompt_environment_title"' in strings and
          'name="prompt_tools_title"' in strings)

print()
if failures:
    print("FAILED: %d check(s)" % len(failures))
    raise SystemExit(1)
print("OK: developer prompt management checks passed")
