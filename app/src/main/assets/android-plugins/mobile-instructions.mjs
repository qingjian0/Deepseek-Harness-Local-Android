/**
 * [dsh-android] Host environment section for the agent's system prompt.
 *
 * Why this plugin exists (v1.4.0, replaces the AGENTS.md app seed):
 *   `$DSH_HOME/AGENTS.md` is the engine's only user-global *instruction* file, and
 *   `dsh-agent-instructions` injects it as a **user-role** message wrapped in a
 *   system-reminder whose own preamble says it does not override system/developer
 *   instructions. The host's environment facts and hard rules were living there:
 *   weakest authority, overridden by any project AGENTS.md, and first to be dropped
 *   when the 64KiB instruction budget is exceeded. Worse, user-owned text and
 *   app-owned text shared one file, which forced marker parsing and merge heuristics.
 *
 *   This plugin registers the app-owned bundle as a real system prompt section
 *   (strong, static, versioned with the app), so `$DSH_HOME/AGENTS.md` can be
 *   100% user-owned with no markers and no merge logic at all.
 *
 * Content is compiled by tools/instructions/build.mjs into
 * `$DSH_HOME/mobile/resident.md` (docs and skills live beside it).
 *
 * Constraints worth knowing:
 *   · `systemPrompt.section().text` is evaluated synchronously by the engine, so the
 *     file is read with statSync + mtime memo (no async, no watcher, no restart).
 *   · Sections are NOT covered by the instruction chain's 64KiB budget, so this
 *     plugin enforces its own ceiling and truncates loudly instead of growing silently.
 */
import fs from "node:fs";
import path from "node:path";

export const name = "mobile-instructions";

/** The prompt registry this row contributes to. */
export const inject = ["systemPrompt"];

/** Section name; keep stable — it is the identity other layers would shadow. */
const SECTION = "mobile:environment";

export function apply(ctx, config = {}) {
  const home = process.env.DSH_HOME ?? process.env.HOME;
  if (!home) {
    warn(ctx, "no DSH_HOME/HOME in the engine environment; environment section disabled");
    return;
  }

  const file = path.join(home, config.file ?? "mobile/resident.md");
  const maxBytes = config.maxBytes ?? 8192;
  const order = config.order ?? 100;
  let memo = { key: "", text: "" };

  const render = () => {
    let stat;
    try {
      stat = fs.statSync(file);
    } catch {
      return "";
    }
    const key = `${stat.mtimeMs}:${stat.size}`;
    if (key === memo.key) return memo.text;
    let text = fs.readFileSync(file, "utf8");
    if (Buffer.byteLength(text) > maxBytes) {
      text = `${text.slice(0, maxBytes)}\n\n[environment section truncated at ${maxBytes} bytes]`;
      warn(ctx, `${file} exceeds ${maxBytes} bytes; truncated`);
    }
    memo = { key, text };
    return text;
  };

  ctx.effect(
    () =>
      ctx.systemPrompt.section({
        name: SECTION,
        order,
        text: render,
      }),
    "mobile.environment()",
  );
}

function warn(ctx, message) {
  try {
    ctx.logger.warn(message);
  } catch {
    /* logging is best-effort during boot */
  }
}
