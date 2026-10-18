# 按需资料

细节不在上面的常驻段里，需要时用 `read` 打开 `$HOME/mobile/docs/`：

| 场景 | 文件 |
| --- | --- |
| 命令行用法、PATH 优先级、curl／pnpm 细节 | `docs/toolchain.md` |
| 读屏、点击、输入、手势、通知、截图 API | `docs/screen-api.md` |
| 装／修扩展、依赖闭包、SHA-256、shebang 修复 | `docs/extensions-troubleshooting.md` |
| 扩展中心列表与安装接口 | `docs/extensions-center.md` |
| 从电脑访问 WebUI、SSH 转发、自建代理 | `docs/lan-webui.md` |
| 三级权限、su 闸门、root 注意事项 | `docs/privilege-modes.md` |
| 给用户看页面／产物预览 | `docs/visual-preview.md` |

流程类放进技能（目录里只有名字和一句描述，调用时才载入正文）：

- `ui-loop` —— 多步界面自动化的完整协议：计划编译、逐步验证、失败接管、反模式。
- `android-app-build` —— 在设备上编译、dex、签名 APK 的完整命令序列。
