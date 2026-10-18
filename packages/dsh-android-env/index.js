/**
 * [dsh-android] Android 环境说明书 —— 核心常驻 + 完整手册按需加载。
 *
 * ## 为什么不再写 AGENTS.md
 * 旧实现把说明书 seed 成 `$DSH_HOME/AGENTS.md`，由
 * `@deepseek-ai/dsh-agent-instructions` 以 **user 角色消息**注入：只在文件读写时
 * 刷新、随 compaction 被压缩、每会话只注入一次。长会话里说明书会被压没，agent
 * 退化成"陌生沙箱"重新探测环境；改文案也常常不生效。
 *
 * ## 两层结构（v1.3.1）
 * 1. **core** —— 每步都要的事实，走 `systemPrompt.section()`：
 *
 *    | 正文 | order |
 *    | --- | --- |
 *    | 平台 / 工作区 | 44 |
 *    | 已就绪能力（PATH、闸门、扩展中心、端口） | 46 |
 *    | 工作约定 + 反欺诈边界 | 50 |
 *    | 当前权限模式 + 已激活扩展（每步求值） | 15（context） |
 *
 * 2. **skill `android-env`** —— 用到才付钱的完整手册（loop/scr 全表、UI 自动化
 *    协议与反模式、屏幕控制、扩展排障、端上构建、局域网）。走
 *    `ctx.skills.registerProvider()`：system prompt 里只留一条 name+description
 *    （约 40 token，且按 digest 去重、每会话只发一次），正文只在模型主动调
 *    `skill` 工具时读取。
 *
 * 合计：常驻约 1.7k 字符，完整手册约 9.3k 字符按需加载。
 *
 * ## 硬约束：section 的 complete 必须为 false
 * `assemble()` 里若同时存在多于一个 `complete: true` 的 section 会直接抛
 * `multiple complete prompt sections are active`，整轮请求失败。
 *
 * ## 可编辑 / 可关
 * - 正文**每步从磁盘读**（mtime 缓存 1s），改文件即生效，不用重启。
 * - `$DSH_HOME/android-env/<文件名>` 覆盖包内同名文件；`ref/` 下的正文覆盖时
 *   写 `$DSH_HOME/android-env/ref/<文件名>`。
 * - `disabledSections: ["ui-automation"]` 这类 id 只对 core 生效；
 *   `disabledSections: ["skill"]` 摘掉整个按需手册；`p0Probe: true` 挂通道探针。
 *
 * @module dsh-android-env
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** Cordis 插件名。 */
const name = "android-env";

/** 依赖：常驻 section 走 systemPrompt，按需手册走 skills。 */
const inject = ["systemPrompt", "skills"];

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** 包内正文目录。 */
const SECTIONS_DIR = path.join(HERE, "sections");

/** `$DSH_HOME`：用户覆盖目录与状态文件的根。 */
const HOME = process.env.DSH_HOME || "";

/** 正文缓存窗口（assemble 每步都跑，别每步都读盘）。 */
const CACHE_MS = 1000;

/**
 * 常驻 section 表：id → 文件名与 order。
 *
 * order 44–50 是引擎保留表之外的区间（TOOL_* 从 1000 起、PERSONA 为 0/10200）。
 */
const CORE_SECTIONS = [
  { id: "platform", file: "10-platform.md", order: 44 },
  { id: "core-tools", file: "20-core-tools.md", order: 46 },
  { id: "agreements", file: "40-agreements.md", order: 50 },
];

/** 按需手册的正文文件（按顺序拼接成 skill body）。 */
const SKILL_FILES = [
  "ref/00-ui-automation.md",
  "ref/10-ui-antipatterns.md",
  "ref/20-device.md",
  "ref/30-toolchain.md",
  "ref/40-lan.md",
];

/** 按需手册在 catalog 里露出的那一行。 */
const SKILL_NAME = "android-env";

const SKILL_DESCRIPTION =
  "Android 设备完整运营手册：scr/loop 命令表、UI 自动化协议与反模式、屏幕控制、扩展排障、端上构建、局域网。";

const SKILL_WHEN =
  "当任务要读屏/点击、跑 UI 自动化、装扩展、端上构建 APK、配局域网访问，或你准备自己探测环境时，先加载它。";

/** 动态 context 的 order（引擎 CONTEXT_ORDERS 从 110 起，15 排在它们前面）。 */
const CONTEXT_ORDER = 15;

/** 每个文件的读取缓存：绝对路径 → { checkedAt, text }。 */
const readCache = new Map();

/**
 * 读取一个正文文件：用户覆盖目录优先，其次包内；带 1s 缓存。
 *
 * 读不到返回空串 —— 空 section 会被 `renderPrompt` 过滤掉，等于零 token，
 * 比抛错让整轮 assemble 失败安全。
 *
 * @param {string} file - 相对 sections/ 的文件名（可含 `ref/`）。
 * @returns {string} 正文（已 trim），或空串。
 */
function readBody(file) {
  const candidates = [];
  if (HOME) candidates.push(path.join(HOME, "android-env", file));
  candidates.push(path.join(SECTIONS_DIR, file));

  for (const candidate of candidates) {
    const cached = readCache.get(candidate);
    // ⚠️ 只短路**命中内容**的缓存：不存在/空的读取也会被缓存，若这里直接返回
    // cached.text，就会在 1s 内把"覆盖目录没有这个文件"误当成"正文为空"，
    // 从而永远读不到包内正文（v1.3.1 实测踩过：core 段整段变 0 字符）。
    if (cached !== undefined && cached.text.length > 0 && Date.now() - cached.checkedAt < CACHE_MS) return cached.text;
    let text = "";
    try {
      text = fs.readFileSync(candidate, "utf8").trim();
    } catch {
      text = ""; // 不存在 → 试下一个
    }
    readCache.set(candidate, { checkedAt: Date.now(), text });
    if (text.length > 0) return text;
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
 * 已激活扩展：从 PATH 匹配 `<engine>/extensions/<id>/bin`。
 *
 * 不读安装清单 —— PATH 才是"此刻能不能用"的事实，扩展目录里可能还有未激活的残留。
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
 * 把按需手册的正文拼成一个 skill body。
 * @returns {string} 完整正文，或空串（文件缺失）。
 */
function skillBody() {
  const parts = [];
  for (const file of SKILL_FILES) {
    const body = readBody(file);
    if (body.length > 0) parts.push(body);
  }
  return parts.join("\n\n");
}

/**
 * 注册按需手册的 skill provider。
 *
 * 契约（`@deepseek-ai/dsh-skill`）：
 * - `list()` 返回候选数组或 `{candidates, complete}`；`list()` 每步都会被调，
 *   **这里刻意不做任何 IO**，只把候选吐出去。
 * - `get(candidate)` 才真正读正文；正文每步可改，所以不做长缓存。
 * - 候选与定义的 `provider` 必须等于 provider 的 `name`，`rank` 必须是有限数。
 *
 * @param {object} ctx - 插件上下文（需 skills 服务）。
 */
function registerSkill(ctx) {
  const provider = {
    name: "android-env",
    /** @returns {object[]} 单条候选，不做 IO。 */
    list() {
      return [
        {
          name: SKILL_NAME,
          description: SKILL_DESCRIPTION,
          whenToUse: SKILL_WHEN,
          source: "android-env-plugin",
          rank: 500,
          provider: "android-env",
          path: SECTIONS_DIR,
        },
      ];
    },
    /**
     * 读取正文。读不到就返回 undefined —— 调用方会得到 "unknown or no longer
     * available"，比抛错干净。
     * @param {object} candidate - list() 返回的候选。
     * @returns {object|undefined} 完整 skill 定义。
     */
    get(candidate) {
      const content = skillBody();
      if (content.length === 0) return undefined;
      return {
        name: candidate.name,
        description: candidate.description,
        whenToUse: SKILL_WHEN,
        source: candidate.source,
        provider: "android-env",
        path: SECTIONS_DIR,
        content,
      };
    },
  };
  // registerProvider(create)：引擎同步调用 create(control) 取 provider 对象，
  // 直接传对象会被当成函数调用 → TypeError。control 我们不需要（不做 IO、不监听失效）。
  return ctx.skills.registerProvider(() => provider);
}

/**
 * 挂载：按配置注册 core sections、动态 context 与按需手册。
 *
 * @param {object} ctx - 插件上下文（需 systemPrompt + skills）。
 * @param {unknown} config - loader 传入的原始配置。本插件不导出 Config schema，
 *   因此自行校验：非法值按默认处理，绝不阻断引擎启动。
 */
function apply(ctx, config) {
  const raw = config && typeof config === "object" ? config : {};

  const disabled = new Set(
    Array.isArray(raw.disabledSections)
      ? raw.disabledSections.map((value) => String(value).trim().toLowerCase()).filter(Boolean)
      : [],
  );

  // 未知 id 只警告不报错：错别字不该让引擎起不来。
  const known = new Set([...CORE_SECTIONS.map((section) => section.id), "dynamic", "skill"]);
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

  // ── 常驻 section（正文每步从磁盘读，带 1s 缓存）───────────────────────
  let mounted = 0;
  for (const section of CORE_SECTIONS) {
    if (disabled.has(section.id)) {
      ctx.logger.info(`[${name}] section "${section.id}" disabled by config`);
      continue;
    }
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

  // ── 按需手册（skill）─────────────────────────────────────────────────
  let skill = "off";
  if (!disabled.has("skill")) {
    ctx.effect(() => registerSkill(ctx));
    skill = "on";
  }

  // ── P0 探针（可选，通道验证用）───────────────────────────────────────
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
    `[${name}] core ${mounted}/${CORE_SECTIONS.length} sections, skill ${skill}` +
      (disabled.size === 0 ? "" : ` (disabled: ${[...disabled].join(", ")})`) +
      (raw.p0Probe === true ? " + p0 probe" : ""),
  );
}

export { apply, inject, name, CORE_SECTIONS, SKILL_FILES, SKILL_NAME, readBody, activeExtensions, privilegeMode, skillBody };
