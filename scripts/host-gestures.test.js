const { planHostGesture, stepTouchGesture } = require("../pages/host-gestures.js");

function assert(cond, msg) {
  if (!cond) {
    console.error("FAIL", msg);
    process.exitCode = 1;
  }
}

function hostScale(state, focusX, focusY, factor) {
  return planHostGesture(state, { type: "scale", focusX: focusX, focusY: focusY, factor: factor });
}

// Two fingers slide sideways: rotate, and a 2% span change is not a zoom.
let s = hostScale(null, 400, 300, 1).state;
let step = hostScale(s, 460, 302, 1.02);
assert(step.actions.some(a => a.type === "nudge" && a.heading > 5), "sideways two-finger slide rotates");
assert(!step.actions.some(a => a.type === "zoom"), "tiny span change during a rotate is not a zoom");

// Two fingers slide down: tilt.
s = hostScale(null, 400, 200, 1).state;
step = hostScale(s, 401, 250, 1.01);
assert(step.actions.some(a => a.type === "nudge" && a.tilt > 5), "two-finger slide down tilts");
assert(!step.actions.some(a => a.type === "zoom"), "two-finger slide down does not zoom");

// A clear pinch zooms, including while the fingers also move a little.
s = hostScale(null, 400, 300, 1).state;
step = hostScale(s, 410, 308, 1.12);
assert(step.actions.some(a => a.type === "zoom" && a.factor === 1.12), "pinch zooms in");

// After the pinch, one finger is a pan and does not tilt.
const after = planHostGesture(step.state, { type: "scroll", dx: 4, dy: 30 });
assert(after.actions.length === 1 && after.actions[0].type === "pan", "scroll after pinch stays a pan");
assert(!after.actions.some(a => a.type === "nudge"), "scroll after pinch does not lower the camera");

// One finger noise must not zoom.
s = hostScale(null, 200, 200, 1.01).state;
step = hostScale(s, 206, 204, 1.02);
assert(!step.actions.some(a => a.type === "zoom"), "one-finger scale noise is not a zoom");

// A finger-lift jump does not spin the map.
s = hostScale(null, 400, 300, 1).state;
step = hostScale(s, 10, 10, 1);
assert(!step.actions.some(a => a.type === "nudge"), "focus jump is ignored");

function pair(dist, angleDeg, midX, midY) {
  return { dist: dist, angle: angleDeg * Math.PI / 180, midX: midX, midY: midY };
}

// Twist, with a little span noise, rotates once the turn is clear.
let g = stepTouchGesture(null, pair(200, 0, 400, 300));
g = stepTouchGesture(g, pair(204, 4, 401, 301));
g = stepTouchGesture(g, pair(206, 9, 402, 300));
assert(g.mode === "rotate", "twist is classified as rotate, got " + g.mode);
assert(g.heading !== 0, "twist changes heading");

// Up/down, with a little span noise, tilts.
g = stepTouchGesture(null, pair(180, 10, 400, 300));
g = stepTouchGesture(g, pair(184, 11, 400, 320));
g = stepTouchGesture(g, pair(186, 12, 401, 342));
assert(g.mode === "tilt", "two-finger vertical is tilt, got " + g.mode);
assert(g.tilt !== 0, "two-finger vertical changes tilt");

// Pinch stays a zoom when the fingers also wobble.
g = stepTouchGesture(null, pair(160, 0, 400, 300));
g = stepTouchGesture(g, pair(190, 2, 404, 306));
g = stepTouchGesture(g, pair(230, 3, 406, 308));
assert(g.mode === "zoom", "pinch stays zoom, got " + g.mode);
assert(g.zoom > 1, "pinch increases the zoom factor");

if (!process.exitCode) console.log("host gestures ok");
