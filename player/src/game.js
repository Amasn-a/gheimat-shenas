// Player state controller: one authoritative store drives the DOM. Explicit view states with transition guards; run,
// question and operation identities plus a generation counter make stale responses and timers harmless. The server
// decides timing, results, money and rewards; this file only renders what the server said and sends guarded intents.
import { api, ApiError, newKey } from './api.js';
import * as clock from './clock.js';
import * as host from './host.js';
import * as motion from './motion.js';
import { DUR } from './durations.js';
import * as V from './views.js';
import { DEMO_FIRST, DEMO_SECOND, DEMO_SECOND_PRICE } from './demo.js';
import { fa, untilInstant } from './fmt.js';

const $ = (s, r = document) => r.querySelector(s);
const sleep = ms => new Promise(r => setTimeout(r, ms));

export const S = {
  view: 'G01.LOADING',
  boot: null, balance: null,
  run: null,          // last run view from the server
  ref: null,          // first product card
  q: null,            // open question { questionId, product, t0, inputDeadline, acceptDeadline, imgReady, imgSlow, pick, key, locked }
  fb: null,           // feedback { kind, choice, direction, revealedPrice, product, equal }
  offer: null, op: null,
  result: null, products: null, ended: null, share: null,
  ui: { toast: null, leave: false, cp: null, offline: !navigator.onLine, resuming: false, queued: null, morph: null,
        guardUntil: 0, shareBusy: false, codeState: null, afterContinue: false, noOffer: null, cpShown: new Set(),
        tour: null, daily: null, historyState: 'loading', histPositioned: null, histScroll: null, slowSettle: false, offerOver: false },
  keys: {},           // one Idempotency-Key per intent, reused for its retries
  gen: 0,
};

let root = null, screenEl = null, cpEl = null;
const now = () => performance.now();
function later(ms, fn) { const g = S.gen; return setTimeout(() => { if (g === S.gen) fn(); }, ms); }
/**
 * Per-run transient UI. Cleared in one place whenever a run ends, is replaced, or a new one starts or boots, so no
 * flag outlives its run (a checkpoint due at a leave once blocked every reveal of the next run).
 */
function resetRunUi() {
  Object.assign(S.ui, { leave: false, cpDue: null, queued: null, resuming: false, afterContinue: false, noOffer: null, guardUntil: 0,
    offerEnded: false, offerOver: false, shareBusy: false, slowSettle: false, cpShown: new Set() });
  unmountCp();                                                    // an open or pending checkpoint of the old run can no longer close or open
}
const cfg = () => (S.run && S.run.runConfig) || (S.boot && S.boot.config) || { checkpoints: [], continueRule: { enabled: false, cost: 0, cap: 0 }, inputMs: 10000, acceptMs: 12500, offerMs: 10000, offerAcceptMs: 15000, checkpointAutoContinueMs: 5000 };
const inRun = v => /^G03\.|^G04\.|^G05\.|^G06\./.test(v);
/** A01 switch of the run's (or, before a run, the active) configuration; on when an older server sends none. */
const feat = k => { const f = cfg().features; return !f || f[k] !== false; };
const haptic = kind => { if (feat('haptics')) host.haptic(kind); };

// ------------------------------------------------------------------------------------------------ rendering
function model() {
  const r = S.run, c = cfg();
  return {
    boot: S.boot, toast: S.ui.toast, config: c,
    untilReset: S.boot ? clock.ms(S.boot.quota.resetsAt) - clock.serverNow() : 0,
    result: S.result ? { ...S.result, codeState: S.ui.codeState } : null,   // carries the copy state the view renders
    products: S.products, historyState: S.ui.historyState, shareBusy: S.ui.shareBusy, ended: S.ended,
    lastScore: S.ended ? S.ended.score : 0, playerMessage: S.boot && S.boot.access ? S.boot.access.message : null,
    shareSheet: '', score: r ? r.score : 0, checkpoint: r ? r.highestCheckpoint : null, leave: S.ui.leave,
    questionOpen: ['G03.QUESTION_ACTIVE', 'G03.ANSWER_PENDING'].includes(S.view),
    modal: S.ui.daily && S.boot && S.boot.dailyReward ? V.dailyModal(S.boot.dailyReward, S.ui.daily) : '',
  };
}

function hud(plus) {
  return V.top({ score: S.run.score, plus,
    backLocked: !!S.op || S.view === 'G03.ANSWER_PENDING' || S.view === 'G06.PENDING' || S.view === 'G06.UNKNOWN' });
}
const trk = earned => V.track(S.run.score, cfg().checkpoints, earned);

/** Visible countdown of the open question: 10 → 1 over the 10,000 ms window; white, easing to orange, red in the last 3 s. */
const WHITE = [255, 255, 255], ORANGE = [255, 154, 60];
function timerNow() {
  const total = cfg().inputMs, q = S.q;
  const rem = Math.max(0, Math.min(total, clock.ms(q.inputDeadline) - clock.serverNow()));
  const low = rem <= 3000, k = Math.min(1, Math.max(0, (total - rem) / Math.max(1, total - 3000)));
  const tc = low ? '#FF5468' : `rgb(${WHITE.map((w, i) => Math.round(w + (ORANGE[i] - w) * k)).join(',')})`;
  return { rem, t: fa(Math.ceil(rem / 1000)), p: (rem / total).toFixed(3), tc, low, mode: low ? 'low' : '' };   // never 0 while input is open
}

function screen() {
  const v = S.view, m = model();
  if (v.startsWith('G01.')) return V.vStart(v, m);
  if (v === 'G02.TOUR') return tourScene();
  if (v === 'G03.LOADING') return loadingRun();
  if (['G03.READY', 'G03.PREPARING', 'G03.PREPARE_FAILED', 'G06.SUCCESS'].includes(v)) return ready(m);
  if (v === 'G03.QUESTION_ACTIVE' || v === 'G03.ANSWER_PENDING') return question(m);
  if (inRun(v)) return feedback(m);
  if (v.startsWith('G07.')) { m.shareSheet = S.share ? shareSheet() : ''; return V.vResult(m); }
  return V.vSystem(v, m);
}

function loadingRun() {
  return V.play(V.top({ score: 0 }), V.track(0, cfg().checkpoints), '<div class="v3-hero v3-hero--ref" aria-busy="true" aria-label="کالای اول در راهه"><div class="v3-img v3-skel v3-skel--card"></div><div class="v3-hero__b"><span class="v3-skel v3-skel--card" style="flex:1 1 60%;height:20px"></span><span class="v3-skel v3-skel--card" style="width:40%;height:30px"></span></div></div>' +
    V.revealRow('کالا در راهه…', '', '<span class="v3-spin" aria-hidden="true"></span>نمایش بده', ' disabled aria-disabled="true" aria-busy="true"'), V.keys('off', null, true), '', S.ui.toast);
}

function ready(m) {
  const v = S.view, off = S.ui.offline;
  const pill = S.ui.resuming ? V.stagepill('در حال اتصال دوباره…', 'refresh') : off ? V.stagepill('اتصال قطع شد. هنوز سؤالی شروع نشده؛ دستت محفوظه.', 'wifi') : '';
  if (v === 'G03.PREPARING' || v === 'G03.PREPARE_FAILED') {
    const tg = v === 'G03.PREPARING' ? V.tag('preparing', 'آماده‌سازی کالا…', 'زمان هنوز شروع نشده.') : V.tag('failed', 'کالا آماده نشد', 'امتیاز و زمانت سالمه؛ دوباره امتحان کن.', 'دوباره امتحان کن', ' data-act="reveal"');
    const a = v === 'G03.PREPARING' ? V.ask('کالای بعدی آماده می‌شه…', { mode: 'idle', muted: true, spin: true }) : V.ask('کالای بعدی آماده نشد', { mode: 'idle', muted: true });
    return V.play(hud(), trk(), pill + V.refTicket(S.ref) + a + tg, V.keys('off', null, true), V.leaveSheet(m), S.ui.toast);
  }
  const row = off ? V.revealRow('کالای بعدی آماده‌ست', '', 'در انتظار اتصال…', V.L)
    : S.ui.afterContinue ? V.revealRow('ادامه می‌دیم!', 'فرصت ادامه این دست استفاده شد.', 'نمایش بده', ' data-act="reveal"')
    : V.revealRow('کالای بعدی آماده‌ست', 'هر وقت آماده بودی ببینش؛ بعدش ۱۰ ثانیه وقت داری.', 'نمایش بده', ' data-act="reveal"');
  return V.play(hud(), trk(), pill + V.refBig(S.ref) + row, V.keys('off', null, true), V.leaveSheet(m), S.ui.toast);
}

function question(m) {
  const q = S.q, pend = !!q.pick, off = S.ui.offline, tn = timerNow(), over = !pend && (q.locked || tn.rem <= 0);
  let pill = off ? V.stagepill('اتصال قطع شد؛ تا برگرده نمی‌شه جواب داد و زمان ادامه داره.', 'wifi', true) : '';
  if (S.ui.resuming) pill = V.stagepill('در حال اتصال دوباره… زمان ادامه داره.', 'refresh');   // CP-26
  if (S.ui.slowSettle) pill = V.stagepill(pend ? 'پاسخت ثبت شده؛ نتیجه‌اش هنوز نرسیده.' : 'نتیجه هنوز نرسیده.', 'refresh', false,
    '<button class="v3-stagepill__btn" data-act="recheck-answer">بررسی دوباره</button>');
  // after a pick the countdown gives way to a processing state; at the visible zero without a pick, input locks and the server records the timeout
  const a = pend ? V.ask('در حال بررسی پاسخ…', { mode: 'pending', muted: true })
    : over ? V.ask('وقت تموم شد؛ در حال ثبت…', { mode: 'wait', muted: true, spin: true }) : V.ask(V.Q_TEXT, { ...tn, muted: off });
  return V.play(hud(), trk(), pill + V.refTicket(S.ref) + a + V.hero(q.product, 'hidden', q.imgSlow && !q.imgReady), V.keys(pend ? 'pending' : off || over ? 'off' : 'on', q.pick, false, S.ref.price), V.leaveSheet(m), S.ui.toast);
}

function fbParts() {
  const fb = S.fb, dir = V.dirOf(fb.direction), truthW = V.DIRW[dir];
  if (fb.kind === 'correct') {
    return [hud(true), trk(true), V.refTicket(S.ref) + V.verdict('ok', 'درست گفتی!', fb.equal ? 'قیمت‌ها برابر بود؛ هر دو جواب درسته' : `${truthW} بود`) +
      V.hero(fb.product, 'correct', false, dir, fb.revealedPrice), V.keys('ok', fb.choice), false];
  }
  if (fb.kind === 'wrong') return [hud(), trk(), V.refTicket(S.ref) + V.verdict('no', 'اشتباه بود', `${truthW} بود`) + V.hero(fb.product, 'wrong', false, dir, fb.revealedPrice), V.keys('no', fb.choice), true];
  const truthSide = fb.direction === 'higher' ? 'higher' : 'lower';   // timeout: no answer is shown as the player's
  return [hud(), trk(), V.refTicket(S.ref) + V.verdict('time', 'وقت تموم شد', `${truthW} بود`) + V.hero(fb.product, 'timeout', false, dir, fb.revealedPrice), V.keys('truth', truthSide), true];
}

const SPIN_ICON = '<span class="v3-sheet__icon v3-sheet__icon--busy" aria-hidden="true"><span class="v3-spin"></span></span>';
function feedback(m) {
  const v = S.view, c = cfg();
  if (v === 'G03.NO_PRODUCTS_LEFT') return V.play(hud(), trk(), V.refTicket(S.ref) + V.ask('دست کامل شد', { mode: 'off', muted: true }) + '<div class="v3-hero is-empty"></div>', V.keys('off'),
    V.sheet('کالاها تموم شد!', `کالای مناسب دیگه‌ای برای این دست نمونده. دستت با ${fa(S.run.score)} پاسخ درست ثبت شد.`, '', V.GOLD('دیدن نتیجه', ' data-act="see-result"', false), '', ['ok', 'check']), S.ui.toast);
  if (!S.fb) return ready(m);
  const [t, tr, mm, d, dim] = fbParts();
  const bal = S.balance == null ? '…' : fa(S.balance), C = fa(S.offer ? S.offer.cost : c.continueRule.cost);
  const cap = c.continueRule.cap, left = Math.max(0, cap - (S.run.continuesUsed || 0));   // CP-23 from the run's pinned rule
  const ONCE = `<p class="v3-once">${cap === 1 ? 'فقط یک بار در هر دست.' : `${fa(left)} فرصت از ${fa(cap)} فرصت این دست باقی مونده.`}</p>`;
  const ringHTML = () => S.offer ? V.ring(Math.max(0, clock.ms(S.offer.deadline) - clock.serverNow()), c.offerMs) : '';
  const txt = () => V.offerText(S.fb.revealedPrice, S.run.score, S.run.highestCheckpoint);
  let sh = '';
  if (v === 'G05.OFFER' && S.ui.offerOver)                                   // visible window over, no purchase sent
    sh = V.sheet('زمان پیشنهاد تموم شد', txt(), V.cost(bal, S.offer.cost), V.BTNS(V.BTN('v3-btn--block v3-btn--xl', V.COIN + `ادامه با ${C} دیجی‌کوین`, V.L), V.SKIP(' data-act="skip"', 'دیدن نتیجه')),
      V.status('', 'در حال ثبت پایان دست…', null, true), null, ringHTML());
  else if (v === 'G05.OFFER') sh = V.sheet('یه فرصت دیگه؟', txt(), V.cost(bal, S.offer.cost) + ONCE, V.BTNS(V.GOLD(V.COIN + `ادامه با ${C} دیجی‌کوین`, ' data-act="continue"', false), V.SKIP()), '', null, ringHTML());
  else if (v === 'G06.PENDING') sh = V.sheet('یه فرصت دیگه؟', txt(), V.cost(bal, S.offer.cost), V.BTNS(V.BUSY(`ادامه با ${C} دیجی‌کوین`, false), V.SKIP(V.L)),
    V.status('', 'در حال بررسی پرداخت… تا مشخص شدن نتیجه، خروج غیرفعاله.', null, true), null, SPIN_ICON);
  else if (v === 'G06.FAILED_RETRY') sh = V.sheet('یه فرصت دیگه؟', txt(), V.cost(bal, S.offer.cost) + ONCE, V.BTNS(V.GOLD(V.COIN + `دوباره: ادامه با ${C} دیجی‌کوین`, ' data-act="continue"', false), V.SKIP()),
    V.status('no', 'پرداخت انجام نشد و کوینی کسر نشد. هنوز فرصت داری دوباره امتحان کنی.', 'alert', false, true), null, ringHTML());
  else if (v === 'G06.FAILED_END') sh = V.sheet('ادامه ممکن نشد', `پرداخت انجام نشد و کوینی کسر نشد. این دست با ${fa(S.run.score)} پاسخ درست ثبت شد.`, '',
    V.GOLD('دیدن نتیجه', ' data-act="see-result"', false), '', ['no', 'alert']);
  else if (v === 'G06.REFUNDED') sh = V.sheet('ادامه ممکن نشد', `این دست با ${fa(S.run.score)} پاسخ درست تموم شد${S.run.highestCheckpoint ? ` و پاداش چک‌پوینت ${fa(S.run.highestCheckpoint.requiredCorrect)} برات ثبت می‌شه` : ''}.`, '',
    V.GOLD('دیدن نتیجه', ' data-act="see-result"', false), V.status('ok', `${C} دیجی‌کوین به حسابت برگشت.`, 'check'), ['ok', 'refresh']);
  else if (v === 'G06.UNKNOWN') sh = V.sheet('نتیجه پرداخت هنوز مشخص نیست', 'درخواستت به‌موقع رسیده و داریم نتیجه‌اش رو می‌گیریم. هیچ مبلغی دوباره کسر نمی‌شه؛ اگه ادامه ممکن نشه، هزینه برمی‌گرده.', '',
    V.BTN('v3-btn--outline v3-btn--block', 'بررسی دوباره', ' data-act="recheck"'), V.status('warn', 'تا مشخص شدن نتیجه، خروج از دست غیرفعاله.', null, true), null, SPIN_ICON);
  else if (v === 'G06.FAILED_END.UNCONFIRMED') {
    // CP-20 is NOT approved: draft wording that does not claim the debit never happened (contract §6, §19).
    const refund = S.op && S.op.refundStatus === 'succeeded' ? V.status('ok', 'هزینه داره به حسابت برمی‌گرده.', 'check') : '';
    sh = V.sheet('ادامه ممکن نشد', `پرداخت به‌موقع تأیید نشد و ادامه انجام نشد؛ اگه مبلغی کسر شده باشه، خودکار برمی‌گرده. این دست با ${fa(S.run.score)} پاسخ درست ثبت شد.`, refund,
      V.GOLD('دیدن نتیجه', ' data-act="see-result"', false), '', ['no', 'alert']);
  }
  const miss = ['G03.WRONG_FEEDBACK', 'G03.TIMEOUT', 'G05.NO_FUNDS'].includes(v);
  const why = miss && S.ui.noOffer === 'limit_used' ? V.stagepill('فرصت ادامه این دست رو قبلاً استفاده کردی.', 'info')   // CP-24
    : miss && S.ui.noOffer === 'no_funds' ? V.stagepill('برای ادامه دیجی‌کوین کافی نداشتی.', 'info') : '';             // CP-32
  return V.play(t, tr, (S.ui.resuming ? V.stagepill('در حال اتصال دوباره… زمان ادامه داره.', 'refresh') : '') + why + mm, d, sh + V.leaveSheet(m), S.ui.toast, dim);
}

function shareSheet() {
  return `<div class="pe-host v3-sheetwrap" role="dialog" aria-modal="true" aria-label="اشتراک‌گذاری"><section class="v3-sheet"><span class="v3-grip" aria-hidden="true"></span><div class="pe-share-frame" style="width:180px;margin:0 auto;aspect-ratio:9/16;overflow:hidden;border-radius:14px">${V.shareAsset(S.share)}</div>` +
    `<div class="v3-sheet__btns">${V.GOLD(V.ICO('share') + 'اشتراک‌گذاری تصویر', ' data-act="share-send"', false)}${V.BTN('v3-btn--outline v3-btn--block', 'بستن', ' data-act="share-close"')}</div></section></div>`;
}

export function render() {
  if (!root) return;
  // access/error screens (G11) replace the game at once: no ghosts of an interrupted card move behind their message
  const ms = S.view.startsWith('G11.') ? 0 : S.ui.morph != null ? S.ui.morph : motion.running() ? Math.max(160, motion.remaining()) : 0;
  S.ui.morph = null;
  const before = ms && !motion.reducedMotion() ? motion.snapshot(root) : null;
  const hs = root.querySelector('[data-hscroll]');
  if (hs && S.ui.histPositioned) S.ui.histScroll = hs.scrollLeft;   // a re-render never resets the player's own scrolling
  motion.end();
  screenEl.innerHTML = screen();
  root.dataset.view = stateId();
  wireImages();
  if (before) motion.morph(root, before, ms, () => { const q = S.ui.queued; S.ui.queued = null; if (q === 'reveal') setTimeout(reveal, 0); });
  if (root.querySelector('[data-countdown]')) tickLoop();       // any screen with a quota countdown keeps it ticking
  if (S.view === 'G02.TOUR') afterTourRender();
  if (S.view.startsWith('G07.')) afterResultRender();
}

/** Fine-grained state id (as in the handoff state index) for analytics, tests and reviewers. */
export function stateId() {
  const v = S.view;
  if (S.ui.cp && (v === 'G03.READY' || v === 'G06.SUCCESS')) return 'G04.REACHED';
  if (S.ui.resuming && inRun(v)) return 'G10.RESUMING';
  if (v === 'G02.TOUR' && S.ui.tour) return `G02.${S.ui.tour.mode === 'first' ? 'ONBOARDING' : 'GUIDE'}.${TOUR[S.ui.tour.i].id.toUpperCase()}`;
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
  if (v === 'G01.READY' && S.boot) {
    const b = S.boot;
    if (b.activeRun && !b.activeRun.onThisDevice) return 'G01.READY.OTHER_DEVICE_RUN';
    if (b.campaign) return 'G01.READY.CAMPAIGN';
    if (!b.record) return 'G01.READY.NEW_PLAYER';
  }
  if (v.startsWith('G07.')) return S.share ? 'G09.ASSET' : S.ui.shareBusy ? 'G09.GENERATING' : S.ui.toast && S.ui.toast.id ? S.ui.toast.id : V.resultId(S.result ? { ...S.result, codeState: S.ui.codeState } : null);
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
function toast(text, no, id, page) {
  S.ui.toast = { text, no, id, page, at: now() };
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
  if (e.network) { S.ui.offline = !navigator.onLine || S.ui.offline; return context === 'boot' ? go('G01.ERROR') : render(); }
  if (e.status >= 500) return go('G11.SERVICE_ERROR');
  console.warn('unhandled', e.code); return go('G11.SERVICE_ERROR');
}
const endedFrom = st => st ? { score: st.score, checkpoint: st.highestCheckpoint, runId: st.runId } : (S.run ? { score: S.run.score, checkpoint: S.run.highestCheckpoint, runId: S.run.runId } : null);

// ------------------------------------------------------------------------------------------------ boot / entry order
/**
 * Entry order (brief 2026-10-20), never stacked: 1) the mandatory onboarding while the account has not completed it,
 * 2) the daily reward modal when an earned reward waits for its claim, 3) the start screen. A run in progress on this
 * device comes before all of them (its deadlines keep running).
 */
export async function boot() {
  S.gen++; motion.end();
  Object.assign(S, { run: null, ref: null, q: null, fb: null, offer: null, op: null, result: null, products: null, share: null, keys: {} });
  resetRunUi();
  Object.assign(S.ui, { toast: null, shareBusy: false, codeState: null, tour: null, daily: null, histPositioned: null, histScroll: null });
  host.setBackHandler(null);
  go('G01.LOADING');
  try {
    S.boot = await api.bootstrap();
  } catch (e) { return handleError(e, 'boot'); }
  const b = S.boot; S.balance = b.balance;
  if (b.access.status === 'blocked') return go('G11.BLOCKED');
  if (b.access.status === 'game_off') return go('G11.GAME_OFF');
  if (b.activeRun && b.activeRun.onThisDevice) {
    if (b.activeRun.state === 'PAYMENT_PENDING') { go('G01.OPERATION_PENDING'); return pollPendingAtStart(b.activeRun); }
    return go('G01.ACTIVE_RUN');
  }
  if (b.firstVisit) return startTour('first');
  toStart();
}

/** Start screen, with the daily reward modal on top when a reward is claimable (or a claim is still being confirmed). */
function toStart() {
  const d = S.boot.dailyReward;
  if (d && (d.state === 'claimable' || d.state === 'processing') && !(S.boot.activeRun && S.boot.activeRun.onThisDevice)) {
    S.ui.daily = { phase: d.state === 'processing' ? 'processing' : 'claimable' };
    if (d.state === 'processing') pollDaily();
  }
  go(S.boot.quota.playsLeft > 0 ? 'G01.READY' : 'G01.QUOTA_EXHAUSTED');
  if (S.ui.daily) focusModal();
}
function focusModal() { requestAnimationFrame(() => { const b = root.querySelector('.pe-daily .v3-btn:not([disabled])'); if (b) b.focus({ preventScroll: true }); }); }

async function pollPendingAtStart(active) {   // 1 s backing off to 2 s while the server still waits for the payment
  const g = S.gen; let delay = 1000;
  while (g === S.gen) {
    await sleep(delay); delay = Math.min(2000, delay + 500);
    if (g !== S.gen) return;
    try {
      const run = await api.run(active.runId);
      if (run.state !== 'PAYMENT_PENDING') { S.boot.activeRun = run.state === 'FINISHED' ? null : { ...active, state: run.state }; return run.state === 'FINISHED' ? finished(run) : go('G01.ACTIVE_RUN'); }
    } catch (e) { if (!(e instanceof ApiError) || !e.network) return handleError(e); }
  }
}

// ------------------------------------------------------------------------------------------------ daily reward (G01.DAILY_REWARD)
async function claimDaily() {
  const d = S.ui.daily; if (!d || ['claiming', 'processing', 'claimed'].includes(d.phase)) return;
  d.phase = 'claiming'; render();                                   // locked at once: one claim per tap, however fast
  const key = S.keys.daily ||= newKey(), g = S.gen;
  try {
    const v = await api.claimDaily(key);
    if (g !== S.gen) return;
    S.keys.daily = null;
    applyDaily(v);
  } catch (e) {
    if (g !== S.gen) return;
    if (e instanceof ApiError && e.network) { Object.assign(S.ui.daily, { phase: 'error', message: null }); return render(); }   // same key on retry
    S.keys.daily = null;
    if (e instanceof ApiError && (e.code === 'PLAYER_BLOCKED' || e.status === 401)) { S.ui.daily = null; return handleError(e); }
    Object.assign(S.ui.daily, { phase: 'error', message: 'جایزه الان ثبت نشد؛ جایزه‌ات محفوظه. دوباره امتحان کن.' }); render();
  }
}
function applyDaily(v) {
  if (!S.ui.daily || !S.boot) return;
  S.boot.dailyReward = v;
  const phase = { claimed: 'claimed', processing: 'processing', delayed: 'delayed', claimable: 'claimable' }[v.state];
  if (!phase) { S.ui.daily = null; return render(); }               // nothing to claim any more (claimed elsewhere long ago)
  S.ui.daily.phase = phase; render(); focusModal();
  if (phase === 'processing') pollDaily();
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

// ------------------------------------------------------------------------------------------------ G02 onboarding and guide
/**
 * One spotlight lesson on a demo scene built from the real gameplay components, used twice: as the mandatory
 * onboarding (cannot be skipped in one action; completion is stored on the account only after the last step) and as the
 * guide reopened from the start screen (closable). Nothing in it calls a game endpoint, spends a play or a coin, or
 * starts a real deadline: its buttons carry tour-* actions only.
 */
const TOUR = [
  { id: 'first', phase: 'ready', target: 'first', title: 'کالای اول', text: 'بازی با یه کالا و قیمتش شروع می‌شه.' },
  { id: 'reveal', phase: 'ready', target: 'reveal', title: '«نمایش بده»', text: 'هر وقت آماده بودی، بزنش تا کالای دوم بیاد.', interact: true, hint: 'دکمه‌ی «نمایش بده» رو بزن.' },
  { id: 'second', phase: 'question', target: 'second', title: 'کالای دوم', text: 'قیمت این یکی مخفیه؛ باید حدسش بزنی.' },
  { id: 'timer', phase: 'question', target: 'timer', title: '۱۰ ثانیه وقت', text: 'از لحظه‌ای که کالای دوم میاد، ۱۰ ثانیه وقت داری.' },
  { id: 'answer', phase: 'question', target: 'answers', title: 'گرون‌تر یا ارزون‌تر؟', text: 'کالای دوم از اولی گرون‌تره یا ارزون‌تر؟', interact: true, hint: 'یکی از دو دکمه رو بزن.' },
  { id: 'progress', phase: 'answered', target: 'hud,progress', title: 'شمارنده و پرچم‌ها', text: 'هر جواب درست یکی به شمارنده اضافه می‌کنه. به هر پرچم برسی، پاداش اون چک‌پوینت مال توئه.' },
  { id: 'done', phase: 'answered', target: null, title: 'آماده‌ای؟', text: '' },
];

function startTour(mode) {
  S.ui.daily = null;
  S.ui.tour = { mode, i: 0, pick: null, busy: false, error: null };
  host.setBackHandler(() => {                                     // device back: one step back; never skips the onboarding
    const t = S.ui.tour; if (!t) return false;
    if (t.mode === 'guide') { closeTour(); return true; }
    if (t.i > 0) tourStep(t.i - 1);
    return true;
  });
  go('G02.TOUR');
}
function tourStep(i) {
  const t = S.ui.tour; if (!t) return;
  const from = TOUR[t.i].phase, to = TOUR[i].phase;
  if (to !== 'answered') t.pick = null;
  t.i = i; t.error = null;
  S.ui.morph = from === 'ready' && to === 'question' ? DUR.shrinkMs : from !== to ? DUR.verdictMs : 0;
  go('G02.TOUR');
}
function closeTour() { S.ui.tour = null; host.setBackHandler(null); toStart(); }
async function finishTour() {
  const t = S.ui.tour; if (!t || t.busy) return;
  if (t.mode === 'guide') return closeTour();
  t.busy = true; t.error = null; render();
  const key = S.keys.onboarding ||= newKey(), g = S.gen;
  for (let i = 0; i < 2; i++) {
    try {
      await api.onboarding(key);
      if (g !== S.gen) return;
      S.keys.onboarding = null; S.boot.firstVisit = false;
      return closeTour();                                         // stored on the account: next: daily reward if earned, then start
    } catch (e) {
      if (g !== S.gen) return;
      if (!(e instanceof ApiError) || !e.network || i === 1) break;
      await sleep(800);
    }
  }
  Object.assign(t, { busy: false, error: 'ذخیره نشد؛ اتصال رو بررسی کن و دوباره بزن.' }); render();
}
function tourAnswer(choice) { const t = S.ui.tour; if (!t || TOUR[t.i].id !== 'answer') return; t.pick = choice; tourStep(t.i + 1); }

function tourScene() {
  const t = S.ui.tour, st = TOUR[t.i], ph = st.phase, c = cfg(), ok = t.pick === 'higher';
  const score = ph === 'answered' && ok ? 1 : 0;
  const top = V.top({ score, plus: ph === 'answered' && ok, backLocked: true });
  let main, dock;
  if (ph === 'ready') {
    main = V.refBig(DEMO_FIRST) + V.revealRow('کالای بعدی آماده‌ست', 'هر وقت آماده بودی ببینش؛ بعدش ۱۰ ثانیه وقت داری.', 'نمایش بده', st.id === 'reveal' ? ' data-act="tour-reveal"' : V.L);
    dock = V.keys('off', null, true);
  } else if (ph === 'question') {
    main = V.refTicket(DEMO_FIRST) + V.ask(V.Q_TEXT, { t: fa(10), p: '1.000', tc: '#FFFFFF' }) + V.hero(DEMO_SECOND, 'hidden');
    dock = V.keys(st.id === 'answer' ? 'on' : 'off', null, false, DEMO_FIRST.price).replace(/data-act="ans-/g, 'data-act="tour-ans-');
  } else {
    main = V.refTicket(DEMO_FIRST) + V.verdict(ok ? 'ok' : 'no', ok ? 'درست گفتی!' : 'اشتباه بود', 'گرون‌تر بود') + V.hero(DEMO_SECOND, ok ? 'correct' : 'wrong', false, 'up', DEMO_SECOND_PRICE);
    dock = V.keys(ok ? 'ok' : 'no', t.pick);
  }
  const cr = c.continueRule, plays = S.boot ? S.boot.quota.playsPerDay : 3;
  const doneText = `هر روز ${fa(plays)} دست داری.${cr.enabled && cr.cap > 0 ? ` اگه اشتباه کنی و دیجی‌کوین کافی داشته باشی، در هر دست ${cr.cap === 1 ? 'یک بار' : `تا ${fa(cr.cap)} بار`} می‌تونی با ${fa(cr.cost)} دیجی‌کوین ادامه بدی.` : ''}`;   // CP-29, numbers from config
  const final = st.id === 'done';
  const ov = V.tourOverlay({
    id: st.id, index: t.i, total: TOUR.length, title: st.title, text: final ? doneText : st.text, target: st.target, interact: !!st.interact,
    hint: st.interact ? st.hint : null, closable: t.mode === 'guide', final, busy: t.busy, error: t.error,
    action: st.interact ? null : final ? 'tour-finish' : 'tour-next', actionLabel: final ? (t.mode === 'first' ? 'شروع کن' : 'فهمیدم') : 'بعدی',
  });
  return V.play(top, V.track(score, c.checkpoints, ph === 'answered' && ok), main, dock, ov, null);
}

let tourListeners = false;
function afterTourRender() {
  positionTour();
  requestAnimationFrame(positionTour);                             // again once fonts and the card move have laid out
  if (!tourListeners) {
    tourListeners = true;
    const again = () => { if (S.view === 'G02.TOUR') requestAnimationFrame(positionTour); };
    window.addEventListener('resize', again);
    window.addEventListener('orientationchange', again);
    if (window.visualViewport) window.visualViewport.addEventListener('resize', again);
    document.addEventListener('scroll', again, true);              // any scrolled container moves the target
    if (window.ResizeObserver) new ResizeObserver(again).observe(root);
  }
  const st = TOUR[S.ui.tour.i];
  requestAnimationFrame(() => {                                    // keyboard and screen readers land on the lesson
    const target = st.interact ? root.querySelector(`[data-tour="${st.target}"] button:not([disabled]), button[data-tour="${st.target}"]`) : null;
    const b = target || root.querySelector('.pe-tour__tip .v3-btn:not([disabled])') || root.querySelector('.pe-tour__tip');
    if (b && b.focus) b.focus({ preventScroll: true });
  });
}
/** Places the hole, the four blockers and the tip around the target, inside the safe area, after layout. */
function positionTour() {
  const ov = root.querySelector('.pe-tour'); if (!ov || !S.ui.tour) return;
  const st = TOUR[S.ui.tour.i], R = root.getBoundingClientRect(), W = R.width, H = R.height;
  const stageEl = root.querySelector('.v3-stage'), cs = stageEl ? getComputedStyle(stageEl) : null;
  const safeT = cs ? parseFloat(cs.paddingTop) || 0 : 0, safeB = cs ? parseFloat(cs.paddingBottom) || 0 : 0;
  const set = (el, x, y, w, h) => { if (el) Object.assign(el.style, { left: x + 'px', top: y + 'px', width: Math.max(0, w) + 'px', height: Math.max(0, h) + 'px' }); };
  const blk = k => ov.querySelector(`[data-block="${k}"]`), hole = ov.querySelector('[data-hole]'), tip = ov.querySelector('[data-tip]');
  const els = st.target ? st.target.split(',').map(k => root.querySelector(`.v3-screen [data-tour="${k}"]`)).filter(Boolean) : [];
  if (!els.length) {                                               // centred card: everything dimmed and blocked
    set(hole, W / 2, H / 2, 0, 0); set(blk('t'), 0, 0, W, H); ['r', 'b', 'l', 'hole'].forEach(k => set(blk(k), 0, 0, 0, 0));
    if (tip) { const th = tip.offsetHeight; Object.assign(tip.style, { top: Math.max(safeT + 16, (H - th) / 2) + 'px' }); }
    return;
  }
  const rs = els.map(e => e.getBoundingClientRect()), pad = 8;
  let x = Math.min(...rs.map(r => r.left)) - R.left - pad, y = Math.min(...rs.map(r => r.top)) - R.top - pad;
  let x2 = Math.max(...rs.map(r => r.right)) - R.left + pad, y2 = Math.max(...rs.map(r => r.bottom)) - R.top + pad;
  x = Math.max(4, x); y = Math.max(4, y); x2 = Math.min(W - 4, x2); y2 = Math.min(H - 4, y2);
  set(hole, x, y, x2 - x, y2 - y);
  set(blk('t'), 0, 0, W, y); set(blk('b'), 0, y2, W, H - y2); set(blk('l'), 0, y, x, y2 - y); set(blk('r'), x2, y, W - x2, y2 - y); set(blk('hole'), x, y, x2 - x, y2 - y);
  if (!tip) return;
  const th = tip.offsetHeight, gap = 14, below = y2 + gap, above = y - gap - th;
  let top = below + th <= H - safeB - 12 ? below : above >= safeT + 12 ? above : Math.max(safeT + 12, H - safeB - 12 - th);
  tip.style.top = top + 'px';
  tip.dataset.side = top >= y2 ? 'below' : top + th <= y ? 'above' : 'over';
}

// ------------------------------------------------------------------------------------------------ start / resume
export async function startRun() {
  if (S.view === 'G01.STARTING') return;
  go('G01.STARTING');
  const key = S.keys.start ||= newKey();
  try {
    const st = await api.start(key);
    S.keys.start = null;
    S.gen++;
    S.result = null; S.products = null; S.share = null; S.ended = null;
    resetRunUi(); S.ui.histPositioned = null;
    S.run = st.run; S.ref = st.run.reference;
    host.setBackHandler(onBack);
    go('G03.READY');
  } catch (e) {
    if (e instanceof ApiError) {
      if (e.code === 'ACTIVE_RUN_EXISTS') { S.keys.start = null; return resume(e.body.run_id); }
      if (e.code === 'OPERATION_PENDING') { S.keys.start = null; go('G01.OPERATION_PENDING'); return pollPendingAtStart({ runId: e.body.run_id }); }
      if (e.code === 'DAILY_QUOTA_EXHAUSTED') { S.keys.start = null; return boot(); }
    }
    if (e instanceof ApiError && e.network) { toast('اتصال برقرار نشد؛ دوباره امتحان کن.', true); return go('G01.READY'); }   // same key on retry
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
  S.ui.resuming = false;                                       // an authoritative state ends «در حال اتصال دوباره…»
  const prev = S.run;
  S.run = run; S.ref = run.reference;
  if (run.state === 'FINISHED') {
    // the run ended while a payment was unresolved: show what happened to the payment before the result (G06.FAILED_END*)
    if (S.op && S.op.operationId && run.finishReason === 'continue_failed' && ['G06.PENDING', 'G06.UNKNOWN'].includes(S.view))
      return api.operation(S.op.operationId).then(o => onOperation(o, true)).catch(() => finished(run));
    if (S.view.startsWith('G06.FAILED_END') || S.view === 'G06.REFUNDED') return render();   // the sheet's «دیدن نتیجه» leads on
    return finished(run);
  }
  if (run.state === 'READY') {
    if (['G03.CORRECT_FEEDBACK'].includes(S.view) && context !== 'resume') return;   // the promotion will land here anyway
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
  S.q = null;
  if (run.state === 'AWAITING_OFFER') { go(S.fb && S.fb.kind === 'timeout' ? 'G03.TIMEOUT' : 'G03.WRONG_FEEDBACK'); return later(context === 'resume' ? 600 : 0, activateOffer); }
  if (run.state === 'OFFER_OPEN') {
    if (!S.offer || S.offer.offerId !== run.offer.offerId) S.ui.offerOver = false;
    S.offer = run.offer; S.op = null;
    if (['G06.FAILED_END'].includes(S.view)) return render();     // a rejection after the visible window: the sheet waits for the run's end
    go('G05.OFFER'); return tickLoop();
  }
  if (run.state === 'PAYMENT_PENDING') {
    S.offer = run.offer;
    if (!S.op || S.op.operationId !== (run.operation && run.operation.operationId)) S.op = { operationId: run.operation ? run.operation.operationId : null, status: 'pending', since: now() };
    go(S.view === 'G06.UNKNOWN' ? 'G06.UNKNOWN' : 'G06.PENDING'); tickLoop(); return pollOperation();
  }
}

// ------------------------------------------------------------------------------------------------ reveal / answer
export async function reveal() {
  if (!S.run || S.ui.offline) return;
  if (!['G03.READY', 'G06.SUCCESS', 'G03.PREPARE_FAILED'].includes(S.view) || S.ui.cp || S.ui.cpDue) return;
  if (now() < S.ui.guardUntil) return;                         // a tap falling through from closing the checkpoint
  if (motion.running()) { S.ui.queued = 'reveal'; return; }    // one tap during a card move runs when it lands
  S.ui.toast = null; S.ui.afterContinue = false;
  S.ui.morph = S.view === 'G03.PREPARE_FAILED' ? 0 : DUR.shrinkMs;
  go('G03.PREPARING');
  const runId = S.run.runId, key = S.keys.reveal ||= newKey(), g = S.gen;
  try {
    let run;
    try { run = await api.reveal(runId, key); }
    catch (e) { if (e instanceof ApiError && e.network) run = await api.reveal(runId, key); else throw e; }   // one retry, same key
    if (g !== S.gen) return;
    S.keys.reveal = null;
    S.run = run;
    if (run.state === 'FINISHED' && run.finishReason === 'no_products') return go('G03.NO_PRODUCTS_LEFT');
    if (run.state !== 'QUESTION_OPEN') return applyRun(run);
    S.q = { ...run.question, imgReady: false, imgSlow: false, pick: null, key: null };
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
 * The selection is recorded synchronously (both keys lock, the picked one shows its spinner, the countdown gives way to
 * the processing state) and sent at once. From then on the local timer has no say: reaching its zero neither cancels the
 * answer nor shows a loss; only the server's settled result moves the screen. A tap after the visible window is ignored.
 */
export async function answer(choice) {
  const q = S.q;
  if (!q || q.pick || q.locked || S.view !== 'G03.QUESTION_ACTIVE' || S.ui.offline) return;   // one selection per question
  if (clock.serverNow() >= clock.ms(q.inputDeadline)) { lockQuestion(q); return; }             // the visible window is over
  q.pick = choice; q.key ||= newKey();
  S.ui.morph = 0; go('G03.ANSWER_PENDING');
  const g = S.gen;
  // retry after 1.5 s with the same key, up to 2 times; a retry never creates a second answer (the server keeps the first)
  for (let i = 0; i < 3; i++) {
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
  return awaitSettlement(q);                                    // no response: read the run by the question's identity
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
  S.ui.morph = DUR.verdictMs;                                  // hidden price tag → price plate, ask row → verdict
  S.ui.noOffer = ans.noOfferReason;
  if (ans.result === 'correct') {
    haptic('correct');
    go('G03.CORRECT_FEEDBACK');
    const reached = ans.checkpointReached, fb = S.fb;
    later(DUR.correctHoldMs, () => {                           // readable verdict first, then the promotion
      if (S.fb !== fb) return;
      S.ref = { displayTitle: fb.product.displayTitle, imageUrl: fb.product.imageUrl, price: fb.revealedPrice };
      S.fb = null;
      if (ans.runFinished) return finished(S.run);
      S.ui.cpDue = reached || null;
      S.ui.morph = DUR.promoteMs; go('G03.READY');             // one continuous move into the first product's slot (UX-2)
      if (reached) later(DUR.promoteMs + 60, () => { if (S.ui.cpDue === reached) openCp(reached); });
    });
    return;
  }
  haptic('wrong');
  go(ans.result === 'timeout' ? 'G03.TIMEOUT' : 'G03.WRONG_FEEDBACK');
  afterFeedback(S.run);
}

/** After a miss: hold the real price readable, then open the offer (F-02) or show the result. */
function afterFeedback(run) {
  const fb = S.fb;
  later(DUR.missHoldMs, () => {
    if (S.fb !== fb) return;
    if (run.state === 'AWAITING_OFFER') return activateOffer();
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
    delete S.keys[intent];                                      // answered: this miss's activation is done
    S.run = act.run;
    if (act.balance != null) S.balance = act.balance;
    if (act.offer && (act.run.state === 'OFFER_OPEN' || act.run.state === 'PAYMENT_PENDING')) { S.offer = act.offer; S.ui.offerOver = false; go('G05.OFFER'); return tickLoop(); }
    S.ui.noOffer = act.noOfferReason;
    if (act.noOfferReason === 'no_funds') { go('G05.NO_FUNDS'); return later(DUR.missHoldMs, () => finished(S.run)); }   // no offer, not even disabled (R-63)
    finished(act.run);
  } catch (e) { if (g === S.gen) handleError(e); }
}

// ------------------------------------------------------------------------------------------------ continue / skip / leave
/**
 * Extra-life purchase: one tap locks both buttons and shows the loading state before the request leaves; the request
 * is admitted by its arrival time (offer start + 15 s), so reaching the visible zero while it is in flight changes
 * nothing on screen: the game resumes only after the server confirms the charge, and a definitive failure ends the
 * offer as the server decides. A lost response is recovered by the operation's own id, never by a second purchase.
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
    catch (e) { if (e instanceof ApiError && e.network) opv = await api.continueOffer(offerId, S.op.key); else throw e; }   // same key
    if (g !== S.gen) return;
    onOperation(opv);
  } catch (e) {
    if (g !== S.gen) return;
    if (e instanceof ApiError && e.network) { go('G06.UNKNOWN'); return reconcileRun(); }
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
    S.ui.morph = DUR.promoteMs; return go('G06.SUCCESS');      // same run, same first product, score and checkpoints kept (R-07)
  }
  if (opv.status === 'failed') {
    delete S.keys['continue:' + (S.offer && S.offer.offerId)];   // a retry after a rejected debit is a new request, not a replay
    S.op = null;
    if (S.offer && clock.serverNow() < clock.ms(S.offer.deadline) && opv.run.state === 'OFFER_OPEN') return go('G06.FAILED_RETRY');
    go('G06.FAILED_END');
    if (opv.run.state !== 'FINISHED') later(1000, reconcileRun);  // the server ends the offer; «دیدن نتیجه» ends it now
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
      if (!S.op.operationId) { await reconcileRun(); continue; }
      const opId = S.op.operationId;
      try { const o = await api.operation(opId); if (g !== S.gen || !S.op) return; if (S.op.operationId !== opId) continue; onOperation(o, true); }   // the loop continues while still pending
      catch (e) { if (g !== S.gen) return; if (!(e instanceof ApiError) || !e.network) return handleError(e); }
    }
  } finally { S.ui.polling = false; }
}

/** A late-confirmed debit is refunded by the server; while its sheet is open, look for the refund for 2 minutes (the reconciliation backs off to 60 s). */
async function watchRefund(operationId) {
  const g = S.gen;
  for (let i = 0; i < 24; i++) {
    await sleep(5000);
    if (g !== S.gen || S.view !== 'G06.FAILED_END.UNCONFIRMED') return;
    try { const o = await api.operation(operationId); if (o.balance != null) S.balance = o.balance; if (o.status === 'refunded') { S.op = { ...S.op, ...o }; return go('G06.REFUNDED'); } }
    catch (e) { /* the sheet stays; the result page shows reward status */ }
  }
}

async function reconcileRun() {
  if (!S.run) return;
  const g = S.gen, runId = S.run.runId;
  try { const run = await api.run(runId); if (g === S.gen && S.run && S.run.runId === runId) applyRun(run); }
  catch (e) { if (g === S.gen && (!(e instanceof ApiError) || !e.network)) handleError(e); }
}

/** The visible offer window is over and nothing was bought: the purchase button locks; the server ends the offer at its arrival deadline. */
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

export async function skip() {
  if (!S.offer || S.op) return;
  const offerId = S.offer.offerId, key = S.keys['skip:' + offerId] ||= newKey();
  try { const run = await api.skip(offerId, key); finished(run); } catch (e) { handleError(e); }
}

function onBack() {
  if (!S.run) return false;
  if (S.ui.cp) return true;                                   // the checkpoint closes itself; back does nothing meanwhile
  if (S.op || S.view === 'G03.ANSWER_PENDING') return true;   // back is blocked while an answer or a payment is in flight
  if (inRun(S.view)) { S.ui.leave = true; render(); return true; }
  return false;
}

export async function leaveConfirmed() {
  if (!S.run) return;
  S.ui.leave = false;
  const key = S.keys['leave:' + S.run.runId] ||= newKey();
  try { const run = await api.leave(S.run.runId, key); finished(run); } catch (e) { handleError(e); }
}

// ------------------------------------------------------------------------------------------------ checkpoint (G04)
/**
 * Root cause of the duplicate celebration: the overlay was part of the screen markup, and the screen is rebuilt on
 * every render — the guard timer re-rendered at 1.2 s (and a toast, an image load or a reconnect could too), so the
 * overlay's entrance (character, burst, confetti) played again. It is now mounted ONCE into its own host that render()
 * never touches, only for a (run, checkpoint) pair not shown before; a replayed or recovered answer cannot open it twice.
 */
let cpToken = 0, cpTimers = [];
function openCp(c) {
  const k = S.run.runId + ':' + c.checkpointId;
  if (S.ui.cpShown.has(k)) return;                            // once per checkpoint per run
  S.ui.cpShown.add(k);
  const token = ++cpToken, cps = cfg().checkpoints, idx = cps.findIndex(x => x.checkpointId === c.checkpointId);
  S.ui.cp = c; S.ui.cpDue = null; S.ui.cpAt = now();
  haptic('checkpoint');
  // «دریافت کردی» needs a confirmed grant; checkpoint rewards are paid at the end of the run (spec), so not yet here
  cpEl.innerHTML = V.cpOverlay(c, cps.find(x => x.requiredCorrect > c.requiredCorrect), idx, false, DUR.cpGuardMs, cfg().checkpointAutoContinueMs);
  screenEl.inert = true;
  root.dataset.view = stateId();
  cpTimers.push(later(DUR.cpGuardMs, () => {
    if (cpToken !== token) return;
    const ov = $('.v3-cp', cpEl); if (!ov) return;
    ov.setAttribute('data-ready', ''); const b = $('.v3-cp__go', cpEl); b.removeAttribute('aria-disabled'); b.focus({ preventScroll: true });
  }));
  cpTimers.push(later(DUR.cpEntranceMs, () => {                // the automatic hold is measured once the content is readable
    if (cpToken !== token) return;
    const bar = $('[data-cpauto]', cpEl); if (bar) requestAnimationFrame(() => { bar.style.transform = 'scaleX(1)'; });
    cpTimers.push(later(cfg().checkpointAutoContinueMs, () => closeCp(token, 'auto')));
  }));
}
function unmountCp() {
  cpToken++;
  cpTimers.forEach(clearTimeout); cpTimers = [];
  S.ui.cp = null;
  if (cpEl) cpEl.innerHTML = '';
  if (screenEl) screenEl.inert = false;
}
/** Tap and automatic completion converge here; the token makes the second one (or a stale timer) a no-op. */
export function closeCp(token = cpToken, source = 'tap') {
  if (!S.ui.cp || token !== cpToken) return;
  if (source === 'tap' && now() < S.ui.cpAt + DUR.cpGuardMs) return;   // the earning tap or a double tap cannot dismiss it
  unmountCp();
  S.ui.guardUntil = now() + DUR.afterCpGuardMs;
  render();
}

// ------------------------------------------------------------------------------------------------ timers
let raf = 0, quotaTry = { at: null, n: 0, next: 0 };

/**
 * The daily reset was reached on a quota screen: ask the server (never treat a local zero as permission to play).
 * Retried every 2 s, at most 5 times per reset instant, until the server's reset instant has moved on.
 */
async function quotaReset() {
  if (!S.boot) return;
  const at = S.boot.quota.resetsAt;
  if (quotaTry.at !== at) quotaTry = { at, n: 0, next: 0 };
  if (quotaTry.n >= 5 || now() < quotaTry.next) return;
  quotaTry.n++; quotaTry.next = now() + 2000;
  if (S.view.startsWith('G01.')) return boot();
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
        if (tm) { tm.style.setProperty('--p', n.p); tm.style.setProperty('--tc', n.tc); tm.classList.toggle('is-low', n.low); const b = tm.querySelector('b'); if (b && b.textContent !== n.t) { b.textContent = n.t; tm.setAttribute('aria-label', n.t + ' ثانیه'); } }
        if (n.rem <= 0) lockQuestion(q);                            // the visible zero without a selection: input locks
      }
      if (root.dataset.view !== stateId()) root.dataset.view = stateId();
      raf = requestAnimationFrame(step); return;
    }
    if (['G05.OFFER', 'G06.FAILED_RETRY'].includes(v) && S.offer) {
      const rg = $('[data-ring]', root), rem = Math.max(0, clock.ms(S.offer.deadline) - clock.serverNow()), c = cfg();
      if (rg) { const s = fa(Math.ceil(rem / 1000)); rg.style.setProperty('--p', (rem / c.offerMs).toFixed(3)); rg.classList.toggle('is-low', rem <= 3000); const b = rg.querySelector('b'); if (b && b.textContent !== s) b.textContent = s; }
      if (rem <= 0) {                                               // the visible window is over; the server ends the offer
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

// ------------------------------------------------------------------------------------------------ End Screen (G07)
function finished(run) {
  S.gen++;
  S.run = run; S.q = null; S.offer = null; S.op = null;
  resetRunUi();
  S.ended = endedFrom(run);
  host.setBackHandler(null);
  if (run.finishReason === 'inactive') return go('G10.INACTIVITY_END');
  if (run.finishReason === 'replaced') return go('G11.OTHER_DEVICE');
  if (run.finishReason === 'blocked') return go('G11.BLOCKED');
  showResult();
}

/**
 * Shown only for a finished run. Back (on screen or the device's) goes to the start screen through the normal entry:
 * the local run state is dropped, nothing is resubmitted, and no run or payment is created.
 */
export async function showResult() {
  const runId = (S.ended && S.ended.runId) || (S.run && S.run.runId);
  if (!runId) return boot();
  S.result = null; S.share = null; S.products = null; S.ui.codeState = null; S.ui.historyState = 'loading';
  host.setBackHandler(() => { if (S.view.startsWith('G07.')) { if (S.share) { S.share = null; render(); } else boot(); return true; } return false; });
  go('G07.LOADING');
  const g = S.gen;
  if (feat('products')) loadHistory(runId, g);
  for (let i = 0; i < 4; i++) {                               // quiet retries with backoff
    try { S.result = await api.result(runId); break; }
    catch (e) { if (i === 3 || !(e instanceof ApiError) || !(e.network || e.status >= 500)) { if (g === S.gen) handleError(e); return; } await sleep(600 * (i + 1)); }
  }
  if (g !== S.gen) return;
  if (S.boot) S.boot.quota = S.result.quota;
  go('G07.STANDARD');
  refreshResult(runId, g);
}

async function loadHistory(runId, g) {
  S.ui.historyState = 'loading';
  try {
    const list = await api.products(runId);
    if (g !== S.gen || !S.view.startsWith('G07.')) return;
    S.products = list; S.ui.historyState = 'ready';
  } catch (e) { if (g !== S.gen) return; S.ui.historyState = 'error'; }
  render();
}

async function refreshResult(runId, g) {   // a pending reward: every 2 s up to 10 s; a pending code: every 2 s up to 20 s (handoff)
  const started = now();
  while (g === S.gen && S.view.startsWith('G07.')) {
    const R = S.result;
    const rewardPending = R.checkpointReward && R.checkpointReward.status === 'pending';
    const codePending = R.campaignCode && R.campaignCode.status === 'pending';
    const t = now() - started;
    if (!((rewardPending && t < 10000) || (codePending && t < 20000))) return;
    await sleep(2000);
    if (g !== S.gen) return;
    try { const r = await api.result(runId); if (g !== S.gen || !S.result || S.result.runId !== runId) return; S.result = r; render(); } catch (e) { return; }
  }
}

/** After every End Screen render: restore the player's own scroll; once per run, bring the losing turn into view. */
function afterResultRender() {
  const sc = root.querySelector('[data-hscroll="history"]'); if (!sc) return;
  const runId = S.products && S.products.runId;
  if (S.ui.histPositioned === runId && S.ui.histScroll != null) sc.scrollLeft = S.ui.histScroll;
  sc.addEventListener('scroll', () => { if (S.ui.histPositioned === runId) S.ui.histScroll = sc.scrollLeft; }, { passive: true });
  if (S.ui.histPositioned === runId) return;
  const place = () => {
    const s2 = root.querySelector('[data-hscroll="history"]'); if (!s2 || S.ui.histPositioned === runId) return;
    const target = s2.querySelector('[data-loss]') || s2.lastElementChild; if (!target) return;
    target.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'auto' });   // RTL-aware: the browser maps inline to the scroll direction
    if (target.hasAttribute('data-loss')) target.classList.add('is-guide');              // one brief emphasis
    S.ui.histPositioned = runId; S.ui.histScroll = s2.scrollLeft;
  };
  // after layout (and fonts) are ready, so the measured positions are final
  requestAnimationFrame(() => requestAnimationFrame(() => (document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve()).then(place)));
}

export async function openProduct(link) {
  const ok = await host.openProduct(link);
  if (!ok) { toast('صفحه کالا باز نشد. دوباره امتحان کن.', true, 'G07.LINK_FAILED', true); render(); }
}

export async function prepareShare() {
  if (S.ui.shareBusy || !S.result || !feat('share')) return;
  const g = S.gen, runId = S.result.runId, mine = () => g === S.gen && S.result && S.result.runId === runId;
  S.ui.shareBusy = true; render();
  try { const sh = await api.share(runId); if (!mine()) return; S.share = sh; }
  catch (e) { if (!mine()) return; S.share = null; toast('تصویر اشتراک ساخته نشد. دوباره امتحان کن.', true, 'G09.FAILED', true); }
  finally { if (mine()) { S.ui.shareBusy = false; render(); } }
}

export async function sendShare() {
  const blob = await drawShareImage(S.share).catch(() => null);
  if (!blob) { S.share = null; toast('تصویر اشتراک ساخته نشد. دوباره امتحان کن.', true, 'G09.FAILED', true); return render(); }
  const r = await host.share(blob, 'قیمت‌شناس');
  if (r === 'unsupported') toast('اشتراک‌گذاری روی این دستگاه پشتیبانی نمی‌شه.', true, 'G09.FAILED', true);
  S.share = null; render();
}

/** Share image (proposed 1080×1920; no player name, R-12; no streak; image only, no invented deep link, R-56). */
async function drawShareImage(s) {
  if (document.fonts && document.fonts.ready) await document.fonts.ready;
  const c = document.createElement('canvas'); c.width = 1080; c.height = 1920;
  const x = c.getContext('2d');
  const g = x.createLinearGradient(0, 0, 0, 1920); g.addColorStop(0, '#D7193A'); g.addColorStop(1, '#8E0E25');
  x.fillStyle = g; x.fillRect(0, 0, 1080, 1920);
  x.direction = 'rtl'; x.textAlign = 'center'; x.fillStyle = '#FFFFFF';
  x.font = '120px Lalezar, Vazirmatn, sans-serif'; x.fillText(s.gameName, 540, 330);
  x.font = '700 48px Vazirmatn, sans-serif'; x.fillText('بازی گرون‌تره یا ارزون‌تر', 540, 420);
  x.font = '900 420px Vazirmatn, sans-serif'; x.fillText(fa(s.score), 540, 1060);
  x.font = '800 64px Vazirmatn, sans-serif'; x.fillText('پاسخ درست در یک دست', 540, 1180);
  if (s.checkpointTitle) { x.fillStyle = '#FFC633'; x.font = '900 72px Vazirmatn, sans-serif'; x.fillText(s.checkpointTitle, 540, 1400); x.fillStyle = '#FFFFFF'; }
  x.font = '700 52px Vazirmatn, sans-serif'; x.fillText('تو چندتا می‌زنی؟', 540, 1700);
  return new Promise(res => c.toBlob(res, 'image/png'));
}

export async function copyCode() {
  const R = S.result; if (!R || !R.campaignCode || !R.campaignCode.code) return;
  const ok = await host.copy(R.campaignCode.code);
  if (S.result !== R) return;
  if (!ok) { S.ui.codeState = null; toast('کپی نشد. کد رو نگه دار و دستی کپی کن.', true, 'G07.CAMPAIGN_CODE.COPY_FAILED', true); return render(); }
  S.ui.codeState = 'copied'; render();
  setTimeout(() => { if (S.result && S.result.runId === R.runId && S.ui.codeState === 'copied') { S.ui.codeState = null; render(); } }, DUR.copiedMs);
}

// ------------------------------------------------------------------------------------------------ connectivity & lifecycle
function setOffline(off) {
  S.ui.offline = off;
  if (off) return render();
  if (S.run && inRun(S.view)) {                              // reconcile inline; the screen stays (CP-26)
    S.ui.resuming = true; render();
    const g = S.gen;
    api.run(S.run.runId).then(run => { if (g !== S.gen) return; S.ui.resuming = false; applyRun(run); })
      .catch(e => { if (g !== S.gen) return; S.ui.resuming = false; render(); if (!(e instanceof ApiError) || !e.network) handleError(e); });
  } else render();
}

export function init(el) {
  root = el;
  root.innerHTML = '<div class="pe-screenhost"></div><div class="pe-cphost"></div>';
  screenEl = root.firstElementChild; cpEl = root.lastElementChild;
  window.addEventListener('online', () => setOffline(false));
  window.addEventListener('offline', () => setOffline(true));
  document.addEventListener('visibilitychange', () => {       // backgrounding is not leaving; revalidate on return
    if (document.visibilityState !== 'visible') return;
    clock.resetAfterSleep();
    if (S.view === 'G01.QUOTA_EXHAUSTED') return boot();       // the reset may have passed while the app was in the background
    if (S.run && inRun(S.view) && !S.ui.cp && !['G03.CORRECT_FEEDBACK', 'G03.WRONG_FEEDBACK', 'G03.TIMEOUT'].includes(S.view)) reconcileRun();
  });
  root.addEventListener('click', onClick);
  root.addEventListener('keydown', e => { if (S.ui.cp && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); closeCp(); } });
  boot();
}

function onClick(e) {
  if (S.ui.cp && e.target.closest('.v3-cp')) return closeCp();   // tap anywhere on the overlay
  const el = e.target.closest('[data-act]'); if (!el || el.disabled || el.getAttribute('aria-disabled') === 'true') return;
  const a = el.dataset.act;
  const acts = {
    boot, start: startRun, reveal, skip, continue: continueOffer, 'ans-higher': () => answer('higher'), 'ans-lower': () => answer('lower'),
    guide: () => startTour('guide'),
    'tour-next': () => { const t = S.ui.tour; if (t && !TOUR[t.i].interact && t.i < TOUR.length - 1) tourStep(t.i + 1); },
    'tour-prev': () => { const t = S.ui.tour; if (t && t.i > 0) tourStep(t.i - 1); },
    'tour-reveal': () => { const t = S.ui.tour; if (t && TOUR[t.i].id === 'reveal') tourStep(t.i + 1); },
    'tour-ans-higher': () => tourAnswer('higher'), 'tour-ans-lower': () => tourAnswer('lower'),
    'tour-close': () => { if (S.ui.tour && S.ui.tour.mode === 'guide') closeTour(); },
    'tour-finish': finishTour,
    'daily-claim': claimDaily, 'daily-check': checkDaily, 'daily-close': () => { S.ui.daily = null; render(); },
    resume: () => resume(S.boot.activeRun.runId),
    back: () => onBack(),
    'leave-no': () => { S.ui.leave = false; render(); },
    'leave-yes': leaveConfirmed,
    // a run still open (the offer's visible window ended after a rejected payment) is ended by its skip, never left hanging
    'see-result': () => (S.run && S.run.state === 'OFFER_OPEN' && S.offer ? skip() : finished(S.run)),
    'show-result': showResult,
    recheck: () => { if (S.op && S.op.operationId) api.operation(S.op.operationId).then(o => onOperation(o, true)).catch(() => {}); else reconcileRun(); },
    'recheck-answer': () => { if (S.q) readSettlement(S.q, S.gen); },
    'to-start': boot,
    share: prepareShare, 'share-send': sendShare, 'share-close': () => { S.share = null; render(); },
    'history-retry': () => { const id = S.result && S.result.runId; if (id) { S.ui.historyState = 'loading'; S.products = null; render(); loadHistory(id, S.gen); } },
    'open-product': () => openProduct(el.dataset.link),
    copy: copyCode,
    'exit-app': () => host.close(),
  };
  if (acts[a]) acts[a]();
}
