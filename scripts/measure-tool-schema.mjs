#!/usr/bin/env node
/**
 * Measure model-facing tool schemas from a DSH append-only
 * session.v4.jsonl.zstd log.
 *
 * DSH writes an append-only zstd frame chain: each frame contains only the
 * newly appended JSONL events. Decode each frame independently in file order.
 * Keep seq-based de-duplication as a defensive integrity check, not as an
 * assumption that frames repeat the complete session history.
 *
 * Only aggregate sizes and event counts are printed. Prompts, messages, tool
 * arguments/results, credentials, and schema text are never emitted.
 *
 * Usage:
 *   node scripts/measure-tool-schema.mjs /path/to/session.v4.jsonl.zstd
 */
import fs from "node:fs";
import zlib from "node:zlib";

const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

function findMagicOffsets(buf) {
  const offsets = [];
  let from = 0;
  while (from <= buf.length - ZSTD_MAGIC.length) {
    const at = buf.indexOf(ZSTD_MAGIC, from);
    if (at < 0) break;
    offsets.push(at);
    from = at + ZSTD_MAGIC.length;
  }
  return offsets;
}

function decodeZstdFrameChain(buf) {
  const offsets = findMagicOffsets(buf);
  const eventsBySeq = new Map();
  let decodedFrames = 0;
  let rawEvents = 0;
  let malformedLines = 0;

  for (let i = 0; i < offsets.length; i++) {
    const start = offsets[i];
    const end = i + 1 < offsets.length ? offsets[i + 1] : buf.length;
    if (end <= start + ZSTD_MAGIC.length) continue;

    let plain;
    try {
      // Decode this individual zstd frame. DSH's frame chain is incremental:
      // each frame contains only newly appended JSONL rows.
      plain = zlib.zstdDecompressSync(buf.subarray(start, end)).toString("utf8");
    } catch {
      // A magic-like byte sequence inside compressed data is not a frame.
      continue;
    }

    let frameHasEvents = false;
    for (const line of plain.split(/\r?\n/)) {
      if (!line.trim()) continue;
      let event;
      try {
        event = JSON.parse(line);
      } catch {
        malformedLines++;
        continue;
      }
      if (!event || typeof event !== "object" || Array.isArray(event)) {
        malformedLines++;
        continue;
      }
      rawEvents++;
      frameHasEvents = true;

      // seq is the event identity in DSH's append-only session log. Retain
      // defensive de-duplication in case a log contains a repeated event.
      const key = event.seq === undefined
        ? "json:" + JSON.stringify(event)
        : "seq:" + String(event.seq);
      eventsBySeq.set(key, event);
    }
    if (frameHasEvents) decodedFrames++;
  }

  const events = [...eventsBySeq.values()].sort((a, b) => {
    const an = Number(a?.seq);
    const bn = Number(b?.seq);
    if (Number.isFinite(an) && Number.isFinite(bn)) return an - bn;
    return String(a?.seq ?? "").localeCompare(String(b?.seq ?? ""));
  });

  return {
    events,
    frameCandidates: offsets.length,
    decodedFrames,
    rawEvents,
    uniqueEvents: events.length,
    duplicateEvents: Math.max(0, rawEvents - events.length),
    malformedLines,
  };
}

function schemaSummary(tools) {
  const rows = (Array.isArray(tools) ? tools : []).map((tool) => {
    const name = String(tool?.name ?? "(unnamed)");
    const descriptionBytes = Buffer.byteLength(String(tool?.description ?? ""), "utf8");
    const parametersBytes = Buffer.byteLength(JSON.stringify(tool?.parameters ?? {}), "utf8");
    const totalBytes = Buffer.byteLength(JSON.stringify(tool), "utf8");
    return { name, descriptionBytes, parametersBytes, totalBytes };
  }).sort((a, b) => b.totalBytes - a.totalBytes);
  return {
    toolCount: rows.length,
    schemaBytes: Buffer.byteLength(JSON.stringify(tools ?? []), "utf8"),
    descriptionBytes: rows.reduce((n, row) => n + row.descriptionBytes, 0),
    parameterBytes: rows.reduce((n, row) => n + row.parametersBytes, 0),
    largestTools: rows.slice(0, 10),
  };
}

function runSelfTest() {
  // Model an incremental zstd frame chain: the second frame contains only
  // events appended after the first frame, not a cumulative copy.
  const first = [
    { seq: 1, type: "request/header", data: { reason: "initial", header: { tools: [{ name: "demo", description: "test", parameters: { type: "object" } }] } } },
    { seq: 2, type: "assistant/message", data: {} },
  ];
  const second = [
    { seq: 3, type: "assistant/attempt", data: {} },
    { seq: 4, type: "assistant/message", data: {} },
  ];
  const frame = (events) => zlib.zstdCompressSync(
    Buffer.from(events.map((event) => JSON.stringify(event)).join("\n") + "\n", "utf8"),
  );
  const fixture = Buffer.concat([frame(first), frame(second)]);
  const decoded = decodeZstdFrameChain(fixture);
  const messageCount = decoded.events.filter((event) => event.type === "assistant/message").length;
  const attemptCount = decoded.events.filter((event) => event.type === "assistant/attempt").length;
  if (decoded.frameCandidates !== 2 || decoded.decodedFrames !== 2 ||
      decoded.rawEvents !== 4 || decoded.uniqueEvents !== 4 ||
      decoded.duplicateEvents !== 0 || messageCount !== 2 || attemptCount !== 1) {
    throw new Error("incremental zstd frame-chain fixture failed: " +
      JSON.stringify({
        frameCandidates: decoded.frameCandidates,
        decodedFrames: decoded.decodedFrames,
        rawEvents: decoded.rawEvents,
        uniqueEvents: decoded.uniqueEvents,
        duplicateEvents: decoded.duplicateEvents,
        assistantMessages: messageCount,
        assistantAttempts: attemptCount,
      }));
  }
  process.stdout.write("PASS incremental zstd frame chain: 2 frames, 4 events, 0 duplicates; assistant/message=2, assistant/attempt=1\n");
}

if (process.argv[2] === "--self-test") {
  try {
    runSelfTest();
  } catch (error) {
    process.stderr.write("Self-test failed: " + error.message + "\n");
    process.exit(1);
  }
  process.exit(0);
}

const file = process.argv[2];
if (!file) {
  process.stderr.write("Usage: node scripts/measure-tool-schema.mjs <session.v4.jsonl.zstd>\n");
  process.exit(2);
}

let buf;
try {
  buf = fs.readFileSync(file);
} catch (error) {
  process.stderr.write("Read failed: " + error.message + "\n");
  process.exit(2);
}

const decoded = decodeZstdFrameChain(buf);
if (decoded.events.length === 0) {
  process.stderr.write(
    "No DSH session events decoded from zstd frame chain " +
    `(magicCandidates=${decoded.frameCandidates}, decodedFrames=${decoded.decodedFrames}).\n`,
  );
  process.exit(3);
}

const events = decoded.events;
const headers = events.filter((event) => event?.type === "request/header");
const contexts = events.filter((event) => event?.type === "request/context");
const attempts = events.filter((event) => event?.type === "assistant/attempt");
const messages = events.filter((event) => event?.type === "assistant/message");

if (headers.length === 0) {
  process.stderr.write(
    "No request/header event found after frame decoding " +
    `(rawEvents=${decoded.rawEvents}, uniqueEvents=${decoded.uniqueEvents}).\n`,
  );
  process.exit(4);
}

const snapshots = headers.map((event) => {
  const header = event?.data?.header;
  const summary = schemaSummary(header?.tools);
  return {
    seq: event.seq,
    reason: event?.data?.reason,
    toolCount: summary.toolCount,
    schemaBytes: summary.schemaBytes,
  };
});
const latestHeaderEvent = headers[headers.length - 1];
const latestHeader = latestHeaderEvent?.data?.header;
// assistant/message tracks completed assistant model outputs in the session
// event stream. assistant/attempt is an error/retry lifecycle event and is not
// a valid denominator for total model requests.
const requestEvents = messages;
const requestCountBasis = "assistant/message";
let activeHeader;
let requestSchemaBytesTotal = 0;
let requestSchemaBytesMin = Infinity;
let requestSchemaBytesMax = 0;
let requestsWithTools = 0;

for (const event of events) {
  if (event?.type === "request/header") {
    activeHeader = event?.data?.header;
    continue;
  }
  if (event?.type !== requestType) continue;
  const tools = activeHeader?.tools ?? [];
  const bytes = Buffer.byteLength(JSON.stringify(tools), "utf8");
  if (tools.length > 0) requestsWithTools++;
  requestSchemaBytesTotal += bytes;
  requestSchemaBytesMin = Math.min(requestSchemaBytesMin, bytes);
  requestSchemaBytesMax = Math.max(requestSchemaBytesMax, bytes);
}

const summary = schemaSummary(latestHeader.tools);
process.stdout.write(JSON.stringify({
  fileBytes: buf.length,
  frameCandidates: decoded.frameCandidates,
  decodedFrames: decoded.decodedFrames,
  rawEvents: decoded.rawEvents,
  uniqueEvents: decoded.uniqueEvents,
  duplicateEvents: decoded.duplicateEvents,
  requestHeaderSnapshots: headers.length,
  requestContextEvents: contexts.length,
  assistantAttemptEvents: attempts.length,
  assistantMessageEvents: messages.length,
  modelRequests: requestEvents.length,
  modelRequestCountBasis: requestCountBasis,
  requestsWithTools,
  schemaBytesPerRequest: {
    min: requestEvents.length ? requestSchemaBytesMin : 0,
    max: requestSchemaBytesMax,
    average: requestEvents.length ? Math.round(requestSchemaBytesTotal / requestEvents.length) : 0,
    cumulativeAcrossRequests: requestSchemaBytesTotal,
  },
  schemaSnapshots: snapshots,
  latestHeader: summary,
  note: "Schema sizes are exact compact UTF-8 JSON byte counts reconstructed from request/header. Each model request is attributed the latest active header snapshot. Token count requires the selected model tokenizer and is not estimated.",
  malformedLines: decoded.malformedLines,
}, null, 2) + "\n");
