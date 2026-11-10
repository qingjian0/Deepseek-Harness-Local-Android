#!/usr/bin/env python3
"""Exercise the schema diagnostic against DSH-style cumulative zstd frames."""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, "scripts", "measure-tool-schema.mjs")
NODE = shutil.which("node")

if not NODE:
    print("FAIL  Node.js is required to test measure-tool-schema.mjs")
    sys.exit(1)

result = subprocess.run(
    [NODE, SCRIPT, "--self-test"],
    cwd=ROOT,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
)

if result.returncode != 0:
    print("FAIL  cumulative zstd snapshot self-test")
    if result.stdout:
        print(result.stdout.rstrip())
    if result.stderr:
        print(result.stderr.rstrip())
    sys.exit(result.returncode or 1)

if "PASS incremental zstd frame chain" not in result.stdout:
    print("FAIL  self-test did not emit its success marker")
    print(result.stdout.rstrip())
    sys.exit(1)

print("PASS  measure-tool-schema cumulative snapshot decoder")
