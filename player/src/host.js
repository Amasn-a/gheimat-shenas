// Host adapter: every Super App interaction goes through here. The bridge contract (login token handover, back/close,
// device id, haptics, share, deep link, WebView floor) is with the app team (D09) and is NOT in the supplied
// sources, so no bridge object name or URL scheme is invented. The integration supplies an implementation through
// configureHost(); until then each capability falls back to a browser behaviour or reports itself unsupported.
let impl = {};

export function configureHost(i) { impl = i || {}; }

export const capabilities = () => ({
  token: typeof impl.getToken === 'function',
  share: typeof impl.share === 'function' || !!(navigator.canShare && navigator.share),
  haptics: typeof impl.haptic === 'function',            // no vibration fallback: haptics only through the approved bridge (R-10)
  productLink: typeof impl.openProduct === 'function',
  back: typeof impl.onBack === 'function',
});

export async function token() { return typeof impl.getToken === 'function' ? impl.getToken() : null; }
export async function refreshToken() { return typeof impl.refreshToken === 'function' ? impl.refreshToken() : null; }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
let device = null;
export function deviceId() {
  if (device) return device;
  const fromHost = typeof impl.deviceId === 'function' ? impl.deviceId() : null;
  if (fromHost && UUID.test(fromHost)) return (device = fromHost.toLowerCase());
  try { const v = localStorage.getItem('pe.device'); if (v && UUID.test(v)) return (device = v); } catch (e) { /* storage blocked */ }
  device = crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => (Math.random() * 16 | 0).toString(16));
  try { localStorage.setItem('pe.device', device); } catch (e) { /* the id then lives for this page only */ }
  return device;
}

export function haptic(kind) { if (typeof impl.haptic === 'function' && ['correct', 'wrong', 'checkpoint'].includes(kind)) impl.haptic(kind); }

/** Share image only unless the host supports a game deep link (R-56). Resolves 'sent' | 'cancelled' | 'unsupported'. */
export async function share(blob, text) {
  if (typeof impl.share === 'function') return impl.share({ blob, text });
  const file = new File([blob], 'qeymatshenas.png', { type: 'image/png' });
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try { await navigator.share({ files: [file], text }); return 'sent'; } catch (e) { return 'cancelled'; }
  }
  return 'unsupported';
}

/** Product links are built from a product-id template on the server, never taken from free text. */
export async function openProduct(url) {
  if (!/^https:\/\/www\.digikala\.com\/product\/dkp-\d+\/$/.test(url)) return false;
  if (typeof impl.openProduct === 'function') return impl.openProduct(url);
  return !!window.open(url, '_blank', 'noopener,noreferrer');
}

export function close() { if (typeof impl.close === 'function') impl.close(); else history.back(); }

/** Back handling: host onBack when available; otherwise a history entry at run start and popstate (handoff). */
let backHandler = null, guardPushed = false;
export function setBackHandler(fn) {
  backHandler = fn;
  if (typeof impl.onBack === 'function') { impl.onBack(() => (backHandler ? backHandler() : false)); return; }
  if (fn && !guardPushed) { history.pushState({ pe: 1 }, ''); guardPushed = true; }
}
window.addEventListener('popstate', () => {
  if (!guardPushed) return;
  guardPushed = false;
  if (backHandler && backHandler()) { history.pushState({ pe: 1 }, ''); guardPushed = true; }
});

export async function copy(text) {
  try { await navigator.clipboard.writeText(text); return true; } catch (e) { /* fall back */ }
  const ta = document.createElement('textarea'); ta.value = text; ta.setAttribute('readonly', ''); ta.style.position = 'fixed'; ta.style.opacity = '0';
  document.body.appendChild(ta); ta.select();
  let ok = false; try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
  ta.remove(); return ok;
}
