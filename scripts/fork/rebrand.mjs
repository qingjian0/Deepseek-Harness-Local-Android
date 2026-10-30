#!/usr/bin/env node
/**
 * 包名迁移 dsh.dsh.mobile -> dsh.dsh.mobile（本 fork 定制项，Node 实现，无需 python）。
 *
 * 顺序固定：
 *   1. 源码树 app/src/main/java/dsh/dsh/mobile -> dsh/dsh/mobile
 *   2. 全仓文本替换 dsh.dsh.mobile -> dsh.dsh.mobile、dsh/dsh/mobile -> dsh/dsh/mobile
 *   3. JNI 导出符号 Java_dsh_dsh_mobile_ -> Java_dsh_dsh_mobile_
 *      （漏第 3 步：loadLibrary 成功但首个 native 调用 UnsatisfiedLinkError，引擎直接崩）
 *   4. 校验：源码树不得再有旧 JNI 符号
 *
 * 幂等：旧目录不存在则跳过迁移，只做文本替换。
 * 用法：node scripts/fork/rebrand.mjs [--dry-run]
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const SRC = path.join(ROOT, "app", "src", "main", "java");
const OLD_DIR = path.join(SRC, "app", "dsh", "mobile");
const NEW_DIR = path.join(SRC, "dsh", "dsh", "mobile");
const EXTS = new Set([".kt", ".java", ".xml", ".pro", ".mjs", ".js", ".py", ".sh", ".yml",
  ".yaml", ".json", ".md", ".gradle", ".kts", ".properties", ".txt", ".c", ".h", ".cpp"]);
const SKIP_DIRS = new Set([".git", "build", ".gradle", "node_modules", "dist", "artifacts", ".idea", ".kotlin"]);
const DRY = process.argv.includes("--dry-run");

function walk(dir, out = []) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP_DIRS.has(ent.name)) continue;
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

function main() {
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
    let text;
    try { text = fs.readFileSync(p, "utf8"); } catch { continue; }
    const next = text
      .replaceAll("dsh.dsh.mobile", "dsh.dsh.mobile")
      .replaceAll("dsh/dsh/mobile", "dsh/dsh/mobile")
      .replaceAll("Java_dsh_dsh_mobile_", "Java_dsh_dsh_mobile_");
    if (next !== text) {
      changed++;
      if (!DRY) fs.writeFileSync(p, next, "utf8");
    }
  }
  console.log(`rebrand: 文本替换 ${changed} 个文件` + (DRY ? "（dry-run）" : ""));

  const leftovers = [];
  for (const p of walk(path.join(ROOT, "app", "src", "main"))) {
    if (!/\.(c|h|cpp|kt|java)$/.test(p)) continue;
    if (fs.readFileSync(p, "utf8").includes("Java_app_dsh_mobile")) leftovers.push(path.relative(ROOT, p));
  }
  if (leftovers.length) {
    console.error("rebrand: ⚠️ JNI 符号残留", leftovers);
    process.exit(1);
  }
  return 0;
}

process.exit(main());
