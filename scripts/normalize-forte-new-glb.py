"""Scale forte-new.glb to a real car and match the existing Forte pose.

Length along +Z, width along X, Y-up, wheels on the ground, length ~4.64 m.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "pages" / "forte-new.glb"
TARGET_LENGTH_M = 4.64

spec = importlib.util.spec_from_file_location(
    "normalize_forte_glb", Path(__file__).resolve().parent / "normalize-forte-glb.py"
)
norm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(norm)


def extra_matrix(js, binary, worlds):
    chunks = []
    for node_i, node in enumerate(js["nodes"]):
        if "mesh" not in node:
            continue
        m = worlds[node_i]
        for prim in js["meshes"][node["mesh"]]["primitives"]:
            pos, _, _, _ = norm.accessor_f32(js, binary, prim["attributes"]["POSITION"])
            ones = np.ones((len(pos), 1), dtype=np.float64)
            world = (m @ np.concatenate([pos.astype(np.float64), ones], axis=1).T).T[:, :3]
            chunks.append(world)
    verts = np.concatenate(chunks, axis=0)
    raw = verts.max(axis=0) - verts.min(axis=0)
    # Source is X-forward, Y-up, Z-right. Existing map cars are X-right, Y-up, Z-forward.
    rotate = np.array(
        [
            [0.0, 0.0, 1.0, 0.0],
            [0.0, 1.0, 0.0, 0.0],
            [-1.0, 0.0, 0.0, 0.0],
            [0.0, 0.0, 0.0, 1.0],
        ],
        dtype=np.float64,
    )
    ones = np.ones((len(verts), 1), dtype=np.float64)
    rotated = (rotate @ np.concatenate([verts, ones], axis=1).T).T[:, :3]
    size = rotated.max(axis=0) - rotated.min(axis=0)
    scale = TARGET_LENGTH_M / float(size[2]) if size[2] > 1e-6 else 1.0
    scaled = rotated * scale
    mn = scaled.min(axis=0)
    mx = scaled.max(axis=0)
    extra = np.eye(4, dtype=np.float64)
    extra[0, 0] = extra[1, 1] = extra[2, 2] = scale
    extra = extra @ rotate
    extra[0, 3] = -0.5 * (mn[0] + mx[0])
    extra[1, 3] = -mn[1]
    extra[2, 3] = -0.5 * (mn[2] + mx[2])
    return extra, raw, scale, size * scale


def main():
    js, binary = norm.read_glb(SRC)
    worlds = norm.world_matrices(js)
    extra, raw, scale, final_est = extra_matrix(js, binary, worlds)
    print(f"raw size={raw.tolist()} scale={scale:.4f} est={final_est.tolist()}")
    for node_i, node in enumerate(js["nodes"]):
        if "mesh" not in node:
            continue
        norm.transform_mesh(js, binary, node["mesh"], worlds[node_i], extra)
    norm.clear_node_transforms(js)
    verts = norm.collect_positions(js, binary)
    size = verts.max(axis=0) - verts.min(axis=0)
    print(f"baked size={size.tolist()} min={verts.min(axis=0).tolist()} max={verts.max(axis=0).tolist()}")
    norm.write_glb(SRC, js, binary)
    print(f"Wrote {SRC} ({SRC.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
