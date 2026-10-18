# Agent instructions: build artifacts + a system prompt section

> Since v1.4.0 the app's environment manual is no longer written into
> `$DSH_HOME/AGENTS.md`. It is compiled by `tools/instructions/` into assets and
> registered as a system prompt section by a bundled plugin. This document records
> why that changed, how the new layout works, and what is still unverified.

## 1. Why the old design was wrong

v1.3.x seeded a 31KB manual into `$DSH_HOME/AGENTS.md` and relied on
`dsh-agent-instructions` to inject it. That channel had four structural problems:

| Problem | Concretely |
|---|---|
| Weakest authority | Injected as a **user-role** `<system-reminder>` whose own preamble says it does not override system/developer instructions |
| Lowest precedence | The instruction chain runs broad → specific and "more specific takes precedence", so any project `AGENTS.md` outranks it |
| First to be dropped | The whole chain shares 64KiB (`maxBytes: 65536` in `dsh-base`); on overflow the broadest files go first — that was this one |
| Shared with user text | The engine loads exactly one user-global file, so user instructions had to live in the same file → marker sections, upgrade merging, edge cases by convention |

It also cost ~8k tokens per session, and 72% of it was reference material and
procedures (toolchain, screen API, UI-automation protocol) that no turn needs.

## 2. New layout

```
tools/instructions/
  content/                  authored source (policy / facts / index / reference / procedures)
  build.mjs                 compiles into assets + enforces budgets and references
  ci/check.mjs              re-builds and byte-compares the committed assets

app/src/main/assets/mobile/ shipped artifacts (committed; APK packages them)
  resident.md               always-on section
  docs/*.md                 on-demand manual pages (model reads them)
  skills/<id>/SKILL.md      skills: only name + description in the catalog
```

At runtime:

1. `app/src/main/assets/android-plugins/mobile-instructions.mjs` (injected by the
   app's overlay) registers `$DSH_HOME/mobile/resident.md` as section
   `mobile:environment` at `order: 100`. Its `text` is a function, evaluated
   synchronously with a statSync mtime cache; a missing file yields a degraded
   section instead of an empty one.
2. `MobileInstructions` (Kotlin) deploys assets to `$DSH_HOME/mobile/` and
   `$DSH_HOME/skills/` at boot and migrates the legacy `AGENTS.md` into a
   user-owned file.

## 3. Size

| | v1.3.3 | v1.4.0 |
|---|---|---|
| Resident per session | 31,016B ≈ 8,000 tokens | **4,458B ≈ 1,063 tokens** |
| 64KiB instruction budget | app section consumed nearly half | untouched, all for user/project text |
| Manuals / procedures | always resident | 7 docs + 2 skills, on demand |
| Growth control | grew every release (v1 → v48) | build gate at 6KB / 2000 tokens |

> Token counts are rough estimates from a mixed CJK/ASCII heuristic, not billing data.

## 4. Maintenance

Edit `content/`, then:

```sh
node tools/instructions/build.mjs       # writes app/src/main/assets/mobile
node tools/instructions/ci/check.mjs    # rebuild + byte-compare (this is what CI runs)
```

The build fails on: resident over budget; an index entry pointing at a missing doc or
skill; `{{ }}` in the resident text (the engine interpolates prompt variables and an
unknown name breaks every turn); or a skill without `name`/`description` frontmatter.

## 5. Migration (v1.3.3 → v1.4.0)

| Legacy file | Action |
|---|---|
| v1.3.2+ split file | keep the user section only; archive the original as `AGENTS.md.bak-legacy-<stamp>` |
| bare old seed (no user markers) | archive, then leave a pointer comment to the backup |
| no app markers (user-authored) | untouched |
| exists but unreadable | skip, retry next boot — never overwrite blind |
| plugin not packaged in this build | skip migration entirely |

Archiving failures abort the rewrite; writes go through temp + rename; docs and skills
are pruned by ledger so only files this app deployed are ever deleted.

## 6. Trade-offs

- Sections are outside the instruction-chain budget, so the plugin enforces its own
  `maxBytes` (7168) and truncates at a paragraph boundary with a warning.
- Section changes rewrite the system prompt prefix (KV cache), so the resident bundle
  only changes on release; user text never goes there.
- Docs/skills require the model to read or invoke them; `index.md` gives trigger
  phrases per topic, but a model can still act without reading — rules whose absence
  causes immediate failure stay resident.
- No dynamic placeholders any more: the old seed rendered privilege mode and active
  extensions; now the model reads `DSH_ANDROID_PRIV_MODE` / `/ext/list` on demand.
  Deliberate: per-step changing text would defeat prefix caching.
- `content/` is Chinese today (docs keep their original English bodies). Switching the
  whole layer to English means editing that layer only.

## 7. Verification status

Proven by CI: Kotlin compiles, dual-ABI APK builds, `ci/check.mjs` passes (artifact
consistency + budgets + references + interpolation guard), plugin `node --check`,
i18n gates.

Not yet verified on a device: the boot log lines and deployed files, the resident
section surviving 10+ turns and a compaction, and the v1.3.3 → v1.4.0 upgrade path
(with and without user instructions in `AGENTS.md`).
