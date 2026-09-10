"""Scale the Sketchfab Kia Forte to metres and recolour body paint light grey."""
from __future__ import annotations

import json
import struct
from io import BytesIO
from pathlib import Path

from PIL import Image
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "pages" / "forte.glb"
DST = ROOT / "pages" / "forte.glb"
TARGET_LENGTH_M = 4.64
LIGHT_GREY = np.array([196, 202, 208], dtype=np.float32)


def read_glb(path: Path):
    data = path.read_bytes()
    json_len = struct.unpack_from("<I", data, 12)[0]
    js = json.loads(data[20 : 20 + json_len])
    binary = data[20 + json_len + 8 :]
    return js, binary


def view_bytes(binary: bytes, view: dict) -> bytes:
    start = view.get("byteOffset", 0)
    return binary[start : start + view["byteLength"]]


def mesh_size(js: dict, binary: bytes):
    min_v = np.array([np.inf, np.inf, np.inf])
    max_v = np.array([-np.inf, -np.inf, -np.inf])
    for mesh in js["meshes"]:
        for prim in mesh["primitives"]:
            acc = js["accessors"][prim["attributes"]["POSITION"]]
            view = js["bufferViews"][acc["bufferView"]]
            start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
            count = acc["count"]
            arr = np.frombuffer(binary, dtype="<f4", count=count * 3, offset=start).reshape(count, 3)
            min_v = np.minimum(min_v, arr.min(axis=0))
            max_v = np.maximum(max_v, arr.max(axis=0))
    return (max_v - min_v).tolist()


def recolor_albedo(png: bytes) -> bytes:
    im = Image.open(BytesIO(png)).convert("RGBA")
    px = np.asarray(im).astype(np.float32)
    rgb = px[..., :3]
    luma = (0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]) / 255.0
    mx = rgb.max(axis=-1)
    mn = rgb.min(axis=-1)
    sat = np.divide(mx - mn, mx, out=np.zeros_like(mx), where=mx != 0)
    body = (luma < 0.45) & (sat < 0.28)
    mid = (luma < 0.62) & (sat < 0.18) & ~body
    t = np.zeros(luma.shape, dtype=np.float32)
    t[body] = np.clip((0.45 - luma[body]) / 0.45, 0, 1)
    t[mid] = 0.55
    out_rgb = rgb + (LIGHT_GREY - rgb) * t[..., None]
    px[..., :3] = np.clip(out_rgb, 0, 255)
    out_im = Image.fromarray(px.astype(np.uint8), "RGBA")
    buf = BytesIO()
    out_im.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def pad4(blob: bytes) -> bytes:
    return blob + (b"\x00" * ((4 - (len(blob) % 4)) % 4))


def write_glb(path: Path, js: dict, views: list[bytes]):
    packed = bytearray()
    rebuilt = []
    for i, blob in enumerate(views):
        offset = len(packed)
        packed.extend(pad4(blob))
        view = {"buffer": 0, "byteOffset": offset, "byteLength": len(blob)}
        for key, val in js["bufferViews"][i].items():
            if key not in ("buffer", "byteOffset", "byteLength"):
                view[key] = val
        rebuilt.append(view)
    js = dict(js)
    js["bufferViews"] = rebuilt
    js["buffers"] = [{"byteLength": len(packed)}]
    json_bytes = pad4(json.dumps(js, separators=(",", ":")).encode("utf-8"))
    total = 12 + 8 + len(json_bytes) + 8 + len(packed)
    header = struct.pack("<4sII", b"glTF", 2, total)
    json_chunk = struct.pack("<II", len(json_bytes), 0x4E4F534A) + json_bytes
    bin_chunk = struct.pack("<II", len(packed), 0x004E4942) + packed
    path.write_bytes(header + json_chunk + bin_chunk)


def scale_positions(js: dict, views: list[bytes], scale: float):
    for mesh in js["meshes"]:
        for prim in mesh["primitives"]:
            acc_index = prim["attributes"]["POSITION"]
            acc = js["accessors"][acc_index]
            view_index = acc["bufferView"]
            start = acc.get("byteOffset", 0)
            count = acc["count"]
            blob = bytearray(views[view_index])
            arr = np.frombuffer(blob, dtype="<f4", count=count * 3, offset=start).copy().reshape(count, 3)
            arr *= np.float32(scale)
            blob[start : start + count * 12] = arr.astype("<f4", copy=False).tobytes()
            if "min" in acc:
                acc["min"] = arr.min(axis=0).tolist()
            if "max" in acc:
                acc["max"] = arr.max(axis=0).tolist()
            views[view_index] = bytes(blob)


def main():
    js, binary = read_glb(SRC)
    size = mesh_size(js, binary)
    longest = max(size)
    scale = TARGET_LENGTH_M / longest if longest < 2.5 else 1.0
    print(f"size={size} scale={scale:.3f}")

    views = [view_bytes(binary, v) for v in js["bufferViews"]]
    scale_positions(js, views, scale)
    views[js["images"][0]["bufferView"]] = recolor_albedo(views[js["images"][0]["bufferView"]])

    mat = js["materials"][0]
    pbr = mat.setdefault("pbrMetallicRoughness", {})
    pbr["baseColorFactor"] = [0.92, 0.94, 0.96, 1.0]
    pbr["metallicFactor"] = 0.12
    pbr["roughnessFactor"] = 0.55
    mat["emissiveFactor"] = [0.10, 0.11, 0.12]

    write_glb(DST, js, views)
    print(f"Wrote {DST} ({DST.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
