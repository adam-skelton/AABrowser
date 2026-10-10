// Gesture decisions shared by the car host and the page.
// The car only reports a pinch scale and the point between the fingers.
// It does not report a twist angle, so a sideways two-finger slide rotates
// and an up-down slide tilts. One-finger movement is never either of those.
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.planHostGesture = api.planHostGesture;
  root.stepTouchGesture = api.stepTouchGesture;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  const ZOOM_IN = 1.06;
  const ZOOM_OUT = 1 / ZOOM_IN;
  const FOCUS_JUMP_PX = 96;

  function emptyHost() {
    return { lastFocusX: null, lastFocusY: null };
  }

  // Car host events. Scroll is always a pan. Scale zooms only when the pinch
  // is obvious, and focus movement tilts or rotates on its own.
  function planHostGesture(state, ev) {
    const prev = state || emptyHost();
    if (!ev || ev.type === "scroll") {
      return { state: prev, actions: [{ type: "pan", dx: Number(ev && ev.dx) || 0, dy: Number(ev && ev.dy) || 0 }] };
    }
    if (ev.type === "end") return { state: emptyHost(), actions: [] };
    if (ev.type !== "scale") return { state: prev, actions: [] };

    const actions = [];
    const factor = Number(ev.factor);
    if (Number.isFinite(factor) && (factor >= ZOOM_IN || (factor > 0 && factor <= ZOOM_OUT))) {
      actions.push({ type: "zoom", factor: factor });
    }
    const fx = Number(ev.focusX);
    const fy = Number(ev.focusY);
    const focusOk = Number.isFinite(fx) && Number.isFinite(fy) && fx >= 0 && fy >= 0;
    let next = prev;
    if (focusOk && prev.lastFocusX != null && prev.lastFocusY != null) {
      const dx = fx - prev.lastFocusX;
      const dy = fy - prev.lastFocusY;
      const jump = Math.abs(dx) > FOCUS_JUMP_PX || Math.abs(dy) > FOCUS_JUMP_PX;
      if (!jump && (Math.abs(dx) > 1.5 || Math.abs(dy) > 1.5)) {
        actions.push({ type: "nudge", heading: dx * 0.16, tilt: dy * 0.18 });
      }
    }
    if (focusOk) next = { lastFocusX: fx, lastFocusY: fy };
    return { state: next, actions: actions };
  }

  function wrapDeg(d) {
    let a = d;
    if (a > 180) a -= 360;
    if (a < -180) a += 360;
    return a;
  }

  // Two real touches. Each frame keeps only the gesture that is actually
  // happening, so a twist is not swallowed by a tiny change in finger span.
  function stepTouchGesture(state, next) {
    const base = {
      dist: next.dist,
      angle: next.angle,
      midX: next.midX,
      midY: next.midY,
      mode: "undecided",
      accTwist: 0,
      accZoom: 0,
      accTilt: 0,
      zoom: 1,
      heading: 0,
      tilt: 0
    };
    if (!state) return base;
    let dAngle = wrapDeg((next.angle - state.angle) * 180 / Math.PI);
    const dDist = (next.dist || 1) / (state.dist || 1);
    const dMidY = next.midY - state.midY;
    const spanOk = dDist > 0.72 && dDist < 1.4;
    const angleOk = Math.abs(dAngle) <= 28;
    if (!spanOk || !angleOk) {
      return Object.assign({}, base, { mode: state.mode || "undecided" });
    }
    const accTwist = (state.accTwist || 0) + dAngle;
    const accZoom = (state.accZoom || 0) + Math.log(dDist || 1);
    const accTilt = (state.accTilt || 0) + dMidY;
    let mode = state.mode || "undecided";
    if (mode === "undecided") {
      const twist = Math.abs(accTwist);
      const zoom = Math.abs(accZoom);
      const tilt = Math.abs(accTilt);
      if (twist > 6 && twist > zoom * 120 && twist > tilt * 0.35) mode = "rotate";
      else if (zoom > 0.045 && zoom * 120 > twist && zoom * 380 > tilt) mode = "zoom";
      else if (tilt > 14 && tilt > twist * 2 && tilt > zoom * 420) mode = "tilt";
    }
    const out = Object.assign({}, base, { mode: mode, accTwist: accTwist, accZoom: accZoom, accTilt: accTilt });
    if (mode === "rotate" && Math.abs(dAngle) > 0.6) out.heading = dAngle;
    else if (mode === "zoom" && Math.abs(dDist - 1) > 0.008) out.zoom = dDist;
    else if (mode === "tilt" && Math.abs(dMidY) > 1.5) out.tilt = -dMidY * 0.18;
    return out;
  }

  return { planHostGesture: planHostGesture, stepTouchGesture: stepTouchGesture };
});
