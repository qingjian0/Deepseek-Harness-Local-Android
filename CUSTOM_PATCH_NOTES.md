# DSH Mobile custom patch notes

Applied directly to the user-provided source archive; no upstream download was performed.

- Android namespace/applicationId: `dsh.dsh.mobile`; Kotlin package tree migrated accordingly.
- Port shift +1000: WebUI 4080, Shizuku bridge 4082, Agent bridge 4083, semantic embedding 4085, LAN proxy 4090.
- Removed boot-time AgentContextSeed invocation. `AGENTS.md` remains user-owned and is not seeded or rewritten by startup.
- Local embedding: 512-entry LRU keyed by model fingerprint + normalized text, bounded batches of 24, synchronized cache fill to deduplicate concurrent work.
- Semantic model warm-up runs asynchronously after engine readiness only when the model file already exists; boot does not trigger a model download.
- LocalModel startup is synchronized and retains the spawned process reference.
- Root backup reuses a non-empty snapshot younger than 24 hours to avoid a full tar on every Root cold start.
- Root ownership audit is cached for 7 days per effective engine UID; quick readability checks still run each boot, and mode/UID transitions force a fresh privileged audit.

Build/device verification status is recorded separately in the delivery notes.
