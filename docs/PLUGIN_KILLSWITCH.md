# 插件应急总开关（PluginKillSwitch）

> v1.4.27 · 适用场景：**装了个第三方插件，引擎没死、界面却用不了**

## 1. 它解决哪一类故障

| 现象 | 现有自救层 | 会不会动手 |
|---|---|---|
| 引擎起不来（装完插件再启动就崩） | `PluginRecovery.isolateRecentBundleOnBootFailure` | 会（摘最近装的那一个） |
| 引擎起来了，探针报 degraded 且能精确归因到某个 bundle | `PluginRecovery.quarantineOne` | 会（摘一个，上限 3 次） |
| 同签名真死连续 10 次 | `ProfileGuardian` | 会（归档整个 profiles） |
| **引擎活着、HTTP 通、探针 ok，但 WebUI 白屏 / 对话发不出 / 一点就卡** | 无 | **不会 —— 本开关补的就是这一格** |

最后一种最难受：所有自动层都在等"崩溃"这个信号，而用户此刻正被困在打不开的
WebUI 里，连"进插件中心卸载"都点不到。

## 2. 怎么用

### 路径 A · 设置页（主路径）

`设置 → 维护 → Emergency: turn off all third-party plugins`

- 标题随状态变：停用后变成 **Restore third-party plugins**；
- 确认框会列出**将被停用/恢复的每一个包名**，不盲点；
- 确认后引擎自动重启（10~30s）。

### 路径 B · 本地 HTTP（界面点不动时）

AgentBridge 只监听 `127.0.0.1:4083`，**且和其他端点一样要鉴权**（`X-DSH-Token`，
token 每进程随机生成、只通过环境变量交给引擎 —— 这是防同机第三方 App 调用读屏/
点击能力的既有设计，本端点不破例）。所以这条路面向**调试 / 有 root 的用户**：

```bash
adb forward tcp:4083 tcp:4083

# 取 token（debug 包用 run-as；release 包需要 root）
TOKEN=$(adb shell "run-as <包名> sh -c 'PID=\$(pidof node); \
  tr \"\\0\" \"\\n\" < /proc/\$PID/environ | grep DSH_BRIDGE_TOKEN'" \
  | tr -d '\r' | cut -d= -f2)

# 查状态
curl -s -H "X-DSH-Token: $TOKEN" http://127.0.0.1:4083/plugin/killswitch

# 停用全部第三方插件（202 = 已受理，引擎随后重启）
curl -s -X POST -H "X-DSH-Token: $TOKEN" -d '{"action":"disable"}' \
  http://127.0.0.1:4083/plugin/killswitch

# 恢复
curl -s -X POST -H "X-DSH-Token: $TOKEN" -d '{"action":"enable"}' \
  http://127.0.0.1:4083/plugin/killswitch

# 单独启用一个第三方插件（仅停用期间有效；白名单，重启后加载）
curl -s -X POST -H "X-DSH-Token: $TOKEN" \
  -d '{"action":"enable-one","package":"dsh-cost-meter"}' \
  http://127.0.0.1:4083/plugin/killswitch

# 再摘回去
curl -s -X POST -H "X-DSH-Token: $TOKEN" \
  -d '{"action":"disable-one","package":"dsh-cost-meter"}' \
  http://127.0.0.1:4083/plugin/killswitch
```

不带 token 一律 `403 {"ok":false,"error":"unauthorised"}`（fail-closed，实测）。
返回体里 `thirdParty` / `disabled` / `allow` / `total` 是当前加载列表的实况，排查时先看这个。

**停用期间的安装/更新是放开的（v1.4.28）**：装上/更新后**不会加载**（不在白名单），
「已安装」页会出现「单独启用」按钮，试过了再启用。这正是排查工作流：

> 全部禁用 → 重启 → 更新/重装嫌疑插件 → 单独启用它 → 好了就逐个恢复其余，
> 不好就「再停用」摘回去。

唯一仍会 `409` 的是**恢复中窗口**（`enabling`，引擎重启中）：

```jsonc
{"ok":false,"error":"plugins are being restored; try again in a moment", ...}
```

以前停用期间也无脑 409/拒绝，但「更新嫌疑插件」本来就是排查的一半，
堵死它反而让总开关变成死胡同 —— v1.4.28 起放开。

### 路径 C · 冷启动标志（连原生界面都进不去）

在 `$DSH_HOME` 放一个空文件，然后杀进程重开：

```bash
adb shell touch /sdcard/Android/data/<包名>/files/dshdata/.plugin-killswitch.force
adb shell am force-stop <包名>
```

（不需要 token，但需要能写进应用的数据目录：adb+root，或 debug 包
`run-as <包名> touch files/dsh-home/.plugin-killswitch.force`。）

下次启动时该标志被消费：先按当前 bundle 列表写一份"原始记录"，再按禁用处理，
然后自动删除标志。**标志只作为一次性的"这次启动请禁用"指令**，不会覆盖已有记录。

## 3. 内部机制：意图日志 + 每次启动收敛

```
用户动作 ──写──> $DSH_HOME/.plugin-killswitch.json  （原子写，唯一的"意图"）
                            │
                   引擎每次 spawn 前
                            ▼
             enforceBeforeBoot()：把 profiles/web/package.json
             的 dsh.profile.bundles 收敛到日志说的状态
                            │
                    引擎 healthy 后
                            ▼
              phase: enabling → enabled（恢复完成）
```

日志格式：

```json
{
  "v": 1,
  "phase": "disabled",
  "at": 1761490000000,
  "reason": "settings",
  "bundles": ["@deepseek-ai/dsh-base", "dsh-web-app", "dsh-cost-meter", "dsh-pet"],
  "count": 2
}
```

- `bundles` 存的是**完整的原始列表**（内置 + 第三方，保持原顺序）——只存第三方
  会让恢复后的顺序全乱。
- `phase` 三态：`disabled`（目标=只留内置）、`enabling`（目标=写回原列表）、
  `enabled`（已恢复，日志留作历史记录）。

### 为什么不在用户点按钮时直接改 manifest

引擎运行中会把内存里的 profile 状态写回 `package.json`，运行中改会被覆盖；
而"先停引擎再改"中间那段强杀窗口又会留下半改状态。所以：**动作只写意图，
manifest 只在引擎没跑的时候（spawn 前）改**。

### 为什么不会卡在半路

| 强杀时机 | 下次启动的行为 |
|---|---|
| 刚写完日志、还没重启 | `disabled` → 收敛成"只留内置"，与用户意图一致 |
| 收敛完、引擎还没起来 | 同上，收敛幂等，不重复写文件 |
| 恢复途中（`enabling`）被杀 | 再写回一遍原列表 → 收敛到"已恢复" |
| 恢复完、引擎没起来 | 同上 |

## 4. 恢复时的过滤规则（重要）

恢复**不是**无脑把日志里的数组塞回去，而是逐项判断：

```
保留 ⇔ dependencies 里有这个包  且  (profiles/web/node_modules/<pkg>
                                    或 engine/lib/node_modules/<pkg> 目录存在)
```

禁用期间被用户卸载的插件会被跳过（日志里有、磁盘上没有了）。把不存在的包写回
`dsh.profile.bundles` 会让引擎 bundle 解析失败 —— 那等于亲手再造一次启动故障。

## 5. 与其它自救层的关系

- **禁用期间 `PluginRecovery` 自然空转**：`userBundles` 为空，两个入口都会早退，
  不需要给它加任何特判。
- **不碰 `profile-guardian.meta`、不写 `.safe-mode`**：这是用户的显式选择，
  不是"引擎判定配置坏了"，不该污染崩溃计数。
- **`ProfileGuardian.snapshotLastGood()` 会把"已禁用"存成 last-good**：之后若真
  回滚，插件仍保持禁用 —— 这是刻意的（回滚到最近一个能用的状态），别当 bug 改。
- **`ProfileGuardian.topLevelConfigFiles()` 已排除本日志文件**：否则一次安全模式
  会把日志归档+删除，用户永久失去恢复依据，而这恰好是它最该起作用的时刻。
- **禁用期间禁止安装新插件**（`PluginCenter.install` 直接拒绝 + 插件中心提示条）：
  装进来也不会加载，只会让用户看到"装完没反应"这种更难查的现象。恢复中
  （`enabling`）同样拒绝 —— 那会儿监督器正要按日志写回 bundles，新装的包会被
  写回动作丢掉，还会和 pnpm 抢 `node_modules`。HTTP 侧见第 2 节路径 B 的 409。

## 6. 排查清单

| 问题 | 看哪里 |
|---|---|
| 改了没生效 | `engine.log` 里搜 `kill-switch converged plugins`（启动时打印 before→after） |
| 恢复后少了几个插件 | 同一条日志的 `skipped=`，以及 `Settings → 插件中心 → 已安装` |
| 状态显示不对 | 直接看 `.plugin-killswitch.json` 的 `phase` 字段；UI 完全以它为准 |
| 想手工兜底 | 把 `phase` 改成 `enabling` 重启 = 强制再恢复一次；改成 `disabled` 重启 = 强制再禁用 |

## 7. 实测记录（v1.4.27）

环境：MuMu 第二实例（`adb connect 127.0.0.1:7555`），Android 12 / x86_64，
**全新安装**（此前没有本应用），用 `-Ppackage=full -Pabi=x86_64` 内嵌运行时打包。
先经 `POST /plugin/install` 装了 3 个真实第三方插件
（cost-meter / dream-skin / whale-widget）才做开关测试。

| # | 用例 | 结果 |
|---|---|---|
| 1 | 设置页停用 → 确认框列出 3 个包名 → 确认 | 日志 `phase=disabled`，`converged bundles 5 -> 2`，引擎 1939ms 后 healthy |
| 2 | 停用后该行文案 | 翻转为「恢复第三方插件 / 已停用 3 个 · 点此恢复」 |
| 3 | 停用期间装插件 | `409` + "…turned off; restore them first"，`dependencies` 未变 |
| 4 | 恢复途中（enabling）装插件 | `409` + "plugins are being restored…" |
| 5 | UI 恢复 | `2 -> 4`，bundle 顺序与停用前逐项一致，healthy 后 `restore confirmed` |
| 6 | HTTP `GET/POST` 往返 + 无 token | 状态一致；无 token `403` |
| 7 | **断点 A**：手写日志 `disabled`、manifest 仍是 5 条 → 冷启动 | 启动收敛 `5 -> 2`（等价于"写完日志就被杀"） |
| 8 | **断点 B**：期间卸载其中一个插件 → 日志改 `enabling`、manifest 2 条 → 冷启动 | `2 -> 4`，`skipped=dsh-whale-widget`，幽灵包没有写回，phase 自动推到 `enabled`（等价于"恢复途中被杀"） |
| 9 | 冷启动标志 `touch .plugin-killswitch.force` + force-stop | `force flag consumed`，`4 -> 2`，`reason=force-flag`，标志自动删除 |

`compileDebugKotlin` 与 `scripts/check-i18n.py` 均通过。

> 注：第 8 条同时验证了第 4 节的过滤规则 —— 被卸载的包不会以幽灵形式回到
> `bundles`，否则引擎解析失败，等于又造一次启动故障。

## 8. 实测记录（v1.4.28 · 白名单部分恢复）

同一 MuMu 实例，覆盖安装新包（含旧版日志文件，无 `allow` 字段 → 读出空白名单，
向后兼容）。起点：上一轮测试遗留的 `phase=disabled`（3 个第三方包）。

| # | 用例 | 结果 |
|---|---|---|
| 1 | 旧日志（无 `allow` 字段）读取 | `allow=[]`，行为与 v1.4.27 完全一致 |
| 2 | 恢复全部 | `phase=enabled`，`total 2 -> 5`，3 个包全回来 |
| 3 | 全部禁用 | `total 5 -> 2`，`allow=[]` |
| 4 | 停用期间 `POST /plugin/install` 更新 dsh-cost-meter | **`202 started`（v1.4.27 是 409）**；装完后 `thirdParty=[]`、`total=2` —— 装了不加载 |
| 5 | `enable-one dsh-cost-meter` | `allow=["dsh-cost-meter"]`，重启后 `thirdParty=["dsh-cost-meter"]`、`total=3`（2 内置 + 1 白名单） |
| 6 | `disable-one dsh-cost-meter` | `allow=[]`，重启后回 `total=2` |
| 7 | UI「已安装」页 | 3 行各带「单独启用」chip；点 dsh-cost-meter 的 chip → 文案翻转为「再停用」，桥侧 `allow` 同步写入 |
| 8 | UI banner | 新文案「…可在『已安装』逐个启用 · 点此全部恢复」 |
| 9 | 收尾恢复全部 | `phase=enabled`，`total=5` |

`compileDebugKotlin`、`scripts/check-i18n.py`（en/zh-rCN/de 三语键一致）均通过。

### 白名单语义（v1.4.28）

- `allow` 只在 `phase=disabled` 时有意义；恢复（enable）会忽略它，全量写回；
- 每次「全部停用」都从空白名单开始 —— 全禁就是全禁，白名单不跨轮次残留；
- 白名单包在停用期间被卸载 → 收敛时目标里不放（防幽灵包），日志里的 `allow`
  不清洗（与 `bundles` 记录同理，保留历史）；
- 崩溃收敛语义不变：改白名单也是先落日志、spawn 前收敛，幂等。
