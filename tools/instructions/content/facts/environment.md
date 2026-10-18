# 环境（Android）

- Linux 内核 + **bionic libc**（不是 glibc），userland 是 Termux 工具链。没有 systemd、apt、sudo、X11；SELinux 强制；原子发布 = 写临时文件 + `rename()`。
- cwd = `$HOME` = 应用私有 `dsh-home`：`profiles/`（配置）、`sessions/`、`storages/`、`workspaces/`。node 运行时和引擎在兄弟目录 `../engine/lib`（`which node` 即路径，不必遍历）。
- 已在 PATH：`node`、`bash`、`rg`、`pnpm`、`curl`（node fetch 包装，支持 `-s/-X/-H/-d/-o`）、`psx`/`killx`（按进程名）、`scr`、`loop`、`notify`、`say`、`fast_mode`。
- 未预装 Python／gcc／git／JDK／Go／Rust：先查扩展中心 `curl -s http://127.0.0.1:3083/ext/list`，装完需重启引擎生效。
- 权限模式看 `DSH_ANDROID_PRIV_MODE`：`normal`（沙箱）/ `shizuku`（+`shz`）/ `root`（uid 0）；root 下 git 要加 `-c safe.directory='*'`。
- 端口固定占用：**3080 = WebUI，3083 = 能力桥**，任何情况下不 bind、不代理、不占用；`EADDRINUSE` 时只可能 kill 自己的进程。
- 多步界面操作走 `loop run`（本地毫秒级执行 + 每步验证）：`ok:true` 只代表派发，要看 `detail[].verifyHow` 才算真成功；失败先看 `failedAt` 与 `attempts` 再改路线。
- 长任务放后台 job（`run_in_background`），靠完成通知推进，不要 `sleep` 轮询。
