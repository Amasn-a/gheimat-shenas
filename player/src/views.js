// Screen markup in the v3 «ویترین» design system classes (ported from the approved prototype, same structure and copy).
// Views are pure functions of the view model: they never read the network or the clock themselves, except the passed
// serverNow for countdown rings. All text from data goes through esc()/title().
import { I } from './icons.js';
import { fa, money, esc, title, untilInstant } from './fmt.js';

const ICO = (k, s = 20) => I[k].replace('width="24" height="24"', `width="${s}" height="${s}"`);
const COIN = '<i class="v3-coin" aria-hidden="true"></i>';
const L = ' disabled aria-disabled="true"';
export const Q_TEXT = 'قیمتش گرون‌تره یا ارزون‌تر؟';   // exact question (R-17)

// ---------------------------------------------------------------- primitives
export const keyOf = card => 'p' + hash(card.imageUrl || card.displayTitle || '');
function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0).toString(36); }
const mk = (k, fx) => k ? ` data-m="${k}" data-mfx="${fx}"` : '';
export function img(card, loading, keyed, onload) {
  const key = keyed ? mk('img:' + keyOf(card), 'box') : '';
  if (loading === 'skeleton') return `<div class="v3-img is-loading v3-skel v3-skel--card" role="img" aria-label="تصویر در حال بارگذاری"${key}></div>`;
  return `<div class="v3-img"${key}><img src="${esc(card.imageUrl)}" alt="" decoding="async"${onload ? ` data-onload="${onload}"` : ''}></div>`;
}
export function priceHTML(n, cls = '', attrs = '') {
  const d = String(Math.round(n)).length, size = d >= 10 ? ' v3-price--xl' : d >= 9 ? ' v3-price--long' : '';
  return `<div class="v3-price${size}${cls}"${attrs}><b>${money(n)}</b><small>تومان</small></div>`;
}
const ttl = card => `<h3${mk('ttl:' + keyOf(card), 'text')}>${title(card.displayTitle)}</h3>`;
export const refTicket = card => `<article class="v3-ticket" aria-label="کالای مرجع، ${money(card.price)} تومان"${mk('card:' + keyOf(card), 'frame')}>${img(card, false, true)}<div class="v3-ticket__b"><span class="v3-chip">کالای مرجع</span>${ttl(card)}${priceHTML(card.price, '', mk('prc:' + keyOf(card), 'text'))}</div></article>`;
// UX-6: before «نمایش بده» the reference is the large card; it shrinks into the ticket when the question is requested
export const refBig = card => `<article class="v3-hero v3-hero--ref" aria-label="کالای مرجع، ${money(card.price)} تومان"${mk('card:' + keyOf(card), 'frame')}><span class="v3-ribbon v3-ribbon--ref">کالای مرجع</span>${img(card, false, true)}<div class="v3-hero__b">${ttl(card)}${priceHTML(card.price, '', mk('prc:' + keyOf(card), 'text'))}</div></article>`;
export function ask(text, o = {}) {
  const cls = 'v3-timer' + (o.mode ? ' is-' + o.mode : ''), sp = o.spin ? '<span class="v3-spin" aria-hidden="true"></span>' : '';
  let tm;
  if (o.mode === 'idle') tm = `<div class="${cls}" aria-label="۱۰ ثانیه پس از نمایش"><b>${fa(10)}<small>ثانیه</small></b></div>`;
  else if (o.mode === 'off') tm = `<div class="${cls}" aria-hidden="true"><b></b></div>`;
  else tm = `<div class="${cls}" style="--p:${o.p}" role="timer" aria-live="off" aria-label="${o.t} ثانیه" data-timer><b>${o.t}</b></div>`;
  return `<div class="v3-ask"${mk('ask', 'fade')}><p${o.muted ? ' class="is-muted"' : ''}>${sp}${text}</p>${tm}</div>`;
}
const HIDDEN = k => `<span class="v3-hidden" role="img" aria-label="قیمت مخفی"${mk('prc:' + k, 'text')}>${I.lock}<b>؟</b><small>تومان</small></span>`;
const ARR = {
  down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14M6 13l6 6 6-6"/></svg>',
  up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M6 11l6-6 6 6"/></svg>',
  eq: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"><path d="M6 9h12M6 15h12"/></svg>' };
export const DIRW = { up: 'گرون‌تر', down: 'ارزون‌تر', eq: 'قیمت برابر' };
export const dirOf = d => d === 'higher' ? 'up' : d === 'lower' ? 'down' : 'eq';
/** Comparison card: hidden while answerable; after the answer, the authorised revealed price sits on it (UX-1). */
export function hero(card, st, loading, dir, revealedPrice) {
  const k0 = keyOf(card);
  const cls = { hidden: '', correct: ' is-correct', wrong: ' is-wrong', timeout: ' is-timeout' }[st];
  const hint = loading ? '<p class="v3-hero__hint">عکس در راهه؛ از روی اسم هم می‌تونی جواب بدی.</p>' : '';   // CP-22 (R-58)
  if (st === 'hidden') return `<article class="v3-hero" aria-label="کالای بعدی"${mk('card:' + k0, 'frame')}><span class="v3-ribbon">کالای بعدی</span>${img(card, false, true, 'q')}<div class="v3-hero__b">${ttl(card)}${hint}${HIDDEN(k0)}</div></article>`;
  const k = { correct: 'ok', wrong: 'no', timeout: 'time' }[st], ic = { ok: I.check, no: '✕', time: '!' }[k];
  const lead = st === 'correct' ? `<span class="v3-dir" role="img" aria-label="${DIRW[dir]}">${ARR[dir]}</span>` : '<span>قیمت واقعی</span>';   // «قیمت واقعی»: CP-34, proposed
  const pr = `<div class="v3-reveal v3-reveal--${k}"${mk('prc:' + k0, 'text')}>${lead}${priceHTML(revealedPrice, ' v3-price--reveal' + (k === 'ok' ? ' v3-price--ok' : ''))}</div>`;
  return `<article class="v3-hero${cls}" aria-label="کالای بعدی، ${{ ok: 'پاسخ درست', no: 'پاسخ اشتباه', time: 'وقت تموم شد' }[k]}"${mk('card:' + k0, 'frame')}><span class="v3-hero__badge v3-hero__badge--${k}" aria-hidden="true"${mk('badge:' + k0, 'fade')}>${ic}</span>${img(card, false, true)}<div class="v3-hero__b v3-hero__b--reveal">${ttl(card)}${pr}</div></article>`;
}
export function verdict(k, t, sub) {
  const ic = { ok: I.check, no: '<b aria-hidden="true">✕</b>', time: I.clock }[k];
  return `<div class="v3-verdict v3-verdict--${k}" role="status"${mk('ask', 'fade')}><span class="v3-verdict__i" aria-hidden="true">${ic}</span><b class="v3-verdict__t">${t}</b><span class="v3-verdict__s">${sub}</span></div>`;
}
export function tag(kind, t, text, btn, btnAttr) {
  const b = btn ? `<button class="v3-btn v3-btn--white"${btnAttr || ''}>${btn}</button>` : '';
  return `<div class="v3-tag${kind ? ' v3-tag--' + kind : ''}"${kind === 'preparing' ? ' aria-busy="true"' : ''}${mk('slot', 'fade')}><span class="v3-tag__string" aria-hidden="true"></span>` +
    `<div class="v3-tag__card"><span class="v3-tag__hole" aria-hidden="true"></span><b aria-hidden="true">؟</b><span>${t}</span></div>${text ? `<p>${text}</p>` : ''}${b}</div>`;
}
/** Answer keys: stable position and equal prominence; accessible names include the reference price (R-14). */
export function keys(st, side, quiet, refPrice) {
  const iU = `<span class="v3-ab__i" aria-hidden="true">${I.up}</span>`, iD = `<span class="v3-ab__i" aria-hidden="true">${I.down}</span>`;
  const chk = `<span class="v3-ab__i" aria-hidden="true">${I.check}</span>`, x = '<span class="v3-ab__i" aria-hidden="true">✕</span>';
  const spin = '<span class="v3-ab__i" aria-hidden="true"><span class="v3-spin"></span></span>';
  const A = { c: '', a: ' data-act="ans-higher"', i: iU }, B = { c: '', a: ' data-act="ans-lower"', i: iD };
  const me = side === 'higher' ? A : B, ot = side === 'higher' ? B : A;
  if (st === 'off') { A.a = B.a = L; }
  else if (st === 'pending') { me.c = ' is-picked'; me.a = ' aria-pressed="true" aria-busy="true"' + L; me.i = spin; ot.c = ' is-dim'; ot.a = L; }
  else if (st === 'ok') { me.c = ' is-ok'; me.a = ' aria-pressed="true"' + L; me.i = chk; ot.c = ' is-dim'; ot.a = L; }
  else if (st === 'no') { me.c = ' is-no'; me.a = ' aria-pressed="true"' + L; me.i = x; ot.c = ' is-truth'; ot.a = L; ot.i = chk; }
  else if (st === 'truth') { me.c = ' is-truth'; me.a = L; me.i = chk; ot.c = ' is-dim'; ot.a = L; }
  const rp = st === 'on' && refPrice ? money(refPrice) : '';
  const lb = w => rp ? ` aria-label="${w} از ${rp} تومان"` : '';
  return `<div class="v3-dock${quiet ? ' v3-dock--quiet' : ''}"${mk('dock', 'fade')}><div class="v3-ans" role="group" aria-label="پاسخ"><button class="v3-ab${A.c}"${A.a}${lb('گرون‌تر')}>${A.i}گرون‌تر</button><button class="v3-ab${B.c}"${B.a}${lb('ارزون‌تر')}>${B.i}ارزون‌تر</button></div></div>`;
}
function confetti(n, seed) {
  let s = seed; const rnd = () => (s = (s * 9301 + 49297) % 233280) / 233280; const cols = ['#FFC633', '#FFFFFF', '#22C49A', '#FFE9A6', '#FF8FA0'];
  let o = ''; for (let i = 0; i < n; i++) o += `<i style="left:${2 + Math.floor(rnd() * 94)}%;top:${Math.floor(rnd() * 80)}%;width:${[6, 8, 10][Math.floor(rnd() * 3)]}px;height:${[10, 14, 6][Math.floor(rnd() * 3)]}px;background:${cols[Math.floor(rnd() * 5)]};transform:rotate(${Math.floor(rnd() * 180)}deg);animation-delay:${Math.floor(rnd() * 200)}ms"></i>`;
  return '<div class="v3-confetti" aria-hidden="true">' + o + '</div>';
}
export function stage(inner, extra = '', kind = '', dim = false) {
  const modal = /v3-sheetwrap|v3-cp|pe-host/.test(extra) ? ' inert' : '';
  return `<div class="v3-stage${kind ? ' v3-stage--' + kind : ''}${dim ? ' v3-stage--dim' : ''}"><div class="v3-screen${kind === 'page' ? ' v3-screen--page' : ''}"${modal}>${inner}</div>${extra}</div>`;
}
export function sheet(t, text, body, btns, st, icon, ring) {
  let lead = '';
  if (ring) lead = ring; else if (icon) lead = `<span class="v3-sheet__icon v3-sheet__icon--${icon[0]}" aria-hidden="true">${I[icon[1]]}</span>`;
  return `<div class="v3-sheetwrap"><section class="v3-sheet" role="dialog" aria-modal="true" aria-labelledby="sh-t"><span class="v3-grip" aria-hidden="true"></span>` +
    `<div class="v3-sheet__hd">${lead}<div><h3 id="sh-t">${t}</h3>${text ? `<p>${text}</p>` : ''}</div></div>${body || ''}${btns || ''}${st || ''}</section></div>`;
}
export function ring(remMs, totalMs) {
  const s = fa(Math.ceil(remMs / 1000));
  return `<div class="v3-ring${remMs <= 3000 ? ' is-low' : ''}" style="--p:${(remMs / totalMs).toFixed(3)}" role="timer" data-ring aria-label="${s} ثانیه برای تصمیم"><b>${s}</b><small>ثانیه</small></div>`;
}
export const cost = (bal, costN) => `<div class="v3-cost"><div class="v3-cost__fee"><span>هزینه ادامه</span><b>${COIN}${fa(costN)} دیجی‌کوین</b></div><div><span>موجودی شما</span><b>${bal}</b></div></div>`;
export function status(kind, text, icon, spin, alert) {
  const lead = spin ? '<span class="v3-spin"></span>' : icon ? ICO(icon, 16) : '';
  return `<div class="v3-status${kind ? ' v3-status--' + kind : ''}" role="${alert ? 'alert' : 'status'}"><span class="v3-status__i" aria-hidden="true">${lead}</span><span>${text}</span></div>`;
}
export const stagepill = (t, ic, no) => `<div class="v3-stagepill${no ? ' v3-stagepill--no' : ''}" role="status">${ICO(ic, 18)}<span>${t}</span></div>`;
export function toastHTML(t) {
  if (!t) return '';
  return `<div class="v3-toast${t.no ? ' v3-toast--no' : ''}" role="${t.no ? 'alert' : 'status'}"><i aria-hidden="true">${t.no ? '!' : '✓'}</i>${t.text}</div>`;
}
export const BTN = (cls, t, a = '') => `<button class="v3-btn ${cls}"${a}>${t}</button>`;
export const GOLD = (t, a = '', xl = true) => BTN('v3-btn--gold v3-btn--block' + (xl ? ' v3-btn--xl' : ''), t, a);
export const BUSY = (t, xl = true) => BTN('v3-btn--block' + (xl ? ' v3-btn--xl' : ''), `<span class="v3-spin" aria-hidden="true"></span>${t}`, ' disabled aria-disabled="true" aria-busy="true"');
export const BTNS = (...b) => '<div class="v3-sheet__btns">' + b.join('') + '</div>';
export const SKIP = (a = ' data-act="skip"') => BTN('v3-btn--outline v3-btn--block', 'رد کردن', a);
export const statecard = (ic, kind, t, text, actions) => `<div class="v3-statecard"><div class="v3-statecard__i${kind ? ' v3-statecard__i--' + kind : ''}" aria-hidden="true">${I[ic]}</div><h2>${t}</h2>${text ? `<p>${text}</p>` : ''}<div class="v3-statecard__a">${actions}</div></div>`;
const PLAYI = '<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>';
const pillCoin = n => `<span class="v3-pill v3-pill--gold">${COIN}<span dir="ltr">+${fa(n)}</span></span>`;   // streak reward (not a checkpoint total)
const PILL_PAID = '<span class="v3-pill v3-pill--ok">✓ واریز شد</span>';
const WAIT = '<em class="v3-pill v3-pill--wait"><i class="v3-spin" style="width:10px;height:10px;border-width:2px"></i>در حال واریز</em>';
const PAID = '<em class="v3-pill v3-pill--ok">✓ واریز شد</em>';
const LATE = '<em class="v3-pill v3-pill--warn">با تأخیر</em>';
const CB = n => `<b>${COIN}${fa(n)}</b>`;

// ---------------------------------------------------------------- HUD
export function top(m) {
  const lock = m.backLocked;
  const coins = m.balance == null ? '…' : fa(m.balance);
  const p = m.plus ? '<span class="v3-plus" dir="ltr">+۱</span>' : '';
  const R0 = m.record, rec = !R0 ? '' : m.score > R0 ? '<small class="v3-score__rec is-new">رکورد جدید</small>' : `<small class="v3-score__rec">رکورد ${fa(R0)}</small>`;   // record from question one (R-54)
  return `<div class="v3-top"><button class="v3-round" aria-label="خروج از دست" data-act="back"${lock ? L : ''}>${I.back}</button>` +
    `<div class="v3-score${m.plus ? ' is-up' : ''}" aria-live="polite"><b>${fa(m.score)}</b>${p}<span>پاسخ درست${rec}</span></div>` +
    `<span class="v3-wallet" aria-label="موجودی ${coins} دیجی‌کوین">${COIN}${coins}</span></div>`;
}
export function ptop(t, back, act, brand, coins) {
  const tt = `<span class="v3-brand${brand ? '' : ' v3-brand--sm'}">${t}</span>`;
  const w = coins === '' ? '<span></span>' : `<span class="v3-wallet">${COIN}${coins == null ? '…' : coins}</span>`;
  return `<div class="v3-top"><button class="v3-round" aria-label="${back}" data-act="${act}">${I.back}</button>${tt}${w}</div>`;
}
/** Checkpoint progress; after the last checkpoint the bar fills toward the record (no extra milestones, R-54). */
export function track(score, cps, record, earned) {
  const prev = [...cps].reverse().find(c => c.requiredCorrect <= score), next = cps.find(c => c.requiredCorrect > score), start = prev ? prev.requiredCorrect : 0;
  const from = prev ? `<span class="v3-trk__from is-done">✓ ${fa(prev.requiredCorrect)}</span>` : '<span class="v3-trk__from">شروع</span>';
  let end, endHTML, label;
  if (next) { end = next.requiredCorrect; endHTML = `<span class="v3-medal v3-medal--sm">${fa(next.requiredCorrect)}</span><span class="v3-trk__rw">پاداش: ${fa(next.rewardAmount)} کوین</span>`; label = `${fa(score)} پاسخ درست؛ چک‌پوینت بعدی ${fa(next.requiredCorrect)}`; }
  else { end = Math.min(Math.max(record, score + 1), start + 8); endHTML = `<span class="v3-trk__goal">${record > score ? 'رکورد ' + fa(record) : 'رکورد جدید'}</span>`; label = `${fa(score)} پاسخ درست؛ رکورد ${fa(record)}`; }
  let bar = '';
  for (let i = start + 1; i <= end; i++) bar += `<i class="${i <= score ? (earned && i === score ? 'new' : 'on') : i === score + 1 ? 'now' : ''}"></i>`;
  return `<div class="v3-trk" aria-label="${label}">${from}<div class="v3-trk__bar">${bar}</div>${endHTML}</div>`;
}
export const play = (t, trk, main, dock, extra, toast, dim) => stage(t + trk + `<div class="v3-main">${main}</div>` + dock, (extra || '') + toastHTML(toast), 'play', dim);

// ---------------------------------------------------------------- G01 start, G02 help
function illu() {   // approved start illustration: product-free art, no prices, never a future question's product (D-5)
  return '<div class="v3-illu" aria-hidden="true"><div class="v3-illu__art"><div class="v3-glow"></div>' +
    '<div class="v3-illu__t v3-illu__t--a"><div class="v3-img v3-img--art"></div></div><div class="v3-illu__t v3-illu__t--b"><div class="v3-img v3-img--art"></div></div>' +
    '<div class="v3-illu__tag"><span class="v3-tag__hole"></span>؟</div><i class="v3-coin v3-fl" style="width:30px;height:30px;top:8px;left:40px"></i><i class="v3-coin v3-fl" style="width:18px;height:18px;bottom:30px;right:22px"></i>' +
    '<i class="v3-spark" style="top:30px;right:60px"></i><i class="v3-spark" style="bottom:40px;left:30px;transform:scale(.6)"></i></div></div>';
}
const HL = (h, p) => `<div class="v3-hl"><h1>${h}</h1><p>${p}</p></div>`;
const HL_DEF = () => HL('گرون‌تره یا ارزون‌تر؟', 'قیمت دو کالا رو مقایسه کن، درست جواب بده و به چک‌پوینت‌ها برس.');
const HELP = `<button class="v3-link" data-act="help">${ICO('help')}راهنمای بازی</button>`;
function streakCard(b) {
  if (b.config && b.config.features && b.config.features.streak === false) return '';   // A01 streak switch off
  const s = b.streak, r = b.streakReward;
  if (s.lostNotice) return `<div class="v3-streak v3-streak--lost" role="status"><span class="v3-streak__f" aria-hidden="true">${I.flame}</span><div><b>استریکت قطع شد</b><span>امروز یه دست کامل بازی کن تا دوباره شروع بشه.</span></div>${r ? pillCoin(r) : ''}</div>`;
  const today = s.rewardToday !== 'none';
  if (s.days === 0 && !today) return `<div class="v3-streak"><span class="v3-streak__f" aria-hidden="true">${I.flame}</span><div><b>استریکت رو امروز شروع کن</b><span>جایزه بعد از اولین دست کامل</span></div>${r ? pillCoin(r) : ''}</div>`;
  const sub = s.rewardToday === 'paid' ? 'جایزه امروز گرفته شد' : s.rewardToday === 'pending' ? 'جایزه امروز در حال واریز' : b.activeRun ? 'جایزه امروز بعد از پایان این دست' : 'جایزه امروز بعد از اولین دست کامل';
  return `<div class="v3-streak"><span class="v3-streak__f" aria-hidden="true">${I.flame}</span><div><b>استریک ${fa(s.days)} روزه</b><span>${sub}</span></div>${s.rewardToday === 'paid' ? PILL_PAID : r ? pillCoin(r) : ''}</div>`;
}
function metaLine(b) {
  if (!b.record) return '<div class="v3-meta"><span>هنوز رکوردی نداری؛ اولین دستت رو بزن.</span></div>';
  const best = b.bestCheckpoint;
  return `<div class="v3-meta"><span>رکورد <b>${fa(b.record)}</b></span>${best ? `<i aria-hidden="true"></i><span class="v3-medal v3-medal--xs">${fa(best.requiredCorrect)}</span><span>بهترین عنوان <b>${esc(best.title)}</b></span>` : ''}</div>`;
}
function startPage(b, hl, cta, plays, pre = '', coins, toast) {
  return stage(ptop('قیمت‌شناس', 'بازگشت به اپ', 'exit-app', true, coins) + illu() + hl +
    `<div class="v3-panel v3-panel--end">${pre}${cta}<p class="v3-plays">${plays}</p>${streakCard(b)}${metaLine(b)}${HELP}</div>`, toastHTML(toast), 'page');
}
const playsLine = b => `<b>${fa(b.quota.playsLeft)} دست</b> از ${fa(b.quota.playsPerDay)} دست امروز باقی مونده`;

export function vStart(v, m) {
  if (v === 'G01.LOADING') return stage('<div class="v3-top"><span class="v3-round v3-skel"></span><span class="v3-skel" style="width:110px;height:26px"></span><span class="v3-skel" style="width:70px;height:36px;border-radius:999px"></span></div>' +
    '<div class="v3-illu" aria-hidden="true"><div class="v3-illu__art"><div class="v3-skel" style="position:absolute;left:30px;right:30px;top:20px;bottom:20px;border-radius:24px"></div></div></div>' +
    '<div class="v3-hl" aria-hidden="true"><div class="v3-skel" style="width:240px;height:40px;margin:0 auto 8px"></div><div class="v3-skel" style="width:290px;height:16px;margin:0 auto"></div></div>' +
    '<div class="v3-panel v3-panel--end" aria-busy="true"><div class="v3-skel v3-skel--card" style="height:60px;border-radius:18px"></div><div class="v3-skel v3-skel--card" style="width:180px;height:14px;align-self:center"></div><div class="v3-skel v3-skel--card" style="height:62px;border-radius:16px"></div></div>', '', 'page');
  if (v === 'G01.ERROR') return stage(ptop('قیمت‌شناس', 'بازگشت به اپ', 'exit-app', true, '') + statecard('wifi', 'no', 'بارگذاری نشد', 'اطلاعات بازی دریافت نشد. اتصال اینترنت رو بررسی کن و دوباره امتحان کن.',
    GOLD('تلاش دوباره', ' data-act="boot"', false) + BTN('v3-btn--outline v3-btn--block', 'بازگشت به اپ', ' data-act="exit-app"')), toastHTML(m.toast));
  const b = m.boot, coins = b.balance == null ? null : fa(b.balance);
  if (v === 'G01.QUOTA_EXHAUSTED') return startPage(b, HL('برای امروز کافیه!', `فردا با ${fa(b.quota.playsPerDay)} دست جدید برگرد و استریکت رو ادامه بده.`),
    status('', `دست‌های جدید تا <b dir="ltr" data-countdown>${untilInstant(m.untilReset)}</b> دیگه`, 'clock'), playsLine(b), '', coins, m.toast);   // no disabled start button; CP-30
  if (v === 'G01.ACTIVE_RUN') {
    const r = b.activeRun, c = r.checkpoint;
    return startPage(b, HL('یه دست نیمه‌کاره داری', 'از همون‌جا ادامه بده؛ امتیازت هنوز سر جاشه.'), GOLD(PLAYI + 'ادامه دست', ' data-act="resume"'),
      `این دست از سهمیه امروز کم شده؛ <b>${fa(b.quota.playsLeft)} دست</b> دیگه داری`, `<div class="v3-tile"><span class="v3-medal v3-medal--sm">${c ? fa(c.requiredCorrect) : '—'}</span><div><span>دست فعلی</span><b>${fa(r.score)} پاسخ درست${c ? '، چک‌پوینت ' + fa(c.requiredCorrect) : ''}</b></div></div>`, coins, m.toast);
  }
  if (v === 'G01.OPERATION_PENDING') return startPage(b, HL_DEF(), BUSY('شروع بازی'), playsLine(b), status('', 'پرداخت قبلیت هنوز در حال بررسیه. دوباره کسر نمی‌شه؛ چند لحظه صبر کن.', null, true), '…', m.toast);
  if (v === 'G01.STARTING') return startPage(b, HL_DEF(), BUSY('شروع بازی'), playsLine(b), '', coins, m.toast);
  const o = b.activeRun && !b.activeRun.onThisDevice ? b.activeRun : null;
  const note = o ? status('warn', `یه دست روی دستگاه دیگه‌ات بازه. با شروع دست جدید، اون دست با ${fa(o.score)} پاسخ درست تموم می‌شه${o.checkpoint ? ' و پاداش چک‌پوینتش برات ثبت می‌شه' : ''}.`, 'devices') : '';
  const camp = b.campaign ? status('warn', `کمپین ویژه: با رسیدن به چک‌پوینت ${fa(b.campaign.checkpointRequiredCorrect)}، کد تخفیف ${money(b.campaign.codeAmountToman)} تومانی هم می‌گیری.`, 'info') : '';   // CP-33
  return startPage(b, HL_DEF(), GOLD(PLAYI + 'شروع بازی', ' data-act="start"'), playsLine(b), camp + note, coins, m.toast);
}

export function vHelp(m) {
  const cfg = m.config;
  const steps = [
    ['<b style="font:900 13px/1 var(--f);color:var(--ink)">۲,۴۵۰</b>', 'قیمت کالای مرجع رو ببین', 'هر دور با یک کالا و قیمتش شروع می‌شه.'],
    ['<span style="width:32px;height:42px;border-radius:8px;background:linear-gradient(160deg,#FFE27A,#F4B400);color:#5E0819;display:grid;place-items:center;font:900 22px/1 var(--f);transform:rotate(-6deg)">؟</span>', 'هر وقت آماده بودی «نمایش بده»', 'کالای بعدی ظاهر می‌شه و ۱۰ ثانیه وقت داری.'],
    [`<span style="display:flex;gap:4px"><span class="v3-ab__i" style="width:24px;height:24px">${ICO('up', 14)}</span><span class="v3-ab__i" style="width:24px;height:24px">${ICO('down', 14)}</span></span>`, 'گرون‌تره یا ارزون‌تر؟', 'جواب درست امتیازت رو بالا می‌بره و کالای مرجع بعدی می‌شه.'],
    [`<span style="color:var(--warn)">${I.clock}</span>`, 'اشتباه یا تموم شدن وقت', '۱۰ ثانیه فرصت داری با دیجی‌کوین ادامه بدی؛ وگرنه دست تموم می‌شه.'],
    [`<span class="v3-medal" style="width:38px;height:38px;font-size:15px">${fa(cfg.checkpoints[0] ? cfg.checkpoints[0].requiredCorrect : 3)}</span>`, 'به چک‌پوینت‌ها برس', 'آخر دست، پاداش بالاترین چک‌پوینتت واریز می‌شه.']];
  const lis = steps.map((s, i) => `<li><div class="v3-steps__v" aria-hidden="true"><span class="v3-steps__n">${fa(i + 1)}</span>${s[0]}</div><div><b>${s[1]}</b><p>${s[2]}</p></div></li>`).join('');
  const cps = cfg.checkpoints.map(c => `<span><span class="v3-medal v3-medal--xs" style="width:26px;height:26px">${fa(c.requiredCorrect)}</span>${fa(c.rewardAmount)} کوین</span>`).join('');
  const cont = cfg.continueRule;
  const close = m.helpFirst ? 'help-done' : 'help-close';
  return stage(`<div class="v3-top"><button class="v3-round" aria-label="بستن راهنما" data-act="${close}">${ICO('x')}</button><span class="v3-brand v3-brand--sm">راهنما</span><span></span></div>` +
    '<div class="v3-hl v3-hl--sm"><h1>چطور بازی کنم؟</h1></div>' +
    `<div class="v3-panel v3-panel--fit v3-help"><ol class="v3-steps">${lis}</ol><div><p class="v3-plays v3-help__cpt">چک‌پوینت‌ها و پاداش‌ها</p><div class="v3-cps">${cps}</div>` +
    '<p class="v3-plays v3-help__note">پاداش‌ها جمع نمی‌شن؛ فقط پاداش بالاترین چک‌پوینت واریز می‌شه.</p>' +
    `<p class="v3-plays v3-help__note">هر روز ${fa(m.playsPerDay)} دست داری.${cont.enabled && cont.cap > 0 ? ` اگه اشتباه کنی و دیجی‌کوین کافی داشته باشی، در هر دست ${cont.cap === 1 ? 'یک بار' : `تا ${fa(cont.cap)} بار`} می‌تونی با ${fa(cont.cost)} دیجی‌کوین ادامه بدی.` : ''}</p></div>` +   // CP-29, numbers from config
    `<div class="v3-actions">${GOLD('فهمیدم، بزن بریم', ` data-act="${close}"`, false)}</div></div>`, '', 'page');
}

// ---------------------------------------------------------------- G03–G06 gameplay
export function leaveSheet(m) {
  if (!m.leave) return '';
  const c = m.checkpoint;
  const txt = `دست با ${fa(m.score)} پاسخ درست تموم می‌شه و دیگه نمی‌تونی ادامه بدی.${c ? ` پاداش چک‌پوینت ${fa(c.requiredCorrect)} (${fa(c.rewardAmount)} دیجی‌کوین) برات می‌مونه.` : ''}`;
  return sheet('از این دست خارج می‌شی؟', txt, m.questionOpen ? status('warn', 'زمان سؤال متوقف نمی‌شه.', 'clock') : '',
    BTNS(GOLD('ادامه بازی', ' data-act="leave-no"', false), BTN('v3-btn--danger v3-btn--block', 'خروج و پایان دست', ' data-act="leave-yes"')), '', ['warn', 'alert']);
}
/** G04: tap anywhere after the guard, or the longer automatic continuation (F-01); one continuation either way. */
export function cpOverlay(c, next, ready, guardMs, autoMs) {
  return `<div class="v3-cp" role="dialog" aria-modal="true" aria-labelledby="cp-t" aria-describedby="cp-rw" data-act="cp-go"${ready ? ' data-ready' : ''} style="--guard:${guardMs}ms"><div class="v3-burst" aria-hidden="true"></div>${confetti(26, 7)}` +
    `<div class="v3-cp__in"><div class="v3-medal v3-medal--xl" aria-hidden="true"><span>${fa(c.requiredCorrect)}</span></div><span class="v3-cp__eb">چک‌پوینت رسید</span><h2 class="v3-cp__t" id="cp-t">${esc(c.title)}</h2><span class="v3-cp__th">${fa(c.requiredCorrect)} پاسخ درست</span>` +
    `<div class="v3-cp__rw" id="cp-rw"><div>${COIN}<b>${fa(c.rewardAmount)}</b></div><span>پاداش فعلی: ${fa(c.rewardAmount)} دیجی‌کوین<small>واریز در پایان دست</small></span></div>${next ? `<span class="v3-cp__nx">بعدی: ${fa(next.requiredCorrect)} پاسخ درست، ${fa(next.rewardAmount)} دیجی‌کوین</span>` : ''}</div>` +
    `<button class="v3-cp__go" data-act="cp-go"${ready ? '' : ' aria-disabled="true"'}>برای ادامه، هر جای صفحه بزن</button>` +   // CP-35 instruction (UX brief), proposed
    `<div class="v3-cp__auto" aria-hidden="true"><i data-cpauto style="transition:transform ${autoMs}ms linear"></i></div></div>`;
}
export function revealRow(t, text, btn, attr) {
  return `<div class="v3-revealrow"${mk('reveal', 'fade')}><span class="v3-revealrow__tag" aria-hidden="true"><i></i>؟</span><div class="v3-revealrow__t"><b>${t}</b>${text ? `<span>${text}</span>` : ''}</div>` +
    `<button class="v3-btn v3-btn--gold v3-btn--block v3-btn--xl"${attr}>${btn}</button></div>`;
}
export function offerText(price, score, cp) {
  const pr = `<b class="v3-offer__p">${money(price)} تومان</b>`;
  if (!score) return `این کالا ${pr} بود. با ادامه، دست ادامه پیدا می‌کنه.`;   // CP-27 (R-58)
  return `این کالا ${pr} بود. با ادامه، ${fa(score)} پاسخ درست${cp ? ` و چک‌پوینت ${fa(cp.requiredCorrect)}` : ''} حفظ می‌شه.`;   // CP-19 (approved)
}

// ---------------------------------------------------------------- G07 result, G08 products, G09 share
export function codeCard(c, codeState) {
  const exp = c.expiresAt ? new Intl.DateTimeFormat('fa-IR', { month: 'long', day: 'numeric', timeZone: 'Asia/Tehran' }).format(new Date(c.expiresAt)) : '';
  const body = c.status === 'pending' ? '<div class="v3-code__val"><span class="v3-spin" aria-hidden="true"></span><span>در حال صدور کد…</span></div><p>اگه طول کشید، کد بعداً در کدهای تخفیف حسابت هم پیدا می‌شه.</p>'
    : `<div class="v3-code__val"><b dir="ltr">${esc(c.code)}</b>${codeState === 'copied' ? `<button class="v3-btn v3-btn--soft v3-code__copy" aria-live="polite" data-act="copy">${ICO('check', 18)}کپی شد</button>` : '<button class="v3-btn v3-btn--soft v3-code__copy" data-act="copy">کپی کد</button>'}</div><p>${exp ? `اعتبار تا ${exp}. ` : ''}${esc(c.terms)}</p>`;
  return `<section class="v3-code" aria-label="کد تخفیف جایزه"><div class="v3-code__hd"><b>کد تخفیف ${money(c.amountToman)} تومانی</b><span class="v3-chip">جایزه کمپین</span></div>${body}</section>`;
}
export function resultId(R, quotaLeft) {
  if (!R) return 'G07.LOADING';
  if (R.campaignCode && R.campaignCode.status === 'sold_out') return 'G07.CAMPAIGN_CODE.SOLD_OUT';
  if (R.campaignCode) return R.codeState === 'copied' ? 'G07.CAMPAIGN_CODE.COPIED' : R.campaignCode.status === 'pending' ? 'G07.CAMPAIGN_CODE.PENDING' : 'G07.CAMPAIGN_CODE';
  if ([R.checkpointReward, R.streak && R.streak.reward].some(x => x && x.status === 'delayed')) return 'G07.REWARD_DELAYED';
  if (R.reason === 'left') return 'G07.LEFT_RUN';
  if (R.isNewRecord) return 'G07.NEW_RECORD';
  if (quotaLeft === 0) return 'G07.QUOTA_EXHAUSTED';
  if (!R.checkpoint) return 'G07.NO_CHECKPOINT';
  return 'G07.STANDARD';
}
const pillFor = s => s === 'succeeded' ? PAID : s === 'delayed' ? LATE : WAIT;
export function vResult(m) {
  const R = m.result;
  if (!R) return stage(ptop('نتیجه دست', 'بازگشت به شروع', 'to-start', false, '…') + `<div class="v3-rhero" aria-live="polite"><b class="v3-rhero__n">${fa(m.lastScore || 0)}</b><span class="v3-rhero__l">پاسخ درست در این دست</span><span class="v3-rhero__sub">در حال ثبت نتیجه…</span></div>` +
    '<div class="v3-panel" aria-busy="true">' + status('', 'در حال ثبت نتیجه، رکورد و پاداش…', null, true) +
    `<div class="v3-skel v3-skel--card" style="height:66px;border-radius:18px"></div><div class="v3-skel v3-skel--card" style="height:96px;border-radius:16px"></div><div class="v3-actions">${GOLD('دوباره بازی کن', L)}</div></div>`, '', 'page');
  let rh;
  if (R.isNewRecord) rh = `<div class="v3-rhero v3-rhero--record" aria-live="polite"><div class="v3-burst v3-burst--gold" aria-hidden="true"></div>${confetti(24, 11)}<span class="v3-banner">رکورد جدید!</span><b class="v3-rhero__n">${fa(R.score)}</b><span class="v3-rhero__l">پاسخ درست در این دست</span>${R.previousRecord ? `<span class="v3-rhero__sub">رکورد قبلی: ${fa(R.previousRecord)}</span>` : ''}</div>`;
  else rh = `<div class="v3-rhero" aria-live="polite">${R.reason === 'left' ? '<span class="v3-flag">از دست خارج شدی</span>' : ''}<b class="v3-rhero__n">${fa(R.score)}</b><span class="v3-rhero__l">پاسخ درست در این دست</span><span class="v3-rhero__sub">رکورد: ${fa(R.record)}</span></div>`;
  const first = m.config.checkpoints[0];
  const ach = R.checkpoint ? `<div class="v3-ach"><span class="v3-medal"><span>${fa(R.checkpoint.requiredCorrect)}</span></span><div><span>آخرین عنوان</span><b>${esc(R.checkpoint.title)}</b></div></div>`
    : `<div class="v3-ach v3-ach--none"><span class="v3-medal v3-medal--none"><span>—</span></span><div><span>هنوز چک‌پوینتی نگرفتی</span><b>اولی با ${fa(first ? first.requiredCorrect : 3)} پاسخ درست</b></div></div>`;
  const F = m.config.features || {}, P = F.products !== false, SH = F.share !== false;   // A01 switches of this run's version
  const cr = R.checkpointReward, sr = R.streak && R.streak.reward;
  const rows = [['پاداش بالاترین چک‌پوینت', R.checkpoint && cr && cr.amount > 0 ? CB(cr.amount) + pillFor(cr.status) : '<b class="is-none">—</b>'],
    sr ? [`جایزه استریک ${fa(R.streak.days)} روزه`, CB(sr.amount) + pillFor(sr.status)] : F.streak === false ? null : ['استریک', `<b>${fa(R.streak ? R.streak.days : 0)} روز</b>`]].filter(Boolean);
  let st = '';
  const delayed = [cr, sr].some(x => x && x.status === 'delayed');
  if (delayed) st = status('warn', 'واریز با تأخیر انجام می‌شه.', 'clock');   // R-24: on the result only
  else if (['left', 'game_off', 'inactive', 'replaced'].includes(R.reason)) st = status('ok', R.reason === 'game_off' ? 'بازی موقتاً متوقف شد؛ پاداش بالاترین چک‌پوینتت ثبت شد.' : 'پاداش بالاترین چک‌پوینتت ثبت شد.', 'check');
  else if (R.continueLimitUsed) st = status('', 'فرصت ادامه این دست رو قبلاً استفاده کردی.', 'info');   // CP-24
  else if (R.noOfferReason === 'no_funds') st = status('', 'برای ادامه دیجی‌کوین کافی نداشتی.', 'info');   // CP-32
  const nx = R.nextGoal;
  if (!st && nx && nx.remaining <= 2 && R.reason !== 'no_products') st = status('', `فقط ${fa(nx.remaining)} پاسخ تا ${esc(nx.title)} (${fa(nx.reward)} دیجی‌کوین) کم داشتی.`, 'info');   // CP-25
  const quota = R.quota.playsLeft === 0;
  const cta = !quota ? GOLD('دوباره بازی کن', ' data-act="start"') : P ? GOLD(`${ICO('list')}کالاهای این دست`, ' data-act="products"') : GOLD('بازگشت به شروع', ' data-act="to-start"');
  const plays = quota ? `دست‌های جدید تا <b dir="ltr" data-countdown>${untilInstant(m.untilReset)}</b> دیگه` : `<b>${fa(R.quota.playsLeft)} دست</b> دیگه برای امروز داری`;
  const sb = m.shareBusy ? `<button class="v3-btn v3-btn--soft" disabled aria-disabled="true" aria-busy="true"><span class="v3-spin" aria-hidden="true"></span>اشتراک‌گذاری</button>`
    : `<button class="v3-btn v3-btn--soft" data-act="share">${ICO('share')}اشتراک‌گذاری</button>`;
  const second = [SH ? sb : '', !P ? '' : quota ? `<button class="v3-btn v3-btn--soft" data-act="to-start">${ICO('back')}بازگشت به شروع</button>`
    : `<button class="v3-btn v3-btn--soft" data-act="products">${ICO('list')}کالاهای این دست</button>`].filter(Boolean);
  const sec = second.length === 2 ? `<div class="v3-row2">${second.join('')}</div>` : second.length ? second[0].replace('v3-btn v3-btn--soft', 'v3-btn v3-btn--soft v3-btn--block') : '';
  const tst = m.toast && m.toast.page ? toastHTML(m.toast) : '';
  const out = R.campaignCode && R.campaignCode.status === 'sold_out' ? status('', 'کدهای تخفیف این کمپین تموم شد؛ پاداش دیجی‌کوینت سر جاشه.', 'info') : '';   // CP-31
  const code = R.campaignCode && R.campaignCode.status !== 'sold_out' ? codeCard(R.campaignCode, R.codeState) : '';
  const info = out + st + code + ach + `<div class="v3-rows">${rows.map(([a, b]) => `<div><span>${a}</span>${b}</div>`).join('')}</div>`;
  return stage(ptop('نتیجه دست', 'بازگشت به شروع', 'to-start', false, R.balance == null ? null : fa(R.balance)) + rh +
    `<div class="v3-panel">${info}<div class="v3-actions">${tst}${cta}<p class="v3-plays">${plays}</p>${sec}</div></div>`, m.shareSheet || '', 'page');
}
export function vProducts(v, m) {
  let body;
  if (v === 'G08.LOADING') body = '<div aria-busy="true" style="display:flex;flex-direction:column;gap:12px">' + Array(4).fill('<div class="v3-prow" aria-hidden="true"><div class="v3-img v3-skel v3-skel--card" style="--img:84px"></div><div class="v3-prow__b"><span class="v3-skel v3-skel--card" style="height:16px"></span><span class="v3-skel v3-skel--card" style="width:70%;height:16px;margin-top:6px"></span><span class="v3-skel v3-skel--card" style="width:50%;height:14px;margin-top:10px"></span></div></div>').join('') + '</div>';
  else if (v === 'G08.ERROR') body = statecard('alert', 'no', 'کالاها بارگذاری نشد', 'اتصال رو بررسی کن و دوباره امتحان کن.', GOLD('تلاش دوباره', ' data-act="products"', false));
  else {
    const P = m.products, guide = P.lossProductId && !m.lossGuided;
    const mark = p => p.result === 'correct' ? '<span class="v3-prow__m v3-prow__m--ok" role="img" aria-label="پاسخ درست">✓</span>'
      : (p.result === 'wrong' || p.result === 'timeout') ? `<span class="v3-prow__m v3-prow__m--no" role="img" aria-label="${p.result === 'timeout' ? 'وقت تموم شد' : 'پاسخ اشتباه'}">✕</span>` : '';
    body = status('', 'قیمت‌ها مربوط به زمان بازی‌اند؛ قیمت فعلی رو در صفحه کالا ببین.', 'info') + P.items.map(p => {
      const loss = p.isLoss;
      const price = p.gamePrice == null ? '<b>—</b>' : `<b>${money(p.gamePrice)}</b> تومان`;
      return `<article class="v3-prow${loss ? ' v3-prow--miss' + (guide ? ' is-guide' : '') : ''}"${loss ? ' data-loss' : ''} aria-label="${esc(p.title)}">${img(p)}<div class="v3-prow__b">${loss ? '<span class="v3-chip v3-chip--no">اینجا باختی</span>' : ''}<h3>${mark(p)}${title(p.displayTitle)}</h3><div class="v3-prow__p">قیمت در بازی ${price}</div><button class="v3-open" data-act="open-product" data-link="${esc(p.link)}" data-id="${esc(p.productId)}">مشاهده کالا ${ICO('ext', 16)}</button></div></article>`;   // CP-28
    }).join('');
  }
  return stage(ptop('کالاهای این دست', 'بازگشت به نتیجه', 'back-result', false, '') + `<div class="v3-panel v3-panel--fill v3-panel--end" data-scroll="products">${body}</div>`, toastHTML(m.toast), 'page');   // the one scrolling area (UX-4)
}
export function shareAsset(s) {   // no player name (R-12)
  return `<div class="v3-share" role="img" aria-label="تصویر اشتراک: ${fa(s.score)} پاسخ درست"><div class="v3-burst v3-burst--gold" aria-hidden="true" style="top:42%"></div>` +
    `<div><div class="v3-share__brand">${esc(s.gameName)}</div><div class="v3-share__sub">بازی گرون‌تره یا ارزون‌تر</div></div>` +
    `<div><div class="v3-share__n">${fa(s.score)}</div><div class="v3-share__l">پاسخ درست در یک دست</div></div>` +
    (s.checkpointTitle ? `<div class="v3-share__card"><span class="v3-medal"><span>✓</span></span><div><span>آخرین عنوان</span><b>${esc(s.checkpointTitle)}</b></div></div>` : '') +
    `<div class="v3-share__foot">${s.streakDays == null ? '' : `استریک ${fa(s.streakDays)} روزه. `}تو چندتا می‌زنی؟</div></div>`;
}

// ---------------------------------------------------------------- G10 connection, G11 unavailable
export function vSystem(v, m) {
  const pg = (ic, k, t, p, a, coins) => stage(ptop('قیمت‌شناس', 'بازگشت به اپ', 'exit-app', true, coins) + statecard(ic, k, t, p, a), toastHTML(m.toast));
  const R = m.ended || {};
  const cpTxt = R.checkpoint ? ` و پاداش چک‌پوینت ${fa(R.checkpoint.requiredCorrect)}` : '';
  if (v === 'G10.RESUMING') return stage(ptop('قیمت‌شناس', 'بازگشت به اپ', 'exit-app', true, '…') + '<div class="v3-wait" role="status"><span class="v3-spin" aria-hidden="true"></span><h2>برگشتی!</h2><p>داریم آخرین وضعیت دستت رو از سرور می‌گیریم…</p></div>');
  if (v === 'G10.INACTIVITY_END') return pg('clock', 'warn', 'این دست تموم شد', `بیشتر از ۳ دقیقه بین سؤال‌ها کاری انجام نشد. دستت با ${fa(R.score || 0)} پاسخ درست${cpTxt} ثبت شد.`, GOLD('دیدن نتیجه', ' data-act="show-result"', false));
  if (v === 'G11.GAME_OFF') return pg('power', '', 'بازی موقتاً در دسترس نیست', m.playerMessage ? esc(m.playerMessage) : 'در حال به‌روزرسانی هستیم. کمی بعد دوباره سر بزن.', BTN('v3-btn--outline v3-btn--block', 'بازگشت به اپ', ' data-act="exit-app"'), '');
  if (v === 'G11.AUTH_ERROR') return pg('user', 'no', 'ورود انجام نشد', 'برای بازی باید وارد حساب دیجی‌کالا باشی. از داخل اپ دوباره وارد شو.', GOLD('بازگشت به اپ', ' data-act="exit-app"', false), '');
  if (v === 'G11.SERVICE_ERROR') return pg('alert', 'no', 'یه مشکلی پیش اومد', 'سرویس بازی الان جواب نمی‌ده. امتیاز و دست‌هات محفوظه؛ چند لحظه دیگه دوباره امتحان کن.', GOLD('تلاش دوباره', ' data-act="boot"', false) + BTN('v3-btn--outline v3-btn--block', 'بازگشت به اپ', ' data-act="exit-app"'), '');
  if (v === 'G11.OTHER_DEVICE') return pg('devices', '', 'این دست تموم شد', `روی دستگاه دیگه‌ای دست جدید شروع کردی. این دست با ${fa(R.score || 0)} پاسخ درست ثبت شد${R.checkpoint ? ' و پاداش چک‌پوینتش برات می‌مونه' : ''}.`, GOLD('دیدن نتیجه', ' data-act="show-result"', false) + BTN('v3-btn--outline v3-btn--block', 'بازگشت به شروع', ' data-act="boot"'));
  if (v === 'G11.BLOCKED') return pg('lock', 'no', 'بازی غیرفعال شده است', '', BTN('v3-btn--outline v3-btn--block', 'بازگشت به اپ', ' data-act="exit-app"'), '');   // exact copy, no support link (R-61)
  return '';
}

export { COIN, L, ICO, PLAYI, CB };
