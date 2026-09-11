"""Make Forte roof match body grey; turn front/hub red into silver."""
from __future__ import annotations

import json
import struct
from io import BytesIO
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import binary_dilation

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "pages" / "forte.glb"
DST = ROOT / "pages" / "forte.glb"
BODY_GREY = np.array([96.0, 100.0, 106.0], dtype=np.float32)
SILVER = np.array([178.0, 182.0, 188.0], dtype=np.float32)


def read_glb(path: Path):
    data = path.read_bytes()
    json_len = struct.unpack_from("<I", data, 12)[0]
    js = json.loads(data[20 : 20 + json_len].split(b"\x00")[0])
    binary = data[20 + json_len + 8 :]
    return js, binary


def write_glb(path: Path, js: dict, views: list[bytes]):
    packed = bytearray()
    rebuilt = []
    for i, blob in enumerate(views):
        pad = (4 - (len(blob) % 4)) % 4
        rebuilt.append(
            {
                **{k: v for k, v in js["bufferViews"][i].items() if k not in ("buffer", "byteOffset", "byteLength")},
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


def recolor(png: bytes) -> bytes:
    im = Image.open(BytesIO(png)).convert("RGBA")
    px = np.asarray(im).astype(np.float32)
    rgb = px[..., :3]
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
    mx = rgb.max(axis=-1)
    mn = rgb.min(axis=-1)
    sat = np.divide(mx - mn, mx, out=np.zeros_like(mx), where=mx > 1)

    spokes = (luma > 72) & (sat < 0.22) & (np.abs(r - g) < 20) & (np.abs(r - b) < 22)
    wheel = binary_dilation(spokes, iterations=22)
    glass = (b > r + 10) & (b > g + 6)

    paint = (sat < 0.24) & (luma < 82) & ~wheel & ~glass
    t = np.clip((82.0 - luma[paint]) / 82.0, 0.58, 0.88)
    rgb[paint] = rgb[paint] * (1.0 - t)[..., None] + BODY_GREY * t[..., None]

    red = (r > 48) & (r > g * 1.08) & (r > b * 1.05) & (g < 170)
    lamp_core = binary_dilation((luma > 155) & (r > 140), iterations=7)
    hub = wheel & red
    rgb[hub] = SILVER
    accent = red & ~lamp_core & ~hub
    rgb[accent] = SILVER

    px[..., :3] = np.clip(rgb, 0, 255)
    out = Image.fromarray(px.astype(np.uint8), "RGBA")
    buf = BytesIO()
    out.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def main():
    js, binary = read_glb(SRC)
    views = []
    for view in js["bufferViews"]:
        start = view.get("byteOffset", 0)
        views.append(binary[start : start + view["byteLength"]])
    img0 = js["images"][0]["bufferView"]
    views[img0] = recolor(views[img0])
    write_glb(DST, js, views)
    print(f"Wrote {DST} ({DST.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
