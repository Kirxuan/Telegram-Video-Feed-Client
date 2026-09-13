"""Offline ELF and APK alignment proof. Does not load native code or contact devices."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def inspect(apk):
    results = []
    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as source:
        for entry in archive.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            data = archive.read(entry)
            if data[:6] != b"\x7fELF\x02\x01":
                raise ValueError("Expected 64-bit little-endian ELF: " + entry.filename)
            machine = struct.unpack_from("<H", data, 18)[0]
            phoff = struct.unpack_from("<Q", data, 32)[0]
            phsize, phcount = struct.unpack_from("<HH", data, 54)
            loads = []
            for i in range(phcount):
                ptype, flags, offset, virtual, _, _, _, align = struct.unpack_from(
                    "<IIQQQQQQ", data, phoff + i * phsize
                )
                if ptype == 1:
                    loads.append({"offset": offset, "vaddr": virtual, "alignment": align,
                                  "aligned16KiB": align >= 16384 and (offset - virtual) % 16384 == 0})
            source.seek(entry.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", source.read(4))
            apk_offset = entry.header_offset + 30 + name_size + extra_size
            # Compressed libraries are extracted by Android. They have no mmap alignment proof.
            packaged = entry.compress_type == zipfile.ZIP_STORED and apk_offset % 16384 == 0
            results.append({"entry": entry.filename, "sha256": hashlib.sha256(data).hexdigest(),
                            "machine": machine, "loads": loads, "apkDataOffset": apk_offset,
                            "storedAndAligned16KiB": packaged,
                            "elfAligned16KiB": bool(loads) and all(x["aligned16KiB"] for x in loads)})
    return {"apk": Path(apk).name, "apkSha256": hashlib.sha256(Path(apk).read_bytes()).hexdigest(),
            "libraries": results,
            "staticPass": bool(results) and all(x["machine"] == 183 and x["elfAligned16KiB"] and
                                               x["storedAndAligned16KiB"] for x in results),
            "runtime16KiB": "NOT_VERIFIED", "deviceActions": "none"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.apk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("NATIVE_16K_STATIC=" + ("PASS" if report["staticPass"] else "FAIL"))
    print("RUNTIME_16K=NOT_VERIFIED")
    print("REPORT=" + str(args.output))
    raise SystemExit(0 if report["staticPass"] else 1)
