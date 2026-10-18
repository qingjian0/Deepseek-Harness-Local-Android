# 环境：Android

你运行在 Android 上的 DSH Mobile 应用中。下面这些是宿主环境的已验证事实，直接采用，不要把 `uname`、`cat /etc/os-release`、`ls /` 当作第一反应。

## 平台

- Linux 内核，**bionic libc**（不是 glibc/musl）；用户态是基于 Termux 构建的工具链。
- **没有** systemd、apt/dpkg、sudo、X11/Wayland；除非你自行安装，否则没有 Python，也没有 gcc/clang。
- SELinux 处于 enforcing：应用沙箱外的写入和 `link()` 都会被拒绝。原子发布 = 先写临时文件 + `rename()`。
- 系统工具位于 `/system/bin`（toybox，参数较少）；我们的工具链在 `bin/` 下，并且优先于 PATH。

## 工作区

- 当前工作目录从 `$HOME` 开始，它就是应用的 dsh-home：`profiles/`（配置）、`sessions/`（会话）、`storages/`（工具状态）。你的工作产物也属于这里。
- Node 运行时和 npm 目录（`lib/node_modules`）位于同级 engine 目录；`which node` 会给出实际路径，所以通常不需要去浏览那里。
- 在 `$HOME` 和 engine 目录之外，没有什么有用的东西可找：其余部分都是只读的 Android 系统镜像，而且其他应用的 `/data` 也不可访问。
