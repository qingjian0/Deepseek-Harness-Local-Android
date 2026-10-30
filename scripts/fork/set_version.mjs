#!/usr/bin/env node
/**
 * 设置 versionName / versionCode（本 fork 定制项，Node 实现）。
 *
 * 用法：node scripts/fork/set_version.mjs 1.5.7 305
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const GRADLE = path.join(ROOT, "app", "build.gradle.kts");
const [name, code] = process.argv.slice(2);

if (!name || !code) {
  console.error("用法: node scripts/fork/set_version.mjs <versionName> <versionCode>");
  process.exit(2);
}

let text = fs.readFileSync(GRADLE, "utf8");
const before = text;
text = text.replace(/versionName = "[^"]+"/, `versionName = "${name}"`);
text = text.replace(/versionCode = \d+/, `versionCode = ${code}`);
if (text === before) {
  console.error("set_version: 未匹配到 versionName/versionCode");
  process.exit(1);
}
fs.writeFileSync(GRADLE, text, "utf8");
console.log(`set_version: versionName=${name} versionCode=${code}`);
