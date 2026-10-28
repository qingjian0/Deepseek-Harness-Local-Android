# Fork 定制改动清单（相对上游 `Soodok/Deepseek-Harness-Local-Android`）

- **本 fork**：`qingjian0/Deepseek-Harness-Local-Android`
- **改动快照**：tag `fork-snapshot-1.5.3`（=`v1.5.4` / commit `90ea7733`）
- **上游基线**：`v1.4.36`（versionCode 286）
- **同步分叉时的原则**：**先同步上游，再用本清单把定制改动重放上去**。
  不要反过来（在旧代码上 merge 上游），否则 116 个重命名文件必然大面积冲突。

---

## 一、同步流程（推荐）

```bash
# 1) 取最新工具包（本分支不参与同步，永远保留）
git fetch origin fork-toolkit
git checkout origin/fork-toolkit -- scripts/fork FORK_CHANGES.md

# 2) 把 main 重置到上游最新（等价于 GitHub 的 Sync fork）
git remote add upstream https://github.com/Soodok/Deepseek-Harness-Local-Android.git 2>/dev/null || true
git fetch upstream main
git reset --hard upstream/main

# 3) 重放机械改动（端口 +1000、版本号；可选包名）
bash scripts/fork/apply_mechanical.sh 1.5.4 276
#   要恢复包名 dsh.dsh.mobile 时：
#   WITH_REBRAND=1 bash scripts/fork/apply_mechanical.sh 1.5.4 276

# 4) 按「二、手动项」逐项应用 4 处代码改动（每项都已在本仓库 snapshot 里）

# 5) 提交 + 触发 CI 验证，绿了再合并
git add -A && git commit -m "sync: upstream v1.4.36 + fork customizations"
git push origin main
```

GitHub 网页上的 **Sync fork** 按钮同样是「merge 上游」——本 fork 因为包名迁移过（116 文件重命名），
直接点会冲突失败。若要保留包名，请走上面的「先重置后重放」。

> 取代码参考：`git show fork-snapshot-1.5.3:<path>` 可随时查看任何改动前/后的完整文件。

---

## 二、机械项（脚本自动）

### 1) 端口整体 +1000

| 用途 | 上游 | 本 fork |
|---|---|---|
| 引擎 WebUI | 3080 | **4080** |
| 能力桥（scr/notify/loop） | 3083 | **4083** |
| 本地嵌入服务（语义 pick） | 3085 | **4085** |
| LAN 反向代理 | 3090 | **4090** |
| shizuku 桥（engine+2） | 3082 | **4082** |

`scripts/fork/port_shift.py` 做全仓文本替换（运行时代码 + gates 脚本 + UI 文案 + 文档）。
**为什么**：旧包 `app.dsh.mobile` 固定占 3080/3083，新包若同名段会抢端口
（实测 `[bridge] bind FAILED ... EADDRINUSE` 启动失败）。整体平移后两个实例可共存。
**不要**恢复「自动顺延」方案——用户已定案：固定 +1000，不做探测/漂移。

验证：`rg "3080|3083|3085|3090" app src -g '!*.md'` 应为空（或只剩历史文档）。

### 2) 版本号

`scripts/fork/set_version.py 1.5.4 276`。每次同步后建议递增（上游 + 自己的版本线）。

---

## 三、手动项（4 处代码改动）

> 全部实现都在 `fork-snapshot-1.5.3` 里，可以直接对比拷贝：
> `git diff fork-snapshot-1.5.3 -- <file>`（或在 GitHub 上 compare）。

### 3) AGENTS.md 不再注入默认种子 + 启动清理旧种子

**文件**
- `app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt`
- `app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt`

**改动**
1. `AgentContextSeed` 新增 `fun stripLegacySeedOnBoot(ctx: Context): Boolean`：
   - 文件不存在 → `false`（不创建）
   - 不含 `<!-- dsh-android AGENTS seed v` 标记 → `false`（不动）
   - 含标记 → 只保留用户段（复用 `userSection(parseUserSection(...))`），写回并返回 `true`
2. `EngineSupervisor` 启动流程早期（`step("guardian-restore")` 之后）插入：
   ```kotlin
   val seedStripped = withContext(Dispatchers.IO) { AgentContextSeed.stripLegacySeedOnBoot(ctx) }
   step("agents-seed")
   if (seedStripped) Log.i(TAG, "AGENTS.md: stripped legacy app seed, user section preserved")
   ```

**验证**：旧版升级后 `$DSH_HOME/AGENTS.md` 只剩用户段；新装则不生成该文件。

### 4) 性能：语义 pick 向量缓存 + 本地模型启动预热

**文件**
- `engine/FastPicker.kt`（`LocalEmbedder`）
- `engine/LocalModel.kt`
- `engine/EngineSupervisor.kt`

**改动**
1. `LocalEmbedder`：新增 `vecCache`（`ConcurrentHashMap<String, DoubleArray>`，上限 512），
   把「目标 + 整屏候选」按文本缓存向量，只请求缓存没有的文本；
   请求/响应数量不符时不写缓存。`bestMatch` 的 HTTP 请求抽到 `fetchEmbeddings()`。
   *实测语义 pick 763–1096ms → 热态毫秒级。*
2. `LocalModel.warmUpIfReady(ctx)`：模型已在本地时后台拉起 `llama-server`；
   **模型不存在绝不触发下载**；幂等、失败静默。
3. `EngineSupervisor` 在 `step("bridge")` 之后：
   ```kotlin
   scopeRef?.launch(Dispatchers.IO) { LocalModel.warmUpIfReady(ctx) }
   ```

**验证**：`/local/status` 显示 `modelReady=true, serverRunning=true`；第二次 pick 明显变快。

### 5) 体积：CI 接入轻量运行时

**文件**：`.github/workflows/android-build.yml`（collect-runtime 步骤内）

在 `校验运行时命令闭包` 之前插入：
```yaml
      - name: 生成轻量运行时（剥离 LibreOffice + inspector）
        run: |
          set -euo pipefail
          du -h runtime.zip
          if python3 scripts/make-lite-runtime.py runtime.zip runtime-lite.zip; then
            ls -lh runtime-lite.zip
            mv runtime-lite.zip runtime.zip
            echo "::notice::lite runtime in use: $(du -h runtime.zip | cut -f1)"
          else
            echo "::warning::lite 运行时生成失败，回退完整运行时"
            rm -f runtime-lite.zip
          fi
```
**注意（1.5.3 真机踩过的坑）**：`make-lite-runtime.py` 必须**整包剥离** Office 插件
（`dsh-office-to-pdf`、`dsh-skill-office`）并同步摘掉宿主 package.json 里的依赖声明，
只删依赖不删包会得到「包在、依赖不在」的半残态 → 引擎启动失败 → 插件恢复反复重启。
本 fork 的 `v1.5.4` 已修复脚本（见 `90ea7733`），同步时记得一并带走该脚本改动。

**验证**：APK 由 228MB → 约 178MB（lite zip 220→167MB；WASM 压缩率高，别指望 60MB）。

### 6) fork 自动发版（否则构建成功也没有 APK 可下）

**文件**：`.github/workflows/android-build.yml`

把 `release` job 的 `if` 从「上游硬门禁 + 仅手动」改为：
```yaml
    if: >-
      (github.event_name == 'push' || github.event_name == 'workflow_dispatch')
      && github.ref == 'refs/heads/main'
      && needs.build-apk.result == 'success'
      && github.repository != 'Soodok/Deepseek-Harness-Local-Android'
```
**为什么**：上游把 `release` / `publish-edge` / `publish-release` 三个 job 都用
`github.repository == 'Soodok/...'` 硬门禁，fork 后它们永远 skipped —— 构建绿了也没有
任何 APK。放开 `release`（debug 签名，tag `v<version>-<run_number>`）后 main push 成功即出包。
`publish-edge` / `publish-release` 需要 `RELEASE_*` 签名 secrets，fork 一般没有，保持跳过即可。

---

## 四、可选：包名迁移（破坏性）

`python3 scripts/fork/rebrand.py` 做三件事：
1. 源码树 `app/src/main/java/app/dsh/mobile` → `dsh/dsh/mobile`
2. 全仓文本 `app.dsh.mobile` → `dsh.dsh.mobile`、`app/dsh/mobile` → `dsh/dsh/mobile`
3. **JNI 导出符号 `Java_app_dsh_mobile_` → `Java_dsh_dsh_mobile_`**

> ⚠️ 第 3 步最容易被漏：漏了会 `System.loadLibrary` 成功但首个 native 调用抛
> `UnsatisfiedLinkError`，引擎 spawn 直接崩（1.5.0 真机事故）。脚本末尾会校验残留。

**代价**：数据目录变为 `/data/user/0/dsh.dsh.mobile/`，与旧版不能覆盖安装、数据不共享；
每次同步上游都要重跑一次 rebrand。**如果只为「新旧共存」，端口 +1000 已经够了，包名不必改。**

---

## 五、验证清单（每次同步后）

- [ ] CI `android-build` 全绿（4 个 job + release）
- [ ] 安装后启动不闪退；`ls -l /dev/null` 应为 `crw-rw-rw- 1 root root 1, 3`（不是普通文件）
- [ ] 引擎起在 4080、桥在 4083（`/proc/net/tcp` 或日志 `engine port: 4080`）
- [ ] 旧包（3080 段）与新包可同时运行，互不抢端口
- [ ] `$DSH_HOME/AGENTS.md` 无 `dsh-android AGENTS seed` 段
- [ ] `/local/status`：`modelReady=true, serverRunning=true`（预热生效）
- [ ] APK 体积约 178MB（lite 生效，不是 228MB）
- [ ] `scr` / `notify` / `loop` 正常（能力桥 4083）

## 六、已知环境坑（非代码问题）

- **`/dev/null` 被破坏成普通文件**：zygote 开不了它 → 所有新进程闪退 + 系统卡顿。
  修复：`rm -f /dev/null && mknod /dev/null c 1 3 && chmod 666 /dev/null && restorecon /dev/null`。
  出现过一次（新包首启时某 root 脚本重定向所致），排查先看 `ls -l /dev/null`。
- **两个实例同时跑内存吃紧**（每实例 300–500MB + node），低内存机器建议只留一个。
