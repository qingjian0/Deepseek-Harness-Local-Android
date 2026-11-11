# DSH Agent 上下文与内存预算专项改造

状态：RFC / 审计阶段  
目标分支：`feat/agent-context-budget`（从 `dev` 创建）  
基线：`dev`，当前审计提交 `dc3b588798fe34e6791c19ae5e7e445699be0362`  
范围：Android 端运行的 `@deepseek-ai/dsh` Agent Runtime；不修改 `main`，不改变上游稳定运行时行为。

## 1. 目标与非目标

目标是让有限上下文和有限内存的 Android 设备能够稳定完成轻量、多轮、带工具的 Agent 任务，而不是单纯把上下文窗口从 8K 强行提高到 16K。

本专项必须同时解决：

1. 模型请求中系统提示、工具描述、运行时上下文、历史消息和工具结果的 token 预算。
2. Agent 任务状态与模型上下文解耦，模型上下文可压缩、可重建，任务不能因此丢失。
3. 工具可见性与工具执行能力分离，避免每次请求无差别暴露全部工具定义。
4. Android 进程内存、KV Cache、上下文长度之间的联动约束。
5. 通过真实请求与真机数据验证收益，不以 JSON 字节数代替 token 数，不以理论内存代替实测峰值。

非目标：

- 不自研替换整个 DeepSeek Harness Agent Loop。
- 不把 8K/16K 写成所有模型通用的硬编码默认值。
- 不把完整工具执行历史、长期记忆或任务状态塞进 system prompt。
- 不在没有基准测试的情况下承诺 tok/s、可用上下文或内存节省比例。
- 不直接向 `main` 提交或合并。

## 2. 当前仓库审计发现

### Android Fork

- 仓库：`qingjian0/Deepseek-Harness-Local-Android`
- `dev` 与 `main` 当前指向同一提交；本分支从 `dev` 独立创建。
- `scripts/build-runtime.sh` 默认 DSH 版本为 `0.2.2-rc.1`。
- `scripts/measure-tool-schema.mjs` 已能从 DSH append-only zstd session log 重建 request/header，并统计每次请求的工具 schema UTF-8 字节数。
- 该测量脚本明确没有 tokenizer，因此不能把 schemaBytes 换算成 tokens，也不能仅凭它断言“工具定义占用了多少 token”。
- 构建链已有 PTC runtime 相关产物校验，但“PTC 执行器是否被实际默认 Agent preset 选中”仍须对当前锁定版本和打包 runtime 做验证。
- `AgentContextSeed.kt` 会维护全局 `AGENTS.md` 种子；它属于提示词输入来源之一，需要纳入长度审计，但不能未经评估就删除用户指令或改变现有用户段保护语义。
- `ContextCap.kt` 是 Android 设备情境/提醒能力，不是 LLM token 上下文预算器；不得混用或把它改造成 token manager。

### 上游 Harness 能力（需按锁定版本复核）

上游当前架构文档已提供若干可复用能力：

- `dsh-system-prompt`：按 agent 作用域组装提示词段、动态上下文和工具 schema。
- 新版工具执行器支持 native / PTC 两种模型可见形态；PTC 通过单一 `run_code` 入口和生成 SDK 呈现工具能力。旧的 `dsh-agent-tool-presentation` 已标记弃用，新配置应使用对应 executor。
- `dsh-compaction-basic`：在 token 压力上升时自动压缩较早历史。
- `dsh-compaction-tool-result-pruner`：在压缩过程中裁剪过大的工具结果，完整原始结果仍保留在 session log。
- `dsh-token-meter`：为压缩触发和结果修剪提供 token 压力测量。

这些能力说明不应先造一套平行的 Tool Router、Compaction 或 Prompt Builder。第一优先级是确认锁定的 `0.2.2-rc.1` runtime 实际启用了哪些能力，再决定最小补丁。

## 3. 目标架构

```text
用户输入
   |
   v
Task Runtime  <----> Durable Session / Task State
   |
   v
Task & Capability Router
   |                 |
   |                 +--> Capability Catalog / Skills
   |                         |
   v                         v
Context Budget Manager --> Tool Executor
   |                       (PTC 优先评估，native 按需保留)
   v
Prompt / History Assembler
   |
   v
Token Meter + Context Admission
   |
   v
Model Adapter
   |
   +--> Tool execution --> compact result / durable trace
   |
   +--> Final response
```

### 3.1 上下文预算公式

每次请求使用动态预算，而不是固定上下文值：

`usable_input = min(model_limit, configured_limit, memory_safe_limit) - output_reserve - safety_margin`

请求输入由以下部分构成：

- system prompt 与 persona；
- 当前任务所需的工具说明 / PTC SDK；
- 必需 runtime context；
- 当前任务摘要与少量相关历史；
- 本轮必要的工具结果；
- 用户当前输入。

预算分配必须记录实际 tokenizer token 数。若 provider 不暴露 tokenizer，则标记为估算/未知，不能伪装成精确值。

### 3.2 工具暴露策略

优先级：

1. 先验证已打包的 PTC runtime 和当前默认 preset 是否真的使用 PTC executor。
2. 若 PTC 可用且目标模型具备足够代码执行能力，评估用单一执行入口替代几十个原生 schema 的收益。
3. 仍需 native 工具的任务，使用任务能力类别做准入；不要把未相关工具的完整 schema 无条件塞进每次请求。
4. 工具权限和可执行集合由 Runtime 控制，模型看不到某个 schema 不代表权限自动撤销；执行端必须再次鉴权。
5. 工具目录/Skills 负责发现和说明，工具执行器负责真正调用；两者不可混成一个提示词列表。

PTC 并非免费：生成 SDK 和代码执行指导也会占用 token，必须与 native schema 做同任务 A/B 测量。

### 3.3 历史与任务状态分离

Durable state 至少分为：

- Session Event Log：原始用户/助手/工具事件，追加写入、可回放。
- Task State：目标、计划、当前步骤、已完成步骤、待执行动作、失败与重试状态。
- Execution Trace：工具名称、参数引用、执行状态、结果引用、耗时与权限判定。
- Working Summary：当前任务压缩摘要，允许重建。
- Long-term Memory：跨会话稳定事实，仅按相关性检索，不自动全量注入。

模型上下文只是上述状态的一个受预算约束的投影，不是唯一事实源。压缩不能删除原始日志，也不能把未完成任务状态丢进不可恢复的摘要。

### 3.4 工具结果治理

- 先按 MIME / 结构类型处理，不把大文件、完整日志或二进制内容直接放进 prompt。
- 大文本结果保留头尾、错误摘要、关键行和可回读引用。
- 原始结果仍在 session log 或工作区中，模型可按需继续读取。
- 对代码、JSON、表格等结构化结果，优先采用结构化摘要，不能盲目只保留字符串头尾。
- 修剪后必须记录来源引用与摘要范围，保证可审计。

### 3.5 Android 内存联动

内存预算与 token 预算分开测量、联合决策：

- 模型加载前读取可用内存、进程 PSS/RSS、设备内存压力与模型后端信息。
- 若后端可提供 KV Cache 配置，按层数、KV heads、head dimension、缓存精度和序列长度计算理论占用，并用实测峰值校准。
- 上下文上限由模型能力、后端能力、应用配置和当前内存安全余量共同决定。
- 内存压力升高时按顺序降级：裁剪可选 runtime context → 压缩/修剪历史 → 降低上下文上限 → 卸载非必要模型资源 → 明确拒绝超预算任务。
- 使用滞回阈值，避免上下文上限在临界内存附近频繁上下跳动。
- 不得仅因 Android 总 RAM 容量就推断某个模型可用；必须记录进程峰值和系统回收情况。

## 4. 实施阶段与验收门槛

### P0 — 版本与现状基线（当前阶段）

- [x] 确认目标 fork、分支和当前 DSH 版本声明。
- [x] 确认已有 schema 日志测量脚本。
- [x] 确认 PTC runtime 构建校验存在。
- [ ] 在锁定的 `0.2.2-rc.1` runtime 中确认默认 executor、compaction、tool-result pruner 和 token meter 的实际组合。
- [ ] 采集至少三类请求：纯问答、单工具、多工具多轮；保存脱敏后的 token/字节/工具数/历史长度统计。

### P1 — 低风险配置优化

- 按真实 preset 结果启用/修正 PTC 或工具准入策略。
- 校准 compaction 与 tool-result pruning 的触发阈值。
- 将非必要 runtime context 从全局提示词迁移到按需上下文。
- 不改变工具权限边界，不破坏用户自定义指令。
- 增加配置与运行时探针，确认实际请求携带的 schema 数量和 token 数。

### P2 — Context Budget Manager

- 统一收集模型上下文上限、输出预留、token 计量、工具/提示词成本。
- 在模型调用前执行 admission control。
- 为未知 tokenizer 的 provider 使用保守估算，并明确标注估算值。
- 记录每次请求的预算分配和拒绝原因，不记录 API Key、完整 prompt 或敏感工具参数。

### P3 — Durable Task State 与恢复

- 将任务状态从对话文本中结构化持久化。
- 允许压缩后继续执行未完成任务。
- 进程重启后恢复任务状态；工具副作用操作必须具备幂等键或明确的重复执行保护。
- 对工具失败、模型切换、内存回收分别做恢复测试。

### P4 — Android 内存自适应与真机验证

- 建立内存压力到上下文预算的映射。
- 在目标手机上测量 4K/8K/更高候选配置，不预设哪个一定可用。
- 测量峰值 PSS、KV Cache、首 token 延迟、生成速度、工具成功率、连续运行稳定性。
- 至少覆盖冷启动、长会话、工具结果过大、模型切换和系统内存压力场景。

## 5. 验收指标

每轮基准至少输出：

| 指标 | 目的 |
|---|---|
| system prompt tokens | 识别固定提示词成本 |
| tool schema / SDK tokens | 比较 native 与 PTC 成本 |
| history tokens | 识别历史膨胀 |
| tool result tokens | 识别工具输出污染 |
| total input / output tokens | 确认真实上下文占用 |
| compaction 次数与耗时 | 评估压缩开销 |
| 峰值 PSS / RSS | 评估 Android 内存压力 |
| KV Cache bytes | 评估上下文长度的真实代价 |
| 首 token 延迟 / tok/s | 评估交互性能 |
| 多轮工具任务成功率 | 防止只优化 token 却损坏 Agent 能力 |
| 重启恢复成功率 | 验证任务状态是否真正独立于上下文 |

性能改进只有在相同任务集、相同模型、相同推理后端下做 A/B 才能成立。

## 6. 当前决策

- 不把“8K 不适合 Agent”当作架构结论；它取决于固定开销、任务长度、工具形态和压缩策略。
- 不直接把所有工具改成动态逐个注入；优先复用上游 PTC executor / tool-set / compaction 机制。
- 不在 Android 侧复制一份上游 Agent Loop。
- 不以 schema UTF-8 bytes 推断 token 数。
- 不在未测 KV Cache 和峰值 PSS 前承诺 16K。
- 所有运行时补丁必须有锚点校验、幂等性测试、版本门禁和回归测试；上游锚点漂移时 fail closed。

下一步执行顺序：先完成 P0 未完成项，确认当前 runtime 的实际 preset 与 token meter/compaction 组合，再进入 P1。