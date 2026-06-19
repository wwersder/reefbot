/**
 * Reef Plinko — Canvas board & ball animation v2.
 *
 * Physics-based ball drop:
 *   - Quadratic Bezier arcs between pegs (horizontal sweep then fall)
 *   - Ease-in per segment (gravity acceleration)
 *   - Glowing trail behind ball
 *   - Peg flash on hit
 *   - Particle burst on slot landing
 *   - Winning slot pulse animation
 */

const STEP_MS_NORMAL = 210;
const STEP_MS_FAST   = 62;
const BALL_R         = 8;
const PEG_R          = 6;
const SLOT_H         = 42;
const TRAIL_MAX      = 16;

// Ease-in: simulates gravitational acceleration per segment
const easeIn = t => t * t;

// Slot visual palette by multiplier
function slotPalette(mult) {
    if (mult >= 15) return { top: '#fcd34d', bot: '#b45309', glow: '#fbbf24' }; // jackpot gold
    if (mult >= 5)  return { top: '#6ee7b7', bot: '#047857', glow: '#34d399' }; // emerald
    if (mult >= 2)  return { top: '#67e8f9', bot: '#0e7490', glow: '#22d3ee' }; // cyan
    if (mult >= 0.8)return { top: '#94a3b8', bot: '#334155', glow: '#64748b' }; // slate
    return             { top: '#fca5a5', bot: '#991b1b', glow: '#ef4444' };      // red loss
}

// Multiplier tables — MUST match PlinkoService.java
const MULT = {
    8: {
        LOW:    [1.5, 1.2, 1.1, 1.0, 0.5, 1.0, 1.1, 1.2, 1.5],
        MEDIUM: [6.0, 2.5, 1.3, 0.8, 0.4, 0.8, 1.3, 2.5, 6.0],
        HIGH:   [18.0, 4.0, 1.3, 0.5, 0.1, 0.5, 1.3, 4.0, 18.0]
    },
    12: {
        LOW:    [20.0, 7.0, 2.5, 1.5, 1.0, 0.7, 0.5, 0.7, 1.0, 1.5, 2.5, 7.0, 20.0],
        MEDIUM: [20.0, 7.0, 2.5, 1.5, 1.0, 0.7, 0.5, 0.7, 1.0, 1.5, 2.5, 7.0, 20.0],
        HIGH:   [20.0, 7.0, 2.5, 1.5, 1.0, 0.7, 0.5, 0.7, 1.0, 1.5, 2.5, 7.0, 20.0]
    }
};

export class PlinkoBoard {
    /**
     * @param {HTMLCanvasElement} canvas
     * @param {Function} onAnimDone(multiplier, profit) — called when ball lands
     */
    constructor(canvas, onAnimDone) {
        this.canvas     = canvas;
        this.ctx        = canvas.getContext('2d');
        this.onAnimDone = onAnimDone;

        this.rows = 8;
        this.risk = 'MEDIUM';
        this.fast = false;

        this._raf       = null;
        this._ball      = null;
        this._flashes   = new Map(); // `${row},${col}` → brightness [0..1]
        this._trail     = [];        // [{x, y}] newest first
        this._particles = [];
        this._landing   = null;      // {idx, pal, age}
        this._lastTs    = null;

        this.resize();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    setRows(r) { this.rows = r; this._staticRedraw(); }
    setRisk(r) { this.risk = r; this._staticRedraw(); }
    setFast(f) { this.fast = f; }

    resize() {
        const p = this.canvas.parentElement;
        this.canvas.width  = p.clientWidth;
        this.canvas.height = p.clientHeight;
        this._layout();
        this._staticRedraw();
    }

    /**
     * Animate a ball drop.
     * @param {boolean[]} path       server-provided path (false=left, true=right)
     * @param {number}    slot       destination slot index
     * @param {number}    multiplier result multiplier
     * @param {number}    profit     signed shell delta
     */
    dropBall(path, slot, multiplier, profit) {
        if (this._raf) cancelAnimationFrame(this._raf);
        this._flashes.clear();
        this._trail     = [];
        this._particles = [];
        this._landing   = null;
        this._lastTs    = null;

        this._ball = {
            segs: this._buildSegs(path, slot),
            segIdx: 0, t: 0,
            multiplier, profit,
            done: false
        };

        this._raf = requestAnimationFrame(ts => this._tick(ts));
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    _layout() {
        const W = this.canvas.width;
        const H = this.canvas.height;
        const topPad = 20;
        const botPad = SLOT_H + 12;
        this.rowSpacing = (H - topPad - botPad) / (this.rows + 1);
        this.colSpacing = Math.min((W - 28) / (this.rows + 1), 42);
        this.ox = W / 2;
        this.oy = topPad + this.rowSpacing;
    }

    _pegPos(row, col) {
        const n = row + 2;
        return {
            x: this.ox - (n - 1) * this.colSpacing / 2 + col * this.colSpacing,
            y: this.oy + row * this.rowSpacing
        };
    }

    _slotX(i) {
        return this.ox - this.rows * this.colSpacing / 2 + i * this.colSpacing;
    }

    // ── Path building ─────────────────────────────────────────────────────────

    _buildSegs(path, slotIdx) {
        const segs = [];
        let col  = 0;
        // Entry: appear directly above first peg
        const p0 = this._pegPos(0, 0);
        let x0   = p0.x;
        let y0   = p0.y - this.rowSpacing * 1.6;

        for (let row = 0; row < this.rows; row++) {
            const peg = this._pegPos(row, col);
            segs.push({
                x0, y0,
                x1: peg.x, y1: peg.y,
                // Control point: jump to target X first, then fall (plinko arc)
                cx: peg.x, cy: y0,
                pegRow: row, pegCol: col
            });
            x0 = peg.x;
            y0 = peg.y;
            if (path[row]) col++;
        }

        // Final arc from last peg down to slot
        const sx = this._slotX(slotIdx);
        const sy = this.canvas.height - SLOT_H / 2 - 2;
        segs.push({
            x0, y0,
            x1: sx, y1: sy,
            cx: sx, cy: y0,
            isSlot: true, slotIdx
        });

        return segs;
    }

    // Quadratic Bézier position at parameter t
    _bez(seg, t) {
        const u = 1 - t;
        return {
            x: u*u*seg.x0 + 2*u*t*seg.cx + t*t*seg.x1,
            y: u*u*seg.y0 + 2*u*t*seg.cy + t*t*seg.y1
        };
    }

    // ── Animation loop ────────────────────────────────────────────────────────

    _tick(ts) {
        if (!this._lastTs) this._lastTs = ts;
        const dt = Math.min(ts - this._lastTs, 50);
        this._lastTs = ts;

        const b = this._ball;
        let bx = null, by = null;

        if (!b.done && b.segIdx < b.segs.length) {
            const seg    = b.segs[b.segIdx];
            const stepMs = (this.fast ? STEP_MS_FAST : STEP_MS_NORMAL)
                         * (seg.isSlot ? 1.4 : 1);

            b.t += dt / stepMs;
            const te  = easeIn(Math.min(b.t, 1));
            const pos = this._bez(seg, te);
            bx = pos.x; by = pos.y;

            // Accumulate trail
            this._trail.unshift({ x: bx, y: by });
            if (this._trail.length > TRAIL_MAX) this._trail.pop();

            if (b.t >= 1) {
                b.t = 0;
                if (!seg.isSlot) {
                    // Flash the hit peg
                    this._flashes.set(`${seg.pegRow},${seg.pegCol}`, 1.0);
                    b.segIdx++;
                } else {
                    // Ball landed
                    const pal = slotPalette(MULT[this.rows][this.risk][seg.slotIdx]);
                    this._landing = { idx: seg.slotIdx, pal, age: 0 };
                    this._spawnParticles(bx, by, pal);
                    b.done = true;
                    b.segIdx++;
                    this.onAnimDone(b.multiplier, b.profit);
                }
            }
        }

        // Decay peg flashes
        for (const [k, v] of this._flashes) {
            const nv = v - dt / 300;
            nv <= 0 ? this._flashes.delete(k) : this._flashes.set(k, nv);
        }

        this._updateParticles(dt);
        if (this._landing) this._landing.age += dt;

        this._frame(bx, by);

        if (!b.done || this._particles.length > 0 || this._flashes.size > 0) {
            this._raf = requestAnimationFrame(ts => this._tick(ts));
        } else {
            this._ball = null;
        }
    }

    // ── Particles ─────────────────────────────────────────────────────────────

    _spawnParticles(x, y, pal) {
        const count = 22;
        for (let i = 0; i < count; i++) {
            const a  = (Math.PI * 2 * i / count) - Math.PI / 2;
            const sp = 1.5 + Math.random() * 2.8;
            this._particles.push({
                x, y,
                vx: Math.cos(a) * sp,
                vy: Math.sin(a) * sp - 2,
                color: pal.glow,
                life: 650 + Math.random() * 400,
                age: 0
            });
        }
        // Extra sparkles: small fast ones
        for (let i = 0; i < 8; i++) {
            const a  = Math.random() * Math.PI * 2;
            this._particles.push({
                x: x + (Math.random() - 0.5) * 12,
                y: y + (Math.random() - 0.5) * 8,
                vx: Math.cos(a) * (3 + Math.random() * 2),
                vy: Math.sin(a) * (3 + Math.random() * 2) - 3,
                color: '#ffffff',
                life: 300 + Math.random() * 200,
                age: 0
            });
        }
    }

    _updateParticles(dt) {
        for (let i = this._particles.length - 1; i >= 0; i--) {
            const p = this._particles[i];
            p.age += dt;
            p.vy  += 0.07;   // gravity
            p.vx  *= 0.97;   // air drag
            p.x   += p.vx;
            p.y   += p.vy;
            if (p.age >= p.life) this._particles.splice(i, 1);
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    _staticRedraw() {
        this._layout();
        this._frame(null, null);
    }

    _frame(bx, by) {
        const { ctx, canvas } = this;
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        this._drawPegs();
        this._drawSlots();
        this._drawParticles();
        if (bx !== null) {
            this._drawTrail();
            this._drawBall(bx, by);
        }
    }

    _drawPegs() {
        const { ctx } = this;
        for (let row = 0; row < this.rows; row++) {
            for (let col = 0; col < row + 2; col++) {
                const { x, y } = this._pegPos(row, col);
                const flash    = this._flashes.get(`${row},${col}`) || 0;

                // Outer halo
                const haloR = PEG_R + (flash > 0 ? 10 : 4);
                const haloA = flash > 0 ? flash * 0.55 : 0.07;
                ctx.beginPath();
                ctx.arc(x, y, haloR, 0, Math.PI * 2);
                ctx.fillStyle = flash > 0
                    ? `rgba(255,255,255,${haloA})`
                    : `rgba(34,211,238,${haloA})`;
                ctx.fill();

                // Peg body with 3D radial gradient
                const grad = ctx.createRadialGradient(x - 2, y - 2, 0.5, x, y, PEG_R);
                if (flash > 0) {
                    grad.addColorStop(0, `rgba(255,255,255,1)`);
                    grad.addColorStop(1, `rgba(140,230,255,${0.7 + flash * 0.3})`);
                } else {
                    grad.addColorStop(0, 'rgba(190,245,255,0.95)');
                    grad.addColorStop(1, 'rgba(14,165,200,0.9)');
                }
                ctx.shadowColor = flash > 0 ? 'rgba(255,255,255,0.95)' : 'rgba(34,211,238,0.55)';
                ctx.shadowBlur  = flash > 0 ? 22 : 6;
                ctx.beginPath();
                ctx.arc(x, y, PEG_R, 0, Math.PI * 2);
                ctx.fillStyle = grad;
                ctx.fill();
                ctx.shadowBlur = 0;
            }
        }
    }

    _drawSlots() {
        const { ctx, canvas } = this;
        const slots  = this.rows + 1;
        const mults  = MULT[this.rows][this.risk];
        const slotW  = this.colSpacing - 3;
        const slotY  = canvas.height - SLOT_H;

        for (let i = 0; i < slots; i++) {
            const cx  = this._slotX(i);
            const x   = cx - slotW / 2;
            const pal = slotPalette(mults[i]);
            const isLand = this._landing?.idx === i;

            if (isLand) {
                // Pulsing outer glow
                const pulse = 0.5 + 0.5 * Math.sin(this._landing.age / 110);
                ctx.beginPath();
                this._rrect(ctx, x - 5, slotY - 7, slotW + 10, SLOT_H + 7, 9);
                ctx.fillStyle = pal.glow + '40';
                ctx.shadowColor = pal.glow;
                ctx.shadowBlur  = 24 * pulse;
                ctx.fill();
                ctx.shadowBlur  = 0;
            }

            // Slot body: linear gradient (bright top → dark base)
            const grad = ctx.createLinearGradient(cx, slotY, cx, slotY + SLOT_H);
            grad.addColorStop(0, pal.top);
            grad.addColorStop(1, pal.bot);

            const alpha = isLand
                ? 0.78 + 0.22 * Math.sin(this._landing.age / 100)
                : 1;
            ctx.beginPath();
            this._rrect(ctx, x, slotY, slotW, SLOT_H - 4, 6);
            ctx.globalAlpha = alpha;
            ctx.fillStyle   = grad;
            ctx.fill();
            ctx.globalAlpha = 1;

            // Multiplier label
            const m   = mults[i];
            const lbl = m >= 10 ? `${m.toFixed(0)}x` : `${m.toFixed(1)}x`;
            const fs  = slotW > 34 ? 11 : 9;
            ctx.font          = `bold ${fs}px Inter, system-ui`;
            ctx.textAlign     = 'center';
            ctx.textBaseline  = 'middle';
            ctx.fillStyle     = '#050e1d';
            ctx.fillText(lbl, cx, slotY + (SLOT_H - 4) / 2);
        }
    }

    _drawTrail() {
        const { ctx } = this;
        for (let i = 0; i < this._trail.length; i++) {
            const { x, y } = this._trail[i];
            const frac = 1 - i / this._trail.length;
            ctx.beginPath();
            ctx.arc(x, y, BALL_R * frac * 0.55, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(180,210,255,${frac * 0.32})`;
            ctx.fill();
        }
    }

    _drawBall(x, y) {
        const { ctx } = this;

        // Diffuse outer glow ring
        ctx.beginPath();
        ctx.arc(x, y, BALL_R + 8, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,255,0.05)';
        ctx.fill();

        // Main ball — radial gradient (lit from top-left)
        ctx.shadowColor = 'rgba(210,230,255,0.65)';
        ctx.shadowBlur  = 18;
        const g = ctx.createRadialGradient(x - 2.5, y - 2.5, 0.5, x, y, BALL_R);
        g.addColorStop(0,   '#ffffff');
        g.addColorStop(0.35,'#ddeeff');
        g.addColorStop(0.75,'#a0b8cc');
        g.addColorStop(1,   '#5c7284');
        ctx.beginPath();
        ctx.arc(x, y, BALL_R, 0, Math.PI * 2);
        ctx.fillStyle = g;
        ctx.fill();
        ctx.shadowBlur = 0;

        // Sharp specular highlight
        ctx.beginPath();
        ctx.arc(x - 2.5, y - 2.5, BALL_R * 0.36, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,255,0.82)';
        ctx.fill();
    }

    _drawParticles() {
        const { ctx } = this;
        for (const p of this._particles) {
            const a  = Math.max(0, 1 - p.age / p.life);
            const r  = 3.5 * a;
            if (r < 0.3) continue;
            ctx.beginPath();
            ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
            const ah = Math.floor(a * 255).toString(16).padStart(2, '0');
            ctx.fillStyle = p.color + ah;
            ctx.fill();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    _rrect(ctx, x, y, w, h, r) {
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.lineTo(x + w - r, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + r);
        ctx.lineTo(x + w, y + h - r);
        ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
        ctx.lineTo(x + r, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - r);
        ctx.lineTo(x, y + r);
        ctx.quadraticCurveTo(x, y, x + r, y);
        ctx.closePath();
    }
}
