dsh-android-env — P0 probe

目的
- P0 的目标是以最低风险验证：JS/TS 侧的 systemPrompt.section 通道在 Android 引擎上可用且每次 assemble 生效。

包结构
- package.json       插件元数据（dsh.bundle: true）
- index.js           插件入口，apply(ctx) 会注册探针 section（无配置、无外部依赖）
- sections/hello.md  可选的静态 section 文档样例（P1 以后使用）

如何使用（开发/本地验证）
1. 将本目录放到项目中： packages/dsh-android-env/
2. 在 profile bundle 列表中添加本地路径（或用 dsh 的本地 bundle 安装方法）：
   - 在 profile.json/bundles 中添加 "../packages/dsh-android-env"（相对路径）
   - 或使用 dsh CLI 安装本地 bundle（如果你的 dsh 版本支持）
3. 启动引擎并在 Android 设备/模拟器上运行一个新会话，按照下面的验证步骤检查探针是否生效。

验证步骤（量化）
- 目标：确认探针 section 每步生效且未进入 user 消息通道。
1. 新会话后发送任意一条 user 消息，连续交互 10 轮以上（以触发 compaction）。
2. 检查每一轮 assistant 回复的第一行是否为字符串： P0-PROBE-OK
   - 如果每一轮都包含该前缀，说明 systemPrompt.section 按预期每步生效。
   - 如果探针在某轮丢失，说明该 section 没有被每次 assemble 注入或被 compaction/历史影响。
3. 同时检查对话历史中的 user 消息，确认探针文本没有以 user 消息形式出现（证明通道是 system prompt，而非 AGENTS.md 注入）。

注意
- P0 仅用于通道验证；后续 P1/P2/P3 会把 agents_seed.md 的静态部分与运行时 provider 分别迁移到 sections/context provider。
- P0 不包含权限规则（permission rules），这些将在 P1 单独提交并 review。
