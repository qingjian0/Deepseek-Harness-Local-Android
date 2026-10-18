# dsh-android-env

DSH Mobile 的 Android 环境说明书，以 **ordered systemPrompt 注册项** 的形式交付。

## 为什么不再用 AGENTS.md

旧实现把 31KB 说明书 seed 成 `$DSH_HOME/AGENTS.md`，由 `@deepseek-ai/dsh-agent-instructions`
以 **user 角色消息** 注入。那条通道有三个不可靠之处：

- 只在文件读写时刷新；
- 会随 compaction 被压缩，长会话里说明书会被压没；
- 每会话只注入一次。

结果就是：会话跑久了 agent 退化成“陌生沙箱”，自己乱探测环境；而改文案常常不生效。

本插件改成**两层**：每步都要的少数事实常驻，其余按需加载。

### 第一层：core（常驻，约 2.0k 字符）

用 `@deepseek-ai/dsh-system-prompt` 的原语，**每步重新 assemble**：

| 内容 | 原语 | order |
| --- | --- | --- |
| 平台 / 工作区 | `systemPrompt.section()` | 44 |
| 已就绪能力（PATH、闸门、扩展中心、端口） | `systemPrompt.section()` | 46 |
| 工作约定 + 反欺诈边界 | `systemPrompt.section()` | 50 |
| 当前权限模式 + 已激活扩展（每步求值） | `systemPrompt.context()` | 15 |

**硬约束：core section 的 `complete` 必须为 `false`。** `assemble()` 中若同时存在
多于一个 `complete: true` 的 section 会直接抛
`multiple complete prompt sections are active`，整轮请求失败。

### 第二层：skill `android-env`（按需，约 9.3k 字符）

`loop`/`scr` 全表、UI 自动化协议与反模式、屏幕控制、扩展排障、端上构建、局域网。
走 `ctx.skills.registerProvider()`：system prompt 里只留 **一条 name+description**
（约 40 token，按 digest 去重、每会话只发一次），正文只在模型主动调 `skill` 工具
读取时才进上下文。

core 的最后一段明确写了“要动手机就先 `skill` 加载 `android-env`”，这就是发现入口。

## 包结构

```
package.json            插件元数据（ESM；peerDependency: @deepseek-ai/dsh-system-prompt）
index.js                apply(ctx, config)：注册 core section / context / skill provider
sections/
  10-platform.md        core  order 44
  20-core-tools.md      core  order 46
  40-agreements.md      core  order 50
  ref/00-ui-automation.md      ┐
  ref/10-ui-antipatterns.md    │
  ref/20-device.md             ├ skill body（按顺序拼接，按需加载）
  ref/30-toolchain.md          │
  ref/40-lan.md                ┘
```

APK 构建时由 Gradle 任务 `syncAndroidEnvPlugin` 把本目录复制进
`assets/android-plugins/android-env/`，装机后由 `EngineConfig` 部署到
`<engine>/android-plugins/android-env/`，再由 `android-overlay.yml` 的 insert 行加载。

## 配置

```yaml
- insert:
    - id: android-env
      name: ./android-plugins/android-env/index.js
      config:
        disabledSections: []   # 见下
        p0Probe: false         # 通道验证用，正式运行保持 false
```

- `disabledSections`：要摘掉的 id，可选
  `platform` / `core-tools` / `agreements` / `dynamic` / `skill`。
  关闭 = 不挂载 effect（`skill` = 不注册 provider，catalog 里直接消失），
  不是把正文清空。
- `p0Probe`：额外挂一个探针 section，用于确认通道可用（见下）。

配置在 `cordis.patch.yml` 里改成你自己的一份即可；插件的开关本身走 dsh 的插件清单
（WebUI 设置 → 插件 → 找到 `android-env` 这一行），禁用会写进 `cordis.patch.yml`
的 `disabled: true`，重启后依然生效。

## 可编辑

正文**每步从磁盘读取**（mtime 缓存 1s），改文件即生效，不用重启引擎。
查找顺序：

1. `$DSH_HOME/android-env/<文件名>`（用户覆盖，推荐在这里改；`ref/` 下的写
   `$DSH_HOME/android-env/ref/<文件名>`）
2. `<engine>/android-plugins/android-env/sections/<文件名>`（随 APK 更新，会被覆盖）

**不要**直接改第 2 处：每次开机都会用 APK 里的版本覆盖它。删掉
`$DSH_HOME/android-env/` 下的同名文件即恢复包内默认。

## 验证

### 便宜且硬的验收（每次改动都做）

1. 装包、重启引擎，看 `engine.log` 里是否有一行
   `[android-env] core 3/3 sections, skill on`。
2. 发一句话，确认 system prompt 里有 core 正文（`bionic libc` 等），且**没有**
   `scroll_find` / `扩展故障排查` 这类只属于手册的内容。
3. 问一句“读一下屏幕”，确认模型会先调用 `skill` 工具加载 `android-env`。

离线也可重跑（不需要设备）：用引擎自带的 cordis + `dsh-system-prompt` + `dsh-skill`
真跑一次 `assemble()` 和 `skills.get()`，断言 core 约 2.0k 字符、skill 约 9.3k 字符、
所有 section `complete === false`。

### 通道回归（改 assemble 相关代码时才需要）

1. 配置 `p0Probe: true`，重启引擎，开新会话。
2. 连续交互 **10 轮以上**（目的是触发 compaction），compaction 后再跑 **2–3 轮**。
3. 每一轮 assistant 回复的第一行都必须是 `P0-PROBE-OK`。

只要有一轮丢失，就说明这条通道没有按预期每步生效，此时应**回滚**，不要在此基础上
继续叠功能。同时确认：会话历史的 user 消息里**没有**这些正文（证明走的是 system
prompt，而不是 AGENTS.md 的 user 消息通道）。

## 与权限规则的关系

本插件只负责“告诉模型事实与边界”。硬性拦截（ask / deny）不属于它：
当前引擎版本（`0.2.0-rc.2`）里没有 `@deepseek-ai/dsh-permission-rules`，也没有
`rules.yaml`；可用的强制手段是 `dsh-user-approval` 的 `ask` / `never` 策略、
`dsh-permission-presets` 的三档预设，以及 `dsh-hooks-claude-code` 的 `PreToolUse`
命令钩子。需要“真拦截”时走这三条里的任意一条，不要指望提示词。
