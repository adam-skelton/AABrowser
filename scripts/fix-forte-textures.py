"""Fix Forte looking smeared/blotchy on Maps 3D.

The Sketchfab bake packs thousands of tiny specular islands into a 2048 atlas
and stores lighting in the albedo. A later brighten pass then used that albedo
as an emissive map, so baked highlights glow. UVs also wrap outside 0-1; clamp
sampling smears atlas-edge pixels across the body.

This keeps wheels/lamps/plates but: kills 1px sparkles, fills empty atlas
gaps so mips do not bleed black, lifts the near-black paint, wraps UVs, and
uses REPEAT + linear filtering with no emissive/normal/metal-rough maps.
"""
from __future__ import annotations

import json
import struct
from io import BytesIO
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from scipy.ndimage import distance_transform_edt, label

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "pages" / "forte.glb"
DST = ROOT / "pages" / "forte.glb"
FILL = [0.08, 0.08, 0.09]


def read_glb(path: Path):
    data = path.read_bytes()
    json_len = struct.unpack_from("<I", data, 12)[0]
    js = json.loads(data[20 : 20 + json_len].split(b"\x00")[0])
    binary = data[20 + json_len + 8 :]
    return js, binary


def view_blob(binary: bytes, view: dict) -> bytes:
    start = view.get("byteOffset", 0)
    return binary[start : start + view["byteLength"]]


def wrap_interleaved_uvs(blob: bytes, stride: int, uv_offset: int) -> bytes:
    if stride < 8 or stride % 4 or uv_offset % 4:
        return blob
    raw = np.frombuffer(blob, dtype=np.uint8).copy()
    floats = raw.view("<f4").reshape(-1, stride // 4)
    col = uv_offset // 4
    uv = floats[:, col : col + 2]
    uv -= np.floor(uv)
    return raw.tobytes()


def clean_albedo(png: bytes) -> bytes:
    im = Image.open(BytesIO(png)).convert("RGBA")
    src = np.asarray(im)[..., :3]
    med = np.asarray(
        Image.fromarray(src).filter(ImageFilter.MedianFilter(size=3)),
        dtype=np.float32,
    )
    lum = med.mean(axis=-1)
    # Tiny dark UV gutters smear under mipmaps. Keep large dark islands
    # (glass, tires, grille) so the car does not turn into a silver slab.
    dark = lum < 40
    gutter = dark
    if dark.any():
        lab, _ = label(dark)
        sizes = np.bincount(lab.ravel())
        gutter = dark & (sizes[lab] < 2048)
    if gutter.any() and (~gutter).any():
        _, (iy, ix) = distance_transform_edt(gutter, return_indices=True)
        filled = med.copy()
        filled[gutter] = med[iy[gutter], ix[gutter]]
    else:
        filled = med
    # Lift near-black Sketchfab lighting so Maps PBR has a readable base color.
    x = np.clip(filled / 255.0, 0.0, 1.0)
    x = np.clip(np.power(x, 0.72) * 1.22 + 0.10, 0.0, 1.0)
    out = np.dstack([(x * 255.0 + 0.5).astype(np.uint8), np.full(lum.shape, 255, np.uint8)])
    buf = BytesIO()
    Image.fromarray(out, "RGBA").save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def write_glb(path: Path, js: dict, views: list[dict], blobs: list[bytes]):
    packed = bytearray()
    rebuilt = []
    for i, blob in enumerate(blobs):
        pad = (4 - (len(blob) % 4)) % 4
        rebuilt.append(
            {
                **{k: v for k, v in views[i].items() if k not in ("buffer", "byteOffset", "byteLength")},
                "buffer": 0,
                "byteOffset": len(packed),
                "byteLength": len(blob),
            }
        )
        packed.extend(blob)
        packed.extend(b"\x00" * pad)
    js = dict(js)
    js["bufferViews"] = rebuilt
    js["buffers"] = [{"byteLength": len(packed)}]
    json_bytes = json.dumps(js, separators=(",", ":")).encode("utf-8")
    json_bytes += b" " * ((4 - (len(json_bytes) % 4)) % 4)
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
    albedo_view_i = js["images"][0]["bufferView"]
    albedo = clean_albedo(view_blob(binary, js["bufferViews"][albedo_view_i]))

    keep = set()
    for acc in js.get("accessors", []):
        if "bufferView" in acc:
            keep.add(acc["bufferView"])
    keep.add(albedo_view_i)

    remap = {}
    blobs = []
    views = []
    for i, view in enumerate(js["bufferViews"]):
        if i not in keep:
            continue
        remap[i] = len(blobs)
        blob = view_blob(binary, view)
        if i == albedo_view_i:
            blob = albedo
        elif view.get("byteStride") == 48:
            blob = wrap_interleaved_uvs(blob, 48, 40)
        blobs.append(blob)
        views.append(view)

    for acc in js.get("accessors", []):
        if "bufferView" in acc:
            acc["bufferView"] = remap[acc["bufferView"]]

    js["images"] = [{"bufferView": remap[albedo_view_i], "mimeType": "image/png"}]
    js["textures"] = [{"sampler": 0, "source": 0}]
    js["samplers"] = [
        {"magFilter": 9729, "minFilter": 9729, "wrapS": 10497, "wrapT": 10497}
    ]
    js["materials"] = [
        {
            "name": "forte-paint",
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": 0},
                "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
                "metallicFactor": 0.04,
                "roughnessFactor": 0.55,
            },
            "emissiveFactor": FILL,
        }
    ]
    js.pop("extensionsUsed", None)
    js.pop("extensionsRequired", None)
    write_glb(DST, js, views, blobs)
    print(f"Wrote {DST} ({DST.stat().st_size} bytes) albedo {len(albedo)} bytes")


if __name__ == "__main__":
    main()
