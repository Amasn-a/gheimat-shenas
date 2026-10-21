// Player state controller: one authoritative store drives the DOM. Explicit view states with transition guards; run,
// question and operation identities plus a generation counter make stale responses and timers harmless. The server
// decides timing, results, money and rewards; this file only renders what the server said and sends guarded intents.
// Redesign 2026-10-21: splash → (onboarding) → home; the loss screen is the one surface for a miss and the continue
// of the same run; checkpoint rewards are registered with the answer that crosses them, the celebration only continues.
import { api, ApiError, newKey } from './api.js';
import * as clock from './clock.js';
import * as host from './host.js';
import * as motion from './motion.js';
import { DUR } from './durations.js';
import * as V from './views.js';
import { DEMO_FIRST, DEMO_SECOND } from './demo.js';
import { fa, untilInstant } from './fmt.js';

const $ = (s, r = document) => r.querySelector(s);
const sleep = ms => new Promise(r => setTimeout(r, ms));

export const S = {
  view: 'G00.SPLASH',
  boot: null, balance: null,
  run: null,          // last run view from the server
  ref: null,          // first product card
  q: null,            // open question { questionId, product, t0, inputDeadline, acceptDeadline, imgReady, imgSlow, pick, key, locked }
  fb: null,           // feedback { kind, choice, direction, revealedPrice, product, equal }
  offer: null, op: null,
  result: null, ended: null,
  home: { runId: null, products: null, state: 'none', positioned: null, scroll: null },   // «کالاهای دست قبلی»
  ui: { toast: null, leave: false, cp: null, offline: !navigator.onLine, resuming: false, queued: null, morph: null,
        guardUntil: 0, codeState: null, afterContinue: false, noOffer: null, cpShown: new Set(), lossReason: null,
        onb: null, daily: null, slowSettle: false, offerOver: false, guideFrom: null, recordToast: null },
  keys: {},           // one Idempotency-Key per intent, reused for its retries
  gen: 0,
};

let root = null, screenEl = null, cpEl = null;
const now = () => performance.now();
function later(ms, fn) { const g = S.gen; return setTimeout(() => { if (g === S.gen) fn(); }, ms); }
/** Per-run transient UI, cleared in one place whenever a run ends, is replaced, or a new one starts or boots. */
function resetRunUi() {
  Object.assign(S.ui, { leave: false, cpDue: null, queued: null, resuming: false, afterContinue: false, noOffer: null, guardUntil: 0,
    offerEnded: false, offerOver: false, slowSettle: false, cpShown: new Set(), lossReason: null, recordToast: null, codeState: null });
  unmountCp();
}
const cfg = () => (S.run && S.run.runConfig) || (S.boot && S.boot.config) || { checkpoints: [], continueRule: { enabled: false, cost: 0, cap: 0 }, inputMs: 10000, acceptMs: 12500, offerMs: 10000, offerAcceptMs: 15000 };
const inRun = v => /^G03\.|^G04\.|^G05\.|^G06\./.test(v) && v !== 'G03.NO_PRODUCTS_LEFT';
const isLoss = v => /^G05\.|^G06\./.test(v) && v !== 'G06.SUCCESS';
/** A01 switch of the run's (or, before a run, the active) configuration; on when an older server sends none. */
const feat = k => { const f = cfg().features; return !f || f[k] !== false; };
const haptic = kind => { if (feat('haptics')) host.haptic(kind); };
const cpIndex = c => cfg().checkpoints.findIndex(x => x.checkpointId === c.checkpointId);

// ------------------------------------------------------------------------------------------------ rendering
function screen() {
  const v = S.view;
  if (v === 'G00.SPLASH' || v === 'G00.ERROR') return V.vSplash(v === 'G00.ERROR');
  if (v.startsWith('G01.')) return homeScreen();
  if (v === 'G02.GUIDE') return V.vGuide({ config: cfg() });
  if (v === 'G02.ONBOARDING') return onboardScreen();
  if (v === 'G03.LOADING') return loadingRun();
  if (['G03.READY', 'G03.PREPARING', 'G03.PREPARE_FAILED', 'G06.SUCCESS'].includes(v)) return ready();
  if (v === 'G03.QUESTION_ACTIVE' || v === 'G03.ANSWER_PENDING') return question();
  if (['G03.CORRECT_FEEDBACK', 'G03.WRONG_FEEDBACK', 'G03.TIMEOUT'].includes(v)) return feedback();
  if (isLoss(v)) return lossScreen();
  return V.vSystem(v, { toast: S.ui.toast, ended: S.ended, playerMessage: S.boot && S.boot.access ? S.boot.access.message : null });
}

function homeScreen() {
  const b = S.boot;
  return V.vHome(S.view, {
    boot: b, balance: S.balance, toast: S.ui.toast, offline: S.ui.offline,
    untilReset: untilInstant(clock.ms(b.quota.resetsAt) - clock.serverNow()),
    productsOn: feat('products'), products: S.home.products, historyState: S.home.state,
    modal: S.ui.daily && b.dailyReward ? V.dailyModal(b.dailyReward, S.ui.daily) : '',
  });
}

const top = () => V.gameTop(S.run.score, !!S.op || S.view === 'G03.ANSWER_PENDING');
const trk = () => V.track(S.run.score, cfg().checkpoints);
const leave = () => V.leaveSheet({ leave: S.ui.leave, score: S.run.score, checkpoint: S.run.highestCheckpoint, questionOpen: ['G03.QUESTION_ACTIVE', 'G03.ANSWER_PENDING'].includes(S.view) });

function loadingRun() {
  return V.game(V.gameTop(0, true), V.track(0, cfg().checkpoints), V.timer({ mode: 'idle' }),
    '<article class="q-ref q-skel" aria-busy="true" aria-label="کالای اول در راهه"></article>' + V.revealCard('busy'), '', '', S.ui.toast);
}

function connPill() {
  if (S.ui.resuming) return V.pill('در حال اتصال دوباره…', 'refresh');
  return '';
}

function ready() {
  const v = S.view, off = S.ui.offline;
  const st = v === 'G03.PREPARING' ? 'busy' : v === 'G03.PREPARE_FAILED' ? 'failed' : off ? 'off' : 'on';
  const lead = connPill();                                    // after a continue the toast says so; no extra row that could push the button off
  return V.game(top(), trk(), V.timer({ mode: 'idle' }), lead + V.refCard(S.ref, false) + V.revealCard(st),
    '<p class="q-hintline">قبل از نمایش، کالای اول رو ببین</p>', leave(), S.ui.toast);
}

/** Visible countdown of the open question: whole seconds from the server-anchored deadline (never a decremented counter). */
function timerNow() {
  const total = cfg().inputMs, q = S.q;
  const rem = Math.max(0, Math.min(total, clock.ms(q.inputDeadline) - clock.serverNow()));
  return { rem, t: fa(Math.ceil(rem / 1000)), p: (rem / total).toFixed(3), low: rem <= 3000 };
}

function question() {
  const q = S.q, pend = !!q.pick, off = S.ui.offline, tn = timerNow(), over = !pend && (q.locked || tn.rem <= 0);
  let lead = off ? V.pill('اتصال قطع شد؛ تا برگرده نمی‌شه جواب داد و زمان ادامه داره.', 'wifi') : connPill();
  if (S.ui.slowSettle) lead = V.pill(pend ? 'پاسخت ثبت شده؛ نتیجه‌اش هنوز نرسیده.' : 'نتیجه هنوز نرسیده.', 'refresh', '<button class="q-pill__btn" data-act="recheck-answer">بررسی دوباره</button>');
  const tm = pend ? V.timer({ mode: 'busy' }) : over ? V.timer({ mode: 'zero' }) : V.timer({ mode: tn.low ? 'low' : 'run', t: tn.t, p: tn.p });
  const line = pend ? V.prompt('در حال بررسی پاسخ...', true) : over ? V.prompt('زمان تموم شد؛ در حال بررسی…', true) : V.prompt(V.Q_PROMPT, off);
  return V.game(top(), trk(), tm, lead + V.refCard(S.ref, true) + V.cmpCard(q.product, 'hidden', 0, q.imgSlow && !q.imgReady),
    V.decide(pend ? 'pending' : off || over ? 'off' : 'on', q.pick, line, S.ref.price), leave(), S.ui.toast);
}

/** The server's verdict in the place the player acted: same cards, same buttons, same sizes (§8). */
function feedback() {
  const fb = S.fb;
  if (!fb) return ready();
  const dir = V.dirOf(fb.direction), truthW = V.DIRW[dir];
  let tm, card, dec;
  if (fb.kind === 'correct') {
    tm = V.timer({ mode: 'done' });
    card = V.cmpCard(fb.product, 'ok', fb.revealedPrice);
    dec = V.decide('ok', fb.choice, V.verdict('ok', 'درست گفتی!', fb.equal ? 'قیمت‌ها برابر بود؛ هر دو جواب درسته' : `${truthW} بود`));
  } else if (fb.kind === 'wrong') {
    tm = V.timer({ mode: 'done' });
    card = V.cmpCard(fb.product, 'no', fb.revealedPrice);
    dec = V.decide('no', fb.choice, V.verdict('no', 'جوابت درست نبود', `${truthW} بود`));
  } else {   // timeout: no answer is shown as the player's
    tm = V.timer({ mode: 'zero' });
    card = V.cmpCard(fb.product, 'time', fb.revealedPrice);
    dec = V.decide('truth', fb.direction === 'higher' ? 'higher' : 'lower', V.verdict('time', 'زمان تموم شد', `${truthW} بود`));
  }
  return V.game(top(), trk(), tm, connPill() + V.refCard(S.ref, true) + card, dec, leave(), S.ui.toast);
}

/** G05/G06: the loss screen and every state of the continue of the same run (§11, §12). */
function lossScreen() {
  const v = S.view, run = S.run, c = cfg(), cost = S.offer ? S.offer.cost : c.continueRule.cost;
  const COST = remS => `<p class="q-cost">${V.COIN}<span>${fa(cost)} دیجی‌کوین</span>${remS != null ? `<span class="q-cost__t" aria-hidden="true">· <b data-offer-left>${fa(remS)}</b> ثانیه</span>` : ''}</p>`;
  const remS = S.offer ? Math.max(0, Math.ceil((clock.ms(S.offer.deadline) - clock.serverNow()) / 1000)) : null;
  const GO = V.PRIMARY('ادامه', ' data-act="continue"', V.ICO('fwd', 22));
  let action = '', homeLocked = false;
  if (v === 'G05.LOADING') { action = V.BUSY('ادامه') + COST(null); homeLocked = true; }
  else if (v === 'G05.OFFER' && S.ui.offerOver) action = V.status('', 'زمان ادامه تموم شد؛ در حال ثبت پایان دست…', null, true);
  else if (v === 'G05.OFFER') action = GO + COST(remS);
  else if (v === 'G05.NO_FUNDS') {
    action = `<button class="q-btn q-btn--primary" disabled aria-disabled="true"><span>ادامه</span><i class="q-btn__i">${V.ICO('fwd', 22)}</i></button>` +
      V.status('warn', `برای ادامه ${fa(cost)} دیجی‌کوین لازمه${S.balance != null ? `؛ موجودیت ${fa(S.balance)} دیجی‌کوینه` : ''}.`, 'info');
  } else if (v === 'G06.PENDING') { action = V.BUSY('در حال بررسی پرداخت…') + COST(null); homeLocked = true; }
  else if (v === 'G06.UNKNOWN') {
    action = V.status('warn', 'نتیجه پرداخت هنوز مشخص نیست. هیچ مبلغی دوباره کسر نمی‌شه؛ اگه ادامه ممکن نشه، هزینه برمی‌گرده.', null, true) +
      V.SECONDARY('بررسی دوباره', ' data-act="recheck"', V.ICO('refresh')); homeLocked = true;
  } else if (v === 'G06.FAILED_RETRY') action = V.status('no', 'پرداخت انجام نشد و کوینی کسر نشد. هنوز فرصت داری دوباره امتحان کنی.', 'alert', false, true) + GO + COST(remS);
  else if (v === 'G06.FAILED_END') action = V.status('no', 'پرداخت انجام نشد و کوینی کسر نشد؛ ادامه انجام نشد.', 'alert', false, true);
  else if (v === 'G06.REFUNDED') action = V.status('ok', `ادامه ممکن نشد؛ ${fa(cost)} دیجی‌کوین به حسابت برگشت.`, 'check');
  else if (v === 'G06.FAILED_END.UNCONFIRMED') action = V.status('no', 'پرداخت به‌موقع تأیید نشد و ادامه انجام نشد؛ اگه مبلغی کسر شده باشه، خودکار برمی‌گرده.', 'alert');
  // G05.LOSS: the run is over without a continue (limit used, window over, skipped): only «صفحه اصلی»
  const best = Math.max(S.result ? S.result.record : 0, (S.boot && S.boot.record) || 0, run ? run.score : 0);
  const cp = run ? run.highestCheckpoint : null;
  const R = S.result, code = R && R.campaignCode && R.campaignCode.status !== 'sold_out' ? V.codeCard(R.campaignCode, S.ui.codeState === 'copied') : '';
  return V.vLoss({ reason: S.ui.lossReason, score: run ? run.score : 0, best, cp, cpIndex: cp ? cpIndex(cp) : 0, action, homeLocked, code, toast: S.ui.toast });
}

// ------------------------------------------------------------------------------------------------ onboarding (§14)
const SPOT = [
  { target: 'first', title: 'اسم و قیمت این کالا رو ببین', text: 'قیمت کالای اول، راهنمای توئه.' },
  { target: 'second,answers', title: 'حدس بزن این یکی گرون‌تره یا ارزون‌تر', text: 'یکی از این دو تا دکمه رو انتخاب می‌کنی.' },
  { target: 'timer', title: 'برای هر مقایسه ۱۰ ثانیه وقت داری', text: 'قبل از صفر جوابت رو انتخاب کن.' },
];
function onboardScreen() {
  const o = S.ui.onb, c = cfg();
  if (o.page === 2) return V.vOnboard2({ config: c, busy: o.busy, error: o.error });
  const scene = V.game(V.gameTop(0, true), V.track(0, c.checkpoints), V.timer({ mode: 'run', t: fa(Math.round(c.inputMs / 1000)), p: 1 }),
    V.refCard(DEMO_FIRST, true) + V.cmpCard(DEMO_SECOND, 'hidden', 0), V.decide('off', null, V.prompt(V.Q_PROMPT)), '', null);
  return V.vOnboard1({ busy: o.busy, error: o.error }, scene, o.step, SPOT, motion.reducedMotion());
}
let spotTimer = 0;
function afterOnboardRender() {
  positionSpot();
  requestAnimationFrame(positionSpot);
  clearTimeout(spotTimer);
  const o = S.ui.onb;
  if (o && o.page === 1 && !motion.reducedMotion()) {          // each emphasis about 2.6 s, then the loop starts again
    const g = S.gen;
    spotTimer = setTimeout(() => { if (g === S.gen && S.ui.onb === o && S.view === 'G02.ONBOARDING' && o.page === 1) { o.step = (o.step + 1) % SPOT.length; render(); } }, 2600);
  }
}
/** The hole follows the real size of the demo elements; its box-shadow dims everything else (smooth CSS move, no flash). */
function positionSpot() {
  const o = S.ui.onb; if (!o || o.page !== 1) return;
  const ov = root.querySelector('.q-onb--1'); if (!ov) return;
  const sc = ov.querySelector('.q-onb__scene'), pg = sc && sc.firstElementChild, NEED = 700;
  if (pg && sc.clientHeight < NEED) {                          // a short screen sees the whole sandbox, scaled, never squeezed
    const k = sc.clientHeight / NEED;
    Object.assign(pg.style, { height: NEED + 'px', width: (sc.clientWidth / k) + 'px', bottom: 'auto', left: 'auto', transform: `scale(${k})`, transformOrigin: 'top right' });
  }
  const R = ov.getBoundingClientRect(), hole = ov.querySelector('[data-hole]'), tip = ov.querySelector('[data-tip]');
  const els = SPOT[o.step].target.split(',').map(k => ov.querySelector(`.q-onb__scene [data-tour="${k}"]`)).filter(Boolean);
  if (!els.length || !hole) return;
  const rs = els.map(e => e.getBoundingClientRect()), pad = 8;
  const x = Math.max(4, Math.min(...rs.map(r => r.left)) - R.left - pad), y = Math.max(4, Math.min(...rs.map(r => r.top)) - R.top - pad);
  const x2 = Math.min(R.width - 4, Math.max(...rs.map(r => r.right)) - R.left + pad), y2 = Math.max(...rs.map(r => r.bottom)) - R.top + pad;
  Object.assign(hole.style, { left: x + 'px', top: y + 'px', width: (x2 - x) + 'px', height: (y2 - y) + 'px' });
  if (!tip) return;
  const panel = ov.querySelector('.q-onb__panel'), limit = panel ? panel.getBoundingClientRect().top - R.top - 12 : R.height - 12;
  const th = tip.offsetHeight, below = y2 + 14, above = y - 14 - th;
  tip.style.top = (below + th <= limit ? below : above >= 12 ? above : Math.max(12, limit - th)) + 'px';
}
function startOnboarding() {
  S.ui.daily = null;
  S.ui.onb = { page: 1, step: 0, busy: false, error: null };
  host.setBackHandler(() => { const o = S.ui.onb; if (!o) return false; if (o.page === 2 && !o.busy) { o.page = 1; o.error = null; render(); } return true; });
  go('G02.ONBOARDING');
}
/** Finishing or skipping is stored on the account (one request, same key on retry); the home follows. No play is spent. */
async function finishOnboarding() {
  const o = S.ui.onb; if (!o || o.busy) return;
  o.busy = true; o.error = null; render();
  const key = S.keys.onboarding ||= newKey(), g = S.gen;
  for (let i = 0; i < 2; i++) {
    try {
      await api.onboarding(key);
      if (g !== S.gen) return;
      S.keys.onboarding = null; S.boot.firstVisit = false; S.ui.onb = null; host.setBackHandler(null);
      return routeHome();
    } catch (e) {
      if (g !== S.gen) return;
      if (!(e instanceof ApiError) || !e.network || i === 1) break;
      await sleep(800);
    }
  }
  Object.assign(o, { busy: false, error: 'ذخیره نشد؛ اتصال رو بررسی کن و دوباره بزن.' }); render();
}

export function render() {
  if (!root) return;
  const ms = S.view.startsWith('G11.') ? 0 : S.ui.morph != null ? S.ui.morph : motion.running() ? Math.max(160, motion.remaining()) : 0;
  S.ui.morph = null;
  const before = ms && !motion.reducedMotion() ? motion.snapshot(root) : null;
  const hs = root.querySelector('[data-hscroll]');
  if (hs && S.home.positioned) S.home.scroll = hs.scrollLeft;   // a re-render never resets the player's own scrolling
  motion.end();
  screenEl.innerHTML = screen();
  root.dataset.view = stateId();
  wireImages();
  if (before) motion.morph(root, before, ms, () => { const q = S.ui.queued; S.ui.queued = null; if (q === 'reveal') setTimeout(reveal, 0); });
  if (root.querySelector('[data-countdown],[data-offer-left]')) tickLoop();
  if (S.view === 'G02.ONBOARDING') afterOnboardRender();
  if (S.view.startsWith('G01.')) afterHomeRender();
}

/** Fine-grained state id (as in the handoff state index) for analytics, tests and reviewers. */
export function stateId() {
  const v = S.view;
  if (S.ui.cp && (v === 'G03.READY' || v === 'G06.SUCCESS')) return 'G04.REACHED';
  if (S.ui.resuming && inRun(v)) return 'G10.RESUMING';
  if (v === 'G02.ONBOARDING' && S.ui.onb) return `G02.ONBOARDING.P${S.ui.onb.page}${S.ui.onb.busy ? '.SAVING' : S.ui.onb.error ? '.ERROR' : ''}`;
  if (v.startsWith('G01.') && S.ui.daily) return 'G01.DAILY_REWARD.' + S.ui.daily.phase.toUpperCase();
  if (v === 'G03.QUESTION_ACTIVE' && S.q) {
    if (S.ui.offline) return 'G10.OFFLINE_AFTER_RELEASE';
    if (S.q.locked || timerNow().rem <= 0) return 'G03.TIME_UP_PENDING';
    if (S.q.imgSlow && !S.q.imgReady) return 'G03.QUESTION_ACTIVE.IMAGE_SLOW';
    if (timerNow().low) return 'G03.QUESTION_ACTIVE.LOW_TIME';
  }
  if (v === 'G03.ANSWER_PENDING' && S.ui.offline) return 'G10.OFFLINE_AFTER_RELEASE';
  if (v === 'G03.ANSWER_PENDING' && S.ui.slowSettle) return 'G03.ANSWER_PENDING.RECOVERY';
  if (v === 'G03.READY' && S.ui.offline) return 'G10.OFFLINE_BEFORE_RELEASE';
  if (v === 'G03.CORRECT_FEEDBACK' && S.fb && S.fb.equal) return 'G03.CORRECT_FEEDBACK.EQUAL';
  if (v === 'G05.OFFER' && S.ui.offerOver) return 'G05.OFFER.TIME_UP';
  if (v === 'G05.LOSS' && S.ui.noOffer === 'limit_used') return 'G05.LOSS.LIMIT_USED';
  if (v === 'G01.READY' && S.boot) {
    const b = S.boot;
    if (S.ui.offline) return 'G01.READY.OFFLINE';
    if (b.activeRun && !b.activeRun.onThisDevice) return 'G01.READY.OTHER_DEVICE_RUN';
    if (b.campaign) return 'G01.READY.CAMPAIGN';
    if (!b.record && !b.lastRunId) return 'G01.READY.NEW_PLAYER';
  }
  if (S.ui.leave && inRun(v)) return 'G03.LEAVE_CONFIRM';
  return v;
}

function wireImages() {
  const im = root.querySelector('img[data-onload="q"]');
  if (!im || !S.q) return;
  const qid = S.q.questionId;
  const done = () => { if (S.q && S.q.questionId === qid && !S.q.imgReady) { S.q.imgReady = true; if (S.q.imgSlow) render(); } };
  if (im.complete && im.naturalWidth) done(); else { im.addEventListener('load', done, { once: true }); im.addEventListener('error', () => {}, { once: true }); }
}

function go(view) { S.view = view; render(); }
function toast(text, no, id) {
  S.ui.toast = { text, no, id, at: now() };
  const t = S.ui.toast; setTimeout(() => { if (S.ui.toast === t) { S.ui.toast = null; render(); } }, DUR.toastMs);
}

// ------------------------------------------------------------------------------------------------ errors → screens
function handleError(e, context) {
  if (!(e instanceof ApiError)) { console.error(e); return go('G11.SERVICE_ERROR'); }
  if (e.status === 401) return go('G11.AUTH_ERROR');
  if (e.code === 'PLAYER_BLOCKED') return go('G11.BLOCKED');
  if (e.code === 'RUN_REPLACED') { S.ended = endedFrom(e.state); return go('G11.OTHER_DEVICE'); }
  if (e.code === 'GAME_UNAVAILABLE') { if (e.state && e.state.state === 'FINISHED') return finished(e.state); return go('G11.GAME_OFF'); }
  if (e.state) return applyRun(e.state, context);
  if (e.network) { S.ui.offline = !navigator.onLine || S.ui.offline; return context === 'boot' ? go('G00.ERROR') : render(); }
  if (e.status >= 500) return go(context === 'boot' ? 'G00.ERROR' : 'G11.SERVICE_ERROR');
  console.warn('unhandled', e.code); return go('G11.SERVICE_ERROR');
}
const endedFrom = st => st ? { score: st.score, checkpoint: st.highestCheckpoint, runId: st.runId } : (S.run ? { score: S.run.score, checkpoint: S.run.highestCheckpoint, runId: S.run.runId } : null);

// ------------------------------------------------------------------------------------------------ splash, home
/**
 * Splash (§3): shown only on the first entry. It waits for the essential data (configuration, authentication, the
 * player's state) — no invented progress or forced delay. A new account goes to the onboarding, others to the home.
 * A run in progress on this device comes first (its deadlines keep running).
 */
export async function boot() {
  S.gen++; motion.end();
  Object.assign(S, { run: null, ref: null, q: null, fb: null, offer: null, op: null, result: null, keys: {} });
  resetRunUi();
  Object.assign(S.ui, { toast: null, onb: null, daily: null });
  host.setBackHandler(null);
  if (!S.boot) go('G00.SPLASH');
  else go('G01.REFRESHING');                                   // back home between runs: no splash again (§3)
  try { S.boot = await api.bootstrap(); }
  catch (e) { return handleError(e, S.view === 'G00.SPLASH' ? 'boot' : 'home'); }
  const b = S.boot; S.balance = b.balance;
  if (b.access.status === 'blocked') return go('G11.BLOCKED');
  if (b.access.status === 'game_off') return go('G11.GAME_OFF');
  loadHistory(b.lastRunId);
  if (b.activeRun && b.activeRun.onThisDevice && b.activeRun.state === 'PAYMENT_PENDING') { go('G01.OPERATION_PENDING'); return pollPendingAtStart(b.activeRun); }
  if (b.firstVisit && !(b.activeRun && b.activeRun.onThisDevice)) return startOnboarding();
  routeHome();
}

/** Home in the state the server described, with the daily reward modal on top when a reward waits for its claim. */
function routeHome() {
  const b = S.boot;
  if (b.activeRun && b.activeRun.onThisDevice) return go('G01.ACTIVE_RUN');
  const d = b.dailyReward;
  if (d && (d.state === 'claimable' || d.state === 'processing')) {
    S.ui.daily = { phase: d.state === 'processing' ? 'processing' : 'claimable' };
    if (d.state === 'processing') pollDaily();
  }
  go(b.quota.playsLeft > 0 ? 'G01.READY' : 'G01.QUOTA_EXHAUSTED');
  if (S.ui.daily) focusModal();
}
function focusModal() { requestAnimationFrame(() => { const b = root.querySelector('.q-daily .q-btn:not([disabled])'); if (b) b.focus({ preventScroll: true }); }); }

/** «کالاهای دست قبلی»: only the products shown in the player's latest finished run (§5), loaded once per run id. */
async function loadHistory(runId) {
  if (!feat('products')) return;
  if (!runId) { Object.assign(S.home, { runId: null, products: null, state: 'none' }); return; }
  if (S.home.runId === runId && S.home.state === 'ready') return;
  Object.assign(S.home, { runId, products: null, state: 'loading', positioned: null, scroll: null });
  try {
    const list = await api.products(runId);
    if (S.home.runId !== runId) return;
    S.home.products = list; S.home.state = 'ready';
  } catch (e) { if (S.home.runId === runId) S.home.state = 'error'; }
  if (S.view.startsWith('G01.')) render();
}
/** Restore the player's own scroll; once per run, bring the card of the last loss into view. */
function afterHomeRender() {
  const sc = root.querySelector('[data-hscroll="history"]'); if (!sc) return;
  const runId = S.home.runId;
  if (S.home.positioned === runId && S.home.scroll != null) sc.scrollLeft = S.home.scroll;
  sc.addEventListener('scroll', () => { if (S.home.positioned === runId) S.home.scroll = sc.scrollLeft; }, { passive: true });
  if (S.home.positioned === runId) return;
  requestAnimationFrame(() => {
    const s2 = root.querySelector('[data-hscroll="history"]'); if (!s2 || S.home.positioned === runId) return;
    const t = s2.querySelector('[data-loss]');
    if (t) s2.scrollLeft += t.getBoundingClientRect().left + t.offsetWidth / 2 - (s2.getBoundingClientRect().left + s2.clientWidth / 2);   // centre, RTL-safe
    S.home.positioned = runId; S.home.scroll = s2.scrollLeft;
  });
}

async function pollPendingAtStart(active) {   // 1 s backing off to 2 s while the server still waits for the payment
  const g = S.gen; let delay = 1000;
  while (g === S.gen) {
    await sleep(delay); delay = Math.min(2000, delay + 500);
    if (g !== S.gen) return;
    try {
      const run = await api.run(active.runId);
      if (run.state !== 'PAYMENT_PENDING') { S.boot.activeRun = run.state === 'FINISHED' ? null : { ...active, state: run.state }; return run.state === 'FINISHED' ? boot() : go('G01.ACTIVE_RUN'); }
    } catch (e) { if (!(e instanceof ApiError) || !e.network) return handleError(e); }
  }
}

// ------------------------------------------------------------------------------------------------ daily reward (G01.DAILY_REWARD)
/** One claim per tap; the first success closes the modal and shows the home (no second «دریافت» surface, §10, §15). */
async function claimDaily() {
  const d = S.ui.daily; if (!d || ['claiming', 'processing'].includes(d.phase)) return;
  d.phase = 'claiming'; render();
  const key = S.keys.daily ||= newKey(), g = S.gen;
  try {
    const v = await api.claimDaily(key);
    if (g !== S.gen) return;
    S.keys.daily = null;
    applyDaily(v);
  } catch (e) {
    if (g !== S.gen) return;
    if (e instanceof ApiError && e.network) { Object.assign(S.ui.daily, { phase: 'error', message: null }); return render(); }   // same key on retry
    if (e instanceof ApiError && e.code === 'REQUEST_IN_PROGRESS') { S.ui.daily.phase = 'processing'; render(); return pollDaily(); }
    S.keys.daily = null;
    if (e instanceof ApiError && (e.code === 'PLAYER_BLOCKED' || e.status === 401)) { S.ui.daily = null; return handleError(e); }
    Object.assign(S.ui.daily, { phase: 'error', message: 'جایزه الان ثبت نشد؛ جایزه‌ات محفوظه. دوباره امتحان کن.' }); render();
  }
}
function applyDaily(v) {
  if (!S.ui.daily || !S.boot) return;
  S.boot.dailyReward = v;
  if (v.state === 'processing') { S.ui.daily.phase = 'processing'; render(); return pollDaily(); }
  if (v.state === 'claimable') { S.ui.daily.phase = 'claimable'; render(); return focusModal(); }
  S.ui.daily = null;                                            // claimed (or delayed: recorded, paid later): straight on to the home
  if (v.state === 'delayed') toast('جایزه‌ات ثبت شد؛ واریزش کمی طول می‌کشه.');
  render();
  refreshBalance();
}
async function refreshBalance() {   // the home's balance after a credit: the server's number only, never a local sum
  const g = S.gen;
  try { const b = await api.bootstrap(); if (g !== S.gen || !S.view.startsWith('G01.')) return; S.boot = { ...S.boot, ...b, dailyReward: S.boot.dailyReward }; S.balance = b.balance; render(); } catch (e) { /* the old number stays */ }
}
async function pollDaily() {   // every 1 s for 8 s, then the recovery state with «بررسی دوباره»
  if (S.ui.dailyPolling) return;
  S.ui.dailyPolling = true;
  const g = S.gen;
  try {
    for (let i = 0; i < 8; i++) {
      await sleep(1000);
      if (g !== S.gen || !S.ui.daily || S.ui.daily.phase !== 'processing') return;
      try { const v = await api.dailyReward(); if (g !== S.gen || !S.ui.daily) return; if (v.state !== 'processing') return applyDaily(v); }
      catch (e) { /* stays processing; the recovery state follows */ }
    }
    if (g === S.gen && S.ui.daily && S.ui.daily.phase === 'processing') { S.ui.daily.phase = 'slow'; render(); }
  } finally { S.ui.dailyPolling = false; }
}
async function checkDaily() {
  const g = S.gen;
  if (!S.ui.daily) return;
  S.ui.daily.phase = 'processing'; render();
  try { const v = await api.dailyReward(); if (g === S.gen) { if (v.state === 'processing') { S.ui.daily.phase = 'slow'; render(); } else applyDaily(v); } }
  catch (e) { if (g === S.gen && S.ui.daily) { S.ui.daily.phase = 'slow'; render(); } }
}

// ------------------------------------------------------------------------------------------------ guide (G02.GUIDE)
function openGuide() {
  S.ui.guideFrom = S.view; S.ui.daily = null;
  host.setBackHandler(() => { if (S.view === 'G02.GUIDE') { closeGuide(); return true; } return false; });
  go('G02.GUIDE');
}
function closeGuide() { host.setBackHandler(null); const v = S.ui.guideFrom || 'G01.READY'; S.ui.guideFrom = null; go(v); }   // opening and closing creates nothing

// ------------------------------------------------------------------------------------------------ start / resume
export async function startRun() {
  if (S.view !== 'G01.READY' || S.ui.offline) return;
  go('G01.STARTING');
  const key = S.keys.start ||= newKey();
  try {
    const st = await api.start(key);
    S.keys.start = null;
    S.gen++;
    S.result = null; S.ended = null;
    resetRunUi();
    S.run = st.run; S.ref = st.run.reference;
    host.setBackHandler(onBack);
    go('G03.READY');
  } catch (e) {
    if (e instanceof ApiError) {
      if (e.code === 'ACTIVE_RUN_EXISTS') { S.keys.start = null; return resume(e.body.run_id); }
      if (e.code === 'OPERATION_PENDING') { S.keys.start = null; go('G01.OPERATION_PENDING'); return pollPendingAtStart({ runId: e.body.run_id }); }
      if (e.code === 'DAILY_QUOTA_EXHAUSTED') { S.keys.start = null; return boot(); }
    }
    // a failed request spends nothing: the same key is reused, so a retry never takes a second play (§5)
    if (e instanceof ApiError && e.network) { toast('اتصال برقرار نشد؛ دوباره امتحان کن.', true); return go('G01.READY'); }
    S.keys.start = null;
    handleError(e);
  }
}

export async function resume(runId) {
  go('G10.RESUMING');
  const g = S.gen;
  try { const run = await api.run(runId); if (g !== S.gen) return; host.setBackHandler(onBack); applyRun(run, 'resume'); }
  catch (e) { if (g === S.gen) handleError(e); }
}

/** Rebuilds the view from an authoritative run view (resume, reconnect, any 409 with state). Deadlines never restart. */
function applyRun(run, context) {
  S.ui.resuming = false;
  const q = S.q, settled = q && run.state !== 'QUESTION_OPEN' ? answerFromRun(run, q) : null;
  if (settled) return onAnswer(settled, q);   // settled while its response was outstanding (e.g. a reconnect read): the verdict shows once, in place
  const prev = S.run;
  S.run = run; S.ref = run.reference;
  if (run.state === 'FINISHED') {
    if (S.op && S.op.operationId && run.finishReason === 'continue_failed' && ['G06.PENDING', 'G06.UNKNOWN'].includes(S.view))
      return api.operation(S.op.operationId).then(o => onOperation(o, true)).catch(() => finished(run));
    if (S.view.startsWith('G06.FAILED_END') || S.view === 'G06.REFUNDED') { S.offer = null; return render(); }   // the message stays; «صفحه اصلی» leads on
    return finished(run);
  }
  if (run.state === 'READY') {
    if (['G03.CORRECT_FEEDBACK'].includes(S.view) && context !== 'resume') return render();   // the promotion will land here anyway; drop the reconnect pill
    S.q = null; S.fb = null; S.offer = null; S.op = null;
    return go(S.view === 'G06.SUCCESS' ? 'G06.SUCCESS' : 'G03.READY');
  }
  if (run.state === 'QUESTION_OPEN') {
    const q = run.question;
    if (!S.q || S.q.questionId !== q.questionId) S.q = { ...q, imgReady: false, imgSlow: false, pick: null, key: null };
    else Object.assign(S.q, { t0: q.t0, inputDeadline: q.inputDeadline, acceptDeadline: q.acceptDeadline });
    if (S.view !== 'G03.ANSWER_PENDING') go('G03.QUESTION_ACTIVE'); else render();
    return tickLoop();
  }
  const lr = run.lastResult;
  if (lr && (!S.fb || S.fb.questionId !== lr.questionId)) {
    const product = S.q && S.q.questionId === lr.questionId ? S.q.product : (prev && prev.question && prev.question.questionId === lr.questionId ? prev.question.product : S.fb ? S.fb.product : null);
    S.fb = { questionId: lr.questionId, kind: lr.result, choice: lr.choice, direction: lr.direction, revealedPrice: lr.revealedPrice, equal: lr.equalPrices,
      product: product || { displayTitle: 'کالای بعدی', imageUrl: '' } };
  }
  if (lr) S.ui.lossReason = lr.result === 'timeout' ? 'timeout' : 'wrong';
  S.q = null;
  if (run.state === 'AWAITING_OFFER') { go('G05.LOADING'); return later(context === 'resume' ? 300 : 0, activateOffer); }
  if (run.state === 'OFFER_OPEN') {
    if (!S.offer || S.offer.offerId !== run.offer.offerId) S.ui.offerOver = false;
    if (S.op && !S.op.operationId && S.offer && S.offer.offerId === run.offer.offerId && ['G06.PENDING', 'G06.UNKNOWN'].includes(S.view)) { S.offer = run.offer; return render(); }
    S.offer = run.offer; S.op = null;
    if (['G06.FAILED_END'].includes(S.view)) return render();
    go('G05.OFFER'); return tickLoop();
  }
  if (run.state === 'PAYMENT_PENDING') {
    S.offer = run.offer;
    if (!S.op || S.op.operationId !== (run.operation && run.operation.operationId)) S.op = { operationId: run.operation ? run.operation.operationId : null, status: 'pending', since: now() };
    go(S.view === 'G06.UNKNOWN' ? 'G06.UNKNOWN' : 'G06.PENDING'); return pollOperation();
  }
}

// ------------------------------------------------------------------------------------------------ reveal / answer
export async function reveal() {
  if (!S.run || S.ui.offline) return;
  if (!['G03.READY', 'G06.SUCCESS', 'G03.PREPARE_FAILED'].includes(S.view) || S.ui.cp || S.ui.cpDue) return;
  if (now() < S.ui.guardUntil) return;                         // a tap falling through from closing the checkpoint
  if (motion.running()) { S.ui.queued = 'reveal'; return; }
  S.ui.toast = null; S.ui.afterContinue = false;
  go('G03.PREPARING');
  const runId = S.run.runId, key = S.keys.reveal ||= newKey(), g = S.gen;
  try {
    let run;
    try { run = await api.reveal(runId, key); }
    catch (e) { if (e instanceof ApiError && e.network) run = await api.reveal(runId, key); else throw e; }   // one retry, same key
    if (g !== S.gen) return;
    S.keys.reveal = null;
    S.run = run;
    if (run.state === 'FINISHED' && run.finishReason === 'no_products') { S.ended = endedFrom(run); host.setBackHandler(null); return go('G03.NO_PRODUCTS_LEFT'); }
    if (run.state !== 'QUESTION_OPEN') return applyRun(run);
    S.q = { ...run.question, imgReady: false, imgSlow: false, pick: null, key: null, buzz: 0 };
    S.ui.morph = DUR.verdictMs;
    go('G03.QUESTION_ACTIVE');
    const qid = S.q.questionId;
    later(DUR.imageSlowMs, () => { if (S.q && S.q.questionId === qid && !S.q.imgReady) { S.q.imgSlow = true; render(); } });
    tickLoop();
  } catch (e) {
    if (g !== S.gen) return;
    if (e instanceof ApiError && e.network) return go('G03.PREPARE_FAILED');   // keep the key: a retry returns the same question
    S.keys.reveal = null;
    if (e instanceof ApiError && e.code === 'PREPARE_FAILED') return go('G03.PREPARE_FAILED');
    handleError(e);
  }
}

/**
 * The selection is recorded synchronously (both answers lock, the picked one shows its spinner, the timer gives way to
 * «در حال بررسی پاسخ...») and sent at once. From then on the local timer has no say: reaching its zero neither cancels
 * the answer nor shows a loss; only the server's settled result moves the screen. A tap after the visible zero is ignored.
 */
export async function answer(choice) {
  const q = S.q;
  if (!q || q.pick || q.locked || S.view !== 'G03.QUESTION_ACTIVE' || S.ui.offline) return;
  if (clock.serverNow() >= clock.ms(q.inputDeadline)) { lockQuestion(q); return; }
  q.pick = choice; q.key ||= newKey();
  S.ui.morph = 0; go('G03.ANSWER_PENDING');
  const g = S.gen;
  for (let i = 0; i < 3; i++) {   // retry after 1.5 s with the same key, up to 2 times; the server keeps the first
    try {
      const ans = await Promise.race([api.answer(q.questionId, choice, q.key), sleep(1500 + i * 1000).then(() => { throw new ApiError(0, 'SLOW'); })]);
      if (g !== S.gen || S.q !== q) return;
      return onAnswer(ans, q);
    } catch (e) {
      if (g !== S.gen || S.q !== q) return;
      if (e instanceof ApiError && (e.network || e.code === 'REQUEST_IN_PROGRESS')) continue;
      if (e instanceof ApiError && e.code === 'QUESTION_EXPIRED' && e.state) return applyMissFromState(e.state, q);
      return handleError(e);
    }
  }
  return awaitSettlement(q);
}
function lockQuestion(q) { if (q.locked || q.pick) return; q.locked = true; render(); awaitSettlement(q); }

/** An answer the server accepted but whose response was lost is shown from the run's lastResult, never as a loss. */
function answerFromRun(run, q) {
  const lr = run.lastResult;
  if (!lr || lr.questionId !== q.questionId) return null;
  const cp = run.highestCheckpoint;
  return { result: lr.result, choice: lr.choice, direction: lr.direction, revealedPrice: lr.revealedPrice, equalPrices: lr.equalPrices, run,
    noOfferReason: run.noOfferReason, runFinished: run.state === 'FINISHED',
    checkpointReached: lr.result === 'correct' && cp && cp.requiredCorrect === run.score ? cp : null };
}
function applyMissFromState(run, q) {
  if (run.state === 'QUESTION_OPEN' && run.question && run.question.questionId === q.questionId) { S.run = run; return awaitSettlement(q); }
  S.ui.resuming = false; S.ui.slowSettle = false;
  const ans = answerFromRun(run, q);
  if (ans) return onAnswer(ans, q);
  applyRun(run);
}

/**
 * One reader for a question whose outcome is the server's to settle (an answer in flight or lost, or the visible zero
 * reached without one). A timeout cannot be settled before the arrival deadline, so it waits for that first; then it
 * reads the run every 700 ms for 12 reads and with a backoff capped at 10 s after that, showing «بررسی دوباره».
 */
async function awaitSettlement(q) {
  if (q.polling) return;
  q.polling = true;
  const g = S.gen;
  try {
    const wait = clock.ms(q.acceptDeadline) + 300 - clock.serverNow();
    if (!q.pick && wait > 0) await sleep(wait);
    for (let i = 0; g === S.gen && S.q === q; i++) {
      if (i === 12 && !S.ui.slowSettle) { S.ui.slowSettle = true; render(); }
      if (await readSettlement(q, g)) return;
      await sleep(i < 12 ? 700 : Math.min(10000, 700 * 2 ** (i - 11)));
    }
  } finally { q.polling = false; }
}
async function readSettlement(q, g) {
  try {
    const run = await api.run(S.run.runId);
    if (g !== S.gen || S.q !== q) return true;
    if (run.state !== 'QUESTION_OPEN' || run.question.questionId !== q.questionId) { S.ui.slowSettle = false; applyMissFromState(run, q); return true; }
  } catch (e) { if (!(e instanceof ApiError) || !e.network) { handleError(e); return true; } }
  return false;
}

function onAnswer(ans, q) {
  S.run = ans.run; S.ui.slowSettle = false;
  S.fb = { questionId: q.questionId, kind: ans.result, choice: ans.choice, direction: ans.direction, revealedPrice: ans.revealedPrice, equal: ans.equalPrices, product: q.product };
  S.q = null;
  S.ui.morph = DUR.verdictMs;
  S.ui.noOffer = ans.noOfferReason;
  if (ans.result === 'correct') {
    haptic('correct');
    go('G03.CORRECT_FEEDBACK');
    const reached = ans.checkpointReached, fb = S.fb, reward = ans.checkpointReward || null;
    if (ans.recordReward) S.ui.recordToast = `به رکوردت رسیدی؛ ${fa(ans.recordReward.amount)} دیجی‌کوین جایزه.`;   // a short, secondary note (§13)
    later(DUR.correctHoldMs, () => {                           // readable verdict first, then the promotion
      if (S.fb !== fb) return;
      S.ref = { displayTitle: fb.product.displayTitle, imageUrl: fb.product.imageUrl, price: fb.revealedPrice };
      S.fb = null;
      if (ans.runFinished) return finished(S.run);
      S.ui.cpDue = reached || null;
      S.ui.morph = DUR.promoteMs; go('G03.READY');             // the answered card moves into the first product's place
      if (reached) later(DUR.promoteMs + 60, () => { if (S.ui.cpDue === reached) openCp(reached, reward); });
      else showRecordToast();
    });
    return;
  }
  haptic('wrong');
  S.ui.lossReason = ans.result === 'timeout' ? 'timeout' : 'wrong';
  go(ans.result === 'timeout' ? 'G03.TIMEOUT' : 'G03.WRONG_FEEDBACK');
  afterFeedback(S.run);
}
function showRecordToast() { const t = S.ui.recordToast; if (t) { S.ui.recordToast = null; toast(t); render(); } }

/** After a miss: the verdict stays readable in place, then the loss screen opens once (§8). */
function afterFeedback(run) {
  const fb = S.fb;
  later(DUR.missHoldMs, () => {
    if (S.fb !== fb) return;
    if (run.state === 'AWAITING_OFFER') { go('G05.LOADING'); return activateOffer(); }
    if (run.state === 'OFFER_OPEN') { S.offer = run.offer; go('G05.OFFER'); return tickLoop(); }
    finished(run);
  });
}

async function activateOffer() {
  if (!S.run) return;
  const runId = S.run.runId, intent = 'offer:' + runId + ':' + (S.run.lastResult ? S.run.lastResult.questionId : ''), key = S.keys[intent] ||= newKey(), g = S.gen;
  try {
    let act;
    try { act = await api.activateOffer(runId, key); }
    catch (e) { if (e instanceof ApiError && e.network) act = await api.activateOffer(runId, key); else throw e; }
    if (g !== S.gen) return;
    delete S.keys[intent];
    S.run = act.run;
    if (act.balance != null) S.balance = act.balance;
    if (act.offer && (act.run.state === 'OFFER_OPEN' || act.run.state === 'PAYMENT_PENDING')) { S.offer = act.offer; S.ui.offerOver = false; go('G05.OFFER'); return tickLoop(); }
    S.ui.noOffer = act.noOfferReason;
    if (act.noOfferReason === 'no_funds') return finished(act.run, 'G05.NO_FUNDS');   // the same surface explains the shortfall (§12)
    finished(act.run);
  } catch (e) { if (g === S.gen) handleError(e); }
}

// ------------------------------------------------------------------------------------------------ continue / home / leave
/**
 * Extra-life purchase from the loss screen: one tap locks the button before the request leaves; the request is admitted
 * by its arrival time (offer start + 15 s). The game resumes only after the server confirms the charge; a lost response
 * is recovered by the operation's own id, never by a second purchase (§12: one 5-coin debit, one continue).
 */
export async function continueOffer() {
  if (!S.offer || S.op || S.ui.offline || S.ui.offerOver) return;
  if (clock.serverNow() >= clock.ms(S.offer.deadline)) return offerTimeUp();
  const offerId = S.offer.offerId;
  S.op = { key: S.keys['continue:' + offerId] ||= newKey(), status: 'pending', since: now(), operationId: null };
  go('G06.PENDING');
  const g = S.gen;
  try {
    let opv;
    try { opv = await api.continueOffer(offerId, S.op.key); }
    catch (e) { if (e instanceof ApiError && e.network) opv = await api.continueOffer(offerId, S.op.key); else throw e; }
    if (g !== S.gen) return;
    onOperation(opv);
  } catch (e) {
    if (g !== S.gen) return;
    if (e instanceof ApiError && (e.network || e.code === 'REQUEST_IN_PROGRESS')) { go('G06.UNKNOWN'); return pollOperation(); }
    S.op = null;
    if (e instanceof ApiError && e.code === 'OFFER_EXPIRED') return e.state ? applyRun(e.state) : reconcileRun();
    handleError(e);
  }
}

function onOperation(opv, fromPoll = false) {
  const op = S.op || {};
  if (op.operationId && opv.operationId !== op.operationId) return;   // a late response of another operation
  Object.assign(op, { operationId: opv.operationId, status: opv.status, chargeStatus: opv.chargeStatus, refundStatus: opv.refundStatus });
  S.op = op;
  if (opv.balance != null) S.balance = opv.balance;
  S.run = opv.run;
  if (opv.status === 'succeeded') {
    S.op = null; S.offer = null; S.fb = null; S.ui.afterContinue = true;
    toast(`ادامه ثبت شد؛ ${fa(cfg().continueRule.cost)} دیجی‌کوین کسر شد`);
    return go('G06.SUCCESS');                                  // same run, same first product, score and checkpoints kept (R-07)
  }
  if (opv.status === 'failed') {
    delete S.keys['continue:' + (S.offer && S.offer.offerId)];  // a retry after a rejected debit is a new request, not a replay
    S.op = null;
    if (S.offer && clock.serverNow() < clock.ms(S.offer.deadline) && opv.run.state === 'OFFER_OPEN') { go('G06.FAILED_RETRY'); return tickLoop(); }
    go('G06.FAILED_END');
    if (opv.run.state !== 'FINISHED') later(1000, reconcileRun);
    return;
  }
  if (opv.status === 'refunded') return go('G06.REFUNDED');
  if (opv.status === 'expired_unconfirmed') { go('G06.FAILED_END.UNCONFIRMED'); return watchRefund(opv.operationId); }
  if (S.view !== 'G06.UNKNOWN') go('G06.PENDING');
  if (!fromPoll) pollOperation();
}

async function pollOperation() {   // 1 s backing off to 5 s; G06.UNKNOWN (with «بررسی دوباره») after 5 s; until the server settles
  if (S.ui.polling) return;
  S.ui.polling = true;
  const g = S.gen; let delay = 1000;
  try {
    while (g === S.gen && S.op && ['G06.PENDING', 'G06.UNKNOWN'].includes(S.view)) {
      await sleep(delay); delay = Math.min(5000, delay * 1.6);
      if (g !== S.gen || !S.op) return;
      if (S.view === 'G06.PENDING' && now() - S.op.since > 5000) go('G06.UNKNOWN');
      if (!S.op.operationId) { await resendPurchase(g); continue; }
      const opId = S.op.operationId;
      try { const o = await api.operation(opId); if (g !== S.gen || !S.op) return; if (S.op.operationId !== opId) continue; onOperation(o, true); }
      catch (e) { if (g !== S.gen) return; if (!(e instanceof ApiError) || !e.network) return handleError(e); }
    }
  } finally { S.ui.polling = false; }
}

/** The purchase response was lost: the same request (same key) is sent again while it could still arrive in time. */
async function resendPurchase(g) {
  const op = S.op, offer = S.offer;
  if (!op || !offer || clock.serverNow() > clock.ms(offer.acceptDeadline || offer.deadline)) return reconcileRun();
  try { const opv = await api.continueOffer(offer.offerId, op.key); if (g === S.gen && S.op === op) onOperation(opv, true); }
  catch (e) {
    if (g !== S.gen || S.op !== op) return;
    if (e instanceof ApiError && (e.network || e.code === 'REQUEST_IN_PROGRESS')) return;
    S.op = null;
    if (e instanceof ApiError && e.state) return applyRun(e.state);
    handleError(e);
  }
}

/** A late-confirmed debit is refunded by the server; while its message is shown, look for the refund for 2 minutes. */
async function watchRefund(operationId) {
  const g = S.gen;
  for (let i = 0; i < 24; i++) {
    await sleep(5000);
    if (g !== S.gen || S.view !== 'G06.FAILED_END.UNCONFIRMED') return;
    try { const o = await api.operation(operationId); if (o.balance != null) S.balance = o.balance; if (o.status === 'refunded') { S.op = { ...S.op, ...o }; return go('G06.REFUNDED'); } }
    catch (e) { /* the message stays */ }
  }
}

async function reconcileRun() {
  if (!S.run) return;
  const g = S.gen, runId = S.run.runId;
  try { const run = await api.run(runId); if (g === S.gen && S.run && S.run.runId === runId) applyRun(run); }
  catch (e) { if (g === S.gen && (!(e instanceof ApiError) || !e.network)) handleError(e); }
}

/** The visible offer window is over and nothing was bought: continue disappears; the server ends the offer at its arrival deadline. */
function offerTimeUp() {
  if (S.ui.offerOver || S.view !== 'G05.OFFER') return;
  S.ui.offerOver = true; render();
  const g = S.gen, until = S.offer ? clock.ms(S.offer.acceptDeadline || S.offer.deadline) + 1500 : 0;
  (async () => {
    while (g === S.gen && S.view === 'G05.OFFER' && S.ui.offerOver) {
      await sleep(clock.serverNow() < until ? Math.max(300, until - clock.serverNow()) : 1500);
      if (g !== S.gen || S.view !== 'G05.OFFER') return;
      await reconcileRun();
    }
  })();
}

/**
 * «صفحه اصلی» and back on the loss screen (§11, §12): the run ends once — an open offer is declined with its own key —
 * and the home opens without any old decision modal. Locked while a payment or the offer activation is in flight.
 */
async function lossHome() {
  if (['G05.LOADING', 'G06.PENDING', 'G06.UNKNOWN'].includes(S.view)) return;
  const r = S.run;
  if (r && r.state === 'OFFER_OPEN' && S.offer && !S.op) {
    const offerId = S.offer.offerId, key = S.keys['skip:' + offerId] ||= newKey();
    try { await api.skip(offerId, key); } catch (e) { if (e instanceof ApiError && (e.network || e.status >= 500)) { toast('اتصال برقرار نشد؛ دوباره امتحان کن.', true); return render(); } }
  }
  boot();
}

function onBack() {
  if (!S.run) return false;
  if (S.ui.cp) return true;                                   // the celebration closes with its own button only
  if (S.op || S.view === 'G03.ANSWER_PENDING') return true;   // back is blocked while an answer or a payment is in flight
  if (isLoss(S.view)) { lossHome(); return true; }
  if (inRun(S.view)) { S.ui.leave = true; render(); return true; }
  return false;
}

export async function leaveConfirmed() {
  if (!S.run) return;
  S.ui.leave = false;
  const key = S.keys['leave:' + S.run.runId] ||= newKey();
  try { await api.leave(S.run.runId, key); boot(); } catch (e) { handleError(e); }   // leaving is not losing: straight home
}

// ------------------------------------------------------------------------------------------------ checkpoint (G04)
/**
 * Mounted ONCE per (run, checkpoint) into its own host that render() never touches, so a re-render, a replayed answer or
 * a recovered run cannot replay it. The reward was registered by the server with the crossing answer; the only control
 * is «ادامه بده» (no countdown, no automatic continue, no second «دریافت»).
 */
let cpToken = 0, cpTimers = [];
function openCp(c, reward) {
  const k = S.run.runId + ':' + c.checkpointId;
  if (S.ui.cpShown.has(k)) return;
  S.ui.cpShown.add(k);
  const token = ++cpToken;
  S.ui.cp = c; S.ui.cpDue = null; S.ui.cpAt = now();
  haptic('checkpoint');
  cpEl.innerHTML = V.cpOverlay(c, cpIndex(c), reward);
  screenEl.inert = true;
  root.dataset.view = stateId();
  cpTimers.push(later(DUR.cpGuardMs, () => { if (cpToken !== token) return; const b = $('.q-cp [data-act="cp-go"]', cpEl); if (b) b.focus({ preventScroll: true }); }));
}
function unmountCp() {
  cpToken++;
  cpTimers.forEach(clearTimeout); cpTimers = [];
  S.ui.cp = null;
  if (cpEl) cpEl.innerHTML = '';
  if (screenEl) screenEl.inert = false;
}
export function closeCp() {
  if (!S.ui.cp) return;
  if (now() < S.ui.cpAt + DUR.cpGuardMs) return;               // the earning tap or a double tap cannot dismiss it
  unmountCp();
  S.ui.guardUntil = now() + DUR.afterCpGuardMs;
  render();
  showRecordToast();
}

// ------------------------------------------------------------------------------------------------ timers
let raf = 0, quotaTry = { at: null, n: 0, next: 0 };
/**
 * The next chance's time was reached on the home: ask the server; the start only becomes available when it confirms
 * (a local zero is never permission to play). Retried every 2 s, at most 5 times per reset instant.
 */
async function quotaReset() {
  if (!S.boot) return;
  const at = S.boot.quota.resetsAt;
  if (quotaTry.at !== at) quotaTry = { at, n: 0, next: 0 };
  if (quotaTry.n >= 5 || now() < quotaTry.next) return;
  quotaTry.n++; quotaTry.next = now() + 2000;
  if (S.view === 'G01.QUOTA_EXHAUSTED') go('G01.QUOTA_CHECKING');
  const g = S.gen;
  try {
    const b = await api.bootstrap();
    if (g !== S.gen || !S.view.startsWith('G01.')) return;
    S.boot = b; S.balance = b.balance; routeHome();
  } catch (e) { /* stays «در حال بررسی شانس بعدی...»; tried again */ }
}
function tickLoop() {
  if (raf) return;
  const step = () => {
    raf = 0;
    const v = S.view;
    if ((v === 'G03.QUESTION_ACTIVE' || v === 'G03.ANSWER_PENDING') && S.q) {
      const q = S.q, n = timerNow();
      if (!q.pick && !q.locked) {
        const tm = $('[data-timer]', root);
        if (tm) {
          tm.style.setProperty('--p', n.p);
          if (tm.classList.contains('is-low') !== n.low) { tm.classList.toggle('is-low', n.low); tm.classList.toggle('is-run', !n.low); const l = tm.querySelector('.q-timer__l'); if (l) l.textContent = n.low ? 'وقت داره تموم می‌شه' : 'ثانیه وقت داری'; }
          const b = tm.querySelector('.q-timer__n'); if (b && b.textContent !== n.t) { b.textContent = n.t; tm.setAttribute('aria-label', `${n.t} ثانیه`); }
        }
        // two short warnings at 3 s and 1 s, only through the host's haptics (§7); the visual emphasis works without them
        if (n.rem <= 3000 && q.buzz < 1) { q.buzz = 1; haptic('warning'); }
        if (n.rem <= 1000 && q.buzz < 2) { q.buzz = 2; haptic('warning'); }
        if (n.rem <= 0) lockQuestion(q);
      }
      if (root.dataset.view !== stateId()) root.dataset.view = stateId();
      raf = requestAnimationFrame(step); return;
    }
    if (['G05.OFFER', 'G06.FAILED_RETRY'].includes(v) && S.offer) {
      const rem = Math.max(0, clock.ms(S.offer.deadline) - clock.serverNow()), el = $('[data-offer-left]', root);
      if (el) { const s = fa(Math.ceil(rem / 1000)); if (el.textContent !== s) el.textContent = s; }
      if (rem <= 0) {
        if (v === 'G05.OFFER') offerTimeUp();
        else if (!S.ui.offerEnded) { S.ui.offerEnded = true; go('G06.FAILED_END'); later(300, () => { S.ui.offerEnded = false; reconcileRun(); }); }
      }
      raf = requestAnimationFrame(step); return;
    }
    const cd = $('[data-countdown]', root);
    if (cd && S.boot) {
      const rem = clock.ms(S.boot.quota.resetsAt) - clock.serverNow(), s = untilInstant(rem);
      if (cd.textContent !== s) cd.textContent = s;
      if (rem <= 0) quotaReset();
      raf = requestAnimationFrame(step);
    }
  };
  raf = requestAnimationFrame(step);
}

// ------------------------------------------------------------------------------------------------ run end
/**
 * A finished run: a miss shows the loss screen (once, without a continue when none is possible); leaving goes home;
 * the other endings have their own screens. The result is read quietly for the all-time record and a campaign code.
 */
function finished(run, view = 'G05.LOSS') {
  S.gen++;
  S.run = run; S.q = null; S.offer = null; S.op = null;
  const ui = { lossReason: S.ui.lossReason, noOffer: S.ui.noOffer };
  resetRunUi(); Object.assign(S.ui, ui);
  S.ended = endedFrom(run);
  host.setBackHandler(null);
  const r = run.finishReason;
  if (r === 'inactive') return go('G10.INACTIVITY_END');
  if (r === 'replaced') return go('G11.OTHER_DEVICE');
  if (r === 'blocked') return go('G11.BLOCKED');
  if (r === 'game_off') return go('G11.GAME_OFF');
  if (r === 'left') return boot();
  if (r === 'no_products') return go('G03.NO_PRODUCTS_LEFT');
  if (!S.ui.lossReason && run.lastResult) S.ui.lossReason = run.lastResult.result === 'timeout' ? 'timeout' : 'wrong';
  host.setBackHandler(() => { if (isLoss(S.view)) { lossHome(); return true; } return false; });
  go(view);
  loadResult(run.runId);
}
async function loadResult(runId, polls = 0) {
  const g = S.gen;
  for (let i = 0; i < 3; i++) {
    try {
      const r = await api.result(runId); if (g !== S.gen) return; S.result = r; if (S.boot) S.boot.quota = r.quota; if (r.balance != null) S.balance = r.balance;
      // a campaign code is issued shortly after the run ends: read again (every 2 s, at most 10 times) until it is no longer pending
      if (r.campaignCode && r.campaignCode.status === 'pending' && polls < 10) later(2000, () => loadResult(runId, polls + 1));
      return render();
    }
    catch (e) { if (!(e instanceof ApiError) || !(e.network || e.status >= 500)) return; await sleep(600 * (i + 1)); }
  }
}

export async function openProduct(link) {
  const ok = await host.openProduct(link);
  if (!ok) { toast('صفحه کالا باز نشد. دوباره امتحان کن.', true, 'G01.LINK_FAILED'); render(); }
}

export async function copyCode() {
  const R = S.result; if (!R || !R.campaignCode || !R.campaignCode.code) return;
  const ok = await host.copy(R.campaignCode.code);
  if (S.result !== R) return;
  if (!ok) { S.ui.codeState = null; toast('کپی نشد. کد رو نگه دار و دستی کپی کن.', true, 'G05.CAMPAIGN_CODE.COPY_FAILED'); return render(); }
  S.ui.codeState = 'copied'; render();
  setTimeout(() => { if (S.result && S.result.runId === R.runId && S.ui.codeState === 'copied') { S.ui.codeState = null; render(); } }, DUR.copiedMs);
}

// ------------------------------------------------------------------------------------------------ connectivity & lifecycle
function setOffline(off) {
  S.ui.offline = off;
  if (off) return render();
  if (S.run && inRun(S.view)) {
    S.ui.resuming = true; render();
    const g = S.gen;
    api.run(S.run.runId).then(run => { if (g !== S.gen) return; S.ui.resuming = false; applyRun(run); })
      .catch(e => { if (g !== S.gen) return; S.ui.resuming = false; render(); if (!(e instanceof ApiError) || !e.network) handleError(e); });
  } else if (S.view.startsWith('G01.')) boot();               // stale home: refresh before a start is allowed
  else render();
}

export function init(el) {
  root = el;
  root.innerHTML = '<div class="q-screenhost"></div><div class="q-cphost"></div>';
  screenEl = root.firstElementChild; cpEl = root.lastElementChild;
  window.addEventListener('online', () => setOffline(false));
  window.addEventListener('offline', () => setOffline(true));
  document.addEventListener('visibilitychange', () => {       // backgrounding is not leaving; revalidate on return
    if (document.visibilityState !== 'visible') return;
    clock.resetAfterSleep();
    if (['G01.READY', 'G01.QUOTA_EXHAUSTED', 'G01.QUOTA_CHECKING'].includes(S.view) && !S.ui.daily) return boot();   // chances re-synced with the server
    if (S.run && inRun(S.view) && !S.ui.cp && !['G03.CORRECT_FEEDBACK', 'G03.WRONG_FEEDBACK', 'G03.TIMEOUT'].includes(S.view)) reconcileRun();
  });
  root.addEventListener('click', onClick);
  boot();
}

function onClick(e) {
  const el = e.target.closest('[data-act]'); if (!el || el.disabled || el.getAttribute('aria-disabled') === 'true') return;
  const a = el.dataset.act;
  const acts = {
    boot, home: boot, start: startRun, reveal, continue: continueOffer, 'ans-higher': () => answer('higher'), 'ans-lower': () => answer('lower'),
    guide: openGuide, 'guide-close': closeGuide,
    'onb-next': () => { const o = S.ui.onb; if (o && !o.busy) { o.page = 2; o.error = null; go('G02.ONBOARDING'); } },
    'onb-prev': () => { const o = S.ui.onb; if (o && !o.busy) { o.page = 1; o.error = null; go('G02.ONBOARDING'); } },
    'onb-skip': finishOnboarding, 'onb-done': finishOnboarding,
    'spot-next': () => { const o = S.ui.onb; if (o && o.step < SPOT.length - 1) { o.step++; render(); } },
    'spot-prev': () => { const o = S.ui.onb; if (o && o.step > 0) { o.step--; render(); } },
    'daily-claim': claimDaily, 'daily-check': checkDaily, 'daily-close': () => { S.ui.daily = null; render(); },
    resume: () => resume(S.boot.activeRun.runId),
    back: () => onBack(),
    'leave-no': () => { S.ui.leave = false; render(); },
    'leave-yes': leaveConfirmed,
    'loss-home': lossHome,
    'cp-go': closeCp,
    recheck: () => { if (S.op && S.op.operationId) api.operation(S.op.operationId).then(o => onOperation(o, true)).catch(() => {}); else if (S.op) resendPurchase(S.gen); else reconcileRun(); },
    'recheck-answer': () => { if (S.q) readSettlement(S.q, S.gen); },
    'history-retry': () => { const id = S.home.runId; S.home.runId = null; S.home.state = 'loading'; render(); loadHistory(id); },
    'open-product': () => openProduct(el.dataset.link),
    copy: copyCode,
    'exit-app': () => host.close(),
  };
  if (acts[a]) acts[a]();
}
