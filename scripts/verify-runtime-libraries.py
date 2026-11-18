#!/usr/bin/env python3
"""Validate required runtime shared libraries, resolving deduplicated ZIP LINK aliases."""
import argparse
import zipfile

REQUIRED = ("libreadline.so.8", "libz.so.1", "libssl.so")
MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def resolve_entry(archive: zipfile.ZipFile, name: str, seen=()):
    if name in seen:
        raise ValueError(f"LINK cycle: {' -> '.join((*seen, name))}")
    try:
        info = archive.getinfo(name)
    except KeyError as exc:
        raise ValueError(f"missing ZIP entry: {name}") from exc
    comment = info.comment.decode("utf-8", "replace")
    if comment.startswith("LINK:"):
        return resolve_entry(archive, comment[len("LINK:"):], (*seen, name))
    return archive.read(info)


def verify(path: str, abi: str):
    expected = MACHINES[abi]
    with zipfile.ZipFile(path) as archive:
        for basename in REQUIRED:
            name = f"lib/{basename}"
            data = resolve_entry(archive, name)
            if len(data) < 64:
                raise ValueError(f"{name}: resolved payload is only {len(data)} bytes")
            if data[:4] != b"\x7fELF":
                raise ValueError(f"{name}: resolved payload is not ELF")
            if data[4] != 2 or data[5] != 1 or data[16] != 3 or data[17] != 0:
                raise ValueError(f"{name}: expected little-endian ELF64 ET_DYN shared object")
            machine = int.from_bytes(data[18:20], "little")
            if machine != expected:
                raise ValueError(f"{name}: e_machine={machine}, expected {expected}")
            print(f"OK {name}: {len(data)} bytes, ELF64 e_machine={machine}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("zip")
    parser.add_argument("--abi", choices=tuple(MACHINES), required=True)
    args = parser.parse_args()
    verify(args.zip, args.abi)


if __name__ == "__main__":
    main()
