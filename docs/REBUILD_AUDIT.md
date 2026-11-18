# Upstream Reset Rebuild Audit

基线：`upstream/main` / `main` / `dev` 均已对齐到
`653b1d7bc59fa6ce6e10587f53a55252b30bbae5`。
本次重做从该基线另开工作分支，不回迁旧分支差异。

## 当前工作分支

`dsh/rebuild-after-upstream`

## 架构基线

| 层 | 主要模块 | 职责 |
|---|---|---|
| Android 宿主 | `MainActivity` / `EngineService` / `DshApp` | UI、前台服务、应用级生命周期 |
| Engine 生命周期 | `EngineSupervisor` / `EngineProcess` / `BootTrace` | 启动、PTY、健康检查、退避、自愈、日志 |
| 运行时与配置 | `EngineConfig` / `RuntimeInstaller` / `LinuxEngineLauncher` | Node 环境、配置注入、原生/容器运行模式、运行时换装 |
| AI 设备能力 | `AgentBridge` / `EndpointCatalog` / `engine/cap/*` | HTTP 能力桥、自描述路由、Android 设备能力 |
| 权限 | `Privilege` / `CapabilityGate` / `ShizukuHttpBridge` | 普通、Shizuku、Root 模式 |
| 网络入口 | `LanWebProxy` / `RemoteAccessServer` | WebUI 局域网代理、设备发现与配对 |
| 数据与恢复 | `MemoryStore` / `TaskRecorder` / `ProfileGuardian` / `PluginRecovery` | 记忆、任务轨迹、配置快照与故障恢复 |

上游 README 声明具备原生 Node Engine、可选 Linux 容器、扩展中心、社区插件、设备能力桥和远程工作区。重做应优先修复跨层契约，不应复制重造这些已有子系统。

## 已确认的端口问题

### Engine 主端口

1. Engine 目前以 `EngineConfig.DEFAULT_PORT = 3080` 为固定端口。
   `EngineSupervisor` 的 spawn、健康轮询、端口检测、回收和诊断均围绕该常量。
2. `EngineSupervisor.reclaimPortByPort()` 会按监听 socket inode 找到占用 3080 的 PID，并对找到的 PID 执行 `kill -9`；
   它不先证明该进程属于 DSH。因而它可能误杀其他软件，也可能因 /proc 可见性或权限问题杀不掉。
3. 启动前 `cleanupOrphanEngine()` 与异常退出后的清场都会进入这条按端口清理路径。清场后若端口仍被占用，当前启动路径没有备用 Engine 端口机制，仍以 3080 启动。
4. `EngineTriage` 的端口事实采集、PID 归属、错误文案和修复动作仍引用固定默认端口。
5. `OrphanReaper` 也按 `EngineConfig.DEFAULT_PORT` 扫描主 Engine socket；动态端口后必须把实际端口传入，不能漏掉 Root 孤儿。

### 相关服务端口与隐藏依赖

| 服务 | 当前端口/规则 | 需要同步的依赖 |
|---|---:|---|
| Engine Web | 3080 | `EngineSupervisor`、`EngineConfig`、健康检查、WebView、诊断 |
| Shizuku HTTP | Engine + 2（默认 3082） | `ShizukuHttpBridge`、`DSH_SHZ_PORT` |
| AgentBridge | 3083 | `AgentBridge.PORT`、`EngineConfig.buildEnv()`、远程配对、gate 脚本 |
| LAN Web proxy | 3090 | `LanWebProxy.UPSTREAM_PORT`、`EngineConfig.buildEnv()`、WebUI token |
| Remote access pairing | 3091 | `RemoteAccessServer` 的发现/配对响应和 setup 脚本 |
| Pet bridge | 7779 | 独立服务，保持独立配置 |

补充证据：
- `app/src/main/assets/gates/_dsh_http.sh` 已支持 `DSH_BRIDGE_PORT`，但当前 Engine 环境没有注入该变量。
- `RemoteAccessServer` 的配对响应和 setup 脚本读取 `AgentBridge.PORT`，端口动态化时必须返回实际绑定端口。
- `LanWebProxy` 把上游固定为 127.0.0.1:3080，并用 `EngineConfig.DEFAULT_PORT` 构造代理环境。
- `AgentBridge` 内部诊断页还有固定 3080 的 HTTP/WS 探测和文案。
- `LinuxEngineLauncher` 已接受 port 参数并写入 `PORT`；容器启动参数可以复用统一分配结果。

## 重做原则

- 不杀不属于 DSH 的监听进程；端口被占用是选择备用端口的条件，不是杀进程的理由。
- 端口分配必须集中管理，不能让 Engine、WebView、健康检查、Agent gate、Shizuku、LAN 代理和诊断各自猜测端口。
- Engine 启动使用同一个实际端口；健康检查必须验证本次 Engine 的 token URL，而非只验证端口有 HTTP 响应。
- 端口选择失败、绑定竞态和权限不可见都必须有明确日志与 UI 错误，不得静默假成功。
- 首选端口保持 3080 以兼容现有默认配置；被占用时选择避开 DSH 固定服务端口的候选端口。
- 对外桥接端口单独分配，并把实际值通过环境变量和发现/配对响应传递给 Agent。
- 保留 Engine 自己的孤儿清理能力，但只能清理经过 cmdline、UID、socket inode 等证据确认属于本应用的进程。
- 端口探测与实际 bind 之间存在竞态；必须把 Node 的 `EADDRINUSE` 纳入有限重选流程，不能把「探测空闲」当作 bind 成功保证。

## 实施顺序

1. 完成所有端口引用盘点，包含 Kotlin、shell gate、WebView、容器启动器、配对响应与诊断页。
2. 引入可测试的端口分配器和单一运行时端口配置；保留 3080 为首选。
3. 改造 `EngineSupervisor` / `EngineConfig` / `EngineTriage` / `OrphanReaper`。
4. 将实际端口贯穿 `AgentBridge`、Shizuku bridge、LAN proxy、RemoteAccess 与 UI。
5. 增加单元测试：默认端口空闲、默认端口被占、候选端口被占、候选耗尽、端口探测异常、
   启动时发生 bind race、旧进程残留、外部进程绝不被终止。
6. 构建并运行 Android 测试；检查启动、重启、Root/Shizuku/普通模式和 LAN 访问。

## 当前状态

- 已完成：上游基线与分支同步核验。
- 已完成：第一轮源码架构与固定端口路径审查。
- 进行中：实现前依赖盘点。
- 未完成：功能代码修改、构建、真机验证。

本文件是审计记录，不代表端口动态分配功能已经实现。
