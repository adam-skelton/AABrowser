// Split a car GLB into body + one wheel (hub at origin) and a rigged GLB
// with four wheel nodes plus a looping "spin" animation for model-viewer.
const fs = require("fs");
const path = require("path");

const SRC = process.argv[2] || "pages/forte-new.glb";
const STEM = process.argv[3] || path.basename(SRC, ".glb");
const OUT_DIR = path.join(__dirname, "..", "pages");

function loadGlb(file) {
  const buf = fs.readFileSync(file);
  const jsonLen = buf.readUInt32LE(12);
  const json = JSON.parse(buf.slice(20, 20 + jsonLen).toString("utf8"));
  let off = 20 + jsonLen;
  if (off % 4) off += 4 - (off % 4);
  const binLen = buf.readUInt32LE(off);
  const bin = buf.slice(off + 8, off + 8 + binLen);
  return { json, bin };
}

function pad4(buf, padByte) {
  const n = (4 - (buf.length % 4)) % 4;
  return n ? Buffer.concat([buf, Buffer.alloc(n, padByte)]) : buf;
}

function writeGlb(json, bin) {
  const binBuf = pad4(bin, 0);
  json.buffers = [{ byteLength: binBuf.length }];
  const jsonBuf = pad4(Buffer.from(JSON.stringify(json)), 0x20);
  const header = Buffer.alloc(12);
  header.write("glTF", 0);
  header.writeUInt32LE(2, 4);
  const total = 12 + 8 + jsonBuf.length + 8 + binBuf.length;
  header.writeUInt32LE(total, 8);
  const jh = Buffer.alloc(8);
  jh.writeUInt32LE(jsonBuf.length, 0);
  jh.writeUInt32LE(0x4e4f534a, 4);
  const bh = Buffer.alloc(8);
  bh.writeUInt32LE(binBuf.length, 0);
  bh.writeUInt32LE(0x004e4942, 4);
  return Buffer.concat([header, jh, jsonBuf, bh, binBuf]);
}

function readF32(bin, offset) {
  return bin.readFloatLE(offset);
}

function getAccessorData(json, bin, accIdx) {
  const acc = json.accessors[accIdx];
  const view = json.bufferViews[acc.bufferView];
  const start = (view.byteOffset || 0) + (acc.byteOffset || 0);
  const typeSize = acc.type === "VEC3" ? 3 : acc.type === "VEC2" ? 2 : 1;
  const out = [];
  if (acc.componentType === 5126) {
    for (let i = 0; i < acc.count; i++) {
      const o = start + i * typeSize * 4;
      if (typeSize === 1) out.push(readF32(bin, o));
      else {
        const v = [];
        for (let k = 0; k < typeSize; k++) v.push(readF32(bin, o + k * 4));
        out.push(v);
      }
    }
  } else if (acc.componentType === 5125) {
    for (let i = 0; i < acc.count; i++) out.push(bin.readUInt32LE(start + i * 4));
  } else if (acc.componentType === 5123) {
    for (let i = 0; i < acc.count; i++) out.push(bin.readUInt16LE(start + i * 2));
  }
  return { acc, data: out };
}

function findHubs(positions) {
  const ground = positions.filter((p) => p[1] < 0.05);
  const seeds = [
    [-0.8, 1.4],
    [0.8, 1.4],
    [-0.8, -1.4],
    [0.8, -1.4]
  ];
  let cents = seeds.map((s) => [s[0], s[1]]);
  for (let iter = 0; iter < 10; iter++) {
    const buckets = [[], [], [], []];
    ground.forEach((p) => {
      let bi = 0, bd = 1e9;
      cents.forEach((c, i) => {
        const d = (p[0] - c[0]) ** 2 + (p[2] - c[1]) ** 2;
        if (d < bd) {
          bd = d;
          bi = i;
        }
      });
      buckets[bi].push(p);
    });
    cents = buckets.map((b) => {
      if (!b.length) return [0, 0];
      return [b.reduce((s, p) => s + p[0], 0) / b.length, b.reduce((s, p) => s + p[2], 0) / b.length];
    });
  }
  return cents.map((c) => {
    const near = positions.filter((p) => (p[0] - c[0]) ** 2 + (p[2] - c[1]) ** 2 < 0.42 * 0.42 && p[1] < 0.7);
    const ys = near.map((p) => p[1]).sort((a, b) => a - b);
    const y = ys.length ? ys[Math.floor(ys.length * 0.45)] : 0.32;
    return { x: c[0], y, z: c[1] };
  });
}

function dist2(a, b) {
  const dx = a[0] - b.x, dy = a[1] - b.y, dz = a[2] - b.z;
  return dx * dx + dy * dy + dz * dz;
}

function packVecs(vecs, n) {
  const buf = Buffer.alloc(vecs.length * n * 4);
  vecs.forEach((v, i) => {
    for (let k = 0; k < n; k++) buf.writeFloatLE(v[k], (i * n + k) * 4);
  });
  return buf;
}

function packU32(arr) {
  const buf = Buffer.alloc(arr.length * 4);
  arr.forEach((v, i) => buf.writeUInt32LE(v >>> 0, i * 4));
  return buf;
}

function computeNormals(positions, indices) {
  const nrm = positions.map(() => [0, 0, 0]);
  for (let i = 0; i < indices.length; i += 3) {
    const ia = indices[i], ib = indices[i + 1], ic = indices[i + 2];
    const a = positions[ia], b = positions[ib], c = positions[ic];
    const ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const ac = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    const cr = [
      ab[1] * ac[2] - ab[2] * ac[1],
      ab[2] * ac[0] - ab[0] * ac[2],
      ab[0] * ac[1] - ab[1] * ac[0]
    ];
    [ia, ib, ic].forEach((idx) => {
      nrm[idx][0] += cr[0];
      nrm[idx][1] += cr[1];
      nrm[idx][2] += cr[2];
    });
  }
  return nrm.map((v) => {
    const len = Math.hypot(v[0], v[1], v[2]) || 1;
    return [v[0] / len, v[1] / len, v[2] / len];
  });
}

function meshFrom(positions, uvs, indices, imageBin, jsonIn) {
  const normals = computeNormals(positions, indices);
  const idxBuf = packU32(indices);
  const posBuf = packVecs(positions, 3);
  const nrmBuf = packVecs(normals, 3);
  const imgBuf = pad4(imageBin, 0);
  const uvBuf = packVecs(uvs, 2);
  const parts = [idxBuf, posBuf, nrmBuf, imgBuf, uvBuf];
  const offsets = [];
  let off = 0;
  parts.forEach((p) => {
    offsets.push(off);
    off += p.length;
  });
  const bin = Buffer.concat(parts);
  const posMin = [Infinity, Infinity, Infinity];
  const posMax = [-Infinity, -Infinity, -Infinity];
  positions.forEach((p) => {
    for (let k = 0; k < 3; k++) {
      posMin[k] = Math.min(posMin[k], p[k]);
      posMax[k] = Math.max(posMax[k], p[k]);
    }
  });
  const mat = retargetMaterial(JSON.parse(JSON.stringify(jsonIn.materials[0])), 0);
  mat.doubleSided = true;
  const json = {
    asset: { version: "2.0", generator: "AABrowser-wheel-rig" },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes: [{ name: "root", mesh: 0 }],
    meshes: [{
      name: "mesh",
      primitives: [{
        attributes: { POSITION: 1, NORMAL: 2, TEXCOORD_0: 3 },
        indices: 0,
        mode: 4,
        material: 0
      }]
    }],
    materials: [mat],
    images: [{ bufferView: 3, mimeType: jsonIn.images[0].mimeType || "image/png" }],
    textures: [{ source: 0 }],
    accessors: [
      { componentType: 5125, type: "SCALAR", bufferView: 0, count: indices.length, max: [positions.length - 1], min: [0] },
      { componentType: 5126, type: "VEC3", bufferView: 1, count: positions.length, min: posMin, max: posMax },
      { componentType: 5126, type: "VEC3", bufferView: 2, count: normals.length },
      { componentType: 5126, type: "VEC2", bufferView: 4, count: uvs.length }
    ],
    bufferViews: [
      { buffer: 0, byteOffset: offsets[0], byteLength: idxBuf.length },
      { buffer: 0, byteOffset: offsets[1], byteLength: posBuf.length },
      { buffer: 0, byteOffset: offsets[2], byteLength: nrmBuf.length },
      { buffer: 0, byteOffset: offsets[3], byteLength: imgBuf.length },
      { buffer: 0, byteOffset: offsets[4], byteLength: uvBuf.length }
    ],
    buffers: [{ byteLength: bin.length }]
  };
  return { json, bin };
}

function quatAxisAngle(ax, ay, az, rad) {
  const s = Math.sin(rad / 2);
  return [ax * s, ay * s, az * s, Math.cos(rad / 2)];
}

function rigGlb(body, wheels, imageBin, jsonIn) {
  // wheels: [{name, positions, uvs, indices, hub}]
  const chunks = [];
  const views = [];
  const accessors = [];
  const nodes = [{ name: "world", children: [] }];
  const meshes = [];
  function addBuf(buf) {
    const off = chunks.reduce((s, b) => s + b.length, 0);
    chunks.push(buf);
    return off;
  }
  function addMesh(name, positions, uvs, indices) {
    const idxBuf = packU32(indices);
    const posBuf = packVecs(positions, 3);
    const uvBuf = packVecs(uvs, 2);
    const posMin = [Infinity, Infinity, Infinity];
    const posMax = [-Infinity, -Infinity, -Infinity];
    positions.forEach((p) => {
      for (let k = 0; k < 3; k++) {
        posMin[k] = Math.min(posMin[k], p[k]);
        posMax[k] = Math.max(posMax[k], p[k]);
      }
    });
    const iOff = addBuf(idxBuf);
    const pOff = addBuf(posBuf);
    const uOff = addBuf(uvBuf);
    const iAcc = accessors.length;
    accessors.push({ componentType: 5125, type: "SCALAR", bufferView: views.length, count: indices.length, max: [positions.length - 1], min: [0] });
    views.push({ buffer: 0, byteOffset: iOff, byteLength: idxBuf.length });
    accessors.push({ componentType: 5126, type: "VEC3", bufferView: views.length, count: positions.length, min: posMin, max: posMax });
    views.push({ buffer: 0, byteOffset: pOff, byteLength: posBuf.length });
    accessors.push({ componentType: 5126, type: "VEC2", bufferView: views.length, count: uvs.length });
    views.push({ buffer: 0, byteOffset: uOff, byteLength: uvBuf.length });
    const meshIdx = meshes.length;
    meshes.push({
      name,
      primitives: [{ attributes: { POSITION: iAcc + 1, TEXCOORD_0: iAcc + 2 }, indices: iAcc, mode: 4, material: 0 }]
    });
    return meshIdx;
  }

  const bodyMesh = addMesh("body", body.positions, body.uvs, body.indices);
  nodes[0].children.push(1);
  nodes.push({ name: "body", mesh: bodyMesh });

  const wheelNodeIdx = [];
  wheels.forEach((w) => {
    const meshIdx = addMesh(w.name, w.positions, w.uvs, w.indices);
    const ni = nodes.length;
    wheelNodeIdx.push(ni);
    nodes[0].children.push(ni);
    nodes.push({ name: w.name, mesh: meshIdx, translation: [w.hub.x, w.hub.y, w.hub.z] });
  });

  const imgOff = addBuf(imageBin);
  const imgView = views.length;
  views.push({ buffer: 0, byteOffset: imgOff, byteLength: imageBin.length });

  const times = [0, 0.25, 0.5, 0.75, 1];
  const timeBuf = packVecs(times.map((t) => [t]), 1);
  // 360° uses w=-1 so slerp does not reverse from 270° back to identity.
  const quats = [0, Math.PI / 2, Math.PI, (3 * Math.PI) / 2, Math.PI * 2].map((a) => quatAxisAngle(1, 0, 0, a));
  const quatBuf = packVecs(quats, 4);
  const tOff = addBuf(timeBuf);
  const qOff = addBuf(quatBuf);
  const tAcc = accessors.length;
  accessors.push({ componentType: 5126, type: "SCALAR", bufferView: views.length, count: times.length, min: [0], max: [1] });
  views.push({ buffer: 0, byteOffset: tOff, byteLength: timeBuf.length });
  const qAcc = accessors.length;
  accessors.push({ componentType: 5126, type: "VEC4", bufferView: views.length, count: quats.length });
  views.push({ buffer: 0, byteOffset: qOff, byteLength: quatBuf.length });

  const channels = [];
  const samplers = [];
  wheelNodeIdx.forEach((ni) => {
    const si = samplers.length;
    samplers.push({ input: tAcc, output: qAcc, interpolation: "LINEAR" });
    channels.push({ sampler: si, target: { node: ni, path: "rotation" } });
  });

  const bin = Buffer.concat(chunks);
  const json = {
    asset: { version: "2.0", generator: "AABrowser-wheel-rig" },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes,
    meshes,
    materials: [JSON.parse(JSON.stringify(jsonIn.materials[0]))],
    images: [{ bufferView: imgView, mimeType: jsonIn.images[0].mimeType || "image/png" }],
    textures: [{ source: 0 }],
    accessors,
    bufferViews: views,
    buffers: [{ byteLength: bin.length }],
    animations: [{ name: "spin", samplers, channels }]
  };
  return { json, bin };
}

function split(file, stem) {
  const { json, bin } = loadGlb(file);
  const prim = json.meshes[0].primitives[0];
  const pos = getAccessorData(json, bin, prim.attributes.POSITION).data;
  const uv = getAccessorData(json, bin, prim.attributes.TEXCOORD_0).data;
  const idx = getAccessorData(json, bin, prim.indices).data;
  const imgView = json.bufferViews[json.images[0].bufferView];
  const imageBin = bin.slice(imgView.byteOffset || 0, (imgView.byteOffset || 0) + imgView.byteLength);
  const hubs = findHubs(pos);
  const R2 = 0.36 * 0.36;
  const wheelOf = pos.map((p) => {
    let best = -1, bd = R2;
    hubs.forEach((h, i) => {
      const d = dist2(p, h);
      if (d < bd) {
        bd = d;
        best = i;
      }
    });
    return best;
  });

  const bodyMap = new Map();
  const bodyPos = [], bodyUv = [], bodyIdx = [];
  function bodyVert(i) {
    if (bodyMap.has(i)) return bodyMap.get(i);
    const n = bodyPos.length;
    bodyMap.set(i, n);
    bodyPos.push(pos[i]);
    bodyUv.push(uv[i]);
    return n;
  }
  const wheelParts = hubs.map(() => ({ map: new Map(), pos: [], uv: [], idx: [] }));
  function wheelVert(w, i, hub) {
    const part = wheelParts[w];
    if (part.map.has(i)) return part.map.get(i);
    const n = part.pos.length;
    part.map.set(i, n);
    part.pos.push([pos[i][0] - hub.x, pos[i][1] - hub.y, pos[i][2] - hub.z]);
    part.uv.push(uv[i]);
    return n;
  }

  for (let t = 0; t < idx.length; t += 3) {
    const a = idx[t], b = idx[t + 1], c = idx[t + 2];
    const votes = [wheelOf[a], wheelOf[b], wheelOf[c]].filter((v) => v >= 0);
    const w = votes.length >= 2 ? votes[0] === votes[1] || votes[0] === votes[2] ? votes[0] : votes[1] : -1;
    const useWheel = votes.length >= 2 && (votes[0] === votes[1] || votes[0] === votes[2] || votes[1] === votes[2]);
    if (useWheel) {
      const wi = votes[0] === votes[1] || votes[0] === votes[2] ? votes[0] : votes[1];
      const hub = hubs[wi];
      wheelParts[wi].idx.push(wheelVert(wi, a, hub), wheelVert(wi, b, hub), wheelVert(wi, c, hub));
    } else {
      bodyIdx.push(bodyVert(a), bodyVert(b), bodyVert(c));
    }
  }

  const fr = hubs.reduce((best, h, i) => (h.x > 0 && h.z > 0 ? i : best), 1);
  const wheelMesh = wheelParts[fr];
  const bodyGlb = meshFrom(bodyPos, bodyUv, bodyIdx, imageBin, json);
  const wheelGlb = meshFrom(wheelMesh.pos, wheelMesh.uv, wheelMesh.idx, imageBin, json);
  const rigWheels = wheelParts.map((part, i) => ({
    name: ["wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr"][i] || ("wheel_" + i),
    positions: part.pos,
    uvs: part.uv,
    indices: part.idx,
    hub: hubs[i]
  }));
  // Keep body vertices in world space for the rig; wheel verts are hub-local.
  const rig = rigGlb({ positions: bodyPos, uvs: bodyUv, indices: bodyIdx }, rigWheels, imageBin, json);

  const bodyPath = path.join(OUT_DIR, stem + "-body.glb");
  const wheelPath = path.join(OUT_DIR, stem + "-wheel.glb");
  const rigPath = path.join(OUT_DIR, stem + "-rig.glb");
  fs.writeFileSync(bodyPath, writeGlb(bodyGlb.json, bodyGlb.bin));
  fs.writeFileSync(wheelPath, writeGlb(wheelGlb.json, wheelGlb.bin));
  fs.writeFileSync(rigPath, writeGlb(rig.json, rig.bin));
  const meta = {
    hubs,
    radius: Math.max(...wheelMesh.pos.map((p) => Math.hypot(p[1], p[2])))
  };
  console.log(JSON.stringify({ stem, body: bodyPath, wheel: wheelPath, rig: rigPath, meta, bodyTris: bodyIdx.length / 3, wheelTris: wheelMesh.idx.length / 3 }, null, 2));
}

function extractSimpleMesh(file) {
  const { json, bin } = loadGlb(file);
  const prim = json.meshes[0].primitives[0];
  const positions = getAccessorData(json, bin, prim.attributes.POSITION).data;
  const uvAcc = prim.attributes.TEXCOORD_0;
  const uvs = uvAcc != null
    ? getAccessorData(json, bin, uvAcc).data
    : positions.map(() => [0, 0]);
  const indices = prim.indices != null
    ? getAccessorData(json, bin, prim.indices).data
    : positions.map((_, i) => i);
  let imageBin = null;
  if (json.images && json.images[0] && json.images[0].bufferView != null) {
    const view = json.bufferViews[json.images[0].bufferView];
    imageBin = bin.slice(view.byteOffset || 0, (view.byteOffset || 0) + view.byteLength);
  }
  return { json, positions, uvs, indices, imageBin };
}

function centerSpinAxis(positions) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  positions.forEach((p) => {
    for (let k = 0; k < 3; k++) {
      min[k] = Math.min(min[k], p[k]);
      max[k] = Math.max(max[k], p[k]);
    }
  });
  const c = [
    (min[0] + max[0]) / 2,
    (min[1] + max[1]) / 2,
    (min[2] + max[2]) / 2
  ];
  return {
    positions: positions.map((p) => [p[0] - c[0], p[1] - c[1], p[2] - c[2]]),
    shift: c,
    radius: Math.max(...positions.map((p) => Math.hypot(p[1] - c[1], p[2] - c[2])))
  };
}

function retargetMaterial(mat, texIndex) {
  function set(obj, key) {
    if (obj && obj[key] && typeof obj[key].index === "number") obj[key].index = texIndex;
  }
  if (mat.pbrMetallicRoughness) {
    set(mat.pbrMetallicRoughness, "baseColorTexture");
    set(mat.pbrMetallicRoughness, "metallicRoughnessTexture");
  }
  set(mat, "emissiveTexture");
  set(mat, "normalTexture");
  set(mat, "occlusionTexture");
  return mat;
}

const HUB_NUDGE = { y: -0.032, z: 0.022 };
const DEFAULT_HUBS = [
  { name: "wheel_fl", x: -0.8256, y: 0.3561 + HUB_NUDGE.y, z: 1.3780 + HUB_NUDGE.z },
  { name: "wheel_fr", x: 0.8018, y: 0.3474 + HUB_NUDGE.y, z: 1.3772 + HUB_NUDGE.z },
  { name: "wheel_rl", x: -0.7819, y: 0.3403 + HUB_NUDGE.y, z: -1.3779 + HUB_NUDGE.z },
  { name: "wheel_rr", x: 0.8095, y: 0.3234 + HUB_NUDGE.y, z: -1.3636 + HUB_NUDGE.z }
];

function composeRigGlb(body, wheels, bodyImg, wheelImg, wheelJson) {
  const sameImg = bodyImg.equals(wheelImg);
  const chunks = [];
  const views = [];
  const accessors = [];
  const nodes = [{ name: "world", children: [] }];
  const meshes = [];
  function addBuf(buf) {
    const off = chunks.reduce((s, b) => s + b.length, 0);
    chunks.push(buf);
    return off;
  }
  function addMesh(name, positions, uvs, indices, material) {
    const idxBuf = packU32(indices);
    const posBuf = packVecs(positions, 3);
    const uvBuf = packVecs(uvs, 2);
    const posMin = [Infinity, Infinity, Infinity];
    const posMax = [-Infinity, -Infinity, -Infinity];
    positions.forEach((p) => {
      for (let k = 0; k < 3; k++) {
        posMin[k] = Math.min(posMin[k], p[k]);
        posMax[k] = Math.max(posMax[k], p[k]);
      }
    });
    const iOff = addBuf(idxBuf);
    const pOff = addBuf(posBuf);
    const uOff = addBuf(uvBuf);
    const iAcc = accessors.length;
    accessors.push({ componentType: 5125, type: "SCALAR", bufferView: views.length, count: indices.length, max: [positions.length - 1], min: [0] });
    views.push({ buffer: 0, byteOffset: iOff, byteLength: idxBuf.length });
    accessors.push({ componentType: 5126, type: "VEC3", bufferView: views.length, count: positions.length, min: posMin, max: posMax });
    views.push({ buffer: 0, byteOffset: pOff, byteLength: posBuf.length });
    accessors.push({ componentType: 5126, type: "VEC2", bufferView: views.length, count: uvs.length });
    views.push({ buffer: 0, byteOffset: uOff, byteLength: uvBuf.length });
    const meshIdx = meshes.length;
    meshes.push({
      name,
      primitives: [{ attributes: { POSITION: iAcc + 1, TEXCOORD_0: iAcc + 2 }, indices: iAcc, mode: 4, material }]
    });
    return meshIdx;
  }

  const bodyMesh = addMesh("body", body.positions, body.uvs, body.indices, 0);
  nodes[0].children.push(1);
  nodes.push({ name: "body", mesh: bodyMesh });

  const wheelNodeIdx = [];
  wheels.forEach((w) => {
    const meshIdx = addMesh(w.name, w.positions, w.uvs, w.indices, sameImg ? 0 : 1);
    const hubIdx = nodes.length;
    const spinIdx = hubIdx + 1;
    wheelNodeIdx.push(spinIdx);
    nodes[0].children.push(hubIdx);
    const hub = { name: w.name + "_hub", children: [spinIdx], translation: [w.hub.x, w.hub.y, w.hub.z] };
    if (w.hub.x < 0) hub.scale = [-1, 1, 1];
    nodes.push(hub);
    nodes.push({ name: w.name, mesh: meshIdx });
  });

  const bodyImgOff = addBuf(bodyImg);
  const bodyImgView = views.length;
  views.push({ buffer: 0, byteOffset: bodyImgOff, byteLength: bodyImg.length });
  let wheelImgView = bodyImgView;
  if (!sameImg) {
    const wheelImgOff = addBuf(wheelImg);
    wheelImgView = views.length;
    views.push({ buffer: 0, byteOffset: wheelImgOff, byteLength: wheelImg.length });
  }

  const times = [0, 0.25, 0.5, 0.75, 1];
  const timeBuf = packVecs(times.map((t) => [t]), 1);
  const quats = [0, Math.PI / 2, Math.PI, (3 * Math.PI) / 2, Math.PI * 2].map((a) => quatAxisAngle(1, 0, 0, a));
  const quatBuf = packVecs(quats, 4);
  const tOff = addBuf(timeBuf);
  const qOff = addBuf(quatBuf);
  const tAcc = accessors.length;
  accessors.push({ componentType: 5126, type: "SCALAR", bufferView: views.length, count: times.length, min: [0], max: [1] });
  views.push({ buffer: 0, byteOffset: tOff, byteLength: timeBuf.length });
  const qAcc = accessors.length;
  accessors.push({ componentType: 5126, type: "VEC4", bufferView: views.length, count: quats.length });
  views.push({ buffer: 0, byteOffset: qOff, byteLength: quatBuf.length });

  const channels = [];
  const samplers = [];
  wheelNodeIdx.forEach((ni) => {
    const si = samplers.length;
    samplers.push({ input: tAcc, output: qAcc, interpolation: "LINEAR" });
    channels.push({ sampler: si, target: { node: ni, path: "rotation" } });
  });

  const bodyMat = retargetMaterial(JSON.parse(JSON.stringify(body.json.materials[0])), 0);
  const materials = [bodyMat];
  const images = [{ bufferView: bodyImgView, mimeType: (body.json.images[0] && body.json.images[0].mimeType) || "image/png" }];
  const textures = [{ source: 0 }];
  if (!sameImg) {
    const wheelMat = retargetMaterial(JSON.parse(JSON.stringify((wheelJson.materials && wheelJson.materials[0]) || bodyMat)), 1);
    materials.push(wheelMat);
    images.push({ bufferView: wheelImgView, mimeType: (wheelJson.images[0] && wheelJson.images[0].mimeType) || "image/png" });
    textures.push({ source: 1 });
  }

  const bin = Buffer.concat(chunks);
  const json = {
    asset: { version: "2.0", generator: "AABrowser-wheel-rig" },
    scene: 0,
    scenes: [{ nodes: [0] }],
    nodes,
    meshes,
    materials,
    images,
    textures,
    accessors,
    bufferViews: views,
    buffers: [{ byteLength: bin.length }],
    animations: [{ name: "spin", samplers, channels }]
  };
  return { json, bin };
}

function composeRig(bodyFile, wheelFile, outFile) {
  const body = extractSimpleMesh(bodyFile);
  const wheel = extractSimpleMesh(wheelFile);
  const spun = centerSpinAxis(wheel.positions);
  const bodyImg = body.imageBin || wheel.imageBin;
  const wheelImg = wheel.imageBin || body.imageBin;
  if (!bodyImg || !wheelImg) throw new Error("body/wheel GLB needs a PNG texture");
  const wheels = DEFAULT_HUBS.map((hub) => ({
    name: hub.name,
    positions: spun.positions,
    uvs: wheel.uvs,
    indices: wheel.indices,
    hub
  }));
  const rig = composeRigGlb(body, wheels, bodyImg, wheelImg, wheel.json);
  fs.writeFileSync(outFile, writeGlb(rig.json, rig.bin));
  const centeredWheel = meshFrom(spun.positions, wheel.uvs, wheel.indices, wheelImg, wheel.json);
  fs.writeFileSync(wheelFile, writeGlb(centeredWheel.json, centeredWheel.bin));
  console.log(JSON.stringify({
    mode: "compose",
    body: bodyFile,
    wheel: wheelFile,
    rig: outFile,
    hubs: DEFAULT_HUBS,
    spinAxisShift: spun.shift,
    radius: spun.radius,
    bodyTris: body.indices.length / 3,
    wheelTris: wheel.indices.length / 3
  }, null, 2));
}

function bakeLockedWheels(wheelFile, outFile) {
  const wheel = extractSimpleMesh(wheelFile);
  const hubs = [
    { x: -0.8256, y: 0.334, z: 1.4 },
    { x: 0.8018, y: 0.334, z: 1.3992 },
    { x: -0.7819, y: 0.334, z: -1.3559 },
    { x: 0.8095, y: 0.334, z: -1.3416 }
  ];
  const positions = [];
  const uvs = [];
  const indices = [];
  hubs.forEach((hub) => {
    const base = positions.length;
    const mirror = hub.x < 0;
    wheel.positions.forEach((p) => {
      const x = (mirror ? -p[0] : p[0]) + hub.x;
      positions.push([x, p[1] + hub.y, p[2] + hub.z]);
    });
    wheel.uvs.forEach((uv) => uvs.push(uv));
    for (let i = 0; i < wheel.indices.length; i += 3) {
      const a = base + wheel.indices[i];
      const b = base + wheel.indices[i + 1];
      const c = base + wheel.indices[i + 2];
      if (mirror) indices.push(a, c, b);
      else indices.push(a, b, c);
    }
  });
  const glb = meshFrom(positions, uvs, indices, wheel.imageBin, wheel.json);
  fs.writeFileSync(outFile, writeGlb(glb.json, glb.bin));
  console.log(JSON.stringify({ out: outFile, bytes: fs.statSync(outFile).size, verts: positions.length }));
}

function bakeSpinFrames(wheelFile, imageFile, outDir) {
  const wheel = extractSimpleMesh(wheelFile);
  const image = fs.readFileSync(imageFile);
  const mime = imageFile.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
  const fakeJson = {
    materials: wheel.json.materials,
    images: [{ mimeType: mime }]
  };
  const hubs = [
    { x: -0.8256, y: 0.334, z: 1.4 },
    { x: 0.8018, y: 0.334, z: 1.3992 },
    { x: -0.7819, y: 0.334, z: -1.3559 },
    { x: 0.8095, y: 0.334, z: -1.3416 }
  ];
  const frames = 16;
  fs.mkdirSync(outDir, { recursive: true });
  hubs.forEach((hub, hi) => {
    const mirror = hub.x < 0;
    for (let f = 0; f < frames; f++) {
      const a = (f / frames) * Math.PI * 2 * (mirror ? -1 : -1);
      const c = Math.cos(a);
      const s = Math.sin(a);
      const positions = wheel.positions.map((p) => {
        let x = p[0];
        const y = p[1] * c - p[2] * s;
        const z = p[1] * s + p[2] * c;
        if (mirror) x = -x;
        return [x + hub.x, y + hub.y, z + hub.z];
      });
      const indices = [];
      for (let i = 0; i < wheel.indices.length; i += 3) {
        const a0 = wheel.indices[i];
        const b0 = wheel.indices[i + 1];
        const c0 = wheel.indices[i + 2];
        if (mirror) indices.push(a0, c0, b0);
        else indices.push(a0, b0, c0);
      }
      const glb = meshFrom(positions, wheel.uvs, indices, image, fakeJson);
      const file = path.join(outDir, "h" + hi + "-f" + f + ".glb");
      fs.writeFileSync(file, writeGlb(glb.json, glb.bin));
    }
  });
  console.log(JSON.stringify({ outDir, frames, hubs: hubs.length, image: image.length }));
}

const composeIdx = process.argv.indexOf("--compose");
const spinIdx = process.argv.indexOf("--bake-spin");
const bakeIdx = process.argv.indexOf("--bake-wheels");
if (spinIdx >= 0) {
  const wheelFile = path.resolve(process.argv[spinIdx + 1] || path.join(OUT_DIR, "forte-new-wheel.glb"));
  const imageFile = path.resolve(process.argv[spinIdx + 2]);
  const outDir = path.resolve(process.argv[spinIdx + 3] || path.join(OUT_DIR, "wheel-spin"));
  if (!imageFile) throw new Error("jpeg path required");
  bakeSpinFrames(wheelFile, imageFile, outDir);
} else if (bakeIdx >= 0) {
  const wheelFile = path.resolve(process.argv[bakeIdx + 1] || path.join(OUT_DIR, "forte-new-wheel.glb"));
  const outFile = path.resolve(process.argv[bakeIdx + 2] || path.join(OUT_DIR, "forte-wheels.glb"));
  bakeLockedWheels(wheelFile, outFile);
} else if (composeIdx >= 0) {
  const bodyFile = path.resolve(process.argv[composeIdx + 1] || path.join(OUT_DIR, "forte-new-body.glb"));
  const wheelFile = path.resolve(process.argv[composeIdx + 2] || path.join(OUT_DIR, "forte-new-wheel.glb"));
  const rigFile = path.resolve(process.argv[composeIdx + 3] || path.join(OUT_DIR, "forte-new-rig.glb"));
  composeRig(bodyFile, wheelFile, rigFile);
} else {
  split(path.resolve(__dirname, "..", SRC), STEM);
}
