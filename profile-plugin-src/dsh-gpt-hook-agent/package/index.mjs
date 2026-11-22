/** DSH Agent tools for the dsh-gpt LSPosed extension. */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const name = "dsh-gpt-hook-agent";
export const inject = ["tools"];

const BASE_URL = "http://127.0.0.1:3083";
const output = {
  schema: { type: "object", additionalProperties: true },
  render: (_args, value) => [{ type: "text", text: JSON.stringify(value, null, 2) }],
};

async function loadDefineTool() {
  // A profile bundle lives several directories below DSH_HOME, unlike android-plugins.
  // Search DSH_HOME and the module's ancestors so both layouts resolve the same engine API.
  const here = path.dirname(fileURLToPath(import.meta.url));
  const candidates = [];
  const seen = new Set();
  const addRoot = (root) => {
    if (!root) return;
    for (const relative of [
      "lib/node_modules/@deepseek-ai/dsh-tools/lib/index.js",
      "node_modules/@deepseek-ai/dsh-tools/lib/index.js",
    ]) {
      const candidate = path.resolve(root, relative);
      if (!seen.has(candidate)) {
        seen.add(candidate);
        candidates.push(candidate);
      }
    }
  };

  addRoot(process.env.DSH_HOME);
  let cursor = here;
  for (let depth = 0; depth < 10; depth++) {
    addRoot(cursor);
    const parent = path.dirname(cursor);
    if (parent === cursor) break;
    cursor = parent;
  }

  for (const candidate of candidates) {
    try {
      if (!fs.existsSync(candidate)) continue;
      const mod = await import(pathToFileURL(candidate).href);
      if (typeof mod.defineTool === "function") return mod.defineTool;
    } catch { /* try the next engine layout */ }
  }
  return undefined;
}

async function request(method, endpoint, body) {
  const token = process.env.DSH_BRIDGE_TOKEN;
  if (!token) throw new Error("DSH_BRIDGE_TOKEN is unavailable in the DSH engine environment");

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(`${BASE_URL}${endpoint}`, {
      method,
      headers: {
        "X-DSH-Token": token,
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const raw = await response.text();
    let value;
    try { value = JSON.parse(raw); } catch { value = { raw }; }
    if (!response.ok) {
      throw new Error(`DSH Hook bridge ${response.status}: ${value?.error || response.statusText}`);
    }
    if (value?.ok === false) throw new Error(value.error || "DSH Hook operation failed");
    return value;
  } finally {
    clearTimeout(timer);
  }
}

function register(ctx, defineTool, definition) {
  ctx.tools.register(defineTool({ ...definition, output }));
}

async function apply(ctx) {
  const defineTool = await loadDefineTool();
  if (!defineTool) {
    console.warn("[dsh-gpt-hook-agent] dsh-tools defineTool unavailable; tools not registered");
    return;
  }

  register(ctx, defineTool, {
    name: "dsh_hook_status",
    description: "Read the dsh-gpt Provider connection, target observation state, enablement and buffered event count.",
    parameters: {},
    execute: async () => request("GET", "/hook/status"),
  });

  register(ctx, defineTool, {
    name: "dsh_hook_events",
    description: "Read incremental metadata-only Hook observations. Use since to continue from the last sequence.",
    parameters: {
      since: { type: "integer", description: "Return events with sequence greater than this value." },
      limit: { type: "integer", description: "Maximum number of events, from 1 to 500." },
    },
    execute: async ({ since = 0, limit = 100 } = {}) => {
      const query = new URLSearchParams({
        since: String(Math.max(0, Number(since) || 0)),
        limit: String(Math.min(500, Math.max(1, Number(limit) || 100))),
      });
      return request("GET", `/hook/events?${query}`);
    },
  });

  register(ctx, defineTool, {
    name: "dsh_hook_rules_get",
    description: "Read the current declarative Hook rules from the LSPosed module.",
    parameters: {},
    execute: async () => request("GET", "/hook/rules"),
  });

  register(ctx, defineTool, {
    name: "dsh_hook_rules_update",
    description: "Validate and apply a complete declarative Hook configuration snapshot.",
    parameters: {
      config: { type: "object", required: true, description: "Complete Hook config: schemaVersion, targetPackage and rules; optional observeClassLoads=true enables bounded metadata-only ClassLoader observation." },
    },
    execute: async ({ config }) => {
      if (!config || typeof config !== "object" || Array.isArray(config)) {
        throw new Error("config must be a JSON object");
      }
      return request("POST", "/hook/rules", config);
    },
  });

  register(ctx, defineTool, {
    name: "dsh_hook_discover",
    description: "Discover target-app class and method signatures by package prefix; returns metadata only.",
    parameters: {
      prefix: { type: "string", description: "Class/package prefix, for example com.openai." },
      className: { type: "string", description: "Optional exact class name to inspect." },
      limit: { type: "integer", description: "Maximum metadata records, from 1 to 500." },
      cursor: { type: "integer", description: "Page cursor returned by the previous discovery result." },
    },
    execute: async ({ prefix = "com.openai.", className, limit = 100, cursor = 0 } = {}) => request("POST", "/hook/discover", {
      prefix: String(prefix),
      ...(className ? { className: String(className) } : {}),
      limit: Math.min(500, Math.max(1, Number(limit) || 100)),
      cursor: Math.max(0, Number(cursor) || 0),
    }),
  });

  register(ctx, defineTool, {
    name: "dsh_hook_discover_status",
    description: "Read the lifecycle state and latest result for a previously submitted discovery request.",
    parameters: {
      requestId: { type: "string", required: true, description: "Discovery request ID returned by dsh_hook_discover." },
    },
    execute: async ({ requestId }) => {
      if (typeof requestId !== "string" || !/^[a-f0-9-]{16,64}$/i.test(requestId)) {
        throw new Error("requestId is invalid");
      }
      const query = new URLSearchParams({ requestId });
      return request("GET", "/hook/discover?" + query.toString());
    },
  });

  register(ctx, defineTool, {
    name: "dsh_hook_discover_cancel",
    description: "Cancel a queued or running discovery request.",
    parameters: {
      requestId: { type: "string", required: true, description: "Discovery request ID returned by dsh_hook_discover." },
    },
    execute: async ({ requestId }) => {
      if (typeof requestId !== "string" || !/^[a-f0-9-]{16,64}$/i.test(requestId)) {
        throw new Error("requestId is invalid");
      }
      return request("POST", "/hook/discover/cancel", { requestId });
    },
  });

  register(ctx, defineTool, {
    name: "dsh_hook_enable",
    description: "Enable or disable the LSPosed Hook runtime while keeping the module installed.",
    parameters: {
      enabled: { type: "boolean", required: true, description: "Whether Hook callbacks should be active." },
    },
    execute: async ({ enabled }) => {
      if (typeof enabled !== "boolean") throw new Error("enabled must be boolean");
      return request("POST", "/hook/control", { enabled });
    },
  });
}

export { apply };
