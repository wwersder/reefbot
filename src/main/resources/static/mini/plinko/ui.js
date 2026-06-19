/**
 * Reef Plinko — UI controller v3
 *
 * Multi-ball: throw button never freezes.
 * Free bet input + multiplier chips (½ ×2 ×5 ×10 MAX).
 * Auto-spin is sequential; manual throws are concurrent.
 * Leaderboard fetches fresh data every open.
 * No daily loss limit.
 */

import { setInitData, fetchState, postPlay, fetchLeaderboard } from './api.js';
import { PlinkoBoard } from './plinko.js';

const MIN_BET  = 5;
const MAX_BET  = 500;
const BET_STEP = 5;   // +/- increment
const AUTO_MAX = 100;

// ── State ─────────────────────────────────────────────────────────────────────

let balance     = 0;
let betValue    = 25;    // current bet amount (free integer)
let rows        = 8;
let risk        = 'MEDIUM';
let autoRunning = false;
let autoCount   = 0;
let autoInFlight = false;  // one auto-ball in flight at a time
let board       = null;

// ── Helpers ───────────────────────────────────────────────────────────────────

const $  = id  => document.getElementById(id);
const $$ = sel => document.querySelectorAll(sel);

function clampBet(v) {
    return Math.max(MIN_BET, Math.min(MAX_BET, Math.round(v / BET_STEP) * BET_STEP || MIN_BET));
}

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

        balance = state.balance;

        if (!state.rows12Unlocked) {
            $('rows-select').querySelector('option[value="12"]')?.remove();
        }

        bindEvents();
        updateUI();
        showScreen('app');

        requestAnimationFrame(() => {
            board = new PlinkoBoard($('plinko-canvas'), null);
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

    // Bet: +/- buttons (step by BET_STEP)
    $('bet-minus').addEventListener('click', () => setBet(betValue - BET_STEP));
    $('bet-plus').addEventListener('click',  () => setBet(betValue + BET_STEP));

    // Bet: free input
    $('bet-input').addEventListener('input', e => {
        const v = parseInt(e.target.value) || MIN_BET;
        betValue = Math.max(MIN_BET, Math.min(MAX_BET, v));
        // Don't clamp display while typing — clamp on blur
    });
    $('bet-input').addEventListener('blur', () => {
        setBet(parseInt($('bet-input').value) || MIN_BET);
    });

    // Multiplier chips
    $$('.mult-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const m = btn.dataset.mult;
            if (m === 'max') {
                setBet(Math.min(MAX_BET, balance));
            } else {
                setBet(Math.round(betValue * parseFloat(m)));
            }
        });
    });

    // Throw button
    $('throw-btn').addEventListener('click', handleThrow);

    // Auto-spin checkbox
    $('auto-check').addEventListener('change', e => {
        if (e.target.checked) startAuto();
        else stopAuto();
    });

    // Turbo checkbox
    $('turbo-check').addEventListener('change', e => {
        board?.setFast(e.target.checked);
    });

    // Leaderboard
    $('lb-btn').addEventListener('click', openLeaderboard);
    $('lb-close').addEventListener('click', closeLeaderboard);
    $('lb-backdrop').addEventListener('click', closeLeaderboard);
}

// ── Throw ─────────────────────────────────────────────────────────────────────

function handleThrow() {
    if (autoRunning) { stopAuto(); return; }
    doManualThrow();
}

async function doManualThrow() {
    if (!board) return;
    const bet = betValue;
    try {
        const res = await postPlay(bet, rows, risk);
        if (res.error) { setResult(res.message || res.error, 'loss'); return; }
        balance = res.newBalance;
        updateBalance();
        board.dropBall(res.path, res.slot, res.multiplier, res.profit, (mult, profit) => {
            showResult(mult, profit);
        });
    } catch (e) {
        console.error('Play error', e);
        setResult('Ошибка сети 🌊', 'loss');
    }
}

async function doAutoThrow() {
    if (!autoRunning || !board) return;
    autoInFlight = true;
    try {
        const res = await postPlay(betValue, rows, risk);
        if (res.error) {
            setResult(res.message || res.error, 'loss');
            stopAuto();
            return;
        }
        balance = res.newBalance;
        updateBalance();
        board.dropBall(res.path, res.slot, res.multiplier, res.profit, (mult, profit) => {
            showResult(mult, profit);
            autoInFlight = false;

            if (!autoRunning) return;
            autoCount--;
            updateThrowBtn();
            if (autoCount <= 0) { stopAuto(); return; }
            setTimeout(doAutoThrow, 900);
        });
    } catch (e) {
        console.error('Auto play error', e);
        stopAuto();
    }
}

function showResult(multiplier, profit) {
    if (multiplier >= 15) {
        setResult(`🎰 ДЖЕКПОТ ×${multiplier.toFixed(0)}!`, 'jackpot');
    } else if (profit > 0) {
        setResult(`+${profit} 🐚  (×${multiplier.toFixed(1)})`, 'win');
    } else if (profit === 0) {
        setResult(`Ничья ×${multiplier.toFixed(1)}`, 'neutral');
    } else {
        setResult(`НЕ ПОВЕЗЛО  ×${multiplier.toFixed(1)}`, 'loss');
    }
}

// ── Auto ──────────────────────────────────────────────────────────────────────

function startAuto() {
    autoRunning  = true;
    autoCount    = AUTO_MAX;
    autoInFlight = false;
    updateThrowBtn();
    doAutoThrow();
}

function stopAuto() {
    autoRunning  = false;
    autoInFlight = false;
    autoCount    = 0;
    $('auto-check').checked = false;
    updateThrowBtn();
}

// ── UI helpers ────────────────────────────────────────────────────────────────

function updateUI() {
    setBet(betValue);
    updateBalance();
    setResult('Выбери ставку и бросай', 'neutral');
}

function setBet(v) {
    betValue = clampBet(v);
    $('bet-input').value = betValue;
}

function updateBalance() {
    $('balance-num').textContent  = balance;
    $('balance-hint').textContent = balance;
}

function updateThrowBtn() {
    const btn = $('throw-btn');
    if (autoRunning) {
        btn.textContent = `■ СТОП (${autoCount}x)`;
        btn.className   = 'auto-running';
    } else {
        btn.textContent = 'БРОСИТЬ';
        btn.className   = '';
    }
}

function setResult(text, type) {
    const el = $('result-text');
    el.textContent = text;
    el.className   = type;
}

function showScreen(name) {
    $('screen-loading').style.display    = name === 'loading'    ? 'flex' : 'none';
    $('screen-onboarding').style.display = name === 'onboarding' ? 'flex' : 'none';
    $('app').style.display               = name === 'app'        ? 'flex' : 'none';
}

// ── Leaderboard ───────────────────────────────────────────────────────────────

function openLeaderboard() {
    $('lb-overlay').classList.add('open');
    loadLeaderboard();   // always fetch fresh
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
