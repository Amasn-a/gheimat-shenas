// Player state controller: one authoritative store drives the DOM. Explicit view states with transition guards; run,
// question and operation identities plus a generation counter make stale responses and timers harmless. The server
// decides timing, results, money and rewards; this file only renders what the server said and sends guarded intents.
import { api, ApiError, newKey } from './api.js';
import * as clock from './clock.js';
import * as host from './host.js';
import * as motion from './motion.js';
import { DUR } from './durations.js';
import * as V from './views.js';
import { fa, money, untilInstant } from './fmt.js';

const $ = (s, r = document) => r.querySelector(s);

export const S = {
  view: 'G01.LOADING',
  boot: null, balance: null,
  run: null,          // last run view from the server
  ref: null,          // reference card
  q: null,            // open question { questionId, product, t0, inputDeadline, acceptDeadline, imgReady, imgSlow, pick, key }
  fb: null,           // feedback { kind, choice, direction, revealedPrice, product, equal }
  offer: null, op: null,
  result: null, products: null, ended: null, share: null,
  ui: { toast: null, leave: false, cp: null, help: 'FIRST', offline: !navigator.onLine, resuming: false, queued: null, morph: null,
        guardUntil: 0, lossGuided: false, shareBusy: false, codeState: null, afterContinue: false, noOffer: null },
  keys: {},           // one Idempotency-Key per intent, reused for its retries
  gen: 0,
};

let root = null;
const now = () => performance.now();
function later(ms, fn) { const g = S.gen; return setTimeout(() => { if (g === S.gen) fn(); }, ms); }
const cfg = () => (S.run && S.run.runConfig) || (S.boot && S.boot.config) || { checkpoints: [], continueRule: { enabled: false, cost: 0, cap: 0 }, inputMs: 10500, acceptMs: 11000, offerMs: 10000, checkpointAutoContinueMs: 5000 };
const inRun = v => /^G03\.|^G04\.|^G05\.|^G06\./.test(v);

// ------------------------------------------------------------------------------------------------ rendering
function model() {
  const r = S.run, c = cfg();
  return {
    boot: S.boot, toast: S.ui.toast, config: c, playsPerDay: S.boot ? S.boot.quota.playsPerDay : 3, helpFirst: S.ui.help === 'FIRST',
    untilReset: S.boot ? clock.ms(S.boot.quota.resetsAt) - clock.serverNow() : 0,
    result: S.result, products: S.products, lossGuided: S.ui.lossGuided, shareBusy: S.ui.shareBusy, ended: S.ended,
    lastScore: S.ended ? S.ended.score : 0, playerMessage: S.boot && S.boot.access ? S.boot.access.message : null,
    shareSheet: '', score: r ? r.score : 0, checkpoint: r ? r.highestCheckpoint : null, leave: S.ui.leave,
    questionOpen: ['G03.QUESTION_ACTIVE', 'G03.ANSWER_PENDING'].includes(S.view),
  };
}

function hud(plus) {
  const r = S.run;
  return V.top({ score: r.score, record: r.record, balance: S.balance, plus,
    backLocked: !!S.op || S.view === 'G03.ANSWER_PENDING' || S.view === 'G06.PENDING' || S.view === 'G06.UNKNOWN' });
}
const trk = earned => V.track(S.run.score, cfg().checkpoints, S.run.record, earned);

function timerNow() {
  const c = cfg(), q = S.q;
  const rem = Math.max(0, Math.min(c.inputMs, clock.ms(q.inputDeadline) - clock.serverNow()));
  // the label belongs to t0: 10 × remaining / 10,500, rounded up, so it never shows 0 while input is open (R-45)
  return { rem, t: fa(Math.ceil(10 * rem / c.inputMs)), p: (rem / c.inputMs).toFixed(3), mode: rem <= c.inputMs * 0.3 ? 'low' : '' };   // last 3 shown seconds
}

function screen() {
  const v = S.view, m = model();
  if (v.startsWith('G01.')) return V.vStart(v, m);
  if (v.startsWith('G02.')) return V.vHelp(m);
  if (v === 'G03.LOADING') return loadingRun();
  if (['G03.READY', 'G03.PREPARING', 'G03.PREPARE_FAILED', 'G06.SUCCESS'].includes(v)) return ready(m);
  if (v === 'G03.QUESTION_ACTIVE' || v === 'G03.ANSWER_PENDING') return question(m);
  if (inRun(v)) return feedback(m);
  if (v.startsWith('G07.')) { m.shareSheet = S.share ? shareSheet() : ''; return V.vResult(m); }
  if (v.startsWith('G08.')) return V.vProducts(v, m);
  return V.vSystem(v, m);
}

function loadingRun() {
  return V.play(V.top({ score: 0, record: 0, balance: S.balance }), V.track(0, cfg().checkpoints, 0), '<div class="v3-hero v3-hero--ref" aria-busy="true" aria-label="کالای مرجع در راهه"><div class="v3-img v3-skel v3-skel--card"></div><div class="v3-hero__b"><span class="v3-skel v3-skel--card" style="flex:1 1 60%;height:20px"></span><span class="v3-skel v3-skel--card" style="width:40%;height:30px"></span></div></div>' +
    V.revealRow('کالای مرجع در راهه…', '', '<span class="v3-spin" aria-hidden="true"></span>نمایش بده', ' disabled aria-disabled="true" aria-busy="true"'), V.keys('off', null, true), '', S.ui.toast);
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
  const cp = S.ui.cp;
  const cpHTML = cp ? V.cpOverlay(cp, cfg().checkpoints.find(x => x.requiredCorrect > cp.requiredCorrect), now() >= S.ui.cpAt + DUR.cpGuardMs, DUR.cpGuardMs, cfg().checkpointAutoContinueMs) : '';
  return V.play(hud(), trk(), pill + V.refBig(S.ref) + row, V.keys('off', null, true), cpHTML + V.leaveSheet(m), S.ui.toast);
}

function question(m) {
  const q = S.q, pend = !!q.pick, off = S.ui.offline, tn = timerNow();
  const pill = off ? V.stagepill('اتصال قطع شد؛ تا برگرده نمی‌شه جواب داد و زمان ادامه داره.', 'wifi', true) : '';
  const a = pend ? V.ask('در حال بررسی پاسخ…', { ...tn, muted: true, spin: true }) : V.ask(V.Q_TEXT, { ...tn, muted: off });
  const rs = S.ui.resuming ? V.stagepill('در حال اتصال دوباره… زمان ادامه داره.', 'refresh') : '';   // CP-26
  const locked = off || tn.rem <= 0;
  return V.play(hud(), trk(), (rs || pill) + V.refTicket(S.ref) + a + V.hero(q.product, 'hidden', q.imgSlow && !q.imgReady), V.keys(pend ? 'pending' : locked ? 'off' : 'on', q.pick, false, S.ref.price), V.leaveSheet(m), S.ui.toast);
}

function fbParts() {
  const fb = S.fb, dir = V.dirOf(fb.direction), truthW = V.DIRW[dir];
  if (fb.kind === 'correct') {
    return [hud(true), trk(true), V.refTicket(S.ref) + V.verdict('ok', 'درست گفتی!', fb.equal ? 'قیمت‌ها برابر بود؛ هر دو جواب درسته' : `${truthW} بود`) +
      V.hero(fb.product, 'correct', false, dir, fb.revealedPrice), V.keys('ok', fb.choice), false];
  }
  if (fb.kind === 'wrong') return [hud(), trk(), V.refTicket(S.ref) + V.verdict('no', 'اشتباه بود', `${truthW} بود`) + V.hero(fb.product, 'wrong', false, dir, fb.revealedPrice), V.keys('no', fb.choice), true];
  const truthSide = fb.direction === 'higher' ? 'higher' : 'lower';
  return [hud(), trk(), V.refTicket(S.ref) + V.verdict('time', 'وقت تموم شد', `${truthW} بود`) + V.hero(fb.product, 'timeout', false, dir, fb.revealedPrice), V.keys('truth', truthSide), true];
}

function feedback(m) {
  const v = S.view, c = cfg();
  if (v === 'G03.NO_PRODUCTS_LEFT') return V.play(hud(), trk(), V.refTicket(S.ref) + V.ask('دست کامل شد', { mode: 'off', muted: true }) + '<div class="v3-hero is-empty"></div>', V.keys('off'),
    V.sheet('کالاها تموم شد!', `کالای مناسب دیگه‌ای برای این دست نمونده. دستت با ${fa(S.run.score)} پاسخ درست ثبت شد.`, '', V.GOLD('دیدن نتیجه', ' data-act="see-result"', false), '', ['ok', 'check']), S.ui.toast);
  if (!S.fb) return ready(m);
  const [t, tr, mm, d, dim] = fbParts();
  const bal = S.balance == null ? '…' : fa(S.balance), C = fa(S.offer ? S.offer.cost : c.continueRule.cost);
  const ONCE = '<p class="v3-once">فقط یک بار در هر دست.</p>';   // CP-23
  const ringHTML = () => S.offer ? V.ring(Math.max(0, clock.ms(S.offer.deadline) - clock.serverNow()), c.offerMs) : '';
  const txt = () => V.offerText(S.fb.revealedPrice, S.run.score, S.run.highestCheckpoint);
  let sh = '';
  if (v === 'G05.OFFER') sh = V.sheet('یه فرصت دیگه؟', txt(), V.cost(bal, S.offer.cost) + ONCE, V.BTNS(V.GOLD(V.COIN + `ادامه با ${C} دیجی‌کوین`, ' data-act="continue"', false), V.SKIP()), '', null, ringHTML());
  else if (v === 'G06.PENDING') sh = V.sheet('یه فرصت دیگه؟', txt(), V.cost(bal, S.offer.cost), V.BTNS(V.BUSY(`ادامه با ${C} دیجی‌کوین`, false), V.SKIP(V.L)),
    V.status('', 'در حال بررسی پرداخت… تا مشخص شدن نتیجه، خروج غیرفعاله.', null, true), null, ringHTML());
  else if (v === 'G06.FAILED_RETRY') sh = V.sheet('یه فرصت دیگه؟', txt(), V.cost(bal, S.offer.cost) + ONCE, V.BTNS(V.GOLD(V.COIN + `دوباره: ادامه با ${C} دیجی‌کوین`, ' data-act="continue"', false), V.SKIP()),
    V.status('no', 'پرداخت انجام نشد و کوینی کسر نشد. هنوز فرصت داری دوباره امتحان کنی.', 'alert', false, true), null, ringHTML());
  else if (v === 'G06.FAILED_END') sh = V.sheet('ادامه ممکن نشد', `پرداخت انجام نشد و کوینی کسر نشد. زمان پیشنهاد تموم شد و این دست با ${fa(S.run.score)} پاسخ درست ثبت شد.`, '',
    V.GOLD('دیدن نتیجه', ' data-act="see-result"', false), V.status('', `موجودی شما: ${bal} دیجی‌کوین`, 'info'), ['no', 'alert']);
  else if (v === 'G06.REFUNDED') sh = V.sheet('ادامه ممکن نشد', `این دست با ${fa(S.run.score)} پاسخ درست تموم شد${S.run.highestCheckpoint ? ` و پاداش چک‌پوینت ${fa(S.run.highestCheckpoint.requiredCorrect)} برات ثبت می‌شه` : ''}.`, '',
    V.GOLD('دیدن نتیجه', ' data-act="see-result"', false), V.status('ok', `${C} دیجی‌کوین به حسابت برگشت. موجودی: ${bal}`, 'check'), ['ok', 'refresh']);
  else if (v === 'G06.UNKNOWN') sh = V.sheet('نتیجه پرداخت هنوز مشخص نیست', 'داریم نتیجه رو از سرور می‌گیریم. هیچ مبلغی دوباره کسر نمی‌شه؛ اگه ادامه ممکن نشه، هزینه برمی‌گرده.', '',
    V.BTN('v3-btn--outline v3-btn--block', 'بررسی دوباره', ' data-act="recheck"'), V.status('warn', 'تا مشخص شدن نتیجه، خروج از دست غیرفعاله.', null, true), null, ringHTML());
  else if (v === 'G06.FAILED_END.UNCONFIRMED') {
    // CP-20 is NOT approved: draft wording that does not claim the debit never happened (contract §6, §19).
    const refund = S.op && S.op.refundStatus === 'succeeded' ? V.status('ok', 'هزینه داره به حسابت برمی‌گرده.', 'check') : '';
    sh = V.sheet('ادامه ممکن نشد', `پرداخت به‌موقع تأیید نشد و ادامه انجام نشد؛ اگه مبلغی کسر شده باشه، خودکار برمی‌گرده. این دست با ${fa(S.run.score)} پاسخ درست ثبت شد.`, refund,
      V.GOLD('دیدن نتیجه', ' data-act="see-result"', false), V.status('', `موجودی شما: ${bal} دیجی‌کوین`, 'info'), ['no', 'alert']);
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
  const ms = S.ui.morph != null ? S.ui.morph : motion.running() ? Math.max(160, motion.remaining()) : 0;
  S.ui.morph = null;
  const before = ms && !motion.reducedMotion() ? motion.snapshot(root) : null;
  const sc = root.querySelector('[data-scroll]'), keep = sc && [sc.dataset.scroll, sc.scrollTop];
  motion.end();
  root.innerHTML = screen();
  root.dataset.view = stateId();
  if (keep) { const n = root.querySelector(`[data-scroll="${keep[0]}"]`); if (n) n.scrollTop = keep[1]; }
  wireImages();
  if (S.view === 'G08.LIST' && S.products && S.products.lossProductId && !S.ui.lossGuided) { S.ui.lossGuided = true; requestAnimationFrame(guideToLoss); }
  if (before) motion.morph(root, before, ms, () => { const q = S.ui.queued; S.ui.queued = null; if (q === 'reveal') setTimeout(reveal, 0); });
  if (S.ui.cp && S.ui.cpAutoStarted) { const bar = root.querySelector('[data-cpauto]'); if (bar) { bar.style.transition = 'none'; bar.style.transform = `scaleX(${Math.min(1, (now() - S.ui.cpAutoStartedAt) / cfg().checkpointAutoContinueMs)})`; requestAnimationFrame(() => { bar.style.transition = `transform ${Math.max(0, cfg().checkpointAutoContinueMs - (now() - S.ui.cpAutoStartedAt))}ms linear`; bar.style.transform = 'scaleX(1)'; }); } }
}

/** Fine-grained state id (as in the handoff state index) for analytics, tests and reviewers. */
export function stateId() {
  const v = S.view;
  if (S.ui.resuming && inRun(v)) return 'G10.RESUMING';
  if (v === 'G03.QUESTION_ACTIVE' && S.q) {
    if (S.ui.offline) return 'G10.OFFLINE_AFTER_RELEASE';
    if (S.q.imgSlow && !S.q.imgReady) return 'G03.QUESTION_ACTIVE.IMAGE_SLOW';
    if (timerNow().mode === 'low') return 'G03.QUESTION_ACTIVE.LOW_TIME';
  }
  if (v === 'G03.ANSWER_PENDING' && S.ui.offline) return 'G10.OFFLINE_AFTER_RELEASE';
  if (v === 'G03.READY' && S.ui.offline) return 'G10.OFFLINE_BEFORE_RELEASE';
  if ((v === 'G03.READY' || v === 'G06.SUCCESS') && S.ui.cp) return 'G04.REACHED';
  if (v === 'G03.CORRECT_FEEDBACK' && S.fb && S.fb.equal) return 'G03.CORRECT_FEEDBACK.EQUAL';
  if (v === 'G01.READY' && S.boot) {
    const b = S.boot;
    if (b.activeRun && !b.activeRun.onThisDevice) return 'G01.READY.OTHER_DEVICE_RUN';
    if (b.campaign) return 'G01.READY.CAMPAIGN';
    if (b.streak.lostNotice) return 'G01.READY.STREAK_LOST';
    if (!b.record && !b.streak.days) return 'G01.READY.NEW_PLAYER';
  }
  if (v.startsWith('G07.')) return S.share ? 'G09.ASSET' : S.ui.shareBusy ? 'G09.GENERATING' : S.ui.toast && S.ui.toast.id ? S.ui.toast.id : V.resultId(S.result ? { ...S.result, codeState: S.ui.codeState } : null, S.result ? S.result.quota.playsLeft : null);
  if (v.startsWith('G08.') && S.ui.toast && S.ui.toast.id) return S.ui.toast.id;
  if (S.ui.leave && inRun(v)) return 'G03.LEAVE_CONFIRM';
  if (v.startsWith('G02.')) return 'G02.' + S.ui.help;
  return v;
}

function wireImages() {
  const im = root.querySelector('img[data-onload="q"]');
  if (!im || !S.q) return;
  const qid = S.q.questionId;
  const done = () => { if (S.q && S.q.questionId === qid && !S.q.imgReady) { S.q.imgReady = true; if (S.q.imgSlow) render(); } };
  if (im.complete && im.naturalWidth) done(); else { im.addEventListener('load', done, { once: true }); im.addEventListener('error', () => {}, { once: true }); }
}

function guideToLoss() {   // once per result: bring the losing product into view with a little context above it (UX-5)
  const list = root.querySelector('[data-scroll="products"]'), card = list && list.querySelector('[data-loss]'); if (!card) return;
  const top = card.offsetTop, bottom = top + card.offsetHeight, view = list.scrollTop + list.clientHeight;
  if (top >= list.scrollTop && bottom <= view - 8) return;
  list.scrollTo({ top: Math.max(0, top - 56), behavior: motion.reducedMotion() ? 'auto' : 'smooth' });
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

// ------------------------------------------------------------------------------------------------ boot / start / resume
export async function boot() {
  S.gen++; motion.end();
  Object.assign(S, { run: null, ref: null, q: null, fb: null, offer: null, op: null, result: null, products: null, share: null, keys: {} });
  S.ui = { ...S.ui, toast: null, leave: false, cp: null, queued: null, resuming: false, lossGuided: false, shareBusy: false, codeState: null, afterContinue: false, noOffer: null };
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
  if (b.firstVisit) { S.ui.help = 'FIRST'; return go('G02.FIRST'); }
  if (b.quota.playsLeft <= 0) return go('G01.QUOTA_EXHAUSTED');
  go('G01.READY');
}

async function pollPendingAtStart(active) {   // 1 s backing off to 2 s, only until the offer deadline (handoff)
  const g = S.gen; let delay = 1000;
  while (g === S.gen) {
    await new Promise(r => setTimeout(r, delay)); delay = Math.min(2000, delay + 500);
    if (g !== S.gen) return;
    try {
      const run = await api.run(active.runId);
      if (run.state !== 'PAYMENT_PENDING') { S.boot.activeRun = run.state === 'FINISHED' ? null : { ...active, state: run.state }; return run.state === 'FINISHED' ? finished(run) : go('G01.ACTIVE_RUN'); }
    } catch (e) { if (!(e instanceof ApiError) || !e.network) return handleError(e); }
  }
}

export async function startRun() {
  if (S.view === 'G01.STARTING') return;
  go('G01.STARTING');
  const key = S.keys.start ||= newKey();
  try {
    const st = await api.start(key);
    S.keys.start = null;
    S.gen++;
    S.result = null; S.products = null; S.share = null; S.ended = null;
    S.ui.lossGuided = false; S.ui.afterContinue = false; S.ui.noOffer = null;
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
  try { const run = await api.run(runId); host.setBackHandler(onBack); applyRun(run, 'resume'); }
  catch (e) { handleError(e); }
}

/** Rebuilds the view from an authoritative run view (resume, reconnect, any 409 with state). Deadlines never restart. */
function applyRun(run, context) {
  const prev = S.run;
  S.run = run; S.ref = run.reference;
  if (run.state === 'FINISHED') return finished(run);
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
  if (run.state === 'OFFER_OPEN') { S.offer = run.offer; S.op = null; go('G05.OFFER'); return tickLoop(); }
  if (run.state === 'PAYMENT_PENDING') {
    S.offer = run.offer;
    if (!S.op || S.op.operationId !== (run.operation && run.operation.operationId)) S.op = { operationId: run.operation ? run.operation.operationId : null, status: 'pending', since: now() };
    go('G06.PENDING'); tickLoop(); return pollOperation();
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

export async function answer(choice) {
  const q = S.q;
  if (!q || q.pick || S.view !== 'G03.QUESTION_ACTIVE' || S.ui.offline) return;   // one request per question
  if (clock.serverNow() >= clock.ms(q.inputDeadline)) return;                       // keys lock at the input deadline (R-45)
  q.pick = choice; q.key ||= newKey();
  S.ui.morph = 0; go('G03.ANSWER_PENDING');                                         // the timer keeps running (R-18)
  const g = S.gen;
  // retry after 1.5 s with the same key, up to 2 times, then read the run (handoff)
  for (let i = 0; i < 3; i++) {
    try {
      const ans = await Promise.race([api.answer(q.questionId, choice, q.key), new Promise((_, rej) => setTimeout(() => rej(new ApiError(0, 'SLOW')), 1500 + i * 1000))]);
      if (g !== S.gen || S.q !== q) return;
      return onAnswer(ans, q);
    } catch (e) {
      if (g !== S.gen || S.q !== q) return;
      if (e instanceof ApiError && (e.network || e.code === 'REQUEST_IN_PROGRESS')) continue;
      if (e instanceof ApiError && e.code === 'QUESTION_EXPIRED' && e.state) return applyMissFromState(e.state, q);
      return handleError(e);
    }
  }
  // No response after the retries: read the run until the server has settled this question (answer or timeout).
  for (let i = 0; i < 12 && g === S.gen && S.q === q; i++) {
    try {
      const run = await api.run(S.run.runId);
      if (g !== S.gen || S.q !== q) return;
      if (run.state !== 'QUESTION_OPEN' || run.question.questionId !== q.questionId) return applyMissFromState(run, q);
    } catch (e) { if (!(e instanceof ApiError) || !e.network) return handleError(e); }
    await new Promise(r => setTimeout(r, 700));
  }
  if (g === S.gen && S.q === q) { S.ui.resuming = true; render(); }
}

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
  if (run.state === 'QUESTION_OPEN' && run.question && run.question.questionId === q.questionId) { S.run = run; return pollAfterDeadline(q); }
  S.ui.resuming = false;
  const ans = answerFromRun(run, q);
  if (ans) return onAnswer(ans, q);
  applyRun(run);
}

function onAnswer(ans, q) {
  S.run = ans.run;
  S.fb = { questionId: q.questionId, kind: ans.result, choice: ans.choice, direction: ans.direction, revealedPrice: ans.revealedPrice, equal: ans.equalPrices, product: q.product };
  S.q = null;
  S.ui.morph = DUR.verdictMs;                                  // hidden price tag → price plate, ask row → verdict
  S.ui.noOffer = ans.noOfferReason;
  if (ans.result === 'correct') {
    host.haptic('correct');
    go('G03.CORRECT_FEEDBACK');
    const reached = ans.checkpointReached, fb = S.fb;
    later(DUR.correctHoldMs, () => {                           // readable verdict first, then the promotion
      if (S.fb !== fb) return;
      S.ref = { displayTitle: fb.product.displayTitle, imageUrl: fb.product.imageUrl, price: fb.revealedPrice };
      S.fb = null;
      if (ans.runFinished) return finished(S.run);
      S.ui.cpDue = reached || null;
      S.ui.morph = DUR.promoteMs; go('G03.READY');             // one continuous move into the reference slot (UX-2)
      if (reached) later(DUR.promoteMs + 60, () => { if (S.ui.cpDue === reached) openCp(reached); });
    });
    return;
  }
  host.haptic('wrong');
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
  const runId = S.run.runId, key = S.keys['offer:' + runId] ||= newKey(), g = S.gen;
  try {
    let act;
    try { act = await api.activateOffer(runId, key); }
    catch (e) { if (e instanceof ApiError && e.network) act = await api.activateOffer(runId, key); else throw e; }
    if (g !== S.gen) return;
    S.run = act.run;
    if (act.balance != null) S.balance = act.balance;
    if (act.offer && (act.run.state === 'OFFER_OPEN' || act.run.state === 'PAYMENT_PENDING')) { S.offer = act.offer; go('G05.OFFER'); return tickLoop(); }
    S.ui.noOffer = act.noOfferReason;
    if (act.noOfferReason === 'no_funds') { go('G05.NO_FUNDS'); return later(DUR.missHoldMs, () => finished(S.run)); }   // no offer, not even disabled (R-63)
    finished(act.run);
  } catch (e) { if (g === S.gen) handleError(e); }
}

// ------------------------------------------------------------------------------------------------ continue / skip / leave
export async function continueOffer() {
  if (!S.offer || S.op || S.ui.offline) return;
  if (clock.serverNow() >= clock.ms(S.offer.deadline)) return;
  const offerId = S.offer.offerId;
  S.op = { key: S.keys['continue:' + offerId] ||= newKey(), status: 'pending', since: now(), operationId: null };
  go('G06.PENDING');
  const g = S.gen;
  try {
    let opv;
    try { opv = await api.continueOffer(offerId, S.op.key); }
    catch (e) { if (e instanceof ApiError && e.network) opv = await api.continueOffer(offerId, S.op.key); else throw e; }   // 3 s, then poll
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

function onOperation(opv) {
  const op = S.op || {};
  Object.assign(op, { operationId: opv.operationId, status: opv.status, chargeStatus: opv.chargeStatus, refundStatus: opv.refundStatus });
  S.op = op;
  if (opv.balance != null) S.balance = opv.balance;
  S.run = opv.run;
  if (opv.status === 'succeeded') {
    S.op = null; S.offer = null; S.fb = null; S.ui.afterContinue = true;
    toast(`ادامه ثبت شد؛ ${fa(cfg().continueRule.cost)} دیجی‌کوین کسر شد`);
    S.ui.morph = DUR.promoteMs; return go('G06.SUCCESS');      // same run, same reference, score and checkpoints kept (R-07)
  }
  if (opv.status === 'failed') {
    S.op = null;
    if (S.offer && clock.serverNow() < clock.ms(S.offer.deadline) && opv.run.state === 'OFFER_OPEN') return go('G06.FAILED_RETRY');
    return go('G06.FAILED_END');
  }
  if (opv.status === 'refunded') return go('G06.REFUNDED');
  if (opv.status === 'expired_unconfirmed') return go('G06.FAILED_END.UNCONFIRMED');
  if (S.view !== 'G06.UNKNOWN') go('G06.PENDING');
  pollOperation();
}

async function pollOperation() {   // 1 s backing off to 5 s; G06.UNKNOWN after 5 s; stop at the offer deadline (R-43)
  if (S.ui.polling) return;
  S.ui.polling = true;
  const g = S.gen; let delay = 1000;
  try {
    while (g === S.gen && S.op && ['G06.PENDING', 'G06.UNKNOWN'].includes(S.view)) {
      await new Promise(r => setTimeout(r, delay)); delay = Math.min(5000, delay * 1.6);
      if (g !== S.gen || !S.op) return;
      if (S.view === 'G06.PENDING' && now() - S.op.since > 5000) go('G06.UNKNOWN');
      if (!S.op.operationId) { await reconcileRun(); continue; }
      try { onOperation(await api.operation(S.op.operationId)); return; }
      catch (e) { if (!(e instanceof ApiError) || !e.network) return handleError(e); }
    }
  } finally { S.ui.polling = false; }
}

async function reconcileRun() {
  if (!S.run) return;
  try { const run = await api.run(S.run.runId); applyRun(run); } catch (e) { if (!(e instanceof ApiError) || !e.network) handleError(e); }
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

// ------------------------------------------------------------------------------------------------ checkpoint (UX-3, F-01)
let cpToken = 0, cpTimers = [];
function openCp(c) {
  const token = ++cpToken;
  S.ui.cp = c; S.ui.cpDue = null; S.ui.cpAt = now(); S.ui.cpAutoStarted = false;
  host.haptic('checkpoint');
  render();
  cpTimers.push(later(DUR.cpGuardMs, () => { if (cpToken === token && S.ui.cp) { render(); const b = $('.v3-cp__go', root); if (b) b.focus({ preventScroll: true }); } }));
  cpTimers.push(later(DUR.cpEntranceMs, () => {                // the automatic hold is measured once the content is readable
    if (cpToken !== token || !S.ui.cp) return;
    S.ui.cpAutoStarted = true; S.ui.cpAutoStartedAt = now();
    const bar = $('[data-cpauto]', root); if (bar) requestAnimationFrame(() => { bar.style.transform = 'scaleX(1)'; });
    cpTimers.push(later(cfg().checkpointAutoContinueMs, () => closeCp(token, 'auto')));
  }));
}
/** Tap and automatic completion converge here; the token makes the second one (or a stale timer) a no-op. */
export function closeCp(token = cpToken, source = 'tap') {
  if (!S.ui.cp || token !== cpToken) return;
  if (source === 'tap' && now() < S.ui.cpAt + DUR.cpGuardMs) return;   // the earning tap or a double tap cannot dismiss it
  cpToken++;
  cpTimers.forEach(clearTimeout); cpTimers = [];
  S.ui.cp = null; S.ui.guardUntil = now() + DUR.afterCpGuardMs;
  render();
}

// ------------------------------------------------------------------------------------------------ timers
let raf = 0, pollingDeadline = false;
function tickLoop() {
  if (raf) return;
  const step = () => {
    raf = 0;
    const v = S.view;
    if ((v === 'G03.QUESTION_ACTIVE' || v === 'G03.ANSWER_PENDING') && S.q) {
      const tm = $('[data-timer]', root), n = timerNow();
      if (tm) { tm.style.setProperty('--p', n.p); tm.classList.toggle('is-low', n.mode === 'low'); const b = tm.querySelector('b'); if (b && b.textContent !== n.t) { b.textContent = n.t; tm.setAttribute('aria-label', n.t + ' ثانیه'); } }
      if (n.rem <= 0 && !S.q.locked) { S.q.locked = true; if (!S.q.pick) render(); }   // keys lock at the input deadline
      if (n.rem <= 0 && !S.q.pick && !pollingDeadline) pollAfterDeadline(S.q);
      if (root.dataset.view !== stateId()) root.dataset.view = stateId();
      raf = requestAnimationFrame(step); return;
    }
    if (['G05.OFFER', 'G06.PENDING', 'G06.UNKNOWN', 'G06.FAILED_RETRY'].includes(v) && S.offer) {
      const rg = $('[data-ring]', root), rem = Math.max(0, clock.ms(S.offer.deadline) - clock.serverNow()), c = cfg();
      if (rg) { const s = fa(Math.ceil(rem / 1000)); rg.style.setProperty('--p', (rem / c.offerMs).toFixed(3)); rg.classList.toggle('is-low', rem <= 3000); const b = rg.querySelector('b'); if (b && b.textContent !== s) b.textContent = s; }
      if (rem <= 0 && !S.ui.offerEnded) { S.ui.offerEnded = true; later(300, () => { S.ui.offerEnded = false; reconcileRun(); }); }   // the ring never pauses; the server ends the offer at its deadline
      raf = requestAnimationFrame(step); return;
    }
    const cd = $('[data-countdown]', root);
    if (cd && S.boot) { const s = untilInstant(clock.ms(S.boot.quota.resetsAt) - clock.serverNow()); if (cd.textContent !== s) cd.textContent = s; raf = requestAnimationFrame(step); }
  };
  raf = requestAnimationFrame(step);
}

/** After the input deadline: ask the server at acceptDeadline + 300 ms, then every 500 ms up to 4 times, then G10.RESUMING. */
async function pollAfterDeadline(q) {
  if (pollingDeadline) return;
  pollingDeadline = true;
  const g = S.gen;
  try {
    const wait = clock.ms(q.acceptDeadline) + 300 - clock.serverNow();
    if (wait > 0) await new Promise(r => setTimeout(r, wait));
    for (let i = 0; i < 4 + 6; i++) {
      if (g !== S.gen || S.q !== q || q.pick) return;
      if (i === 4) S.ui.resuming = true, render();
      try {
        const run = await api.run(S.run.runId);
        if (g !== S.gen || S.q !== q) return;
        if (run.state !== 'QUESTION_OPEN' || run.question.questionId !== q.questionId) { S.ui.resuming = false; return applyMissFromState(run, q); }
      } catch (e) { if (!(e instanceof ApiError) || !e.network) return handleError(e); }
      await new Promise(r => setTimeout(r, 500));
    }
  } finally { pollingDeadline = false; }
}

// ------------------------------------------------------------------------------------------------ results
function finished(run) {
  S.gen++;
  host.setBackHandler(null);
  S.run = run; S.q = null; S.offer = null; S.op = null; S.ui.cp = null; S.ui.leave = false;
  S.ended = endedFrom(run);
  if (run.finishReason === 'inactive') return go('G10.INACTIVITY_END');
  if (run.finishReason === 'replaced') return go('G11.OTHER_DEVICE');
  if (run.finishReason === 'blocked') return go('G11.BLOCKED');
  showResult();
}

export async function showResult() {
  const runId = (S.ended && S.ended.runId) || (S.run && S.run.runId);
  if (!runId) return boot();
  S.result = null; S.share = null; S.ui.codeState = null; go('G07.LOADING');
  const g = S.gen;
  for (let i = 0; i < 4; i++) {                               // quiet retries with backoff
    try { S.result = await api.result(runId); break; }
    catch (e) { if (i === 3 || !(e instanceof ApiError) || !(e.network || e.status >= 500)) { if (g === S.gen) handleError(e); return; } await new Promise(r => setTimeout(r, 600 * (i + 1))); }
  }
  if (g !== S.gen) return;
  S.balance = S.result.balance ?? S.balance;
  if (S.boot) S.boot.quota = S.result.quota;
  go('G07.STANDARD');
  refreshResult(runId, g);
}

async function refreshResult(runId, g) {   // pending rewards: every 2 s up to 10 s; pending code: every 2 s up to 20 s (handoff)
  const started = now();
  while (g === S.gen && S.view.startsWith('G07.')) {
    const R = S.result;
    const rewardPending = [R.checkpointReward, R.streak && R.streak.reward].some(x => x && x.status === 'pending');
    const codePending = R.campaignCode && R.campaignCode.status === 'pending';
    const t = now() - started;
    if (!((rewardPending && t < 10000) || (codePending && t < 20000))) return;
    await new Promise(r => setTimeout(r, 2000));
    if (g !== S.gen) return;
    try { S.result = await api.result(runId); S.balance = S.result.balance ?? S.balance; render(); } catch (e) { return; }
  }
}

export async function showProducts() {
  const runId = S.result && S.result.runId; if (!runId) return;
  go('G08.LOADING');
  try { S.products = S.products && S.products.runId === runId ? S.products : await api.products(runId); go('G08.LIST'); }
  catch (e) { go('G08.ERROR'); }
}

export async function openProduct(link, id) {
  const ok = await host.openProduct(link);
  if (!ok) { toast('صفحه کالا باز نشد. دوباره امتحان کن.', true, 'G08.LINK_FAILED'); render(); }
}

export async function prepareShare() {
  if (S.ui.shareBusy || !S.result) return;
  S.ui.shareBusy = true; render();
  try { S.share = await api.share(S.result.runId); }
  catch (e) { S.share = null; toast('تصویر اشتراک ساخته نشد. دوباره امتحان کن.', true, 'G09.FAILED', true); }
  S.ui.shareBusy = false; render();
}

export async function sendShare() {
  const blob = await drawShareImage(S.share).catch(() => null);
  if (!blob) { S.share = null; toast('تصویر اشتراک ساخته نشد. دوباره امتحان کن.', true, 'G09.FAILED', true); return render(); }
  const r = await host.share(blob, 'قیمت‌شناس');
  if (r === 'unsupported') toast('اشتراک‌گذاری روی این دستگاه پشتیبانی نمی‌شه.', true, 'G09.FAILED', true);
  S.share = null; render();
}

/** Share image (proposed 1080×1920; no player name, R-12; image only, no invented deep link, R-56). */
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
  x.font = '700 52px Vazirmatn, sans-serif'; x.fillText(`استریک ${fa(s.streakDays)} روزه. تو چندتا می‌زنی؟`, 540, 1700);
  return new Promise(res => c.toBlob(res, 'image/png'));
}

export async function copyCode() {
  const R = S.result; if (!R || !R.campaignCode || !R.campaignCode.code) return;
  await host.copy(R.campaignCode.code);
  S.ui.codeState = 'copied'; render();
  const r = R; setTimeout(() => { if (S.result === r && S.ui.codeState === 'copied') { S.ui.codeState = null; render(); } }, DUR.copiedMs);
}

// ------------------------------------------------------------------------------------------------ connectivity & lifecycle
function setOffline(off) {
  S.ui.offline = off;
  if (off) return render();
  if (S.run && inRun(S.view)) {                              // reconcile inline; the screen stays (CP-26)
    S.ui.resuming = true; render();
    api.run(S.run.runId).then(run => { S.ui.resuming = false; applyRun(run); }).catch(e => { S.ui.resuming = false; render(); if (!(e instanceof ApiError) || !e.network) handleError(e); });
  } else render();
}

export function init(el) {
  root = el;
  window.addEventListener('online', () => setOffline(false));
  window.addEventListener('offline', () => setOffline(true));
  document.addEventListener('visibilitychange', () => {       // backgrounding is not leaving; revalidate on return
    if (document.visibilityState !== 'visible') return;
    clock.resetAfterSleep();
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
    help: () => { S.ui.help = 'REOPEN'; go('G02.REOPEN'); },
    'help-close': () => go(S.boot && S.boot.quota.playsLeft > 0 ? 'G01.READY' : 'G01.QUOTA_EXHAUSTED'),
    'help-done': async () => { try { await api.onboarding(newKey()); } catch (x) { /* shown again next time at worst */ } S.boot.firstVisit = false; go(S.boot.quota.playsLeft > 0 ? 'G01.READY' : 'G01.QUOTA_EXHAUSTED'); },
    resume: () => resume(S.boot.activeRun.runId),
    back: () => onBack(),
    'leave-no': () => { S.ui.leave = false; render(); },
    'leave-yes': leaveConfirmed,
    'see-result': () => finished(S.run),
    'show-result': showResult,
    recheck: () => { if (S.op && S.op.operationId) api.operation(S.op.operationId).then(onOperation).catch(() => {}); else reconcileRun(); },
    'to-start': boot, 'back-result': () => { S.ui.toast = null; go('G07.STANDARD'); },
    share: prepareShare, 'share-send': sendShare, 'share-close': () => { S.share = null; render(); },
    products: showProducts, 'open-product': () => openProduct(el.dataset.link, el.dataset.id),
    copy: copyCode,
    'exit-app': () => host.close(),
  };
  if (acts[a]) acts[a]();
}

export const debug = { S, stateId, render, closeCp, motion: () => motion.running() };
