# DSH Mobile v1.4.0

> 本次是 agent 指令层重构：环境说明书从「每会话注入的 31KB `AGENTS.md` 应用段」
> 改成「构建产物 + system prompt section」，常驻体积降到约 1/7，
> `$DSH_HOME/AGENTS.md` 从此 100% 归用户。详见 [docs/INSTRUCTIONS.md](docs/INSTRUCTIONS.md)。

## 为什么改

v1.3.x 把说明书预写成 `$DSH_HOME/AGENTS.md`，靠 `dsh-agent-instructions` 注入。这条路线上：

- 它是 **user 角色**的 system-reminder，前缀明写「不覆盖 system / developer / 直接指令」；
- 指令链按「宽泛 → 具体」排序，项目里任何一个 `AGENTS.md` 都能盖过它；
- 整条链共享 64KiB 预算，溢出时**从最宽泛的开始丢弃**，它正好排第一；
- 引擎只加载这一个 user-global 文件，所以用户指令只能塞进同一份 → 标记分段、升级合并全靠约定。

## 新结构

```
tools/instructions/content/   单一作者源（policy / facts / index / reference / procedures）
    ↓ build.mjs（卡 6KB / 2000 tok）
app/src/main/assets/mobile/   产物：resident.md + docs×7 + skills×2
    ↓ MobileInstructions（boot 部署）
$DSH_HOME/mobile/ + $DSH_HOME/skills/
    ↓ mobile-instructions.mjs
system prompt section `mobile:environment`（order 100）
```

- **常驻**只放「缺失即出错」的事实与硬规则；
- **手册**（工具链、读屏 API、扩展、LAN、权限、预览）按需 `read`；
- **流程**（UI 自动化协议、端上构建 APK）做成 skill，目录里只出现 name + description。

## 体积

| | v1.3.3 | v1.4.0 |
|---|---|---|
| 每会话常驻 | 31,016B ≈ 8,000 tok | **4,458B ≈ 1,063 tok** |
| 指令链 64KiB 预算 | 被应用段占掉近一半 | 不占用，全留给用户与项目 |
| 增长约束 | 每版都涨（v1 → v48） | 构建期卡预算，CI 卡产物一致性 |

## 用户可见的变化

- **自定义指令**：设置页现在编辑整份 `AGENTS.md`，所见即所存；不再有「应用段/用户段」之分，
  环境说明已移到系统提示，升级永远不会碰这个文件。
- **自动迁移**：v1.3.2+ 的分段文件只保留你写的内容，原文件归档为
  `AGENTS.md.bak-legacy-<时间戳>`；没有 App 标记的自写文件原样不动；
  读不了的文件会跳过并在下次启动重试，绝不盲写。
- **更稳**：环境说明升级为系统提示的一部分，不会再被项目级 `AGENTS.md` 覆盖，
  也不会因为预算溢出被静默丢弃；文件缺失时插件会注入一段降级说明而不是留空。

## 维护与门禁

- 改文案只动 `tools/instructions/content/`，跑 `node tools/instructions/build.mjs`；
- `node tools/instructions/ci/check.mjs` 重新构建并逐字节比对，CI 每个 PR 都跑；
- 构建期拦住四类错误：超出预算、索引引用不存在、`resident.md` 里出现 `{{ }}`、
  技能 frontmatter 缺字段。

## 验证

已由 CI 证明：

- Kotlin 编译通过，arm64-v8a / x86_64 双架构 APK 构建成功；
- `node tools/instructions/ci/check.mjs` 通过（产物一致 + 预算 + 引用 + 插值守卫）；
- agent 插件 `node --check` 通过；
- i18n 三语门禁、三语 strings 无回归。

## 尚未真机验证（升级后请留意）

1. `engine.log` 里出现 `boot step mobile-instructions` 与 `plugin deployed: mobile-instructions.mjs`；
2. `$HOME/mobile/{resident.md,docs/,skills/}` 与 `$HOME/skills/ui-loop/SKILL.md` 均已落盘；
3. 新会话能回答设备事实；连续 10 轮并触发压缩后再问同一句仍能答对；
4. 从 v1.3.3 升级（有 / 无自定义指令两种设备）后 `AGENTS.md`、备份文件与设置页显示正确。

## 已知边界

- `content/` 的常驻段目前是中文（`docs/` 保留原英文正文）；要整份换英文只改这一层。
- section 变更会重写系统提示前缀（KV cache），所以只在发版时更新常驻内容。
- 不再渲染权限模式/已装扩展等动态占位符：常驻段给事实，模型按需查
  `DSH_ANDROID_PRIV_MODE` 与 `/ext/list`。

## 安装

覆盖安装即可，用户数据与配置保留。首次启动会自动迁移旧 `AGENTS.md` 并部署新的指令产物；
若迁移遇到读不了的文件会跳过（备份与原因写入 `engine.log`），下次启动重试。
