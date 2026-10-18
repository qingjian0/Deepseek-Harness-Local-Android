#!/usr/bin/env node
/**
 * CI 门禁：
 *   1) 重新构建到临时目录，和提交的 app/src/main/assets/mobile 逐字节比对——有人手改产物就红；
 *   2) 预算与引用检查由 build.mjs 负责，这里继承它的退出码。
 */
import { execFileSync } from "node:child_process";
import { readdirSync, readFileSync, rmSync, statSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = dirname(fileURLToPath(import.meta.url));
const TMP = join(ROOT, "..", ".dist-check");

const walk = (dir, base = dir) =>
  readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name);
    return entry.isDirectory() ? walk(full, base) : [{ path: relative(base, full), full }];
  });

rmSync(TMP, { recursive: true, force: true });

try {
  execFileSync(process.execPath, [join(ROOT, "..", "build.mjs")], {
    cwd: join(ROOT, ".."),
    env: { ...process.env, BUILD_OUT: TMP },
    stdio: "inherit",
  });
} catch {
  rmSync(TMP, { recursive: true, force: true });
  console.error("✗ 构建失败（预算或引用检查未通过）");
  process.exit(1);
}

const distDir = join(ROOT, "..", "..", "..", "app", "src", "main", "assets", "mobile");
const committed = new Map(walk(distDir).map((f) => [f.path, f.full]));
const built = new Map(walk(TMP).map((f) => [f.path, f.full]));
const problems = [];

for (const [path, full] of built) {
  const other = committed.get(path);
  if (other === undefined) {
    problems.push(`assets/mobile 缺少 ${path}（忘记提交构建产物）`);
  } else if (!readFileSync(full).equals(readFileSync(other))) {
    problems.push(`assets/mobile/${path} 与源码不一致（有人手改了构建产物）`);
  }
}
for (const path of committed.keys()) {
  if (!built.has(path)) problems.push(`assets/mobile/${path} 没有对应源码（残留文件）`);
}

rmSync(TMP, { recursive: true, force: true });

const total = [...committed.keys()].reduce((sum, p) => sum + statSync(join(distDir, p)).size, 0);
console.log(`assets/mobile  ${committed.size} 个文件，共 ${total}B`);

if (problems.length > 0) {
  console.error("\n✗ " + problems.join("\n✗ "));
  process.exit(1);
}
console.log("✓ 构建产物与源码一致");
