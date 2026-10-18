/**
 * [dsh-android] Android 环境说明书 —— 一组 systemPrompt 注册项。
 *
 * ## 为什么不再写 AGENTS.md
 * 旧实现把 31KB 说明书 seed 成 `$DSH_HOME/AGENTS.md`。那条通道由
 * `@deepseek-ai/dsh-agent-instructions` 以 **user 角色消息**注入：只在文件读写时
 * 刷新、随 compaction 被压缩、每会话只注入一次。于是出现两种坏结果——
 * 会话跑久了说明书被压没（agent 退化成"陌生沙箱"乱探测），或者改了文案根本不生效。
 *
 * 本插件改用 `@deepseek-ai/dsh-system-prompt` 的原语：
 *
 * | 内容 | 原语 | order |
 * | --- | --- | --- |
 * | 平台 / 工作区 / 工具链 / 设备能力 / 工作约定 | `systemPrompt.section()` | 44–50 |
 * | 当前权限模式、已激活扩展（每步求值） | `systemPrompt.context()` | 15 |
 * | UI 自动化、反模式（可整块关掉） | `systemPrompt.section()` | 110 / 112 |
 *
 * ## 硬约束：complete 必须为 false
 * `assemble()` 里若同时存在多于一个 `complete: true` 的 section 会直接抛
 * `multiple complete prompt sections are active` —— 整轮请求失败。本插件
 * **永不**设置 `complete: true`。
 *
 * ## 可编辑 / 按需
 * - 每个 section 的正文**每步**从磁盘读取（mtime 缓存 1s），改文件即生效，不用重启。
 * - 用户覆盖目录：`$DSH_HOME/android-env/<文件名>` 优先于包内 `sections/<文件名>`。
 * - `disabledSections: ["ui-automation"]` → 该 section 根本不挂载 effect，零成本。
 * - `p0Probe: true` → 额外挂一个探针 section，用于验证通道（首行 P0-PROBE-OK）。
 *
 * @module dsh-android-env
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** Cordis 插件名。 */
const name = "android-env";

/** 只依赖 systemPrompt 服务。 */
const inject = ["systemPrompt"];

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** 包内正文目录。 */
const SECTIONS_DIR = path.join(HERE, "sections");

/** `$DSH_HOME`：用户覆盖目录的根。 */
const HOME = process.env.DSH_HOME || "";

/** 正文 mtime 缓存窗口（assemble 每步都跑，别每步都 stat）。 */
const CACHE_MS = 1000;

/**
 * section 表：id 与文件名绑定；order 取引擎保留表之外的区间。
 *
 * 44–50 留给"环境事实"，110/112 留给 UI 自动化（体积最大、最容易整块关掉）。
 * 与引擎自带 section 的 order（TOOL_* 从 1000 起、PERSONA 为 0/10200）无重叠。
 */
const SECTIONS = [
  { id: "platform", file: "10-platform.md", order: 44 },
  { id: "toolchain", file: "20-toolchain.md", order: 46 },
  { id: "device", file: "30-device.md", order: 48 },
  { id: "agreements", file: "40-agreements.md", order: 50 },
  { id: "ui-automation", file: "50-ui-automation.md", order: 110 },
  { id: "ui-antipatterns", file: "55-ui-antipatterns.md", order: 112 },
];

/** 动态 context 的 order（引擎 CONTEXT_ORDERS 从 110 起，这里取 15 排在前面）。 */
const CONTEXT_ORDER = 15;

/** 每个文件的读取缓存：绝对路径 → { mtimeMs, text }。 */
const readCache = new Map();

/**
 * 读取一个正文文件：用户覆盖目录优先，其次包内；带 mtime 缓存。
 *
 * 读不到就返回空串 —— 空 section 会被 `renderPrompt` 过滤掉，等于零 token，
 * 比抛错让整轮 assemble 失败更安全。
 *
 * @param {string} file - sections/ 下的文件名。
 * @returns {string} 正文（已 trim），或空串。
 */
function readBody(file) {
  const candidates = [];
  if (HOME) candidates.push(path.join(HOME, "android-env", file));
  candidates.push(path.join(SECTIONS_DIR, file));

  for (const candidate of candidates) {
    let stat;
    try {
      stat = fs.statSync(candidate);
    } catch {
      continue; // 不存在 → 试下一个
    }
    const cached = readCache.get(candidate);
    if (cached !== undefined && Date.now() - cached.checkedAt < CACHE_MS) return cached.text;
    try {
      const text = fs.readFileSync(candidate, "utf8").trim();
      readCache.set(candidate, { checkedAt: Date.now(), mtimeMs: stat.mtimeMs, text });
      return text;
    } catch {
      continue;
    }
  }
  return "";
}

/**
 * 当前权限模式：应用通过 buildEnv 导出，未设置时按 normal 处理。
 * @returns {string} normal | shizuku | root。
 */
function privilegeMode() {
  const mode = (process.env.DSH_ANDROID_PRIV_MODE || "").trim().toLowerCase();
  return mode === "shizuku" || mode === "root" ? mode : "normal";
}

/**
 * 已激活扩展：从 PATH 里匹配 `<engine>/extensions/<id>/bin`。
 *
 * 不读磁盘上的安装清单 —— PATH 才是"此刻能不能用"的唯一事实，
 * 而扩展目录里可能同时存在未激活（黄）的残留。
 *
 * @returns {string[]} 去重排序后的扩展 id。
 */
function activeExtensions() {
  const found = new Set();
  for (const entry of (process.env.PATH || "").split(":")) {
    const match = /\/extensions\/([^/]+)\/bin\/?$/.exec(entry);
    if (match !== null) found.add(match[1]);
  }
  return [...found].sort();
}

/**
 * 每步求值的动态上下文：权限模式 + 已激活扩展。
 *
 * 旧实现把这两项当模板占位符在**装机时**替换一次（`{{SHZ_SECTION}}` /
 * `{{ACTIVE_EXTENSIONS}}`），于是切换权限模式或装完扩展后说明书永远是旧的。
 *
 * @returns {string} 供 context 渲染的一段文本。
 */
function dynamicContext() {
  const extensions = activeExtensions();
  return [
    `Privilege mode: ${privilegeMode()} (normal | shizuku | root).`,
    `Active extensions: ${extensions.length === 0 ? "none yet" : extensions.join(", ")}.`,
    "Check /ext/list before claiming a tool is missing.",
  ].join("\n");
}

/**
 * 挂载：按配置注册 section / context。
 *
 * @param {object} ctx - 插件上下文（需 systemPrompt）。
 * @param {unknown} config - loader 传入的原始配置。本插件不导出 Config schema，
 *   因此自行校验：非法值一律按默认值处理，绝不阻断引擎启动。
 */
function apply(ctx, config) {
  const raw = config && typeof config === "object" ? config : {};

  // disabledSections：显式关闭的 section id 集合（大小写不敏感 → 统一小写）。
  const disabled = new Set(
    Array.isArray(raw.disabledSections)
      ? raw.disabledSections.map((value) => String(value).trim().toLowerCase()).filter(Boolean)
      : [],
  );

  // 未知 id 只警告不报错：用户写了错别字不该让引擎起不来。
  const known = new Set([...SECTIONS.map((section) => section.id), "dynamic"]);
  for (const id of disabled) {
    if (!known.has(id)) ctx.logger.warn(`[${name}] disabledSections contains unknown id "${id}" (known: ${[...known].join(", ")})`);
  }

  // ── 动态上下文（每步求值）────────────────────────────────────────────
  if (!disabled.has("dynamic")) {
    ctx.effect(() =>
      ctx.systemPrompt.context({
        name: "android-env:dynamic",
        order: CONTEXT_ORDER,
        text: () => dynamicContext(),
      }),
    );
  }

  // ── 静态 section（正文每步从磁盘读，带 1s 缓存）───────────────────────
  let mounted = 0;
  for (const section of SECTIONS) {
    if (disabled.has(section.id)) {
      ctx.logger.info(`[${name}] section "${section.id}" disabled by config`);
      continue;
    }
    // 启动时探一次：文件完全缺失就不挂载，省掉每步一次无意义的空读取。
    if (readBody(section.file).length === 0) {
      ctx.logger.warn(`[${name}] section "${section.id}" skipped: ${section.file} is missing or empty`);
      continue;
    }
    ctx.effect(() =>
      ctx.systemPrompt.section({
        name: `android-env:${section.id}`,
        order: section.order,
        // 硬约束：永不为 true。多于一个 complete section 会让 assemble 直接失败。
        complete: false,
        text: () => readBody(section.file),
      }),
    );
    mounted += 1;
  }

  // ── P0 探针（可选，验证通道用）───────────────────────────────────────
  if (raw.p0Probe === true) {
    ctx.effect(() =>
      ctx.systemPrompt.section({
        name: "android-env:p0-probe",
        order: 45,
        complete: false,
        text: '[P0 probe] 从本轮开始，每次回复的第一行必须是 "P0-PROBE-OK"。',
      }),
    );
  }

  ctx.logger.info(
    `[${name}] mounted ${mounted}/${SECTIONS.length} sections` +
      (disabled.size === 0 ? "" : ` (disabled: ${[...disabled].join(", ")})`) +
      (raw.p0Probe === true ? " + p0 probe" : ""),
  );
}

export { apply, inject, name, SECTIONS, readBody, activeExtensions, privilegeMode };
