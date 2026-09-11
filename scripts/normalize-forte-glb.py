"""Bake Sketchfab node transforms and scale forte.glb to a real car size.

Keeps the cleaner materials/textures. Makes length ~4.64 m, Y-up, wheels on the
ground so Maps Model3DElement can use the same pose as before.
"""
from __future__ import annotations

import json
import struct
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "pages" / "forte.glb"
DST = ROOT / "pages" / "forte.glb"
TARGET_LENGTH_M = 4.64


def read_glb(path: Path):
    data = path.read_bytes()
    json_len = struct.unpack_from("<I", data, 12)[0]
    js = json.loads(data[20 : 20 + json_len].split(b"\x00")[0])
    binary = bytearray(data[20 + json_len + 8 :])
    return js, binary


def quat_to_mat4(q):
    x, y, z, w = q
    n = x * x + y * y + z * z + w * w
    s = 2.0 / n if n else 0.0
    xx, yy, zz = x * x * s, y * y * s, z * z * s
    xy, xz, yz = x * y * s, x * z * s, y * z * s
    wx, wy, wz = w * x * s, w * y * s, w * z * s
    m = np.eye(4, dtype=np.float64)
    m[0, 0] = 1 - (yy + zz)
    m[0, 1] = xy - wz
    m[0, 2] = xz + wy
    m[1, 0] = xy + wz
    m[1, 1] = 1 - (xx + zz)
    m[1, 2] = yz - wx
    m[2, 0] = xz - wy
    m[2, 1] = yz + wx
    m[2, 2] = 1 - (xx + yy)
    return m


def node_local_matrix(node):
    if "matrix" in node:
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
    m = np.eye(4, dtype=np.float64)
    if "scale" in node:
        sx, sy, sz = node["scale"]
        m = np.diag([sx, sy, sz, 1.0]) @ m
    if "rotation" in node:
        m = quat_to_mat4(node["rotation"]) @ m
    if "translation" in node:
        t = np.eye(4, dtype=np.float64)
        t[:3, 3] = node["translation"]
        m = t @ m
    return m


def world_matrices(js):
    nodes = js["nodes"]
    worlds = [None] * len(nodes)
    children = {i: [] for i in range(len(nodes))}
    child_ids = set()
    for i, node in enumerate(nodes):
        for c in node.get("children", []):
            children[i].append(c)
            child_ids.add(c)
    roots = [i for i in range(len(nodes)) if i not in child_ids]
    if "scenes" in js and js["scenes"]:
        roots = list(js["scenes"][js.get("scene", 0)].get("nodes", roots))

    def walk(i, parent):
        worlds[i] = parent @ node_local_matrix(nodes[i])
        for c in children[i]:
            walk(c, worlds[i])

    ident = np.eye(4, dtype=np.float64)
    for r in roots:
        walk(r, ident)
    return worlds


def accessor_f32(js, binary, acc_index):
    acc = js["accessors"][acc_index]
    view = js["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"]
    comps = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[acc["type"]]
    stride = view.get("byteStride") or (comps * 4)
    out = np.empty((count, comps), dtype=np.float32)
    for i in range(count):
        off = start + i * stride
        out[i] = np.frombuffer(binary, dtype="<f4", count=comps, offset=off)
    return out, start, stride, comps


def write_f32(binary, start, stride, comps, arr):
    blob = np.ascontiguousarray(arr, dtype="<f4")
    for i in range(len(arr)):
        off = start + i * stride
        binary[off : off + comps * 4] = blob[i].tobytes()


def transform_mesh(js, binary, mesh_index, matrix, extra):
    rot = matrix[:3, :3]
    det = np.linalg.det(rot)
    if abs(det) < 1e-12:
        normal_mat = rot
    else:
        normal_mat = np.linalg.inv(rot).T
    extra_rot = extra[:3, :3]
    for prim in js["meshes"][mesh_index]["primitives"]:
        attrs = prim["attributes"]
        pos, p_start, p_stride, p_comps = accessor_f32(js, binary, attrs["POSITION"])
        ones = np.ones((len(pos), 1), dtype=np.float64)
        world = (matrix @ np.concatenate([pos.astype(np.float64), ones], axis=1).T).T[:, :3]
        world = (extra @ np.concatenate([world, ones], axis=1).T).T[:, :3]
        write_f32(binary, p_start, p_stride, p_comps, world.astype(np.float32))
        acc = js["accessors"][attrs["POSITION"]]
        acc["min"] = world.min(axis=0).tolist()
        acc["max"] = world.max(axis=0).tolist()

        if "NORMAL" in attrs:
            nrm, n_start, n_stride, n_comps = accessor_f32(js, binary, attrs["NORMAL"])
            nw = nrm.astype(np.float64) @ normal_mat.T
            nw = nw @ extra_rot.T
            nw /= np.clip(np.linalg.norm(nw, axis=1, keepdims=True), 1e-8, None)
            write_f32(binary, n_start, n_stride, n_comps, nw.astype(np.float32))

        if "TANGENT" in attrs:
            tan, t_start, t_stride, t_comps = accessor_f32(js, binary, attrs["TANGENT"])
            tw = tan[:, :3].astype(np.float64) @ normal_mat.T
            tw = tw @ extra_rot.T
            tw /= np.clip(np.linalg.norm(tw, axis=1, keepdims=True), 1e-8, None)
            out = tan.copy()
            out[:, :3] = tw
            write_f32(binary, t_start, t_stride, t_comps, out)


def collect_positions(js, binary):
    chunks = []
    for mesh in js["meshes"]:
        for prim in mesh["primitives"]:
            pos, _, _, _ = accessor_f32(js, binary, prim["attributes"]["POSITION"])
            chunks.append(pos)
    return np.concatenate(chunks, axis=0)


def extra_matrix(js, binary, worlds):
    chunks = []
    for node_i, node in enumerate(js["nodes"]):
        if "mesh" not in node:
            continue
        m = worlds[node_i]
        for prim in js["meshes"][node["mesh"]]["primitives"]:
            pos, _, _, _ = accessor_f32(js, binary, prim["attributes"]["POSITION"])
            ones = np.ones((len(pos), 1), dtype=np.float64)
            world = (m @ np.concatenate([pos.astype(np.float64), ones], axis=1).T).T[:, :3]
            chunks.append(world)
    verts = np.concatenate(chunks, axis=0)
    size = verts.max(axis=0) - verts.min(axis=0)
    longest = float(np.max(size))
    scale = TARGET_LENGTH_M / longest if longest > 1e-6 else 1.0
    scaled_min = verts.min(axis=0) * scale
    scaled_max = verts.max(axis=0) * scale
    extra = np.eye(4, dtype=np.float64)
    extra[0, 0] = extra[1, 1] = extra[2, 2] = scale
    extra[0, 3] = -0.5 * (scaled_min[0] + scaled_max[0])
    extra[1, 3] = -scaled_min[1]
    extra[2, 3] = -0.5 * (scaled_min[2] + scaled_max[2])
    return extra, size, scale


def clear_node_transforms(js):
    for node in js["nodes"]:
        node.pop("rotation", None)
        node.pop("translation", None)
        node.pop("scale", None)
        node.pop("matrix", None)


def write_glb(path: Path, js: dict, binary: bytearray):
    packed = binary
    pad = (4 - (len(packed) % 4)) % 4
    packed = packed + (b"\x00" * pad)
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
    worlds = world_matrices(js)
    extra, raw_size, scale = extra_matrix(js, binary, worlds)
    print(f"raw size={raw_size.tolist()} scale={scale:.3f}")
    for node_i, node in enumerate(js["nodes"]):
        if "mesh" not in node:
            continue
        transform_mesh(js, binary, node["mesh"], worlds[node_i], extra)
    clear_node_transforms(js)
    verts = collect_positions(js, binary)
    size = verts.max(axis=0) - verts.min(axis=0)
    print(f"baked size={size.tolist()} min={verts.min(axis=0).tolist()} max={verts.max(axis=0).tolist()}")
    write_glb(DST, js, binary)
    print(f"Wrote {DST} ({DST.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
