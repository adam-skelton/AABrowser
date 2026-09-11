"""Write a small Y-up, +Z-forward blue chevron for the zoomed-out nav puck."""
from __future__ import annotations

import json
import struct
from pathlib import Path

import numpy as np

DST = Path(__file__).resolve().parents[1] / "pages" / "heading-arrow.glb"


def pad4(blob: bytes) -> bytes:
    return blob + (b"\x00" * ((4 - (len(blob) % 4)) % 4))


def main():
    # Compact Google-style chevron, +Z forward, sitting on Y=0. ~2.4 m long.
    top = 0.32
    verts = np.array(
        [
            [0.0, top, 1.35],
            [0.95, top, -1.05],
            [0.32, top, -0.62],
            [-0.32, top, -0.62],
            [-0.95, top, -1.05],
            [0.0, 0.0, 1.35],
            [0.95, 0.0, -1.05],
            [0.32, 0.0, -0.62],
            [-0.32, 0.0, -0.62],
            [-0.95, 0.0, -1.05],
        ],
        dtype=np.float32,
    )
    faces = np.array(
        [
            [0, 1, 2],
            [0, 2, 3],
            [0, 3, 4],
            [5, 7, 6],
            [5, 8, 7],
            [5, 9, 8],
            [0, 5, 6],
            [0, 6, 1],
            [1, 6, 7],
            [1, 7, 2],
            [2, 7, 8],
            [2, 8, 3],
            [3, 8, 9],
            [3, 9, 4],
            [4, 9, 5],
            [4, 5, 0],
        ],
        dtype=np.uint16,
    )
    mesh = []
    nrm = []
    idx = []
    for a, b, c in faces:
        p0, p1, p2 = verts[a], verts[b], verts[c]
        n = np.cross(p1 - p0, p2 - p0)
        ln = np.linalg.norm(n) or 1.0
        n = (n / ln).astype(np.float32)
        base = len(mesh)
        mesh.extend([p0, p1, p2])
        nrm.extend([n, n, n])
        idx.extend([base, base + 1, base + 2])
    pos = np.asarray(mesh, dtype="<f4")
    nrm = np.asarray(nrm, dtype="<f4")
    idx = np.asarray(idx, dtype="<u2")

    blobs = [idx.tobytes(), pos.tobytes(), nrm.tobytes()]
    views = []
    packed = bytearray()
    for i, blob in enumerate(blobs):
        views.append(
            {
                "buffer": 0,
                "byteOffset": len(packed),
                "byteLength": len(blob),
                "target": 34963 if i == 0 else 34962,
            }
        )
        packed.extend(pad4(blob))

    js = {
        "asset": {"version": "2.0", "generator": "AABrowser heading arrow"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "heading-arrow"}],
        "meshes": [
            {
                "name": "heading-arrow",
                "primitives": [
                    {
                        "attributes": {"POSITION": 0, "NORMAL": 1},
                        "indices": 2,
                        "material": 0,
                    }
                ],
            }
        ],
        "materials": [
            {
                "name": "maps-blue",
                "pbrMetallicRoughness": {
                    "baseColorFactor": [0.15, 0.48, 0.98, 1.0],
                    "metallicFactor": 0.0,
                    "roughnessFactor": 0.45,
                },
                "emissiveFactor": [0.08, 0.26, 0.72],
                "doubleSided": True,
            }
        ],
        "accessors": [
            {
                "bufferView": 1,
                "componentType": 5126,
                "count": len(pos),
                "type": "VEC3",
                "min": pos.min(axis=0).tolist(),
                "max": pos.max(axis=0).tolist(),
            },
            {"bufferView": 2, "componentType": 5126, "count": len(nrm), "type": "VEC3"},
            {"bufferView": 0, "componentType": 5123, "count": int(idx.size), "type": "SCALAR"},
        ],
        "bufferViews": views,
        "buffers": [{"byteLength": len(packed)}],
    }
    json_bytes = pad4(json.dumps(js, separators=(",", ":")).encode("utf-8"))
    total = 12 + 8 + len(json_bytes) + 8 + len(packed)
    DST.write_bytes(
        struct.pack("<4sII", b"glTF", 2, total)
        + struct.pack("<II", len(json_bytes), 0x4E4F534A)
        + json_bytes
        + struct.pack("<II", len(packed), 0x004E4942)
        + packed
    )
    print(f"Wrote {DST} ({DST.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
