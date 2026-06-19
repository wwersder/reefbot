/**
 * Reef Plinko — UI controller v2 (light theme)
 */

import { setInitData, fetchState, postPlay, fetchLeaderboard } from './api.js';
import { PlinkoBoard } from './plinko.js';

const BET_STEPS   = [5, 10, 25, 50, 100, 250, 500];
const DAILY_LIMIT = 2000;
const AUTO_MAX    = 100;

// ── State ─────────────────────────────────────────────────────────────────────

let balance    = 0;
let dailyLost  = 0;
let betIdx     = 2;      // default: 25 🐚
let rows       = 8;
let risk       = 'MEDIUM';
let animating  = false;
let autoRunning = false;
let autoCount  = 0;
let board      = null;

// ── Helpers ───────────────────────────────────────────────────────────────────

const $  = id  => document.getElementById(id);
const $$ = sel => document.querySelectorAll(sel);

function escHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ── Init ──────────────────────────────────────────────────────────────────────

export async function init() {
    const tg = window.Telegram?.WebApp;
    if (tg) {
        tg.ready();
        tg.expand();
        setInitData(tg.initData);
    } else {
        setInitData('');
    }

    showScreen('loading');

    try {
        const state = await fetchState();

        if (state.onboardingRequired) {
            $('ob-text').textContent = state.message || 'Сначала заверши регистрацию в боте.';
            showScreen('onboarding');
            return;
        }

        balance   = state.balance;
        dailyLost = state.dailyLost;

        if (!state.rows12Unlocked) {
            $('rows-select').querySelector('option[value="12"]')?.remove();
        }

        bindEvents();
        updateUI();
        showScreen('app');

        // Canvas has 0 size while #app is display:none — init after reveal
        requestAnimationFrame(() => {
            board = new PlinkoBoard($('plinko-canvas'), onAnimDone);
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
    $('risk-select').addEventListener('change', e => {
        risk = e.target.value;
        board?.setRisk(risk);
    });

    $('rows-select').addEventListener('change', e => {
        rows = parseInt(e.target.value);
        board?.setRows(rows);
    });

    $('bet-minus').addEventListener('click', () => adjustBet(-1));
    $('bet-plus').addEventListener('click',  () => adjustBet(+1));

    $$('.qbet-btn').forEach((btn, i) =>
        btn.addEventListener('click', () => selectBetIdx(i)));

    $('throw-btn').addEventListener('click', handleThrow);

    $('auto-check').addEventListener('change', e => {
        if (e.target.checked) startAuto();
        else stopAuto();
    });

    $('turbo-check').addEventListener('change', e => {
        board?.setFast(e.target.checked);
    });

    $('lb-btn').addEventListener('click', openLeaderboard);
    $('lb-close').addEventListener('click', closeLeaderboard);
    $('lb-backdrop').addEventListener('click', closeLeaderboard);
}

// ── Throw ─────────────────────────────────────────────────────────────────────

function handleThrow() {
    if (autoRunning) { stopAuto(); return; }
    if (animating) return;
    doThrow();
}

async function doThrow() {
    if (!board || animating) return;
    const bet = BET_STEPS[betIdx];

    setAnimating(true);
    setResult('', 'neutral');

    try {
        const res = await postPlay(bet, rows, risk);

        if (res.error) {
            setResult(res.message || res.error, 'loss');
            setAnimating(false);
            stopAuto();
            return;
        }

        balance   = res.newBalance;
        dailyLost = Math.min(DAILY_LIMIT, dailyLost + Math.max(0, -(res.profit)));
        updateBalance();
        updateLimitBar();

        board.dropBall(res.path, res.slot, res.multiplier, res.profit);

    } catch (e) {
        console.error('Play error', e);
        setResult('Ошибка сети 🌊', 'loss');
        setAnimating(false);
        stopAuto();
    }
}

function onAnimDone(multiplier, profit) {
    setAnimating(false);

    if (multiplier >= 15) {
        setResult(`🎰 ДЖЕКПОТ ×${multiplier.toFixed(0)}!`, 'jackpot');
    } else if (profit > 0) {
        setResult(`+${profit} 🐚  (×${multiplier.toFixed(1)})`, 'win');
    } else if (profit === 0) {
        setResult(`Ничья ×${multiplier.toFixed(1)}`, 'neutral');
    } else {
        setResult(`НЕ ПОВЕЗЛО  ×${multiplier.toFixed(1)}`, 'loss');
    }

    if (autoRunning) {
        autoCount--;
        updateThrowBtn();
        if (autoCount <= 0 || dailyLost >= DAILY_LIMIT) {
            stopAuto();
            return;
        }
        setTimeout(doThrow, 1100);
    }
}

// ── Auto ──────────────────────────────────────────────────────────────────────

function startAuto() {
    autoRunning = true;
    autoCount   = AUTO_MAX;
    updateThrowBtn();
    if (!animating) doThrow();
}

function stopAuto() {
    autoRunning = false;
    autoCount   = 0;
    $('auto-check').checked = false;
    updateThrowBtn();
}

// ── UI state ──────────────────────────────────────────────────────────────────

function updateUI() {
    selectBetIdx(betIdx);
    updateBalance();
    updateLimitBar();
    setResult('Выбери ставку и бросай', 'neutral');
}

function adjustBet(d) {
    selectBetIdx(Math.max(0, Math.min(BET_STEPS.length - 1, betIdx + d)));
}

function selectBetIdx(i) {
    betIdx = i;
    $$('.qbet-btn').forEach((btn, j) => btn.classList.toggle('active', j === i));
    $('bet-display').textContent = BET_STEPS[i] + ' 🐚';
}

function updateBalance() {
    $('balance-num').textContent  = balance;
    $('balance-hint').textContent = balance;
}

function updateLimitBar() {
    const pct = Math.min(100, dailyLost / DAILY_LIMIT * 100);
    $('limit-bar-fill').style.width = pct + '%';
    $('limit-label').textContent = `Потери: ${dailyLost} / ${DAILY_LIMIT} 🐚`;
}

function setAnimating(val) {
    animating = val;
    updateThrowBtn();
}

function updateThrowBtn() {
    const btn = $('throw-btn');
    if (autoRunning) {
        btn.disabled = false;
        btn.textContent = `■ СТОП (${autoCount}x)`;
        btn.className = 'auto-running';
    } else if (animating) {
        btn.disabled = true;
        btn.textContent = 'БРОСОК...';
        btn.className = '';
    } else {
        btn.disabled = false;
        btn.textContent = 'БРОСИТЬ';
        btn.className = '';
    }
}

function setResult(text, type) {
    const el = $('result-text');
    el.textContent = text;
    el.className = type;
}

function showScreen(name) {
    $('screen-loading').style.display    = name === 'loading'    ? 'flex' : 'none';
    $('screen-onboarding').style.display = name === 'onboarding' ? 'flex' : 'none';
    $('app').style.display               = name === 'app'        ? 'flex' : 'none';
}

// ── Leaderboard bottom sheet ──────────────────────────────────────────────────

let lbLoaded = false;

function openLeaderboard() {
    $('lb-overlay').classList.add('open');
    if (!lbLoaded) loadLeaderboard();
}

function closeLeaderboard() {
    $('lb-overlay').classList.remove('open');
}

async function loadLeaderboard() {
    const content = $('lb-content');
    content.innerHTML = '<div class="lb-placeholder">⏳ Загрузка...</div>';
    try {
        const data = await fetchLeaderboard();
        lbLoaded = true;
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
        const rows = entries.slice(0, 10).map((e, i) => {
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
            ${rows}
        </div>`;
    };

    container.innerHTML =
        section('💰 Лучший выигрыш', data.topWin, 'win') +
        section('🎯 Лучший множитель', data.topMultiplier, 'mult');
}
