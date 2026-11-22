# dsh-gpt Hook Agent Bridge

This document describes the isolated integration branch
`refactor/dsh-gpt-hook-bridge-hardening`. It is based on
`feat/dsh-gpt-hook-agent-bridge`; it is not merged into `main`.

## Components

- `AgentBridge.kt`: authenticated loopback HTTP route dispatch.
- `HookAgentBridge.kt`: DSH host adapter to the dsh-gpt ContentProvider.
- `EndpointCatalog.kt`: discoverable route descriptions and limits.
- `profile-plugin-src/dsh-gpt-hook-agent/package/`: DSH Cordis Agent tools.
- `ProfilePluginProvisioner.kt` and Gradle asset wiring: install the profile plugin
  in the DSH host app. This does not add Node.js or the plugin bundle to the separate
  dsh-gpt LSPosed module APK.

## HTTP contract

| Method | Route | Purpose |
|---|---|---|
| GET | `/hook/status` | Provider reachability, observation state, enablement, discovery status |
| GET | `/hook/events` | Bounded incremental metadata events |
| GET | `/hook/rules` | Read declarative Hook rules |
| POST | `/hook/rules` | Validate and store a complete rules snapshot |
| POST | `/hook/discover` | Queue a bounded metadata discovery request |
| GET | `/hook/discover?requestId=...` | Read task state and latest result |
| POST | `/hook/discover/cancel` | Cancel a queued/running task |
| POST | `/hook/control` | Enable/disable Hook callbacks |

The loopback HTTP server validates `X-DSH-Token` before calling these routes. The token
stays in the DSH engine process and is not copied into the target process.

## Discovery lifecycle

The host generates a UUID request ID and submits `prefix`, optional `className`,
`limit`, and `cursor` to the module Provider. The target process claims a revisioned
lease, performs metadata-only discovery, and posts a result. The host waits for a
terminal task state instead of treating a non-empty intermediate attempt result as
final. Transient Provider/index errors may be retried within the task's bounded attempt
budget. A stale worker revision is rejected. A timed-out HTTP request leaves the task
queryable by request ID; it is not silently discarded.

## Security boundary and remaining work

- HTTP Agent authentication uses the DSH bridge token.
- Provider authorization currently checks the Binder caller UID's package set for
  `app.dsh.mobile`. This is not cryptographic certificate pinning and is not a
  substitute for an explicit pairing protocol.
- The Provider accepts only bounded declarative rules and metadata discovery requests;
  no arbitrary Agent-generated code is executed in the ChatGPT process.
- Observation payloads must remain free of arguments, return values, headers, cookies,
  request/response bodies, and credentials.
- Provider/host revisions must be deployed together. The host branch still needs
  conflict review against its 12 commits behind `main`, CI completion, and device
  validation of Binder caller identity, process restarts, task retry, cancellation,
  and actual ChatGPT ClassLoader discovery.

## Validation

The CI matrix separately packages the profile plugin and builds the DSH app for
arm64-v8a/x86_64, full/noruntime variants. A passing plugin packaging job alone is not
evidence that Provider IPC or a real device works.
