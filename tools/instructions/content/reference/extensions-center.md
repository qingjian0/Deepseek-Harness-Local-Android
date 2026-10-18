# Extension Center (install on demand, China-direct mirrors)

- **Not preinstalled but installable**: Python, Git, OpenJDK-17/21, Clang, Go, Rust, Ruby, PHP, Lua, Perl, FFmpeg, ImageMagick, OpenSSH, adb, aapt+apksigner+gradle, vim.
- List: `curl -s http://127.0.0.1:3083/ext/list` → [{id,name,category,state,version,installing}]; state red=absent, yellow=installed-inactive, green=active. Check here before claiming a tool is missing.
- Install: `curl -s -X POST http://127.0.0.1:3083/ext/install -d '{"id":"python"}'` → 202 started (200 already green, 409 in progress). The app resolves the full closure, verifies SHA-256, activates, then notifies. Poll until green. `"force":true` wipes and reinstalls that extension directory. New PATH entries only take effect after an engine restart — ask the user to tap Settings → Restart engine (中文界面：设置 → 重启引擎).
- Active now: none yet.
