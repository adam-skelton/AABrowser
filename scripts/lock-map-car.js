// Build the nav car GLB: body plus four wheels parented in model space.
// Separate wheel models sample the ground under each hub, so a bump lifts one
// wheel out of the arch. One model shares the body's ground sample.
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..", "pages");
const src = path.join(root, "forte-new-rig.glb");
const out = path.join(root, "forte-map.glb");

const HUBS = {
  wheel_fl_hub: [-0.8256, 0.334, 1.4],
  wheel_fr_hub: [0.8018, 0.334, 1.3992],
  wheel_rl_hub: [-0.7819, 0.334, -1.3559],
  wheel_rr_hub: [0.8095, 0.334, -1.3416]
};

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
  header.writeUInt32LE(12 + 8 + jsonBuf.length + 8 + binBuf.length, 8);
  const jh = Buffer.alloc(8);
  jh.writeUInt32LE(jsonBuf.length, 0);
  jh.writeUInt32LE(0x4e4f534a, 4);
  const bh = Buffer.alloc(8);
  bh.writeUInt32LE(binBuf.length, 0);
  bh.writeUInt32LE(0x004e4942, 4);
  return Buffer.concat([header, jh, jsonBuf, bh, binBuf]);
}

const file = fs.readFileSync(src);
const jsonLen = file.readUInt32LE(12);
const json = JSON.parse(file.slice(20, 20 + jsonLen).toString("utf8"));
let binOff = 20 + jsonLen;
if (binOff % 4) binOff += 4 - (binOff % 4);
const binLen = file.readUInt32LE(binOff);
const bin = Buffer.from(file.slice(binOff + 8, binOff + 8 + binLen));

function viewStart(acc) {
  const view = json.bufferViews[acc.bufferView];
  return (view.byteOffset || 0) + (acc.byteOffset || 0);
}

const report = [];
for (const node of json.nodes) {
  if (!HUBS[node.name]) continue;
  node.translation = HUBS[node.name].slice();
  const mirror = node.scale && node.scale[0] < 0;
  if (!mirror) {
    delete node.scale;
    continue;
  }
  const child = json.nodes[node.children[0]];
  const prim = json.meshes[child.mesh].primitives[0];
  const posAcc = json.accessors[prim.attributes.POSITION];
  const posView = json.bufferViews[posAcc.bufferView];
  const stride = posView.byteStride || 12;
  const posStart = viewStart(posAcc);
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (let i = 0; i < posAcc.count; i++) {
    const o = posStart + i * stride;
    const x = -bin.readFloatLE(o);
    bin.writeFloatLE(x, o);
    const y = bin.readFloatLE(o + 4);
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  posAcc.min[0] = minX;
  posAcc.max[0] = maxX;
  const idxAcc = json.accessors[prim.indices];
  const idxStart = viewStart(idxAcc);
  const width = idxAcc.componentType === 5123 ? 2 : 4;
  const read = width === 2 ? (o) => bin.readUInt16LE(o) : (o) => bin.readUInt32LE(o);
  const write = width === 2 ? (v, o) => bin.writeUInt16LE(v, o) : (v, o) => bin.writeUInt32LE(v, o);
  for (let t = 0; t < idxAcc.count; t += 3) {
    const aOff = idxStart + t * width;
    const bOff = aOff + width;
    const a = read(aOff);
    const b = read(bOff);
    write(b, aOff);
    write(a, bOff);
  }
  delete node.scale;
  report.push({ name: node.name, minY, maxY, hubY: node.translation[1] });
}

delete json.animations;
fs.writeFileSync(out, writeGlb(json, bin));
console.log(JSON.stringify({ out, bytes: fs.statSync(out).size, report }, null, 2));
