// Screen markup in the v3 «ویترین» design system classes. Views are pure functions of the view model: they never read
// the network or the clock themselves. All text from data goes through esc()/title(). Light theme only (brief 2026-10-20).
import { I } from './icons.js';
import { fa, money, esc, title, untilInstant } from './fmt.js';
import { character } from './characters.js';

const ICO = (k, s = 20) => I[k].replace('width="24" height="24"', `width="${s}" height="${s}"`);
const COIN = '<i class="v3-coin" aria-hidden="true"></i>';
const L = ' disabled aria-disabled="true"';
export const Q_TEXT = 'قیمتش گرون‌تره یا ارزون‌تر؟';   // exact question (R-17)
export const INTRO = 'دو تا کالا رو ببین، قیمت رو حدس بزن و جواب بده!';   // start screen intro, exact (brief 2026-10-20)

// ---------------------------------------------------------------- primitives
export const keyOf = card => 'p' + hash(card.imageUrl || card.displayTitle || '');
function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0).toString(36); }
const mk = (k, fx) => k ? ` data-m="${k}" data-mfx="${fx}"` : '';
export function img(card, loading, keyed, onload) {
  const key = keyed ? mk('img:' + keyOf(card), 'box') : '';
  if (loading === 'skeleton') return `<div class="v3-img is-loading v3-skel v3-skel--card" role="img" aria-label="تصویر در حال بارگذاری"${key}></div>`;
  if (card.art) return `<div class="v3-img"${key}>${card.art}</div>`;   // guide demo: drawn art, never a catalog product
  return `<div class="v3-img"${key}><img src="${esc(card.imageUrl)}" alt="" decoding="async"${onload ? ` data-onload="${onload}"` : ''}></div>`;
}
export function priceHTML(n, cls = '', attrs = '') {
  const d = String(Math.round(n)).length, size = d >= 10 ? ' v3-price--xl' : d >= 9 ? ' v3-price--long' : '';
  return `<div class="v3-price${size}${cls}"${attrs}><b>${money(n)}</b><small>تومان</small></div>`;
}
const ttl = card => `<h3${mk('ttl:' + keyOf(card), 'text')}>${title(card.displayTitle)}</h3>`;
// the first product: no «کالای مرجع» label on screen (brief 2026-10-20); screen readers still hear what it is
export const refTicket = card => `<article class="v3-ticket" data-tour="first" aria-label="کالای اول، ${money(card.price)} تومان"${mk('card:' + keyOf(card), 'frame')}>${img(card, false, true)}<div class="v3-ticket__b">${ttl(card)}${priceHTML(card.price, '', mk('prc:' + keyOf(card), 'text'))}</div></article>`;
// UX-6: before «نمایش بده» the first product is the large card; it shrinks into the ticket when the question is requested
export const refBig = card => `<article class="v3-hero v3-hero--ref" data-tour="first" aria-label="کالای اول، ${money(card.price)} تومان"${mk('card:' + keyOf(card), 'frame')}>${img(card, false, true)}<div class="v3-hero__b">${ttl(card)}${priceHTML(card.price, '', mk('prc:' + keyOf(card), 'text'))}</div></article>`;

/**
 * Question row with the timer. Timer modes: countdown (white → orange, red in the last 3 s, on a dark plate so every
 * colour reads on the red stage), 'idle' (10 before «نمایش بده»), 'pending' (an answer is being processed: the countdown
 * is replaced by a spinner), 'wait' (the visible time is over and the server is recording it), 'off'.
 */
export function ask(text, o = {}) {
  const sp = o.spin ? '<span class="v3-spin" aria-hidden="true"></span>' : '';
  let tm;
  if (o.mode === 'idle') tm = `<div class="v3-timer is-idle" data-tour="timer" aria-label="۱۰ ثانیه پس از نمایش"><b>${fa(10)}<small>ثانیه</small></b></div>`;
  else if (o.mode === 'off') tm = '<div class="v3-timer is-off" aria-hidden="true"><b></b></div>';
  else if (o.mode === 'pending') tm = '<div class="v3-timer is-busy" data-tour="timer" role="status" aria-label="در حال بررسی پاسخ"><b><span class="v3-spin" aria-hidden="true"></span></b></div>';
  else if (o.mode === 'wait') tm = `<div class="v3-timer is-zero" data-tour="timer" role="status" aria-label="زمان تموم شد"><b>${fa(0)}</b></div>`;
  else tm = `<div class="v3-timer${o.low ? ' is-low' : ''}" data-tour="timer" style="--p:${o.p};--tc:${o.tc}" role="timer" aria-live="off" aria-label="${o.t} ثانیه" data-timer><b>${o.t}</b></div>`;
  return `<div class="v3-ask"${mk('ask', 'fade')}><p${o.muted ? ' class="is-muted"' : ''}>${sp}${text}</p>${tm}</div>`;
}
const HIDDEN = k => `<span class="v3-hidden" role="img" aria-label="قیمت مخفی"${mk('prc:' + k, 'text')}>${I.lock}<b>؟</b><small>تومان</small></span>`;
const ARR = {
  down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14M6 13l6 6 6-6"/></svg>',
  up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M6 11l6-6 6 6"/></svg>',
  eq: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"><path d="M6 9h12M6 15h12"/></svg>' };
export const DIRW = { up: 'گرون‌تر', down: 'ارزون‌تر', eq: 'قیمت برابر' };
export const dirOf = d => d === 'higher' ? 'up' : d === 'lower' ? 'down' : 'eq';
/** Second product: hidden while answerable; after the answer, the authorised revealed price sits on it (UX-1). No «کالای دوم» label. */
export function hero(card, st, loading, dir, revealedPrice) {
  const k0 = keyOf(card);
  const cls = { hidden: '', correct: ' is-correct', wrong: ' is-wrong', timeout: ' is-timeout' }[st];
  const hint = loading ? '<p class="v3-hero__hint">عکس در راهه؛ از روی اسم هم می‌تونی جواب بدی.</p>' : '';   // CP-22 (R-58)
  if (st === 'hidden') return `<article class="v3-hero" data-tour="second" aria-label="کالای دوم، قیمت مخفی"${mk('card:' + k0, 'frame')}>${img(card, false, true, 'q')}<div class="v3-hero__b">${ttl(card)}${hint}${HIDDEN(k0)}</div></article>`;
  const k = { correct: 'ok', wrong: 'no', timeout: 'time' }[st], ic = { ok: I.check, no: '✕', time: '!' }[k];
  const lead = st === 'correct' ? `<span class="v3-dir" role="img" aria-label="${DIRW[dir]}">${ARR[dir]}</span>` : '<span>قیمت واقعی</span>';   // «قیمت واقعی»: CP-34, proposed
  const pr = `<div class="v3-reveal v3-reveal--${k}"${mk('prc:' + k0, 'text')}>${lead}${priceHTML(revealedPrice, ' v3-price--reveal' + (k === 'ok' ? ' v3-price--ok' : ''))}</div>`;
  return `<article class="v3-hero${cls}" data-tour="second" aria-label="کالای دوم، ${{ ok: 'پاسخ درست', no: 'پاسخ اشتباه', time: 'وقت تموم شد' }[k]}"${mk('card:' + k0, 'frame')}><span class="v3-hero__badge v3-hero__badge--${k}" aria-hidden="true"${mk('badge:' + k0, 'fade')}>${ic}</span>${img(card, false, true)}<div class="v3-hero__b v3-hero__b--reveal">${ttl(card)}${pr}</div></article>`;
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
/** Answer keys: stable position and equal prominence; accessible names include the first product's price (R-14). */
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
  return `<div class="v3-dock${quiet ? ' v3-dock--quiet' : ''}"${mk('dock', 'fade')}><div class="v3-ans" role="group" aria-label="پاسخ" data-tour="answers"><button class="v3-ab${A.c}"${A.a}${lb('گرون‌تر')}>${A.i}گرون‌تر</button><button class="v3-ab${B.c}"${B.a}${lb('ارزون‌تر')}>${B.i}ارزون‌تر</button></div></div>`;
}
function confetti(n, seed) {
  let s = seed; const rnd = () => (s = (s * 9301 + 49297) % 233280) / 233280; const cols = ['#FFC633', '#FFFFFF', '#22C49A', '#FFE9A6', '#FF8FA0'];
  let o = ''; for (let i = 0; i < n; i++) o += `<i style="left:${2 + Math.floor(rnd() * 94)}%;top:${Math.floor(rnd() * 80)}%;width:${[6, 8, 10][Math.floor(rnd() * 3)]}px;height:${[10, 14, 6][Math.floor(rnd() * 3)]}px;background:${cols[Math.floor(rnd() * 5)]};transform:rotate(${Math.floor(rnd() * 180)}deg);animation-delay:${Math.floor(rnd() * 200)}ms"></i>`;
  return '<div class="v3-confetti" aria-hidden="true">' + o + '</div>';
}
export function stage(inner, extra = '', kind = '', dim = false) {
  const modal = /v3-sheetwrap|pe-host|pe-daily-wrap/.test(extra) ? ' inert' : '';   // the tour keeps its target reachable itself
  const kinds = kind ? kind.split(' ') : [];
  return `<div class="v3-stage${kinds.map(k => ' v3-stage--' + k).join('')}${dim ? ' v3-stage--dim' : ''}"><div class="v3-screen${kinds.includes('page') ? ' v3-screen--page' : ''}"${modal}>${inner}</div>${extra}</div>`;
}
export function sheet(t, text, body, btns, st, icon, ring) {
  let lead = '';
  if (ring) lead = ring; else if (icon) lead = `<span class="v3-sheet__icon v3-sheet__icon--${icon[0]}" aria-hidden="true">${I[icon[1]] || icon[1]}</span>`;
  return `<div class="v3-sheetwrap"><section class="v3-sheet" role="dialog" aria-modal="true" aria-labelledby="sh-t"><span class="v3-grip" aria-hidden="true"></span>` +
    `<div class="v3-sheet__hd">${lead}<div><h3 id="sh-t">${t}</h3>${text ? `<p>${text}</p>` : ''}</div></div>${body || ''}${btns || ''}${st || ''}</section></div>`;
}
export function ring(remMs, totalMs) {
  const s = fa(Math.ceil(remMs / 1000));
  return `<div class="v3-ring${remMs <= 3000 ? ' is-low' : ''}" style="--p:${(remMs / totalMs).toFixed(3)}" role="timer" data-ring aria-label="${s} ثانیه برای تصمیم"><b>${s}</b><small>ثانیه</small></div>`;
}
/** The one place a DigiCoin balance is shown (brief 2026-10-20): inside the extra-life purchase sheet. */
export const cost = (bal, costN) => `<div class="v3-cost"><div class="v3-cost__fee"><span>هزینه ادامه</span><b>${COIN}${fa(costN)} دیجی‌کوین</b></div><div><span>موجودی شما</span><b>${bal}</b></div></div>`;
export function status(kind, text, icon, spin, alert) {
  const lead = spin ? '<span class="v3-spin"></span>' : icon ? ICO(icon, 16) : '';
  return `<div class="v3-status${kind ? ' v3-status--' + kind : ''}" role="${alert ? 'alert' : 'status'}"><span class="v3-status__i" aria-hidden="true">${lead}</span><span>${text}</span></div>`;
}
export const stagepill = (t, ic, no, btn) => `<div class="v3-stagepill${no ? ' v3-stagepill--no' : ''}" role="status">${ICO(ic, 18)}<span>${t}</span>${btn || ''}</div>`;
export function toastHTML(t) {
  if (!t) return '';
  return `<div class="v3-toast${t.no ? ' v3-toast--no' : ''}" role="${t.no ? 'alert' : 'status'}"><i aria-hidden="true">${t.no ? '!' : '✓'}</i>${t.text}</div>`;
}
export const BTN = (cls, t, a = '') => `<button class="v3-btn ${cls}"${a}>${t}</button>`;
export const GOLD = (t, a = '', xl = true) => BTN('v3-btn--gold v3-btn--block' + (xl ? ' v3-btn--xl' : ''), t, a);
export const BUSY = (t, xl = true) => BTN('v3-btn--block' + (xl ? ' v3-btn--xl' : ''), `<span class="v3-spin" aria-hidden="true"></span>${t}`, ' disabled aria-disabled="true" aria-busy="true"');
export const BTNS = (...b) => '<div class="v3-sheet__btns">' + b.join('') + '</div>';
export const SKIP = (a = ' data-act="skip"', t = 'رد کردن') => BTN('v3-btn--outline v3-btn--block', t, a);
export const statecard = (ic, kind, t, text, actions) => `<div class="v3-statecard"><div class="v3-statecard__i${kind ? ' v3-statecard__i--' + kind : ''}" aria-hidden="true">${I[ic]}</div><h2>${t}</h2>${text ? `<p>${text}</p>` : ''}<div class="v3-statecard__a">${actions}</div></div>`;
const PLAYI = '<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>';
const CB = n => `<b>${COIN}${fa(n)}</b>`;
// checkpoint flag standing on the bar: the pole sits exactly on the threshold, the pennant waves toward the bar's end (RTL)
const FLAG = '<svg viewBox="0 0 18 24" width="20" height="27" aria-hidden="true"><path d="M15 2v21" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/><path d="M14 2.5H3l3 4.2-3 4.3h11Z" fill="currentColor" stroke="rgba(0,0,0,.25)" stroke-width="1"/></svg>';

// ---------------------------------------------------------------- HUD
/** Gameplay top bar: back · the correct-answer count only (no caption, record, balance or streak) · a balancing spacer. */
export function top(m) {
  const lock = m.backLocked;
  const p = m.plus ? '<span class="v3-plus" dir="ltr" aria-hidden="true">+۱</span>' : '';
  return `<div class="v3-top" data-tour="hud"><button class="v3-round" aria-label="خروج از دست" data-act="back"${lock ? L : ''}>${I.back}</button>` +
    `<div class="v3-score${m.plus ? ' is-up' : ''}" aria-live="polite"><b aria-hidden="true">${fa(m.score)}</b>${p}<span class="pe-sr">${fa(m.score)} جواب درست</span></div>` +
    '<span class="v3-top__end" aria-hidden="true"></span></div>';
}
/** Page header: back on the right (RTL start), title, nothing on the left. No DigiCoin balance anywhere (brief 2026-10-20). */
export function ptop(t, back, act, brand) {
  return `<div class="v3-top"><button class="v3-round" aria-label="${back}" data-act="${act}">${I.back}</button><span class="v3-brand${brand ? '' : ' v3-brand--sm'}">${t}</span><span class="v3-top__end" aria-hidden="true"></span></div>`;
}
/**
 * One continuous progress bar: fills from zero with the count and is full at the LAST checkpoint; checkpoint thresholds
 * are flags (reached ones gold); ∞ marks that the count goes on after the last one. No reward amounts on the bar.
 */
export function track(score, cps, earned) {
  const last = cps.length ? cps[cps.length - 1].requiredCorrect : 1, next = cps.find(c => c.requiredCorrect > score);
  const f = Math.min(1, score / last);
  const flags = cps.map(c => `<span class="v3-pflag${c.requiredCorrect <= score ? ' is-done' : ''}${earned && c.requiredCorrect === score ? ' is-new' : ''}" style="--x:${(100 * c.requiredCorrect / last).toFixed(2)}%">${FLAG}</span>`).join('');
  const label = next ? `${fa(score)} جواب درست؛ چک‌پوینت بعدی با ${fa(next.requiredCorrect)} جواب درست` : `${fa(score)} جواب درست؛ همه‌ی چک‌پوینت‌ها رو گرفتی`;
  return `<div class="v3-prog" data-tour="progress" role="progressbar" aria-valuemin="0" aria-valuemax="${last}" aria-valuenow="${Math.min(score, last)}" aria-label="${label}">` +
    `<div class="v3-prog__bar"><i class="v3-prog__fill${earned ? ' is-up' : ''}" style="--f:${f.toFixed(4)}"></i>${flags}</div><span class="v3-prog__inf" aria-hidden="true">∞</span></div>`;
}
export const play = (t, trk, main, dock, extra, toast, dim) => stage(t + trk + `<div class="v3-main">${main}</div>` + dock, (extra || '') + toastHTML(toast), 'play', dim);

// ---------------------------------------------------------------- G01 start
function illu() {   // approved start illustration: product-free art, no prices, never a future question's product (D-5)
  return '<div class="v3-illu" aria-hidden="true"><div class="v3-illu__art"><div class="v3-glow"></div>' +
    '<div class="v3-illu__t v3-illu__t--a"><div class="v3-img v3-img--art"></div></div><div class="v3-illu__t v3-illu__t--b"><div class="v3-img v3-img--art"></div></div>' +
    '<div class="v3-illu__tag"><span class="v3-tag__hole"></span>؟</div><i class="v3-coin v3-fl" style="width:30px;height:30px;top:8px;left:40px"></i><i class="v3-coin v3-fl" style="width:18px;height:18px;bottom:30px;right:22px"></i>' +
    '<i class="v3-spark" style="top:30px;right:60px"></i><i class="v3-spark" style="bottom:40px;left:30px;transform:scale(.6)"></i></div></div>';
}
const HL = (h, p) => `<div class="v3-hl"><h1>${h}</h1><p>${p}</p></div>`;
const HL_DEF = () => HL('گرون‌تره یا ارزون‌تر؟', INTRO);
const HELP = `<button class="v3-link" data-act="guide">${ICO('help')}راهنمای بازی</button>`;
function metaLine(b) {
  if (!b.record) return '';
  const best = b.bestCheckpoint;
  return `<div class="v3-meta"><span>رکورد <b>${fa(b.record)}</b></span>${best ? `<i aria-hidden="true"></i><span class="v3-medal v3-medal--xs">${fa(best.requiredCorrect)}</span><span>بهترین عنوان <b>${esc(best.title)}</b></span>` : ''}</div>`;
}
function startPage(b, hl, cta, plays, pre = '', extra = '') {
  return stage(ptop('قیمت‌شناس', 'بازگشت به اپ', 'exit-app', true) + illu() + hl +
    `<div class="v3-panel v3-panel--end">${pre}${cta}<p class="v3-plays">${plays}</p>${metaLine(b)}${HELP}</div>`, extra, 'page');
}
const playsLine = b => `<b>${fa(b.quota.playsLeft)} دست</b> از ${fa(b.quota.playsPerDay)} دست امروز باقی مونده`;

export function vStart(v, m) {
  // neutral loading: nothing of the start screen shows before we know whether the onboarding comes first
  if (v === 'G01.LOADING') return stage('<div class="v3-wait v3-wait--boot" role="status" aria-label="در حال بارگذاری"><span class="v3-brand">قیمت‌شناس</span><span class="v3-spin" aria-hidden="true"></span></div>', '', 'page');
  if (v === 'G01.ERROR') return stage(ptop('قیمت‌شناس', 'بازگشت به اپ', 'exit-app', true) + statecard('wifi', 'no', 'بارگذاری نشد', 'اطلاعات بازی دریافت نشد. اتصال اینترنت رو بررسی کن و دوباره امتحان کن.',
    GOLD('تلاش دوباره', ' data-act="boot"', false) + BTN('v3-btn--outline v3-btn--block', 'بازگشت به اپ', ' data-act="exit-app"')), toastHTML(m.toast));
  const b = m.boot, extra = (m.modal || '') + toastHTML(m.toast);
  if (v === 'G01.QUOTA_EXHAUSTED') return startPage(b, HL('برای امروز کافیه!', `فردا با ${fa(b.quota.playsPerDay)} دست جدید برگرد.`),
    status('', `دست‌های جدید تا <b dir="ltr" data-countdown>${untilInstant(m.untilReset)}</b> دیگه`, 'clock'), playsLine(b), '', extra);   // no disabled start button; CP-30
  if (v === 'G01.ACTIVE_RUN') {
    const r = b.activeRun, c = r.checkpoint;
    return startPage(b, HL('یه دست نیمه‌کاره داری', 'از همون‌جا ادامه بده؛ امتیازت هنوز سر جاشه.'), GOLD(PLAYI + 'ادامه دست', ' data-act="resume"'),
      `این دست از سهمیه امروز کم شده؛ <b>${fa(b.quota.playsLeft)} دست</b> دیگه داری`, `<div class="v3-tile"><span class="v3-medal v3-medal--sm">${c ? fa(c.requiredCorrect) : '—'}</span><div><span>دست فعلی</span><b>${fa(r.score)} پاسخ درست${c ? '، چک‌پوینت ' + fa(c.requiredCorrect) : ''}</b></div></div>`, extra);
  }
  if (v === 'G01.OPERATION_PENDING') return startPage(b, HL_DEF(), BUSY('شروع بازی'), playsLine(b), status('', 'پرداخت قبلیت هنوز در حال بررسیه. دوباره کسر نمی‌شه؛ چند لحظه صبر کن.', null, true), extra);
  if (v === 'G01.STARTING') return startPage(b, HL_DEF(), BUSY('شروع بازی'), playsLine(b), '', extra);
  const o = b.activeRun && !b.activeRun.onThisDevice ? b.activeRun : null;
  const note = o ? status('warn', `یه دست روی دستگاه دیگه‌ات بازه. با شروع دست جدید، اون دست با ${fa(o.score)} پاسخ درست تموم می‌شه${o.checkpoint ? ' و پاداش چک‌پوینتش برات ثبت می‌شه' : ''}.`, 'devices') : '';
  const camp = b.campaign ? status('warn', `کمپین ویژه: با رسیدن به چک‌پوینت ${fa(b.campaign.checkpointRequiredCorrect)}، کد تخفیف ${money(b.campaign.codeAmountToman)} تومانی هم می‌گیری.`, 'info') : '';   // CP-33
  return startPage(b, HL_DEF(), GOLD(PLAYI + 'شروع بازی', ' data-act="start"'), playsLine(b), camp + note, extra);
}

// ---------------------------------------------------------------- G02 guide / onboarding spotlight
/**
 * Spotlight over the demo scene: everything but the target is dimmed by the hole's own shadow; four blockers around
 * the hole (and one over it unless the step asks to tap the target) stop every touch outside the lesson. Geometry is
 * set by the controller after layout, on resize and on scroll.
 */
export function tourOverlay(s) {
  const dots = Array.from({ length: s.total }, (_, i) => `<i${i === s.index ? ' class="is-on"' : ''}></i>`).join('');
  const close = s.closable ? `<button class="pe-tour__x" data-act="tour-close" aria-label="بستن راهنما">${ICO('x', 18)}</button>` : '';
  const err = s.error ? `<p class="pe-tour__err" role="alert">${s.error}</p>` : '';
  const btn = s.action ? `<button class="v3-btn v3-btn--gold v3-btn--block" data-act="${s.action}"${s.busy ? ' disabled aria-disabled="true" aria-busy="true"' : ''}>${s.busy ? '<span class="v3-spin" aria-hidden="true"></span>' : ''}${s.actionLabel}</button>` : '';
  const prev = s.index > 0 && !s.final ? `<button class="v3-btn v3-btn--outline pe-tour__prev" data-act="tour-prev">قبلی</button>` : '';
  return `<div class="pe-tour pe-modal${s.target ? '' : ' is-center'}" role="dialog" aria-modal="true" aria-labelledby="tour-t" aria-describedby="tour-d" data-step="${s.id}">` +
    `<div class="pe-tour__hole" data-hole aria-hidden="true"></div>${['t', 'r', 'b', 'l'].map(k => `<div class="pe-tour__block" data-block="${k}"></div>`).join('')}` +
    `${s.interact ? '' : '<div class="pe-tour__block" data-block="hole"></div>'}` +
    `<div class="pe-tour__tip" data-tip>${close}<span class="pe-tour__n" aria-hidden="true">${fa(s.index + 1)} از ${fa(s.total)}</span><h2 id="tour-t">${s.title}</h2><p id="tour-d">${s.text}</p>${err}` +
    `<div class="pe-tour__dots" aria-hidden="true">${dots}</div>${s.hint ? `<p class="pe-tour__hint">${ICO('info', 16)}${s.hint}</p>` : ''}<div class="pe-tour__btns">${btn}${prev}</div></div></div>`;
}

// ---------------------------------------------------------------- G01 daily reward modal
const WD = ['ی', 'د', 'س', 'چ', 'پ', 'ج', 'ش'];   // getUTCDay(): Sunday … Saturday
/**
 * «جایزه‌ی روزانه». d: the server's daily reward view; ui: { phase: claimable | claiming | claimed | processing | slow |
 * error | delayed, message }. Opening it claims nothing; only «دریافت کن» does, and «دریافت کردی» appears only after the
 * server confirmed the credit.
 */
export function dailyModal(d, ui) {
  const days = d.days.map(x => {
    const wd = WD[new Date(x.date + 'T12:00:00Z').getUTCDay()];
    return `<li class="${x.attended ? 'is-on' : ''}${x.today ? ' is-today' : ''}"><span class="pe-day__c" aria-hidden="true">${x.attended ? I.check : ''}</span><span>${x.today ? 'امروز' : wd}</span></li>`;
  }).join('');
  const amount = `<div class="pe-daily__rw">${COIN}<b>${fa(d.amount)}</b><span>دیجی‌کوین</span></div>`;
  let body = '', btns = '';
  const p = ui.phase;
  if (p === 'claimed') { body = status('ok', `${fa(d.amount)} دیجی‌کوین دریافت کردی.`, 'check'); btns = GOLD('بزن بریم', ' data-act="daily-close"', false); }
  else if (p === 'claiming') btns = BUSY('دریافت کن', false);
  else if (p === 'processing') { body = status('', 'در حال ثبت جایزه…', null, true); btns = BUSY('دریافت کن', false); }
  else if (p === 'slow') { body = status('warn', 'ثبت جایزه بیشتر از همیشه طول کشیده. جایزه‌ات محفوظه؛ دوباره بررسی کن.', 'clock'); btns = GOLD('بررسی دوباره', ' data-act="daily-check"', false) + BTN('v3-btn--outline v3-btn--block', 'بعداً', ' data-act="daily-close"'); }
  else if (p === 'error') { body = status('no', ui.message || 'اتصال برقرار نشد؛ جایزه‌ات محفوظه. دوباره امتحان کن.', 'alert', false, true); btns = GOLD('دوباره امتحان کن', ' data-act="daily-claim"', false) + BTN('v3-btn--outline v3-btn--block', 'بعداً', ' data-act="daily-close"'); }
  else if (p === 'delayed') { body = status('warn', 'واریز با تأخیر انجام می‌شه.', 'clock'); btns = BTN('v3-btn--outline v3-btn--block', 'باشه', ' data-act="daily-close"'); }
  else btns = GOLD('دریافت کن', ' data-act="daily-claim"', false);
  const closable = p !== 'claiming' && p !== 'processing';
  return `<div class="v3-sheetwrap pe-modal pe-daily-wrap"><section class="v3-sheet pe-daily" role="dialog" aria-modal="true" aria-labelledby="daily-t" data-phase="${p}">` +
    (closable && p !== 'claimed' ? `<button class="pe-daily__x" data-act="daily-close" aria-label="بستن">${ICO('x', 18)}</button>` : '') +
    `<div class="pe-daily__streak"><b>${fa(d.streakDays)}</b><span>روز پشت‌سرهم</span></div><h2 id="daily-t">جایزه‌ی روزانه</h2>` +
    `<ol class="pe-days" aria-label="روزهای حضور در هفته اخیر">${days}</ol>${amount}${body}<div class="v3-sheet__btns">${btns}</div></section></div>`;
}

// ---------------------------------------------------------------- G03–G06 gameplay
export function leaveSheet(m) {
  if (!m.leave) return '';
  const c = m.checkpoint;
  const txt = `دست با ${fa(m.score)} پاسخ درست تموم می‌شه و دیگه نمی‌تونی ادامه بدی.${c ? ` پاداش چک‌پوینت ${fa(c.requiredCorrect)} (${fa(c.rewardAmount)} دیجی‌کوین) برات می‌مونه.` : ''}`;
  return sheet('از این دست خارج می‌شی؟', txt, m.questionOpen ? status('warn', 'زمان سؤال متوقف نمی‌شه.', 'clock') : '',
    BTNS(GOLD('ادامه بازی', ' data-act="leave-no"', false), BTN('v3-btn--danger v3-btn--block', 'خروج و پایان دست', ' data-act="leave-yes"')), '', ['warn', 'alert']);
}
/**
 * G04 checkpoint celebration, mounted ONCE per checkpoint and run outside the re-rendered screen (the duplicate
 * celebration was the screen re-render rebuilding this overlay and replaying its entrance). Copy (brief 2026-10-20):
 * «[نام] رو گرفتی.» · «[مقدار] تا دیجی‌کوین دریافت کردی.» only when the grant is confirmed — under the existing rule the
 * reward is paid at the end of the run, so during the run the approved R-17 lines show instead · «هدف بعدی: …».
 */
export function cpOverlay(c, next, index, confirmed, guardMs, autoMs) {
  const reward = confirmed ? `<p class="v3-cp__rw2">${COIN}<span>${fa(c.rewardAmount)} تا دیجی‌کوین دریافت کردی.</span></p>`
    : `<p class="v3-cp__rw2">${COIN}<span>پاداش فعلی: ${fa(c.rewardAmount)} دیجی‌کوین<small>واریز در پایان دست</small></span></p>`;
  return `<div class="v3-cp" role="dialog" aria-modal="true" aria-labelledby="cp-t" aria-describedby="cp-rw" style="--guard:${guardMs}ms"><div class="v3-burst" aria-hidden="true"></div>${confetti(26, 7)}` +
    `<div class="v3-cp__in"><div class="v3-cp__char" aria-hidden="true">${character(c.badgeAssetId, index)}</div>` +
    `<h2 class="v3-cp__t" id="cp-t">${esc(c.title)} رو گرفتی.</h2><div id="cp-rw">${reward}` +
    `${next ? `<p class="v3-cp__nx">هدف بعدی: ${esc(next.title)} با ${fa(next.requiredCorrect)} جواب درست</p>` : ''}</div></div>` +
    `<button class="v3-cp__go" data-act="cp-go" aria-disabled="true">برای ادامه، هر جای صفحه بزن</button>` +   // CP-35 instruction (UX brief), proposed
    `<div class="v3-cp__auto" aria-hidden="true"><i data-cpauto style="transition:transform ${autoMs}ms linear"></i></div></div>`;
}
export function revealRow(t, text, btn, attr) {
  return `<div class="v3-revealrow"${mk('reveal', 'fade')}><span class="v3-revealrow__tag" aria-hidden="true"><i></i>؟</span><div class="v3-revealrow__t"><b>${t}</b>${text ? `<span>${text}</span>` : ''}</div>` +
    `<button class="v3-btn v3-btn--gold v3-btn--block v3-btn--xl" data-tour="reveal"${attr}>${btn}</button></div>`;
}
export function offerText(price, score, cp) {
  const pr = `<b class="v3-offer__p">${money(price)} تومان</b>`;
  if (!score) return `این کالا ${pr} بود. با ادامه، دست ادامه پیدا می‌کنه.`;   // CP-27 (R-58)
  return `این کالا ${pr} بود. با ادامه، ${fa(score)} پاسخ درست${cp ? ` و چک‌پوینت ${fa(cp.requiredCorrect)}` : ''} حفظ می‌شه.`;   // CP-19 (approved)
}

// ---------------------------------------------------------------- G07 End Screen, G09 share
export function codeCard(c, codeState) {
  const exp = c.expiresAt ? new Intl.DateTimeFormat('fa-IR', { month: 'long', day: 'numeric', timeZone: 'Asia/Tehran' }).format(new Date(c.expiresAt)) : '';
  const body = c.status === 'pending' ? '<div class="v3-code__val"><span class="v3-spin" aria-hidden="true"></span><span>در حال صدور کد…</span></div><p>اگه طول کشید، کد بعداً در کدهای تخفیف حسابت هم پیدا می‌شه.</p>'
    : `<div class="v3-code__val"><b dir="ltr">${esc(c.code)}</b>${codeState === 'copied' ? `<button class="v3-btn v3-btn--soft v3-code__copy" aria-live="polite" data-act="copy">${ICO('check', 18)}کپی شد</button>` : '<button class="v3-btn v3-btn--soft v3-code__copy" data-act="copy">کپی کد</button>'}</div><p>${exp ? `اعتبار تا ${exp}. ` : ''}${esc(c.terms)}</p>`;
  return `<section class="v3-code" aria-label="کد تخفیف جایزه"><div class="v3-code__hd"><b>کد تخفیف ${money(c.amountToman)} تومانی</b><span class="v3-chip">جایزه کمپین</span></div>${body}</section>`;
}
export function resultId(R) {
  if (!R) return 'G07.LOADING';
  if (R.campaignCode && R.campaignCode.status === 'sold_out') return 'G07.CAMPAIGN_CODE.SOLD_OUT';
  if (R.campaignCode) return R.codeState === 'copied' ? 'G07.CAMPAIGN_CODE.COPIED' : R.campaignCode.status === 'pending' ? 'G07.CAMPAIGN_CODE.PENDING' : 'G07.CAMPAIGN_CODE';
  if (R.checkpointReward && R.checkpointReward.status === 'delayed') return 'G07.REWARD_DELAYED';
  if (R.reason === 'left') return 'G07.LEFT_RUN';
  if (!R.checkpoint) return 'G07.NO_CHECKPOINT';
  return 'G07.STANDARD';
}
/** End Screen header: share (icon only) at the physical top-right, title, Back at the physical top-left. */
function rtop(m, shareOn) {
  const share = !shareOn ? '<span class="v3-top__end" aria-hidden="true"></span>'
    : m.shareBusy ? '<button class="v3-round" aria-label="در حال آماده‌سازی تصویر اشتراک" disabled aria-disabled="true" aria-busy="true"><span class="v3-spin" aria-hidden="true"></span></button>'
      : `<button class="v3-round" aria-label="اشتراک‌گذاری نتیجه" data-act="share">${ICO('share', 22)}</button>`;
  return `<div class="v3-top v3-top--result">${share}<span class="v3-brand v3-brand--sm">نتیجه دست</span><button class="v3-round v3-round--l" aria-label="بازگشت به صفحه شروع" data-act="to-start">${I.back}</button></div>`;
}
/** History card: chronological turn, the product, the price in the game, and what happened — never a fabricated answer. */
function hcard(p, n) {
  const res = p.role === 'reference' ? ['start', 'کالای شروع', '']
    : p.result === 'correct' ? ['ok', 'درست', I.check] : p.result === 'wrong' ? ['no', 'اشتباه', '✕'] : p.result === 'timeout' ? ['time', 'وقت تموم شد', I.clock] : ['none', 'بی‌پاسخ', ''];
  const price = p.gamePrice == null ? '<b>—</b>' : `<b>${money(p.gamePrice)}</b><small>تومان</small>`;
  return `<article class="v3-hcard${p.isLoss ? ' is-loss' : ''}" role="listitem" data-qid="${esc(p.questionId || 'start')}" data-turn="${n}"${p.isLoss ? ' data-loss' : ''} aria-label="${n === 0 ? 'کالای شروع' : 'نوبت ' + fa(n)}: ${esc(p.displayTitle)}، ${res[1]}">` +
    `${p.isLoss ? '<span class="v3-hcard__loss">اینجا باختی</span>' : ''}<span class="v3-hcard__res v3-hcard__res--${res[0]}">${res[2] ? `<i aria-hidden="true">${res[2]}</i>` : ''}${res[1]}</span>` +
    `${img(p)}<h3>${title(p.displayTitle)}</h3><div class="v3-hcard__p">${price}</div>` +
    `<button class="v3-open" data-act="open-product" data-link="${esc(p.link)}" data-id="${esc(p.productId)}" aria-label="مشاهده کالا: ${esc(p.displayTitle)}">مشاهده کالا ${ICO('ext', 14)}</button></article>`;   // CP-28
}
function history(m) {
  const P = m.products, hs = m.historyState;
  let body;
  if (hs === 'error') body = `<div class="v3-hist__msg">${status('no', 'کالاهای این دست بارگذاری نشد.', 'alert')}${BTN('v3-btn--soft', 'دوباره امتحان کن', ' data-act="history-retry"')}</div>`;
  else if (!P) body = `<div class="v3-hist__scroll" aria-busy="true">${Array(3).fill('<div class="v3-hcard v3-hcard--skel" aria-hidden="true"><div class="v3-img v3-skel v3-skel--card"></div><span class="v3-skel v3-skel--card"></span><span class="v3-skel v3-skel--card"></span></div>').join('')}</div>`;
  else body = `<div class="v3-hist__scroll" data-hscroll="history" role="list" tabindex="0" aria-label="کالاهای این دست به ترتیب نوبت">${P.items.map((p, i) => hcard(p, i)).join('')}</div>`;
  return `<section class="v3-hist" data-hist-state="${hs}" aria-labelledby="hist-t"><div class="v3-hist__hd"><h2 id="hist-t">کالاهای این دست</h2><span>قیمت‌ها مربوط به زمان بازی‌اند</span></div>${body}</section>`;
}
/**
 * G07 End Screen (brief 2026-10-20): a compact summary (count, checkpoint, the reward amount; «دریافت کردی» only once the
 * credit is confirmed), one status line, the campaign code when there is one, and the run's product history. No record,
 * no settlement ticks, no «دوباره بازی کن», no bottom share button.
 */
export function vResult(m) {
  const R = m.result, F = m.config.features || {}, SH = F.share !== false, P = F.products !== false;
  if (!R) return stage(rtop(m, false) + `<div class="v3-rsum" aria-busy="true"><div class="v3-rsum__score"><b>${fa(m.lastScore || 0)}</b><span>پاسخ درست</span></div>${status('', 'در حال ثبت نتیجه…', null, true)}</div>`, '', 'page result');
  const cp = R.checkpoint, cr = R.checkpointReward, first = m.config.checkpoints[0];
  const cpLine = cp ? `<div class="v3-rsum__cp"><span class="v3-rsum__char" aria-hidden="true">${character(cp.badgeAssetId, m.config.checkpoints.findIndex(x => x.checkpointId === cp.checkpointId))}</span><div><span>چک‌پوینت</span><b>${esc(cp.title)}</b></div></div>`
    : `<div class="v3-rsum__cp v3-rsum__cp--none"><span class="v3-medal v3-medal--none v3-medal--sm"><span>—</span></span><div><span>هنوز چک‌پوینتی نگرفتی</span><b>اولی با ${fa(first ? first.requiredCorrect : 3)} پاسخ درست</b></div></div>`;
  const amount = cr && cr.amount > 0 ? cr.amount : 0;
  const rw = !amount ? '' : cr.status === 'succeeded' ? `<p class="v3-rsum__rw">${COIN}<span>${fa(amount)} تا دیجی‌کوین دریافت کردی.</span></p>`
    : `<p class="v3-rsum__rw">${COIN}<span>پاداش این دست: ${fa(amount)} دیجی‌کوین</span></p>`;
  let st = '';
  if (cr && cr.status === 'delayed') st = status('warn', 'واریز با تأخیر انجام می‌شه.', 'clock');   // R-24: on the result only
  else if (R.reason === 'game_off') st = status('', 'بازی موقتاً متوقف شد؛ پاداش بالاترین چک‌پوینتت ثبت شد.', 'info');
  else if (R.continueLimitUsed) st = status('', 'فرصت ادامه این دست رو قبلاً استفاده کردی.', 'info');   // CP-24
  else if (R.noOfferReason === 'no_funds') st = status('', 'برای ادامه دیجی‌کوین کافی نداشتی.', 'info');   // CP-32
  const nx = R.nextGoal;
  if (!st && nx && nx.remaining <= 2 && R.reason !== 'no_products') st = status('', `فقط ${fa(nx.remaining)} پاسخ تا ${esc(nx.title)} (${fa(nx.reward)} دیجی‌کوین) کم داشتی.`, 'info');   // CP-25
  const out = R.campaignCode && R.campaignCode.status === 'sold_out' ? status('', 'کدهای تخفیف این کمپین تموم شد؛ پاداش دیجی‌کوینت سر جاشه.', 'info') : '';   // CP-31
  const code = R.campaignCode && R.campaignCode.status !== 'sold_out' ? codeCard(R.campaignCode, R.codeState) : '';
  const left = R.reason === 'left' ? '<span class="v3-flag-left">از دست خارج شدی</span>' : '';
  const tst = m.toast && m.toast.page ? toastHTML(m.toast) : '';
  return stage(rtop(m, SH) + `<div class="v3-rsum" aria-live="polite">${left}<div class="v3-rsum__score"><b>${fa(R.score)}</b><span>پاسخ درست</span></div>${cpLine}${rw}</div>` +
    `<div class="v3-rpanel">${out}${st}${code}${P ? history(m) : ''}</div>`, (m.shareSheet || '') + tst, 'page result');
}
export function shareAsset(s) {   // no player name (R-12); no streak (it lives in the daily reward modal only)
  return `<div class="v3-share" role="img" aria-label="تصویر اشتراک: ${fa(s.score)} پاسخ درست"><div class="v3-burst v3-burst--gold" aria-hidden="true" style="top:42%"></div>` +
    `<div><div class="v3-share__brand">${esc(s.gameName)}</div><div class="v3-share__sub">بازی گرون‌تره یا ارزون‌تر</div></div>` +
    `<div><div class="v3-share__n">${fa(s.score)}</div><div class="v3-share__l">پاسخ درست در یک دست</div></div>` +
    (s.checkpointTitle ? `<div class="v3-share__card"><span class="v3-medal"><span>✓</span></span><div><span>آخرین عنوان</span><b>${esc(s.checkpointTitle)}</b></div></div>` : '') +
    '<div class="v3-share__foot">تو چندتا می‌زنی؟</div></div>';
}

// ---------------------------------------------------------------- G10 connection, G11 unavailable
export function vSystem(v, m) {
  const pg = (ic, k, t, p, a) => stage(ptop('قیمت‌شناس', 'بازگشت به اپ', 'exit-app', true) + statecard(ic, k, t, p, a), toastHTML(m.toast));
  const R = m.ended || {};
  const cpTxt = R.checkpoint ? ` و پاداش چک‌پوینت ${fa(R.checkpoint.requiredCorrect)}` : '';
  if (v === 'G10.RESUMING') return stage(ptop('قیمت‌شناس', 'بازگشت به اپ', 'exit-app', true) + '<div class="v3-wait" role="status"><span class="v3-spin" aria-hidden="true"></span><h2>برگشتی!</h2><p>داریم آخرین وضعیت دستت رو از سرور می‌گیریم…</p></div>');
  if (v === 'G10.INACTIVITY_END') return pg('clock', 'warn', 'این دست تموم شد', `بیشتر از ۳ دقیقه بین سؤال‌ها کاری انجام نشد. دستت با ${fa(R.score || 0)} پاسخ درست${cpTxt} ثبت شد.`, GOLD('دیدن نتیجه', ' data-act="show-result"', false));
  if (v === 'G11.GAME_OFF') return pg('power', '', 'بازی موقتاً در دسترس نیست', m.playerMessage ? esc(m.playerMessage) : 'در حال به‌روزرسانی هستیم. کمی بعد دوباره سر بزن.', BTN('v3-btn--outline v3-btn--block', 'بازگشت به اپ', ' data-act="exit-app"'));
  if (v === 'G11.AUTH_ERROR') return pg('user', 'no', 'ورود انجام نشد', 'برای بازی باید وارد حساب دیجی‌کالا باشی. از داخل اپ دوباره وارد شو.', GOLD('بازگشت به اپ', ' data-act="exit-app"', false));
  if (v === 'G11.SERVICE_ERROR') return pg('alert', 'no', 'یه مشکلی پیش اومد', 'سرویس بازی الان جواب نمی‌ده. امتیاز و دست‌هات محفوظه؛ چند لحظه دیگه دوباره امتحان کن.', GOLD('تلاش دوباره', ' data-act="boot"', false) + BTN('v3-btn--outline v3-btn--block', 'بازگشت به اپ', ' data-act="exit-app"'));
  if (v === 'G11.OTHER_DEVICE') return pg('devices', '', 'این دست تموم شد', `روی دستگاه دیگه‌ای دست جدید شروع کردی. این دست با ${fa(R.score || 0)} پاسخ درست ثبت شد${R.checkpoint ? ' و پاداش چک‌پوینتش برات می‌مونه' : ''}.`, GOLD('دیدن نتیجه', ' data-act="show-result"', false) + BTN('v3-btn--outline v3-btn--block', 'بازگشت به شروع', ' data-act="boot"'));
  if (v === 'G11.BLOCKED') return pg('lock', 'no', 'بازی غیرفعال شده است', '', BTN('v3-btn--outline v3-btn--block', 'بازگشت به اپ', ' data-act="exit-app"'));   // exact copy, no support link (R-61)
  return '';
}

export { COIN, L, ICO, PLAYI, CB };
