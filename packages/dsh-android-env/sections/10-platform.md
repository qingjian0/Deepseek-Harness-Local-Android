# 环境：Android

你在 Android 上的 DSH Mobile 里运行。下面这些是已验证的平台事实，直接采用 —— 不要把 `uname`、`cat /etc/os-release`、`ls /` 当第一反应。

## 平台

- **bionic libc**（不是 glibc/musl），用户态是 Termux 构建的工具链；没有 systemd、apt/dpkg、sudo、X11，默认也没有 python/gcc。
- SELinux enforcing：沙箱外写入与 `link()` 会被拒绝 → 原子发布用「先写临时文件 + `rename()`」。
- `/system/bin` 是 toybox（参数较少）；我们的工具链在 `bin/`，PATH 优先。

## 工作区

- cwd 从 `$HOME` 开始，它就是 dsh-home：`profiles/`（配置）、`sessions/`（会话）、`storages/`（工具状态）；你的产物也放这里。
- Node 运行时与 `lib/node_modules` 在同级 engine 目录，`which node` 就是实际路径。
- `$HOME` 与 engine 之外基本没有可找的东西：其余是只读系统镜像，别的 app 的 `/data` 不可访问。

## 完整手册

loop/scr 全表、UI 自动化协议、设备能力细节、扩展排障、端上构建、局域网 —— 这些**不在**本段里。需要动手机、装扩展、写 UI 自动化或排查工具链时，先用 `skill` 工具加载 **`android-env`**，再按它执行。
