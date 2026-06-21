/** Reef House slot — API layer */

let _initData = '';

export function setInitData(d) { _initData = d || ''; }

async function req(method, path, body) {
    const opts = {
        method,
        headers: { 'X-Telegram-Init-Data': _initData },
    };
    if (body) {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(body);
    }
    const r = await fetch('/api/mini/slot' + path, opts);
    if (!r.ok && r.status !== 400) throw new Error('HTTP ' + r.status);
    return r.json();
}

export const fetchState = ()          => req('GET',  '/state');
export const postSpin   = (bet)       => req('POST', '/spin', { bet });
