#!/usr/bin/env python3
"""Verify APK runtime assets, ABI, and the actual Android application ID.

Usage:
  python scripts/verify-apk-assets.py app-debug.apk \
    --expect-package full --expect-abi arm64-v8a \
    --expect-application-id dsh.dsh.mobile
"""
import argparse
import hashlib
import json
import struct
import sys
import zipfile

EM = {"arm64-v8a": 183, "x86_64": 62}


def fail(msg: str) -> None:
    print(f"FAIL: {msg}", file=sys.stderr)
    raise SystemExit(1)


def _length8(data: bytes, pos: int) -> tuple[int, int]:
    first = data[pos]
    pos += 1
    if first & 0x80:
        return ((first & 0x7F) << 8) | data[pos], pos + 1
    return first, pos


def _length16(data: bytes, pos: int) -> tuple[int, int]:
    value = struct.unpack_from("<H", data, pos)[0]
    pos += 2
    if value & 0x8000:
        return ((value & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0], pos + 2
    return value, pos


def _string_pool(data: bytes, chunk: int, header_size: int) -> list[str]:
    count, _styles, flags, strings_start, _styles_start = struct.unpack_from("<IIIII", data, chunk + 8)
    offsets = chunk + header_size
    utf8 = bool(flags & 0x100)
    result = []
    for index in range(count):
        relative = struct.unpack_from("<I", data, offsets + index * 4)[0]
        pos = chunk + strings_start + relative
        if utf8:
            _utf16_length, pos = _length8(data, pos)
            byte_length, pos = _length8(data, pos)
            value = data[pos:pos + byte_length].decode("utf-8")
        else:
            char_length, pos = _length16(data, pos)
            value = data[pos:pos + char_length * 2].decode("utf-16le")
        result.append(value)
    return result


def manifest_package(manifest: bytes) -> str:
    """Read the manifest package attribute from Android's binary XML format."""
    if manifest[:2] == b"<?":
        import xml.etree.ElementTree as ET
        return ET.fromstring(manifest).attrib.get("package", "")

    strings: list[str] = []
    pos = 8  # RES_XML header
    while pos + 8 <= len(manifest):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", manifest, pos)
        if chunk_size < header_size or pos + chunk_size > len(manifest):
            raise ValueError("malformed AndroidManifest.xml chunk")
        if chunk_type == 0x0001:  # RES_STRING_POOL_TYPE
            strings = _string_pool(manifest, pos, header_size)
        elif chunk_type == 0x0102 and strings:  # RES_XML_START_ELEMENT_TYPE
            name_index = struct.unpack_from("<I", manifest, pos + 20)[0]
            element_name = strings[name_index] if name_index < len(strings) else ""
            if element_name == "manifest":
                attribute_start, attribute_size, attribute_count = struct.unpack_from("<HHH", manifest, pos + 24)
                attributes = pos + 16 + attribute_start
                for index in range(attribute_count):
                    item = attributes + index * attribute_size
                    _namespace, name_index, raw_value = struct.unpack_from("<III", manifest, item)
                    name = strings[name_index] if name_index < len(strings) else ""
                    if name != "package":
                        continue
                    if raw_value != 0xFFFFFFFF:
                        return strings[raw_value] if raw_value < len(strings) else ""
                    value_size, _res0, value_type, value_data = struct.unpack_from("<HBBI", manifest, item + 12)
                    if value_type == 0x03 and value_data < len(strings):
                        return strings[value_data]
                    raise ValueError(f"unsupported manifest package value type {value_type}")
        pos += chunk_size
    raise ValueError("manifest package attribute not found")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--expect-package", choices=["full", "noruntime"], required=True)
    ap.add_argument("--expect-abi", required=True)
    ap.add_argument("--expect-application-id", required=True)
    args = ap.parse_args()

    with zipfile.ZipFile(args.apk) as z:
        names = set(z.namelist())
        if "AndroidManifest.xml" not in names:
            fail("missing AndroidManifest.xml")
        try:
            actual_application_id = manifest_package(z.read("AndroidManifest.xml"))
        except (ValueError, struct.error, UnicodeError) as exc:
            fail(f"cannot read Android application ID: {exc}")
        if actual_application_id != args.expect_application_id:
            fail(f"applicationId mismatch: {actual_application_id!r} != {args.expect_application_id!r}")

        for rel in (
            "assets/hardssh/lib/index.js",
            "assets/hardssh/node_modules/ssh2/package.json",
            "assets/hardssh/node_modules/ws/package.json",
        ):
            if rel not in names:
                fail(f"missing {rel}")

        if "assets/runtime/MANIFEST.json" not in names:
            fail("missing assets/runtime/MANIFEST.json")
        man = json.loads(z.read("assets/runtime/MANIFEST.json"))
        if man.get("schema") != 2:
            fail(f"unexpected manifest schema: {man.get('schema')}")
        if man.get("package") != args.expect_package:
            fail(f"package mismatch: {man.get('package')} != {args.expect_package}")
        if man.get("abi") != args.expect_abi:
            fail(f"abi mismatch: {man.get('abi')} != {args.expect_abi}")

        runtime = man.get("runtime") or {}
        has_zip = "assets/runtime.zip" in names
        if args.expect_package == "full":
            if not has_zip:
                fail("full package missing assets/runtime.zip")
            import os
            import tempfile

            fd, tmp = tempfile.mkstemp(suffix=".zip")
            os.close(fd)
            h = hashlib.sha256()
            try:
                with z.open("assets/runtime.zip") as f, open(tmp, "wb") as out:
                    for b in iter(lambda: f.read(1 << 20), b""):
                        h.update(b)
                        out.write(b)
                if h.hexdigest() != runtime.get("sha256"):
                    fail("runtime.zip sha256 mismatch with manifest")
                size = z.getinfo("assets/runtime.zip").file_size
                if runtime.get("size") and size != runtime["size"]:
                    fail(f"runtime.zip size mismatch: {size} != {runtime['size']}")
                with zipfile.ZipFile(tmp) as rz:
                    node = rz.read("bin/node")[:20]
                    if node[:4] != b"\\x7fELF":
                        fail("runtime bin/node is not ELF")
                    got = int.from_bytes(node[18:20], "little")
                    want = EM[args.expect_abi]
                    if got != want:
                        fail(f"runtime bin/node e_machine={got}, expected {want}")
            finally:
                os.unlink(tmp)
        else:
            if has_zip:
                fail("noruntime package must not contain assets/runtime.zip")
            urls = runtime.get("urls") or []
            if not urls:
                fail("noruntime package has no runtime download URL")
            rolling = any("/latest/" in u for u in urls)
            if rolling and runtime.get("sha256"):
                fail("rolling latest URL must not carry a pinned sha (App resolves via index)")
            if not rolling and not runtime.get("sha256"):
                fail("tag-pinned runtime URL must carry sha256")

        hard = man.get("hardssh") or {}
        if not hard.get("version"):
            fail("manifest hardssh.version missing")

    print(
        f"OK {args.apk}: {actual_application_id}, "
        f"{args.expect_package}/{args.expect_abi}, "
        f"runtime={runtime.get('version')} hardssh={hard.get('version')}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
