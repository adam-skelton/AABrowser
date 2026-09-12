import json, struct
from io import BytesIO
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

p = Path("pages/forte.glb")
data = p.read_bytes()
json_len = struct.unpack_from("<I", data, 12)[0]
js = json.loads(data[20 : 20 + json_len].split(b"\x00")[0])
binary = data[20 + json_len + 8 :]


def acc_f32(acc_i, ncomp):
    acc = js["accessors"][acc_i]
    view = js["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"]
    stride = view.get("byteStride") or (ncomp * 4)
    if stride == ncomp * 4:
        return np.frombuffer(binary, dtype="<f4", count=count * ncomp, offset=start).reshape(count, ncomp).copy()
    out = np.empty((count, ncomp), dtype=np.float32)
    for i in range(count):
        off = start + i * stride
        out[i] = np.frombuffer(binary[off : off + ncomp * 4], dtype="<f4")
    return out


def acc_idx(acc_i):
    acc = js["accessors"][acc_i]
    view = js["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    count = acc["count"]
    dt = {5121: np.uint8, 5123: np.uint16, 5125: np.uint32}[acc["componentType"]]
    return np.frombuffer(binary, dtype=dt, count=count, offset=start).astype(np.int32)


view = js["bufferViews"][js["images"][0]["bufferView"]]
png = binary[view.get("byteOffset", 0) : view.get("byteOffset", 0) + view["byteLength"]]
im = Image.open(BytesIO(png)).convert("RGB")
W, H = im.size
mask = Image.new("L", (W, H), 0)
draw = ImageDraw.Draw(mask)

hits = 0
for m in js["meshes"]:
    pr = m["primitives"][0]
    pos = acc_f32(pr["attributes"]["POSITION"], 3)
    nrm = acc_f32(pr["attributes"]["NORMAL"], 3)
    uv = acc_f32(pr["attributes"]["TEXCOORD_0"], 2)
    if "indices" in pr:
        idx = acc_idx(pr["indices"]).reshape(-1, 3)
    else:
        idx = np.arange(len(pos), dtype=np.int32).reshape(-1, 3)
    tri = pos[idx]
    n = nrm[idx].mean(axis=1)
    c = tri.mean(axis=1)
    area = 0.5 * np.linalg.norm(np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]), axis=1)
    keep = (c[:, 1] > 0.82) & (c[:, 1] < 1.42) & (area > 4e-5) & (n[:, 1] < 0.72)
    uvs = uv[idx][keep]
    uvs = uvs - np.floor(uvs)
    xs = uvs[..., 0] * (W - 1)
    ys = (1 - uvs[..., 1]) * (H - 1)
    hits += int(keep.sum())
    for t in range(len(xs)):
        pts = [(float(xs[t, 0]), float(ys[t, 0])), (float(xs[t, 1]), float(ys[t, 1])), (float(xs[t, 2]), float(ys[t, 2]))]
        draw.polygon(pts, fill=255)

arr = np.asarray(mask)
print("glass tris", hits, "mask px", int((arr > 0).sum()))
vis = np.asarray(im).copy()
vis[arr > 0] = [40, 170, 255]
Image.fromarray(vis).save("_forte_glass_uv.png")
mask.save("_forte_glass_mask.png")
print("wrote overlays")
