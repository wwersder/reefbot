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
const BALL_R         = 8;
const PEG_R          = 5;
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
 * Professional dark-theme palette.
 * Each slot: near-black body, 3 px coloured accent on top, white-ish label.
 * No pastel fills — only the accent line and glow carry the colour.
 */
function slotPalette(mult) {
    if (mult >= 15) return { bg: '#100800', border: '#d97706', text: '#fef08a', glow: '#f59e0b' };
    if (mult >= 5)  return { bg: '#030e06', border: '#15803d', text: '#bbf7d0', glow: '#22c55e' };
    if (mult >= 2)  return { bg: '#020812', border: '#1d4ed8', text: '#bfdbfe', glow: '#3b82f6' };
    if (mult >= 0.8)return { bg: '#0c0e13', border: '#334155', text: '#64748b', glow: '#475569' };
    return               { bg: '#100303', border: '#991b1b', text: '#fca5a5', glow: '#ef4444' };
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
     * Static background: a single overhead spotlight fading to pitch black at edges.
     * No patterns, no dots — clean and professional.
     */
    _buildBgSprite() {
        const { canvas } = this;
        const c   = document.createElement('canvas');
        c.width   = canvas.width;
        c.height  = canvas.height;
        const ctx = c.getContext('2d');

        // Overhead spotlight — cool blue-white source from top-centre
        const spot = ctx.createRadialGradient(
            c.width / 2, -c.height * 0.05, 0,
            c.width / 2,  c.height * 0.10, c.height * 0.95
        );
        spot.addColorStop(0,    'rgba(18, 38, 90, 0.38)');
        spot.addColorStop(0.45, 'rgba( 8, 18, 45, 0.18)');
        spot.addColorStop(1,    'rgba( 0,  0,  0, 0.00)');
        ctx.fillStyle = spot;
        ctx.fillRect(0, 0, c.width, c.height);

        this._bgSprite = c;
    }

    /**
     * Pre-rendered bioluminescent coral pin.
     * Dark steel body with a bright cyan inner core and a subtle outer glow —
     * pops clearly against the near-black canvas background.
     */
    _buildPegSprite() {
        const PAD  = 16;
        const size = (PEG_R + PAD) * 2;
        const c    = document.createElement('canvas');
        c.width    = c.height = size;
        const ctx  = c.getContext('2d');
        const cx   = size / 2;
        const cy   = size / 2;

        // Outer ambient glow (bioluminescent halo)
        const glow = ctx.createRadialGradient(cx, cy, PEG_R * 0.6, cx, cy, PEG_R * 3.0);
        glow.addColorStop(0, 'rgba(34,211,238,0.22)');
        glow.addColorStop(1, 'rgba(34,211,238,0)');
        ctx.beginPath();
        ctx.arc(cx, cy, PEG_R * 3.0, 0, Math.PI * 2);
        ctx.fillStyle = glow;
        ctx.fill();

        // Drop shadow
        ctx.beginPath();
        ctx.arc(cx + 0.5, cy + 2.0, PEG_R * 0.90, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(0,0,0,0.60)';
        ctx.fill();

        // Body — dark steel with cyan-lit top
        const body = ctx.createRadialGradient(
            cx - PEG_R * 0.36, cy - PEG_R * 0.36, PEG_R * 0.02,
            cx, cy, PEG_R
        );
        body.addColorStop(0,    'rgba(210, 250, 255, 0.98)');  // bright cyan-white top
        body.addColorStop(0.28, 'rgba(60,  195, 220, 0.94)');  // cyan mid
        body.addColorStop(0.62, 'rgba(14,  80,  105, 0.90)');  // teal-steel
        body.addColorStop(1,    'rgba(4,   22,  38,  0.88)');  // near-black edge
        ctx.beginPath();
        ctx.arc(cx, cy, PEG_R, 0, Math.PI * 2);
        ctx.fillStyle = body;
        ctx.fill();

        // Rim highlight ring — thin bright arc around top half
        ctx.beginPath();
        ctx.arc(cx, cy, PEG_R - 0.8, Math.PI * 1.1, Math.PI * 1.9);
        ctx.strokeStyle = 'rgba(160,240,255,0.55)';
        ctx.lineWidth   = 1.0;
        ctx.stroke();

        // Sharp specular dot — top-left
        ctx.beginPath();
        ctx.arc(cx - PEG_R * 0.28, cy - PEG_R * 0.32, PEG_R * 0.28, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,255,0.95)';
        ctx.fill();

        this._pegSprite       = c;
        this._pegSpriteOffset = size / 2;
    }

    /**
     * Pre-rendered golden pearl ball — warm amber/gold tone.
     * Contrasts strongly with the cyan pegs and dark-blue background.
     * Iridescent sheen layer gives the "real pearl" look.
     */
    _buildBallSprite() {
        const PAD  = 22;
        const size = (BALL_R + PAD) * 2;
        const c    = document.createElement('canvas');
        c.width    = c.height = size;
        const ctx  = c.getContext('2d');
        const cx   = size / 2;
        const cy   = size / 2;

        // Warm golden outer glow
        const aura = ctx.createRadialGradient(cx, cy, BALL_R * 0.4, cx, cy, BALL_R * 3.2);
        aura.addColorStop(0,   'rgba(255, 210, 90,  0.22)');
        aura.addColorStop(0.5, 'rgba(255, 170, 30,  0.09)');
        aura.addColorStop(1,   'transparent');
        ctx.beginPath();
        ctx.arc(cx, cy, BALL_R * 3.2, 0, Math.PI * 2);
        ctx.fillStyle = aura;
        ctx.fill();

        // Drop shadow
        ctx.beginPath();
        ctx.arc(cx + 0.8, cy + 2.5, BALL_R * 0.90, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(0,0,0,0.48)';
        ctx.fill();

        // Pearl body — warm cream to deep amber radial, light from top-left
        const g = ctx.createRadialGradient(
            cx - BALL_R * 0.30, cy - BALL_R * 0.34, 0,
            cx, cy, BALL_R
        );
        g.addColorStop(0,    '#fffbe8');  // creamy white highlight
        g.addColorStop(0.18, '#f5d97a');  // warm gold
        g.addColorStop(0.45, '#d4a020');  // rich amber-gold
        g.addColorStop(0.74, '#9a6a08');  // deep gold-brown
        g.addColorStop(1,    '#4a3004');  // near-black shadow

        ctx.beginPath();
        ctx.arc(cx, cy, BALL_R, 0, Math.PI * 2);
        ctx.fillStyle = g;
        ctx.shadowColor = 'rgba(255, 195, 50, 0.65)';
        ctx.shadowBlur  = 14;
        ctx.fill();
        ctx.shadowBlur = 0;

        // Iridescent sheen — rose-to-green shift (real pearl lustre)
        const sheen = ctx.createRadialGradient(
            cx + BALL_R * 0.18, cy + BALL_R * 0.12, 0,
            cx, cy, BALL_R * 1.05
        );
        sheen.addColorStop(0,   'rgba(255, 140, 180, 0.18)');
        sheen.addColorStop(0.45,'rgba(120, 240, 180, 0.10)');
        sheen.addColorStop(1,   'transparent');
        ctx.beginPath();
        ctx.arc(cx, cy, BALL_R, 0, Math.PI * 2);
        ctx.fillStyle = sheen;
        ctx.fill();

        // Wide soft specular — covers upper-left quadrant
        const h1 = ctx.createRadialGradient(
            cx - BALL_R * 0.26, cy - BALL_R * 0.28, 0,
            cx - BALL_R * 0.08, cy - BALL_R * 0.08, BALL_R * 0.56
        );
        h1.addColorStop(0,   'rgba(255,255,255,0.90)');
        h1.addColorStop(0.5, 'rgba(255,255,255,0.22)');
        h1.addColorStop(1,   'rgba(255,255,255,0)');
        ctx.beginPath();
        ctx.arc(cx, cy, BALL_R, 0, Math.PI * 2);
        ctx.fillStyle = h1;
        ctx.fill();

        // Sharp specular dot
        ctx.beginPath();
        ctx.arc(cx - BALL_R * 0.30, cy - BALL_R * 0.36, BALL_R * 0.18, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,255,0.98)';
        ctx.fill();

        // Bottom warm inner reflection
        const h2 = ctx.createRadialGradient(
            cx + BALL_R * 0.12, cy + BALL_R * 0.48, 0,
            cx, cy, BALL_R
        );
        h2.addColorStop(0, 'rgba(255, 200, 80, 0.28)');
        h2.addColorStop(1, 'transparent');
        ctx.beginPath();
        ctx.arc(cx, cy, BALL_R, 0, Math.PI * 2);
        ctx.fillStyle = h2;
        ctx.fill();

        this._ballSprite       = c;
        this._ballSpriteOffset = size / 2;
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    _layout() {
        const W = this.canvas.width;
        const H = this.canvas.height;
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
        const sy = this.canvas.height - SLOT_H / 2 - 2;
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
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        // 1. Pre-rendered background (dot grid + vignette)
        if (this._bgSprite) ctx.drawImage(this._bgSprite, 0, 0);

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
    }

    _drawPegs() {
        const { ctx } = this;
        if (!this._pegSprite) return;
        const off = this._pegSpriteOffset;

        for (let row = 0; row < this.rows; row++) {
            for (let col = 0; col < row + 2; col++) {
                const { x, y } = this._pegPos(row, col);
                const flash     = this._flashes.get(`${row},${col}`) || 0;

                if (flash > 0) {
                    // Cyan radial burst behind peg on hit
                    const halo = ctx.createRadialGradient(x, y, PEG_R * 0.5, x, y, PEG_R + 14);
                    halo.addColorStop(0, `rgba(34,211,238,${flash * 0.55})`);
                    halo.addColorStop(1, 'rgba(34,211,238,0)');
                    ctx.beginPath();
                    ctx.arc(x, y, PEG_R + 14, 0, Math.PI * 2);
                    ctx.fillStyle = halo;
                    ctx.fill();
                }

                // Pre-rendered bioluminescent peg
                ctx.drawImage(this._pegSprite, x - off, y - off);

                if (flash > 0) {
                    // Bright cyan-white flash overlay on hit
                    ctx.beginPath();
                    ctx.arc(x, y, PEG_R, 0, Math.PI * 2);
                    ctx.fillStyle = `rgba(180,250,255,${flash * 0.65})`;
                    ctx.fill();
                }
            }
        }
    }

    _drawSlots() {
        const { ctx, canvas } = this;
        const slots = this.rows + 1;
        const mults = MULT[this.rows][this.risk];
        const slotW = this.colSpacing - 4;
        const slotH = SLOT_H - 4;
        const slotY = canvas.height - SLOT_H + 1;
        const BORDER = 3;   // px — coloured accent line height

        const landingMap = new Map();
        for (const b of this._balls) {
            if (b.landing) landingMap.set(b.landing.idx, b.landing);
        }

        // Thin separator above slots zone
        const sepX0 = this._slotX(0) - slotW / 2 - 2;
        const sepX1 = this._slotX(slots - 1) + slotW / 2 + 2;
        ctx.beginPath();
        ctx.moveTo(sepX0, slotY - 3);
        ctx.lineTo(sepX1, slotY - 3);
        ctx.strokeStyle = 'rgba(255,255,255,0.06)';
        ctx.lineWidth   = 1;
        ctx.stroke();

        for (let i = 0; i < slots; i++) {
            const cx  = this._slotX(i);
            const x   = cx - slotW / 2;
            const m   = mults[i];
            const pal = slotPalette(m);
            const lnd = landingMap.get(i);
            const [gr, gg, gb] = hexRgb(pal.glow);

            // ── Landing outer glow ─────────────────────────────────────────
            if (lnd) {
                const pulse = 0.5 + 0.5 * Math.sin(lnd.age / 90);
                const halo  = ctx.createRadialGradient(
                    cx, slotY + slotH * 0.5, 0,
                    cx, slotY + slotH * 0.5, slotW * 1.2
                );
                halo.addColorStop(0, `rgba(${gr},${gg},${gb},${0.30 * pulse})`);
                halo.addColorStop(1, `rgba(${gr},${gg},${gb},0)`);
                ctx.beginPath();
                ctx.arc(cx, slotY + slotH * 0.5, slotW * 1.2, 0, Math.PI * 2);
                ctx.fillStyle = halo;
                ctx.fill();
            }

            // ── Slot body — near-black ─────────────────────────────────────
            ctx.beginPath();
            this._rrect(ctx, x, slotY, slotW, slotH, 6);
            ctx.fillStyle = pal.bg;
            ctx.fill();

            // ── Coloured accent line across the top ────────────────────────
            const accentAlpha = lnd
                ? (0.70 + 0.30 * Math.sin(lnd.age / 75))
                : 0.90;
            ctx.beginPath();
            this._rrect(ctx, x, slotY, slotW, BORDER, 3);
            ctx.fillStyle = lnd
                ? `rgba(${gr},${gg},${gb},${accentAlpha})`
                : pal.border;
            ctx.fill();

            // ── Multiplier label — white on dark ───────────────────────────
            const lbl = m >= 10 ? `${m.toFixed(0)}x` : `${m.toFixed(1)}x`;
            const fs  = slotW > 34 ? 10 : 8;
            ctx.font         = `700 ${fs}px Inter, system-ui, sans-serif`;
            ctx.textAlign    = 'center';
            ctx.textBaseline = 'middle';
            const midY = slotY + BORDER + (slotH - BORDER) / 2;
            ctx.fillStyle = lnd
                ? `rgba(${gr},${gg},${gb},1)`
                : pal.text;
            ctx.fillText(lbl, cx, midY);
        }
    }

    _drawBall(x, y) {
        if (!this._ballSprite) return;
        const off = this._ballSpriteOffset;
        this.ctx.drawImage(this._ballSprite, x - off, y - off);
    }

    _drawTrail(trail) {
        const { ctx } = this;
        for (let i = 0; i < trail.length; i++) {
            const { x, y } = trail[i];
            const frac = (1 - i / trail.length);
            if (frac < 0.06) continue;
            const r = BALL_R * frac * 0.60;
            const grad = ctx.createRadialGradient(x, y, 0, x, y, r * 2.2);
            grad.addColorStop(0, `rgba(255,195,50,${frac * 0.28})`);
            grad.addColorStop(1, 'transparent');
            ctx.beginPath();
            ctx.arc(x, y, r * 2.2, 0, Math.PI * 2);
            ctx.fillStyle = grad;
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
