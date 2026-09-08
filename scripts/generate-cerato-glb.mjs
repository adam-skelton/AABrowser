import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Original compact-sedan geometry sized to a 2021 Kia Cerato (BD):
// 4.64 m long, 1.80 m wide, 1.44 m tall. Not a Kia-licensed CAD model.
const materials = [];
const meshes = [];
const nodes = [{ name: "cerato", children: [] }];
const accessors = [];
const bufferViews = [];
const blobs = [];

function pushBytes(bytes, target) {
  const offset = blobs.reduce((sum, b) => sum + b.length, 0);
  blobs.push(bytes);
  bufferViews.push({
    buffer: 0,
    byteOffset: offset,
    byteLength: bytes.length,
    target
  });
  return bufferViews.length - 1;
}

function f32(values) {
  return new Uint8Array(new Float32Array(values).buffer);
}

function u16(values) {
  const out = new Uint16Array(values);
  return new Uint8Array(out.buffer);
}

function addMaterial(name, color, metallic = 0.35, roughness = 0.42) {
  materials.push({
    name,
    pbrMetallicRoughness: {
      baseColorFactor: color,
      metallicFactor: metallic,
      roughnessFactor: roughness
    }
  });
  return materials.length - 1;
}

function addBox(name, x0, y0, z0, x1, y1, z1, material) {
  const hx = [x0, x1];
  const hy = [y0, y1];
  const hz = [z0, z1];
  const faces = [
    { n: [0, 0, 1], corners: [[0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]] },
    { n: [0, 0, -1], corners: [[1, 0, 0], [0, 0, 0], [0, 1, 0], [1, 1, 0]] },
    { n: [0, 1, 0], corners: [[0, 1, 1], [1, 1, 1], [1, 1, 0], [0, 1, 0]] },
    { n: [0, -1, 0], corners: [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]] },
    { n: [1, 0, 0], corners: [[1, 0, 1], [1, 0, 0], [1, 1, 0], [1, 1, 1]] },
    { n: [-1, 0, 0], corners: [[0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]] }
  ];
  const positions = [];
  const normals = [];
  const indices = [];
  let v = 0;
  for (const face of faces) {
    for (const c of face.corners) {
      positions.push(hx[c[0]], hy[c[1]], hz[c[2]]);
      normals.push(...face.n);
    }
    indices.push(v, v + 1, v + 2, v, v + 2, v + 3);
    v += 4;
  }
  const posMin = [Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1)];
  const posMax = [Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1)];
  const posView = pushBytes(f32(positions), 34962);
  const nrmView = pushBytes(f32(normals), 34962);
  const idxView = pushBytes(u16(indices), 34963);
  accessors.push({
    bufferView: posView,
    componentType: 5126,
    count: positions.length / 3,
    type: "VEC3",
    min: posMin,
    max: posMax
  });
  accessors.push({
    bufferView: nrmView,
    componentType: 5126,
    count: normals.length / 3,
    type: "VEC3"
  });
  accessors.push({
    bufferView: idxView,
    componentType: 5123,
    count: indices.length,
    type: "SCALAR"
  });
  const posAcc = accessors.length - 3;
  meshes.push({
    name,
    primitives: [{
      attributes: { POSITION: posAcc, NORMAL: posAcc + 1 },
      indices: posAcc + 2,
      material
    }]
  });
  const nodeIndex = nodes.length;
  nodes.push({ mesh: meshes.length - 1, name });
  nodes[0].children.push(nodeIndex);
}

const body = addMaterial("body", [0.55, 0.57, 0.59, 1], 0.72, 0.28);
const dark = addMaterial("trim", [0.07, 0.07, 0.08, 1], 0.2, 0.55);
const glass = addMaterial("glass", [0.14, 0.18, 0.22, 0.92], 0.9, 0.08);
const rubber = addMaterial("rubber", [0.05, 0.05, 0.05, 1], 0.05, 0.85);
const light = addMaterial("headlamp", [0.92, 0.94, 0.88, 1], 0.85, 0.12);
const tail = addMaterial("tail", [0.62, 0.08, 0.08, 1], 0.45, 0.35);
const silver = addMaterial("silver", [0.72, 0.73, 0.74, 1], 0.85, 0.18);

// Y-up, +Z forward. Origin at ground, car centered.
addBox("body", -0.86, 0.18, -2.22, 0.86, 0.72, 2.18, body);
addBox("sills", -0.90, 0.16, -1.85, 0.90, 0.34, 1.55, body);
addBox("hood", -0.82, 0.70, 0.55, 0.82, 0.86, 2.02, body);
addBox("boot", -0.82, 0.70, -2.18, 0.82, 0.90, -0.62, body);
addBox("cabin", -0.78, 0.70, -0.58, 0.78, 1.28, 0.62, body);
addBox("roof", -0.70, 1.26, -0.48, 0.70, 1.42, 0.48, body);
addBox("frontBumper", -0.88, 0.18, 2.18, 0.88, 0.52, 2.32, body);
addBox("rearBumper", -0.88, 0.18, -2.32, 0.88, 0.52, -2.18, body);
addBox("grille", -0.42, 0.34, 2.30, 0.42, 0.58, 2.34, dark);
addBox("windshield", -0.74, 0.86, 0.48, 0.74, 1.30, 0.92, glass);
addBox("rearGlass", -0.74, 0.90, -0.92, 0.74, 1.28, -0.52, glass);
addBox("sideGlassL", -0.80, 0.86, -0.48, -0.76, 1.22, 0.48, glass);
addBox("sideGlassR", 0.76, 0.86, -0.48, 0.80, 1.22, 0.48, glass);
addBox("mirrorL", -1.02, 0.82, 0.38, -0.86, 0.96, 0.58, body);
addBox("mirrorR", 0.86, 0.82, 0.38, 1.02, 0.96, 0.58, body);
addBox("headL", -0.78, 0.36, 2.28, -0.38, 0.58, 2.34, light);
addBox("headR", 0.38, 0.36, 2.28, 0.78, 0.58, 2.34, light);
addBox("tailL", -0.78, 0.38, -2.34, -0.34, 0.58, -2.28, tail);
addBox("tailR", 0.34, 0.38, -2.34, 0.78, 0.58, -2.28, tail);
addBox("plate", -0.22, 0.28, 2.32, 0.22, 0.42, 2.35, silver);
addBox("wheelFL", -0.92, 0.00, 1.18, -0.62, 0.32, 1.50, rubber);
addBox("wheelFR", 0.62, 0.00, 1.18, 0.92, 0.32, 1.50, rubber);
addBox("wheelRL", -0.92, 0.00, -1.48, -0.62, 0.32, -1.16, rubber);
addBox("wheelRR", 0.62, 0.00, -1.48, 0.92, 0.32, -1.16, rubber);

const jsonPad = (text) => {
  const extra = (4 - (text.length % 4)) % 4;
  return text + " ".repeat(extra);
};

const binParts = blobs;
let bin = Buffer.concat(binParts.map((b) => Buffer.from(b)));
if (bin.length % 4) {
  bin = Buffer.concat([bin, Buffer.alloc((4 - (bin.length % 4)) % 4)]);
}

const json = jsonPad(JSON.stringify({
  asset: { version: "2.0", generator: "AABrowser Cerato stand-in" },
  scene: 0,
  scenes: [{ nodes: [0] }],
  nodes,
  meshes,
  materials,
  accessors,
  bufferViews,
  buffers: [{ byteLength: bin.length }]
}));

const jsonBuf = Buffer.from(json);
const jsonChunk = Buffer.concat([
  Buffer.from(new Uint32Array([jsonBuf.length, 0x4E4F534A]).buffer),
  jsonBuf
]);
const binChunk = Buffer.concat([
  Buffer.from(new Uint32Array([bin.length, 0x004E4942]).buffer),
  bin
]);
const total = 12 + jsonChunk.length + binChunk.length;
const header = Buffer.concat([
  Buffer.from("glTF"),
  Buffer.from(new Uint32Array([2, total]).buffer)
]);
const glb = Buffer.concat([header, jsonChunk, binChunk]);

const out = join(dirname(fileURLToPath(import.meta.url)), "..", "pages", "kia-cerato-2021.glb");
writeFileSync(out, glb);
console.log(`Wrote ${out} (${glb.length} bytes)`);
