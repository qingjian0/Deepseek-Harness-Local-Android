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
 *     plugin enforces its own ceiling (build budget 6144B + 1KB headroom) and
 *     truncates at a paragraph boundary instead of growing silently.
 *   · If the file is missing the section would otherwise render as an empty string:
 *     the agent would run with no environment facts at all and without a trace. The
 *     inline fallback below keeps a minimal, honest warning in that case.
 */
import fs from "node:fs";
import path from "node:path";

export const name = "mobile-instructions";

/** The prompt registry this row contributes to. */
export const inject = ["systemPrompt"];

/** Section name; keep stable — it is the identity other layers would shadow. */
const SECTION = "mobile:environment";

/** Runtime ceiling, slightly above the build budget so a healthy build never truncates. */
const DEFAULT_MAX_BYTES = 7168;

/**
 * Minimal degraded content: only used when the compiled bundle is missing.
 * Says what is unknown, what not to assume, and what the user can do about it.
 */
const FALLBACK = [
  "# 环境：Android（降级模式）",
  "",
  "本机的环境说明文件缺失（`$DSH_HOME/mobile/resident.md` 读不到），所以下面的平台事实**没有被加载**：",
  "",
  "- 不要假设 libc、包管理器、工具链的任何行为，需要时用命令自行探测（`uname`、`command -v`）。",
  "- 设备能力（`scr` / `loop` / `notify` / `say`）与端口边界仍然成立：3080 是 WebUI、3083 是能力桥，不要绑定它们。",
  "- 告诉用户重启引擎或重装 App；如果反复出现，让他们把 `engine.log` 发出来。",
].join("\n");

export function apply(ctx, config = {}) {
  const home = firstNonEmpty(process.env.DSH_HOME, process.env.HOME);
  if (!home) {
    warn(ctx, "no DSH_HOME/HOME in the engine environment; environment section disabled");
    return;
  }

  const file = path.join(home, config.file ?? "mobile/resident.md");
  const maxBytes = Number.isFinite(config.maxBytes) && config.maxBytes > 0 ? config.maxBytes : DEFAULT_MAX_BYTES;
  const order = Number.isFinite(config.order) ? config.order : 100;
  let memo = { key: "", text: "" };
  let warnedMissing = false;

  const render = () => {
    let stat;
    try {
      stat = fs.statSync(file);
    } catch {
      if (!warnedMissing) {
        warnedMissing = true;
        warn(ctx, `${file} is missing; serving the degraded environment section`);
      }
      return FALLBACK;
    }
    warnedMissing = false;

    const key = `${stat.mtimeMs}:${stat.size}`;
    if (key === memo.key) return memo.text;

    let buf;
    try {
      buf = fs.readFileSync(file);
    } catch (error) {
      warn(ctx, `cannot read ${file}: ${error?.message ?? error}`);
      return FALLBACK;
    }
    let text = buf.toString("utf8");
    if (buf.length > maxBytes) {
      // Byte-accurate cut: back off to a UTF-8 lead byte, then to a paragraph break so
      // a markdown table/code block is never sliced in half.
      let end = Math.min(maxBytes, buf.length);
      while (end > 0 && (buf[end] & 0xc0) === 0x80) end -= 1;
      text = buf.subarray(0, end).toString("utf8");
      const cut = text.lastIndexOf("\n\n");
      if (cut > text.length / 2) text = text.slice(0, cut);
      text += `\n\n[环境说明被截断：超过 ${maxBytes} bytes]`;
      warn(ctx, `${file} exceeds ${maxBytes} bytes; truncated at a paragraph boundary`);
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

  const size = safeSize(render());
  info(ctx, `section ${SECTION} registered (order ${order}, ${size} bytes source ${file})`);
}

function firstNonEmpty(...values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim().length > 0) return value;
  }
  return undefined;
}

function safeSize(text) {
  try {
    return Buffer.byteLength(text);
  } catch {
    return 0;
  }
}

function info(ctx, message) {
  try {
    ctx.logger.info(message);
  } catch {
    /* logging is best-effort during boot */
  }
}

function warn(ctx, message) {
  try {
    ctx.logger.warn(message);
  } catch {
    /* logging is best-effort during boot */
  }
}
