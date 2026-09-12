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
import sys
from io import BytesIO
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy.ndimage import binary_closing, binary_dilation, distance_transform_edt, label

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "pages" / "forte.glb"
DST = ROOT / "pages" / "forte.glb"
FILL = [0.08, 0.08, 0.09]
# Restored albedo (HEAD~2) + this PBR: dark grey paint without chrome or crushed black.
PAINT = {
    "name": "forte-paint",
    "pbrMetallicRoughness": {
        "baseColorTexture": {"index": 0},
        "baseColorFactor": [0.70, 0.69, 0.66, 1.0],
        "metallicFactor": 0.0,
        "roughnessFactor": 0.68,
    },
    "emissiveFactor": [0.042, 0.040, 0.037],
}
SKY_BLUE = np.array([118.0, 188.0, 245.0], dtype=np.float32)
GLASS_ROUGH = 0.12
PAINT_ROUGH = 0.68


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


def rewrite_json_keep_bin(path: Path, js: dict):
    data = path.read_bytes()
    json_len = struct.unpack_from("<I", data, 12)[0]
    bin_off = 20 + json_len
    bin_len = struct.unpack_from("<I", data, bin_off)[0]
    binary = data[bin_off + 8 : bin_off + 8 + bin_len]
    json_bytes = json.dumps(js, separators=(",", ":")).encode("utf-8")
    json_bytes += b" " * ((4 - (len(json_bytes) % 4)) % 4)
    total = 12 + 8 + len(json_bytes) + 8 + len(binary)
    path.write_bytes(
        struct.pack("<4sII", b"glTF", 2, total)
        + struct.pack("<II", len(json_bytes), 0x4E4F534A)
        + json_bytes
        + struct.pack("<II", len(binary), 0x004E4942)
        + binary
    )


def patch_pbr(path: Path):
    js, _ = read_glb(path)
    js["materials"] = [PAINT]
    rewrite_json_keep_bin(path, js)
    pbr = js["materials"][0]["pbrMetallicRoughness"]
    print(
        f"Patched {path} ({path.stat().st_size} bytes) "
        f"base={pbr['baseColorFactor'][:3]} rough={pbr['roughnessFactor']} "
        f"emis={js['materials'][0]['emissiveFactor']}"
    )


def acc_f32(js: dict, binary: bytes, acc_i: int, ncomp: int):
    acc = js["accessors"][acc_i]
    view = js["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"]
    stride = view.get("byteStride") or ncomp * 4
    need = count * stride
    if start < 0 or start + need > len(binary):
        count = max(0, (len(binary) - start) // stride)
        need = count * stride
    if count <= 0:
        return np.zeros((0, ncomp), dtype=np.float32)
    if stride == ncomp * 4:
        return np.frombuffer(binary, dtype="<f4", count=count * ncomp, offset=start).reshape(count, ncomp).copy()
    raw = np.frombuffer(binary, dtype=np.uint8, offset=start, count=need)
    out = np.empty((count, ncomp), dtype=np.float32)
    for i in range(count):
        out[i] = np.frombuffer(raw[i * stride : i * stride + ncomp * 4], dtype="<f4")
    return out


def acc_idx(js: dict, binary: bytes, acc_i: int):
    acc = js["accessors"][acc_i]
    view = js["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"]
    dt = {5121: np.uint8, 5123: np.uint16, 5125: np.uint32}[acc["componentType"]]
    n = min(count, max(0, (len(binary) - start) // np.dtype(dt).itemsize))
    return np.frombuffer(binary, dtype=dt, count=n, offset=start).astype(np.int32)


def cabin_glass_mask(js: dict, binary: bytes, size: tuple[int, int]) -> np.ndarray:
    w, h = size
    mask = Image.new("L", (w, h), 0)
    draw = ImageDraw.Draw(mask)
    for mesh in js.get("meshes", []):
        for prim in mesh.get("primitives", []):
            attrs = prim.get("attributes") or {}
            if "POSITION" not in attrs or "TEXCOORD_0" not in attrs:
                continue
            pos = acc_f32(js, binary, attrs["POSITION"], 3)
            uv = acc_f32(js, binary, attrs["TEXCOORD_0"], 2)
            if len(pos) < 3 or len(uv) != len(pos):
                continue
            nrm = acc_f32(js, binary, attrs["NORMAL"], 3) if "NORMAL" in attrs else None
            if "indices" in prim:
                idx = acc_idx(js, binary, prim["indices"])
                idx = idx[: len(idx) - (len(idx) % 3)].reshape(-1, 3)
            else:
                idx = np.arange(len(pos) - (len(pos) % 3), dtype=np.int32).reshape(-1, 3)
            if idx.size == 0:
                continue
            valid = (idx < len(pos)).all(axis=1)
            idx = idx[valid]
            tri = pos[idx]
            c = tri.mean(axis=1)
            area = 0.5 * np.linalg.norm(np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]), axis=1)
            keep = (c[:, 1] > 0.82) & (c[:, 1] < 1.42) & (area > 4e-5)
            if nrm is not None and len(nrm) == len(pos):
                n = nrm[idx].mean(axis=1)
                keep = keep & (n[:, 1] < 0.72)
            uvs = uv[idx][keep]
            if uvs.size == 0:
                continue
            uvs = uvs - np.floor(uvs)
            xs = uvs[..., 0] * (w - 1)
            ys = (1.0 - uvs[..., 1]) * (h - 1)
            for t in range(len(xs)):
                draw.polygon(
                    [
                        (float(xs[t, 0]), float(ys[t, 0])),
                        (float(xs[t, 1]), float(ys[t, 1])),
                        (float(xs[t, 2]), float(ys[t, 2])),
                    ],
                    fill=255,
                )
    return np.asarray(mask) > 0


def glass_pixels(albedo_rgb: np.ndarray, cabin: np.ndarray) -> np.ndarray:
    rgb = albedo_rgb.astype(np.float32)
    luma = 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]
    dark = cabin & (luma < 110)
    glass = binary_closing(dark, iterations=2)
    return binary_dilation(glass, iterations=4) & cabin


def png_bytes(im: Image.Image) -> bytes:
    buf = BytesIO()
    im.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def patch_glass(path: Path):
    js, binary = read_glb(path)
    if not js.get("images"):
        print(f"Skip {path} (no albedo image)")
        return
    albedo_i = js["images"][0]["bufferView"]
    albedo = Image.open(BytesIO(view_blob(binary, js["bufferViews"][albedo_i]))).convert("RGBA")
    rgb = np.asarray(albedo)[..., :3].copy()
    cabin = cabin_glass_mask(js, binary, albedo.size)
    glass = glass_pixels(rgb, cabin)
    rgb = rgb.astype(np.float32)
    rgb[glass] = rgb[glass] * 0.18 + SKY_BLUE * 0.82
    out = np.dstack([np.clip(rgb, 0, 255).astype(np.uint8), np.asarray(albedo)[..., 3]])
    albedo_png = png_bytes(Image.fromarray(out, "RGBA"))

    mr = np.zeros((albedo.size[1], albedo.size[0], 3), dtype=np.uint8)
    mr[..., 1] = 255
    mr[glass] = (0, max(1, int(round(255 * GLASS_ROUGH / PAINT_ROUGH))), 0)
    mr_png = png_bytes(Image.fromarray(mr, "RGB"))

    blobs = []
    views = []
    for i, view in enumerate(js["bufferViews"]):
        blob = albedo_png if i == albedo_i else view_blob(binary, view)
        blobs.append(blob)
        views.append(view)
    mr_index = len(blobs)
    blobs.append(mr_png)
    views.append({})

    js["images"] = [
        {"bufferView": albedo_i, "mimeType": "image/png"},
        {"bufferView": mr_index, "mimeType": "image/png"},
    ]
    js["textures"] = [
        {"sampler": 0, "source": 0},
        {"sampler": 0, "source": 1},
    ]
    if "samplers" not in js:
        js["samplers"] = [{"magFilter": 9729, "minFilter": 9729, "wrapS": 10497, "wrapT": 10497}]
    mat = dict(PAINT)
    mat["pbrMetallicRoughness"] = dict(PAINT["pbrMetallicRoughness"])
    mat["pbrMetallicRoughness"]["roughnessFactor"] = PAINT_ROUGH
    mat["pbrMetallicRoughness"]["metallicFactor"] = 0.0
    mat["pbrMetallicRoughness"]["metallicRoughnessTexture"] = {"index": 1}
    js["materials"] = [mat]
    write_glb(path, js, views, blobs)
    print(f"Glass-tinted {path} ({path.stat().st_size} bytes) glass_px={int(glass.sum())}")


def main():
    if "--pbr-only" in sys.argv:
        for path in (ROOT / "pages" / "forte.glb", ROOT / "pages" / "forte-preview.glb"):
            if path.exists():
                patch_pbr(path)
        return
    if "--glass" in sys.argv:
        for path in (ROOT / "pages" / "forte.glb", ROOT / "pages" / "forte-preview.glb"):
            if path.exists():
                patch_glass(path)
        return
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
    js["materials"] = [PAINT]
    js.pop("extensionsUsed", None)
    js.pop("extensionsRequired", None)
    write_glb(DST, js, views, blobs)
    print(f"Wrote {DST} ({DST.stat().st_size} bytes) albedo {len(albedo)} bytes")


if __name__ == "__main__":
    main()
