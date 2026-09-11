"""Lift Forte materials so Maps 3D lighting does not leave the car in shadow."""
from __future__ import annotations

import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "pages" / "forte.glb"
DST = ROOT / "pages" / "forte.glb"


def read_glb(path: Path):
    data = path.read_bytes()
    json_len = struct.unpack_from("<I", data, 12)[0]
    js = json.loads(data[20 : 20 + json_len].split(b"\x00")[0])
    binary = data[20 + json_len + 8 :]
    return js, binary


def write_glb(path: Path, js: dict, binary: bytes):
    packed = binary
    js = dict(js)
    js["buffers"] = [{"byteLength": len(packed)}]
    json_bytes = json.dumps(js, separators=(",", ":")).encode("utf-8")
    json_pad = (4 - (len(json_bytes) % 4)) % 4
    json_bytes = json_bytes + (b" " * json_pad)
    total = 12 + 8 + len(json_bytes) + 8 + len(packed)
    path.write_bytes(
        struct.pack("<4sII", b"glTF", 2, total)
        + struct.pack("<II", len(json_bytes), 0x4E4F534A)
        + json_bytes
        + struct.pack("<II", len(packed), 0x004E4942)
        + packed
    )


def main():
    js, binary = read_glb(SRC)
    albedo = 0
    if js.get("images"):
        albedo = 0
    for mat in js.get("materials", []):
        pbr = mat.setdefault("pbrMetallicRoughness", {})
        pbr["baseColorFactor"] = [1.0, 1.0, 1.0, 1.0]
        # Dielectric paint: metal-rough metal reads as a black slab in Maps lighting.
        pbr["metallicFactor"] = 0.0
        pbr["roughnessFactor"] = 0.42
        # Soft fill from the albedo so shadows are readable without going unlit/flat.
        mat["emissiveFactor"] = [0.28, 0.28, 0.28]
        mat.pop("extensions", None)
        if js.get("textures"):
            mat["emissiveTexture"] = {"index": albedo}
    used = [ext for ext in (js.get("extensionsUsed") or []) if ext != "KHR_materials_unlit"]
    if used:
        js["extensionsUsed"] = used
    else:
        js.pop("extensionsUsed", None)
    js.pop("extensionsRequired", None)
    write_glb(DST, js, binary)
    print(f"Wrote {DST} ({DST.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
