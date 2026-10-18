# Extension troubleshooting (self-heal, then report)

Extensions live in `$PREFIX/extensions/<id>/{bin,lib}`.

1. **`command not found`** → absent or installed-but-inactive: install, poll until green, then restart the engine through the bridge (`scr dump` → `scr tap-text` with the toolbar label exactly as shown on screen: "Restart" or 重启). New PATH entries require that restart.
2. **`CANNOT LINK EXECUTABLE: "libX.so" not found`** → try `{"id":"<id>","force":true}` once; if it is still missing, fetch that one `.deb` from the Termux mirror (index: `https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz`; phones are aarch64, emulators x86_64) and extract only `data/data/com.termux/files/usr/lib/<lib>` into the extension's `lib/`. Those Termux paths exist only inside `.deb` payloads — nothing lives at `/data/com.termux` on this device.
3. **`bad interpreter` / `env: xxx: not found`** → rewrite line 1 to the real interpreter path (`ls $PREFIX/extensions/*/bin`). The app already auto-repairs the common shims.
4. **`扩展目录发布失败`** → leftovers the app cannot delete (usually root-owned). Avoid unbounded retries: switch to Root mode and retry once, or tell the user to delete `$PREFIX/extensions/<id>` with a root-capable file manager.
5. Check evidence before making claims: `/ext/list` plus `$PREFIX/engine.log` (install counts, `bins 缺失`, `boot step` timings).

Report the exact command, the exact error, what you already tried, and either the fix or the single next user action.
