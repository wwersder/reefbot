/**
 * Reef Plinko — UI controller v4
 *
 * New in v4:
 *   - Haptic feedback (impact on throw/peg, notification on result)
 *   - Balance count-up/down animation (ease-out cubic)
 *   - Results strip: last 10 outcomes as coloured dots
 *   - Jackpot screen flash on 15x+
 *   - Auto-spin progress bar under throw button
 *   - Broke state: disabled button when balance < MIN_BET
 */

import { setInitData, fetchState, postPlay, fetchLeaderboard } from './api.js?v=14';
import { PlinkoBoard } from './plinko.js?v=14';

const MIN_BET    = 5;
const BET_STEP   = 5;
const AUTO_MAX   = 100;
const RESULTS_MAX= 10;

const RTP_TABLE = {
    8:  { LOW: 97.6, MEDIUM: 97.1, HIGH: 94.7 },
    12: { LOW: 97.8, MEDIUM: 97.1, HIGH: 93.8 },
};


// ── State ─────────────────────────────────────────────────────────────────────

let _tg            = null;   // Telegram WebApp
let _vipState      = null;   // VIP data from server (refreshed on fetchState)
let balance        = 0;
let _shownBal      = 0;      // currently displayed balance (for animation)
let _balRaf        = null;   // rAF handle for balance animation
let betValue       = 25;
let rows           = 8;
let risk           = 'MEDIUM';
let autoRunning    = false;
let autoCount      = 0;
let autoInFlight   = false;
let manualInFlight = false;
let board          = null;
let _results       = [];     // last RESULTS_MAX result types
let _hapticTs      = 0;      // timestamp of last peg haptic (throttle)

let _syncTimer     = null;
let _syncSeq       = 0;
let _resultTimer   = null;  // auto-hide result bar

// ── Helpers ───────────────────────────────────────────────────────────────────

const $  = id  => document.getElementById(id);
const $$ = sel => document.querySelectorAll(sel);

/** Clamps bet to [MIN_BET, balance]. Step-rounding only when stepSnap=true (±buttons). */
function clampBet(v, stepSnap = false) {
    const n = parseInt(v) || MIN_BET;
    const clamped = Math.max(MIN_BET, Math.min(balance || MIN_BET, n));
    if (stepSnap) return Math.round(clamped / BET_STEP) * BET_STEP || MIN_BET;
    return clamped;
}

function escHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ── Haptics ───────────────────────────────────────────────────────────────────

function hapticImpact(style) {
    try { _tg?.HapticFeedback?.impactOccurred(style || 'medium'); } catch {}
}

function hapticNotify(type) {
    try { _tg?.HapticFeedback?.notificationOccurred(type || 'success'); } catch {}
}

/** Throttled peg-hit haptic — max once per 80 ms to avoid buzzing on fast boards */
function hapticPeg() {
    const now = Date.now();
    if (now - _hapticTs < 80) return;
    _hapticTs = now;
    hapticImpact('light');
}

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
            $('ob-text').textContent = state.message || 'Сначала заверши регистрацию в боте.';
            showScreen('onboarding');
            return;
        }

        balance    = state.balance;
        _shownBal  = balance;
        _vipState  = state;
        updateVipBadge();

        if (!state.rows12Unlocked) {
            const btn12 = $('rows-btn-12');
            if (btn12) btn12.disabled = true;
        }

        bindEvents();
        renderResultsStrip();
        updateUI();
        showScreen('app');

        requestAnimationFrame(() => {
            board = new PlinkoBoard($('plinko-canvas'), null);
            board.setOnPegHit(hapticPeg);
            window.addEventListener('resize', () => board?.resize());
        });

    } catch (e) {
        console.error('Init failed', e);
        const el = document.querySelector('.ob-emoji');
        if (el) el.textContent = '⚠️';
        $('ob-title') && ($('ob-title').textContent = 'Ошибка загрузки');
        $('ob-text')  && ($('ob-text').textContent  = 'Не удалось подключиться к серверу.');
        showScreen('onboarding');
    }
}

// ── Events ────────────────────────────────────────────────────────────────────

function bindEvents() {
    // Dismiss keyboard on tap-outside (BUG-08)
    document.addEventListener('touchstart', e => {
        if (document.activeElement?.id === 'bet-input' &&
            !e.target.closest('#bet-input')) {
            document.activeElement.blur();
        }
    }, { passive: true });

    // Risk
    $$('#risk-seg .seg-btn').forEach(btn => btn.addEventListener('click', () => {
        $$('#risk-seg .seg-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        risk = btn.dataset.val;
        board?.setRisk(risk);
        updateRtpLabel();
    }));

    // Rows
    $$('#rows-seg .seg-btn').forEach(btn => btn.addEventListener('click', () => {
        if (btn.disabled) return;
        $$('#rows-seg .seg-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        rows = parseInt(btn.dataset.val);
        board?.setRows(rows);
        updateRtpLabel();
    }));

    // Bet +/-
    $('bet-minus').addEventListener('click', () => setBet(betValue - BET_STEP, true));
    $('bet-plus').addEventListener('click',  () => setBet(betValue + BET_STEP, true));

    // Bet free input
    $('bet-input').addEventListener('blur', () => setBet(parseInt($('bet-input').value) || MIN_BET, false));

    // Multiplier chips (BUG-07)
    $$('.mult-btn').forEach(btn => btn.addEventListener('click', () => {
        const m = btn.dataset.mult;
        if (m === 'max') {
            if (balance < MIN_BET) { setResult('Недостаточно ракушек 🐚', 'loss'); return; }
            setBet(balance);
        } else {
            setBet(Math.round(betValue * parseFloat(m)));
        }
    }));

    $('throw-btn').addEventListener('click', handleThrow);
    $('auto-check').addEventListener('change', e => { if (e.target.checked) startAuto(); else stopAuto(); });
    $('turbo-check').addEventListener('change', e => board?.setFast(e.target.checked));

    $('lb-btn').addEventListener('click', openLeaderboard);
    $('lb-close').addEventListener('click', closeLeaderboard);
    $('lb-backdrop').addEventListener('click', closeLeaderboard);

    $('vip-btn').addEventListener('click', openVip);
    $('vip-open-btn').addEventListener('click', openVip);
    $('vip-close').addEventListener('click', closeVip);
    $('vip-backdrop').addEventListener('click', closeVip);
}

// ── Throw ─────────────────────────────────────────────────────────────────────

function handleThrow() {
    if (autoRunning) { stopAuto(); return; }
    doManualThrow();
}

async function doManualThrow() {
    if (!board || manualInFlight) return;
    if (balance < MIN_BET) { updateThrowBtn(); return; }
    manualInFlight = true;
    hapticImpact('medium');
    const bet = betValue;
    try {
        const res = await postPlay(bet, rows, risk);
        if (res.error) { showServerError(res.error); return; }
        const newBalance = res.newBalance;
        board.dropBall(res.path, res.slot, res.multiplier, res.profit, (mult, profit) => {
            balance = newBalance;
            updateBalance();
            showResult(mult, profit);
            applyVipDelta(bet, profit);
            scheduleBalanceSync();
        });
    } catch (e) {
        console.error('Play error', e);
        setResult('Ошибка сети 🌊', 'loss');
    } finally {
        manualInFlight = false;
    }
}

async function doAutoThrow() {
    if (!autoRunning || !board) return;
    autoInFlight = true;
    hapticImpact('light');
    try {
        const res = await postPlay(betValue, rows, risk);
        if (res.error) {
            showServerError(res.error);
            stopAuto(); return;
        }
        const newBalance = res.newBalance;
        const autoBet    = betValue;
        board.dropBall(res.path, res.slot, res.multiplier, res.profit, (mult, profit) => {
            balance = newBalance;
            updateBalance();
            showResult(mult, profit);
            applyVipDelta(autoBet, profit);
            autoInFlight = false;

            if (!autoRunning) { scheduleBalanceSync(); return; }
            autoCount--;
            updateThrowBtn();
            updateAutoProgress();
            if (autoCount <= 0) { stopAuto(); scheduleBalanceSync(); return; }
            setTimeout(doAutoThrow, 900);
        });
    } catch (e) {
        console.error('Auto play error', e);
        stopAuto();
    }
}

function showResult(multiplier, profit) {
    let type;
    if (multiplier >= 15) {
        type = 'jackpot';
        setResult(`🎰 ДЖЕКПОТ ×${multiplier.toFixed(0)}!`, 'jackpot');
        hapticNotify('success');
        triggerJackpotFlash();
    } else if (profit > 0) {
        type = 'win';
        setResult(`+${profit} 🐚  ×${multiplier.toFixed(1)}`, 'win');
        hapticNotify('success');
    } else if (profit === 0) {
        type = 'push';
        setResult(`Ничья ×${multiplier.toFixed(1)}`, 'neutral');
    } else {
        type = 'loss';
        setResult(`НЕ ПОВЕЗЛО  ×${multiplier.toFixed(1)}`, 'loss');
        hapticNotify('error');
    }
    pushResult(type);
}

// ── Auto ──────────────────────────────────────────────────────────────────────

function startAuto() {
    autoRunning  = true;
    autoCount    = AUTO_MAX;
    autoInFlight = false;
    updateThrowBtn();
    updateAutoProgress();
    doAutoThrow();
}

function stopAuto() {
    autoRunning  = false;
    autoInFlight = false;
    autoCount    = 0;
    $('auto-check').checked = false;
    updateThrowBtn();
    updateAutoProgress();
}

// ── Results strip ─────────────────────────────────────────────────────────────

function pushResult(type) {
    _results.unshift(type);
    if (_results.length > RESULTS_MAX) _results.pop();
    renderResultsStrip();
}

function renderResultsStrip() {
    const strip = $('results-strip');
    if (!strip) return;
    strip.innerHTML = '';
    for (let i = 0; i < RESULTS_MAX; i++) {
        const dot = document.createElement('div');
        dot.className = 'result-dot ' + (_results[i] || 'empty');
        strip.appendChild(dot);
    }
}

// ── Jackpot flash ─────────────────────────────────────────────────────────────

function triggerJackpotFlash() {
    const el = $('jackpot-flash');
    if (!el) return;
    el.classList.remove('active');
    void el.offsetWidth;   // force reflow to restart animation
    el.classList.add('active');
}

// ── UI helpers ────────────────────────────────────────────────────────────────

function updateUI() {
    setBet(betValue);
    $('balance-num').textContent  = balance;
    $('balance-hint').textContent = balance;
    _shownBal = balance;
    updateThrowBtn();
    updateAutoProgress();
    updateRtpLabel();
}

function setBet(v, stepSnap = false) {
    betValue = clampBet(v, stepSnap);
    $('bet-input').value = betValue;
}

/**
 * Animate balance display from _shownBal → balance over ~400 ms.
 * Uses ease-out cubic so the number settles smoothly at the target.
 */
function updateBalance() {
    const from = _shownBal;
    const to   = balance;
    if (from === to) { updateThrowBtn(); return; }
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
        if (t < 1) {
            _balRaf = requestAnimationFrame(step);
        } else {
            $('balance-num').textContent  = to;
            $('balance-hint').textContent = to;
            _shownBal = to;
            updateThrowBtn();  // refresh broke state after balance settles
        }
    }
    _balRaf = requestAnimationFrame(step);
    updateThrowBtn();  // also refresh immediately (might cross MIN_BET)
}

function updateAutoProgress() {
    const prog = $('auto-progress');
    const bar  = $('auto-bar');
    if (!prog || !bar) return;
    if (autoRunning) {
        prog.style.display = 'block';
        bar.style.width = (autoCount / AUTO_MAX * 100) + '%';
    } else {
        prog.style.display = 'none';
        bar.style.width = '100%';
    }
}

function updateThrowBtn() {
    const btn = $('throw-btn');
    if (!btn) return;
    if (autoRunning) {
        btn.textContent = `■ СТОП (${autoCount}x)`;
        btn.className   = 'auto-running';
        btn.disabled    = false;
    } else if (balance < MIN_BET) {
        btn.textContent = 'Пополни баланс 🐚';
        btn.className   = 'broke';
        btn.disabled    = true;
    } else {
        btn.textContent = 'БРОСИТЬ';
        btn.className   = '';
        btn.disabled    = false;
    }
}

/**
 * Debounced balance sync (BUG-14: sequence counter prevents stale overwrites).
 * Also refreshes VIP state (tier may have changed server-side).
 */
function scheduleBalanceSync() {
    clearTimeout(_syncTimer);
    const seq = ++_syncSeq;
    _syncTimer = setTimeout(async () => {
        try {
            const s = await fetchState();
            if (seq !== _syncSeq) return;
            if (typeof s.balance === 'number' && s.balance !== balance) {
                balance = s.balance;
                updateBalance();
            }
            // Sync VIP data (confirmed values from server, e.g. tier upgrade)
            if (s.vipTier != null) {
                _vipState = s;
                refreshVipDisplay();
            }
        } catch { /* silent — best-effort */ }
    }, 500);
}

/**
 * Optimistic VIP update — called immediately after each successful play so the
 * user sees numbers moving without waiting for the balance-sync round-trip.
 * @param {number} bet  shells wagered
 * @param {number} profit  net profit (negative = loss)
 */
function applyVipDelta(bet, profit) {
    if (!_vipState) return;
    const won     = bet + profit;
    const netLoss = bet - won;   // positive when player lost, negative when they won

    _vipState = {
        ..._vipState,
        vipLifetimeWager:  (_vipState.vipLifetimeWager  || 0) + bet,
        vipPeriodNetLoss:  Math.max(0, (_vipState.vipPeriodNetLoss || 0) + netLoss),
    };
    // Recalculate estimated cashback from updated period loss
    const tier    = VIP_TIERS.find(t => t.id === _vipState.vipTier) || VIP_TIERS[0];
    _vipState.vipEstimatedCashback = Math.floor(_vipState.vipPeriodNetLoss * tier.cb / 100);

    // Next-tier threshold: stay in sync
    const tierIdx = VIP_TIERS.indexOf(tier);
    const next    = VIP_TIERS[tierIdx + 1];
    _vipState.vipNextTierThreshold = next ? next.threshold : null;

    refreshVipDisplay();
}

/** Updates the topbar badge and re-renders the VIP sheet if it's currently open. */
function refreshVipDisplay() {
    updateVipBadge();
    if ($('vip-overlay')?.classList.contains('open')) renderVipSheet();
}

function updateRtpLabel() {
    const el = $('rtp-label');
    if (!el) return;
    const rtp = RTP_TABLE[rows]?.[risk];
    el.textContent = rtp ? `возврат ~${rtp}%` : '';
}

/** Maps server error codes to short, friendly messages. */
function showServerError(code) {
    const MAP = {
        COOLDOWN:             ['⏱ Секунду...', 'neutral'],
        INSUFFICIENT_BALANCE: ['Недостаточно ракушек 🐚', 'loss'],
        ROWS_LOCKED:          ['Открывается с 5 ур. рыбака', 'neutral'],
        INVALID_BET:          ['Неверная ставка', 'neutral'],
    };
    const [text, type] = MAP[code] ?? ['Что-то пошло не так 🌊', 'neutral'];
    setResult(text, type);
}

function setResult(text, type) {
    const el  = $('result-text');
    const bar = $('result-bar');
    el.textContent = text;
    el.className   = type;

    // Colored border glow on bar
    bar.classList.remove('bar-win', 'bar-loss', 'bar-jackpot');
    if (type === 'win')     bar.classList.add('bar-win');
    if (type === 'loss')    bar.classList.add('bar-loss');
    if (type === 'jackpot') bar.classList.add('bar-jackpot');

    // Show pill, then auto-hide after 2.5 s
    bar.classList.add('visible');
    clearTimeout(_resultTimer);
    if (type !== 'neutral') {
        _resultTimer = setTimeout(() => bar.classList.remove('visible'), 2500);
    }
}

// ── VIP ───────────────────────────────────────────────────────────────────────

const VIP_TIERS = [
    { id: 'NONE',  emoji: '',   name: 'Нет',    cb: 0,  threshold: 0,       color: '#9ca3af' },
    { id: 'CORAL', emoji: '🪸', name: 'Коралл', cb: 3,  threshold: 5000,    color: '#e07857' },
    { id: 'PEARL', emoji: '🦪', name: 'Жемчуг', cb: 6,  threshold: 25000,   color: '#7c6af5' },
    { id: 'REEF',  emoji: '👑', name: 'Риф',    cb: 10, threshold: 100000,  color: '#d4a017' },
];

function updateVipBadge() {
    const badge  = $('vip-badge');
    const btnEl  = badge?.closest('.vip-badge-btn');
    if (!badge || !_vipState) return;
    const tier = VIP_TIERS.find(t => t.id === _vipState.vipTier) || VIP_TIERS[0];
    if (tier.id === 'NONE') {
        badge.textContent     = '🐚 VIP';
        badge.style.color     = '';
        if (btnEl) btnEl.style.borderColor = '';
    } else {
        badge.textContent     = `${tier.emoji} ${tier.name}`;
        badge.style.color     = tier.color;
        if (btnEl) btnEl.style.borderColor = `${tier.color}88`;
    }
}

function openVip() {
    $('vip-overlay').classList.add('open');
    renderVipSheet();
}

function closeVip() {
    $('vip-overlay').classList.remove('open');
}

function renderVipSheet() {
    const content = $('vip-content');
    if (!content || !_vipState) return;

    const v      = _vipState;
    const tier   = VIP_TIERS.find(t => t.id === v.vipTier) || VIP_TIERS[0];
    const wager  = v.vipLifetimeWager || 0;

    // Progress to next tier
    let progressHtml = '';
    const nextTier = VIP_TIERS[tier === VIP_TIERS[3] ? 3 : VIP_TIERS.indexOf(tier) + 1];
    if (tier.id !== 'REEF') {
        const prev  = tier.threshold;
        const gap   = nextTier.threshold - prev;
        const done  = Math.min(wager - prev, gap);
        const pct   = Math.max(0, Math.min(100, (done / gap) * 100));
        progressHtml = `
        <div class="vip-progress-wrap">
            <div class="vip-progress-labels">
                <span>${tier.emoji || '○'} ${tier.name}</span>
                <span>${nextTier.emoji} ${nextTier.name}</span>
            </div>
            <div class="vip-progress-bar">
                <div class="vip-progress-fill" style="width:${pct}%;background:${nextTier.color}"></div>
            </div>
            <div class="vip-progress-sub">${wager.toLocaleString('ru')} / ${nextTier.threshold.toLocaleString('ru')} 🐚 оборота</div>
        </div>`;
    } else {
        progressHtml = `<div class="vip-max-label">🏆 Максимальный статус достигнут</div>`;
    }

    // Cashback chip
    const loss      = v.vipPeriodNetLoss || 0;
    const estimated = v.vipEstimatedCashback || 0;
    const cashbackHtml = v.vipTier === 'NONE'
        ? `<div class="vip-cashback-locked">Кешбэк доступен со статуса 🪸 Коралл</div>`
        : `<div class="vip-cashback-row">
            <div class="vip-cashback-cell">
                <div class="vip-cashback-num">${loss.toLocaleString('ru')} 🐚</div>
                <div class="vip-cashback-label">чистый минус за период</div>
            </div>
            <div class="vip-cashback-arrow">→</div>
            <div class="vip-cashback-cell">
                <div class="vip-cashback-num" style="color:${tier.color}">+${estimated.toLocaleString('ru')} 🐚</div>
                <div class="vip-cashback-label">кешбэк ${tier.cb}% (${v.vipNextCashbackDate || '?'})</div>
            </div>
          </div>`;

    // Tiers table
    const tiersHtml = VIP_TIERS.slice(1).map(t => {
        const active   = t.id === v.vipTier;
        const reached  = VIP_TIERS.indexOf(tier) >= VIP_TIERS.indexOf(t);
        const rowStyle = active ? `border-color:${t.color}55;background:${t.color}08` : '';
        return `<div class="vip-tier-row${active ? ' active' : ''}${reached ? ' reached' : ''}" style="${rowStyle}">
            <div class="vip-tier-icon">${t.emoji}</div>
            <div class="vip-tier-info">
                <div class="vip-tier-name" style="color:${t.color}">${t.name}</div>
                <div class="vip-tier-req">от ${t.threshold.toLocaleString('ru')} 🐚 оборота</div>
            </div>
            <div class="vip-tier-cb" style="color:${t.color}">+${t.cb}%</div>
        </div>`;
    }).join('');

    content.innerHTML = `
        <div class="vip-hero" style="border-color:${tier.color}44; background:${tier.color}11">
            <div class="vip-hero-icon">${tier.emoji || '🐚'}</div>
            <div class="vip-hero-name" style="color:${tier.color}">${tier.id === 'NONE' ? 'Нет статуса' : tier.name}</div>
            <div class="vip-hero-wager">Оборот: ${wager.toLocaleString('ru')} 🐚</div>
        </div>
        ${progressHtml}
        <div class="vip-section-title">Кешбэк периода</div>
        ${cashbackHtml}
        <div class="vip-section-title">Статусы</div>
        <div class="vip-tiers-list">${tiersHtml}</div>
        <div class="vip-footer-note">Статус постоянный и не сгорает. Кешбэк выплачивается пн и чт в 00:00 на чистый минус за период.</div>
    `;
}

function showScreen(name) {
    $('screen-loading').style.display    = name === 'loading'    ? 'flex' : 'none';
    $('screen-onboarding').style.display = name === 'onboarding' ? 'flex' : 'none';
    $('app').style.display               = name === 'app'        ? 'flex' : 'none';
}

// ── Leaderboard ───────────────────────────────────────────────────────────────

function openLeaderboard() {
    $('lb-overlay').classList.add('open');
    loadLeaderboard();
}

function closeLeaderboard() {
    $('lb-overlay').classList.remove('open');
}

async function loadLeaderboard() {
    const content = $('lb-content');
    content.innerHTML = '<div class="lb-placeholder">⏳ Загрузка...</div>';
    try {
        const data = await fetchLeaderboard();
        renderLeaderboard(data, content);
    } catch {
        content.innerHTML = '<div class="lb-empty">Не удалось загрузить рекорды</div>';
    }
}

const MEDALS = ['🥇', '🥈', '🥉'];

function renderLeaderboard(data, container) {
    if (!data.topWin?.length) {
        container.innerHTML = '<div class="lb-empty">🪷 Пока нет записей. Сыграй первым!</div>';
        return;
    }

    const section = (title, entries, type) => {
        const rowsHtml = entries.slice(0, 10).map((e, i) => {
            const val = type === 'win'
                ? `<span class="lb-val">+${e.profit} 🐚</span>`
                : `<span class="lb-val">×${(+e.multiplier).toFixed(0)}</span>`;
            const sub = type === 'win'
                ? `×${(+e.multiplier).toFixed(0)}`
                : `+${e.profit} 🐚`;
            return `<div class="lb-row">
                <span class="lb-rank">${MEDALS[i] || (i + 1)}</span>
                <span class="lb-name">${escHtml(e.username || '?')}</span>
                ${val}
                <span class="lb-sub">${escHtml(sub)}</span>
            </div>`;
        }).join('');
        return `<div class="lb-section">
            <div class="lb-section-title">${title}</div>
            ${rowsHtml}
        </div>`;
    };

    // BUG-21: guard missing topMultiplier
    const multSection = data.topMultiplier?.length
        ? section('🎯 Лучший множитель', data.topMultiplier, 'mult')
        : '';

    container.innerHTML =
        section('💰 Лучший выигрыш', data.topWin, 'win') +
        multSection;
}
