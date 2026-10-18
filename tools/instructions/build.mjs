#!/usr/bin/env node
/**
 * 把 content/ 编译成运行期产物，直接写进 APK 的 assets（app/src/main/assets/mobile）：
 *   mobile/resident.md            常驻段（policy + facts + 索引），由 mobile-instructions 插件注册成 system prompt section
 *   mobile/docs/*.md              按需手册，模型用 read 打开
 *   mobile/skills/<id>/SKILL.md   流程技能，目录里只出现 name + description
 *
 * 预算与引用在构建期校验，超了就失败——这是防止种子再次膨胀的唯一有效手段。
 * 产物提交进仓库，ci/check.mjs 重新构建并逐字节比对，防止有人手改 assets。
 */
import { readdirSync, readFileSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { join, dirname, basename } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = dirname(fileURLToPath(import.meta.url));
const OUT = process.env.BUILD_OUT ?? join(ROOT, "..", "..", "app", "src", "main", "assets", "mobile");

const BUDGET_BYTES = 6144;
const BUDGET_TOKENS = 2000;

const problems = [];
const read = (rel) => readFileSync(join(ROOT, rel), "utf8").trim();
const list = (rel) => {
  try {
    return readdirSync(join(ROOT, rel)).filter((f) => f.endsWith(".md") && !f.startsWith(".")).sort();
  } catch {
    return [];
  }
};
const bytes = (s) => Buffer.byteLength(s);
const tokens = (s) => {
  const cjk = (s.match(/[\u3000-\u303f\u3400-\u9fff\uff00-\uffef]/g) ?? []).length;
  return Math.round(cjk / 1.5 + (s.length - cjk) / 3.6);
};

// ---------- resident ----------
const sources = [
  ...list("content/policy").map((f) => `content/policy/${f}`),
  ...list("content/facts").map((f) => `content/facts/${f}`),
  "content/index.md",
];
const parts = [];
for (const file of sources) {
  const text = read(file);
  if (!text.startsWith("# ")) problems.push(`${file}: 段落必须以一级标题开头`);
  parts.push(text);
}
const resident = parts.join("\n\n") + "\n";

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });
writeFileSync(join(OUT, "resident.md"), resident);

// ---------- docs ----------
mkdirSync(join(OUT, "docs"), { recursive: true });
const docs = new Set();
for (const file of list("content/reference")) {
  writeFileSync(join(OUT, "docs", file), read(`content/reference/${file}`) + "\n");
  docs.add(file);
}

// ---------- skills ----------
const skills = [];
for (const file of list("content/procedures")) {
  const text = read(`content/procedures/${file}`);
  const front = /^---\n([\s\S]*?)\n---\n/.exec(text);
  if (front === null) {
    problems.push(`content/procedures/${file}: 缺少 YAML frontmatter`);
    continue;
  }
  const meta = front[1];
  for (const key of ["name", "description"]) {
    if (!new RegExp(`^${key}:\\s*\\S`, "m").test(meta)) problems.push(`content/procedures/${file}: frontmatter 缺 ${key}`);
  }
  const id = basename(file, ".md");
  mkdirSync(join(OUT, "skills", id), { recursive: true });
  writeFileSync(join(OUT, "skills", id, "SKILL.md"), text + "\n");
  skills.push(id);
}

// ---------- 预算 ----------
if (bytes(resident) > BUDGET_BYTES) problems.push(`resident ${bytes(resident)}B 超出预算 ${BUDGET_BYTES}B`);
if (tokens(resident) > BUDGET_TOKENS) problems.push(`resident ~${tokens(resident)} tokens 超出预算 ${BUDGET_TOKENS}`);

// ---------- 插值安全 ----------
// The section text is run through the engine's {{variable}} interpolation: an unknown
// or malformed {{...}} makes every prompt assembly throw. Keep the compiled prompt free
// of braces and catch it here instead of in production.
if (resident.includes("{{") || resident.includes("}}")) {
  problems.push("resident 含 {{ }}：section 文本会被 system prompt 插值，未知变量会让每轮渲染失败（docs/ 不进 prompt，不受此限）");
}

// ---------- 引用可达 ----------
// Docs are referenced as `docs/<name>.md` in the resident index.
for (const m of resident.matchAll(/docs\/([a-z0-9-]+\.md)/g)) {
  if (!docs.has(m[1])) problems.push(`resident 引用了不存在的文档 docs/${m[1]}`);
}

// Skills are referenced in content/index.md as a bullet `- `<id>` —— ...`.
// The em-dash separator is what distinguishes them from command bullets such as
// "- `python3` — ..." in the facts section, which are not skill references.
for (const m of resident.matchAll(/^- `([a-z0-9][a-z0-9-]+)` ——/gm)) {
  const id = m[1];
  if (!skills.includes(id)) problems.push(`resident 引用了不存在的技能 \`${id}\`（可用: ${skills.join(", ")}）`);
}

console.log(`resident  ${bytes(resident)}B  ~${tokens(resident)} tok   (预算 ${BUDGET_BYTES}B / ${BUDGET_TOKENS} tok)`);
console.log(`docs      ${docs.size}`);
console.log(`skills    ${skills.length}  ${skills.join(", ")}`);
if (problems.length > 0) {
  console.error("\n✗ " + problems.join("\n✗ "));
  process.exit(1);
}
console.log("✓ 预算与引用检查通过");
