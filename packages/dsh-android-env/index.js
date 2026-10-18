/**
 * [dsh-android] Android 环境说明书 —— 极小常驻 + 单 skill 三层按需。
 *
 * ## 为什么不再写 AGENTS.md
 * 旧实现把说明书 seed 成 `$DSH_HOME/AGENTS.md`，由
 * `@deepseek-ai/dsh-agent-instructions` 以 **user 角色消息**注入：只在文件读写时
 * 刷新、随 compaction 被压缩、每会话只注入一次。长会话里说明书会被压没，agent
 * 退化成"陌生沙箱"重新探测环境；改文案也常常不生效。
 *
 * ## 三层结构（v1.3.1）
 *
 * | 层 | 内容 | 何时付费 |
 * | --- | --- | --- |
 * | core | `sections/10-core.md`，约 700 字符：平台事实、工作区、闸门、端口边界、手册入口 | 每个请求 |
 * | catalog | skill `android-env` 的一条 name+description | 每会话一次（被 compaction 隐藏后自动补发） |
 * | 主题文件 | `sections/ref/{screen,ui-automation,tools,lan,policy}.md` | 模型 `read` 哪个才付哪个 |
 *
 * skill 加载时只返回一份**索引**，并通过 `resourceBase` 告诉模型正文目录在哪；
 * 真正的命令表/协议由模型用 `read` 工具按需读取，可以只读一段（`read` 支持 offset/limit）。
 * 这样常驻成本压到约 700 字符，而窄主题（比如只配局域网）只需再付 600 字符。
 *
 * ## 硬约束：section 的 complete 必须为 false
 * `assemble()` 里若同时存在多于一个 `complete: true` 的 section 会直接抛
 * `multiple complete prompt sections are active`，整轮请求失败。
 *
 * ## 可编辑 / 可关
 * - 正文**每步从磁盘读**（mtime 缓存 1s），改文件即生效，不用重启。
 * - `$DSH_HOME/android-env/<文件名>` 覆盖包内同名文件；`ref/` 下的写
 *   `$DSH_HOME/android-env/ref/<文件名>`。
 * - `disabledSections: ["core" | "dynamic" | "skill"]`，关闭 = 不挂载。
 * - `p0Probe: true` 挂通道验证探针（首行 P0-PROBE-OK）。
 *
 * @module dsh-android-env
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** Cordis 插件名。 */
const name = "android-env";

/** 依赖：常驻走 systemPrompt，手册走 skills。 */
const inject = ["systemPrompt", "skills"];

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** 包内正文目录。 */
const SECTIONS_DIR = path.join(HERE, "sections");

/** 主题文件目录：skill 的 `resourceBase` 指向它。 */
const REF_DIR = path.join(SECTIONS_DIR, "ref");

/** `$DSH_HOME`：用户覆盖目录的根。 */
const HOME = process.env.DSH_HOME || "";

/** 正文缓存窗口（assemble 每步都跑，别每步都读盘）。 */
const CACHE_MS = 1000;

/** 常驻 section 表。order 44 在引擎保留的 TOOL_*（1000+）之前。 */
const CORE_SECTIONS = [{ id: "core", file: "10-core.md", order: 44 }];

/**
 * 主题文件表：文件名 → 一句话用途。索引由它生成，模型据此决定读哪个。
 * 增删主题文件时只改这里。
 */
const TOPICS = [
  { file: "screen.md", purpose: "读屏、点击、滑动、截图、OCR、通知与交互纪律" },
  { file: "ui-automation.md", purpose: "loop 计划 DSL、校验语义、执行前沿、反模式" },
  { file: "tools.md", purpose: "工具链、扩展中心与排障、端上构建 APK、给用户看页面" },
  { file: "lan.md", purpose: "局域网访问 WebUI、端口边界" },
  { file: "policy.md", purpose: "微信/QQ/银行/支付类应用：风险告知与用户确认" },
];

const SKILL_NAME = "android-env";

const SKILL_DESCRIPTION =
  "Android 设备操作手册索引（屏幕/UI 自动化/工具链/局域网/风险政策），按主题文件按需精读。";

const SKILL_WHEN =
  "当任务要读屏或点击、跑 UI 自动化、装扩展、端上构建 APK、配局域网，或涉及微信/银行类应用时加载。";

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
 * ⚠️ 只有命中**非空**缓存才可短路：不存在/空的读取也会被缓存，直接返回
 * `cached.text` 会把"覆盖目录没这个文件"误判成"正文为空"，整段变 0 字符。
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
    if (cached !== undefined && cached.text.length > 0 && Date.now() - cached.checkedAt < CACHE_MS) return cached.text;
    let text = "";
    try {
      text = fs.readFileSync(candidate, "utf8").trim();
    } catch {
      text = "";
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
 * skill 正文：一份最小索引（真正的正文是 base 目录里的主题文件）。
 * @returns {string} 索引 markdown。
 */
function skillIndex() {
  return [
    "Android 操作手册索引。正文在同目录下的主题文件里，**只读你实际需要的那个**（用 `read`，大文件可用 offset/limit 分段读）：",
    "",
    ...TOPICS.map((topic) => `- \`${topic.file}\` — ${topic.purpose}`),
    "",
    "涉及微信、QQ、银行或支付类应用时，先读 `policy.md` 再动手。",
  ].join("\n");
}

/**
 * 注册按需手册的 skill provider。
 *
 * 契约（`@deepseek-ai/dsh-skill`）：
 * - `list()` 每步都会被调，**刻意不做 IO**，只吐候选。
 * - `get(candidate)` 才构造内容；`resourceBase` 让引擎在加载时打印 base 目录。
 * - 候选与定义的 `provider` 必须等于 provider 的 `name`，`rank` 必须是有限数。
 *
 * @param {object} ctx - 插件上下文（需 skills 服务）。
 */
function registerSkill(ctx) {
  const provider = {
    name: "android-env",
    list() {
      return [
        {
          name: SKILL_NAME,
          description: SKILL_DESCRIPTION,
          whenToUse: SKILL_WHEN,
          source: "android-env-plugin",
          rank: 500,
          provider: "android-env",
          path: REF_DIR,
        },
      ];
    },
    get(candidate) {
      return {
        name: candidate.name,
        description: candidate.description,
        whenToUse: SKILL_WHEN,
        source: candidate.source,
        provider: "android-env",
        // 引擎据此在 <skill_resources> 里打印 base 目录，模型再用 read 拼相对路径。
        resourceBase: { kind: "directory", path: REF_DIR },
        content: skillIndex(),
      };
    },
  };
  // registerProvider(create)：引擎同步调用 create(control) 取 provider 对象；
  // 直接传对象会被当成函数调用 → TypeError。
  return ctx.skills.registerProvider(() => provider);
}

/**
 * 挂载：按配置注册 core section、动态 context 与按需手册。
 *
 * @param {object} ctx - 插件上下文（需 systemPrompt + skills）。
 * @param {unknown} config - loader 传入的原始配置；非法值按默认处理，绝不阻断启动。
 */
function apply(ctx, config) {
  const raw = config && typeof config === "object" ? config : {};

  const disabled = new Set(
    Array.isArray(raw.disabledSections)
      ? raw.disabledSections.map((value) => String(value).trim().toLowerCase()).filter(Boolean)
      : [],
  );

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

  // ── 常驻 core（每步从磁盘读，带 1s 缓存）──────────────────────────────
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

  // ── P0 探针（可选）───────────────────────────────────────────────────
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
    `[${name}] core ${mounted}/${CORE_SECTIONS.length} sections, skill ${skill}, ${TOPICS.length} topics` +
      (disabled.size === 0 ? "" : ` (disabled: ${[...disabled].join(", ")})`) +
      (raw.p0Probe === true ? " + p0 probe" : ""),
  );
}

export { apply, inject, name, CORE_SECTIONS, TOPICS, REF_DIR, SKILL_NAME, readBody, activeExtensions, privilegeMode, skillIndex };
