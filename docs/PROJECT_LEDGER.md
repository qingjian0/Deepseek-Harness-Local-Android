# DSH Project Ledger

> 唯一事实源。更新日期：2026-11-28。所有状态以本文件记录的 Git ref、代码、测试和 CI 证据为准；对话计划不算实现证据。

## 1. 当前基线

- 仓库：`qingjian0/Deepseek-Harness-Local-Android`
- 默认分支：`main`
- 当前集成基线：`dev`
- 本次审计时 `dev` HEAD：`453f5347f21646bd7e0ac0b1d79719808605daaa`
- `dev` 相对 `main`：领先 69 个提交，落后 0 个提交；不能把二者视为相同代码。
- 本次只新增本台账；没有合并 PR、删除分支或改动应用包名。

## 2. 功能登记表

| 功能域 | 已确认的代码事实 | 状态 | 后续处理 |
|---|---|---|---|
| 运行时服务端口 | `dev` 有 `engine/ServicePortAllocator.kt` 与 `ServicePortAllocatorTest.kt`；HEAD 提交信息为“P0: unify DSH runtime service port allocation”，说明六服务端口映射已集成 | 已集成，待真机验收 | 只在现有 allocator 上修缺陷；不再另建 EnginePortManager / ServicePortConfig |
| 端口清理 | `PortReclaim.kt` 已存在，负责释放监听端口及兜底清理 | 已有实现 | 与 allocator 分工明确，不重复实现分配器 |
| 开发者模式 | `DeveloperModePrefs.kt`、`DeveloperOptionsActivity.kt` 已存在于 main/dev | 基础功能已存在 | 先核对七击入口、菜单收纳和权限边界；只补缺项 |
| 权限管理 | `PermissionCenterActivity.kt`、`PermissionListActivity.kt` 已存在；包含运行身份、系统能力、能力开关和权限清单入口 | 基础功能已存在 | 不新建第二套权限中心；逐项核对需求和授权校验 |
| 用户 Prompt | `UserPromptRepository` 为唯一持久化源；`UserPromptContextAdapter` 将其渲染到 AGENTS.md；Settings、编辑页、`/dev/prompt` API、备份恢复全部走同一仓储；迁移/保存/开关/恢复共用写锁 | PR #44 已提交 66 commits ahead，未合并；无对应 Actions run/check | CI 后再合并；真机验证迁移、开关、AI API 写入、升级、备份恢复 |
| 环境 Prompt 开关 | PR #44 在 Prompt 管理器中复用 PR #9 的既有偏好键 `dsh_ui/agent_default_prompt_enabled`；由 `AgentContextSeed` 保留用户段、仅切换 App 环境段 | PR #44 已集成代码，PR #9 不应再单独合并 | PR #9 与 #44 有重叠，#44 作为唯一集成线；真机验收关闭/开启后用户指令不丢失 |
| 用户 Prompt 独立存储 | 已从旧 PR #13 选择性移植到 PR #44；补齐 `/dev/prompt` 读写统一与偏好写锁；旧 PR 还混有 MCP/HID 性能与诊断改动 | PR #44 待 CI/设备验收 | 不合并旧 PR #13 整体；以 PR #44 为唯一用户 Prompt 实现线 |
| 系统默认 Prompt | `PromptManagementActivity` 通过 `EngineConfig.ensureAndroidOverlay()` 覆盖真实 `system-prompt` 配置：Harness identity 开关、runtime context 开关、persona prefix/suffix；固定 identity 可关闭并用自定义 prefix 替代。`agent-instructions` 的 maxBytes/maxSourceBytes 也写入最终 overlay | PR #44 已接入启动配置和开发者模式 UI；仍待构建/设备验收 | 继续区分上游固定 identity、persona、运行时上下文和其他插件自有 sections；不声称任意插件 section 都可编辑 |
| DSH-GPT Hook | `dev` 有 `HookAgentSecrets.kt`；另有 ADR 说明 Hook 可选插件化 | 已有部分实现/设计 | 不重新启动旧 Hook 主线；先审查当前范围与用户已取消项 |
| APK 直接下载 | PR #26 仍开放且为 Draft，目标 dev | 待集成/验收 | 仅验证工作流产物，不重做现有构建流水线 |
| 应用包名 | main 当前 `applicationId = app.dsh.mobile`；PR #42 提议改为 `io.github.qingjian0.dsh.mobile`，仍为 Draft，CI/真机验证未完成 | 决策未定，禁止自动合并 | 不更改包名；先确认用户最终包名与数据迁移要求 |

## 3. 当前开放 PR 记录（审计时）

| PR | 内容 | 目标 | 状态/处理 |
|---|---|---|---|
| #42 | applicationId 改为 io.github.qingjian0.dsh.mobile | main | Draft；包名决策未定，暂缓 |
| #26 | CI 直接下载 APK | dev | Draft；待验证真实 APK 附件 |
| #15 | 七击开发者设置及其他工具/性能变更 | dev | 已有开发者模式基础实现；仅选择性审查，不整支重复合并 |
| #13 | 用户 Prompt 独立存储 + MCP/HID 性能诊断等混合变更 | dev | 不整体合并；Prompt 子集已单独进入 #44，其余独立评估 |
| #9 | 内置环境 Prompt 注入开关 | main | 功能键/语义已由 PR #44 复用；不要单独合并以免重复覆盖 AgentContextSeed |
| #16 | fresh location bridge | main | Draft；设备验证待完成 |
| #4 | Fork 缺少 RELEASE_* 时跳过发布 | main | 独立 CI 修复，待决定是否纳入集成线 |
| #5 | PTY JNI NULL 防护 | main | 独立安全修复，待核对 dev 是否已包含 |
| #3 | 应答成本纪律 | main | 指令/策略改动，待核对 dev 是否已包含 |
| #7 | OCR 阈值按包名配置 | main | 功能改动，待核对 dev 是否已包含 |

## 4. 已识别的重复/过期分支

- `feature/permission-prompt-management` 与当前 `dev` 内容完全一致（compare：ahead 0 / behind 0）；不要再合并或重做。
- `fix/unified-service-port-map` 相对 `dev` 落后 30 个提交；其端口统一工作已由 dev 的后续提交取代，不要回合并旧分支。
- `feature/adaptive-engine-ports`、`feature/developer-prompt-management`、`feature/hidden-developer-options`、`feature/prompt-injection-toggle`、`feat/user-prompt-independent-storage` 均与 dev 分叉；只允许审查差异并择优移植，不允许整支合并。
- `fix/package-port-autoselect` 是一条包含多个功能域的旧分叉，包含端口、Prompt、远程访问等变更；不得整体合并，以免重复覆盖 dev 现有实现。
- 仓库分支数较多；本次不做批量删除，避免误删仍有独立提交的分支。

## 5. 开发与验收纪律

1. 开发前先读本台账，再检查目标分支当前代码、测试和 PR；不得仅凭旧对话判断状态。
2. 每个功能域只保留一个权威实现入口；新增实现前必须说明现有实现为何不能复用。
3. “讨论过/有分支/有 PR/CI 通过/真机通过”是不同状态，不得混称完成。
4. 变更优先进入 `dev`；除非明确要求，不直接向 `main` 推送功能开发。
5. 禁止无依据重建分支、重复创建 PR、重复实现同名功能。
6. 不批量删除分支；先确认已合并、无独有提交且无引用后才允许清理。
7. 端口分配、Prompt、权限、开发者模式的后续工作必须扩展现有实现，不另起平行体系。
8. 每次提交后更新本台账的状态、提交 SHA、测试结果和未完成项。

## 6. 当前进行中

- PR #44：`integrate/user-prompt-storage` → `dev`，现包含用户 Prompt 独立存储、上游 system-prompt 配置、环境说明开关、会话/工具指导 sections、备份恢复；PR 链接：https://github.com/qingjian0/Deepseek-Harness-Local-Android/pull/44。
- PR #44 当前 head 有 66 个提交领先 dev、0 个落后；GitHub Actions 最近一次仓库运行是 2026-11-25，之后未见新的每日 schedule 或 PR #44 run/check。工作流文件说明 fork 可能需要在 Actions → android-build 页面启用。
- 最近一次 main 基线运行中，两个 ABI 的 APK 构建均成功；`jvm-behaviour` 单独失败，原因是 Gradle 无法从 Google/Maven Central/Plugin Portal 解析 `org.jetbrains.kotlin.android:1.9.24`，随后编译 classpath 为空。这是依赖解析失败，不是本次 Prompt 测试失败；但 PR #44 本身尚无构建结果。
- PR #44 静态审查已发现并修复一致性风险：偏好写操作共用迁移锁；`/dev/prompt` 统一走独立仓储；备份恢复结果在 UI 提示；环境开关复用 PR #9 的原有偏好键；系统默认配置和 session/tool sections 接入实际启动/组装链路。
- Prompt 管理已接入真实消费链路：上游配置由最终 Android overlay 覆盖；session/tool 文本由 `android-prompt-guidance.mjs` 通过 `ctx.systemPrompt.section()` 注入；环境说明由 `AgentContextSeed` 的既有开关控制；用户段由 `UserPromptRepository` 管理。备份同时包含 user prompt 与 prompt-manager settings。

## 7. 验收标准

- 端口：六服务映射唯一、冲突回退确定、配置持久化、重启稳定；单测通过并有目标 Android 真机日志。
- Prompt：环境/用户/上游默认/会话/工具 Prompt 职责明确；默认 Prompt 可查看、编辑、删除；用户数据可迁移、导入导出；无重复注入。
- 权限：所有高风险 bridge/API 在服务端逐次校验 CapabilityGate；UI 开关不能替代授权检查；拒绝/撤销立即生效。
- 开发者模式：七击入口有效；关闭时高级入口隐藏；隐藏 UI 不得构成安全边界。
- APK：能从明确的 CI/Release 页面直接取得真实 APK，并记录构建 SHA、签名身份和 ABI。
- 真机：至少覆盖冷启动、后台杀进程重启、端口被占用、权限撤销、Prompt 修改保存与重启恢复。

## 8. 审计边界

本次通过 GitHub 仓库元数据、main/dev 文件树、指定源码、开放 PR 和分支比较完成静态盘点；未执行 Gradle 构建、CI 重跑或 OPPO 真机测试。因此标记为“已集成”的功能不等于已完成设备验收。
