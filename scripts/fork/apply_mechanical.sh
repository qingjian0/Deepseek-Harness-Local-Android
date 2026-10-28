#!/usr/bin/env bash
# 一键应用「机械类」定制改动（端口 + 版本号，可选包名）。
# 代码插入类改动（种子/性能/lite/发版）见 FORK_CHANGES.md 手动清单。
#
# 用法：
#   bash scripts/fork/apply_mechanical.sh [版本号] [versionCode]
#   WITH_REBRAND=1 bash scripts/fork/apply_mechanical.sh 1.5.4 276
set -euo pipefail
cd "$(dirname "$0")/../.."

python3 scripts/fork/port_shift.py
python3 scripts/fork/set_version.py "${1:-1.5.4}" "${2:-276}"

if [ "${WITH_REBRAND:-0}" = "1" ]; then
  echo "== 包名迁移（破坏性）=="
  python3 scripts/fork/rebrand.py
fi

echo
echo "机械改动完成。剩余手动项见 FORK_CHANGES.md："
echo "  2. AGENTS.md 种子清理   3. 性能缓存+预热   4. lite runtime CI   5. fork 自动发版"
