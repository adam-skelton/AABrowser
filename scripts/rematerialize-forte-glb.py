"""Replace Forte's blotchy Sketchfab atlas with solid PBR parts.

Keeps the Kia Forte mesh, drops textures, and splits triangles into paint /
glass / rubber / wheels / lamps like the procedural Cerato so Maps 3D does
not smear baked lighting across the body.
"""
from __future__ import annotations

import json
import struct
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [ROOT / "pages" / "forte.glb", ROOT / "pages" / "forte-preview.glb"]

# Linear-ish PBR aimed at Google Maps 3D (low metal, a little emissive).
MATERIALS = [
    ("paint", [0.12, 0.13, 0.145, 1.0], 0.04, 0.40, [0.032, 0.034, 0.036]),
    ("glass", [0.46, 0.72, 0.90, 1.0], 0.02, 0.16, [0.09, 0.20, 0.32]),
    ("trim", [0.06, 0.06, 0.07, 1.0], 0.04, 0.80, [0.016, 0.016, 0.018]),
    ("rubber", [0.045, 0.045, 0.045, 1.0], 0.0, 0.92, [0.010, 0.010, 0.010]),
    ("alloy", [0.50, 0.52, 0.55, 1.0], 0.16, 0.40, [0.08, 0.08, 0.09]),
    ("lamp", [0.88, 0.92, 0.96, 1.0], 0.06, 0.24, [0.28, 0.32, 0.38]),
    ("tail", [0.70, 0.08, 0.08, 1.0], 0.05, 0.30, [0.38, 0.02, 0.02]),
    ("plate", [0.86, 0.86, 0.82, 1.0], 0.02, 0.65, [0.08, 0.08, 0.07]),
]
MAT = {name: i for i, (name, *_) in enumerate(MATERIALS)}

WHEELS = np.array(
    [
        [0.80, 0.32, 1.35],
        [-0.80, 0.32, 1.35],
        [0.80, 0.32, -1.35],
        [-0.80, 0.32, -1.35],
    ],
    dtype=np.float32,
)


def read_glb(path: Path):
    data = path.read_bytes()
    json_len = struct.unpack_from("<I", data, 12)[0]
    js = json.loads(data[20 : 20 + json_len].split(b"\x00")[0])
    binary = data[20 + json_len + 8 :]
    return js, binary


def acc_f32(js: dict, binary: bytes, i: int, ncomp: int) -> np.ndarray:
    acc = js["accessors"][i]
    view = js["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"]
    stride = view.get("byteStride") or ncomp * 4
    if stride == ncomp * 4:
        return (
            np.frombuffer(binary, dtype="<f4", count=count * ncomp, offset=start)
            .reshape(count, ncomp)
            .copy()
        )
    raw = np.frombuffer(binary, dtype=np.uint8, offset=start, count=count * stride)
    out = np.empty((count, ncomp), np.float32)
    for k in range(count):
        out[k] = np.frombuffer(raw[k * stride : k * stride + ncomp * 4], dtype="<f4")
    return out


def acc_idx(js: dict, binary: bytes, i: int) -> np.ndarray:
    acc = js["accessors"][i]
    view = js["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    dt = {5121: np.uint8, 5123: np.uint16, 5125: np.uint32}[acc["componentType"]]
    return np.frombuffer(binary, dtype=dt, count=acc["count"], offset=start).astype(np.int32)


def classify(c: np.ndarray, n: np.ndarray) -> np.ndarray:
    x, y, z = c[:, 0], c[:, 1], c[:, 2]
    nx, ny, nz = n[:, 0], n[:, 1], n[:, 2]
    ax, az = np.abs(x), np.abs(z)
    label = np.full(len(c), MAT["paint"], np.int32)

    d = np.sqrt(((c[:, None, [0, 2]] - WHEELS[None, :, [0, 2]]) ** 2).sum(axis=2)).min(axis=1)
    wheel = (d < 0.36) & (y < 0.62) & (ax > 0.58)
    rubber = wheel & ((d > 0.24) | (y < 0.13) | (np.abs(y - 0.32) > 0.20))
    alloy = wheel & ~rubber
    label[rubber] = MAT["rubber"]
    label[alloy] = MAT["alloy"]

    grille = (z > 2.08) & (y > 0.36) & (y < 0.72) & (ax < 0.42)
    lower = (y < 0.20) & (az > 1.60)
    trim = grille | lower | ((y < 0.14) & (az < 1.55) & (ax < 0.50))
    label[~wheel & trim] = MAT["trim"]

    head = (z > 1.95) & (y > 0.50) & (y < 0.76) & (ax > 0.58) & (ax < 0.90)
    fog = (z > 2.00) & (y > 0.22) & (y < 0.36) & (ax > 0.55) & (ax < 0.88)
    tail = (z < -1.92) & (y > 0.52) & (y < 0.78) & (ax > 0.52) & (ax < 0.88)
    label[~wheel & head] = MAT["lamp"]
    label[~wheel & fog] = MAT["lamp"]
    label[~wheel & tail] = MAT["tail"]

    plate = (az > 2.20) & (y > 0.36) & (y < 0.48) & (ax < 0.20)
    label[~wheel & plate] = MAT["plate"]

    # Volume fill for the greenhouse: scan normals are too noisy for a ny cutoff.
    roof = (y > 1.33) & (ny > 0.58) & (az < 0.48) & (ax < 0.68)
    windshield = (y > 1.00) & (y < 1.40) & (z > 0.28) & (z < 1.10) & (ax < 0.80) & ~roof
    rear_glass = (y > 1.00) & (y < 1.36) & (z < -0.40) & (z > -1.35) & (ax < 0.78) & ~roof
    side_glass = (
        (y > 0.98)
        & (y < 1.26)
        & (ax > 0.70)
        & (z > -0.85)
        & (z < 0.62)
        & (np.abs(nx) > 0.58)
        & (ny < 0.32)
    )
    glass = windshield | rear_glass | side_glass
    taken = wheel | trim | head | fog | tail | plate
    label[~taken & glass] = MAT["glass"]
    label[roof] = MAT["paint"]
    return label


def collect_triangles(js: dict, binary: bytes):
    pos_parts = []
    nrm_parts = []
    for mesh in js["meshes"]:
        for prim in mesh["primitives"]:
            attrs = prim["attributes"]
            pos = acc_f32(js, binary, attrs["POSITION"], 3)
            nrm = acc_f32(js, binary, attrs["NORMAL"], 3) if "NORMAL" in attrs else None
            if nrm is None:
                nrm = np.zeros_like(pos)
                nrm[:, 1] = 1
            if "indices" in prim:
                idx = acc_idx(js, binary, prim["indices"])
            else:
                idx = np.arange(len(pos), dtype=np.int32)
            idx = idx[: len(idx) - (len(idx) % 3)].reshape(-1, 3)
            valid = (idx < len(pos)).all(axis=1)
            idx = idx[valid]
            pos_parts.append(pos[idx])
            nrm_parts.append(nrm[idx])
    pos = np.concatenate(pos_parts, axis=0)
    nrm = np.concatenate(nrm_parts, axis=0)
    nrm = nrm / np.clip(np.linalg.norm(nrm, axis=-1, keepdims=True), 1e-6, None)
    return pos, nrm


def weld(pos: np.ndarray, nrm: np.ndarray):
    key = np.round(np.concatenate([pos * 10000.0, nrm * 1000.0], axis=1)).astype(np.int32)
    uniq, inv = np.unique(key, axis=0, return_inverse=True)
    order = np.arange(len(key), dtype=np.int32)
    first = np.full(len(uniq), len(key), np.int32)
    np.minimum.at(first, inv, order)
    return pos[first], nrm[first], inv.astype(np.uint32)


def add_view(packed: bytearray, blob: bytes, target: int | None = None):
    pad = (4 - (len(blob) % 4)) % 4
    view = {"buffer": 0, "byteOffset": len(packed), "byteLength": len(blob)}
    if target is not None:
        view["target"] = target
    packed.extend(blob)
    packed.extend(b"\x00" * pad)
    return view


def rematerialize(path: Path):
    js_in, binary = read_glb(path)
    tri_pos, tri_nrm = collect_triangles(js_in, binary)
    centers = tri_pos.mean(axis=1)
    normals = tri_nrm.mean(axis=1)
    normals = normals / np.clip(np.linalg.norm(normals, axis=1, keepdims=True), 1e-6, None)
    labels = classify(centers, normals)

    materials = []
    for name, color, metal, rough, emis in MATERIALS:
        materials.append(
            {
                "name": name,
                "pbrMetallicRoughness": {
                    "baseColorFactor": color,
                    "metallicFactor": metal,
                    "roughnessFactor": rough,
                },
                "emissiveFactor": emis,
            }
        )

    packed = bytearray()
    views = []
    accessors = []
    meshes = []
    nodes = [{"name": "forte", "children": []}]
    counts = {}

    for mat_i, (name, *_rest) in enumerate(MATERIALS):
        sel = np.where(labels == mat_i)[0]
        counts[name] = int(len(sel))
        if len(sel) == 0:
            continue
        pos, nrm, inv = weld(tri_pos[sel].reshape(-1, 3), tri_nrm[sel].reshape(-1, 3))
        idx = inv.reshape(-1, 3).astype(np.uint32)
        # Keep original winding.
        pos_blob = pos.astype("<f4").tobytes()
        nrm_blob = nrm.astype("<f4").tobytes()
        if pos.shape[0] < 65536:
            idx_blob = idx.astype("<u2").tobytes()
            idx_type = 5123
        else:
            idx_blob = idx.astype("<u4").tobytes()
            idx_type = 5125

        pos_view = len(views)
        views.append(add_view(packed, pos_blob, 34962))
        nrm_view = len(views)
        views.append(add_view(packed, nrm_blob, 34962))
        idx_view = len(views)
        views.append(add_view(packed, idx_blob, 34963))

        pos_acc = len(accessors)
        accessors.append(
            {
                "bufferView": pos_view,
                "componentType": 5126,
                "count": int(len(pos)),
                "type": "VEC3",
                "min": pos.min(0).astype(float).tolist(),
                "max": pos.max(0).astype(float).tolist(),
            }
        )
        nrm_acc = len(accessors)
        accessors.append(
            {
                "bufferView": nrm_view,
                "componentType": 5126,
                "count": int(len(nrm)),
                "type": "VEC3",
            }
        )
        idx_acc = len(accessors)
        accessors.append(
            {
                "bufferView": idx_view,
                "componentType": idx_type,
                "count": int(idx.size),
                "type": "SCALAR",
            }
        )
        mesh_i = len(meshes)
        meshes.append(
            {
                "name": name,
                "primitives": [
                    {
                        "attributes": {"POSITION": pos_acc, "NORMAL": nrm_acc},
                        "indices": idx_acc,
                        "material": mat_i,
                    }
                ],
            }
        )
        node_i = len(nodes)
        nodes.append({"name": name, "mesh": mesh_i})
        nodes[0]["children"].append(node_i)

    out = {
        "asset": {"version": "2.0", "generator": "AABrowser rematerialize-forte-glb"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": materials,
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(packed)}],
    }
    json_bytes = json.dumps(out, separators=(",", ":")).encode("utf-8")
    json_bytes += b" " * ((4 - (len(json_bytes) % 4)) % 4)
    total = 12 + 8 + len(json_bytes) + 8 + len(packed)
    path.write_bytes(
        struct.pack("<4sII", b"glTF", 2, total)
        + struct.pack("<II", len(json_bytes), 0x4E4F534A)
        + json_bytes
        + struct.pack("<II", len(packed), 0x004E4942)
        + packed
    )
    print(f"Wrote {path} ({path.stat().st_size} bytes) tris={counts}")


def main():
    for path in SOURCES:
        if path.exists():
            rematerialize(path)


if __name__ == "__main__":
    main()
