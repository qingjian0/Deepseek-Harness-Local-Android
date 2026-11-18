# Upstream Reset Rebuild Audit

基线：`upstream/main` / `main` / `dev` 均已对齐到
`653b1d7bc59fa6ce6e10587f53a55252b30bbae5`。
本次重做从该基线另开工作分支，不回迁旧分支差异。

## 当前分支

`dsh/rebuild-after-upstream`

## 架构基线

- Android 原生宿主负责生命周期、权限、文件与系统能力。
- `EngineSupervisor` 管理启动、健康检查、崩溃退避和恢复。
- `EngineProcess` 通过 PTY 启动 Node 引擎并泵送日志。
- `EngineConfig` 生成运行环境、配置与 Agent gate 脚本。
- `AgentBridge` 把 Android 能力通过 loopback HTTP 暴露给 Agent。
- `ShizukuHttpBridge` 提供 Shizuku 通道。
- `LanWebProxy` 与 `RemoteAccessServer` 提供独立网络入口。
- Runtime 安装/替换由 `RuntimeInstaller` 与两阶段换装流程负责。

## 已确认的端口问题

1. Engine 目前以 `EngineConfig.DEFAULT_PORT = 3080` 为固定端口。
   `EngineSupervisor` 的 spawn、健康轮询、端口检测、回收和诊断均围绕该常量。
2. `reclaimPortByPort()` 会按监听 socket inode 找到占用 3080 的 PID，并对找到的 PID 执行 `kill -9`；
   它不先证明该进程属于 DSH。因而它可能误杀其他软件，也可能因 /proc 可见性或权限问题杀不掉。
3. 清场后若端口仍被占用，当前启动路径没有选择备用 Engine 端口的机制，仍会以 3080 启动。
4. `AgentBridge` 固定监听 3083；`_dsh_http.sh` 已支持 `DSH_BRIDGE_PORT` 覆盖，
   但当前 `EngineConfig.buildEnv()` 没有注入这个变量。
5. `ShizukuHttpBridge` 使用 Engine 端口 + 2；`LanWebProxy` 的上游仍硬编码为 127.0.0.1:3080，
   且其启动环境也用 `DEFAULT_PORT`。仅修改 Engine 的 PORT 会造成代理与桥接访问旧端口。
6. `EngineTriage` 的端口事实采集和错误文案也引用固定默认端口，诊断必须同步改为实际运行端口。

## 重做原则

- 不杀不属于 DSH 的监听进程；端口被占用是选择备用端口的条件，不是杀进程的理由。
- 端口分配必须集中管理，不能让 Engine、WebView、健康检查、Agent gate、Shizuku、
  LAN 代理和诊断各自猜测端口。
- Engine 启动使用同一个实际端口；健康检查必须验证本次 Engine 的 token URL，而非只验证端口有 HTTP 响应。
- 端口选择失败、绑定竞态和权限不可见都必须有明确日志与 UI 错误，不得静默假成功。
- 首选端口保持 3080 以兼容现有默认配置；被占用时选择避开 DSH 固定服务端口的候选端口。
- 对外桥接端口单独分配，并把实际值通过环境变量和发现/配对响应传递给 Agent。
- 保留 Engine 自己的孤儿清理能力，但只能清理经过 cmdline、UID、socket inode 等证据确认属于本应用的进程。

## 实施顺序

1. 端口依赖盘点：查清所有固定端口、环境变量、WebView URL、LAN 代理和远程配对响应。
2. 引入可测试的端口分配器与统一运行时端口配置。
3. 改造 EngineSupervisor / EngineConfig / EngineTriage。
4. 将实际端口贯穿 AgentBridge、Shizuku bridge、LAN proxy、RemoteAccess 与 UI。
5. 增加单元测试：默认端口空闲、默认端口被占、候选端口被占、连续候选耗尽、
   端口探测异常、启动时发生 bind race、旧进程残留、外部进程不被终止。
6. 构建并运行 Android 测试；检查启动、重启、Root/Shizuku/普通模式和 LAN 访问。

## 当前状态

- 已完成：上游基线与分支同步核验。
- 已完成：第一轮源码架构与固定端口路径审查。
- 进行中：完整端口依赖盘点与实现设计。
- 未完成：功能代码修改、构建、真机验证。

本文件是审计记录，不代表端口动态分配功能已经实现。
