/* ===========================================================================
 * Lightweight canvas line plot (no external charting library).
 * Supports multiple series, auto / fixed axis ranges, grid, and labels.
 * Hover (mouse) or tap (touch) shows the nearest data point.
 * Used by the Sweep (f vs I), Live and B-Field (t vs ratio) tabs.
 * =========================================================================== */

const UC2_BLUE = "#023773";
const UC2_GREEN = "#85b918";

export class LinePlot {
  constructor(canvas, opts = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.opts = Object.assign(
      {
        xLabel: "x",
        yLabel: "y",
        xRange: null, // [min,max] or null=auto
        yRange: null, // [min,max] or null=auto
        yPad: 0.05, // fraction padding when auto
        zeroLine: false,
        emptyText: "No data",
        xTickFmt: (v) => v.toFixed(0),
        yTickFmt: (v) => v.toFixed(0),
        pointFmt: (p) => `${p.x.toFixed(2)} / ${p.y.toFixed(2)}`, // nearest-point readout
      },
      opts
    );
    this.series = []; // [{points:[{x,y}], color, marker}]
    this._cursor = null; // {x, y} in CSS px while hovering / after a tap
    const pick = (e) => {
      const r = this.canvas.getBoundingClientRect();
      this._cursor = { x: e.clientX - r.left, y: e.clientY - r.top };
      this.draw();
    };
    canvas.addEventListener("pointermove", pick);
    canvas.addEventListener("pointerdown", pick);
    // Touch keeps the readout after the finger lifts; a mouse clears it on leave.
    canvas.addEventListener("pointerleave", (e) => {
      if (e.pointerType !== "mouse") return;
      this._cursor = null;
      this.draw();
    });
    this._resize();
    window.addEventListener("resize", () => this.draw());
    // A plot on a tab that was hidden at load has width 0: redraw when its tab shows.
    document.addEventListener("shown.bs.tab", () => this.draw());
  }

  _resize() {
    const parent = this.canvas.parentElement;
    const w = parent ? parent.clientWidth : 600;
    const cssH = this.canvas.dataset.height ? parseInt(this.canvas.dataset.height, 10) : 300;
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = w * dpr;
    this.canvas.height = cssH * dpr;
    this.canvas.style.width = w + "px";
    this.canvas.style.height = cssH + "px";
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this._w = w;
    this._h = cssH;
  }

  setSeries(series) {
    this.series = series;
    this.draw();
  }

  draw() {
    const parent = this.canvas.parentElement;
    if (parent && parent.clientWidth && parent.clientWidth !== this._w) this._resize();
    const ctx = this.ctx;
    const W = this._w;
    const H = this._h;
    const pad = { top: 16, right: 18, bottom: 38, left: 64 };
    const pw = W - pad.left - pad.right;
    const ph = H - pad.top - pad.bottom;

    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, W, H);

    const allPts = this.series.flatMap((s) => s.points);

    // ranges
    let [xMin, xMax] = this.opts.xRange || autoRange(allPts.map((p) => p.x), 0);
    let [yMin, yMax] = this.opts.yRange || autoRange(allPts.map((p) => p.y), this.opts.yPad);
    if (xMin === xMax) { xMin -= 1; xMax += 1; }
    if (yMin === yMax) { yMin -= 1; yMax += 1; }

    const X = (x) => pad.left + ((x - xMin) / (xMax - xMin)) * pw;
    const Y = (y) => pad.top + (1 - (y - yMin) / (yMax - yMin)) * ph;

    // grid
    ctx.strokeStyle = "#eee";
    ctx.lineWidth = 1;
    for (let i = 0; i <= 5; i++) {
      const y = pad.top + (ph * i) / 5;
      ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(W - pad.right, y); ctx.stroke();
    }
    for (let i = 0; i <= 6; i++) {
      const x = pad.left + (pw * i) / 6;
      ctx.beginPath(); ctx.moveTo(x, pad.top); ctx.lineTo(x, H - pad.bottom); ctx.stroke();
    }

    // zero line
    if (this.opts.zeroLine && yMin < 0 && yMax > 0) {
      ctx.strokeStyle = "#bbb";
      ctx.setLineDash([4, 4]);
      ctx.beginPath(); ctx.moveTo(pad.left, Y(0)); ctx.lineTo(W - pad.right, Y(0)); ctx.stroke();
      ctx.setLineDash([]);
    }

    // axes
    ctx.strokeStyle = UC2_BLUE;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(pad.left, pad.top);
    ctx.lineTo(pad.left, H - pad.bottom);
    ctx.lineTo(W - pad.right, H - pad.bottom);
    ctx.stroke();

    // tick labels
    ctx.fillStyle = "#555";
    ctx.font = "11px sans-serif";
    ctx.textAlign = "center";
    for (let i = 0; i <= 6; i++) {
      const x = pad.left + (pw * i) / 6;
      const v = xMin + ((xMax - xMin) * i) / 6;
      ctx.fillText(this.opts.xTickFmt(v), x, H - pad.bottom + 16);
    }
    ctx.textAlign = "right";
    for (let i = 0; i <= 5; i++) {
      const y = pad.top + (ph * i) / 5;
      const v = yMax - ((yMax - yMin) * i) / 5;
      ctx.fillText(this.opts.yTickFmt(v), pad.left - 6, y + 4);
    }

    // axis titles
    ctx.fillStyle = UC2_BLUE;
    ctx.font = "12px sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(this.opts.xLabel, pad.left + pw / 2, H - 6);
    ctx.save();
    ctx.translate(14, pad.top + ph / 2);
    ctx.rotate(-Math.PI / 2);
    ctx.fillText(this.opts.yLabel, 0, 0);
    ctx.restore();

    // empty state
    if (allPts.length === 0) {
      ctx.fillStyle = "#999";
      ctx.font = "14px sans-serif";
      ctx.textAlign = "center";
      ctx.fillText(this.opts.emptyText, W / 2, H / 2);
      return;
    }

    // series
    ctx.save();
    ctx.beginPath();
    ctx.rect(pad.left, pad.top, pw, ph);
    ctx.clip();
    for (const s of this.series) {
      if (s.points.length === 0) continue;
      ctx.strokeStyle = s.color || UC2_BLUE;
      ctx.lineWidth = s.width || 2;
      ctx.beginPath();
      s.points.forEach((p, i) => {
        const px = X(p.x), py = Y(p.y);
        i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
      });
      ctx.stroke();

      if (s.dots) {
        ctx.fillStyle = s.color || UC2_BLUE;
        for (const p of s.points) {
          ctx.beginPath(); ctx.arc(X(p.x), Y(p.y), 2.5, 0, 2 * Math.PI); ctx.fill();
        }
      }
      if (s.marker !== false) {
        const last = s.points[s.points.length - 1];
        ctx.fillStyle = s.markerColor || UC2_GREEN;
        ctx.beginPath(); ctx.arc(X(last.x), Y(last.y), 4, 0, 2 * Math.PI); ctx.fill();
      }
    }
    ctx.restore();
    this._drawReadout(X, Y, W, H, pad);
  }

  // Crosshair + label at the data point nearest to the cursor (pixel distance).
  _drawReadout(X, Y, W, H, pad) {
    const c = this._cursor;
    if (!c) return;
    let best = null, bestD = Infinity;
    for (const s of this.series) {
      for (const p of s.points) {
        const d = (X(p.x) - c.x) ** 2 + (Y(p.y) - c.y) ** 2;
        if (d < bestD) { bestD = d; best = p; }
      }
    }
    if (!best) return;
    const ctx = this.ctx, px = X(best.x), py = Y(best.y);
    if (px < pad.left || px > W - pad.right || py < pad.top || py > H - pad.bottom) return;
    ctx.strokeStyle = "rgba(2,55,115,0.35)";
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.beginPath(); ctx.moveTo(px, pad.top); ctx.lineTo(px, H - pad.bottom); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(pad.left, py); ctx.lineTo(W - pad.right, py); ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = UC2_GREEN;
    ctx.beginPath(); ctx.arc(px, py, 5, 0, 2 * Math.PI); ctx.fill();
    const label = this.opts.pointFmt(best);
    ctx.font = "12px sans-serif";
    const tw = ctx.measureText(label).width + 12;
    const bx = Math.min(Math.max(px + 8, pad.left), W - pad.right - tw);
    const by = py - 26 < pad.top ? py + 8 : py - 26;
    ctx.fillStyle = "rgba(255,255,255,0.92)";
    ctx.strokeStyle = UC2_BLUE;
    ctx.fillRect(bx, by, tw, 20);
    ctx.strokeRect(bx, by, tw, 20);
    ctx.fillStyle = UC2_BLUE;
    ctx.textAlign = "left";
    ctx.fillText(label, bx + 6, by + 14);
  }
}

function autoRange(values, padFrac) {
  if (values.length === 0) return [0, 1];
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  // Only fall back to a default pad when the data has zero span; otherwise a
  // padFrac of 0 must give exactly 0 padding (tight axis).
  const pad = hi === lo ? Math.abs(hi) * 0.1 || 1 : (hi - lo) * padFrac;
  return [lo - pad, hi + pad];
}
