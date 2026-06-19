/**
 * Reef Plinko — Canvas board & ball animation.
 *
 * Coordinate system:
 *   - Board is centered inside the canvas.
 *   - Pegs are arranged in a triangular pattern: row i has (i+2) pegs.
 *   - Slots are below the last row.
 *
 * Ball animation:
 *   - path[] from server (array of booleans: false=left, true=right).
 *   - Ball moves peg-by-peg; each step takes STEP_MS milliseconds.
 *   - After reaching the slot, a flash animation plays.
 */

const STEP_MS_NORMAL = 160;
const STEP_MS_FAST   = 45;
const BALL_RADIUS    = 7;
const PEG_RADIUS     = 5;
const SLOT_HEIGHT    = 36;

// Multiplier tables (must match PlinkoService.java)
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

function slotColor(mult) {
    if (mult >= 15)  return '#fbbf24'; // gold — jackpot
    if (mult >= 5)   return '#34d399'; // green
    if (mult >= 1.5) return '#22d3ee'; // cyan
    if (mult >= 0.6) return '#64748b'; // grey — near break-even
    return '#f87171';                  // red — loss
}

export class PlinkoBoard {
    /**
     * @param {HTMLCanvasElement} canvas
     * @param {Function} onAnimDone - called when ball animation completes
     */
    constructor(canvas, onAnimDone) {
        this.canvas = canvas;
        this.ctx    = canvas.getContext('2d');
        this.onAnimDone = onAnimDone;

        this.rows   = 8;
        this.risk   = 'MEDIUM';
        this.fast   = false;

        this._animFrame = null;
        this._ball = null;  // { x, y, stepIdx, waypoints }

        this.resize();
    }

    // ── Public ────────────────────────────────────────────────────────────────

    setRows(rows) { this.rows = rows; this.redraw(); }
    setRisk(risk) { this.risk = risk; this.redraw(); }
    setFast(fast) { this.fast = fast; }

    resize() {
        const wrap = this.canvas.parentElement;
        const w    = wrap.clientWidth;
        const h    = wrap.clientHeight;
        this.canvas.width  = w;
        this.canvas.height = h;
        this._computeLayout();
        this.redraw();
    }

    /**
     * Animates a ball drop along the given path.
     * @param {boolean[]} path
     * @param {number}    slot   - destination slot
     * @param {number}    multiplier
     * @param {number}    profit - signed shell amount
     */
    dropBall(path, slot, multiplier, profit) {
        if (this._animFrame) cancelAnimationFrame(this._animFrame);

        const waypoints = this._computeWaypoints(path, slot);
        this._ball = { stepIdx: 0, waypoints, t: 0, multiplier, profit };
        this._animate();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    _computeLayout() {
        const W = this.canvas.width;
        const H = this.canvas.height;
        const rows  = this.rows;
        const slots = rows + 1;

        // Available vertical space minus slot area and top padding
        const topPad    = 24;
        const boardH    = H - SLOT_HEIGHT - topPad - 8;
        this.rowSpacing  = boardH / (rows + 1);
        this.colSpacing  = Math.min((W - 32) / (slots), 38);

        // Starting X: center of the board
        this.originX = W / 2;
        this.originY = topPad + this.rowSpacing; // first row of pegs
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    redraw() {
        this._computeLayout();
        this._draw();
    }

    _draw() {
        const { ctx, canvas } = this;
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        this._drawPegs();
        this._drawSlots();

        if (this._ball) {
            const wp = this._ball.waypoints;
            const si = this._ball.stepIdx;
            const t  = this._ball.t;

            if (si < wp.length - 1) {
                // Interpolate between waypoints
                const from = wp[si];
                const to   = wp[si + 1];
                const bx   = from.x + (to.x - from.x) * t;
                const by   = from.y + (to.y - from.y) * t;
                this._drawBall(bx, by);
            } else if (wp.length > 0) {
                const last = wp[wp.length - 1];
                this._drawBall(last.x, last.y);
            }
        }
    }

    _drawPegs() {
        const { ctx } = this;
        for (let row = 0; row < this.rows; row++) {
            const numPegs = row + 2;
            for (let col = 0; col < numPegs; col++) {
                const { x, y } = this._pegPos(row, col);
                ctx.beginPath();
                ctx.arc(x, y, PEG_RADIUS, 0, Math.PI * 2);
                ctx.fillStyle = 'rgba(34, 211, 238, 0.75)';
                ctx.fill();
                // Glow
                ctx.shadowColor = 'rgba(34,211,238,.5)';
                ctx.shadowBlur  = 6;
                ctx.fill();
                ctx.shadowBlur = 0;
            }
        }
    }

    _drawSlots() {
        const { ctx, canvas } = this;
        const slots    = this.rows + 1;
        const mults    = MULT[this.rows][this.risk];
        const slotW    = this.colSpacing - 2;
        const slotY    = canvas.height - SLOT_HEIGHT;

        for (let i = 0; i < slots; i++) {
            const cx = this._slotX(i);
            const x  = cx - slotW / 2;

            ctx.fillStyle = slotColor(mults[i]);
            ctx.beginPath();
            this._roundRect(ctx, x, slotY, slotW, SLOT_HEIGHT - 4, 6);
            ctx.fill();

            // Multiplier label
            ctx.fillStyle = '#0a1628';
            ctx.font = `bold ${slotW > 30 ? 11 : 9}px Inter, system-ui`;
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            const label = mults[i] >= 10 ? mults[i].toFixed(0) + 'x'
                        : mults[i] >= 1  ? mults[i].toFixed(1) + 'x'
                        :                  mults[i].toFixed(1) + 'x';
            ctx.fillText(label, cx, slotY + SLOT_HEIGHT / 2 - 2);
        }
    }

    _drawBall(x, y) {
        const { ctx } = this;
        // Outer glow
        ctx.shadowColor = 'rgba(255,255,255,.6)';
        ctx.shadowBlur  = 12;
        // Ball
        const grad = ctx.createRadialGradient(x - 2, y - 2, 1, x, y, BALL_RADIUS);
        grad.addColorStop(0, '#ffffff');
        grad.addColorStop(1, '#94a3b8');
        ctx.beginPath();
        ctx.arc(x, y, BALL_RADIUS, 0, Math.PI * 2);
        ctx.fillStyle = grad;
        ctx.fill();
        ctx.shadowBlur = 0;
    }

    // ── Waypoints ─────────────────────────────────────────────────────────────

    _computeWaypoints(path, slot) {
        const wps = [];
        // Start: top center, above first row
        const startX = this.originX;
        const startY = this.originY - this.rowSpacing;
        wps.push({ x: startX, y: startY });

        // Track column position (which peg within each row)
        let col = 0;

        for (let row = 0; row < this.rows; row++) {
            const { x, y } = this._pegPos(row, col);
            wps.push({ x, y });
            if (path[row]) col++; // right
            // else left (col stays)
        }

        // Slot
        wps.push({ x: this._slotX(slot), y: this.canvas.height - SLOT_HEIGHT / 2 });
        return wps;
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    _animate() {
        if (!this._ball) return;

        const stepMs = this.fast ? STEP_MS_FAST : STEP_MS_NORMAL;

        let last = performance.now();
        const tick = (now) => {
            const dt  = now - last;
            last = now;

            if (!this._ball) return;

            this._ball.t += dt / stepMs;

            if (this._ball.t >= 1) {
                this._ball.t = 0;
                this._ball.stepIdx++;

                if (this._ball.stepIdx >= this._ball.waypoints.length - 1) {
                    // Done
                    this._draw();
                    const mult   = this._ball.multiplier;
                    const profit = this._ball.profit;
                    this._ball = null;
                    this.onAnimDone(mult, profit);
                    return;
                }
            }

            this._draw();
            this._animFrame = requestAnimationFrame(tick);
        };

        this._animFrame = requestAnimationFrame(tick);
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    _pegPos(row, col) {
        // Row 0 has 2 pegs, row 1 has 3, ...
        const numPegs = row + 2;
        const totalW  = (numPegs - 1) * this.colSpacing;
        const x = this.originX - totalW / 2 + col * this.colSpacing;
        const y = this.originY + row * this.rowSpacing;
        return { x, y };
    }

    _slotX(slotIdx) {
        const slots  = this.rows + 1;
        const totalW = (slots - 1) * this.colSpacing;
        return this.originX - totalW / 2 + slotIdx * this.colSpacing;
    }

    _roundRect(ctx, x, y, w, h, r) {
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
