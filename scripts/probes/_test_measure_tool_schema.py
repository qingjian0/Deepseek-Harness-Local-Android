#!/usr/bin/env python3
"""Exercise both zstd decoding and the complete schema-report path."""
import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, "scripts", "measure-tool-schema.mjs")
NODE = shutil.which("node")

if not NODE:
    print("FAIL  Node.js is required to test measure-tool-schema.mjs")
    sys.exit(1)

def run(args):
    return subprocess.run(
        args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )

# Keep the decoder regression test, but do not treat it as coverage of the report path.
decoder = run([NODE, SCRIPT, "--self-test"])
if decoder.returncode != 0 or "PASS incremental zstd frame chain" not in decoder.stdout:
    print("FAIL  incremental zstd frame-chain self-test")
    print(decoder.stdout.rstrip())
    print(decoder.stderr.rstrip())
    sys.exit(decoder.returncode or 1)

with tempfile.TemporaryDirectory(prefix="dsh-schema-probe-") as temp:
    live_path = os.path.join(temp, "synthetic-live.jsonl.zstd")
    snapshot_path = os.path.join(temp, "synthetic-frozen-snapshot.jsonl.zstd")

    # Two append-only frames: 3 completed assistant messages and one attempt.
    # The request/header event establishes the active tool schema for all 3.
    generator = r"""
const fs = require('node:fs');
const zlib = require('node:zlib');
const output = process.argv[1];
if (typeof zlib.zstdCompressSync !== 'function') {
  throw new Error('Node.js zstdCompressSync is required');
}
const tools = [{name:'demo',description:'fixture',parameters:{type:'object'}}];
const first = [
  {seq:0,type:'request/header',data:{header:{tools}}},
  {seq:1,type:'assistant/message',data:{}},
  {seq:2,type:'assistant/attempt',data:{}}
];
const second = [
  {seq:3,type:'assistant/message',data:{}},
  {seq:4,type:'assistant/message',data:{}}
];
const frame = events => zlib.zstdCompressSync(
  Buffer.from(events.map(event => JSON.stringify(event)).join('\n') + '\n')
);
fs.writeFileSync(output, Buffer.concat([frame(first), frame(second)]));
"""
    generated = run([NODE, "-e", generator, live_path])
    if generated.returncode != 0:
        print("FAIL  synthetic zstd session generation")
        print(generated.stdout.rstrip())
        print(generated.stderr.rstrip())
        sys.exit(generated.returncode or 1)

    # Freeze the fixture before measurement, matching the documented workflow
    # for a live append-only DSH session log.
    shutil.copyfile(live_path, snapshot_path)
    result = run([NODE, SCRIPT, snapshot_path])
    if result.returncode != 0:
        print("FAIL  full schema report path on frozen synthetic snapshot")
        print(result.stdout.rstrip())
        print(result.stderr.rstrip())
        sys.exit(result.returncode or 1)

    try:
        report = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        print("FAIL  report is not valid JSON:", error)
        print(result.stdout.rstrip())
        sys.exit(1)

    tools = [{"name": "demo", "description": "fixture", "parameters": {"type": "object"}}]
    expected_schema_bytes = len(json.dumps(tools, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    checks = {
        "modelRequests": report.get("modelRequests") == 3,
        "modelRequestCountBasis": report.get("modelRequestCountBasis") == "assistant/message",
        "assistantMessageEvents": report.get("assistantMessageEvents") == 3,
        "assistantAttemptEvents": report.get("assistantAttemptEvents") == 1,
        "decodedFrames": report.get("decodedFrames") == 2,
        "rawEvents": report.get("rawEvents") == 5,
        "uniqueEvents": report.get("uniqueEvents") == 5,
        "duplicateEvents": report.get("duplicateEvents") == 0,
        "schemaBytesPerRequest.min": report.get("schemaBytesPerRequest", {}).get("min") == expected_schema_bytes,
        "schemaBytesPerRequest.max": report.get("schemaBytesPerRequest", {}).get("max") == expected_schema_bytes,
        "schemaBytesPerRequest.average": report.get("schemaBytesPerRequest", {}).get("average") == expected_schema_bytes,
        "schemaBytesPerRequest.cumulativeAcrossRequests": report.get("schemaBytesPerRequest", {}).get("cumulativeAcrossRequests") == expected_schema_bytes * 3,
    }
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        print("FAIL  full report assertions: " + ", ".join(failed))
        print(json.dumps(report, ensure_ascii=False, indent=2))
        sys.exit(1)

print("PASS  measure-tool-schema full report path: frozen 2-frame fixture, 3 assistant/message requests, 1 assistant/attempt, correct schema totals")
