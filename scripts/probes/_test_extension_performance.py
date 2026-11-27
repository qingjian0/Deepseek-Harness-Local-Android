#!/usr/bin/env python3
"""Regression guards for bounded extension downloads and shared embedding cache.

These source-contract checks complement (but do not replace) Kotlin/JVM tests.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
FILES = {
    "extensions": ROOT / "app/src/main/java/app/dsh/mobile/engine/ExtensionManager.kt",
    "picker": ROOT / "app/src/main/java/app/dsh/mobile/engine/FastPicker.kt",
    "model": ROOT / "app/src/main/java/app/dsh/mobile/engine/LocalModel.kt",
    "memory": ROOT / "app/src/main/java/app/dsh/mobile/engine/MemoryIndex.kt",
}
src = {name: path.read_text(encoding="utf-8") for name, path in FILES.items()}
checks = [
    ("dependency download phase is suspendable", "private suspend fun downloadPhase(" in src["extensions"]),
    ("per-extension package fan-out is bounded to four", "val packageSemaphore = Semaphore(4)" in src["extensions"]),
    ("independent package downloads use IO coroutines", "async(Dispatchers.IO)" in src["extensions"]),
    ("all package results preserve closure order", "}.awaitAll()" in src["extensions"] and "DownloadedDebs(debs, mainPkg.version, cacheDir, closure)" in src["extensions"]),
    ("download progress uses per-package atomic byte counters", "AtomicLongArray(closure.size)" in src["extensions"]),
    ("retry progress never moves a package counter backwards", "maxOf(completedBytes.get(idx), nextBytes)" in src["extensions"]),
    ("completed downloads publish their full byte count", "completedBytes.set(idx, pkg.size)" in src["extensions"]),
    ("package integrity remains SHA-256 checked", "RuntimeInstaller.sha256(file) == pkg.sha256.lowercase()" in src["extensions"]),
    ("corrupt package files are removed on hash failure", "runCatching { file.delete() }" in src["extensions"]),
    ("embedding cache has a fixed upper bound", "MAX_EMBEDDING_CACHE = 512" in src["picker"]),
    ("embedding cache access is synchronized and defensive", "synchronized(embeddingCache)" in src["picker"] and "copyOf()" in src["picker"]),
    ("single-text embedding normalizes cache key and request", "val normalized = text.trim()" in src["picker"] and "JSONArray().put(normalized)" in src["picker"]),
    ("batch embedding requests use normalized inputs", "val normalizedInputs = inputs.map { it.trim() }" in src["picker"] and "missing.map { normalizedInputs[it] }" in src["picker"]),
    ("embedding response cardinality is validated", "if (arr.length() != expect) return@runCatching null" in src["picker"]),
    ("model replacement invalidates cached vectors", "LocalEmbedder.clearEmbeddingCache()" in src["model"]),
    ("model preparation is serialized", bool(re.search(r"@Synchronized\s+fun ensureReady\(", src["model"]))),
    ("memory indexing reuses the shared embedding cache", "LocalEmbedder.embeddingVector(text)" in src["memory"]),
]
failed = 0
for label, ok in checks:
    print(("PASS" if ok else "FAIL") + "  " + label)
    failed += not ok
print(f"passed={len(checks) - failed} failed={failed}")
sys.exit(1 if failed else 0)
