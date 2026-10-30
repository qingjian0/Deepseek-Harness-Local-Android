#!/usr/bin/env node
/**
 * 端口整体 +1000（本 fork 定制项，Node 实现）。
 *
 *   4080 -> 4080  引擎 WebUI
 *   4083 -> 4083  能力桥（scr / notify / loop / say）
 *   4085 -> 4085  本地嵌入服务（语义 pick）
 *   4090 -> 4090  LAN 反向代理
 *
 * 为什么：旧包 dsh.dsh.mobile 固定 4080/4083，新包同名段会 EADDRINUSE 起不来。
 * 整体 +1000 后新旧可同时运行；已定案「固定 +1000」，不做探测/顺延。
 * 用法：node scripts/fork/port_shift.mjs [--dry-run]
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const MAP = [["4080", "4080"], ["4083", "4083"], ["4085", "4085"], ["4090", "4090"]];
const EXTS = new Set([".kt", ".kts", ".java", ".xml", ".pro", ".mjs", ".js", ".py", ".sh",
  ".yml", ".yaml", ".json", ".md", ".gradle", ".properties", ".txt", ".c", ".h"]);
const SKIP_DIRS = new Set([".git", "build", ".gradle", "node_modules", "dist", "artifacts", ".idea", ".kotlin"]);
// 文档目录描述的是历史与上游原值，重放时不改写
const SKIP_PATHS = ["docs/fork"];
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

const changed = [];
let skipped = 0;
for (const p of walk(ROOT)) {
  if (!EXTS.has(path.extname(p))) continue;
    if (SKIP_PATHS.some((s) => path.relative(ROOT, p).startsWith(s))) continue;
  let text;
  try { text = fs.readFileSync(p, "utf8"); } catch { continue; }
  if (!MAP.some(([old]) => text.includes(old))) { skipped++; continue; }
  let next = text;
  for (const [old, repl] of MAP) next = next.split(old).join(repl);
  if (next === text) continue;
  changed.push(path.relative(ROOT, p));
  if (!DRY) fs.writeFileSync(p, next, "utf8");
}
console.log(`port_shift: 修改 ${changed.length} 个文件，跳过 ${skipped} 个` + (DRY ? "（dry-run）" : ""));
for (const c of changed.slice(0, 20)) console.log("  ", c);
if (changed.length > 20) console.log(`   ... 共 ${changed.length} 个`);
