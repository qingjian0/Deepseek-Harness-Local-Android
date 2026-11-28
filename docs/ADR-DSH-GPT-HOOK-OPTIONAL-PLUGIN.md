# ADR: DSH-GPT Hook as an optional developer plugin

- Status: Accepted for implementation
- Date: 2026-11-28
- Scope: DSH host integration with `qingjian0/dsh-gpt`
- Supersedes: the mandatory/bundled host integration proposed by PR #35

## Decision

Do not make DSH-GPT Hook a core DSH feature and do not bundle or auto-install its Agent extension in the default DSH profile.

Keep the Hook runtime as the independently installable `dsh-gpt` Android/LSPosed module. Integrate its Agent-facing controls into DSH only as an optional Cordis profile plugin, discoverable and manageable from Developer Options / Plugin Center.

The feature is OFF and absent from the default profile unless the user explicitly enables/installs it.

## User experience

1. Normal DSH users see no Hook tools, Hook routes, setup prompts, or background work.
2. Seven-tap Developer Mode reveals advanced options.
3. An optional `DSH-GPT Hook Agent` entry exposes install/enable/disable/remove and connection status.
4. Enabling the integration does not silently install or enable LSPosed, grant privileges, or activate hooks in ChatGPT. The separate module must be installed, enabled in LSPosed, scoped to the intended target, and configured by the user.
5. Disabling the DSH integration removes its Agent tools from the active profile after a controlled engine reload. It does not uninstall the separate module or erase its Hook rules/observations.
6. Removing the DSH plugin removes only the DSH-side adapter and its local connection settings.

Developer Mode remains a UI-discovery switch only. It must never bypass CapabilityGate or grant privileged Android capabilities.

## Integration boundary

- DSH core: no mandatory dependency on the Hook project; no Hook startup work; no Hook-specific default permissions.
- DSH extension: a separately installable Cordis plugin package, using the DSH plugin manager and ordinary plugin lifecycle.
- Hook runtime: remains in the separate `qingjian0/dsh-gpt` APK and LSPosed module.
- Control API: use the documented authenticated local API of dsh-gpt (`/v1/hook/status`, `/v1/hook/events`, `/v1/hook/config`, `/v1/hook/enabled`, `/v1/hook/discover`, `/v1/hook/inspect`, and `/v1/hook/discovery/{requestId}`). The extension must use the configured API base URL and Bearer API key; do not hard-code DSH AgentBridge port 3083 or depend on a DSH-specific ContentProvider adapter.
- Default endpoint is loopback on the dsh-gpt API port (currently 8766), but it must be configurable and verified at connection time.
- API credentials are secrets: store them only in app-private plugin configuration, redact them from logs/export/Agent output, never place them in prompts, and never send them to the ChatGPT target process.

This removes the stale PR #35 design's extra Kotlin host bridge, duplicate endpoint catalog entries, hard-coded `127.0.0.1:3083`, and Provider package-UID coupling from the DSH core.

## Safety and correctness requirements

- Fail closed when the API key is absent/invalid or the module is unreachable.
- Keep observation metadata-only. Do not collect or expose method arguments, return values, HTTP headers, cookies, tokens, request/response bodies, device identifiers, or anti-abuse proof material.
- Keep the Hook project's own target/package scope, rule validation, and behavior-changing restrictions. The DSH Agent must not be able to execute arbitrary code in the target process.
- A stored rule snapshot is not proof of installation. Report success only after the Hook runtime's live HookHandle inventory confirms the expected rules/revision.
- Discovery must remain bounded, cancellable, and report terminal states honestly.
- Hook enable/disable is distinct from installing/removing the DSH Agent plugin.
- The integration must not change DSH's package identity or default startup behavior.

## Implementation plan

1. Retire PR #35 as an obsolete implementation proposal; preserve its branch for reference.
2. Publish the Agent extension as an optional installable DSH plugin, adapting it to dsh-gpt's authenticated `/v1/hook/*` API instead of the stale `/hook/*` host bridge routes.
3. Add a Developer Options entry and feature switch. The switch controls DSH-side plugin availability only; it does not toggle LSPosed or grant target-app access.
4. Add connection diagnostics and explicit setup guidance for the separate dsh-gpt APK, LSPosed scope, API URL, and API key.
5. Add tests for disabled-by-default behavior, secret redaction, unavailable/unauthorized API, cancellation, revision verification, and plugin removal.
6. Run DSH CI and plugin tests. Device acceptance is a separate required gate; CI alone does not prove LSPosed/IPC runtime behavior.

## Explicitly not doing

- No Hook runtime code in the DSH APK.
- No automatic LSPosed activation, target-app scope changes, or Hook rule application.
- No default bundled profile plugin.
- No merge of PR #35 or cherry-pick of its stale host bridge implementation.
- No hard-coded dependency on DSH's AgentBridge port block.
