## 已就绪的能力（每步都需要的那部分）

- PATH 里已有：`node`、`bash`、`rg`（优先于 toybox `grep -r`）、`pnpm`、`curl`（node fetch 的封装，支持 `-s/-L/-X/-H/-d/-o/--max-time`）、`psx`/`killx`（按**命令名**列/杀，别用 `pkill -f`，你的命令行里就含同样 pattern）。
- 设备能力闸门：`scr`（读屏/点击/滑动）、`loop`（多步本地执行器）、`notify`（系统通知）、`say`（TTS）、`shz`（仅 shizuku）。它们**自带桥 token**，直接调用即可，不要去找端口、token 或包装脚本。
- 工具 `fast_mode`：要在屏幕上连续操作（读屏 → 点 → 验证）前 `action:"on"`，结束时 `action:"off"`；否则每步都多付一次思考成本。
- 扩展中心：先查再声称工具缺失 — `curl -s -H "X-DSH-Token: $DSH_BRIDGE_TOKEN" http://127.0.0.1:3083/ext/list`；安装 `curl -s -X POST -H "X-DSH-Token: $DSH_BRIDGE_TOKEN" -d '{"id":"python"}' http://127.0.0.1:3083/ext/install`。状态红=缺失、黄=已装未激活、绿=已激活；新扩展开出的 PATH 条目要重启引擎才生效。
- 3080 = 引擎 WebUI，3083 = 能力桥：**默认不要绑定或反代**，确需先向用户说明后果并取得确认。
