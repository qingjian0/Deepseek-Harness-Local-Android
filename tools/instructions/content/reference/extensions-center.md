# 扩展中心（按需安装，国内镜像直连）

- **未预装但可一键安装**：Python、Git、OpenJDK-17/21、Clang、Go、Rust、Ruby、PHP、Lua、Perl、FFmpeg、ImageMagick、OpenSSH、adb、aapt+apksigner+gradle、vim。
- **能力桥要 token**：所有 `/ext/*` 请求都必须带 `-H "X-DSH-Token: $DSH_BRIDGE_TOKEN"`。这个变量由引擎注入你的 shell 环境；不带会返回 `{"ok":false,"error":"unauthorised"}`。
- 列表：`curl -s -H "X-DSH-Token: $DSH_BRIDGE_TOKEN" http://127.0.0.1:3083/ext/list` → `[{id,name,category,state,version,installing}]`；state `red`=未安装、`yellow`=已装未激活、`green`=已激活。判断"某工具缺失"之前先查这里。
- 安装：`curl -s -X POST -H "X-DSH-Token: $DSH_BRIDGE_TOKEN" http://127.0.0.1:3083/ext/install -d '{"id":"python"}'` → 202 已开始（200 已激活，409 正在装）。App 会解析完整依赖闭包、逐包校验 SHA-256、原子激活后发通知；轮询到 `green` 为止。`"force":true` 会清空该扩展目录重装。
- 新 PATH 条目要**重启引擎**才生效：让用户点 设置 → 重启引擎（英文界面：Settings → Restart engine）。
