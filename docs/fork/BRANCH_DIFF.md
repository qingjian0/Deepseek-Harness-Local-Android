# 分支差异记录（fork vs 上游）

> 记录时间：2026-10-30 15:0x +08:00
> 仓库：`qingjian0/Deepseek-Harness-Local-Android`（fork self）
> 上游：`Soodok/Deepseek-Harness-Local-Android`

## 一、本次新基线

| 项 | 值 |
| --- | --- |
| 新分支 | `refork/1.5.7`（**干净历史，从上游直接开出**） |
| 基线提交 | `f31eb37` — fix(tooling): push-via-api 加卡强制覆盖护栏 |
| 上游版本 | `v1.4.53` / `versionCode 304` |
| 分支创建时差异 | **0 文件**（除未跟踪的 `scripts/fork/` 工具） |
| 包名（上游原样） | `dsh.dsh.mobile` |
| 端口（上游原样） | `4080 / 4083 / 4085 / 4090` |

## 二、差异规模

旧 `main`（`065771e`，自述"上游 v1.4.44 全量基线 + 5 项定制"）对比新基线 `f31eb37`：

```
227 files changed, 16132 insertions(+), 1647 deletions(-)
```

> 这 227 个文件里 **大部分是上游 1.4.44 → 1.4.53 的正常演进**，不是本 fork 的定制。
> 定制要按下面的「触点」清单识别，不能按文件数判断。

「自定义触点」扫描（在旧 `main` 树上）：

| 特征 | 命中文件数 | 说明 |
| --- | --- | --- |
| `4080 / 4083 / 4085 / 4090` | 47 | 端口整体 +1000 的全部触点 |
| `stripLegacySeedOnBoot` / `vecCache` / `warmUpIfReady` / `4090` | 20 | AGENTS 种子剥离 + 语义 pick 缓存 + 模型预热 |

## 三、上游 v1.4.53 的定制存在性（全部缺失 → 需要重做）

| 定制项 | 上游现状 | 判据 |
| --- | --- | --- |
| 包名 → `dsh.dsh.mobile` | 无 | `applicationId = "dsh.dsh.mobile"` |
| 端口 +1000 | 无 | `DEFAULT_PORT = 4080` 段 |
| AGENTS.md 种子剥离 | 无 | `AgentContextSeed.kt` 无 `stripLegacySeedOnBoot` |
| 语义 pick 向量缓存 | 无 | `FastPicker.kt` 无 `vecCache` |
| 本地模型预热 | 无 | `LocalModel.kt` 无 `warmUpIfReady` |
| CI 轻量运行时 | 无 | workflow 无 `make-lite-runtime.py` 步骤 |
| fork 自动发版 | 无 | `release / publish-edge / publish-release` 三个 job 均硬门禁 `github.repository == 'Soodok/...'` |

## 四、旧 main（1.5.6）遗留状态

| 项 | 值 |
| --- | --- |
| 提交 | `065771e` |
| 版本 | `1.5.6` / `versionCode 295` |
| 包名 | `dsh.dsh.mobile` |
| 端口 | 4080 / 4083 / 4085 / 4090 |
| 种子剥离 | 有（`AgentContextSeed.stripLegacySeedOnBoot`） |
| 性能 | 有（`vecCache` 6 处命中、`warmUpIfReady` 1 处） |
| CI 发版 | 未在 main 上体现（`release` job 门禁未放开；相关尝试在 `ci/release-on-fork`） |

## 五、分支全景（fork 侧）

| 分支 | HEAD | 主题 |
| --- | --- | --- |
| `main` | `065771e` | 旧基线：上游 1.4.44 + 定制 |
| `refork/1.5.7` | `f31eb37` | **本次新基线（干净）** |
| `fork-toolkit` | `f94cdc5` | 定制重放工具 + `FORK_CHANGES.md`（长期保留） |
| `sync/v1.5.4-upstream-1.4.41` | `8a5f5d0` | 上次同步重放记录 |
| `ci/release-on-fork` | `2612d50` | 让 fork 能在 tag/dispatch 发版 |
| `ci/p0-signing-seam` | `676d081` | 缺 `RELEASE_*` 时跳过发布 |
| `perf/ext-parallel-download` | `f1f5853` | 扩展依赖并发下载 |
| `fix/pty-jni-null-checks` | `9ec6c73` | JNI 字符串释放空指针 |
| `fix/response-cost-discipline` | `5150b37` | 输出/成本纪律 |
| `feat/ocr-threshold-per-package` | `65e9f15` | 新增 `POST /ocr/threshold` |
| `feat/android-env-*`（4 个） | 0d299a7 / 05054ba / 1a2b2d0 / 0bb6d0c | AGENTS 种子退役与 android-env 探针（已并入 1.5.x） |
| `feat/instructions-pipeline` | `665fd0c` | instructions 构建管线 |

## 六、重放顺序（本次执行）

```bash
# 1) 包名（破坏性，含 JNI 符号）
node scripts/fork/rebrand.mjs
# 2) 端口 +1000
node scripts/fork/port_shift.mjs
# 3) 版本
node scripts/fork/set_version.mjs 1.5.7 305
# 4) 手动项：AGENTS 种子剥离 / 语义 pick 缓存 / 模型预热 / lite 运行时 / fork 自动发版
# 5) 校验 + 提交 + 推送
```

> 本机无 `python3`，`scripts/fork/*.py` 已改写为等价的 `*.mjs`（`rebrand` / `port_shift` /
> `set_version`），行为与旧脚本一致（幂等、dry-run、JNI 残留校验）。

---

## 七、本轮重建的实际差异（相对上游，已落地）

| 类别 | 文件数 | 说明 |
| --- | --- | --- |
| 包名迁移（重命名 + 内容） | ~194 | 含 4 个 JNI 导出符号、脚本里的分段路径 |
| 端口 +1000 | 53 | 运行时代码 + gates + 插件 + 文档 |
| 手动代码项 | 4 | `AgentContextSeed` / `EngineSupervisor` / `FastPicker` / `LocalModel` |
| CI 体积 | 1 | `android-build.yml` 插入 lite runtime 步骤 |
| 新增文档与工具 | 7 | `docs/fork/*` + `scripts/fork/*.mjs` |

### 本轮踩到并修掉的两个坑

1. **probes 里的分段路径**：`os.path.join(..., "app", "dsh", "mobile")` 不是连续字符串，
   文本替换抓不到 → `i18n-check` 里两个 probe 读不到文件（run 41018080387 红）。
   已批量修正 12 个脚本，并把该规则写进 `rebrand.mjs`。
2. **`RepoConfig.kt.tmpl` 的 package 行**：`.tmpl` 不在替换扩展名列表里，
   生成的 `RepoConfig.kt` 包声明仍是旧包 → `Unresolved reference: RepoConfig`
   引发 AboutActivity / MainActivity / RuntimeIndexClient / AppUpdate* / UpdateDialogs
   全线编译失败（run 41018080316 红）。已修的首行 + 把 `.tmpl` 加入扩展名列表。

> 判据：`node scripts/fork/rebrand.mjs --dry-run` 必须输出「文本替换 0 个文件」，
> `node scripts/fork/port_shift.mjs --dry-run` 必须输出「修改 0 个文件」。
