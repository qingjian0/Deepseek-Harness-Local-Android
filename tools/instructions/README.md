# tools/instructions

把 App 的「环境说明书」从**每会话注入的 31KB `AGENTS.md` 应用段**，改成**构建产物**：

- 常驻段 → 一个 system prompt section（`app/src/main/assets/android-plugins/mobile-instructions.mjs`）
- 低频手册 → `assets/mobile/docs/*.md`，模型需要时 `read`
- 流程 → `assets/mobile/skills/<id>/SKILL.md`，目录里只有 name + description，调用时才载入正文

`$DSH_HOME/AGENTS.md` 因此变成**纯用户文件**：没有标记、没有分段、没有升级合并逻辑。

## 实测

| | 改前（v1.3.3） | 改后 |
|---|---|---|
| 每会话常驻 | 31,016B ≈ 8,000 tok（整份 AGENTS.md） | **4,458B ≈ 1,063 tok** |
| 指令链 64KiB 预算 | 应用段占掉近一半 | 不占用，全留给用户与项目 |
| 手册 / 流程 | 全常驻 | 7 篇 docs + 2 个 skill，按需；缺文件时插件给降级段 |
| 增长约束 | 每版都涨（v1 → v48） | 构建卡 6KB / 2000 tok；CI 卡手改产物 |

## 目录

```
content/
  policy/rules.md          常驻：硬规则（含系统提示完整性）
  facts/environment.md     常驻：设备事实
  index.md                 常驻：文档索引（场景 → 路径）
  reference/*.md           按需手册
  procedures/*.md          技能源码（YAML frontmatter + 正文）
build.mjs                  编译到 app/src/main/assets/mobile + 预算/引用校验
ci/check.mjs               CI 门禁：重构建并与提交的 assets 逐字节比对
```

## 用法

```sh
node tools/instructions/build.mjs       # 写 app/src/main/assets/mobile
node tools/instructions/ci/check.mjs    # 重构建 + 逐字节比对（CI 跑这个）
```

产物**提交进仓库**，因为 APK 直接打包 assets（不再生成 manifest.json）。`ci/check.mjs` 保证提交的产物与 `content/` 一致，同时跑预算与引用检查——要放宽预算必须显式改 `build.mjs` 顶部常量。

## 运行时

`MobileInstructions.ensure()`（启动链路里 `step("mobile-instructions")`）在每次启动时：

1. `assets/mobile/resident.md` → `$DSH_HOME/mobile/resident.md`
2. `assets/mobile/docs/*.md` → `$DSH_HOME/mobile/docs/`（并清掉上一版多出来的页）
3. `assets/mobile/skills/<id>/SKILL.md` → `$DSH_HOME/skills/<id>/SKILL.md`
   （这是 skill provider 的默认用户根，所以不需要改 profile）
4. 迁移 `$DSH_HOME/AGENTS.md`：
   - 旧的分段文件（v1.3.2–1.3.3）→ 只保留用户段正文，原文件备份为 `AGENTS.md.bak-legacy`
   - 只有旧种子标记 → 备份后清空（内容已由 section 接管）
   - 没有任何 App 标记 → 判定为用户自写，原样不动

插件由 `EngineConfig` 的 overlay 以 `./android-plugins/mobile-instructions.mjs` 挂载，
配置 `{ file: mobile/resident.md, order: 100 }`；section 名是 `mobile:environment`，
排在身份/人设（0）之后、策略（500）之前。

## 为什么不写进 AGENTS.md

引擎把 `$DSH_HOME/AGENTS.md` 当作 **user 消息**注入，前缀明写「不覆盖 system / developer / 直接指令」；
它的优先级低于任何项目级 `AGENTS.md`，且 64KiB 预算溢出时**从最宽泛的开始丢弃**，它正好排第一。
环境事实与硬规则需要的是强层、静态、随版本走——那正是 system prompt section 的语义。

## 取舍

- section 不受指令链预算约束，所以插件自带 `maxBytes`（默认 8KB）并在超限时截断告警。
- section 变更会重写系统提示前缀（KV cache），所以 resident 只在发版时变；用户内容不进这里。
- docs / skills 依赖模型主动 `read`／调用，因此 `content/index.md` 每条都写了触发场景。
- 内容目前是中文（`docs/` 保留原英文正文）；`content/` 是纯 markdown，要整份换成英文只改这一层。
