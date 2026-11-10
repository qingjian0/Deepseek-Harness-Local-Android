#!/usr/bin/env node
/**
 * Measure model-facing tool schemas from a DSH session.v4.jsonl.zstd log.
 *
 * This intentionally prints aggregate schema sizes only. It does not print
 * prompts, messages, tool arguments, tool results, credentials, or schema text.
 *
 * Usage:
 *   node scripts/measure-tool-schema.mjs /path/to/session.v4.jsonl.zstd
 */
import fs from "node:fs";
import zlib from "node:zlib";

const ZSTD_MAGIC = 0xfd2fb528;

function scanFrames(buf) {
  const frames = [];
  let off = 0;
  while (off + 4 <= buf.length) {
    const start = off;
    if (buf.readUInt32LE(off) !== ZSTD_MAGIC) break;
    off += 4;
    if (off >= buf.length) break;
    const descriptor = buf.readUInt8(off++);
    if ((descriptor & 24) !== 0) break;
    const contentSizeFlag = descriptor >>> 6;
    const singleSegment = (descriptor & 32) !== 0;
    const dictionaryFlag = descriptor & 3;
    const dictionaryBytes = dictionaryFlag === 3 ? 4 : dictionaryFlag;
    const contentSizeBytes = contentSizeFlag === 0
      ? (singleSegment ? 1 : 0)
      : 1 << contentSizeFlag;
    const headerBytes = (singleSegment ? 0 : 1) + dictionaryBytes + contentSizeBytes;
    if (off + headerBytes > buf.length) break;
    off += headerBytes;

    let complete = true;
    for (;;) {
      if (off + 3 > buf.length) { complete = false; break; }
      const blockHeader = buf.readUIntLE(off, 3);
      off += 3;
      const lastBlock = (blockHeader & 1) === 1;
      const blockType = (blockHeader >>> 1) & 3;
      const blockSize = blockHeader >>> 3;
      const bytes = blockType === 1 ? 1 : blockSize;
      if (off + bytes > buf.length) { complete = false; break; }
      off += bytes;
      if (lastBlock) break;
    }
    if (!complete) break;
    frames.push([start, off]);
  }
  return frames;
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

const frames = scanFrames(buf);
if (frames.length === 0) {
  process.stderr.write("No complete zstd frames found.\n");
  process.exit(3);
}

let requestHeaderCount = 0;
let requestContextCount = 0;
let latestHeader;
let malformedLines = 0;
for (const [start, end] of frames) {
  let plain;
  try {
    plain = zlib.zstdDecompressSync(buf.subarray(start, end)).toString("utf8");
  } catch {
    continue;
  }
  for (const line of plain.split(/\r?\n/)) {
    if (!line.trim()) continue;
    let event;
    try { event = JSON.parse(line); } catch { malformedLines++; continue; }
    if (event?.type === "request/context") requestContextCount++;
    if (event?.type === "request/header") {
      requestHeaderCount++;
      latestHeader = event?.data?.header;
    }
  }
}

if (!latestHeader) {
  process.stderr.write("No request/header event found in this session.\n");
  process.exit(4);
}

const summary = schemaSummary(latestHeader.tools);
process.stdout.write(JSON.stringify({
  fileBytes: buf.length,
  completeFrames: frames.length,
  requestHeaderSnapshots: requestHeaderCount,
  requestContextEvents: requestContextCount,
  latestHeaderReason: "latest request/header snapshot",
  ...summary,
  note: "Exact UTF-8 byte counts for the persisted schema object; token count depends on the selected model tokenizer and is not inferred here.",
  malformedLines,
}, null, 2) + "\n");
