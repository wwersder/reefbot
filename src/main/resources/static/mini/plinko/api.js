/**
 * Reef Plinko — API client
 * All calls include X-Telegram-Init-Data header for server-side HMAC auth.
 */

const BASE = '/api/mini/plinko';

let _initData = '';

export function setInitData(raw) {
    _initData = raw;
}

function headers() {
    return {
        'Content-Type': 'application/json',
        'X-Telegram-Init-Data': _initData
    };
}

export async function fetchState() {
    const r = await fetch(`${BASE}/state`, { headers: headers() });
    if (r.status === 401) throw new Error('AUTH_FAILED');
    if (!r.ok) throw new Error('STATE_ERROR');
    return r.json();
}

export async function postPlay(bet, rows, risk) {
    const r = await fetch(`${BASE}/play`, {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({ bet, rows, risk })
    });
    if (r.status === 401) throw new Error('AUTH_FAILED');
    // BUG-24: 500 responses don't have a valid play JSON body — throw before parsing
    if (r.status >= 500) throw new Error('SERVER_ERROR');
    const data = await r.json();
    return data;  // includes error field on 400
}

export async function fetchLeaderboard() {
    const r = await fetch(`${BASE}/leaderboard`);
    if (!r.ok) throw new Error('LEADERBOARD_ERROR');
    return r.json();
}
