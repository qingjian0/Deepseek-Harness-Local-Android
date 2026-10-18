# dsh-android-env

DSH Mobile 的 Android 环境说明书。**三层**：极小常驻（core）→ 一条 catalog（skill）
→ 按主题精读（`ref/*.md`，模型用 `read` 自己取）。

## 为什么不再用 AGENTS.md

旧实现把说明书 seed 成 `$DSH_HOME/AGENTS.md`，由 `@deepseek-ai/dsh-agent-instructions`
以 **user 角色消息** 注入。那条通道有三个不可靠之处：

- 只在文件读写时刷新；
- 会随 compaction 被压缩，长会话里说明书会被压没；
- 每会话只注入一次。

结果就是：会话跑久了 agent 退化成“陌生沙箱”，自己乱探测环境；而改文案常常不生效。
把同一份 10k 字符全塞进 systemPrompt 能解决可靠性，但**每个请求都付全量**——那是
拿 token 换省事，不是设计。

## 三层结构

| 层 | 内容 | 何时付费 |
| --- | --- | --- |
| **core** | `sections/10-core.md` — 平台事实、工作区、闸门、端口边界、手册入口（约 720 字符） | 每个请求 |
| **catalog** | skill `android-env` 的一条 name + description | 每会话一次（被 compaction 隐藏后引擎自动补发） |
| **主题文件** | `sections/ref/{screen,ui-automation,tools,lan,policy}.md` | 模型 `read` 哪个才付哪个 |

skill 加载时**只返回一份索引**（约 320 字符），并通过 `resourceBase` 告诉模型正文
目录在哪；真正的命令表/协议由模型用 `read` 工具按需读取，且 `read` 支持
`offset`/`limit`，大文件还能分段读。

这就是“按需”的落点：常驻 ~720 字符；只配局域网时再付 ~600 字符；做 UI 自动化时
才把 3–4k 的命令表拉进来。

**硬约束：core section 的 `complete` 必须为 `false`。** `assemble()` 中若同时存在
多于一个 `complete: true` 的 section 会直接抛
`multiple complete prompt sections are active`，整轮请求失败。

catalog 的可靠性也查过源码：`dsh-tool-skill` 用 `session.surface.nodes` 判断那条目录
消息是否还在可见历史里，**被 compaction 隐藏后会重新发一次**，所以它不会像 AGENTS.md
那样被压没。

## 包结构

```
package.json            插件元数据（ESM；peerDependency: @deepseek-ai/dsh-system-prompt）
index.js                apply(ctx, config)：注册 core section / context / skill provider
sections/
  10-core.md            core（order 44）
  ref/screen.md         读屏、点击、手势、截图、OCR、通知与交互纪律
  ref/ui-automation.md  loop 计划 DSL、校验语义、执行前沿、反模式
  ref/tools.md          工具链、扩展中心与排障、端上构建、给用户看页面
  ref/lan.md            局域网访问 WebUI、端口边界
  ref/policy.md         微信/QQ/银行/支付类：风险告知与用户确认
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

- `disabledSections`：可摘 `core` / `dynamic` / `skill`。关闭 = 不挂载
  （`skill` = 不注册 provider，catalog 里直接消失），不是把正文清空。
- `p0Probe`：额外挂一个探针 section，用于确认通道可用（见下）。

配置在 `cordis.patch.yml` 里改成你自己的一份即可；插件级开关走 dsh 插件清单
（WebUI 设置 → 插件 → `android-env` 行），禁用会写进 `cordis.patch.yml` 的
`disabled: true`，重启后依然生效。

## 可编辑

正文**每步从磁盘读取**（mtime 缓存 1s），改文件即生效，不用重启引擎。查找顺序：

1. `$DSH_HOME/android-env/<文件名>`（用户覆盖，推荐在这里改；`ref/` 下的写
   `$DSH_HOME/android-env/ref/<文件名>`）
2. `<engine>/android-plugins/android-env/sections/<文件名>`（随 APK 更新，会被覆盖）

**不要**直接改第 2 处：每次开机都会用 APK 里的版本覆盖它。删掉
`$DSH_HOME/android-env/` 下的同名文件即恢复包内默认。

## 验证

### 便宜且硬的验收（每次改动都做）

1. 装包、重启引擎，看 `engine.log` 是否有一行
   `[android-env] core 1/1 sections, skill on, 5 topics`。
2. 发一句话，确认 system prompt 里有 core（`bionic libc` 等），且**没有**
   `scroll_find` / `扩展故障排查` 这类只属于手册的内容。
3. 问一句“读一下屏幕”，确认模型先 `skill` 加载 `android-env`、拿到 base 目录，
   再 `read` `screen.md`（而不是一次把五个文件全读了）。

离线也可重跑（不需要设备）：用引擎自带的 cordis + `dsh-system-prompt` + `dsh-skill`
真跑一次 `assemble()` 与 `skills.get()`，断言 core ~720 字符、索引里列出全部主题、
`resourceBase` 指向的目录里文件都可读。

### 通道回归（改 assemble 相关代码时才需要）

1. 配置 `p0Probe: true`，重启引擎，开新会话。
2. 连续交互 **10 轮以上**（触发 compaction），之后**再跑 2–3 轮**。
3. 每一轮回复的第一行都必须以 `P0-PROBE-OK` 开头。

有一轮丢失就回滚，不要在此基础上继续叠功能。

## 与权限规则的关系

本插件只负责“告诉模型事实与边界”。硬性拦截（ask / deny）不属于它：
当前引擎版本（`0.2.0-rc.2`）里没有 `@deepseek-ai/dsh-permission-rules`，也没有
`rules.yaml`；可用的强制手段是 `dsh-user-approval` 的 `ask` / `never` 策略、
`dsh-permission-presets` 的三档预设，以及 `dsh-hooks-claude-code` 的 `PreToolUse`
命令钩子。需要“真拦截”时走这三条里的任意一条，不要指望提示词。
