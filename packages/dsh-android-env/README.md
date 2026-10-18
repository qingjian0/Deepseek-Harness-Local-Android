# dsh-android-env

DSH Mobile 的 Android 环境说明书，以 **ordered systemPrompt 注册项** 的形式交付。

## 为什么不再用 AGENTS.md

旧实现把 31KB 说明书 seed 成 `$DSH_HOME/AGENTS.md`，由 `@deepseek-ai/dsh-agent-instructions`
以 **user 角色消息** 注入。那条通道有三个不可靠之处：

- 只在文件读写时刷新；
- 会随 compaction 被压缩，长会话里说明书会被压没；
- 每会话只注入一次。

结果就是：会话跑久了 agent 退化成“陌生沙箱”，自己乱探测环境；而改文案常常不生效。

本插件改用 `@deepseek-ai/dsh-system-prompt` 的原语，**每步重新 assemble**：

| 内容 | 原语 | order |
| --- | --- | --- |
| 平台 / 工作区 | `systemPrompt.section()` | 44 |
| 工具链、`loop` DSL、扩展中心、构建 | `systemPrompt.section()` | 46 |
| 权限模式、LAN、端口边界、扩展排障、屏幕控制 | `systemPrompt.section()` | 48 |
| 工作约定、反欺诈（风险告知型） | `systemPrompt.section()` | 50 |
| UI 自动化工作流（可整块关掉） | `systemPrompt.section()` | 110 |
| UI 自动化反模式（可整块关掉） | `systemPrompt.section()` | 112 |
| 当前权限模式 + 已激活扩展（每步求值） | `systemPrompt.context()` | 15 |

**硬约束：所有 section 的 `complete` 必须为 `false`。** `assemble()` 中若同时存在
多于一个 `complete: true` 的 section 会直接抛
`multiple complete prompt sections are active`，整轮请求失败。

## 包结构

```
package.json            插件元数据（ESM；peerDependency: @deepseek-ai/dsh-system-prompt）
index.js                apply(ctx, config)：注册 section / context
sections/
  10-platform.md        order 44
  20-toolchain.md       order 46
  30-device.md          order 48
  40-agreements.md      order 50
  50-ui-automation.md   order 110
  55-ui-antipatterns.md order 112
```

APK 构建时由 Gradle 任务 `syncAndroidEnvAssets` 把本目录复制进
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

- `disabledSections`：要**整块关掉**的 section id，可选
  `platform` / `toolchain` / `device` / `agreements` / `ui-automation` /
  `ui-antipatterns` / `dynamic`。关闭 = 不挂载 effect，不是把正文清空。
  只想聊天、不做手机操作时，建议 `["ui-automation", "ui-antipatterns"]`
  ——这两块占了正文的一半以上。
- `p0Probe`：额外挂一个探针 section，用于确认通道可用（见下）。

配置在 `cordis.patch.yml` 里改成你自己的一份即可；插件的开关本身走 dsh 的插件清单
（WebUI 设置 → 插件 → 找到 `android-env` 这一行），禁用会写进 `cordis.patch.yml`
的 `disabled: true`，重启后依然生效。

## 可编辑

正文**每步从磁盘读取**（mtime 缓存 1s），改文件即生效，不用重启引擎。
查找顺序：

1. `$DSH_HOME/android-env/<文件名>`（用户覆盖，推荐在这里改）
2. `<engine>/android-plugins/android-env/sections/<文件名>`（随 APK 更新，会被覆盖）

想让某个 section 的正文留着自己的版本，把同名文件放进 `$DSH_HOME/android-env/` 即可。

## 验证（不可跳过）

1. 配置 `p0Probe: true`，重启引擎，开新会话。
2. 连续交互 **10 轮以上**（目的是触发 compaction）。
3. compaction 之后再跑 **2–3 轮**。
4. 每一轮 assistant 回复的第一行都必须是 `P0-PROBE-OK`。

只要有一轮丢失，就说明这条通道没有按预期每步生效（或 compaction 会动 system prompt），
此时应**回滚整个改动**，不要在此基础上继续叠功能。

同时确认：会话历史的 user 消息里**没有**这些正文（证明走的是 system prompt，而不是
AGENTS.md 的 user 消息通道）。

## 与权限规则的关系

本插件只负责“告诉模型事实与边界”。硬性拦截（ask / deny）不属于它：
当前引擎版本（`0.2.0-rc.2`）里没有 `@deepseek-ai/dsh-permission-rules`，也没有
`rules.yaml`；可用的强制手段是 `dsh-user-approval` 的 `ask` / `never` 策略、
`dsh-permission-presets` 的三档预设，以及 `dsh-hooks-claude-code` 的 `PreToolUse`
命令钩子。需要“真拦截”时走这三条里的任意一条，不要指望提示词。
