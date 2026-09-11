"""Shrink forte.glb: decimate dense Sketchfab mesh and recompress textures.

Keeps look close to the source: ~100k triangles and 1024px albedo is still
plenty for the map and the debug viewer. Avoids Draco/meshopt/KTX2 so Google
Maps Model3DElement can load the file.
"""
from __future__ import annotations

import json
import struct
from io import BytesIO
from pathlib import Path

import fast_simplification
import numpy as np
import trimesh
from PIL import Image
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "pages" / "forte.glb"
DST = ROOT / "pages" / "forte.glb"
TARGET_TRIS = 40000
MAX_VERTS = 65000
AGGRESSION = 5.0


def read_glb(path: Path):
    data = path.read_bytes()
    json_len = struct.unpack_from("<I", data, 12)[0]
    js = json.loads(data[20 : 20 + json_len].split(b"\x00")[0])
    binary = data[20 + json_len + 8 :]
    return js, binary


def accessor_array(js, binary, index):
    acc = js["accessors"][index]
    view = js["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"]
    ctype = acc["componentType"]
    typ = acc["type"]
    comps = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[typ]
    if ctype == 5126:
        arr = np.frombuffer(binary, dtype="<f4", count=count * comps, offset=start)
    elif ctype == 5125:
        arr = np.frombuffer(binary, dtype="<u4", count=count * comps, offset=start)
    elif ctype == 5123:
        arr = np.frombuffer(binary, dtype="<u2", count=count * comps, offset=start)
    else:
        raise ValueError(f"unsupported componentType {ctype}")
    return arr.reshape(count, comps) if comps > 1 else arr


def load_geometry(js, binary):
    verts = []
    faces = []
    uvs = []
    offset = 0
    for mesh in js["meshes"]:
        prim = mesh["primitives"][0]
        pos = accessor_array(js, binary, prim["attributes"]["POSITION"]).astype(np.float64)
        uv = accessor_array(js, binary, prim["attributes"]["TEXCOORD_0"]).astype(np.float64)
        idx = accessor_array(js, binary, prim["indices"]).astype(np.int64).reshape(-1, 3)
        verts.append(pos)
        uvs.append(uv)
        faces.append(idx + offset)
        offset += len(pos)
    return (
        np.concatenate(verts, axis=0),
        np.concatenate(faces, axis=0),
        np.concatenate(uvs, axis=0),
    )


def encode_png(im: Image.Image, size: int) -> bytes:
    rgba = im.convert("RGBA").resize((size, size), Image.Resampling.LANCZOS)
    buf = BytesIO()
    rgba.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def pad4(blob: bytes) -> bytes:
    return blob + (b"\x00" * ((4 - (len(blob) % 4)) % 4))


def add_view(blobs: list[bytes], blob: bytes, target=None) -> int:
    view = {"buffer": 0, "byteOffset": -1, "byteLength": len(blob)}
    if target is not None:
        view["target"] = target
    blobs.append(blob)
    return len(blobs) - 1, view


def write_glb(path: Path, js: dict, views: list[dict], blobs: list[bytes]):
    packed = bytearray()
    rebuilt = []
    for i, blob in enumerate(blobs):
        offset = len(packed)
        packed.extend(pad4(blob))
        view = dict(views[i])
        view["buffer"] = 0
        view["byteOffset"] = offset
        view["byteLength"] = len(blob)
        rebuilt.append(view)
    js = dict(js)
    js["bufferViews"] = rebuilt
    js["buffers"] = [{"byteLength": len(packed)}]
    json_bytes = pad4(json.dumps(js, separators=(",", ":")).encode("utf-8"))
    total = 12 + 8 + len(json_bytes) + 8 + len(packed)
    header = struct.pack("<4sII", b"glTF", 2, total)
    path.write_bytes(
        header
        + struct.pack("<II", len(json_bytes), 0x4E4F534A)
        + json_bytes
        + struct.pack("<II", len(packed), 0x004E4942)
        + packed
    )


def to_gltf_y_up(verts):
    """Sketchfab stores this car Z-up with length on Y. glTF / Maps want Y-up, +Z forward."""
    out = np.empty_like(verts)
    out[:, 0] = verts[:, 0]
    out[:, 1] = verts[:, 2]
    out[:, 2] = -verts[:, 1]
    out[:, 1] -= out[:, 1].min()
    return out


def main():
    js, binary = read_glb(SRC)
    verts, faces, uvs = load_geometry(js, binary)
    print(f"input verts={len(verts)} tris={len(faces)}")

    new_v, new_f, new_uv = verts, faces, uvs.astype(np.float32)
    target = min(TARGET_TRIS, max(8000, len(faces) - 1))
    while len(new_v) >= MAX_VERTS or len(new_f) > TARGET_TRIS:
        new_v, new_f = fast_simplification.simplify(
            new_v,
            new_f.astype(np.int32),
            target_count=target,
            agg=AGGRESSION,
        )
        if len(new_v) < MAX_VERTS and len(new_f) <= TARGET_TRIS:
            break
        target = max(8000, int(target * 0.72))
        if target < 8000:
            break
    tree = cKDTree(verts)
    _, nearest = tree.query(new_v, k=1, workers=-1)
    new_uv = uvs[nearest].astype(np.float32)

    size = new_v.max(axis=0) - new_v.min(axis=0)
    if size[1] > size[2] * 1.5:
        new_v = to_gltf_y_up(new_v)

    mesh = trimesh.Trimesh(vertices=new_v, faces=new_f, process=True)
    new_v = np.asarray(mesh.vertices, dtype=np.float32)
    new_f = np.asarray(mesh.faces, dtype=np.int32)
    new_n = np.asarray(mesh.vertex_normals, dtype=np.float32)
    tree2 = cKDTree(to_gltf_y_up(verts) if (verts.max(0) - verts.min(0))[1] > (verts.max(0) - verts.min(0))[2] * 1.5 else verts)
    _, nearest = tree2.query(new_v, k=1, workers=-1)
    new_uv = uvs[nearest].astype(np.float32)
    new_v[:, 1] -= new_v[:, 1].min()
    print(f"output verts={len(new_v)} tris={len(new_f)} size={(new_v.max(0)-new_v.min(0)).tolist()}")

    albedo_view = js["bufferViews"][js["images"][0]["bufferView"]]
    albedo_blob = binary[albedo_view.get("byteOffset", 0) : albedo_view.get("byteOffset", 0) + albedo_view["byteLength"]]
    albedo_im = Image.open(BytesIO(albedo_blob))
    albedo = encode_png(albedo_im, 1024)
    print(f"albedo {albedo_im.size} -> 1024 png {len(albedo)} bytes")

    blobs: list[bytes] = []
    views: list[dict] = []

    def push(blob, target=None):
        view = {"buffer": 0, "byteOffset": 0, "byteLength": len(blob)}
        if target is not None:
            view["target"] = target
        blobs.append(blob)
        views.append(view)
        return len(blobs) - 1

    use_u16 = len(new_v) < 65535
    if not use_u16:
        raise SystemExit(f"Forte still has {len(new_v)} verts; needs uint16 for WebView model-viewer")
    idx = new_f.reshape(-1)
    idx_bytes = (idx.astype("<u2") if use_u16 else idx.astype("<u4")).tobytes()
    pos_bytes = np.ascontiguousarray(new_v, dtype="<f4").tobytes()
    nrm_bytes = np.ascontiguousarray(new_n, dtype="<f4").tobytes()
    uv_bytes = np.ascontiguousarray(new_uv, dtype="<f4").tobytes()

    idx_view = push(idx_bytes, 34963)
    uv_view = push(uv_bytes, 34962)
    pos_view = push(pos_bytes, 34962)
    nrm_view = push(nrm_bytes, 34962)
    img_view = push(albedo)

    accessors = [
        {
            "bufferView": pos_view,
            "componentType": 5126,
            "count": len(new_v),
            "type": "VEC3",
            "min": new_v.min(axis=0).tolist(),
            "max": new_v.max(axis=0).tolist(),
        },
        {
            "bufferView": nrm_view,
            "componentType": 5126,
            "count": len(new_v),
            "type": "VEC3",
        },
        {
            "bufferView": uv_view,
            "componentType": 5126,
            "count": len(new_v),
            "type": "VEC2",
        },
        {
            "bufferView": idx_view,
            "componentType": 5123 if use_u16 else 5125,
            "count": int(idx.size),
            "type": "SCALAR",
        },
    ]

    mat = {
        "name": "forte-paint",
        "pbrMetallicRoughness": {
            "baseColorFactor": [0.92, 0.94, 0.96, 1.0],
            "metallicFactor": 0.08,
            "roughnessFactor": 0.48,
            "baseColorTexture": {"index": 0},
        },
        "emissiveFactor": [0.10, 0.11, 0.12],
    }

    out = {
        "asset": {"version": "2.0", "generator": "AABrowser Forte optimizer"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "kia-forte"}],
        "meshes": [
            {
                "name": "kia-forte",
                "primitives": [
                    {
                        "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2},
                        "indices": 3,
                        "material": 0,
                    }
                ],
            }
        ],
        "materials": [mat],
        "textures": [{"sampler": 0, "source": 0}],
        "images": [{"bufferView": img_view, "mimeType": "image/png"}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": 0}],
    }
    write_glb(DST, out, views, blobs)
    print(f"Wrote {DST} ({DST.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
