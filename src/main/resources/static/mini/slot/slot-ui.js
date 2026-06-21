/**
 * The Reef House — UI controller v3
 *
 * Features:
 *  - Auto-spin (100x), Turbo mode
 *  - Skip animation on re-click after server responds
 *  - Bonus buy (100× bet → 10 Free Spins)
 *  - VIP badge in topbar
 *  - Paytable bottom sheet
 */

import { setInitData, fetchState, postSpin, postBuyBonus } from './slot-api.js?v=2';

// ── Symbol catalogue ──────────────────────────────────────────────────────────

const SYM = {
    FISH_CLOWN:  { e: '🐠' }, FISH_PUFFER: { e: '🐡' }, SHRIMP: { e: '🦐' },
    FISH_BLUE:   { e: '🐟' }, CRAB:        { e: '🦀' }, OCTOPUS: { e: '🐙' },
    SQUID:       { e: '🦑' }, SHARK:       { e: '🦈' },
    WILD:        { e: '🌊' }, SCATTER:     { e: '🏺' },
};
const SYM_KEYS      = Object.keys(SYM);
const SYM_HEIGHT    = 76;
const REEL_COUNT    = 5;
const SPIN_ROWS     = 22;
const STAGGER_MS    = 160;
const TURBO_STAGGER = 40;
const SNAP_DUR      = 450;
const TURBO_SNAP    = 120;
const AUTO_MAX      = 100;
const MIN_BET       = 10;
const BET_STEP      = 10;
const BONUS_MULT    = 100;

// ── VIP tiers ─────────────────────────────────────────────────────────────────

const VIP_TIERS = [
    { id: 'NONE',  emoji: '',   name: 'Нет',    cb: 0,  threshold: 0,
      color: '#9ca3af', heroBg: '#f5f5f7', heroBorder: '#e5e5ea',
      heroGlow: 'transparent', heroShadow: 'rgba(0,0,0,.06)', heroText: '#1c1c1e' },
    { id: 'CORAL', emoji: '🪸', name: 'Коралл', cb: 3,  threshold: 5000,
      color: '#e07857', heroBg: '#fff5f2', heroBorder: '#f2c4b0',
      heroGlow: '#f4a07a', heroShadow: 'rgba(224,120,87,.18)', heroText: '#c45a31' },
    { id: 'PEARL', emoji: '🦪', name: 'Жемчуг', cb: 6,  threshold: 25000,
      color: '#7c6af5', heroBg: '#f5f3ff', heroBorder: '#c4bafa',
      heroGlow: '#a596f8', heroShadow: 'rgba(124,106,245,.20)', heroText: '#5b48d9' },
    { id: 'REEF',  emoji: '👑', name: 'Риф',    cb: 10, threshold: 100000,
      color: '#c8850a', heroBg: '#fffbf0', heroBorder: '#f0d080',
      heroGlow: '#f0c040', heroShadow: 'rgba(200,133,10,.22)', heroText: '#a06a00' },
];

// ── State ─────────────────────────────────────────────────────────────────────

let _tg = null, _vipState = null;
let balance = 0, _shownBal = 0, _balRaf = null;
let betValue = 10;
let spinning = false, _serverResult = null, _skipRequested = false;
let turboMode = false;
let autoRunning = false, autoCount = 0, autoInFlight = false;

const $ = id => document.getElementById(id);
const $$ = sel => document.querySelectorAll(sel);

// ── Init ──────────────────────────────────────────────────────────────────────

export async function init() {
    const tg = window.Telegram?.WebApp;
    _tg = tg || null;
    if (tg) { tg.ready(); tg.expand(); setInitData(tg.initData); }
    else setInitData('');
    showScreen('loading');
    try {
        const st = await fetchState();
        if (st.onboardingRequired) {
            $('ob-text').textContent = st.message || 'Сначала зарегистрируйся в боте.';
            showScreen('onboarding'); return;
        }
        balance = st.balance; _shownBal = balance; _vipState = st;
        bindEvents();
        renderReels(null);
        updateUI();
        updateVipBadge();
        showFsBanner(st.freeSpinsRemaining, st.multiplier);
        showScreen('app');
    } catch (e) { console.error('Init', e); showScreen('onboarding'); }
}

// ── Events ────────────────────────────────────────────────────────────────────

function bindEvents() {
    $('bet-minus').addEventListener('click', () => setBet(betValue - BET_STEP, true));
    $('bet-plus') .addEventListener('click', () => setBet(betValue + BET_STEP, true));
    $('bet-input').addEventListener('blur',  () => setBet(parseInt($('bet-input').value) || MIN_BET));
    $$('.mult-btn').forEach(b => b.addEventListener('click', () => {
        const m = b.dataset.mult;
        setBet(m === 'max' ? balance : Math.round(betValue * parseFloat(m)));
    }));
    $('spin-btn')    .addEventListener('click', handleSpin);
    $('auto-btn')    .addEventListener('click', () => autoRunning ? stopAuto() : startAuto());
    $('turbo-btn')   .addEventListener('click', toggleTurbo);
    $('paytable-btn').addEventListener('click', openPaytable);
    $('pt-close')    .addEventListener('click', closePaytable);
    $('pt-backdrop') .addEventListener('click', closePaytable);
    $('bonus-btn')      .addEventListener('click', handleBonusBuy);
    $('game-title-btn') .addEventListener('click', openGameSelector);
    $('gs-close')       .addEventListener('click', closeGameSelector);
    $('gs-backdrop')    .addEventListener('click', closeGameSelector);
    $('gs-slot')        .addEventListener('click', closeGameSelector);
    $('gs-plinko')      .addEventListener('click', () => { window.location.href = '/mini/plinko/'; });
    $('vip-btn')        .addEventListener('click', openVip);
    $('vip-close')      .addEventListener('click', closeVip);
    $('vip-backdrop')   .addEventListener('click', closeVip);
    document.addEventListener('touchstart', e => {
        if (document.activeElement?.id === 'bet-input' && !e.target.closest('#bet-input'))
            document.activeElement.blur();
    }, { passive: true });
}

// ── Spin ──────────────────────────────────────────────────────────────────────

function handleSpin() {
    if (spinning && _serverResult) { _skipRequested = true; return; }
    if (spinning) return;
    if (autoRunning) { stopAuto(); return; }
    doSpin(false);
}

async function doSpin(isAuto) {
    const inFS = (_vipState?.freeSpinsRemaining ?? 0) > 0;
    if (!inFS && balance < MIN_BET) { updateSpinBtn(); return; }

    spinning = true; _serverResult = null; _skipRequested = false;
    haptic(isAuto ? 'light' : 'medium');
    updateSpinBtn(); clearWinOverlay();

    if (!inFS) { balance = Math.max(0, balance - betValue); animateBalance(balance); }
    startAllReels();

    try {
        const res = await postSpin(betValue);
        if (res.error) {
            if (!inFS) { balance += betValue; animateBalance(balance); }
            stopAllInstant([]);
            showWin(errTxt(res.error)); return;
        }
        _serverResult = res;
        await stopReels(res.grid);
        applyResult(res);

        if (isAuto) {
            if (!autoRunning) { scheduleSync(); return; }
            autoCount--;
            updateAutoProgress(); updateSpinBtn();
            if (autoCount <= 0 || balance < MIN_BET) { stopAuto(); scheduleSync(); return; }
            setTimeout(() => doSpin(true), turboMode ? 300 : 700);
        } else {
            scheduleSync();
        }
    } catch (e) {
        console.error('Spin error', e);
        if (!inFS) { balance += betValue; animateBalance(balance); }
        showWin('Ошибка сети 🌊');
        if (isAuto) stopAuto();
    } finally {
        spinning = false; _serverResult = null; _skipRequested = false;
        autoInFlight = false;
        updateSpinBtn(); updateBonusBtn();
    }
}

function applyResult(res) {
    balance = res.newBalance; _vipState = { ..._vipState, ...res };
    animateBalance(balance); updateVipBadge();
    showFsBanner(res.freeSpinsRemaining, res.multiplier);
    if (res.isFreeSpinTrigger) {
        haptic('success');
        const n = res.scatterCount === 3 ? 10 : res.scatterCount === 4 ? 15 : 20;
        showWin(`🏺 БОНУС! ${n} Free Spins!`, 'bonus');
    } else if (res.totalWin > 0) {
        haptic('success');
        const lbl = (res.wasFreeSpins && res.multiplier > 1)
            ? `+${res.totalWin} 🐚  ×${res.multiplier}` : `+${res.totalWin} 🐚`;
        showWin(lbl, 'win'); highlightWins(res.wins);
    } else {
        showWin('Не повезло', 'loss');
    }
}

// ── Bonus buy ─────────────────────────────────────────────────────────────────

async function handleBonusBuy() {
    if (spinning) return;
    if ((_vipState?.freeSpinsRemaining ?? 0) > 0) { showWin('Уже в бонусе 🏺'); return; }
    const cost = betValue * BONUS_MULT;
    if (balance < cost) { showWin(`Нужно ${cost} 🐚`, 'loss'); return; }
    haptic('medium'); spinning = true; updateSpinBtn();
    try {
        const res = await postBuyBonus(betValue);
        if (res.error) { showWin(errTxt(res.error)); return; }
        balance = res.newBalance; _vipState = { ..._vipState, ...res };
        animateBalance(balance); updateVipBadge();
        showFsBanner(res.freeSpinsRemaining, res.multiplier);
        showWin('🏺 Куплено! 10 Free Spins', 'bonus');
    } catch (e) { console.error('Bonus buy', e); showWin('Ошибка сети 🌊'); }
    finally { spinning = false; updateSpinBtn(); updateBonusBtn(); }
}

// ── Auto-spin ─────────────────────────────────────────────────────────────────

function startAuto() {
    autoRunning = true; autoCount = AUTO_MAX; autoInFlight = false;
    $('auto-btn').classList.add('active');
    updateSpinBtn(); updateAutoProgress();
    doSpin(true);
}

function stopAuto() {
    autoRunning = false; autoInFlight = false; autoCount = 0;
    $('auto-btn').classList.remove('active');
    updateSpinBtn(); updateAutoProgress();
}

function toggleTurbo() {
    turboMode = !turboMode;
    $('turbo-btn').classList.toggle('active', turboMode);
    haptic('light');
}

// ── Reel animation ────────────────────────────────────────────────────────────

function startAllReels() {
    for (let r = 0; r < REEL_COUNT; r++) { buildStrip(r, null); startReelSpin(r); }
}

async function stopReels(grid) {
    _skipRequested = false;
    const stagger = turboMode ? TURBO_STAGGER : STAGGER_MS;
    for (let r = 0; r < REEL_COUNT; r++) {
        if (_skipRequested) { for (let i = r; i < REEL_COUNT; i++) snapInstant(i, grid[i]); return; }
        await sleep(stagger);
        if (_skipRequested) { for (let i = r; i < REEL_COUNT; i++) snapInstant(i, grid[i]); return; }
        await snapReel(r, grid[r]);
        haptic('light');
    }
    await sleep(turboMode ? 60 : 180);
}

function stopAllInstant(grid) {
    const g = grid.length ? grid : Array.from({ length: 5 }, () => [rndSym(), rndSym(), rndSym()]);
    for (let r = 0; r < REEL_COUNT; r++) snapInstant(r, g[r] || [rndSym(), rndSym(), rndSym()]);
}

function buildStrip(r, finals) {
    const strip = $(`strip-${r}`);
    if (!strip) return;
    const syms = Array.from({ length: SPIN_ROWS }, rndSym);
    if (finals) syms.push(...finals);
    strip.innerHTML = syms.map(s => `<div class="slot-sym">${SYM[s]?.e ?? '?'}</div>`).join('');
    strip.style.transition = 'none';
    strip.style.transform  = 'translateY(0)';
    strip.offsetHeight;
}

function startReelSpin(r) { $(`strip-${r}`)?.classList.add('spinning'); }

async function snapReel(r, finals3) {
    const strip = $(`strip-${r}`);
    if (!strip) return;
    strip.classList.remove('spinning');
    buildStrip(r, finals3);
    const dur = turboMode ? TURBO_SNAP : SNAP_DUR;
    strip.style.transition = `transform ${dur}ms cubic-bezier(.22,.8,.4,1)`;
    strip.style.transform  = `translateY(${-(SPIN_ROWS * SYM_HEIGHT)}px)`;
    await sleep(dur + 50);
    strip.style.transition = 'none';
    strip.style.transform  = 'translateY(0)';
    strip.innerHTML = finals3.map(s => `<div class="slot-sym">${SYM[s]?.e ?? '?'}</div>`).join('');
    strip.offsetHeight;
}

function snapInstant(r, finals3) {
    const strip = $(`strip-${r}`);
    if (!strip) return;
    strip.classList.remove('spinning');
    strip.style.transition = 'none';
    strip.style.transform  = 'translateY(0)';
    if (finals3?.length)
        strip.innerHTML = finals3.map(s => `<div class="slot-sym">${SYM[s]?.e ?? '?'}</div>`).join('');
    strip.offsetHeight;
}

function renderReels(grid) {
    for (let r = 0; r < REEL_COUNT; r++) {
        const strip = $(`strip-${r}`); if (!strip) continue;
        const syms = grid ? grid[r] : [rndSym(), rndSym(), rndSym()];
        strip.innerHTML = syms.map(s => `<div class="slot-sym">${SYM[s]?.e ?? '?'}</div>`).join('');
        strip.style.transition = 'none'; strip.style.transform = 'translateY(0)';
    }
}

// ── Win display ───────────────────────────────────────────────────────────────

function highlightWins(wins) {
    if (!wins?.length) return;
    wins.forEach(w => {
        for (let r = 0; r < REEL_COUNT; r++) {
            const cells = $(`strip-${r}`)?.querySelectorAll('.slot-sym');
            if (cells?.[w.rows[r]]) cells[w.rows[r]].classList.add('winning');
        }
    });
    setTimeout(clearWinOverlay, 1500);
}

function clearWinOverlay() { $$('.slot-sym.winning').forEach(el => el.classList.remove('winning')); }

function showWin(text, type = 'neutral') {
    const el = $('win-label'), bar = $('win-bar');
    if (!el || !bar) return;
    el.textContent = text;
    bar.className  = type;
    bar.classList.add('visible');
    clearTimeout(bar._t);
    if (type !== 'neutral') bar._t = setTimeout(() => bar.classList.remove('visible'), 2500);
}

function showFsBanner(remaining, mult) {
    const banner = $('fs-banner'), zone = $('slot-zone');
    if (!banner || !zone) return;
    if (remaining > 0) {
        $('fs-count').textContent = remaining;
        $('fs-mult').textContent  = mult > 1 ? `×${mult}` : '';
        banner.classList.add('visible'); zone.classList.add('free-spins');
    } else {
        banner.classList.remove('visible'); zone.classList.remove('free-spins');
    }
}

function openPaytable()  { $('pt-overlay').classList.add('open');    }
function closePaytable() { $('pt-overlay').classList.remove('open'); }

// ── Game selector ─────────────────────────────────────────────────────────────

function openGameSelector() {
    haptic('light');
    $('gs-overlay').classList.add('open');
}

function closeGameSelector() {
    $('gs-overlay').classList.remove('open');
}

// ── VIP sheet ─────────────────────────────────────────────────────────────────

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

    const v       = _vipState;
    const tier    = VIP_TIERS.find(t => t.id === v.vipTier) || VIP_TIERS[0];
    const tierIdx = VIP_TIERS.indexOf(tier);
    const wager   = v.vipLifetimeWager || 0;

    const heroStyle = [
        `--hero-bg:${tier.heroBg}`,
        `--hero-border:${tier.heroBorder}`,
        `--hero-glow:${tier.heroGlow}`,
        `--hero-shadow:${tier.heroShadow}`,
        `--hero-text:${tier.heroText}`,
    ].join(';');

    const heroHtml = `
    <div class="vip-hero" style="${heroStyle}">
        <div class="vip-hero-icon">${tier.emoji || '🐚'}</div>
        <div class="vip-hero-name">${tier.id === 'NONE' ? 'Нет статуса' : tier.name}</div>
        <div class="vip-hero-wager">оборот: ${wager.toLocaleString('ru')} 🐚</div>
    </div>`;

    let progressHtml = '';
    if (tier.id === 'REEF') {
        progressHtml = `<div class="vip-max-badge">🏆 Максимальный статус достигнут</div>`;
    } else {
        const next    = VIP_TIERS[tierIdx + 1];
        const prev    = tier.threshold;
        const gap     = next.threshold - prev;
        const done    = Math.max(0, Math.min(wager - prev, gap));
        const pct     = Math.max(2, Math.min(100, (done / gap) * 100));
        const left    = (next.threshold - wager).toLocaleString('ru');
        const fillBg  = `linear-gradient(90deg, ${tier.id === 'NONE' ? '#c8c8d0' : tier.color}, ${next.color})`;
        progressHtml = `
        <div class="vip-group">
            <div class="vip-group-title">До следующего статуса</div>
            <div class="vip-progress-card">
                <div class="vip-progress-heads">
                    <div class="vip-progress-tier" style="color:${tier.id === 'NONE' ? 'var(--text2)' : tier.color}">
                        ${tier.emoji || '○'} ${tier.name}
                    </div>
                    <div class="vip-progress-tier" style="color:${next.color}">
                        ${next.emoji} ${next.name}
                    </div>
                </div>
                <div class="vip-progress-track">
                    <div class="vip-progress-fill" style="width:${pct}%;background:${fillBg}"></div>
                </div>
                <div class="vip-progress-meta">ещё ${left} 🐚 до ${next.emoji} ${next.name}</div>
            </div>
        </div>`;
    }

    const loss      = v.vipPeriodNetLoss      || 0;
    const estimated = v.vipEstimatedCashback  || 0;
    const payDate   = v.vipNextCashbackDate   || '?';

    let cashbackHtml = '';
    if (tier.id === 'NONE') {
        cashbackHtml = `
        <div class="vip-group">
            <div class="vip-group-title">Кешбэк периода</div>
            <div class="vip-cb-card">
                <div class="vip-cb-locked">Кешбэк доступен с&nbsp;🪸&nbsp;Коралл<br>Тратьте и&nbsp;мы&nbsp;вернём часть потерь</div>
            </div>
        </div>`;
    } else {
        cashbackHtml = `
        <div class="vip-group">
            <div class="vip-group-title">Кешбэк периода</div>
            <div class="vip-cb-card" style="--cb-color:${tier.color}">
                <div class="vip-cb-top">
                    <div class="vip-cb-col">
                        <div class="vip-cb-col-label">Потери</div>
                        <div class="vip-cb-num">${loss.toLocaleString('ru')} 🐚</div>
                        <div class="vip-cb-sub">чистый минус</div>
                    </div>
                    <div class="vip-cb-divider"></div>
                    <div class="vip-cb-col">
                        <div class="vip-cb-col-label">Кешбэк ${tier.cb}%</div>
                        <div class="vip-cb-num earn">+${estimated.toLocaleString('ru')} 🐚</div>
                        <div class="vip-cb-sub">к выплате</div>
                    </div>
                </div>
                <div class="vip-cb-bottom">
                    <div class="vip-cb-payout-label">Следующая выплата</div>
                    <div class="vip-cb-payout-date">${payDate}</div>
                </div>
            </div>
        </div>`;
    }

    const tiersHtml = VIP_TIERS.slice(1).map(t => {
        const active  = t.id === tier.id;
        const reached = tierIdx >= VIP_TIERS.indexOf(t);
        const cls     = ['vip-tier-row', active ? 'active' : '', reached ? 'reached' : ''].filter(Boolean).join(' ');
        let rowStyle  = `--tier-accent:${t.color};`;
        if (active)        rowStyle += `--tier-bg:${t.heroBg};--tier-border:${t.heroBorder};--tier-shadow:${t.heroShadow};--tier-text:${t.heroText};--tier-sub:${t.heroText}88;`;
        else if (reached)  rowStyle += `--tier-text:${t.heroText};--tier-sub:var(--text2);`;
        else               rowStyle += `--tier-text:var(--text2);--tier-sub:var(--text2);`;
        const check = reached
            ? `<div class="vip-tier-check">${active ? 'Текущий' : '✓'}</div>`
            : '';
        return `<div class="${cls}" style="${rowStyle}">
            <div class="vip-tier-icon">${t.emoji}</div>
            <div class="vip-tier-info">
                <div class="vip-tier-name">${t.name}</div>
                <div class="vip-tier-req">от ${t.threshold.toLocaleString('ru')} 🐚</div>
            </div>
            <div class="vip-tier-right">
                <div class="vip-tier-cb">+${t.cb}%</div>
                ${check}
            </div>
        </div>`;
    }).join('');

    content.innerHTML = `
        ${heroHtml}
        ${progressHtml}
        ${cashbackHtml}
        <div class="vip-group">
            <div class="vip-group-title">Статусы и кешбэк</div>
            <div class="vip-tiers-list">${tiersHtml}</div>
        </div>
        <div class="vip-footer-note">Статус постоянный — не сгорает с&nbsp;уровнем. Кешбэк выплачивается каждый понедельник и&nbsp;четверг в&nbsp;00:00 на&nbsp;чистый минус за&nbsp;период.</div>
    `;
}

function updateVipBadge() {
    const badge = $('vip-badge'), btn = badge?.closest('.vip-badge-btn');
    if (!badge || !_vipState) return;
    const tier = VIP_TIERS.find(t => t.id === _vipState.vipTier) || VIP_TIERS[0];
    if (tier.id === 'NONE') {
        badge.textContent = '🐚 VIP'; badge.style.color = '';
        if (btn) { btn.style.borderColor = ''; btn.style.background = ''; }
    } else {
        badge.textContent = `${tier.emoji} ${tier.name}`; badge.style.color = tier.heroText;
        if (btn) { btn.style.borderColor = tier.heroBorder; btn.style.background = tier.heroBg; }
    }
}

function updateUI() {
    setBet(betValue);
    $('balance-num').textContent = $('balance-hint').textContent = balance;
    _shownBal = balance;
    updateSpinBtn(); updateBonusBtn(); updateAutoProgress();
}

function setBet(v, snap = false) {
    let n = parseInt(v) || MIN_BET;
    n = Math.max(MIN_BET, Math.min(balance || MIN_BET, n));
    if (snap) n = Math.round(n / BET_STEP) * BET_STEP || MIN_BET;
    betValue = n; $('bet-input').value = betValue;
    updateBonusBtn();
}

function updateSpinBtn() {
    const btn = $('spin-btn'); if (!btn) return;
    const inFS = (_vipState?.freeSpinsRemaining ?? 0) > 0;
    if (spinning && _serverResult) {
        btn.textContent = '⏭ ПРОПУСТИТЬ'; btn.className = ''; btn.disabled = false; return;
    }
    if (autoRunning) {
        btn.textContent = `■ СТОП (${autoCount}x)`; btn.className = 'auto-running'; btn.disabled = false; return;
    }
    if (spinning) { btn.textContent = '▪▪▪'; btn.className = ''; btn.disabled = true; return; }
    if (inFS)     { btn.textContent = 'FREE SPIN 🏺'; btn.className = 'free'; btn.disabled = false; return; }
    if (balance < MIN_BET) { btn.textContent = 'Пополни баланс 🐚'; btn.className = 'broke'; btn.disabled = true; return; }
    btn.textContent = 'КРУТИТЬ'; btn.className = ''; btn.disabled = false;
}

function updateBonusBtn() {
    const btn = $('bonus-btn'), cost = $('bonus-cost'); if (!btn || !cost) return;
    const inFS = (_vipState?.freeSpinsRemaining ?? 0) > 0;
    const c = betValue * BONUS_MULT;
    cost.textContent = `×100 = ${c} 🐚`;
    btn.disabled = inFS || balance < c || spinning;
    btn.style.opacity = btn.disabled ? '.4' : '';
}

function updateAutoProgress() {
    const prog = $('auto-progress'), bar = $('auto-bar'); if (!prog || !bar) return;
    prog.style.display = autoRunning ? 'block' : 'none';
    bar.style.width = autoRunning ? (autoCount / AUTO_MAX * 100) + '%' : '100%';
}

function animateBalance(to) {
    const from = _shownBal;
    if (from === to) { $('balance-num').textContent = $('balance-hint').textContent = to; return; }
    if (_balRaf) cancelAnimationFrame(_balRaf);
    const dur = Math.max(180, Math.min(500, Math.abs(to - from) * 1.2));
    const start = performance.now();
    function step(now) {
        const t = Math.min((now - start) / dur, 1);
        const e = 1 - Math.pow(1 - t, 3);
        const v = Math.round(from + (to - from) * e);
        $('balance-num').textContent = $('balance-hint').textContent = v;
        _shownBal = v;
        if (t < 1) _balRaf = requestAnimationFrame(step);
        else { _shownBal = to; updateSpinBtn(); updateBonusBtn(); }
    }
    _balRaf = requestAnimationFrame(step);
}

let _syncTimer = null, _syncSeq = 0;
function scheduleSync() {
    clearTimeout(_syncTimer);
    const seq = ++_syncSeq;
    _syncTimer = setTimeout(async () => {
        try {
            const s = await fetchState();
            if (seq !== _syncSeq) return;
            if (typeof s.balance === 'number') { balance = s.balance; animateBalance(balance); }
            if (s.vipTier != null) { _vipState = s; updateVipBadge(); }
            showFsBanner(s.freeSpinsRemaining, s.multiplier);
        } catch { /* silent */ }
    }, 600);
}

function rndSym() { return SYM_KEYS[Math.floor(Math.random() * SYM_KEYS.length)]; }
function haptic(s) { try { _tg?.HapticFeedback?.impactOccurred(s); } catch {} }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function errTxt(code) {
    const M = {
        INSUFFICIENT_BALANCE: 'Недостаточно ракушек 🐚',
        INVALID_BET:          'Неверная ставка',
        ONBOARDING_REQUIRED:  'Сначала пройди регистрацию',
        ALREADY_IN_BONUS:     'Уже в бонусном раунде 🏺',
        COOLDOWN:             '⏱ Секунду...',
    };
    return M[code] ?? 'Что-то пошло не так 🌊';
}

function showScreen(name) {
    $('screen-loading').style.display    = name === 'loading'    ? 'flex' : 'none';
    $('screen-onboarding').style.display = name === 'onboarding' ? 'flex' : 'none';
    $('app').style.display               = name === 'app'        ? 'flex' : 'none';
}
