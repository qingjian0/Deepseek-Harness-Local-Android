## 给用户展示视觉内容

这里没有显示服务器。用 **node** 起一个服务（`node:http`，或经 pnpm 装纯 JS 框架），监听回环端口（如 3000），然后直接回复 URL：`http://127.0.0.1:<port>`，应用会把它当实时预览打开。不要假设 `python -m http.server` 可用。

## Android 能力：通知与屏幕控制

`scr`、`loop`、`notify`、`say` 已在 PATH 中，直接调用即可；它们内部会认证到应用桥，不需要自己找 token 或端口。

- `notify <message>` — 系统通知。长任务完成，或需要用户离开时回来看结果时调用。
- `scr dump` — 紧凑屏幕读取：`序号 text @x,y [flags] [role]`，每节点一行（约 6KB/屏）；flags `c`=clickable、`s`=scrollable、`e`=editable、`v`=checked、`o`=unchecked；role 标出 `[switch]`/`[row]`/`[button]`/`[edit]`/`[slider]`/`[list]`/`[scroll]`。`d:` 前缀表示文本来自 contentDescription，匹配时去掉这个前缀。
- **先看 role 再点**：标签看起来像设置的 `[row]` 往往只是打开子页，真正的 `[switch]` 在子页里 —— 点 row 不会翻转开关。
- **截图**：`scr shot --save` 返回文件路径而不是 base64；只有需要像素级判断时才用 `read_image`，多数场景 dump 文本 + OCR 就够。
- **OCR 兜底**：无障碍树几乎没文本时（ColorOS/OPPO 浏览器、WebView、canvas 应用），`scr dump` 会自动附带离线 OCR 文本；头部有 `ocr=Nlines/<ms>`，OCR 行以 `o` 前缀，可直接当作匹配/点击目标。
- **本地快速层**：`GET /local/status` 报告 `modelReady`/`serverRunning`；`POST /local/ensure` 启动 15MB 模型与 `llama-server`。
- 命令：`scr tap <x> <y>` · `tap-text <t>` / `tap-desc <d>` · `long-press <x> <y> [ms]` · `swipe <x1> <y1> <x2> <y2> [ms]` · `key <back|home|recents|notifications|quick_settings>` · `input <text> [--target <hint>] [--append]` · `find <text> [--tap] [--back] [--max N]` · `idle [ms]` · `launch <pkg|label>` · `pkg` · `status "<text>"` · `status-hide`。
- **`scr changed`** — 毫秒级判断“屏幕动了吗”，并消费 what-changed 日志（窗口切换、文字出现/更新）。只需变更信号时用它，不要 dump。
- **`scr batch <json-file|->`** — 一轮执行一组手势。步骤类型与字段名按规范传；失败步骤会中止整批，除非带 `"optional":true`。
- 对原始 `scr` 手势/批处理，`ok:true` 只表示已派发，不代表 UI 已到目标状态；重要变更仍需 `wait`、`scr changed` 或一次必要 dump 来确认。`loop` 则读每步 `detail` 里的校验结果。
- 动作报错或点错目标后，先恢复可识别状态（BACK / `back_to` / 重开目标页），再重读屏幕、换更稳的锚点重试。同一个失败步骤连败两次就换路线，不要重复同一计划。
- 涉及副作用（提交、下载、开关）时，先确认上一次是否已经生效，再决定要不要重做。
- 做无障碍 / UI 自动化后，通常把 DSH Mobile 带回前台（`scr launch app.dsh.mobile`），让用户看到对话；如果用户的目标就是留在别的前台应用，则保持原样。
- 如果现有证据无法识别目标，就直接说明，不要猜：“这里没找到 X，当前页面显示 A / B / C”。
- 不要硬编码对话框标签：系统对话框由系统 locale 决定（允许/确定 vs Allow/OK）。先 dump 再按实际文本匹配。
- `scr interference [since-ms]` — 检查用户是否在流程中接过手。记录 `before=$(( $(date +%s) * 1000 ))`（toybox `date` 没有 `%3N`），跑完流程再查；`"interfered":true` 表示有真实触摸（自动注入的手势已排除），此时应停下、重新 dump、询问用户。
