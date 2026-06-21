/**
 * Reef Plinko — Canvas board & ball animation v4 (premium render).
 *
 * Performance: static elements (background, peg, ball) are pre-rendered
 * to offscreen canvases and composited with drawImage each frame.
 * Only flashes, slots, trail and particles are drawn live.
 *
 * API:
 *   board.dropBall(path, slot, multiplier, profit, onDone?)
 *   board.setRows(n) / setRisk(r) / setFast(bool) / resize()
 */

const STEP_MS_NORMAL = 210;
const STEP_MS_FAST   = 62;
const BALL_R         = 6;
const PEG_R          = 4;
const SLOT_H         = 44;
const TRAIL_MAX      = 14;
const LANDING_TTL    = 2400;

const easeIn = t => t * t;

/** Decode a 6-char hex colour → [r, g, b] */
function hexRgb(hex) {
    return [
        parseInt(hex.slice(1, 3), 16),
        parseInt(hex.slice(3, 5), 16),
        parseInt(hex.slice(5, 7), 16),
    ];
}

/**
 * Light-theme slot palette — frosted glass cells, vivid top border, dark text.
 */
function slotPalette(mult) {
    if (mult >= 15) return { bg: 'rgba(255,251,235,0.80)', bgLand: 'rgba(254,243,199,0.90)', border: '#f59e0b', text: '#92400e', glow: '#f59e0b' };
    if (mult >= 5)  return { bg: 'rgba(240,253,244,0.80)', bgLand: 'rgba(220,252,231,0.90)', border: '#22c55e', text: '#14532d', glow: '#22c55e' };
    if (mult >= 2)  return { bg: 'rgba(239,246,255,0.80)', bgLand: 'rgba(219,234,254,0.90)', border: '#3b82f6', text: '#1e3a8a', glow: '#3b82f6' };
    if (mult >= 0.8)return { bg: 'rgba(249,250,251,0.75)', bgLand: 'rgba(243,244,246,0.90)', border: '#9ca3af', text: '#6b7280', glow: '#9ca3af' };
    return               { bg: 'rgba(255,241,242,0.80)', bgLand: 'rgba(255,228,230,0.90)', border: '#f87171', text: '#991b1b', glow: '#f87171' };
}

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
    constructor(canvas, defaultOnDone) {
        this.canvas         = canvas;
        this.ctx            = canvas.getContext('2d');
        this._defaultOnDone = defaultOnDone;

        this.rows = 8;
        this.risk = 'MEDIUM';
        this.fast = false;

        this._balls   = [];
        this._flashes = new Map();
        this._raf     = null;
        this._lastTs  = null;

        // Offscreen sprite caches
        this._bgSprite   = null;
        this._pegSprite  = null;
        this._ballSprite = null;

        this._onPegHit = null;   // optional haptic / sound callback

        this.resize();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    setRows(r)      { this.rows = r; this._staticRedraw(); }
    setRisk(r)      { this.risk = r; this._staticRedraw(); }
    setFast(f)      { this.fast = f; }
    setOnPegHit(fn) { this._onPegHit = fn; }

    resize() {
        const p   = this.canvas.parentElement;
        // Respect device pixel ratio so canvas pixels map 1:1 to physical pixels.
        // Without this, every circle and glyph looks blurry on retina screens.
        const dpr  = Math.min(window.devicePixelRatio || 1, 3);
        const cssW = p.clientWidth;
        const cssH = p.clientHeight;
        this._dpr  = dpr;
        this._cssW = cssW;
        this._cssH = cssH;
        this.canvas.width  = Math.round(cssW * dpr);
        this.canvas.height = Math.round(cssH * dpr);
        this.canvas.style.width  = cssW + 'px';
        this.canvas.style.height = cssH + 'px';
        // Rebuild all offscreen caches
        this._buildBgSprite();
        this._buildPegSprite();
        this._buildBallSprite();
        this._staticRedraw();
    }

    /**
     * Drop a ball. Multiple concurrent drops are fully supported.
     * @param {boolean[]} path       false=left, true=right per row
     * @param {number}    slot       destination slot index
     * @param {number}    multiplier result multiplier
     * @param {number}    profit     signed shell delta
     * @param {Function}  [onDone]   (multiplier, profit) → void
     */
    dropBall(path, slot, multiplier, profit, onDone) {
        const ball = {
            segs:       this._buildSegs(path, slot),
            segIdx:     0,
            t:          0,
            x:          0, y: 0,
            multiplier, profit,
            done:       false,
            trail:      [],
            particles:  [],
            landing:    null,
            onDone:     onDone || this._defaultOnDone,
            rows:       this.rows,
            risk:       this.risk,
        };
        this._balls.push(ball);

        if (!this._raf) {
            this._lastTs = null;
            this._raf = requestAnimationFrame(ts => this._tick(ts));
        }
    }

    // ── Offscreen sprite builders ─────────────────────────────────────────────

    /**
     * Background: white (matches the glass card surface).
     */
    _buildBgSprite() {
        const { _cssW: W, _cssH: H, _dpr: dpr } = this;
        const c   = document.createElement('canvas');
        c.width   = Math.round(W * dpr);
        c.height  = Math.round(H * dpr);
        const ctx = c.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, c.width, c.height);
        this._bgSprite = c;
    }

    /**
     * Pre-rendered peg — dark slate sphere, built at physical pixel resolution.
     * Rendered with ctx.scale(dpr) inside so all coordinates stay in CSS units.
     */
    _buildPegSprite() {
        const dpr    = this._dpr;
        const PAD    = 8;
        const cssOff = PEG_R + PAD;
        const cssS   = cssOff * 2;
        const c      = document.createElement('canvas');
        c.width      = c.height = Math.round(cssS * dpr);
        const ctx    = c.getContext('2d');
        ctx.scale(dpr, dpr);
        const cx = cssOff, cy = cssOff;

        // Soft drop shadow
        ctx.beginPath();
        ctx.arc(cx + 0.5, cy + 1.2, PEG_R * 0.90, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(0,0,0,0.14)';
        ctx.fill();

        // Dark slate body
        const g = ctx.createRadialGradient(
            cx - PEG_R * 0.38, cy - PEG_R * 0.38, 0,
            cx, cy, PEG_R
        );
        g.addColorStop(0,    '#6b7280');
        g.addColorStop(0.45, '#374151');
        g.addColorStop(1,    '#111827');
        ctx.beginPath();
        ctx.arc(cx, cy, PEG_R, 0, Math.PI * 2);
        ctx.fillStyle = g;
        ctx.fill();

        // White specular dot
        ctx.beginPath();
        ctx.arc(cx - PEG_R * 0.28, cy - PEG_R * 0.30, PEG_R * 0.24, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,255,0.70)';
        ctx.fill();

        this._pegSprite    = c;
        this._pegSpriteOff = cssOff;   // CSS offset for drawImage positioning
        this._pegSpriteCss = cssS;     // CSS draw size
    }

    /**
     * Pre-rendered ball — accent blue sphere, built at physical pixel resolution.
     */
    _buildBallSprite() {
        const dpr    = this._dpr;
        const PAD    = 12;
        const cssOff = BALL_R + PAD;
        const cssS   = cssOff * 2;
        const c      = document.createElement('canvas');
        c.width      = c.height = Math.round(cssS * dpr);
        const ctx    = c.getContext('2d');
        ctx.scale(dpr, dpr);
        const cx = cssOff, cy = cssOff;

        // Soft drop shadow
        ctx.beginPath();
        ctx.arc(cx + 0.6, cy + 1.8, BALL_R * 0.90, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(0,122,255,0.18)';
        ctx.fill();

        // Blue body — light from top-left
        const g = ctx.createRadialGradient(
            cx - BALL_R * 0.36, cy - BALL_R * 0.36, 0,
            cx, cy, BALL_R
        );
        g.addColorStop(0,    '#60a5fa');
        g.addColorStop(0.40, '#2563eb');
        g.addColorStop(1,    '#1e3a8a');
        ctx.beginPath();
        ctx.arc(cx, cy, BALL_R, 0, Math.PI * 2);
        ctx.fillStyle = g;
        ctx.fill();

        // Wide soft specular
        const h1 = ctx.createRadialGradient(
            cx - BALL_R * 0.28, cy - BALL_R * 0.30, 0,
            cx - BALL_R * 0.08, cy - BALL_R * 0.08, BALL_R * 0.50
        );
        h1.addColorStop(0,   'rgba(255,255,255,0.80)');
        h1.addColorStop(0.5, 'rgba(255,255,255,0.18)');
        h1.addColorStop(1,   'rgba(255,255,255,0)');
        ctx.beginPath();
        ctx.arc(cx, cy, BALL_R, 0, Math.PI * 2);
        ctx.fillStyle = h1;
        ctx.fill();

        // Sharp specular dot
        ctx.beginPath();
        ctx.arc(cx - BALL_R * 0.30, cy - BALL_R * 0.36, BALL_R * 0.17, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,255,0.95)';
        ctx.fill();

        this._ballSprite    = c;
        this._ballSpriteOff = cssOff;
        this._ballSpriteCss = cssS;
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    _layout() {
        // Always use CSS pixels for layout — DPR scaling is applied in _frame()
        const W = this._cssW;
        const H = this._cssH;
        const topPad = 20;
        const botPad = SLOT_H + 10;
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
        let col = 0;
        const p0 = this._pegPos(0, 0);
        let x0   = p0.x;
        let y0   = p0.y - this.rowSpacing * 1.6;

        for (let row = 0; row < this.rows; row++) {
            const peg = this._pegPos(row, col);
            segs.push({
                x0, y0,
                x1: peg.x, y1: peg.y,
                cx: peg.x, cy: y0,
                pegRow: row, pegCol: col
            });
            x0 = peg.x;
            y0 = peg.y;
            if (path[row]) col++;
        }

        const sx = this._slotX(slotIdx);
        const sy = this._cssH - SLOT_H / 2 - 2;
        segs.push({
            x0, y0,
            x1: sx, y1: sy,
            cx: sx, cy: y0,
            isSlot: true, slotIdx
        });

        return segs;
    }

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

        for (const ball of this._balls) {
            this._stepBall(ball, dt);
            this._updateParticles(ball.particles, dt);
            if (ball.landing) ball.landing.age += dt;
        }

        for (const [k, v] of this._flashes) {
            const nv = v - dt / 260;
            nv <= 0 ? this._flashes.delete(k) : this._flashes.set(k, nv);
        }

        this._frame();

        this._balls = this._balls.filter(b =>
            !b.done || b.particles.length > 0 ||
            (b.landing && b.landing.age < LANDING_TTL)
        );

        const alive = this._balls.length > 0 || this._flashes.size > 0;
        if (alive) {
            this._raf = requestAnimationFrame(ts => this._tick(ts));
        } else {
            this._raf = null;
        }
    }

    _stepBall(ball, dt) {
        if (ball.done || ball.segIdx >= ball.segs.length) return;

        const seg    = ball.segs[ball.segIdx];
        const stepMs = (this.fast ? STEP_MS_FAST : STEP_MS_NORMAL)
                     * (seg.isSlot ? 1.4 : 1);

        ball.t += dt / stepMs;
        const te  = easeIn(Math.min(ball.t, 1));
        const pos = this._bez(seg, te);
        ball.x    = pos.x;
        ball.y    = pos.y;

        ball.trail.unshift({ x: pos.x, y: pos.y });
        if (ball.trail.length > TRAIL_MAX) ball.trail.pop();

        if (ball.t >= 1) {
            ball.t = 0;
            if (!seg.isSlot) {
                this._flashes.set(`${seg.pegRow},${seg.pegCol}`, 1.0);
                try { this._onPegHit?.(); } catch {}
                ball.segIdx++;
            } else {
                const pal = slotPalette(MULT[ball.rows][ball.risk][seg.slotIdx]);
                ball.landing = { idx: seg.slotIdx, pal, age: 0 };
                this._spawnParticles(ball.particles, pos.x, pos.y, pal);
                ball.done    = true;
                ball.segIdx++;
                try { ball.onDone(ball.multiplier, ball.profit); }
                catch (e) { console.error('plinko onDone error', e); }
            }
        }
    }

    // ── Particles ─────────────────────────────────────────────────────────────

    _spawnParticles(arr, x, y, pal) {
        // Ring burst
        for (let i = 0; i < 20; i++) {
            const a  = (Math.PI * 2 * i / 20) - Math.PI / 2;
            const sp = 1.5 + Math.random() * 2.6;
            arr.push({ x, y,
                vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 1.8,
                color: pal.glow, life: 600 + Math.random() * 350, age: 0 });
        }
        // Extra white sparks
        for (let i = 0; i < 8; i++) {
            const a = Math.random() * Math.PI * 2;
            arr.push({
                x: x + (Math.random() - 0.5) * 10,
                y: y + (Math.random() - 0.5) * 7,
                vx: Math.cos(a) * (2.5 + Math.random() * 2),
                vy: Math.sin(a) * (2.5 + Math.random() * 2) - 2.5,
                color: '#ffffff', life: 280 + Math.random() * 180, age: 0 });
        }
    }

    _updateParticles(arr, dt) {
        for (let i = arr.length - 1; i >= 0; i--) {
            const p = arr[i];
            p.age += dt;
            p.vy  += 0.07;
            p.vx  *= 0.97;
            p.x   += p.vx;
            p.y   += p.vy;
            if (p.age >= p.life) arr.splice(i, 1);
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    _staticRedraw() {
        this._layout();
        this._frame();
    }

    _frame() {
        const { ctx, canvas } = this;
        // Clear at physical pixel size (before any transform)
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        // Scale to device pixels — all subsequent drawing uses CSS coordinates
        ctx.save();
        ctx.scale(this._dpr, this._dpr);

        // 1. Background
        if (this._bgSprite) ctx.drawImage(this._bgSprite, 0, 0, this._cssW, this._cssH);

        // 2. Slots (drawn before pegs so pegs sit on top)
        this._drawSlots();

        // 3. Pegs + peg flashes
        this._drawPegs();

        // 4. Per-ball: particles → trail → ball
        for (const ball of this._balls) {
            if (ball.particles.length > 0) this._drawParticles(ball.particles);
            if (!ball.done) {
                this._drawTrail(ball.trail);
                this._drawBall(ball.x, ball.y);
            }
        }

        ctx.restore();
    }

    _drawPegs() {
        const { ctx } = this;
        if (!this._pegSprite) return;
        const off = this._pegSpriteOff;
        const css = this._pegSpriteCss;

        for (let row = 0; row < this.rows; row++) {
            for (let col = 0; col < row + 2; col++) {
                const { x, y } = this._pegPos(row, col);
                const flash     = this._flashes.get(`${row},${col}`) || 0;

                // Draw at explicit CSS size so DPR-scaled sprite renders at 1:1
                ctx.drawImage(this._pegSprite, x - off, y - off, css, css);

                if (flash > 0) {
                    const halo = ctx.createRadialGradient(x, y, PEG_R, x, y, PEG_R + 9);
                    halo.addColorStop(0, `rgba(37,99,235,${flash * 0.30})`);
                    halo.addColorStop(1, 'rgba(37,99,235,0)');
                    ctx.beginPath();
                    ctx.arc(x, y, PEG_R + 9, 0, Math.PI * 2);
                    ctx.fillStyle = halo;
                    ctx.fill();
                }
            }
        }
    }

    _drawSlots() {
        const { ctx } = this;
        const slots  = this.rows + 1;
        const mults  = MULT[this.rows][this.risk];
        const gap    = 3;
        const slotW  = this.colSpacing - gap;
        const slotH  = SLOT_H - 4;
        const slotY  = this._cssH - SLOT_H + 2;
        const BORDER = 3;   // coloured top bar height
        const RADIUS = 7;

        const landingMap = new Map();
        for (const b of this._balls) {
            if (b.landing) landingMap.set(b.landing.idx, b.landing);
        }

        for (let i = 0; i < slots; i++) {
            const cx  = this._slotX(i);
            const x   = cx - slotW / 2;
            const m   = mults[i];
            const pal = slotPalette(m);
            const lnd = landingMap.get(i);

            // ── Body fill — tinted white, deeper on landing ────────────────
            ctx.beginPath();
            this._rrect(ctx, x, slotY, slotW, slotH, RADIUS);
            ctx.fillStyle = lnd ? pal.bgLand : pal.bg;
            ctx.fill();

            // ── Coloured top border bar ────────────────────────────────────
            // clip to slot shape so the bar corners match the card
            ctx.save();
            ctx.beginPath();
            this._rrect(ctx, x, slotY, slotW, slotH, RADIUS);
            ctx.clip();
            ctx.fillStyle = pal.border;
            ctx.globalAlpha = lnd
                ? (0.85 + 0.15 * Math.sin(lnd.age / 70))
                : 1;
            ctx.fillRect(x, slotY, slotW, BORDER);
            ctx.globalAlpha = 1;
            ctx.restore();

            // ── Multiplier label ───────────────────────────────────────────
            const lbl = m >= 10 ? `${m.toFixed(0)}x` : `${m.toFixed(1)}x`;
            const fs  = slotW > 32 ? 10 : 8;
            ctx.font         = `700 ${fs}px Inter, system-ui, sans-serif`;
            ctx.textAlign    = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillStyle    = pal.text;
            ctx.fillText(lbl, cx, slotY + BORDER + (slotH - BORDER) / 2);
        }
    }

    _drawBall(x, y) {
        if (!this._ballSprite) return;
        this.ctx.drawImage(this._ballSprite,
            x - this._ballSpriteOff, y - this._ballSpriteOff,
            this._ballSpriteCss, this._ballSpriteCss);
    }

    _drawTrail(trail) {
        const { ctx } = this;
        for (let i = 0; i < trail.length; i++) {
            const { x, y } = trail[i];
            const frac = (1 - i / trail.length);
            if (frac < 0.06) continue;
            const r = BALL_R * frac * 0.45;
            ctx.beginPath();
            ctx.arc(x, y, r, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(37,99,235,${frac * 0.20})`;
            ctx.fill();
        }
    }

    _drawParticles(arr) {
        const { ctx } = this;
        for (const p of arr) {
            const a = Math.max(0, 1 - p.age / p.life);
            const r = 3.0 * a;
            if (r < 0.3) continue;
            ctx.beginPath();
            ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
            ctx.fillStyle = p.color + Math.floor(a * 255).toString(16).padStart(2, '0');
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
