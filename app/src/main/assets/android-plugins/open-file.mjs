/**
 * [dsh-android] Route host "open this file in a native editor" requests to the app.
 *
 * ## Why this plugin exists
 * Upstream ships an "open configuration file" button in Settings. Its host half
 * is `SettingsController.openSettingsDocument()`, which resolves the profile
 * patch path (e.g. `$DSH_HOME/profiles/web/cordis.patch.yml`) and hands it to
 * `openNativeTextFile()` from `@deepseek-ai/dsh-native-command`. That helper
 * dispatches per platform:
 *
 *   macOS   → `open -t <path>`
 *   Windows → Explorer
 *   Linux   → `xdg-open <path>`
 *   other   → **throws** `native path opener is unsupported on android`
 *
 * Android is "other", so the button always fails and the browser shows the
 * generic `openDocument.error` string ("Could not open configuration file").
 * Users reasonably read that as "my config file is broken" (issue #25); the
 * file is in fact fine, there is simply no desktop editor to hand it to.
 *
 * ## What this does instead
 * Replaces the instance seam `settingsController.openTextFile` — the same seam
 * upstream itself uses for tests (`internals.openTextFile ?? openNativeTextFile`)
 * — with a call to the app's loopback bridge (`POST /open-file`). The app then
 * opens its own in-app editor for that exact path, and reports whether the file
 * was actually opened so this plugin can still reject the request when the app
 * is not there to handle it.
 *
 * ## Why the app and not a terminal editor
 * The document lives inside the app's private directory
 * (`/data/data/dsh.dsh.mobile/files/dsh-home/...`). No terminal editor shipped
 * in the runtime (`vi`, `nano`) is reachable from the WebUI's click, and the
 * user is looking at the browser — bouncing them to a terminal would be worse
 * than the current failure. The in-app editor also gives a save path that
 * survives the profile's strict file modes.
 *
 * ## Scope
 * Only the *text-editor* intent for the settings document is redirected. Every
 * other native opener call site (session file references, "open in app",
 * reveal-in-file-manager) keeps upstream's behaviour, because those are
 * explicitly gated by `canOpenNativePath()` / `nativeFileManager()`, which
 * already report "unsupported" on Android and hide the corresponding UI.
 *
 * ## Failure policy
 * Fail closed and keep upstream's diagnostic shape: if the bridge is missing or
 * refuses, throw with the original message so the button reports an error
 * rather than silently doing nothing.
 *
 * @module dsh-android-open-file
 */

/** Cordis plugin name. */
const name = "android-open-file";

/**
 * Services that must exist before this plugin can install its seam.
 *
 * Only `settingsController` is needed. Note that reading the bridge token does
 * NOT require a `shellEnv` injection: the token arrives through this engine
 * process's own `process.env` (the app exports it before spawning node), and
 * `scrubbedParentEnv()` only strips `DSH_*` from *child* environments.
 */
const inject = ["settingsController"];

/** Bridge origin. The app listens on loopback only; see AgentBridge.kt. */
const BRIDGE = "http://127.0.0.1:3083";

/** Token environment variable exported by the app for this engine process. */
const TOKEN_ENV = "DSH_BRIDGE_TOKEN";

/**
 * Ask the app to open one path in its in-app editor.
 *
 * @param path - absolute host path to the document.
 * @param signal - caller lifetime; abort propagates to the HTTP request.
 * @returns the app's JSON reply, or null when the app is unreachable.
 */
async function requestOpen(path, signal) {
  const token = process.env[TOKEN_ENV] || "";
  try {
    const response = await fetch(`${BRIDGE}/open-file`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        // The bridge is fail-closed: a request without the per-process token is
        // answered 403. The token only ever exists inside this engine process.
        "x-dsh-token": token,
      },
      body: JSON.stringify({ path }),
      signal,
    });
    if (!response.ok) return { ok: false, error: `bridge answered ${response.status}` };
    return await response.json();
  } catch (error) {
    return { ok: false, error: error?.message ?? String(error) };
  }
}

/**
 * Install the seam.
 * @param ctx - host context carrying `settingsController`.
 */
function apply(ctx) {
  const controller = ctx.settingsController;
  if (!controller || typeof controller.openTextFile !== "function") {
    // Upstream moved the seam: report loudly instead of pretending the button
    // works. The plugin-health probe surfaces this as a degraded plugin.
    ctx.logger?.warn?.(
      "[android-open-file] settingsController.openTextFile seam not found; " +
        "the Settings document button will keep failing on Android",
    );
    return;
  }

  /**
   * Replacement for `openNativeTextFile`.
   *
   * Mirrors the upstream contract: resolves once the document was handed off,
   * throws when no handler accepted it. `signal` is honoured so an aborted
   * request does not leave a pending HTTP call behind.
   */
  controller.openTextFile = async (path, signal) => {
    const result = await requestOpen(path, signal);
    if (result === null || result.ok !== true) {
      throw new Error(
        `native path opener is unsupported on android (app bridge: ${
          result?.error ?? "unreachable"
        })`,
      );
    }
  };
}

export { apply, inject, name };
