// Screen markup of the redesign of 2026-10-21 (white base, Digikala red actions, badge family). Views are pure functions
// of the view model: they never read the network or the clock. All text from data goes through esc()/title().
// Direction rules (§2): back/close sit at the physical right (first in RTL DOM order) and point right; inside continue,
// start, next and play buttons the icon is written after the label, so it sits on the left.
import { I } from './icons.js';
import { fa, money, esc, title } from './fmt.js';

const L = ' disabled aria-disabled="true"';
const ICO = (k, s = 20) => I[k].replace('width="24" height="24"', `width="${s}" height="${s}"`);
const SPIN = '<span class="q-spin" aria-hidden="true"></span>';
/** Stand-in DigiCoin mark: a plain coin with no currency sign (the official DigiCoin logo was not supplied: asset dependency). */
export const COIN = '<svg class="q-coin" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="10" fill="#F2C14E" stroke="#C88A12" stroke-width="2"/><circle cx="12" cy="12" r="5.5" fill="none" stroke="#C88A12" stroke-width="1.6"/></svg>';
export const TAG = '<svg viewBox="0 0 48 48" aria-hidden="true" focusable="false"><path d="M8 9.5V22l17.5 17.5a2 2 0 0 0 2.8 0L39.5 28.3a2 2 0 0 0 0-2.8L22 8H9.5A1.5 1.5 0 0 0 8 9.5Z" fill="currentColor"/><circle cx="15.5" cy="15.5" r="3.2" fill="var(--tag-hole, #EF4056)"/></svg>';
const BADGE_SRC = id => `assets/badges/${encodeURIComponent(id)}.webp`;
export const BADGES = ['badge_5', 'badge_10', 'badge_15', 'badge_20'];
/** A checkpoint's badge image; an unknown id (a checkpoint added later in A02) gets the badge of its position. */
export const badgeId = (c, i) => BADGES.includes(c.badgeAssetId) ? c.badgeAssetId : BADGES[Math.min(BADGES.length - 1, Math.max(0, i))];
const NEUTRAL = '<svg class="q-badge-neutral" viewBox="0 0 120 120" aria-hidden="true"><path d="M60 8l40 14v30c0 28-17 47-40 58C37 99 20 80 20 52V22Z" fill="#ECEEF2" stroke="#C9CDD5" stroke-width="4"/><path d="M60 40v24M60 76h.01" stroke="#8A8F99" stroke-width="8" stroke-linecap="round"/></svg>';

export const Q_PROMPT = 'این کالا نسبت به کالای بالا...';   // §6, exact
export const DIRW = { up: 'گرون‌تر', down: 'ارزون‌تر', eq: 'قیمت برابر' };
export const dirOf = d => d === 'higher' ? 'up' : d === 'lower' ? 'down' : 'eq';

// ---------------------------------------------------------------- primitives
export const keyOf = card => 'p' + hash(card.imageUrl || card.displayTitle || '');
function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0).toString(36); }
const mk = (k, fx) => k ? ` data-m="${k}" data-mfx="${fx}"` : '';
export function img(card, keyed, onload) {
  const key = keyed ? mk('img:' + keyOf(card), 'box') : '';
  if (card.art) return `<span class="q-img"${key}>${card.art}</span>`;   // onboarding demo: drawn art, never a catalog product
  if (!card.imageUrl) return `<span class="q-img q-img--none"${key}></span>`;
  return `<span class="q-img"${key}><img src="${esc(card.imageUrl)}" alt="" decoding="async"${onload ? ` data-onload="${onload}"` : ''}></span>`;
}
/** Price with its unit; the number is isolated LTR so digits, separators and «تومان» are read in the right place. */
export const price = (n, cls = '', attrs = '') => `<p class="q-price${cls}"${attrs}><b dir="ltr">${money(n)}</b><small>تومان</small></p>`;
export const BTN = (cls, label, a = '', icon = '') => `<button class="q-btn ${cls}"${a}><span>${label}</span>${icon ? `<i class="q-btn__i">${icon}</i>` : ''}</button>`;
export const PRIMARY = (label, a, icon) => BTN('q-btn--primary', label, a, icon);
export const BUSY = (label, cls = 'q-btn--primary') => `<button class="q-btn ${cls}" disabled aria-disabled="true" aria-busy="true"><span>${label}</span><i class="q-btn__i">${SPIN}</i></button>`;
export const SECONDARY = (label, a, icon) => BTN('q-btn--secondary', label, a, icon);
export function status(kind, text, icon, spin, alert) {
  const lead = spin ? SPIN : icon ? ICO(icon, 18) : '';
  return `<p class="q-status${kind ? ' q-status--' + kind : ''}" role="${alert ? 'alert' : 'status'}">${lead ? `<span class="q-status__i" aria-hidden="true">${lead}</span>` : ''}<span>${text}</span></p>`;
}
export function toastHTML(t) {
  if (!t) return '';
  return `<div class="q-toast${t.no ? ' q-toast--no' : ''}" role="${t.no ? 'alert' : 'status'}">${ICO(t.no ? 'alert' : 'check', 18)}<span>${t.text}</span></div>`;
}
/** Page header: back/close at the physical right, title, an optional end slot at the left. */
export function hdr(t, act, label, end = '', cls = '') {
  return `<header class="q-hdr${cls}"><button class="q-iconbtn" data-act="${act}" aria-label="${label}">${I.back}</button>${t ? `<h1 class="q-hdr__t">${t}</h1>` : '<span></span>'}<div class="q-hdr__end">${end}</div></header>`;
}
// a modal on top makes the page behind it inert (focus, taps, screen readers)
export const page = (cls, inner, extra = '') => `<div class="q-page ${cls}"${/q-modal/.test(extra) ? ' inert' : ''}>${inner}</div>${extra}`;
export function sheet(t, text, body, btns, icon) {
  const lead = icon ? `<span class="q-sheet__icon q-sheet__icon--${icon[0]}" aria-hidden="true">${ICO(icon[1], 26)}</span>` : '';
  return `<div class="q-scrim q-modal"><section class="q-sheet" role="dialog" aria-modal="true" aria-labelledby="sh-t">${lead}<h2 id="sh-t">${t}</h2>${text ? `<p>${text}</p>` : ''}${body || ''}<div class="q-sheet__btns">${btns}</div></section></div>`;
}
export const statecard = (ic, kind, t, text, actions) => `<div class="q-state"><span class="q-state__i${kind ? ' q-state__i--' + kind : ''}" aria-hidden="true">${ICO(ic, 30)}</span><h2>${t}</h2>${text ? `<p>${text}</p>` : ''}<div class="q-state__a">${actions}</div></div>`;

// ---------------------------------------------------------------- G00 splash (§3)
export function vSplash(error) {
  const foot = error
    ? `<div class="q-splash__foot">${status('no', 'بازی آماده نشد. اتصال اینترنت رو بررسی کن.', 'wifi', false, true)}${PRIMARY('تلاش دوباره', ' data-act="boot"', I.refresh)}</div>`
    : '<div class="q-splash__foot" role="status"><span class="q-dots" aria-hidden="true"><i></i><i></i><i></i></span><p>داریم بازی رو آماده می‌کنیم...</p></div>';
  return `<div class="q-page q-splash"><div class="q-splash__mid"><span class="q-logo q-logo--xl" aria-hidden="true">${TAG}</span><h1>قیمت‌شناس</h1><p>گرون‌تره یا ارزون‌تر؟</p></div>${foot}</div>`;
}

// ---------------------------------------------------------------- G01 home (§4, §5)
const COMPARE_ART = `<div class="q-compare" aria-hidden="true"><span class="q-compare__t q-compare__t--a">${TAG}</span><span class="q-compare__x">${I.refresh}</span><span class="q-compare__t q-compare__t--b">${TAG}</span></div>`;
function balance(b) {
  if (b == null) return '<span class="q-bal is-unknown" aria-label="موجودی دیجی‌کوین نامعلوم">' + COIN + '<b>—</b></span>';
  return `<span class="q-bal" role="img" aria-label="موجودی: ${fa(b)} دیجی‌کوین">${COIN}<b>${fa(b)}</b></span>`;
}
function pcard(p, n) {
  const label = `${n === 0 ? 'کالای شروع' : 'نوبت ' + fa(n)}: ${esc(p.displayTitle)}${p.isLoss ? '، اینجا باختی' : ''}`;
  const open = p.link ? ` data-act="open-product" data-link="${esc(p.link)}"` : ' disabled';
  return `<button class="q-pcard${p.isLoss ? ' is-loss' : ''}" role="listitem"${open}${p.isLoss ? ' data-loss' : ''} aria-label="${label}">` +
    `${img(p)}<span class="q-pcard__t">${title(p.displayTitle)}</span>${p.isLoss ? '<span class="q-pcard__loss">اینجا باختی</span>' : ''}</button>`;
}
function history(m) {
  const hs = m.historyState;
  let body;
  if (hs === 'none') body = '<p class="q-hist__empty">کالاهای اولین دستت اینجا می‌مونه</p>';
  else if (hs === 'error') body = `<div class="q-hist__empty">${status('no', 'کالاهای دست قبلی بارگذاری نشد.', 'alert')}${BTN('q-btn--text', 'دوباره امتحان کن', ' data-act="history-retry"')}</div>`;
  else if (!m.products) body = `<div class="q-hist__row" aria-busy="true">${Array(3).fill('<span class="q-pcard q-skel" aria-hidden="true"></span>').join('')}</div>`;
  else body = `<div class="q-hist__row" data-hscroll="history" role="list" tabindex="0" aria-label="کالاهای دست قبلی به ترتیب نوبت">${m.products.items.map(pcard).join('')}</div>`;
  return `<section class="q-hist" aria-labelledby="hist-t"><div class="q-hist__hd"><h2 id="hist-t">کالاهای دست قبلی</h2>${m.products && m.products.items.length > 2 ? '<span>بکش و ببین</span>' : ''}</div>${body}</section>`;
}
/** The home's bottom area: part of the page (no scrim, no drag, no extra modal). v: the G01 state. */
function homeSheet(v, m) {
  const b = m.boot, q = b.quota;
  const chances = `<p class="q-chances">شانس بازی: <b>${fa(q.playsLeft)}</b></p>`;
  let cta, pre = '';
  if (v === 'G01.ACTIVE_RUN') {
    const r = b.activeRun;
    pre = status('', `یه دست نیمه‌کاره داری با ${fa(r.score)} جواب درست؛ از همون‌جا ادامه بده.`, 'info');
    cta = PRIMARY('ادامه دست', ' data-act="resume"', I.play);
  } else if (v === 'G01.OPERATION_PENDING') {
    pre = status('', 'پرداخت قبلیت هنوز در حال بررسیه. دوباره کسر نمی‌شه؛ چند لحظه صبر کن.', null, true);
    cta = BUSY('شروع بازی');
  } else if (v === 'G01.STARTING' || v === 'G01.REFRESHING') cta = BUSY('شروع بازی');
  else if (v === 'G01.QUOTA_CHECKING') cta = `<button class="q-btn q-btn--primary" disabled aria-disabled="true" aria-busy="true"><span>در حال بررسی شانس بعدی...</span></button>`;
  else if (v === 'G01.QUOTA_EXHAUSTED') cta = `<button class="q-btn q-btn--primary" disabled aria-disabled="true"><span>تا شانس بعدی <b dir="ltr" data-countdown>${m.untilReset}</b></span><i class="q-btn__i">${ICO('clock')}</i></button>`;
  else if (m.offline) { pre = status('warn', 'اتصال برقرار نیست؛ برای شروع بازی به اینترنت وصل شو.', 'wifi'); cta = `<button class="q-btn q-btn--primary" disabled aria-disabled="true"><span>شروع بازی</span><i class="q-btn__i">${I.play}</i></button>`; }
  else cta = PRIMARY('شروع بازی', ' data-act="start"', I.play);
  return `<section class="q-homesheet" aria-label="شروع بازی">${COMPARE_ART}<h2>گرون‌تره یا ارزون‌تر؟</h2>${pre}${chances}${cta}</section>`;
}
export function vHome(v, m) {
  const b = m.boot;
  const top = `<header class="q-hdr q-hdr--home"><button class="q-iconbtn" data-act="exit-app" aria-label="بازگشت به اپ">${I.back}</button>` +
    `<div class="q-brand"><span class="q-logo q-logo--s" aria-hidden="true">${TAG}</span><b>قیمت‌شناس</b></div><span class="q-flex"></span>${balance(m.balance)}` +
    `<button class="q-guidebtn" data-act="guide">راهنمای بازی</button></header>`;
  const record = `<section class="q-record" aria-label="بهترین رکورد: ${fa(b.record)} جواب درست در یک دست"><div><span>بهترین رکورد</span><b>${fa(b.record)}</b><small>جواب درست در یک دست</small></div>` +
    '<img src="assets/trophy.webp" alt="" width="96" height="94" decoding="async"></section>';
  const o = b.activeRun && !b.activeRun.onThisDevice ? b.activeRun : null;
  const note = o ? status('warn', `یه دست روی دستگاه دیگه‌ات بازه. با شروع دست جدید، اون دست با ${fa(o.score)} جواب درست تموم می‌شه.`, 'devices') : '';
  const camp = b.campaign ? status('', `کمپین ویژه: با رسیدن به ${fa(b.campaign.checkpointRequiredCorrect)} جواب درست، کد تخفیف ${money(b.campaign.codeAmountToman)} تومانی هم می‌گیری.`, 'info') : '';
  return page('q-home', top + `<main class="q-home__scroll">${record}${m.productsOn ? history(m) : ''}${camp}${note}</main>` + homeSheet(v, m), (m.modal || '') + toastHTML(m.toast));
}

// ---------------------------------------------------------------- G01 daily reward modal
const WD = ['ی', 'د', 'س', 'چ', 'پ', 'ج', 'ش'], WDF = ['یکشنبه', 'دوشنبه', 'سه‌شنبه', 'چهارشنبه', 'پنجشنبه', 'جمعه', 'شنبه'];   // getUTCDay()
/**
 * «جایزه‌ی روزانه». The 7 days keep the server's floating window and its dates; the first day of the window sits at the
 * physical right (RTL order, §15). The only claim button sends one claim; on success the modal closes and the home shows
 * (no second «دریافت شد» surface). ui.phase: claimable | claiming | processing | slow | error.
 */
export function dailyModal(d, ui) {
  const days = d.days.map(x => {
    const w = new Date(x.date + 'T12:00:00Z').getUTCDay();
    const st = x.today ? (x.attended ? 'امروز، گرفته شده' : 'امروز') : x.attended ? 'گرفته شده' : 'غیرفعال';
    return `<li class="${x.attended ? 'is-on' : ''}${x.today ? ' is-today' : ''}" aria-label="${WDF[w]}، ${st}"><span class="q-day__c" aria-hidden="true">${x.attended ? ICO('check', 16) : ''}</span><span aria-hidden="true">${x.today ? 'امروز' : WD[w]}</span></li>`;
  }).join('');
  const p = ui.phase;
  let body = '', btns;
  if (p === 'claiming') btns = BUSY('دریافت کن');
  else if (p === 'processing') { body = status('', 'در حال ثبت جایزه…', null, true); btns = BUSY('دریافت کن'); }
  else if (p === 'slow') { body = status('warn', 'ثبت جایزه بیشتر از همیشه طول کشیده. جایزه‌ات محفوظه؛ دوباره بررسی کن.', 'clock'); btns = PRIMARY('بررسی دوباره', ' data-act="daily-check"', I.refresh) + SECONDARY('بعداً', ' data-act="daily-close"'); }
  else if (p === 'error') { body = status('no', ui.message || 'اتصال برقرار نشد؛ جایزه‌ات محفوظه. دوباره امتحان کن.', 'alert', false, true); btns = PRIMARY('دوباره امتحان کن', ' data-act="daily-claim"', I.refresh) + SECONDARY('بعداً', ' data-act="daily-close"'); }
  else btns = PRIMARY('دریافت کن', ' data-act="daily-claim"');
  const closable = p !== 'claiming' && p !== 'processing';
  return `<div class="q-scrim q-modal"><section class="q-sheet q-daily" role="dialog" aria-modal="true" aria-labelledby="daily-t" data-phase="${p}">` +
    (closable ? `<button class="q-iconbtn q-sheet__x" data-act="daily-close" aria-label="بستن">${ICO('x', 20)}</button>` : '') +
    `<div class="q-daily__streak"><b>${fa(d.streakDays)}</b><span>روز پشت‌سرهم</span></div><h2 id="daily-t">جایزه‌ی روزانه</h2>` +
    `<ol class="q-days" aria-label="روزهای حضور در هفته اخیر">${days}</ol><p class="q-daily__rw">${COIN}<b>${fa(d.amount)}</b><span>دیجی‌کوین</span></p>${body}<div class="q-sheet__btns">${btns}</div></section></div>`;
}

// ---------------------------------------------------------------- G02 guide (§15) and onboarding (§14)
const GUIDE_ART = {
  compare: `<span class="q-gart q-gart--compare" aria-hidden="true"><i>${TAG}</i><i>${TAG}</i></span>`,
  time: `<span class="q-gart" aria-hidden="true">${ICO('clock', 30)}</span>`,
  badges: `<span class="q-gart q-gart--badges" aria-hidden="true">${BADGES.slice(0, 3).map(b => `<img src="${BADGE_SRC(b)}" alt="">`).join('')}</span>`,
  cont: `<span class="q-gart" aria-hidden="true">${ICO('undo', 30)}</span>`,
};
export function vGuide(m) {
  const c = m.config, cps = c.checkpoints, cr = c.continueRule;
  const steps = cps.map(x => fa(x.requiredCorrect)), last = steps.pop();
  const firstPaid = cps.findIndex(x => x.rewardAmount > 0);
  const ordinal = ['اول', 'دوم', 'سوم', 'چهارم', 'پنجم'][firstPaid] || fa(firstPaid + 1) + 'م';
  const sec = (art, h, p) => `<section class="q-gsec">${art}<div><h2>${h}</h2><p>${p}</p></div></section>`;
  const body = sec(GUIDE_ART.compare, 'مقایسه قیمت', 'قیمت کالای اول معلومه. حدس بزن کالای دوم گرون‌تره یا ارزون‌تر.') +
    sec(GUIDE_ART.time, `${fa(Math.round(c.inputMs / 1000))} ثانیه فرصت`, `برای هر مقایسه ${fa(Math.round(c.inputMs / 1000))} ثانیه وقت داری.`) +
    sec(GUIDE_ART.badges, 'عنوان تازه بگیر', `با ${steps.join('، ')} و ${last} جواب درست، عنوان تازه می‌گیری${firstPaid >= 0 ? `؛ از چک‌پوینت ${ordinal} دیجی‌کوین هم داری` : ''}.`) +
    (cr.enabled && cr.cap > 0 ? sec(GUIDE_ART.cont, 'همون دست رو ادامه بده', `اگر امکان ادامه داشته باشی، می‌تونی با ${fa(cr.cost)} دیجی‌کوین همون دست رو ادامه بدی.`) : '');
  return page('q-guide', hdr('راهنمای قیمت‌شناس', 'guide-close', 'بستن راهنما') + `<main class="q-guide__body">${body}</main>` +
    `<footer class="q-foot">${PRIMARY('فهمیدم', ' data-act="guide-close"', I.fwd)}</footer>`);
}
/** Onboarding page 1: a fixed sandbox of the game screen under a dimmed layer; the spotlight hole is placed by the controller. */
export function vOnboard1(m, scene, step, steps, still) {
  const s = steps[step];
  const dots = steps.map((_, i) => `<i${i === step ? ' class="is-on"' : ''}></i>`).join('');
  const nav = still ? `<div class="q-spot__nav"><button class="q-btn q-btn--text" data-act="spot-prev"${step === 0 ? L : ''}>قبلی</button><button class="q-btn q-btn--text" data-act="spot-next"${step === steps.length - 1 ? L : ''}>بعدی</button></div>` : '';
  return `<div class="q-onb q-onb--1"><div class="q-onb__scene" inert aria-hidden="true">${scene}</div>` +
    `<div class="q-spot" aria-hidden="true"><div class="q-spot__hole" data-hole></div></div>` +
    `<div class="q-spot__tip" data-tip role="status" aria-live="polite"><h2>${s.title}</h2><p>${s.text}</p><span class="q-spot__dots" aria-hidden="true">${dots}</span>${nav}</div>` +
    `<section class="q-onb__panel" aria-label="آموزش"><p class="q-onb__n">آموزش ${fa(1)} از ${fa(2)}</p>${m.error ? status('no', m.error, 'alert', false, true) : ''}<div class="q-onb__btns">` +
    `${PRIMARY('بعدی', ' data-act="onb-next"', I.fwd)}${m.busy ? BUSY('رد کردن', 'q-btn--text') : BTN('q-btn--text', 'رد کردن', ' data-act="onb-skip"')}</div></section></div>`;
}
export function vOnboard2(m) {
  const cps = m.config.checkpoints;
  const grid = cps.map((c, i) => `<li><img src="${BADGE_SRC(badgeId(c, i))}" alt="" width="132" height="128"><b>${esc(c.title)}</b><span>${fa(c.requiredCorrect)} جواب درست</span></li>`).join('');
  return page('q-onb q-onb--2', hdr('راهنمای بازی', 'onb-prev', 'قبلی') +
    `<main class="q-onb2"><h1>باید رکورد بزنی</h1><p class="q-onb2__sub">هر چند تا سوالی که جواب بدی یه جایزه برات گذاشتیم!</p><ul class="q-badgegrid" aria-label="چک‌پوینت‌ها">${grid}</ul>` +
    `<p class="q-onb2__note">پاسخ‌های درستت حساب می‌شن</p></main>` +
    `<footer class="q-foot q-foot--row">${m.error ? status('no', m.error, 'alert', false, true) : ''}${m.busy ? BUSY('بزن بریم') : PRIMARY('بزن بریم', ' data-act="onb-done"', I.play)}${BTN('q-btn--text', 'قبلی', ' data-act="onb-prev"' + (m.busy ? L : ''))}</footer>`);
}

// ---------------------------------------------------------------- gameplay (§6, §7, §8, §10)
/** Top bar: back at the right, the game name, the correct-answer count at the left. No balance (§1). */
export function gameTop(score, backLocked) {
  return `<header class="q-hdr q-hdr--game"><button class="q-iconbtn" data-act="back" aria-label="خروج از دست"${backLocked ? L : ''}>${I.back}</button><h1 class="q-hdr__t">قیمت‌شناس</h1>` +
    `<div class="q-hdr__end"><span class="q-score" aria-live="polite"><b>${fa(score)}</b><small>جواب درست</small></span></div></header>`;
}
/** A coin stack for a reward amount: 1 coin, a small cluster, or a simple stack for 5 and more (§10); the number is beside it. */
function coinStack(n) {
  const layers = Math.min(3, Math.max(1, n >= 5 ? 3 : n >= 3 ? 2 : 1));
  let s = '';
  for (let i = layers - 1; i >= 0; i--) s += `<circle cx="${17 - i * 3.2}" cy="${17 - i * 3.2}" r="12" fill="#F2C14E" stroke="#C88A12" stroke-width="2"/>`;
  return `<span class="q-stack"><svg viewBox="0 0 32 32" width="34" height="34" aria-hidden="true" focusable="false">${s}</svg><b>${fa(n)}</b></span>`;
}
/** Main progress bar (kept, §10): fills with the score; each checkpoint shows its real reward — the badge when it pays no coin. */
export function track(score, cps) {
  const last = cps.length ? cps[cps.length - 1].requiredCorrect : 1;
  const f = Math.min(1, score / last);
  const nodes = cps.map((c, i) => {
    const ic = c.rewardAmount > 0 ? coinStack(c.rewardAmount) : `<img src="${BADGE_SRC(badgeId(c, i))}" alt="" width="34" height="33">`;
    return `<li class="${c.requiredCorrect <= score ? 'is-done' : ''}" style="--x:${(100 * c.requiredCorrect / last).toFixed(2)}%"><span class="q-track__ic">${ic}</span><span class="q-track__n">${fa(c.requiredCorrect)}</span></li>`;
  }).join('');
  const next = cps.find(c => c.requiredCorrect > score);
  const label = next ? `${fa(score)} جواب درست؛ چک‌پوینت بعدی با ${fa(next.requiredCorrect)} جواب درست${next.rewardAmount > 0 ? ` و ${fa(next.rewardAmount)} دیجی‌کوین` : ''}` : `${fa(score)} جواب درست؛ همه‌ی چک‌پوینت‌ها رو گرفتی`;
  return `<div class="q-track" role="progressbar" aria-valuemin="0" aria-valuemax="${last}" aria-valuenow="${Math.min(score, last)}" aria-label="${label}">` +
    `<div class="q-track__line"><i style="--f:${f.toFixed(4)}"></i></div><ol aria-hidden="true">${nodes}</ol></div>`;
}
/**
 * Timer above the decision area (§7): large fixed-width number on a contrasting plate with a draining line.
 * mode: idle (before «نشان بده») · run · low (last 3 s) · zero (visible window over, server recording it) · busy (answer
 * sent) · done (answered; the verdict lives in the decision area).
 */
export function timer(o) {
  const mode = o.mode, n = mode === 'idle' ? fa(10) : mode === 'zero' ? fa(0) : o.t;
  const lab = { idle: 'ثانیه وقت داری', run: 'ثانیه وقت داری', low: 'وقت داره تموم می‌شه', zero: 'زمان تموم شد', busy: 'در حال بررسی پاسخ...', done: 'جوابت ثبت شد' }[mode];
  const num = mode === 'busy' ? SPIN : mode === 'done' ? `<span class="q-timer__ok">${ICO('check', 26)}</span>` : `<b class="q-timer__n">${n}</b>`;
  const live = mode === 'run' || mode === 'low';
  return `<div class="q-timer is-${mode}" data-tour="timer" ${live ? 'role="timer" data-timer' : 'role="status"'} aria-label="${mode === 'zero' || mode === 'done' ? lab : mode === 'busy' ? 'در حال بررسی پاسخ' : `${n} ${lab}`}" style="--p:${o.p == null ? 1 : o.p}">` +
    `${num}<span class="q-timer__l">${lab}</span><i class="q-timer__bar" aria-hidden="true"></i></div>`;
}
export function refCard(card, strip) {
  const k = keyOf(card);
  return `<article class="q-ref${strip ? ' q-ref--strip' : ''}" aria-label="کالای اول، ${money(card.price)} تومان"${mk('card:' + k, 'frame')} data-tour="first">${img(card, true)}` +
    `<div class="q-ref__b"><span class="q-label">کالای اول</span><h2 class="q-ref__t"${mk('ttl:' + k, 'text')}>${title(card.displayTitle)}</h2>${price(card.price, '', mk('prc:' + k, 'text'))}</div></article>`;
}
/** Before «نشان بده»: the invitation card; the comparison product is chosen by the server only after the tap. */
export function revealCard(state) {
  const busy = state === 'busy', failed = state === 'failed', off = state === 'off';
  const t = failed ? 'کالا آماده نشد' : busy ? 'کالای بعدی آماده می‌شه…' : 'آماده‌ای مقایسه کنی؟';
  const p = failed ? 'امتیاز و زمانت سالمه؛ دوباره امتحان کن.' : off ? 'اتصال قطع شد. هنوز سؤالی شروع نشده؛ دستت محفوظه.' : 'کالای بعدی رو ببین و حدس بزن';
  const b = busy ? BUSY('نشان بده') : off ? `<button class="q-btn q-btn--primary" disabled aria-disabled="true"><span>در انتظار اتصال…</span></button>`
    : PRIMARY(failed ? 'دوباره امتحان کن' : 'نشان بده', ' data-act="reveal" data-tour="reveal"', failed ? I.refresh : I.play);
  return `<section class="q-invite${failed ? ' is-failed' : ''}"${mk('slot', 'fade')} aria-labelledby="inv-t"><span class="q-invite__tag" aria-hidden="true">${TAG}</span><h2 id="inv-t">${t}</h2><p>${p}</p>${b}</section>`;
}
/** Comparison product. Its price is never in the page before the answer; afterwards the server's revealed price fills the same row. */
export function cmpCard(card, st, revealedPrice, slow) {
  const k = keyOf(card);
  const cls = { hidden: '', ok: ' is-ok', no: ' is-no', time: ' is-time' }[st];
  const row = st === 'hidden'
    ? `<p class="q-price q-price--hidden"${mk('prc:' + k, 'text')}><b>؟</b><small>تومان</small><span class="pe-sr">قیمت مخفی</span></p>`
    : price(revealedPrice, ' q-price--reveal', mk('prc:' + k, 'text'));
  const hint = slow ? '<p class="q-cmp__hint">عکس در راهه؛ از روی اسم هم می‌تونی جواب بدی.</p>' : '';
  return `<article class="q-cmp${cls}" aria-label="کالای دوم${st === 'hidden' ? '، قیمت مخفی' : `، ${money(revealedPrice)} تومان`}"${mk('card:' + k, 'frame')} data-tour="second">` +
    `<span class="q-label">کالای دوم</span><h2 class="q-cmp__t"${mk('ttl:' + k, 'text')}>${title(card.displayTitle)}</h2>${img(card, true, st === 'hidden' ? 'q' : '')}${hint}${row}</article>`;
}
/**
 * Decision area: the prompt (or, after the server's verdict, the verdict in the same place) and the two answers at a
 * fixed size and position. st: on · off · pending · ok · no · truth. Text marks («انتخاب تو», «درست») never rely on colour.
 */
export function decide(st, side, line, refPrice) {
  const A = { cls: '', a: ' data-act="ans-higher"', i: I.up, m: '' }, B = { cls: '', a: ' data-act="ans-lower"', i: I.down, m: '' };
  const me = side === 'higher' ? A : B, ot = side === 'higher' ? B : A;
  const ok = `<span class="q-ab__m">${ICO('check', 18)}</span>`, no = `<span class="q-ab__m">${ICO('x', 18)}</span>`;
  if (st === 'off') A.a = B.a = L;
  else if (st === 'pending') { me.cls = ' is-picked'; me.a = ' aria-pressed="true" aria-busy="true"' + L; me.i = SPIN; ot.cls = ' is-dim'; ot.a = L; }
  else if (st === 'ok') { me.cls = ' is-ok'; me.a = ' aria-pressed="true"' + L; me.i = ok; me.m = 'انتخاب تو، درست'; ot.cls = ' is-dim'; ot.a = L; }
  else if (st === 'no') { me.cls = ' is-no'; me.a = ' aria-pressed="true"' + L; me.i = no; me.m = 'انتخاب تو، غلط'; ot.cls = ' is-truth'; ot.a = L; ot.i = ok; ot.m = 'جواب درست'; }
  else if (st === 'truth') { me.cls = ' is-truth'; me.a = L; me.i = ok; me.m = 'جواب درست'; ot.cls = ' is-dim'; ot.a = L; }
  const rp = st === 'on' && refPrice ? money(refPrice) : '';
  const btn = (o, w) => `<button class="q-ab${o.cls}"${o.a}${rp ? ` aria-label="${w} از ${rp} تومان"` : ''}><span>${w}${o.m ? `<small class="pe-sr">، ${o.m}</small>` : ''}</span><i class="q-ab__i">${o.i}</i></button>`;
  return `<div class="q-decide"${mk('decide', 'fade')}>${line}<div class="q-ans" role="group" aria-label="پاسخ" data-tour="answers">${btn(A, 'گرون‌تره')}${btn(B, 'ارزون‌تره')}</div></div>`;
}
export const prompt = (text, muted) => `<p class="q-prompt${muted ? ' is-muted' : ''}">${text}</p>`;
export function verdict(k, t, sub) {
  const ic = { ok: 'check', no: 'x', time: 'clock' }[k];
  return `<p class="q-verdict q-verdict--${k}" role="status"><span class="q-verdict__i" aria-hidden="true">${ICO(ic, 18)}</span><b>${t}</b>${sub ? `<span>${sub}</span>` : ''}</p>`;
}
export const pill = (t, ic, btn) => `<p class="q-pill" role="status">${ICO(ic, 16)}<span>${t}</span>${btn || ''}</p>`;
export const game = (top, trk, tmr, main, dec, extra, toast) => page('q-game', top + trk + tmr + `<main class="q-game__main">${main}</main>` + (dec || ''), (extra || '') + toastHTML(toast));

export function leaveSheet(m) {
  if (!m.leave) return '';
  const txt = `دست با ${fa(m.score)} جواب درست تموم می‌شه و دیگه نمی‌تونی ادامه بدی.${m.checkpoint ? ' جایزه‌ی چک‌پوینت‌هایی که گرفتی سر جاشه.' : ''}`;
  return sheet('از این دست خارج می‌شی؟', txt, m.questionOpen ? status('warn', 'زمان سؤال متوقف نمی‌شه.', 'clock') : '',
    PRIMARY('ادامه بازی', ' data-act="leave-no"', I.fwd) + BTN('q-btn--danger', 'خروج و پایان دست', ' data-act="leave-yes"'), ['warn', 'alert']);
}

// ---------------------------------------------------------------- G04 checkpoint celebration (§10)
/**
 * Mounted once per (run, checkpoint) outside the re-rendered screen. Badge, the title earned, the fixed comparison copy,
 * «{n} جواب درست دادی», the reward line only for a paying checkpoint, and one «ادامه بده». No countdown, no automatic
 * continue, no second «دریافت» step: the reward was registered by the server with the answer that crossed the line.
 */
export function cpOverlay(c, index, reward) {
  const head = c.comparisonPercent ? `از ${fa(c.comparisonPercent)} درصد آدما باهوش‌تری!` : `${esc(c.title)} رو گرفتی!`;
  const amount = c.rewardAmount > 0 ? c.rewardAmount : 0;
  const late = reward && reward.status === 'delayed' ? '<small>واریزش کمی طول می‌کشه</small>' : '';
  return `<div class="q-cp" role="dialog" aria-modal="true" aria-labelledby="cp-t" aria-describedby="cp-d"><div class="q-cp__in">` +
    `<img class="q-cp__badge" src="${BADGE_SRC(badgeId(c, index))}" alt="" width="240" height="232"><p class="q-cp__name">${esc(c.title)}</p>` +
    `<h2 class="q-cp__h" id="cp-t">${head}</h2><p class="q-cp__n" id="cp-d">${fa(c.requiredCorrect)} جواب درست دادی</p>` +
    (amount ? `<p class="q-cp__rw">${COIN}<span>${fa(amount)} دیجی‌کوین جایزه</span>${late}</p>` : '') +
    `</div><div class="q-foot">${PRIMARY('ادامه بده', ' data-act="cp-go"', I.fwd)}</div></div>`;
}

// ---------------------------------------------------------------- G05/G06 loss and continue (§11, §12)
/**
 * One surface for the result of a miss and the continue of the same run (replaces the old offer sheet and End Screen).
 * m: { reason: wrong|timeout, score, best, cp (highest of THIS run) , cpIndex, action: html of the continue area, code }.
 */
export function vLoss(m) {
  const cp = m.cp;
  const badge = cp ? `<img class="q-loss__badge" src="${BADGE_SRC(badgeId(cp, m.cpIndex))}" alt="" width="200" height="194">` : `<span class="q-loss__badge">${NEUTRAL}</span>`;
  const sub = m.reason === 'timeout' ? 'زمانت تموم شد' : m.reason === 'wrong' ? 'این بار قیمت رو درست حدس نزدی' : '';
  const stats = `<dl class="q-stats"><div><dt>امتیاز</dt><dd>${fa(m.score)}</dd></div><div><dt>بهترین امتیاز</dt><dd>${fa(m.best)}</dd></div>` +
    `<div><dt>عنوان</dt><dd class="q-stats__t">${cp ? esc(cp.title) : 'هنوز عنوانی نگرفتی'}</dd></div></dl>`;
  const pct = cp && cp.comparisonPercent ? `<h2 class="q-loss__pct"><em>تو بهتر از ${fa(cp.comparisonPercent)} درصد</em> دیجی‌کالایی‌ها بودی!</h2>` : `<h2 class="q-loss__pct q-loss__pct--plain">این دست ${fa(m.score)} جواب درست دادی</h2>`;
  return page('q-loss', `<header class="q-hdr q-hdr--bare"><button class="q-iconbtn" data-act="loss-home" aria-label="صفحه اصلی"${m.homeLocked ? L : ''}>${I.back}</button></header>` +
    `<main class="q-loss__main">${badge}<h1 class="q-loss__h">باختی!</h1>${sub ? `<p class="q-loss__sub">${sub}</p>` : ''}${stats}${pct}${m.code || ''}</main>` +
    `<footer class="q-foot q-loss__act">${m.action}${m.homeLocked ? `<button class="q-btn q-btn--secondary" disabled aria-disabled="true"><span>صفحه اصلی</span><i class="q-btn__i">${I.home}</i></button>` : SECONDARY('صفحه اصلی', ' data-act="loss-home"', I.home)}</footer>`, toastHTML(m.toast));
}
export function codeCard(c, copied) {
  const body = c.status === 'pending' ? `<p class="q-code__val">${SPIN}<span>در حال صدور کد…</span></p>`
    : `<p class="q-code__val"><b dir="ltr">${esc(c.code)}</b>${BTN('q-btn--text', copied ? 'کپی شد' : 'کپی کد', ' data-act="copy"')}</p>`;
  return `<section class="q-code" aria-label="کد تخفیف جایزه"><b>کد تخفیف ${money(c.amountToman)} تومانی</b>${body}<small>${esc(c.terms || '')}</small></section>`;
}

// ---------------------------------------------------------------- G10 connection, G11 unavailable
export function vSystem(v, m) {
  const pg = (ic, k, t, p, a) => page('q-sys', hdr('', 'exit-app', 'بازگشت به اپ') + statecard(ic, k, t, p, a), toastHTML(m.toast));
  const R = m.ended || {};
  const HOME = SECONDARY('صفحه اصلی', ' data-act="home"', I.home);
  if (v === 'G10.RESUMING') return page('q-sys', hdr('', 'exit-app', 'بازگشت به اپ') + `<div class="q-state" role="status">${SPIN}<h2>برگشتی!</h2><p>داریم آخرین وضعیت دستت رو از سرور می‌گیریم…</p></div>`);
  if (v === 'G10.INACTIVITY_END') return pg('clock', 'warn', 'این دست تموم شد', `بیشتر از ۳ دقیقه بین سؤال‌ها کاری انجام نشد. دستت با ${fa(R.score || 0)} جواب درست ثبت شد.`, PRIMARY('صفحه اصلی', ' data-act="home"', I.home));
  if (v === 'G11.GAME_OFF') return pg('power', '', 'بازی موقتاً در دسترس نیست', m.playerMessage ? esc(m.playerMessage) : 'در حال به‌روزرسانی هستیم. کمی بعد دوباره سر بزن.', SECONDARY('بازگشت به اپ', ' data-act="exit-app"'));
  if (v === 'G11.AUTH_ERROR') return pg('user', 'no', 'ورود انجام نشد', 'برای بازی باید وارد حساب دیجی‌کالا باشی. از داخل اپ دوباره وارد شو.', PRIMARY('بازگشت به اپ', ' data-act="exit-app"'));
  if (v === 'G11.SERVICE_ERROR') return pg('alert', 'no', 'یه مشکلی پیش اومد', 'سرویس بازی الان جواب نمی‌ده. امتیاز و دست‌هات محفوظه؛ چند لحظه دیگه دوباره امتحان کن.', PRIMARY('تلاش دوباره', ' data-act="boot"', I.refresh) + SECONDARY('بازگشت به اپ', ' data-act="exit-app"'));
  if (v === 'G11.OTHER_DEVICE') return pg('devices', '', 'این دست تموم شد', `روی دستگاه دیگه‌ای دست جدید شروع کردی. این دست با ${fa(R.score || 0)} جواب درست ثبت شد.`, HOME);
  if (v === 'G11.BLOCKED') return pg('lock', 'no', 'بازی غیرفعال شده است', '', SECONDARY('بازگشت به اپ', ' data-act="exit-app"'));   // exact copy (R-61)
  if (v === 'G03.NO_PRODUCTS_LEFT') return pg('check', 'ok', 'کالاها تموم شد!', `کالای مناسب دیگه‌ای برای این دست نمونده. دستت با ${fa(R.score || 0)} جواب درست ثبت شد.`, PRIMARY('صفحه اصلی', ' data-act="home"', I.home));
  return '';
}

export { L, ICO, SPIN };
