/**
 * Reef Plinko — UI controller
 * Wires DOM controls to the API and PlinkoBoard animation.
 */

import { setInitData, fetchState, postPlay, fetchLeaderboard } from './api.js';
import { PlinkoBoard } from './plinko.js';

const BET_STEPS = [5, 10, 25, 50, 100, 250, 500];
const DAILY_LIMIT = 2000;
const AUTO_MAX = 100;

// ── State ─────────────────────────────────────────────────────────────────────

let balance    = 0;
let dailyLost  = 0;
let betIdx     = 2;   // default: 25
let rows       = 8;
let risk       = 'MEDIUM';
let animating  = false;
let autoRunning = false;
let autoCount  = 0;
let speedFast  = false;
let board;

// ── Init ──────────────────────────────────────────────────────────────────────

export async function init() {
    const tg = window.Telegram?.WebApp;
    if (tg) {
        tg.ready();
        tg.expand();
        setInitData(tg.initData);
    } else {
        // Dev fallback — leave initData empty; server will 401 but page renders
        setInitData('');
    }

    showScreen('loading');

    try {
        const state = await fetchState();

        if (state.onboardingRequired) {
            document.querySelector('#screen-onboarding p').textContent = state.message;
            showScreen('onboarding');
            return;
        }

        balance   = state.balance;
        dailyLost = state.dailyLost;

        updateRowsSelect(state.rows12Unlocked);
        initBoard();
        bindEvents();
        updateUI();
        showScreen('app');

    } catch (e) {
        console.error('Init failed', e);
        document.querySelector('#screen-onboarding .emoji').textContent = '⚠️';
        document.querySelector('#screen-onboarding h2').textContent = 'Ошибка загрузки';
        document.querySelector('#screen-onboarding p').textContent =
            'Не удалось подключиться к серверу. Попробуй перезапустить.';
        showScreen('onboarding');
    }
}

// ── Board ─────────────────────────────────────────────────────────────────────

function initBoard() {
    const canvas = document.getElementById('plinko-canvas');
    board = new PlinkoBoard(canvas, onAnimationDone);

    window.addEventListener('resize', () => {
        if (board) board.resize();
    });
}

// ── Events ────────────────────────────────────────────────────────────────────

function bindEvents() {
    // Tab switching
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => switchTab(btn.dataset.tab));
    });

    // Risk
    document.getElementById('risk-select').addEventListener('change', e => {
        risk = e.target.value;
        if (board) board.setRisk(risk);
    });

    // Rows
    document.getElementById('rows-select').addEventListener('change', e => {
        rows = parseInt(e.target.value);
        if (board) board.setRows(rows);
    });

    // Bet adjust
    document.getElementById('bet-minus').addEventListener('click', () => adjustBet(-1));
    document.getElementById('bet-plus').addEventListener('click',  () => adjustBet(+1));

    // Quick bet buttons
    document.querySelectorAll('.qbet-btn').forEach((btn, i) => {
        btn.addEventListener('click', () => selectBetIdx(i));
    });

    // Throw
    document.getElementById('throw-btn').addEventListener('click', doThrow);

    // Auto
    document.getElementById('auto-btn').addEventListener('click', toggleAuto);

    // Speed toggle (double-click canvas)
    document.getElementById('plinko-canvas').addEventListener('dblclick', () => {
        speedFast = !speedFast;
        if (board) board.setFast(speedFast);
        document.getElementById('auto-count').textContent =
            speedFast ? '⚡' : (autoRunning ? autoCount + '/' + AUTO_MAX : '');
    });
}

// ── Throw ─────────────────────────────────────────────────────────────────────

async function doThrow() {
    if (animating) return;
    const bet = BET_STEPS[betIdx];

    setAnimating(true);
    hideFlash();

    try {
        const result = await postPlay(bet, rows, risk);

        if (result.error) {
            showFlash(result.message || result.error, 'loss');
            setAnimating(false);
            if (autoRunning) stopAuto();
            return;
        }

        // Update state immediately
        balance   = result.newBalance;
        dailyLost = Math.min(DAILY_LIMIT, dailyLost + Math.max(0, -(result.profit)));
        updateBalanceDisplay();
        updateLimitBar();

        // Animate ball
        board.dropBall(result.path, result.slot, result.multiplier, result.profit);

    } catch (e) {
        console.error('Play error', e);
        showFlash('Ошибка сети', 'loss');
        setAnimating(false);
        if (autoRunning) stopAuto();
    }
}

function onAnimationDone(multiplier, profit) {
    setAnimating(false);

    const isJackpot = multiplier >= 15;
    const isWin     = profit > 0;

    if (isJackpot) {
        showFlash(`🎰 ДЖЕКПОТ ×${multiplier.toFixed(0)}!`, 'jackpot');
    } else if (isWin) {
        showFlash(`+${profit} 🐚`, 'win');
    } else {
        showFlash(`${profit} 🐚`, 'loss');
    }

    // Auto spin
    if (autoRunning) {
        autoCount--;
        updateAutoCount();
        if (autoCount <= 0 || dailyLost >= DAILY_LIMIT) {
            stopAuto();
            return;
        }
        setTimeout(doThrow, 1200);
    }
}

// ── Auto ─────────────────────────────────────────────────────────────────────

function toggleAuto() {
    if (autoRunning) {
        stopAuto();
    } else {
        startAuto();
    }
}

function startAuto() {
    autoRunning = true;
    autoCount   = AUTO_MAX;
    updateAutoCount();
    document.getElementById('auto-btn').classList.add('running');
    document.getElementById('auto-btn').textContent = '⏹';
    if (!animating) doThrow();
}

function stopAuto() {
    autoRunning = false;
    autoCount   = 0;
    document.getElementById('auto-btn').classList.remove('running');
    document.getElementById('auto-btn').textContent = '▶▶';
    document.getElementById('auto-count').textContent = '';
}

function updateAutoCount() {
    document.getElementById('auto-count').textContent =
        autoRunning ? `${autoCount}/${AUTO_MAX}` : '';
}

// ── UI helpers ────────────────────────────────────────────────────────────────

function adjustBet(delta) {
    selectBetIdx(Math.max(0, Math.min(BET_STEPS.length - 1, betIdx + delta)));
}

function selectBetIdx(i) {
    betIdx = i;
    document.querySelectorAll('.qbet-btn').forEach((btn, j) => {
        btn.classList.toggle('active', j === betIdx);
    });
    document.getElementById('bet-display').textContent = BET_STEPS[betIdx] + ' 🐚';
}

function updateUI() {
    selectBetIdx(betIdx);
    updateBalanceDisplay();
    updateLimitBar();
}

function updateBalanceDisplay() {
    document.getElementById('balance-num').textContent = balance;
}

function updateLimitBar() {
    const pct = Math.min(100, (dailyLost / DAILY_LIMIT) * 100);
    document.getElementById('limit-bar-fill').style.width = pct + '%';
    document.getElementById('limit-label').textContent =
        `Потери: ${dailyLost}/${DAILY_LIMIT} 🐚`;
}

function setAnimating(val) {
    animating = val;
    document.getElementById('throw-btn').disabled = val;
}

function showFlash(text, type) {
    const el = document.getElementById('result-flash');
    el.textContent = text;
    el.className   = 'show ' + type;
    clearTimeout(el._timeout);
    el._timeout = setTimeout(() => {
        el.className = type;  // keep type but remove 'show' -> fade via CSS
        setTimeout(() => { el.className = ''; }, 300);
    }, 1800);
}

function hideFlash() {
    const el = document.getElementById('result-flash');
    el.className = '';
    el.textContent = '';
}

function updateRowsSelect(rows12Unlocked) {
    const sel = document.getElementById('rows-select');
    if (!rows12Unlocked) {
        // Disable 12-row option
        sel.querySelector('option[value="12"]')?.remove();
    }
}

function showScreen(name) {
    document.getElementById('screen-loading').style.display    = name === 'loading'    ? 'flex' : 'none';
    document.getElementById('screen-onboarding').style.display = name === 'onboarding' ? 'flex' : 'none';
    document.getElementById('app').style.display               = name === 'app'        ? 'flex' : 'none';
}

function switchTab(tab) {
    document.querySelectorAll('.tab-btn').forEach(b =>
        b.classList.toggle('active', b.dataset.tab === tab));
    document.querySelectorAll('.tab-panel').forEach(p =>
        p.classList.toggle('active', p.id === 'panel-' + tab));

    if (tab === 'top') loadLeaderboard();
}

// ── Leaderboard ───────────────────────────────────────────────────────────────

let lbLoaded = false;

async function loadLeaderboard() {
    if (lbLoaded) return;
    const container = document.getElementById('panel-top');
    container.innerHTML = '<div class="lb-loading">⏳ Загрузка...</div>';

    try {
        const data = await fetchLeaderboard();
        lbLoaded = true;
        renderLeaderboard(data);
    } catch {
        document.getElementById('panel-top').innerHTML =
            '<div class="lb-empty">Не удалось загрузить рекорды</div>';
    }
}

const RANK_CLASS = ['gold', 'silver', 'bronze'];

function renderLeaderboard(data) {
    const container = document.getElementById('panel-top');

    if (!data.topWin?.length) {
        container.innerHTML = '<div class="lb-empty">🪷 Пока нет записей. Сыграй первым!</div>';
        return;
    }

    container.innerHTML = `
        <div class="lb-section">
            <div class="lb-title">💰 Лучший выигрыш</div>
            ${data.topWin.slice(0, 10).map((e, i) => lbRow(e, i, 'win')).join('')}
        </div>
        <div class="lb-section">
            <div class="lb-title">🎯 Лучший множитель</div>
            ${data.topMultiplier.slice(0, 10).map((e, i) => lbRow(e, i, 'mult')).join('')}
        </div>`;
}

function lbRow(entry, idx, type) {
    const rankClass = RANK_CLASS[idx] || '';
    const name = escHtml(entry.username || '?');
    const val  = type === 'win'
        ? `<span class="lb-val">+${entry.profit} 🐚</span>`
        : `<span class="lb-val">×${entry.multiplier.toFixed(0)}</span>`;
    const sub = type === 'win'
        ? `×${entry.multiplier.toFixed(0)}`
        : `+${entry.profit} 🐚`;

    return `
        <div class="lb-row">
            <span class="lb-rank ${rankClass}">${idx + 1}</span>
            <span class="lb-name">${name}</span>
            ${val}
            <span class="lb-sub">${escHtml(sub)}</span>
        </div>`;
}

function escHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}
