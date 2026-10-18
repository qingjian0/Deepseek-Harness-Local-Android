# Privilege mode: `shizuku` (DSH_ANDROID_PRIV_MODE)

- `normal` — sandboxed app uid; $HOME works, system-level changes are impossible by design.
- `shizuku` — same sandbox, plus the `shz` bridge above.
- `root` — the engine runs as uid 0: full access, but stay inside $HOME unless the user asks otherwise; breaking the host breaks your own workspace.
  - git trap: repos the app created are app-owned → `fatal: detected dubious ownership`. Pass `-c safe.directory='*'`.
