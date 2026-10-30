# Fork 定制改动清单（1.5.7，上游 v1.4.53 基线）

- **上游**：`Soodok/Deepseek-Harness-Local-Android` @ `f31eb37`（`v1.4.53` / `versionCode 304`）
- **本 fork**：`qingjian0/Deepseek-Harness-Local-Android`
- **本次分支**：`refork/1.5.7`（**完全重建**：直接从上游开分支，不带旧历史）
- **版本**：`1.5.7` / `versionCode 305`（> 上游 304，可覆盖安装）
- **旧版对照**：`main@065771e` = 上游 1.4.44 + 定制（1.5.6 / 295）

## 一、重建原则

```
上游 main 开分支 → 逐项重放定制 → 校验 → 推 refork/1.5.7 →（合并 main）→ CI 自动出包
```

不采用「在旧代码上 merge 上游」：包名迁移会重命名上百个文件，merge 必然大面积冲突。

## 二、机械项（脚本可重放）

本机无 `python3`，工具已改写为 Node 版（行为一致：幂等、`--dry-run`、JNI 残留校验）：

| 脚本 | 作用 |
| --- | --- |
| `scripts/fork/rebrand.mjs` | 包名 `app.dsh.mobile` → `dsh.dsh.mobile`；源码树迁移 + 全仓替换 + JNI 符号 `Java_app_dsh_mobile_` → `Java_dsh_dsh_mobile_` |
| `scripts/fork/port_shift.mjs` | 端口 +1000：`3080→4080`、`3083→4083`、`3085→4085`、`3090→4090` |
| `scripts/fork/set_version.mjs` | 写 `versionName` / `versionCode` |

本次执行结果：

```
rebrand: 源码树已迁移；文本替换 164 个文件
port_shift: 修改 53 个文件
set_version: versionName=1.5.7 versionCode=305
```

## 三、手动项（代码改动）

### 1) AGENTS.md 不再注入默认种子

- `app/.../engine/AgentContextSeed.kt`：新增 `stripLegacySeedOnBoot(ctx)` —— 文件带旧种子标记时只保留用户段；无标记不动；文件不存在不创建。
- `app/.../engine/EngineSupervisor.kt`：启动早期 `step("guardian-restore")` 之后调用，日志 `AGENTS.md: legacy seed stripped, user section preserved`。

### 2) 语义 pick 向量缓存 + 本地模型预热

- `engine/FastPicker.kt`：`vecCache`（`ConcurrentHashMap`，上限 512 条）+ `fetchEmbeddings()`；每次只请求缓存缺失的文本，数量不符不写缓存。
  实测 763–1096ms → 热态毫秒级。
- `engine/LocalModel.kt`：`warmUpIfReady(ctx)` —— 模型已在本地才后台拉起 `llama-server`；**不存在绝不触发下载**；幂等、失败静默。
- `engine/EngineSupervisor.kt`：`step("bridge")` 之后 `scopeRef?.launch(Dispatchers.IO) { LocalModel.warmUpIfReady(ctx) }`。

### 3) 轻量运行时（体积）

`.github/workflows/android-build.yml` 在「校验运行时命令闭包」之前插入 `make-lite-runtime.py` 步骤，
剥离 LibreOffice / inspector；失败自动回退完整运行时（只告警，不红）。

### 4) 发版流程（1.4.53 起**无需改代码**）

上游已内置 fork 开关，三个发布 job 的条件均为：

```yaml
(github.repository == 'Soodok/Deepseek-Harness-Local-Android' ||
 vars.ENABLE_CI_IN_FORK == 'true')
```

本 fork 只需在仓库里配置：

| 类型 | 名称 | 值 / 用途 |
| --- | --- | --- |
| Variable | `ENABLE_CI_IN_FORK` | `true`（已配置） |
| Variable | `RELEASE_CERT_SHA256` | 证书指纹校验（已配置） |
| Secret | `RELEASE_KEYSTORE_BASE64` | release 签名 keystore（已配置） |
| Secret | `RELEASE_STORE_PASSWORD` | 已配置 |
| Secret | `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` | 已配置 |

发布路径：

| 触发 | job | Release |
| --- | --- | --- |
| push `main` / 手动 dispatch | `publish-edge` | 滚动 prerelease `edge` |
| push tag `v*` | `publish-release` | 正式 Release（含签名 `runtime-index.json`） |
| push `main`（预览清理） | `publish-preview` | 滚动 `preview` |

下载页：

- 全部：[releases](https://github.com/qingjian0/Deepseek-Harness-Local-Android/releases/)
- Edge（每次 main push）：[releases/tag/edge](https://github.com/qingjian0/Deepseek-Harness-Local-Android/releases/tag/edge)
- Preview：[releases/tag/preview](https://github.com/qingjian0/Deepseek-Harness-Local-Android/releases/tag/preview)

> Token 等私有凭据**不入库**：本机存于 `~/.secrets/fork-credentials.env`（0600）。
> 需要新机器/新会话时从那里取值，不要写进仓库文件或提交历史。

## 四、验证清单

- [ ] `applicationId = "dsh.dsh.mobile"`，源码树 `app/src/main/java/dsh/dsh/mobile`
- [ ] `DEFAULT_PORT = 4080`；仓内无 `3080/3083/3085/3090` 运行时代码残留
- [ ] 无 `Java_app_dsh_mobile` 残留
- [ ] `AgentContextSeed.stripLegacySeedOnBoot` + `EngineSupervisor` 调用存在
- [ ] `FastPicker.vecCache` / `LocalModel.warmUpIfReady` 存在
- [ ] `node scripts/fork/port_shift.mjs --dry-run` 输出「修改 0 个文件」
- [ ] CI `android-build` 四 lane + `publish-edge` 全绿；edge 出 8 个 APK
- [ ] 装机：`AGENTS.md` 无 `dsh-android AGENTS seed` 段；`/local/status` 显示 `modelReady=true`

## 五、已知坑

- **JNI 符号**：漏改 `Java_app_dsh_mobile_` → `loadLibrary` 成功但首个 native 调用抛
  `UnsatisfiedLinkError`（1.5.0 真机事故）。`rebrand.mjs` 末尾会校验残留。
- **tar 解压丢 `.java`**：上次重建时外置解压脚本漏了 3 个 `.java`，导致
  `Unresolved reference: ExtensionApkInstallActivity`。这次全程用 `git clone`，不存在该问题。
- **noruntime 包缺运行时索引**：需要一个 `v*` tag 才会生成 `runtime-index.json`；
  edge 的 noruntime 首次启动若下载失败，先打 tag 走 `publish-release`。
