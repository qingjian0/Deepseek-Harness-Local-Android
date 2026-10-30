#!/usr/bin/env node
/**
 * 包名迁移（本 fork 定制项，Node 实现，无需 python）。
 *
 * 上游包名：ap"p".dsh.mobile  →  本 fork：dsh.dsh.mobile
 *
 * 顺序固定：
 *   1. 源码树 app/src/main/java/ap p/dsh/mobile -> dsh/dsh/mobile
 *   2. 全仓文本替换（点号形式、斜杠形式、JNI 符号、脚本里的分段路径）
 *   3. JNI 导出符号 Java_ap p_dsh_mobile_ -> Java_dsh_dsh_mobile_
 *      （漏第 3 步：loadLibrary 成功但首个 native 调用 UnsatisfiedLinkError，引擎直接崩）
 *   4. 校验：源码树不得再有旧 JNI 符号
 *
 * 幂等：旧目录不存在则跳过迁移，只做文本替换。
 * 用法：node scripts/fork/rebrand.mjs [--dry-run]
 *
 * 注意：本文件里的旧包名字面量用拼接构造，避免被自身规则改写。
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const SRC = path.join(ROOT, "app", "src", "main", "java");

const A = "ap" + "p";                 // 旧首段
const OLD = A + ".dsh.mobile";        // 旧包名（点号）
const NEW = "dsh.dsh.mobile";         // 新包名
const OLD_SLASH = A + "/dsh/mobile";
const NEW_SLASH = "dsh/dsh/mobile";
const OLD_JNI = "Java_" + A + "_dsh_mobile_";
const NEW_JNI = "Java_dsh_dsh_mobile_";

const OLD_DIR = path.join(SRC, A, "dsh", "mobile");
const NEW_DIR = path.join(SRC, "dsh", "dsh", "mobile");

const EXTS = new Set([".kt", ".java", ".xml", ".pro", ".mjs", ".js", ".py", ".sh", ".yml",
  ".yaml", ".json", ".md", ".gradle", ".kts", ".properties", ".txt", ".c", ".h", ".cpp"]);
const SKIP_DIRS = new Set([".git", "build", ".gradle", "node_modules", "dist", "artifacts", ".idea", ".kotlin"]);
// 文档目录描述的是历史与上游原值，重放时不改写
const SKIP_PATHS = ["docs/fork"];
const DRY = process.argv.includes("--dry-run");

/** 脚本里常见的分段路径： "app", "dsh", "mobile" */
const SEG_PATTERNS = [
  [`"${A}", "dsh", "mobile"`, `"dsh", "dsh", "mobile"`],
  [`"${A}","dsh","mobile"`, `"dsh","dsh","mobile"`],
  [`'${A}', 'dsh', 'mobile'`, `'dsh', 'dsh', 'mobile'`],
];

function walk(dir, out = []) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIRS.has(ent.name)) continue;
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

function rewrite(text) {
  let t = text.split(OLD).join(NEW);
  t = t.split(OLD_SLASH).join(NEW_SLASH);
  t = t.split(OLD_JNI).join(NEW_JNI);
  for (const [a, b] of SEG_PATTERNS) t = t.split(a).join(b);
  return t;
}

function main() {
  const self = fileURLToPath(import.meta.url);

  if (fs.existsSync(OLD_DIR)) {
    if (DRY) {
      console.log("rebrand: dry-run，将迁移", path.relative(ROOT, OLD_DIR));
    } else {
      if (fs.existsSync(NEW_DIR)) {
        console.error("rebrand: 目标目录已存在，先人工确认", NEW_DIR);
        process.exit(1);
      }
      fs.mkdirSync(path.dirname(NEW_DIR), { recursive: true });
      fs.renameSync(OLD_DIR, NEW_DIR);
      console.log("rebrand: 源码树已迁移");
    }
  } else {
    console.log("rebrand: 旧包目录不存在（可能已迁移），跳过迁移");
  }

  let changed = 0;
  for (const p of walk(ROOT)) {
    if (!EXTS.has(path.extname(p))) continue;
    if (SKIP_PATHS.some((s) => path.relative(ROOT, p).startsWith(s))) continue;
    if (p === self) continue;            // 不改自身
    let text;
    try { text = fs.readFileSync(p, "utf8"); } catch { continue; }
    const next = rewrite(text);
    if (next !== text) {
      changed++;
      if (!DRY) fs.writeFileSync(p, next, "utf8");
    }
  }
  console.log(`rebrand: 文本替换 ${changed} 个文件` + (DRY ? "（dry-run）" : ""));

  const leftovers = [];
  for (const p of walk(path.join(ROOT, "app", "src", "main"))) {
    if (!/\.(c|h|cpp|kt|java)$/.test(p)) continue;
    if (fs.readFileSync(p, "utf8").includes(OLD_JNI)) leftovers.push(path.relative(ROOT, p));
  }
  if (leftovers.length) {
    console.error("rebrand: ⚠️ JNI 符号残留", leftovers);
    process.exit(1);
  }
  return 0;
}

process.exit(main());
