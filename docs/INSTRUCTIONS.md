# Agent 指令层：构建产物 + 系统提示段

> v1.4.0 起，App 的「环境说明书」不再写进 `$DSH_HOME/AGENTS.md`，而是由
> `tools/instructions/` 编译到 assets，再由一个插件注册成 system prompt section。
> 本文记录为什么改、新结构长什么样、怎么维护，以及还没验证的部分。

## 1. 旧方案的问题

v1.3.x 把说明书预写成 `$DSH_HOME/AGENTS.md`（31KB），靠 `dsh-agent-instructions`
注入。这条路线上有四个结构性问题：

| 问题 | 具体表现 |
|---|---|
| 权威层级最低 | 该文件以 **user 角色**的 `<system-reminder>` 注入，前缀明写「不覆盖 system / developer / 直接指令」 |
| 优先级最低 | 指令链按「宽泛 → 具体」排序且更具体的优先，项目里任何一个 `AGENTS.md` 都能盖过它 |
| 预算上最先牺牲 | 整条链共享 64KiB（`dsh-base` 的 `maxBytes: 65536`），超限时**从最宽泛的开始丢弃**，它正好排第一 |
| 与用户内容同文件 | 引擎只加载这一个 user-global 文件，所以用户指令只能写进同一份 → 标记分段、升级合并、边界情况全靠约定 |

此外它还是「每会话固定 ~8k tokens」，而其中 72% 是参考手册与流程（工具链、读屏 API、
UI 自动化协议），并不是每轮都必须知道的东西。

## 2. 新结构

```
tools/instructions/
  content/
    policy/rules.md          硬规则（系统提示完整性、反诈、端口、持久化注入…）
    facts/environment.md     设备事实（bionic、路径、已装工具、权限模式、端口）
    index.md                 索引：哪个场景读哪个 docs，哪个流程调哪个 skill
    reference/*.md           按需手册（工具链、读屏 API、扩展、LAN、权限、预览）
    procedures/*.md          技能源码（YAML frontmatter + 正文）
  build.mjs                  编译 + 预算/引用/插值校验
  ci/check.mjs               CI：重构建并与提交的 assets 逐字节比对

app/src/main/assets/mobile/   构建产物（提交进仓库，APK 直接打包）
  resident.md                常驻段 → system prompt section
  docs/*.md                  按需手册 → 模型用 read 打开
  skills/<id>/SKILL.md       技能 → 只出现 name + description，调用时才载入正文
```

运行时由两段协作：

1. `app/src/main/assets/android-plugins/mobile-instructions.mjs`（overlay 注入）
   把 `$DSH_HOME/mobile/resident.md` 注册成 section `mobile:environment`，
   `order: 100`（身份/人设 0 之后、策略 500 之前）。`text` 是函数、同步求值，
   用 `statSync` + mtime 缓存，文件缺失时给**降级段**而不是空字符串。
2. `MobileInstructions`（Kotlin，替换 `AgentContextSeed`）在 boot 时把 assets 部署到
   `$DSH_HOME/mobile/` 与 `$DSH_HOME/skills/`，并把旧 `AGENTS.md` 迁移成纯用户文件。

## 3. 体量

| | v1.3.3 | v1.4.0 |
|---|---|---|
| 每会话常驻 | 31,016B ≈ 8,000 tok | **4,458B ≈ 1,063 tok** |
| 指令链 64KiB 预算 | 被应用段占掉近一半 | 不占用，全留给用户与项目 |
| 手册 / 流程 | 全常驻 | 7 篇 docs + 2 个 skill，按需 |
| 增长约束 | 每版都涨（v1 → v48） | `build.mjs` 卡 6KB / 2000 tok |

> token 数按中英混排粗估（CJK ≈ 1.5 字/token，ASCII ≈ 3.6 字符/token），不是计费实测。

## 4. 维护方式

改文案只动 `content/`，然后：

```sh
node tools/instructions/build.mjs       # 写 app/src/main/assets/mobile
node tools/instructions/ci/check.mjs    # 重构建 + 逐字节比对（CI 跑这个）
```

构建期会拦住四类错误：

- `resident.md` 超过 6144B / 2000 tok（要放宽必须显式改常量）；
- `index.md` 引用了不存在的 docs 或 skill；
- `resident.md` 里出现 `{{ }}`（引擎会当变量插值，未知变量会让每轮渲染失败）；
- 技能 frontmatter 缺 `name` / `description`。

产物必须提交（APK 直接打包 assets），`ci/check.mjs` 保证它与源码一致，防止有人手改。

## 5. 迁移（v1.3.3 → v1.4.0）

`MobileInstructions.ensure()` 在 boot 时按情况处理旧文件：

| 旧文件形态 | 处理 |
|---|---|
| v1.3.2+ 分段文件（有用户段标记） | 只保留用户段正文，原文件归档为 `AGENTS.md.bak-legacy-<时间戳>` |
| 只有旧种子标记（无用户段） | 归档后清空，留一行指向备份的注释 |
| 没有 App 标记（用户自写） | **原样不动** |
| 存在但读不了（旧 uid / SELinux 标签） | 跳过并下个 boot 重试，绝不盲写 |
| 本 build 没打包插件 | 不迁移，保留旧文件（避免「旧的清了、新的不生效」） |

安全约束：归档失败即中止改写；写盘走 temp + rename；`$DSH_HOME/skills` 与
`$DSH_HOME/mobile/docs` 的清理都按 ledger 只删自己部署过的文件。

## 6. 取舍与已知边界

- **section 不吃指令链预算**：所以插件自带 `maxBytes`（默认 7168B）并在段落边界截断告警。
- **section 变更会重写系统提示前缀**（KV cache）：所以 resident 只在发版时变，用户内容不进这里。
- **docs / skills 依赖模型主动 read / 调用**：`index.md` 每条都写了触发场景，仍存在「模型没读就动手」的可能；缺失代价高的规则一律留在常驻段。
- **不再有动态占位符**：旧种子会渲染权限模式与已装扩展，现在改为常驻段给事实 + 让模型按需查
  `DSH_ANDROID_PRIV_MODE` / `/ext/list`。这是刻意取舍：避免 section 每步变化破坏缓存。
- **内容语言**：`content/` 的 policy/facts/index 目前是中文（`docs/` 保留原英文正文）。
  要整份换英文只改这一层，不需要动 Kotlin。

## 7. 验证状态

已由 CI 证明：Kotlin 编译、双架构 APK 构建、`ci/check.mjs`（产物一致性 + 预算 + 引用 +
插值守卫）、插件 `node --check`、i18n 三语门禁。

尚未在真机验证（升级包实测清单）：

1. `engine.log` 出现 `boot step mobile-instructions` 与 `plugin deployed: mobile-instructions.mjs`；
2. `$HOME/mobile/{resident.md,docs/,skills/}` 与 `$HOME/skills/ui-loop/SKILL.md` 落盘；
3. 新会话能回答设备事实；连续 10 轮 + 触发 compaction 后再问同一句仍能答对（验证 section 不被压缩吞掉）；
4. 从 v1.3.3 升级（有/无用户指令两种设备）后 `AGENTS.md` 内容、备份文件、设置页显示均正确。
