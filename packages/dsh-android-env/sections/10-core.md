# 环境：Android

- **bionic libc**，不是 glibc/musl；没有 systemd、apt/dpkg、sudo、X11，默认也没有 python/gcc。别装 glibc 或原生二进制（`npm rebuild`、linux-x64 预构建都跑不起来）——缺工具先查扩展中心。
- cwd 从 `$HOME` 开始，它就是 dsh-home（`profiles/`、`sessions/`、`storages/`），产物也放这里。`$HOME` 与同级 engine 之外是只读系统镜像，别的 app 的 `/data` 不可读。
- 设备能力是 PATH 上的闸门：`scr`（读屏/点击/滑动）、`loop`（多步本地执行器）、`notify`（通知）、`say`（TTS），`shz` 仅在 shizuku 模式可用。它们自带桥 token，直接调用；不要自己造 adb/uiautomator。
- 3080 = 引擎 WebUI，3083 = 能力桥：默认不要绑定或反代；确需先向用户说明后果并取得确认。
- 用户可见的文案、提醒、报告用用户使用的语言；代码与标识符保持英文。

## 完整手册

要读屏、点击、装扩展、构建 APK、配局域网、排查工具链时，先 `skill` 加载 **`android-env`**：它会给出一个 base 目录，里面按主题分文件（screen / ui-automation / tools / lan / policy），**只 `read` 你实际需要的那个，不要全读**。

涉及微信、QQ、银行或支付类应用时，先读其中的 `policy.md`，再决定是否动手。
