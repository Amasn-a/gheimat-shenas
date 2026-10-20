// API adapter. One wire convention (snake_case) is mapped to camelCase here and nowhere else. Every POST carries an
// Idempotency-Key that stays the same for every retry of the same intent. Responses feed the server clock.
import * as clock from './clock.js';
import * as host from './host.js';

export const BASE = (window.PE_API_BASE || '') + '/v1/gamification/price-expert';

export class ApiError extends Error {
  constructor(status, code, body) { super(code || 'HTTP_' + status); this.status = status; this.code = code || 'HTTP_' + status; this.body = body || {}; this.state = body && body.state ? camel(body.state) : null; }
  get network() { return this.status === 0; }
}

const camelKey = k => k.replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());
export function camel(v) {
  if (Array.isArray(v)) return v.map(camel);
  if (v && typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) o[camelKey(k)] = camel(v[k]); return o; }
  return v;
}

const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => (Math.random() * 16 | 0).toString(16)));
export const newKey = uuid;

let tokenCache = null;
async function authHeader(refresh) {
  if (refresh || !tokenCache) tokenCache = refresh ? await host.refreshToken() : await host.token();
  return tokenCache ? 'Bearer ' + tokenCache : null;
}

/**
 * @param {string} method
 * @param {string} path      relative to BASE
 * @param {object} [o]       { body, key, timeoutMs }
 */
export async function request(method, path, o = {}) {
  const once = async (refresh) => {
    const auth = await authHeader(refresh);
    if (!auth) throw new ApiError(401, 'UNAUTHENTICATED');
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), o.timeoutMs || 8000);
    const headers = { Authorization: auth, 'X-Device-Id': host.deviceId(), Accept: 'application/json' };
    if (method === 'POST') { headers['Content-Type'] = 'application/json'; headers['Idempotency-Key'] = o.key || uuid(); }
    const sent = performance.now();
    let res, body = null, text;
    try {
      res = await fetch(BASE + path, { method, headers, body: method === 'POST' ? JSON.stringify(o.body || {}) : undefined, signal: ctl.signal, cache: 'no-store', credentials: 'omit' });
      text = await res.text();                                   // the deadline covers the body too, not only the headers
    } catch (e) {
      throw new ApiError(0, ctl.signal.aborted ? 'TIMEOUT' : 'NETWORK');   // an aborted request may still have been committed: retry with the same key
    } finally { clearTimeout(t); }
    try { body = text ? JSON.parse(text) : null; } catch (e) { body = null; }
    if (res.ok && (!body || typeof body !== 'object')) throw new ApiError(0, 'BAD_RESPONSE');   // malformed success: recover like a lost response
    body = body || {};
    // a replayed response (Idempotent-Replay) carries its original server_time: never a clock sample
    if (res.headers.get('Idempotent-Replay') !== 'true') {
      if (body.server_time) clock.observe(body.server_time, sent, performance.now());
      else if (body.state && body.state.server_time) clock.observe(body.state.server_time, sent, performance.now());
    }
    if (!res.ok) throw new ApiError(res.status, body.code, body);
    return camel(body);
  };
  try { return await once(false); }
  catch (e) {
    if (e instanceof ApiError && e.status === 401) return once(true);   // ask the host for one fresh token, resend with the same key
    throw e;
  }
}

/** One retry with the same key before surfacing a network error (handoff client timeouts). */
export async function withRetry(fn, retries = 1, delayMs = 400) {
  for (let i = 0; ; i++) {
    try { return await fn(); }
    catch (e) {
      if (!(e instanceof ApiError) || !e.network || i >= retries) throw e;
      await new Promise(r => setTimeout(r, delayMs));
    }
  }
}

export const api = {
  bootstrap: () => request('GET', '/bootstrap'),
  start: key => request('POST', '/runs', { key }),
  run: id => request('GET', `/runs/${id}`, { timeoutMs: 5000 }),
  reveal: (id, key) => request('POST', `/runs/${id}/reveal`, { key, timeoutMs: 4000 }),
  answer: (qid, choice, key) => request('POST', `/questions/${qid}/answer`, { key, body: { choice }, timeoutMs: 4000 }),
  activateOffer: (id, key) => request('POST', `/runs/${id}/offer`, { key, timeoutMs: 5000 }),
  continueOffer: (offerId, key) => request('POST', `/offers/${offerId}/continue`, { key, timeoutMs: 3000 }),
  skip: (offerId, key) => request('POST', `/offers/${offerId}/skip`, { key }),
  leave: (id, key) => request('POST', `/runs/${id}/leave`, { key }),
  operation: id => request('GET', `/operations/${id}`, { timeoutMs: 3000 }),
  result: id => request('GET', `/runs/${id}/result`),
  products: id => request('GET', `/runs/${id}/products`),
  share: id => request('GET', `/runs/${id}/share`),
  onboarding: key => request('POST', '/player/onboarding', { key }),
};
