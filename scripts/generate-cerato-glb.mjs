import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Procedural 2021 Kia Cerato (BD) sedan stand-in. Not Kia CAD data.
// Real proportions: 4.64 m long, 1.80 m wide, 1.44 m tall, 2.70 m wheelbase.
// Coordinate system: Y up, +Z is the nose, +X is the right-hand side.
// Origin is on the ground directly under the centre of the car.

// ---------------------------------------------------------------- materials
const materials = [];
function addMaterial(name, color, metallic, roughness, emissive) {
  const material = {
    name,
    pbrMetallicRoughness: {
      baseColorFactor: color,
      metallicFactor: metallic,
      roughnessFactor: roughness
    }
  };
  if (emissive) material.emissiveFactor = emissive;
  materials.push(material);
  return materials.length - 1;
}

const M = {
  body: addMaterial("steel-grey-paint", [0.64, 0.67, 0.72, 1], 0.12, 0.42, [0.16, 0.17, 0.19]),
  dark: addMaterial("black-trim", [0.16, 0.16, 0.18, 1], 0.04, 0.72, [0.03, 0.03, 0.035]),
  glass: addMaterial("tinted-glass", [0.28, 0.36, 0.44, 1], 0.08, 0.28, [0.08, 0.1, 0.13]),
  rubber: addMaterial("tyre", [0.12, 0.12, 0.12, 1], 0.0, 0.9, [0.02, 0.02, 0.02]),
  chrome: addMaterial("chrome", [0.82, 0.84, 0.86, 1], 0.22, 0.32, [0.18, 0.18, 0.2]),
  alloy: addMaterial("alloy-wheel", [0.62, 0.64, 0.66, 1], 0.18, 0.4, [0.1, 0.1, 0.11]),
  lamp: addMaterial("headlamp", [0.92, 0.94, 0.97, 1], 0.15, 0.22, [0.45, 0.48, 0.52]),
  drl: addMaterial("drl", [0.95, 0.97, 1, 1], 0.08, 0.3, [0.9, 0.92, 1]),
  fog: addMaterial("fog-lamp", [0.95, 0.72, 0.28, 1], 0.08, 0.3, [0.7, 0.4, 0.05]),
  tail: addMaterial("tail-lamp", [0.72, 0.12, 0.12, 1], 0.08, 0.28, [0.55, 0.04, 0.04]),
  plate: addMaterial("number-plate", [0.94, 0.94, 0.9, 1], 0.02, 0.6, [0.12, 0.12, 0.1]),
  indicator: addMaterial("indicator", [0.95, 0.7, 0.2, 1], 0.08, 0.3, [0.55, 0.32, 0.05])
};

// ------------------------------------------------------------ vector helpers
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const scale = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [
  a[1] * b[2] - a[2] * b[1],
  a[2] * b[0] - a[0] * b[2],
  a[0] * b[1] - a[1] * b[0]
];
function normalize(v) {
  const len = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0] / len, v[1] / len, v[2] / len];
}
const lerp = (a, b, t) => a + (b - a) * t;

// ---------------------------------------------------------------- mesh store
class Mesh {
  constructor(material) {
    this.material = material;
    this.positions = [];
    this.normals = [];
    this.indices = [];
    this.lookup = new Map();
  }
  vertex(p, n) {
    const key = `${p[0].toFixed(4)},${p[1].toFixed(4)},${p[2].toFixed(4)}|${n[0].toFixed(3)},${n[1].toFixed(3)},${n[2].toFixed(3)}`;
    let index = this.lookup.get(key);
    if (index === undefined) {
      index = this.positions.length / 3;
      this.positions.push(p[0], p[1], p[2]);
      this.normals.push(n[0], n[1], n[2]);
      this.lookup.set(key, index);
    }
    return index;
  }
  tri(a, b, c, na, nb, nc) {
    this.indices.push(this.vertex(a, na), this.vertex(b, nb), this.vertex(c, nc));
  }
}

const meshes = new Map();
function mesh(material) {
  if (!meshes.has(material)) meshes.set(material, new Mesh(material));
  return meshes.get(material);
}

// Flat quad. `hint` points roughly outward so winding can be fixed automatically.
function quad(material, a, b, c, d, hint) {
  let n = normalize(cross(sub(b, a), sub(d, a)));
  if (hint && dot(n, hint) < 0) {
    [b, d] = [d, b];
    n = scale(n, -1);
  }
  const m = mesh(material);
  m.tri(a, b, c, n, n, n);
  m.tri(a, c, d, n, n, n);
}

// Quad with explicit per-vertex normals (used for smooth cylinders).
function quadSmooth(material, a, b, c, d, na, nb, nc, nd, hint) {
  let n = cross(sub(b, a), sub(d, a));
  if (hint && dot(n, hint) < 0) {
    [b, d] = [d, b];
    [nb, nd] = [nd, nb];
  }
  const m = mesh(material);
  m.tri(a, b, c, na, nb, nc);
  m.tri(a, c, d, na, nc, nd);
}

function triFlat(material, a, b, c, hint) {
  let n = normalize(cross(sub(b, a), sub(c, a)));
  if (hint && dot(n, hint) < 0) {
    [b, c] = [c, b];
    n = scale(n, -1);
  }
  mesh(material).tri(a, b, c, n, n, n);
}

const identity = (p) => p;

function box(material, min, max, transform = identity) {
  const corner = (i) => transform([
    (i & 1) ? max[0] : min[0],
    (i & 2) ? max[1] : min[1],
    (i & 4) ? max[2] : min[2]
  ]);
  const c = [];
  for (let i = 0; i < 8; i++) c.push(corner(i));
  const center = transform([(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2]);
  const faces = [
    [0, 2, 3, 1], // -z
    [4, 5, 7, 6], // +z
    [0, 1, 5, 4], // -y
    [2, 6, 7, 3], // +y
    [0, 4, 6, 2], // -x
    [1, 3, 7, 5]  // +x
  ];
  for (const f of faces) {
    const a = c[f[0]], b = c[f[1]], cc = c[f[2]], d = c[f[3]];
    const faceCenter = scale(add(add(a, b), add(cc, d)), 0.25);
    quad(material, a, b, cc, d, sub(faceCenter, center));
  }
}

// Cylinder / tube along the X axis (used for wheels).
function cylinderX(material, cy, cz, x0, x1, rOuter, rInner, segments, transform = identity) {
  const ring = (x, r, a) => transform([x, cy + r * Math.cos(a), cz + r * Math.sin(a)]);
  const radial = (a) => {
    const p0 = transform([0, cy, cz]);
    const p1 = transform([0, cy + Math.cos(a), cz + Math.sin(a)]);
    return normalize(sub(p1, p0));
  };
  const axisOut = normalize(sub(transform([x1, cy, cz]), transform([x0, cy, cz])));
  const centerX0 = transform([x0, cy, cz]);
  const centerX1 = transform([x1, cy, cz]);
  for (let i = 0; i < segments; i++) {
    const a0 = (i / segments) * Math.PI * 2;
    const a1 = ((i + 1) / segments) * Math.PI * 2;
    const n0 = radial(a0), n1 = radial(a1);
    // outer wall
    quadSmooth(material,
      ring(x0, rOuter, a0), ring(x0, rOuter, a1), ring(x1, rOuter, a1), ring(x1, rOuter, a0),
      n0, n1, n1, n0, radial((a0 + a1) / 2));
    if (rInner > 0) {
      const m0 = scale(n0, -1), m1 = scale(n1, -1);
      quadSmooth(material,
        ring(x0, rInner, a0), ring(x0, rInner, a1), ring(x1, rInner, a1), ring(x1, rInner, a0),
        m0, m1, m1, m0, scale(radial((a0 + a1) / 2), -1));
      quad(material, ring(x0, rInner, a0), ring(x0, rOuter, a0), ring(x0, rOuter, a1), ring(x0, rInner, a1), scale(axisOut, -1));
      quad(material, ring(x1, rInner, a0), ring(x1, rOuter, a0), ring(x1, rOuter, a1), ring(x1, rInner, a1), axisOut);
    } else {
      triFlat(material, centerX0, ring(x0, rOuter, a0), ring(x0, rOuter, a1), scale(axisOut, -1));
      triFlat(material, centerX1, ring(x1, rOuter, a0), ring(x1, rOuter, a1), axisOut);
    }
  }
}

// -------------------------------------------------------------- body profile
// Tables are [z, value] sorted from nose (+z) to tail (-z).
function interp(table, z) {
  if (z >= table[0][0]) return table[0][1];
  for (let i = 0; i < table.length - 1; i++) {
    const [z0, v0] = table[i];
    const [z1, v1] = table[i + 1];
    if (z <= z0 && z >= z1) {
      const t = (z0 - z) / (z0 - z1 || 1);
      return lerp(v0, v1, t);
    }
  }
  return table[table.length - 1][1];
}

const LENGTH_HALF = 2.32;
const WHEELBASE_HALF = 1.35;
const WHEEL_RADIUS = 0.316;
const WHEEL_Y = WHEEL_RADIUS;
const ARCH_RADIUS = 0.375;
const FLOOR_Y = 0.2;

// Highest point of the body at each z (hood / windscreen / roof / boot).
const TOP = [
  [2.32, 0.72], [2.28, 0.745], [2.20, 0.77], [2.05, 0.80], [1.70, 0.84], [1.25, 0.88],
  [0.95, 0.91], [0.78, 0.94], [0.70, 0.99], [0.58, 1.10], [0.44, 1.22], [0.30, 1.32],
  [0.16, 1.39], [0.0, 1.425], [-0.40, 1.44], [-0.75, 1.425], [-0.95, 1.39], [-1.05, 1.33],
  [-1.20, 1.22], [-1.38, 1.09], [-1.52, 1.01], [-1.68, 0.985], [-2.00, 0.98], [-2.18, 0.975],
  [-2.26, 0.95], [-2.30, 0.90], [-2.32, 0.84]
];
// Half width at the widest (belt) line.
const WIDTH = [
  [2.32, 0.60], [2.28, 0.72], [2.20, 0.80], [2.08, 0.855], [1.85, 0.885], [1.30, 0.90],
  [-1.30, 0.90], [-1.85, 0.89], [-2.10, 0.865], [-2.22, 0.82], [-2.29, 0.72], [-2.32, 0.60]
];
// Height of the widest line (fender top / door shoulder).
const BELT = [
  [2.32, 0.52], [2.20, 0.64], [2.00, 0.72], [1.60, 0.775], [1.00, 0.80], [0.50, 0.83],
  [0.0, 0.86], [-0.60, 0.885], [-1.35, 0.91], [-1.80, 0.925], [-2.20, 0.89], [-2.32, 0.76]
];
// Half width of the roof / hood top surface.
const ROOF_W = [
  [2.32, 0.42], [2.20, 0.68], [2.00, 0.76], [1.20, 0.80], [0.78, 0.79], [0.60, 0.72],
  [0.40, 0.66], [0.16, 0.62], [-0.90, 0.62], [-1.10, 0.66], [-1.40, 0.74], [-1.70, 0.78],
  [-2.15, 0.76], [-2.28, 0.62], [-2.32, 0.42]
];
const FLOOR = [
  [2.32, 0.34], [2.26, 0.27], [2.18, FLOOR_Y], [-2.18, FLOOR_Y], [-2.26, 0.27], [-2.32, 0.34]
];

function archY(z) {
  for (const zc of [WHEELBASE_HALF, -WHEELBASE_HALF]) {
    const dz = Math.abs(z - zc);
    if (dz < ARCH_RADIUS) {
      return Math.max(FLOOR_Y, WHEEL_Y + Math.sqrt(ARCH_RADIUS * ARCH_RADIUS - dz * dz));
    }
  }
  return interp(FLOOR, z);
}

// Half profile: 13 points from floor centre up the right side to roof centre.
function halfProfile(z) {
  const top = interp(TOP, z);
  const w = interp(WIDTH, z);
  const belt = Math.min(interp(BELT, z), top - 0.02);
  const roofW = Math.min(interp(ROOF_W, z), w - 0.03);
  const floor = interp(FLOOR, z);
  const arch = archY(z);
  const sillX = Math.max(0.3, w - 0.045);
  const wellX = Math.min(0.56, sillX - 0.05);

  const p3 = [sillX, arch];
  const p4 = [w - 0.02, Math.max(belt - 0.27, arch + 0.03)];
  const p5 = [w, Math.max(belt - 0.07, p4[1] + 0.01)];
  const p6 = [w, belt];
  const shoulderY = Math.min(belt + 0.06, top - 0.01);
  const p7 = [w - 0.05, shoulderY];
  const p10 = [roofW, top - 0.02];
  const glass = (t) => [
    lerp(p7[0], p10[0], t) + Math.sin(Math.PI * t) * 0.025,
    lerp(p7[1], p10[1], t)
  ];
  return [
    [0, floor],
    [wellX, floor],
    [wellX, arch],
    p3, p4, p5, p6, p7,
    glass(1 / 3),
    glass(2 / 3),
    p10,
    [roofW * 0.55, top],
    [0, top]
  ];
}

function ringAt(z) {
  const half = halfProfile(z);
  const ring = [];
  for (let i = 0; i <= 12; i++) ring.push([half[i][0], half[i][1], z]);
  for (let i = 11; i >= 1; i--) ring.push([-half[i][0], half[i][1], z]);
  return ring; // 24 points
}

function segmentIndex(r) {
  return r < 12 ? r : 23 - r;
}

// Panel gaps painted into the skin: front door leading edge, door split, rear door trailing edge.
const SEAMS = [[0.66, 0.674], [-0.16, -0.146], [-1.02, -1.006]];

function loftMaterial(zm, k) {
  if (k <= 2) return M.dark;
  if (k >= 3 && k <= 7 && SEAMS.some(([a, b]) => zm >= a && zm <= b)) return M.dark;
  const sideGlass = zm <= 0.60 && zm >= -0.95 && !(zm <= -0.13 && zm >= -0.27);
  const windscreen = zm <= 0.74 && zm >= 0.14;
  const rearGlass = zm <= -0.96 && zm >= -1.50;
  if (k === 7 || k === 8) return sideGlass ? M.glass : M.body;
  if (k === 9) return windscreen ? M.glass : M.body;
  if (k === 10 || k === 11) return (windscreen || rearGlass) ? M.glass : M.body;
  return M.body;
}

function buildSections() {
  const zs = new Set();
  for (let z = LENGTH_HALF; z >= -LENGTH_HALF - 1e-6; z -= 0.08) zs.add(Number(z.toFixed(3)));
  const extra = [
    2.32, 2.30, 2.28, 2.24, 2.20, 2.12, -2.12, -2.20, -2.24, -2.28, -2.30, -2.32,
    0.78, 0.74, 0.70, 0.674, 0.66, 0.62, 0.60, 0.58, 0.16, 0.14, 0.12,
    -0.13, -0.146, -0.15, -0.16, -0.25, -0.27, -0.94, -0.96, -0.98, -1.006, -1.02,
    -1.48, -1.50, -1.52, -1.60, -1.68
  ];
  for (const z of extra) zs.add(z);
  for (const zc of [WHEELBASE_HALF, -WHEELBASE_HALF]) {
    for (const d of [0.375, 0.36, 0.32, 0.26, 0.18, 0.09, 0]) {
      zs.add(Number((zc + d).toFixed(3)));
      zs.add(Number((zc - d).toFixed(3)));
    }
  }
  const sorted = [...zs].filter((z) => z <= LENGTH_HALF && z >= -LENGTH_HALF).sort((a, b) => b - a);
  const deduped = [];
  for (const z of sorted) {
    if (!deduped.length || Math.abs(deduped[deduped.length - 1] - z) > 0.006) deduped.push(z);
  }
  return deduped;
}

function buildBody() {
  const zs = buildSections();
  const rings = zs.map(ringAt);
  const sectionCount = rings.length;
  const RING = 24;

  // Face list with material, then smooth normals per (material, vertex).
  const faces = [];
  for (let i = 0; i < sectionCount - 1; i++) {
    const zm = (zs[i] + zs[i + 1]) / 2;
    const centroid = [0, (rings[i][0][1] + rings[i][12][1]) / 2, zs[i]];
    for (let r = 0; r < RING; r++) {
      const r2 = (r + 1) % RING;
      const a = rings[i][r], b = rings[i][r2], c = rings[i + 1][r2], d = rings[i + 1][r];
      let n = cross(sub(b, a), sub(d, a));
      if (Math.hypot(n[0], n[1], n[2]) < 1e-9) n = cross(sub(c, a), sub(d, a));
      if (Math.hypot(n[0], n[1], n[2]) < 1e-9) continue;
      const faceCenter = scale(add(add(a, b), add(c, d)), 0.25);
      let flip = false;
      if (dot(n, sub(faceCenter, centroid)) < 0) {
        n = scale(n, -1);
        flip = true;
      }
      faces.push({
        material: loftMaterial(zm, segmentIndex(r)),
        verts: flip ? [[i, r], [i + 1, r], [i + 1, r2], [i, r2]] : [[i, r], [i, r2], [i + 1, r2], [i + 1, r]],
        normal: normalize(n),
        area: Math.hypot(n[0], n[1], n[2])
      });
    }
  }

  // Accumulate normals per material so creases at material borders stay crisp.
  const accum = new Map();
  const keyOf = (material, i, r) => `${material}:${i}:${r}`;
  for (const face of faces) {
    for (const [i, r] of face.verts) {
      const key = keyOf(face.material, i, r);
      const current = accum.get(key) || [0, 0, 0];
      accum.set(key, add(current, scale(face.normal, face.area)));
    }
  }
  for (const face of faces) {
    const pts = face.verts.map(([i, r]) => rings[i][r]);
    const nrm = face.verts.map(([i, r]) => normalize(accum.get(keyOf(face.material, i, r))));
    const m = mesh(face.material);
    m.tri(pts[0], pts[1], pts[2], nrm[0], nrm[1], nrm[2]);
    m.tri(pts[0], pts[2], pts[3], nrm[0], nrm[2], nrm[3]);
  }

  // Nose and tail caps.
  capRing(rings[0], zs[0], [0, 0, 1]);
  capRing(rings[sectionCount - 1], zs[sectionCount - 1], [0, 0, -1]);
}

function capRing(ring, z, hint) {
  const ys = ring.map((p) => p[1]);
  const center = [0, (Math.min(...ys) + Math.max(...ys)) / 2, z];
  const darkBelow = hint[2] > 0 ? 0.48 : 0.46;
  for (let r = 0; r < ring.length; r++) {
    const a = ring[r], b = ring[(r + 1) % ring.length];
    const material = (a[1] <= darkBelow && b[1] <= darkBelow) ? M.dark : M.body;
    triFlat(material, center, a, b, hint);
  }
}

// ------------------------------------------------------- body-hugging patches
// Surface point and outward normal on the right-hand skin at height y for section z.
function surfacePoint(z, y) {
  const half = halfProfile(z);
  y = Math.min(Math.max(y, half[3][1] + 1e-4), half[12][1] - 1e-4);
  for (let k = 3; k < 12; k++) {
    const a = half[k], b = half[k + 1];
    if (y >= Math.min(a[1], b[1]) - 1e-6 && y <= Math.max(a[1], b[1]) + 1e-6) {
      const t = Math.abs(b[1] - a[1]) < 1e-6 ? 0 : (y - a[1]) / (b[1] - a[1]);
      const x = lerp(a[0], b[0], t);
      const dx = b[0] - a[0], dy = b[1] - a[1];
      const len = Math.hypot(dx, dy) || 1;
      return { p: [x, y, z], n: [dy / len, -dx / len, 0] };
    }
  }
  const top = half[12];
  return { p: [Math.min(half[11][0], 0.01), top[1], z], n: [0, 1, 0] };
}

// A thin slab that follows the skin between z0..z1 and y0..y1 (y may be functions of z).
function patch(material, z0, z1, y0, y1, thickness, transform = identity, zSteps = 10, ySteps = 4) {
  const yAt = (fn, z) => (typeof fn === "function" ? fn(z) : fn);
  const S = [], O = [];
  for (let i = 0; i <= zSteps; i++) {
    const z = lerp(z0, z1, i / zSteps);
    const rowS = [], rowO = [];
    const ya = yAt(y0, z), yb = yAt(y1, z);
    for (let j = 0; j <= ySteps; j++) {
      const y = lerp(ya, yb, j / ySteps);
      const { p, n } = surfacePoint(z, y);
      rowS.push(transform(p));
      rowO.push(transform(add(p, scale(n, thickness))));
    }
    S.push(rowS);
    O.push(rowO);
  }
  const out = (i, j) => sub(O[i][j], S[i][j]);
  for (let i = 0; i < zSteps; i++) {
    for (let j = 0; j < ySteps; j++) {
      quad(material, O[i][j], O[i][j + 1], O[i + 1][j + 1], O[i + 1][j], out(i, j));
    }
  }
  const zDir = normalize(sub(S[zSteps][0], S[0][0]));
  for (let j = 0; j < ySteps; j++) {
    quad(material, S[0][j], S[0][j + 1], O[0][j + 1], O[0][j], scale(zDir, -1));
    quad(material, S[zSteps][j], S[zSteps][j + 1], O[zSteps][j + 1], O[zSteps][j], zDir);
  }
  for (let i = 0; i < zSteps; i++) {
    quad(material, S[i][0], S[i + 1][0], O[i + 1][0], O[i][0], [0, -1, 0]);
    quad(material, S[i][ySteps], S[i + 1][ySteps], O[i + 1][ySteps], O[i][ySteps], [0, 1, 0]);
  }
}

// ------------------------------------------------------------------ details
function mirrorX(p) { return [-p[0], p[1], p[2]]; }

function bothSides(fn) {
  fn(identity);
  fn(mirrorX);
}

function buildFrontDetails() {
  const zf = LENGTH_HALF;
  // Tiger-nose grille with chrome surround, on the flat nose face.
  box(M.dark, [-0.44, 0.50, zf - 0.02], [0.44, 0.65, zf + 0.02]);
  box(M.chrome, [-0.46, 0.65, zf - 0.01], [0.46, 0.665, zf + 0.025]);
  box(M.chrome, [-0.46, 0.485, zf - 0.01], [0.46, 0.50, zf + 0.025]);
  box(M.chrome, [-0.46, 0.485, zf - 0.01], [-0.44, 0.665, zf + 0.025]);
  box(M.chrome, [0.44, 0.485, zf - 0.01], [0.46, 0.665, zf + 0.025]);
  // Grille mesh bars.
  for (let y = 0.525; y < 0.64; y += 0.03) {
    box(M.chrome, [-0.42, y, zf + 0.018], [0.42, y + 0.006, zf + 0.024]);
  }
  // Kia badge on the grille.
  box(M.chrome, [-0.07, 0.67, zf - 0.02], [0.07, 0.70, zf + 0.012]);
  // Lower intake and number plate.
  box(M.dark, [-0.60, 0.30, zf - 0.02], [0.60, 0.44, zf + 0.015]);
  box(M.plate, [-0.19, 0.33, zf + 0.012], [0.19, 0.46, zf + 0.022]);
  // Dark lower bumper hugging the nose sides.
  bothSides((tf) => patch(M.dark, zf - 0.01, zf - 0.20, 0.215, 0.30, 0.008, tf, 6, 2));
  bothSides((tf) => {
    // Swept headlamp: block on the nose face plus a wrap that follows the fender.
    box(M.lamp, [0.28, 0.60, zf - 0.03], [0.60, 0.71, zf + 0.014], tf);
    patch(M.lamp, zf - 0.01, zf - 0.30, 0.625, (z) => interp(TOP, z) - 0.03, 0.012, tf, 10, 3);
    // DRL strip along the lamp top edge.
    patch(M.drl, zf - 0.005, zf - 0.28, (z) => interp(TOP, z) - 0.045, (z) => interp(TOP, z) - 0.03, 0.016, tf, 10, 1);
    // Fog lamp pocket and lamp.
    box(M.dark, [0.62, 0.32, zf - 0.03], [0.82, 0.46, zf + 0.008], tf);
    box(M.fog, [0.66, 0.35, zf + 0.004], [0.78, 0.43, zf + 0.016], tf);
  });
}

function buildRearDetails() {
  const zr = -LENGTH_HALF;
  bothSides((tf) => {
    // Tail lamp on the tail face plus a wrap along the rear quarter.
    box(M.tail, [0.26, 0.78, zr - 0.014], [0.58, 0.88, zr + 0.03], tf);
    patch(M.tail, zr + 0.01, zr + 0.26, 0.79, 0.895, 0.012, tf, 10, 3);
    box(M.indicator, [0.28, 0.78, zr - 0.02], [0.42, 0.81, zr - 0.012], tf);
    // Rear reflector.
    box(M.dark, [0.60, 0.34, zr - 0.006], [0.72, 0.38, zr + 0.01], tf);
    // Dark lower bumper following the rear quarter.
    patch(M.dark, zr + 0.01, zr + 0.22, 0.215, 0.30, 0.008, tf, 6, 2);
  });
  // Connecting black garnish and light bar between the tail lamps.
  box(M.dark, [-0.26, 0.80, zr - 0.012], [0.26, 0.87, zr + 0.01]);
  box(M.tail, [-0.26, 0.835, zr - 0.016], [0.26, 0.85, zr - 0.01]);
  // Badge and plate recess.
  box(M.chrome, [-0.07, 0.885, zr - 0.02], [0.07, 0.915, zr + 0.01]);
  box(M.dark, [-0.24, 0.56, zr - 0.012], [0.24, 0.76, zr + 0.01]);
  box(M.plate, [-0.19, 0.60, zr - 0.02], [0.19, 0.73, zr - 0.01]);
  // Diffuser.
  box(M.dark, [-0.50, 0.30, zr - 0.012], [0.50, 0.42, zr + 0.01]);
}

function buildSideDetails() {
  bothSides((tf) => {
    // Wing mirror: dark base, body-coloured cap, mirror glass on the rear face.
    box(M.dark, [0.82, 0.87, 0.56], [0.90, 0.92, 0.66], tf);
    box(M.body, [0.88, 0.89, 0.50], [1.04, 1.00, 0.70], tf);
    box(M.dark, [0.885, 0.895, 0.495], [1.035, 0.995, 0.505], tf);
    // Door handles sit just proud of the door skin.
    const frontX = surfacePoint(0.08, 0.795).p[0];
    const rearX = surfacePoint(-0.78, 0.825).p[0];
    box(M.chrome, [frontX - 0.01, 0.78, 0.00], [frontX + 0.012, 0.81, 0.16], tf);
    box(M.chrome, [rearX - 0.01, 0.81, -0.86], [rearX + 0.012, 0.84, -0.70], tf);
    // Side sill garnish.
    patch(M.dark, 0.95, -0.95, 0.215, 0.27, 0.01, tf, 12, 2);
    // Chrome window belt trim following the shoulder line.
    patch(M.chrome, 0.58, -0.94, (z) => interp(BELT, z) + 0.04, (z) => interp(BELT, z) + 0.056, 0.008, tf, 12, 1);
  });
  // Shark-fin antenna, seated on the rear of the roof.
  const finZ = -0.86;
  const roofY = interp(TOP, finZ);
  box(M.body, [-0.02, roofY - 0.03, finZ - 0.08], [0.02, roofY + 0.055, finZ + 0.08]);
}

function buildWheel(zc, tf) {
  const cx0 = 0.655, cx1 = 0.865;
  const cy = WHEEL_Y;
  cylinderX(M.rubber, cy, zc, cx0, cx1, WHEEL_RADIUS, 0.215, 40, tf);
  // Alloy: dark dish, lip ring, five twin spokes and centre cap.
  cylinderX(M.dark, cy, zc, cx1 - 0.03, cx1 - 0.025, 0.215, 0, 40, tf);
  cylinderX(M.alloy, cy, zc, cx1 - 0.03, cx1 + 0.005, 0.215, 0.19, 40, tf);
  cylinderX(M.alloy, cy, zc, cx1 - 0.02, cx1 + 0.012, 0.05, 0, 24, tf);
  cylinderX(M.chrome, cy, zc, cx1 + 0.012, cx1 + 0.016, 0.028, 0, 24, tf);
  for (let s = 0; s < 5; s++) {
    const angle = (s / 5) * Math.PI * 2;
    const rot = (p) => {
      const y = p[1] - cy, z = p[2] - zc;
      return tf([
        p[0],
        cy + y * Math.cos(angle) - z * Math.sin(angle),
        zc + y * Math.sin(angle) + z * Math.cos(angle)
      ]);
    };
    box(M.alloy, [cx1 - 0.028, cy + 0.04, zc - 0.03], [cx1 + 0.004, cy + 0.20, zc + 0.03], rot);
  }
  // Brake disc visible behind spokes.
  cylinderX(M.chrome, cy, zc, cx1 - 0.06, cx1 - 0.04, 0.15, 0.06, 32, tf);
}

function buildWheels() {
  for (const zc of [WHEELBASE_HALF, -WHEELBASE_HALF]) {
    buildWheel(zc, identity);
    buildWheel(zc, mirrorX);
  }
}

buildBody();
buildFrontDetails();
buildRearDetails();
buildSideDetails();
buildWheels();

// ------------------------------------------------------------- glTF packing
const bufferViews = [];
const accessors = [];
const blobs = [];
let byteOffset = 0;

function pushBlob(bytes, target) {
  const padded = (bytes.length + 3) & ~3;
  const out = new Uint8Array(padded);
  out.set(bytes);
  blobs.push(out);
  bufferViews.push({ buffer: 0, byteOffset, byteLength: bytes.length, target });
  byteOffset += padded;
  return bufferViews.length - 1;
}

const gltfMeshes = [];
const nodes = [{ name: "cerato", children: [] }];
let totalTriangles = 0;

for (const [material, m] of meshes) {
  if (!m.indices.length) continue;
  const positions = new Float32Array(m.positions);
  const normals = new Float32Array(m.normals);
  const useU32 = m.positions.length / 3 > 65535;
  const indices = useU32 ? new Uint32Array(m.indices) : new Uint16Array(m.indices);

  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < positions.length; i += 3) {
    for (let k = 0; k < 3; k++) {
      min[k] = Math.min(min[k], positions[i + k]);
      max[k] = Math.max(max[k], positions[i + k]);
    }
  }

  const posView = pushBlob(new Uint8Array(positions.buffer), 34962);
  const nrmView = pushBlob(new Uint8Array(normals.buffer), 34962);
  const idxView = pushBlob(new Uint8Array(indices.buffer), 34963);
  const posAcc = accessors.push({ bufferView: posView, componentType: 5126, count: positions.length / 3, type: "VEC3", min, max }) - 1;
  const nrmAcc = accessors.push({ bufferView: nrmView, componentType: 5126, count: normals.length / 3, type: "VEC3" }) - 1;
  const idxAcc = accessors.push({ bufferView: idxView, componentType: useU32 ? 5125 : 5123, count: indices.length, type: "SCALAR" }) - 1;

  const meshIndex = gltfMeshes.push({
    name: materials[material].name,
    primitives: [{ attributes: { POSITION: posAcc, NORMAL: nrmAcc }, indices: idxAcc, material }]
  }) - 1;
  const nodeIndex = nodes.push({ mesh: meshIndex, name: materials[material].name }) - 1;
  nodes[0].children.push(nodeIndex);
  totalTriangles += indices.length / 3;
}

const bin = Buffer.concat(blobs.map((b) => Buffer.from(b.buffer, b.byteOffset, b.byteLength)));

const jsonText = JSON.stringify({
  asset: { version: "2.0", generator: "AABrowser Cerato stand-in v3" },
  scene: 0,
  scenes: [{ nodes: [0] }],
  nodes,
  meshes: gltfMeshes,
  materials,
  accessors,
  bufferViews,
  buffers: [{ byteLength: bin.length }]
});
const jsonPadded = jsonText + " ".repeat((4 - (jsonText.length % 4)) % 4);
const jsonBuf = Buffer.from(jsonPadded);

const header = Buffer.alloc(12);
header.write("glTF", 0);
header.writeUInt32LE(2, 4);
header.writeUInt32LE(12 + 8 + jsonBuf.length + 8 + bin.length, 8);
const jsonChunkHeader = Buffer.alloc(8);
jsonChunkHeader.writeUInt32LE(jsonBuf.length, 0);
jsonChunkHeader.writeUInt32LE(0x4E4F534A, 4);
const binChunkHeader = Buffer.alloc(8);
binChunkHeader.writeUInt32LE(bin.length, 0);
binChunkHeader.writeUInt32LE(0x004E4942, 4);

const glb = Buffer.concat([header, jsonChunkHeader, jsonBuf, binChunkHeader, bin]);
const out = join(dirname(fileURLToPath(import.meta.url)), "..", "pages", "cerato.glb");
writeFileSync(out, glb);
console.log(`Wrote ${out} (${glb.length} bytes, ${totalTriangles} triangles, ${gltfMeshes.length} materials)`);
