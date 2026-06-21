/**
 * The Reef House — slot UI controller v1
 *
 * Architecture:
 *  - 5 reels × 3 rows, CSS translateY animation
 *  - Sequential reel stop (150 ms stagger)
 *  - Win-line highlight after all reels settle
 *  - Free-spins mode: teal overlay, growing multiplier badge
 */

import { setInitData, fetchState, postSpin } from './slot-api.js?v=1';

// ── Symbol catalogue ──────────────────────────────────────────────────────────

const SYM = {
    FISH_CLOWN:  { e: '🐠', name: 'Рыба-клоун', color: '#ff7043' },
    FISH_PUFFER: { e: '🐡', name: 'Рыба-шар',   color: '#ff9800' },
    SHRIMP:      { e: '🦐', name: 'Креветка',   color: '#f44336' },
    FISH_BLUE:   { e: '🐟', name: 'Рыба',       color: '#2196f3' },
    CRAB:        { e: '🦀', name: 'Краб',       color: '#e91e63' },
    OCTOPUS:     { e: '🐙', name: 'Осьминог',   color: '#9c27b0' },
    SQUID:       { e: '🦑', name: 'Кальмар',    color: '#673ab7' },
    SHARK:       { e: '🦈', name: 'Акула',      color: '#03a9f4' },
    WILD:        { e: '🌊', name: 'Wild',        color: '#00bcd4' },
    SCATTER:     { e: '🏺', name: 'Scatter',     color: '#ffc107' },
};

const SYM_KEYS   = Object.keys(SYM);
const SYM_HEIGHT = 76;     // px — must match CSS .slot-sym height
const REEL_COUNT = 5;
const SPIN_ROWS  = 22;     // random symbols before the 3 final ones
const STAGGER_MS = 160;    // ms between reel stops

// ── State ─────────────────────────────────────────────────────────────────────

let _tg        = null;
let balance    = 0;
let _shownBal  = 0;
let _balRaf    = null;
let betValue   = 10;
let spinning   = false;
let _vipState  = null;

const MIN_BET  = 10;
const BET_STEP = 10;

// ── DOM helpers ───────────────────────────────────────────────────────────────

const $  = id  => document.getElementById(id);
const $$ = sel => document.querySelectorAll(sel);

// ── Init ──────────────────────────────────────────────────────────────────────

export async function init() {
    const tg = window.Telegram?.WebApp;
    _tg = tg || null;
    if (tg) { tg.ready(); tg.expand(); setInitData(tg.initData); }
    else setInitData('');

    showScreen('loading');

    try {
        const state = await fetchState();

        if (state.onboardingRequired) {
            $('ob-text').textContent = state.message || 'Сначала зарегистрируйся в боте.';
            showScreen('onboarding');
            return;
        }

        balance   = state.balance;
        _shownBal = balance;
        _vipState = state;

        bindEvents();
        renderReels(null);
        updateUI();
        showFreeSpinsBanner(state.freeSpinsRemaining, state.multiplier);
        showScreen('app');

    } catch (e) {
        console.error('Init failed', e);
        showScreen('onboarding');
    }
}

// ── Events ────────────────────────────────────────────────────────────────────

function bindEvents() {
    $('bet-minus').addEventListener('click', () => setBet(betValue - BET_STEP, true));
    $('bet-plus').addEventListener('click',  () => setBet(betValue + BET_STEP, true));
    $('bet-input').addEventListener('blur',  () => setBet(parseInt($('bet-input').value) || MIN_BET));

    $$('.mult-btn').forEach(btn => btn.addEventListener('click', () => {
        const m = btn.dataset.mult;
        setBet(m === 'max' ? balance : Math.round(betValue * parseFloat(m)));
    }));

    $('spin-btn').addEventListener('click', handleSpin);

    // Navigation back to Plinko
    $('back-btn').addEventListener('click', () => {
        window.location.href = '/mini/plinko/';
    });

    // Dismiss keyboard
    document.addEventListener('touchstart', e => {
        if (document.activeElement?.id === 'bet-input' && !e.target.closest('#bet-input')) {
            document.activeElement.blur();
        }
    }, { passive: true });
}

// ── Spin ──────────────────────────────────────────────────────────────────────

async function handleSpin() {
    if (spinning) return;
    const inFreeSpins = (_vipState?.freeSpinsRemaining ?? 0) > 0;
    if (!inFreeSpins && balance < MIN_BET) { showWin('Пополни баланс 🐚'); return; }

    spinning = true;
    haptic('medium');
    updateSpinBtn(true);
    clearWinOverlay();

    // Immediate balance deduction display (optimistic)
    if (!inFreeSpins) {
        balance = Math.max(0, balance - betValue);
        animateBalance(balance);
    }

    try {
        // Start reel animation immediately (don't wait for server)
        const spinPromise  = animateReels(null); // spin with random finals for now
        const serverPromise = postSpin(betValue);

        // Wait for server response
        const res = await serverPromise;

        if (res.error) {
            // Restore balance on error
            balance = (balance + (inFreeSpins ? 0 : betValue));
            animateBalance(balance);
            spinning = false;
            updateSpinBtn(false);
            showWin(errorText(res.error));
            return;
        }

        // Stop reels on the actual server result
        await stopReels(res.grid);
        await spinPromise.catch(() => {});  // ignore if already resolved

        // Apply result
        balance = res.newBalance;
        _vipState = { ..._vipState, ...res };
        animateBalance(balance);
        showFreeSpinsBanner(res.freeSpinsRemaining, res.multiplier);

        // Win display
        if (res.isFreeSpinTrigger) {
            haptic('success');
            showWin(`🏺 БОНУС! ${getSpinsForRes(res)} бесплатных спинов!`, 'bonus');
        } else if (res.totalWin > 0) {
            haptic('success');
            const label = res.wasFreeSpins && res.multiplier > 1
                ? `+${res.totalWin} 🐚  ×${res.multiplier} множитель`
                : `+${res.totalWin} 🐚`;
            showWin(label, 'win');
            highlightWins(res.wins);
        } else {
            showWin('Не повезло', 'loss');
        }

    } catch (e) {
        console.error('Spin error', e);
        balance += (inFreeSpins ? 0 : betValue); // restore
        animateBalance(balance);
        showWin('Ошибка сети 🌊');
    } finally {
        spinning = false;
        updateSpinBtn(false);
        updateThrowBtn();
    }
}

function getSpinsForRes(res) {
    // Derive spin count from scatterCount (mirrors backend logic)
    return res.scatterCount === 3 ? 10 : res.scatterCount === 4 ? 15 : 20;
}

// ── Reel animation ────────────────────────────────────────────────────────────

// Hold references to pending stop controllers
let _reelStopGrid = null;  // server grid to stop on
let _reelStopped  = [false, false, false, false, false];

/** Begin spinning all reels. Resolves when all reels have settled. */
function animateReels(finalGrid) {
    _reelStopGrid  = finalGrid;
    _reelStopped   = [false, false, false, false, false];

    for (let r = 0; r < REEL_COUNT; r++) buildStrip(r, null);
    // Kick off the spin for each reel (they roll indefinitely until stopReels is called)
    for (let r = 0; r < REEL_COUNT; r++) startReelSpin(r);

    return Promise.resolve();
}

/** Server responded — stop each reel sequentially with stagger. */
async function stopReels(grid) {
    for (let r = 0; r < REEL_COUNT; r++) {
        await sleep(STAGGER_MS);
        await snapReelTo(r, grid[r]);
        haptic('light');
    }
    // Brief pause so all reels settle before showing win
    await sleep(200);
}

function buildStrip(reelIdx, finalSymbols) {
    const strip = $(`strip-${reelIdx}`);
    if (!strip) return;
    const syms = Array.from({ length: SPIN_ROWS }, () => randomSym());
    if (finalSymbols) syms.push(...finalSymbols);
    strip.innerHTML = syms.map(s =>
        `<div class="slot-sym">${SYM[s]?.e ?? '?'}</div>`
    ).join('');
    strip.style.transition = 'none';
    strip.style.transform  = 'translateY(0)';
    strip.offsetHeight; // force reflow
}

function startReelSpin(reelIdx) {
    const strip = $(`strip-${reelIdx}`);
    if (!strip) return;
    // Fast continuous scroll using CSS animation
    strip.classList.add('spinning');
}

async function snapReelTo(reelIdx, finalSymbols3) {
    const strip = $(`strip-${reelIdx}`);
    if (!strip) return;
    strip.classList.remove('spinning');

    // Rebuild strip with known finals at bottom
    buildStrip(reelIdx, finalSymbols3);

    const targetY = -(SPIN_ROWS * SYM_HEIGHT);
    strip.style.transition = `transform 0.45s cubic-bezier(0.22, 0.8, 0.4, 1.0)`;
    strip.style.transform  = `translateY(${targetY}px)`;

    await sleep(500);

    // Settle: show only the 3 final symbols
    strip.style.transition = 'none';
    strip.style.transform  = 'translateY(0)';
    strip.innerHTML = finalSymbols3.map(s =>
        `<div class="slot-sym">${SYM[s]?.e ?? '?'}</div>`
    ).join('');
    strip.offsetHeight;
}

/** Render reels statically (on init or after settle). */
function renderReels(grid) {
    for (let r = 0; r < REEL_COUNT; r++) {
        const strip = $(`strip-${r}`);
        if (!strip) continue;
        const syms = grid ? grid[r] : [randomSym(), randomSym(), randomSym()];
        strip.innerHTML = syms.map(s =>
            `<div class="slot-sym">${SYM[s]?.e ?? '?'}</div>`
        ).join('');
        strip.style.transition = 'none';
        strip.style.transform  = 'translateY(0)';
    }
}

// ── Win display ───────────────────────────────────────────────────────────────

function highlightWins(wins) {
    if (!wins?.length) return;
    // Mark each winning cell
    wins.forEach(w => {
        for (let r = 0; r < REEL_COUNT; r++) {
            const row    = w.rows[r];
            const strip  = $(`strip-${r}`);
            const cells  = strip?.querySelectorAll('.slot-sym');
            if (cells?.[row]) cells[row].classList.add('winning');
        }
    });
    // Auto-clear after 1.5s
    setTimeout(clearWinOverlay, 1500);
}

function clearWinOverlay() {
    $$('.slot-sym.winning').forEach(el => el.classList.remove('winning'));
}

function showWin(text, type = 'neutral') {
    const el  = $('win-label');
    const bar = $('win-bar');
    if (!el || !bar) return;
    el.textContent = text;
    bar.className  = 'win-bar ' + type;
    bar.classList.add('visible');
    clearTimeout(bar._timer);
    if (type !== 'neutral') {
        bar._timer = setTimeout(() => bar.classList.remove('visible'), 2500);
    }
}

// ── Free spins banner ─────────────────────────────────────────────────────────

function showFreeSpinsBanner(remaining, multiplier) {
    const banner = $('fs-banner');
    const zone   = $('slot-zone');
    if (!banner || !zone) return;
    if (remaining > 0) {
        $('fs-count').textContent = remaining;
        $('fs-mult').textContent  = multiplier > 1 ? `×${multiplier}` : '';
        banner.classList.add('visible');
        zone.classList.add('free-spins');
    } else {
        banner.classList.remove('visible');
        zone.classList.remove('free-spins');
    }
}

// ── UI helpers ────────────────────────────────────────────────────────────────

function updateUI() {
    setBet(betValue);
    $('balance-num').textContent  = balance;
    $('balance-hint').textContent = balance;
    _shownBal = balance;
    updateThrowBtn();
}

function setBet(v, snap = false) {
    let n = parseInt(v) || MIN_BET;
    n = Math.max(MIN_BET, Math.min(balance || MIN_BET, n));
    if (snap) n = Math.round(n / BET_STEP) * BET_STEP || MIN_BET;
    betValue = n;
    $('bet-input').value = betValue;
}

function updateThrowBtn() {
    const btn = $('spin-btn');
    if (!btn) return;
    const inFreeSpins = (_vipState?.freeSpinsRemaining ?? 0) > 0;
    if (inFreeSpins) {
        btn.textContent = `FREE SPIN 🏺`;
        btn.className   = 'free';
        btn.disabled    = false;
    } else if (balance < MIN_BET) {
        btn.textContent = 'Пополни баланс 🐚';
        btn.className   = 'broke';
        btn.disabled    = true;
    } else {
        btn.textContent = 'КРУТИТЬ';
        btn.className   = '';
        btn.disabled    = false;
    }
}

function updateSpinBtn(isSpinning) {
    const btn = $('spin-btn');
    if (!btn) return;
    btn.disabled = isSpinning;
    if (isSpinning) btn.textContent = '▪▪▪';
}

function animateBalance(to) {
    const from = _shownBal;
    if (from === to) { $('balance-num').textContent = to; $('balance-hint').textContent = to; return; }
    if (_balRaf) cancelAnimationFrame(_balRaf);
    const dur   = Math.max(180, Math.min(500, Math.abs(to - from) * 1.2));
    const start = performance.now();
    function step(now) {
        const t = Math.min((now - start) / dur, 1);
        const e = 1 - Math.pow(1 - t, 3);
        const v = Math.round(from + (to - from) * e);
        $('balance-num').textContent  = v;
        $('balance-hint').textContent = v;
        _shownBal = v;
        if (t < 1) _balRaf = requestAnimationFrame(step);
        else { _shownBal = to; updateThrowBtn(); }
    }
    _balRaf = requestAnimationFrame(step);
}

function randomSym() {
    return SYM_KEYS[Math.floor(Math.random() * SYM_KEYS.length)];
}

function haptic(style) {
    try { _tg?.HapticFeedback?.impactOccurred(style); } catch {}
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function errorText(code) {
    const MAP = {
        INSUFFICIENT_BALANCE: 'Недостаточно ракушек 🐚',
        INVALID_BET:          'Неверная ставка',
        ONBOARDING_REQUIRED:  'Сначала пройди регистрацию',
    };
    return MAP[code] ?? 'Что-то пошло не так 🌊';
}

function showScreen(name) {
    $('screen-loading').style.display    = name === 'loading'    ? 'flex' : 'none';
    $('screen-onboarding').style.display = name === 'onboarding' ? 'flex' : 'none';
    $('app').style.display               = name === 'app'        ? 'flex' : 'none';
}
