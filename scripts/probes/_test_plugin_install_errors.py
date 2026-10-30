#!/usr/bin/env python3
"""用户反馈「插件下不了」的回归护栏（v1.4.37）。

## 事件

用户截图给了两种不同的失败，UI 上都只显示半截错误：
1. `失败：dsh: installation rejected:` —— 冒号后面什么都没有
2. `失败：Error: ERR_PNPM_IGNORED_BUIL` —— 错误码被截断

## 根因（两个独立问题）

**问题 1：引擎兼容性预检拒绝**
引擎 `dsh-plugin-manager` 在安装**前**调 `evaluatePluginCompatibility`：
插件 `engines.dsh` 要求的最低版本高于内置引擎时不通过，回滚并输出
`dsh: installation rejected: Plugin X@Y is incompatible with dsh Z: requirements {...}`。
旧实现把 stderr 截到 300 字符，正好截在冒号处 —— 真正的原因（以及引擎自己
给出的解法 `dsh plugin allow-version`）全被丢掉。

**问题 2：pnpm 默认不执行依赖 build 脚本**
pnpm 10+ 把依赖的 install/postinstall 列为 "ignored builds"，只警告不执行。
带原生模块的插件装完但没编译，`build/` 不存在 → 插件加载失败。

## 本探针守住什么

- stderr 截断长度够拿到真实原因（≥1000）
- 引擎拒绝被解析成结构化数据，并给用户可读解释（走 strings.xml）
- **豁免必须用户显式确认**（引擎原文：may cause crashes or data loss）
- pnpm 白名单只放当前包，**禁止 `*`**（通配 = 允许所有依赖执行任意脚本）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                  "engine", "PluginCenter.kt")
STR = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


src = open(KT, encoding="utf-8").read()
strings = open(STR, encoding="utf-8").read()

# ------------------------------------------------ 问题 1：stderr 截断
check("截断：stderr 至少保留 1000 字符",
      re.search(r"take\((1[0-9]{3}|[2-9][0-9]{3})\)", src) is not None,
      "300 字符正好截在 'installation rejected:' 冒号处，把原因全丢了")
# 注意：成功分支的 stdout 仍用 take(300)，那是给 UI 显示的摘要，没问题。
# 要守的是**失败分支的 stderr** —— 引擎把原因写在 rejected 之后。
check("截断：失败分支的 stderr 不再 take(300)",
      not re.search(r"err,\s*Charsets\.UTF_8\)\.trim\(\)\.take\(300\)", src),
      "旧值会截掉引擎给出的解法提示")

# ------------------------------------------------ 问题 1：解析与提示
check("解析：有 parseEngineRejection", "fun parseEngineRejection" in src)
check("解析：识别 installation rejected 关键字",
      "installation rejected" in src,
      "引擎原文用的是这个措辞")
check("解析：能取出插件名@版本",
      'Plugin\\s+(\\S+?)@' in src or "Plugin\\s+" in src,
      "需要它来拼 allow-version 的 packageVersion")
check("解析：能取出 requirements",
      "requirements" in src)

# ------------------------------------------------ 问题 1：豁免必须显式确认
check("豁免：提供 forceInstallWithExemption", "fun forceInstallWithExemption" in src)
check("豁免：走官方 allow-version 命令",
      '"allow-version"' in src,
      "不手写 compatibility.json，走引擎自己的 API 才能保证格式正确")
check("豁免：必须带 --accept-risk",
      "--accept-risk" in src,
      "引擎强制要求显式确认风险，App 不能替用户静默接受")
check("豁免：必须带 --dsh-version",
      "--dsh-version" in src,
      "引擎会校验 runtime 版本与当前运行的一致")
# 豁免必须是独立入口，**不能**从 install() 里自动调用 ——
# 引擎原文「may cause crashes or data loss」，静默放行等于替用户承担风险。
_install_body = re.search(r"fun install\([\s\S]*?\n    \}", src)
check("豁免：install() 内部不自动调用豁免",
      _install_body is not None and
      "forceInstallWithExemption" not in _install_body.group(0),
      "自动放行会让用户在不被告知的情况下承担崩溃/丢数据风险")
check("豁免：install() 只做识别与提示",
      _install_body is not None and "parseEngineRejection" in _install_body.group(0))

# ------------------------------------------------ 问题 2：pnpm ignored builds
check("pnpm：识别 ERR_PNPM_IGNORED_BUILDS", "ERR_PNPM_IGNORED_BUILDS" in src)
check("pnpm：识别被截断的错误码", "ERR_PNPM_IGNORED_BUILD\"" in src or
      "ERR_PNPM_IGNORED_BUILD'" in src,
      "用户截图里错误码被 UI 截成了 ERR_PNPM_IGNORED_BUIL")
check("pnpm：写 onlyBuiltDependencies 白名单", "onlyBuiltDependencies" in src)
check("pnpm：白名单禁止通配 `*`",
      not re.search(r'onlyBuiltDependencies[^\n]*=\s*"\*"', src),
      "写 * 等于允许所有传递依赖执行任意 postinstall —— 供应链攻击面")
check("pnpm：合并已有配置而非覆盖",
      "items +=" in src or "toMutableSet" in src)

# ------------------------------------------------ i18n
check("i18n：不硬编码中文提示", "此插件要求" not in src and "无法读取" not in src,
      "项目门禁禁止 Kotlin 字符串里写 CJK，文案必须走 strings.xml")
for key in ("plugin_engine_old_why", "plugin_engine_old_req",
            "plugin_engine_old_note", "plugin_engine_old_no_version",
            "plugin_engine_old_exempt_failed"):
    check("i18n：strings.xml 有 %s" % key, 'name="%s"' % key in strings)

# ------------------------------------------------ 回归：旧自愈不能丢
check("keep：pnpm store 锁自愈仍在", "isPnpmStoreLockError" in src and "healPnpmStore" in src,
      "v1.4.18 的修复不能被这次改动挤掉")
check("keep：插件装箱串行执行器仍在", "installExecutor.execute" in src,
      "并发装插件会损坏 node_modules")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)
