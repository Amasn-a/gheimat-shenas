// Price Expert standalone admin (A00–A07). Vanilla ES module; talks only to the admin API (draft contract, see docs/API.md).
// Sign-in here is the DEVELOPMENT stand-in for organisation SSO (S-10). The session token lives in memory only.
const PREFIX = '/admin/v1/gamification/price-expert';
const $app = document.getElementById('app');

// ------------------------------------------------------------------------------------------------ formatting
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const FA = '۰۱۲۳۴۵۶۷۸۹';
const fa = n => n == null || n === '' || Number.isNaN(Number(n)) ? '—' : Number(n).toLocaleString('fa-IR', { maximumFractionDigits: 2 });
const faRaw = v => String(v ?? '').replace(/\d/g, d => FA[d]).replace('.', '٫');
const latin = s => String(s ?? '').replace(/[۰-۹]/g, d => FA.indexOf(d)).replace(/[٠-٩]/g, d => '٠١٢٣٤٥٦٧٨٩'.indexOf(d))
  .replace(/٫/g, '.').replace(/[,٬،\s]/g, '');
const DT = new Intl.DateTimeFormat('fa-IR', { timeZone: 'Asia/Tehran', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
const when = iso => iso ? DT.format(new Date(iso)) : '—';
// shortcut: Iran has used a fixed UTC+03:30 offset since DST was abolished (2022); use Intl conversion if that ever changes.
const TEHRAN_MS = 3.5 * 3600e3;
const fromTehran = v => v ? new Date(Date.parse(v + ':00Z') - TEHRAN_MS).toISOString() : null;
const toTehran = iso => iso ? new Date(Date.parse(iso) + TEHRAN_MS).toISOString().slice(0, 16) : '';
const clone = o => JSON.parse(JSON.stringify(o));
const uuid = () => crypto.randomUUID ? crypto.randomUUID()
  : ([1e7] + -1e3 + -4e3 + -8e3 + -1e11).replace(/[018]/g, c => (c ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> c / 4).toString(16));

// theme follows the system (the admin is a separate tool, not the host WebView)
const mq = matchMedia('(prefers-color-scheme: dark)');
const theme = () => { document.documentElement.dataset.theme = mq.matches ? 'dark' : 'light'; };
theme(); mq.addEventListener?.('change', theme);

// ------------------------------------------------------------------------------------------------ state
const S = {
  token: null, me: null, authMsg: null, signBusy: false, denied: null,
  section: (location.hash.slice(1) || 'A01'),
  ov: null, work: null, loadErr: null, localErr: {},
  saving: false, savePending: false, saveErr: false,
  banner: null, publishFailed: null, dialog: null, toast: null,
  pairs: null, pairsBusy: false,
  mon: null, alerts: [],
  ops: { q: '', loading: false, searched: false, err: null, runs: [], operations: [], player: null, sel: null, outcome: 'no_issue', note: '', message: '' },
  hist: { f: 'all', items: null, more: false, loading: false, err: null },
  camps: { list: null, form: null, errors: {}, busy: false, err: null },
};
const canEdit = () => S.me?.role === 'EDITOR';

// ------------------------------------------------------------------------------------------------ API
class ApiErr extends Error {
  constructor(status, body) { super(body?.message || 'error'); this.status = status; this.body = body || {}; this.code = this.body.code || (status === 0 ? 'NETWORK' : 'HTTP_' + status); }
}
async function api(method, path, body, key) {
  const headers = {};
  if (S.token) headers.Authorization = 'Bearer ' + S.token;
  if (method !== 'GET') { headers['Content-Type'] = 'application/json'; headers['Idempotency-Key'] = key || uuid(); }
  let r;
  try { r = await fetch(PREFIX + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store' }); }
  catch { throw new ApiErr(0, null); }
  let j = null;
  try { j = await r.json(); } catch { /* empty body */ }
  if (r.status === 401 && S.token && path !== '/session') { expire(); throw new ApiErr(401, j); }
  if (!r.ok) throw new ApiErr(r.status, j);
  return j;
}
function failText(e) {
  if (e.status === 0) return 'اتصال به سرور برقرار نشد. اتصال را بررسی و دوباره امتحان کن.';
  if (e.status === 403) return 'این کار برای نقش «بیننده» مجاز نیست.';
  if (e.status === 429) return 'درخواست‌ها زیاد شد؛ کمی بعد دوباره امتحان کن.';
  if (e.status >= 500) return 'خطای سرور؛ تغییری ثبت نشد. دوباره امتحان کن.';
  return e.body?.message ? 'درخواست پذیرفته نشد: ' + e.body.message : 'درخواست پذیرفته نشد.';
}
function expire() { S.token = null; S.me = null; S.authMsg = 'نشست شما تمام شد؛ دوباره وارد شو.'; render(); }
function toast(t) { S.toast = t; render(); clearTimeout(toast.h); toast.h = setTimeout(() => { S.toast = null; render(); }, 3200); }

// ------------------------------------------------------------------------------------------------ labels
const NAV = [['A01', 'بازی و سهمیه'], ['A02', 'چک‌پوینت‌ها'], ['A03', 'اقتصاد ادامه و استریک'], ['A04', 'سختی'],
  ['A05', 'عملیات و بررسی'], ['A07', 'کمپین‌ها'], ['A06', 'تاریخچه تغییرات']];
const TITLE = Object.fromEntries(NAV);
const LABELS = {
  plays_per_day: 'دست روزانه', inactivity_ms: 'پایان با بی‌فعالیتی', streak_reward: 'پاداش استریک',
  'continue_rule.enabled': 'ادامه با دیجی‌کوین', 'continue_rule.cost': 'هزینه هر ادامه', 'continue_rule.cap': 'سقف ادامه موفق در هر دست',
  'features.streak': 'استریک روزانه', 'features.share': 'اشتراک‌گذاری نتیجه', 'features.products': 'کالاهای این دست', 'features.haptics': 'لرزش',
  payout_alert_threshold: 'آستانه هشدار پرداخت روزانه', price_change_threshold_bp: 'آستانه تغییر قیمت کاتالوگ',
};
const CP_F = { required_correct: 'حد پاسخ درست', title: 'عنوان', reward: 'پاداش', enabled: 'فعال', badge_asset_id: 'نشان', id: 'شناسه' };
const BAND_F = { min_score: 'از پاسخ درست', min_bp: 'حداقل فاصله', max_bp: 'حداکثر فاصله', min_inclusive: 'شامل حداقل', max_inclusive: 'شامل حداکثر' };
const BAND_NAMES = ['آسان', 'متوسط', 'سخت', 'خیلی سخت', 'خبره'];
function label(k) {
  let m;
  if ((m = k.match(/^checkpoints\[(\d+)\]\.(\w+)$/))) return `چک‌پوینت ${fa(+m[1] + 1)} · ${CP_F[m[2]] || m[2]}`;
  if ((m = k.match(/^bands\[(\d+)\]\.(\w+)$/))) return `سختی سطح ${fa(+m[1] + 1)} · ${BAND_F[m[2]] || m[2]}`;
  return LABELS[k] || k;
}
function val(k, v) {
  if (v == null) return k.endsWith('max_bp') ? 'بدون سقف' : '—';
  if (typeof v === 'boolean') return v ? 'روشن' : 'خاموش';
  if (k.endsWith('_bp')) return fa(v / 100) + '٪';
  if (k.endsWith('_ms')) return fa(v / 60000) + ' دقیقه';
  return typeof v === 'number' ? fa(v) : String(v);
}
function section(k) {
  if (k.startsWith('checkpoints')) return 'A02';
  if (k.startsWith('continue_rule') || k === 'streak_reward') return 'A03';
  if (k.startsWith('bands')) return 'A04';
  return 'A01';
}
const ERR_FA = [
  [/at least 1$/, 'حداقل ۱'], [/positive integer/, 'باید عدد مثبت باشد'], [/must not be negative/, 'نمی‌تواند منفی باشد'],
  [/must be positive|greater than 0/, 'باید بیشتر از صفر باشد'], [/1–40 characters/, 'بین ۱ تا ۴۰ نویسه'], [/must increase/, 'باید از سطح قبل بیشتر باشد'],
  [/start at score 0/, 'سطح اول باید از ۰ شروع شود'], [/above the minimum/, 'باید بیشتر از حداقل باشد'], [/unique/, 'گام‌ها باید یکتا باشند'],
  [/enabled checkpoint/, 'دست‌کم یک چک‌پوینت فعال لازم است'], [/duplicate id/, 'شناسه تکراری است'], [/must be at most (\d+)/, 'حداکثر $1'],
];
const errKey = p => p.replace(/^continue_rule\./, 'continue.');
function errText(path, msg) {
  const m = path.match(/^checkpoints\[(\d+)\]\.required_correct$/);
  if (m && /ascending|unique/.test(msg)) {
    const prev = Math.max(0, ...S.work.checkpoints.slice(0, +m[1]).filter(c => c.enabled).map(c => c.required_correct));
    return prev && S.work.checkpoints[+m[1]].required_correct <= prev ? `باید بیشتر از ${fa(prev)} باشد` : 'گام‌ها باید یکتا و صعودی باشند';
  }
  for (const [re, t] of ERR_FA) { const r = msg.match(re); if (r) return t.replace('$1', fa(r[1])); }
  return msg;
}
const fieldErr = path => S.localErr[path] || (S.ov?.draft_errors || {})[errKey(path)];

// ------------------------------------------------------------------------------------------------ config editing
function getPath(o, p) { return p.split(/[.[\]]+/).filter(Boolean).reduce((a, k) => a?.[k], o); }
function setPath(o, p, v) {
  const ks = p.split(/[.[\]]+/).filter(Boolean);
  const last = ks.pop();
  ks.reduce((a, k) => a[k], o)[last] = v;
}
async function loadConfig() {
  try {
    S.ov = await api('GET', '/config');
    S.work = clone(S.ov.draft ? S.ov.draft.config : S.ov.active.config);
    S.loadErr = null; S.localErr = {};
  } catch (e) { if (e.status !== 401) S.loadErr = failText(e); }
}
async function saveDraft() {
  if (S.saving) { S.savePending = true; return; }
  S.saving = true; S.saveErr = false; S.publishFailed = null; render();
  try {
    const body = clone(S.work);
    const r = await api('POST', '/config/versions', body);
    S.ov.draft = { version: r.version, config: body };
    S.ov.draft_errors = r.errors; S.ov.draft_changes = r.changes; S.ov.draft_daily_cap = r.daily_cap;
  } catch (e) { if (e.status !== 401) S.saveErr = failText(e); }
  S.saving = false;
  if (S.savePending) { S.savePending = false; return saveDraft(); }
  render();
}
function onFieldChange(el) {
  const p = el.dataset.path, kind = el.dataset.kind;
  let v;
  if (kind === 'text') v = el.value.trim();
  else {
    const raw = latin(el.value);
    if (raw === '' && kind === 'pct-opt') v = null;
    else {
      const n = Number(raw);
      if (raw === '' || !Number.isFinite(n) || (kind === 'int' && !Number.isInteger(n))) { S.localErr[p] = 'یک عدد معتبر وارد کن'; render(); return; }
      v = kind === 'int' ? n : Math.round(n * 100);
    }
  }
  delete S.localErr[p];
  setPath(S.work, p, v);
  saveDraft();
}

// ------------------------------------------------------------------------------------------------ rendering helpers
const disabled = () => canEdit() ? '' : 'disabled';
function input(path, v, { kind = 'int', unit = '', wide = false, name = '' } = {}) {
  const err = fieldErr(path);
  const shown = kind === 'pct' || kind === 'pct-opt' ? (v == null ? '' : faRaw(v / 100)) : faRaw(v ?? '');
  return `<span class="qa-in"><input id="f:${esc(path)}" class="qa-input${wide ? ' qa-input--wide' : ''}${err ? ' qa-input--bad' : ''}" data-path="${esc(path)}" data-kind="${kind}" `
    + `${kind === 'text' ? '' : 'inputmode="decimal" dir="ltr"'} value="${kind === 'text' ? esc(v) : shown}" aria-label="${esc(name || label(path))}" aria-invalid="${!!err}" ${disabled()}>`
    + (unit ? `<span class="qa-unit">${unit}</span>` : '') + `</span>` + (err ? `<span class="qa-err" role="alert">${esc(errText(path, err))}</span>` : '');
}
const sw = (path, on, name) => `<button type="button" class="qa-switch" role="switch" aria-checked="${!!on}" aria-label="${esc(name)}" data-act="toggle" data-path="${esc(path)}" ${disabled()}></button>`;
const pill = (t, k = '') => `<span class="qa-pill${k ? ' qa-pill--' + k : ''}">${t}</span>`;
const btn = (t, act, { kind = 'secondary', attrs = '', dis = false } = {}) => `<button type="button" class="qs-btn qs-btn--${kind}" data-act="${act}" ${attrs} ${dis ? 'disabled' : ''}>${t}</button>`;
const skel = (w = '100%', h = 16) => `<span class="ad-skel" style="width:${w};height:${h}px"></span>`;
const bannerHtml = b => b ? `<div class="ad-banner${b.kind === 'ok' ? ' ad-banner--ok' : b.kind === 'warn' ? ' ad-banner--warn' : ''}" role="status">${b.text}`
  + `${b.link ? ` <button type="button" class="qs-btn qs-btn--text" data-act="nav" data-to="${b.link}">مشاهده در تاریخچه</button>` : ''}`
  + `<button type="button" class="qs-btn qs-btn--text" data-act="banner-close" aria-label="بستن">×</button></div>` : '';

// ------------------------------------------------------------------------------------------------ sign-in (A06 sign-in board)
function signIn() {
  if (S.denied) return `<div class="ad-signwrap"><div class="qa-signin">
    <div class="ad-lock neutral" aria-hidden="true">🔒</div><h1 class="ad-name">دسترسی نداری</h1>
    <p class="ad-sub">حساب «${esc(S.denied)}» به پنل قیمت‌شناس دسترسی ندارد. از مدیر سیستم بخواه نقش «ویرایشگر» یا «بیننده» را برایت فعال کند.</p>
    ${btn('خروج از حساب', 'signout', { kind: 'secondary' })}</div></div>`;
  return `<div class="ad-signwrap"><form class="qa-signin" data-form="signin" novalidate>
    <h1 class="ad-name">پنل قیمت‌شناس</h1>
    <p class="ad-sub">برای مدیریت تنظیمات بازی با حساب سازمانی وارد شو.</p>
    <p class="ad-devnote">ورود آزمایشی محیط توسعه؛ جایگزین ورود سازمانی (SSO) تا قرارداد آن تأیید شود.</p>
    ${S.authMsg ? `<div class="ad-banner" role="alert">${esc(S.authMsg)}</div>` : ''}
    <label class="qa-field" style="grid-template-columns:1fr;gap:6px"><span>نام کاربری</span><input class="qa-input" name="username" autocomplete="username" dir="ltr" required></label>
    <label class="qa-field" style="grid-template-columns:1fr;gap:6px"><span>رمز عبور</span><input class="qa-input" name="password" type="password" autocomplete="current-password" dir="ltr" required></label>
    <button class="qs-btn qs-btn--primary qs-btn--block${S.signBusy ? ' is-loading' : ''}" type="submit">ورود با حساب سازمانی</button>
    <p class="ad-foot">فقط کاربران مجاز · همه تغییرات با نام شما ثبت می‌شود</p></form></div>`;
}

// ------------------------------------------------------------------------------------------------ shell
function shell(main) {
  const a = S.ov?.active;
  const isConfig = ['A01', 'A02', 'A03', 'A04'].includes(S.section);
  return `<div class="qa-shell"><nav class="qa-nav" aria-label="بخش‌های پنل">
      <div class="qa-brand">قیمت‌شناس<small>پنل مدیریت</small></div>
      ${NAV.map(([id, t]) => `<a data-act="nav" data-to="${id}" class="${S.section === id ? 'on' : ''}" ${S.section === id ? 'aria-current="page"' : ''} href="#${id}">${t}<span class="id">${id}</span></a>`).join('')}
      <div class="ad-navfoot">نسخه فعال تنظیمات: <b>${a ? fa(a.version) : '…'}</b><br>منتشرشده ${a ? when(a.published_at) : '…'}</div>
    </nav><div class="qa-body">
      <header class="qa-top"><h1>${TITLE[S.section] || ''}</h1><div class="qa-who">
        ${!canEdit() ? pill('فقط مشاهده؛ بدون ویرایش') : ''}${pill(canEdit() ? 'ویرایشگر' : 'بیننده', canEdit() ? 'ok' : '')}<span>${esc(S.me.display_name || S.me.username)}</span>
        <button type="button" class="qs-btn qs-btn--text" data-act="signout">خروج</button></div></header>
      <main class="qa-main" id="main">${bannerHtml(S.banner)}${main}</main>
      ${isConfig && canEdit() && S.ov ? vbar() : ''}
    </div></div>${dialog()}${S.toast ? `<div class="ad-toast" role="status">${esc(S.toast)}</div>` : ''}`;
}

function vbar() {
  const d = S.ov.draft, act = S.ov.active, ch = S.ov.draft_changes || [], errs = Object.keys(S.ov.draft_errors || {}).length + Object.keys(S.localErr).length;
  let txt, right;
  if (S.publishFailed) {
    txt = `${pill('انتشار ناموفق', 'bad')} نسخه ${fa(d.version)} منتشر نشد؛ تغییرات شما ذخیره مانده است. دوباره امتحان کنید.`;
    right = btn('لغو تغییرات', 'discard') + btn('تلاش دوباره', 'publish-retry', { kind: 'primary' });
  } else if (S.saveErr) {
    txt = `${pill('ذخیره نشد', 'bad')} ${esc(S.saveErr)}`;
    right = btn('تلاش دوباره', 'save-retry', { kind: 'primary' });
  } else if (S.saving) {
    txt = `<span class="qs-spin" aria-hidden="true"></span> در حال ذخیره پیش‌نویس…`;
    right = btn('انتشار', 'noop', { kind: 'primary', dis: true });
  } else if (!d) {
    txt = `${pill('بدون تغییر')} با نسخه ${fa(act.version)} یکسان است`;
    right = btn('انتشار', 'noop', { kind: 'primary', dis: true });
  } else if (errs) {
    const first = Object.entries(S.localErr)[0] || Object.entries(S.ov.draft_errors)[0];
    txt = `${pill(fa(errs) + ' خطا', 'bad')} ${esc(label(first[0]))}: ${esc(errText(first[0], first[1]))}؛ تا رفع خطا امکان انتشار نیست.`;
    right = btn('لغو تغییرات', 'discard') + btn(`انتشار نسخه ${fa(d.version)}`, 'noop', { kind: 'primary', dis: true });
  } else if (!ch.length) {
    txt = `${pill('بدون تغییر')} پیش‌نویس با نسخه ${fa(act.version)} یکسان است`;
    right = btn('لغو پیش‌نویس', 'discard') + btn(`انتشار نسخه ${fa(d.version)}`, 'noop', { kind: 'primary', dis: true });
  } else {
    const list = ch.slice(0, 3).map(c => `${esc(label(c.field))} ${esc(val(c.field, c.before))} → ${esc(val(c.field, c.after))}`).join('، ');
    txt = `${pill('پیش‌نویس', 'warn')} ${fa(ch.length)} تغییر نسبت به نسخه ${fa(act.version)}: ${list}${ch.length > 3 ? '، …' : ''}`;
    right = btn('لغو تغییرات', 'discard') + btn(`انتشار نسخه ${fa(d.version)}`, 'publish', { kind: 'primary' });
  }
  return `<div class="qa-vbar" role="region" aria-label="نسخه تنظیمات"><div class="qa-vbar__txt">${txt}</div><div class="qa-btns">${right}</div></div>`;
}

// ------------------------------------------------------------------------------------------------ A00 loading / A01–A04
const loadingMain = () => `<div class="qa-card">${skel('30%', 22)}${skel()}${skel('80%')}${skel('60%')}</div><div class="qa-card">${skel('40%', 22)}${skel()}${skel('70%')}</div>`;
const configNote = () => `<div class="qa-note">تغییرات پس از انتشار فقط روی دست‌های جدید اعمال می‌شود؛ هر دست نسخه تنظیمات شروع خودش را نگه می‌دارد.</div>`;
function configGate(fn) {
  if (S.loadErr) return `<div class="ad-banner" role="alert">${esc(S.loadErr)} ${btn('تلاش دوباره', 'reload')}</div>`;
  if (!S.work) return loadingMain();
  return fn(S.work);
}
function a01(w) {
  const f = w.features;
  const row = (p, on, t, s) => `<div class="qa-row"><div><b>${t}</b><span>${s}</span></div>${sw(p, on, t)}</div>`;
  return configNote() + `<div class="qa-grid2"><section class="qa-card"><h2>سهمیه و زمان‌ها</h2>
    <div class="qa-field"><label for="f:plays_per_day">دست روزانه<small>مرز روز: ۰۰:۰۰ تهران · مقدار شروع: ۳ (R-33)</small></label><div>${input('plays_per_day', w.plays_per_day, { unit: 'دست' })}</div></div>
    <div class="qa-field"><label>پایان با بی‌فعالیتی بین سؤال‌ها<small>تصمیم مالک؛ در P0 ثابت</small></label><span class="qa-in"><input class="qa-input" readonly value="${faRaw(w.inactivity_ms / 60000)}" aria-label="پایان با بی‌فعالیتی"><span class="qa-unit">دقیقه</span></span></div>
    <div class="qa-field"><label>زمان پاسخ<small>مرجع؛ در P0 قابل تغییر نیست (R-45)</small></label><span class="qa-in"><input class="qa-input" readonly value="${faRaw((w.input_ms - 500) / 1000)}" aria-label="زمان پاسخ"><span class="qa-unit">ثانیه + ۵۰۰ میلی‌ثانیه حاشیه</span></span></div>
    <div class="qa-field"><label>فرصت پیشنهاد ادامه<small>مرجع؛ در P0 قابل تغییر نیست</small></label><span class="qa-in"><input class="qa-input" readonly value="${faRaw(w.offer_ms / 1000)}" aria-label="فرصت پیشنهاد ادامه"><span class="qa-unit">ثانیه</span></span></div>
    <div class="qa-field"><label>سقف پاداش روزانه هر حساب<small>محاسبه‌شده: بالاترین چک‌پوینت × دست روزانه + استریک</small></label><span>${fa(S.ov.draft ? S.ov.draft_daily_cap : S.ov.active_daily_cap)} دیجی‌کوین</span></div>
    </section><section class="qa-card"><h2>قابلیت‌های P0</h2>
    ${row('continue_rule.enabled', w.continue_rule.enabled, 'ادامه با دیجی‌کوین', 'پیشنهاد ۱۰ ثانیه‌ای بعد از شکست')}
    ${row('features.streak', f.streak, 'استریک روزانه', 'شمارش روزها و پاداش روزانه')}
    ${row('features.share', f.share, 'اشتراک‌گذاری نتیجه', 'تصویر نتیجه و اشتراک بومی')}
    ${row('features.products', f.products, 'کالاهای این دست', 'فهرست کالاها و لینک صفحه کالا')}
    ${row('features.haptics', f.haptics, 'لرزش', 'در صورت پشتیبانی اپ میزبان')}
    </section></div>`;
}
const BADGES = ['badge_3', 'badge_5', 'badge_7', 'badge_10'];
function a02(w) {
  const rows = w.checkpoints.map((c, i) => {
    const p = `checkpoints[${i}]`, n = (c.badge_asset_id.match(/\d+/) || [''])[0];
    return `<tr${fieldErr(p + '.required_correct') ? ' class="bad"' : ''}><td>${fa(i + 1)}</td>
      <td>${input(p + '.required_correct', c.required_correct)}</td>
      <td>${input(p + '.title', c.title, { kind: 'text', wide: true })}</td>
      <td><span class="ad-badgepick"><span class="qs-badge qs-badge--sm">${faRaw(n)}</span>
        <select data-path="${p}.badge_asset_id" data-kind="text" aria-label="نشان چک‌پوینت ${fa(i + 1)}" ${disabled()} style="border:0;background:transparent;color:inherit;font:inherit">
        ${[...new Set([...BADGES, c.badge_asset_id])].map(b => `<option ${b === c.badge_asset_id ? 'selected' : ''}>${esc(b)}</option>`).join('')}</select></span></td>
      <td>${input(p + '.reward', c.reward, { unit: 'کوین' })}</td>
      <td>${sw(p + '.enabled', c.enabled, 'فعال بودن چک‌پوینت ' + (i + 1))}</td>
      <td>${canEdit() ? `<button type="button" class="ad-iconbtn" data-act="cp-del" data-i="${i}" aria-label="حذف چک‌پوینت ${fa(i + 1)}">×</button>` : ''}</td></tr>`;
  }).join('');
  const top = (S.ov.draft_errors || {}).checkpoints;
  return `<div class="qa-note">فقط پاداش بالاترین چک‌پوینت هر دست پرداخت می‌شود. گام‌ها باید یکتا و صعودی باشند. حذف یک گام، تاریخچه و پاداش‌های قبلی را پاک نمی‌کند.</div>`
    + `<section class="qa-card"><div class="qa-table-wrap"><table class="qa-table"><thead><tr><th>ترتیب</th><th>حد پاسخ درست</th><th>عنوان</th><th>نشان</th><th>پاداش</th><th>فعال</th><th><span class="sr-only" hidden>حذف</span></th></tr></thead><tbody>${rows}</tbody></table></div>
    ${top ? `<span class="qa-err">${esc(errText('checkpoints', top))}</span>` : ''}
    ${canEdit() ? `<div>${btn('+ افزودن چک‌پوینت', 'cp-add', { kind: 'text' })}</div>` : ''}
    <p class="qs-note">نشان‌ها از مجموعه نشان‌های تأییدشده انتخاب می‌شوند؛ کمپین‌ها با شناسه چک‌پوینت (مثل cp_7) به آن ارجاع می‌دهند.</p></section>`;
}
function a03(w) {
  const c = w.continue_rule;
  return configNote() + `<div class="qa-grid2"><section class="qa-card"><h2>ادامه با دیجی‌کوین</h2>
    <div class="qa-row"><div><b>فعال</b><span>نمایش پیشنهاد ادامه پس از شکست</span></div>${sw('continue_rule.enabled', c.enabled, 'ادامه با دیجی‌کوین')}</div>
    <div class="qa-field"><label>نوع قیمت<small>ثابت (تصمیم R-20)</small></label><span class="ad-seg" role="group" aria-label="نوع قیمت"><button type="button" aria-pressed="true" disabled>ثابت</button><button type="button" aria-pressed="false" disabled>پلکانی</button></span></div>
    <div class="qa-field"><label for="f:continue_rule.cost">هزینه هر ادامه<small>در پیشنهاد باز یا دست فعال تغییر نمی‌کند</small></label><div>${input('continue_rule.cost', c.cost, { unit: 'دیجی‌کوین' })}</div></div>
    <div class="qa-field"><label for="f:continue_rule.cap">سقف ادامه موفق در هر دست</label><div>${input('continue_rule.cap', c.cap, { unit: 'بار' })}</div></div>
    <h2>استریک روزانه</h2>
    <div class="qa-field"><label for="f:streak_reward">پاداش استریک<small>یک بار برای اولین دست کامل هر روز${w.features.streak ? '' : ' · استریک در A01 خاموش است'}</small></label><div>${input('streak_reward', w.streak_reward, { unit: 'دیجی‌کوین' })}</div></div>
    </section><section class="qa-card"><h2>پیش‌نمایش برای بازیکن</h2><div class="ad-preview"><div class="ad-sheet" aria-label="پیش‌نمایش پیشنهاد ادامه">
      <h3>یه فرصت دیگه؟</h3><div class="cost"><span>هزینه</span><b><span class="qs-coin"></span>${fa(c.cost)} دیجی‌کوین</b></div>
      <div class="cost"><span>موجودی</span><b>موجودی واقعی بازیکن</b></div>
      <button type="button" class="qs-btn qs-btn--primary qs-btn--block" tabindex="-1" aria-hidden="true">ادامه با ${fa(c.cost)} دیجی‌کوین</button>
    </div></div>${c.enabled ? '' : '<p class="qs-note">ادامه خاموش است؛ پیشنهاد به بازیکن نمایش داده نمی‌شود.</p>'}</section></div>`;
}
function a04(w) {
  const rows = w.bands.map((b, i) => {
    const p = `bands[${i}]`, next = w.bands[i + 1], pc = S.pairs?.find(x => x.band_index === i);
    return `<tr><td>${fa(i + 1)} · ${BAND_NAMES[i] || 'سطح ' + fa(i + 1)}</td>
      <td>${input(p + '.min_score', b.min_score)}</td><td>${next ? fa(next.min_score - 1) : '—'}</td>
      <td><span class="ad-range">${b.min_inclusive ? 'از' : 'بیشتر از'} ${input(p + '.min_bp', b.min_bp, { kind: 'pct', name: `حداقل فاصله سطح ${i + 1}` })}
        ${b.max_bp == null && !canEdit() ? '٪ بدون سقف' : `تا ${input(p + '.max_bp', b.max_bp, { kind: 'pct-opt', unit: b.max_bp == null ? '٪ · بدون سقف' : b.max_inclusive ? '٪' : '٪ (کمتر از)', name: `حداکثر فاصله سطح ${i + 1}` })}`}</span></td>
      <td>${pc ? fa(pc.ordered_pairs) : (S.pairs ? '—' : '')}</td></tr>`;
  }).join('');
  return `<div class="qa-note">سختی با کم شدن فاصله قیمت دو کالا بالا می‌رود: فاصله = ۱۰۰ × (قیمت بیشتر ÷ قیمت کمتر − ۱). بازه‌ها به امتیاز دست وابسته‌اند، نه به چک‌پوینت‌ها؛ تغییر چک‌پوینت‌ها آن‌ها را تغییر نمی‌دهد. مقادیر شروع تأیید شده‌اند (R-41)؛ تنظیم فقط بعد از تست بازی (R-55).</div>
    <section class="qa-card"><div class="qa-table-wrap"><table class="qa-table"><thead><tr><th>سطح</th><th>از پاسخ درست</th><th>تا پاسخ درست</th><th>فاصله قیمت (درصد) · خالی = بدون سقف</th><th>جفت‌های ممکن در کاتالوگ فعال</th></tr></thead><tbody>${rows}</tbody></table></div>
    <div class="qa-btns">${btn(S.pairsBusy ? '<span class="qs-spin"></span> در حال شمارش…' : 'شمارش جفت‌های هر سطح', 'pairs', { kind: 'secondary', dis: S.pairsBusy })}</div>
    <p class="qs-note">انتخاب کالا: سطح با امتیاز فعلی دست تعیین می‌شود؛ گرون‌تر یا ارزون‌تر ۵۰/۵۰؛ هیچ کالایی در یک دست تکرار نمی‌شود؛ بعد از ادامه، کالای مرجع می‌ماند و کالای اشتباه دوباره نمی‌آید. شمارش جفت‌ها کمک می‌کند کمبود کالا در سطح‌های سخت پیش از انتشار دیده شود.</p></section>`;
}

// ------------------------------------------------------------------------------------------------ A05 operations and manual review
const RUN_STATE = { READY: 'آماده', QUESTION_OPEN: 'سؤال باز', AWAITING_OFFER: 'در انتظار پیشنهاد', OFFER_OPEN: 'پیشنهاد باز', PAYMENT_PENDING: 'پرداخت در جریان', FINISHED: 'پایان‌یافته' };
const OP_TYPE = { CHARGE: 'برداشت ادامه', REFUND: 'بازگشت وجه', CHECKPOINT_REWARD: 'پاداش چک‌پوینت', STREAK_REWARD: 'پاداش استریک' };
const OP_STATUS = { PENDING: ['در انتظار', 'warn'], UNKNOWN: ['نامعلوم', 'warn'], SUCCEEDED: ['موفق', 'ok'], FAILED: ['ناموفق', 'bad'], HELD: ['نگه‌داشته (سقف)', 'warn'], NEEDS_REVIEW: ['نیاز به بررسی', 'bad'] };
const OUTCOME = { no_issue: 'بدون مشکل', suspicious: 'مشکوک', needs_more_review: 'نیاز به بررسی بیشتر' };
const MON = { open_runs: 'دست‌های باز', payments_pending: 'پرداخت‌های در جریان', operations_in_flight: 'عملیات در جریان', operations_unknown: 'عملیات نامعلوم',
  operations_needing_review: 'نیاز به بررسی', credits_held: 'پاداش نگه‌داشته', refunds: 'بازگشت وجه', codes_pending: 'کد در انتظار صدور' };
const ALERT = { DAILY_CAP_HELD: 'پاداش بالای سقف روزانه نگه داشته شد', DAILY_PAYOUT_THRESHOLD: 'عبور از آستانه پرداخت روزانه', COIN_OPERATION_REJECTED: 'عملیات کوین رد شد',
  COIN_OPERATION_NEEDS_REVIEW: 'عملیات کوین نیاز به بررسی دارد', CAMPAIGN_CODE_NEEDS_REVIEW: 'صدور کد نیاز به بررسی دارد', ADMIN_PUBLISH: 'انتشار تنظیمات',
  ADMIN_CAMPAIGN: 'تغییر کمپین', ADMIN_GAME_OFF: 'خاموش شدن بازی', ADMIN_GAME_ON: 'روشن شدن بازی', ADMIN_UNBLOCK: 'رفع مسدودیت بازیکن' };
const short = id => id ? `${id.slice(0, 6)}…${id.slice(-3)}` : '—';

function a05() {
  const o = S.ops, on = S.ov ? S.ov.game_enabled : true;
  const kill = on
    ? `<section class="qa-danger"><h2>خاموش کردن کل بازی</h2><p class="qs-note" style="margin:0">شروع دست جدید متوقف می‌شود و بازیکنان پیام زیر را می‌بینند. دست‌های در جریان در فاصله بعدی بین سؤال‌ها پایان می‌یابند و پاداش چک‌پوینت حفظ می‌شود؛ پرداخت‌های باز ابتدا تعیین تکلیف می‌شوند (R-23).</p>
      <label class="qa-field" style="grid-template-columns:1fr;gap:6px"><span>پیام به بازیکن <small class="qs-note">(۱ تا ۱۲۰ نویسه؛ خالی = متن پیش‌فرض بازی)</small></span><textarea class="ad-text" data-model="ops.message" maxlength="120" ${disabled()} placeholder="بازی موقتاً در دسترس نیست. کمی بعد دوباره سر بزن.">${esc(o.message)}</textarea></label>
      ${canEdit() ? `<div>${btn('خاموش کردن بازی', 'kill', { kind: 'danger' })}</div>` : ''}</section>`
    : `<section class="qa-danger"><h2>بازی برای همه خاموش است</h2><p class="qs-note" style="margin:0">پیام فعلی: «${esc(S.ov.player_message || 'متن پیش‌فرض بازی')}»</p>${canEdit() ? `<div>${btn('روشن کردن بازی', 'game-on', { kind: 'primary' })}</div>` : ''}</section>`;
  const mon = S.mon ? `<section class="qa-card"><h2>پایش</h2><div class="ad-stats">${Object.entries(MON).map(([k, t]) => `<div class="ad-stat"><b>${fa(S.mon[k] ?? 0)}</b><span>${t}</span></div>`).join('')}</div>
      <dl class="ad-kv"><dt>سرویس دیجی‌کوین</dt><dd>${S.mon.coin_provider?.startsWith('fake') ? `${pill('جعلی درون‌پروژه‌ای', 'warn')} اتصال واقعی یا آزمایشی دیجی‌کوین برقرار نیست (R-44)` : esc(S.mon.coin_provider)}</dd>
      <dt>منبع کد تخفیف</dt><dd>${S.mon.campaign_code_provider?.startsWith('fake') ? `${pill('جعلی محیط توسعه', 'warn')} منبع واقعی کد هنوز تعیین نشده` : esc(S.mon.campaign_code_provider)}</dd>
      <dt>آستانه هشدار پرداخت</dt><dd>${S.mon.payout_alert_threshold == null ? 'در انتظار مقدار مالک (R-47)' : fa(S.mon.payout_alert_threshold) + ' دیجی‌کوین'}</dd></dl>
      ${S.alerts.length ? `<h2>هشدارها</h2><div class="qa-table-wrap"><table class="qa-table"><thead><tr><th>زمان</th><th>نوع</th><th>جزئیات</th></tr></thead><tbody>
        ${S.alerts.slice(0, 10).map(a => `<tr><td>${when(a.created_at)}</td><td>${ALERT[a.kind] || esc(a.kind)}</td><td class="ad-mono">${esc(a.details)}</td></tr>`).join('')}</tbody></table></div>` : '<p class="qs-note">هشدار بازی ندارد.</p>'}
    </section>` : `<section class="qa-card">${skel('30%', 22)}${skel()}</section>`;

  let results = '';
  if (o.loading) results = `<div class="qa-card">${skel()}${skel('70%')}</div>`;
  else if (o.err) results = `<div class="ad-banner" role="alert">${esc(o.err)} ${btn('تلاش دوباره', 'search')}</div>`;
  else if (o.searched && !o.runs.length && !o.operations.length && !o.player) results = `<div class="ad-empty"><b>نتیجه‌ای پیدا نشد</b>شناسه کاربر یا runId را بررسی کنید.</div>`;
  else if (o.searched) results = opsResults();
  return `${kill}<section class="qa-card"><h2>جستجوی دست و عملیات</h2><form class="ad-search" data-form="search" role="search">
      <input class="qa-input" data-model="ops.q" value="${esc(o.q)}" placeholder="شناسه کاربر (u_…) یا runId" dir="ltr" aria-label="شناسه کاربر یا runId">
      <button class="qs-btn qs-btn--secondary" type="submit">جستجو</button></form>${results}</section>${mon}`;
}
function opsResults() {
  const o = S.ops, p = o.player;
  const runs = o.runs.length ? `<div class="qa-table-wrap"><table class="qa-table"><thead><tr><th>runId</th><th>وضعیت</th><th>امتیاز</th><th>ادامه</th><th>بالاترین چک‌پوینت</th><th>شروع (روز)</th></tr></thead><tbody>
    ${o.runs.map(r => `<tr class="ad-tr-click" data-act="sel-run" data-id="${esc(r.run_id)}" aria-selected="${o.sel === r.run_id}" tabindex="0"><td class="ad-mono" title="${esc(r.run_id)}">${short(r.run_id)}</td>
      <td>${RUN_STATE[r.state] || esc(r.state)}${r.finish_reason ? ` <span class="qs-note">(${esc(r.finish_reason)})</span>` : ''}</td><td>${fa(r.score)}</td><td>${fa(r.continues_used)}</td>
      <td>${esc(r.highest_checkpoint_id || '—')}</td><td>${when(r.started_at)}</td></tr>`).join('')}</tbody></table></div>` : '';
  const ops = o.operations.length ? `<h2>عملیات کوین</h2><div class="qa-table-wrap"><table class="qa-table"><thead><tr><th>نوع</th><th>مبلغ</th><th>وضعیت</th><th>تلاش</th><th>runId</th><th>زمان</th><th></th></tr></thead><tbody>
    ${o.operations.map(x => { const [t, k] = OP_STATUS[x.status] || [x.status, '']; return `<tr><td>${OP_TYPE[x.type] || esc(x.type)}</td>
      <td>${fa(x.amount)}${x.held_amount ? ` <span class="qs-note">+ ${fa(x.held_amount)} نگه‌داشته</span>` : ''}</td><td>${pill(t, k)}${x.last_error ? `<br><span class="qs-note">${esc(x.last_error)}</span>` : ''}</td>
      <td>${fa(x.attempts)}</td><td class="ad-mono">${short(x.run_id)}</td><td>${when(x.created_at)}</td>
      <td>${canEdit() && x.status === 'NEEDS_REVIEW' ? btn('تلاش دوباره', 'retry-op', { kind: 'secondary', attrs: `data-id="${esc(x.operation_id)}"` }) : ''}</td></tr>`; }).join('')}</tbody></table></div>` : '';
  let player = '';
  if (p) {
    const sg = p.signals || {};
    player = `<section class="qa-card"><h2>بازیکن</h2><dl class="ad-kv"><dt>شناسه</dt><dd class="ad-mono">${esc(p.user_id)}</dd><dt>رکورد</dt><dd>${fa(p.record)}</dd><dt>استریک</dt><dd>${fa(p.streak_days)} روز</dd>
      <dt>آخرین بررسی</dt><dd>${p.reviews?.length ? OUTCOME[p.reviews[0].outcome] || esc(p.reviews[0].outcome) : '—'}</dd>
      <dt>وضعیت دسترسی</dt><dd>${p.blocked ? `${pill('مسدود از ' + when(p.blocked_at), 'bad')}<br><span class="qs-note">مسدود شده توسط ${esc(p.blocked_by)} · دلیل: ${esc(p.block_reason)}</span>` : pill('فعال', 'ok')}</dd></dl>
      <h2>نشانه‌ها <span class="qs-note">(فقط اطلاع؛ حساب علامت‌خورده عادی پرداخت می‌شود تا ویرایشگر مسدودش کند · R-60)</span></h2>
      <dl class="ad-kv"><dt>پاسخ‌ها</dt><dd>${fa(sg.answers)}</dd><dt>میانه زمان پاسخ</dt><dd>${sg.median_ms_from_t0 == null ? '—' : fa(Math.round(sg.median_ms_from_t0)) + ' میلی‌ثانیه'}</dd>
      <dt>سهم پاسخ زیر ۶۰۰ میلی‌ثانیه</dt><dd>${sg.share_under_600ms == null ? '—' : fa(Math.round(sg.share_under_600ms * 100)) + '٪'}</dd><dt>دستگاه‌ها</dt><dd>${fa(sg.devices)}</dd>
      <dt>حساب‌های دیگر روی همین دستگاه‌ها</dt><dd>${fa(sg.accounts_on_same_devices)}</dd><dt>علامت ریسک</dt><dd>${sg.risk_flag ? pill('دارد', 'warn') + ' <span class="qs-note">' + esc(sg.risk_reasons || '') + '</span>' : pill('ندارد')}</dd></dl>
      ${canEdit() ? `<div class="qa-btns">${p.blocked ? btn('رفع مسدودیت', 'unblock', { kind: 'secondary' }) : btn('مسدود کردن بازیکن', 'block', { kind: 'secondary', attrs: 'style="color:var(--brand-red-strong)"' })}</div>` : ''}</section>`;
  }
  const review = p && canEdit() ? `<section class="qa-card"><h2>ثبت بررسی دستی</h2><p class="ad-mono">${o.sel ? short(o.sel) + ' · ' : ''}${esc(p.user_id)}</p>
      <div class="ad-radio" role="radiogroup" aria-label="نتیجه بررسی">${Object.entries(OUTCOME).map(([k, t]) => `<label><input type="radio" name="outcome" value="${k}" data-model="ops.outcome" ${o.outcome === k ? 'checked' : ''}>${t}</label>`).join('')}</div>
      <textarea class="ad-text" data-model="ops.note" maxlength="1000" aria-label="یادداشت بررسی" placeholder="مثلاً: زمان پاسخ‌ها طبیعی است؛ الگوی تکراری دیده نشد.">${esc(o.note)}</textarea>
      <p class="qs-note">ثبت بررسی اثر مالی خودکار ندارد. در صورت نیاز، مدیر بازیکن را جداگانه مسدود می‌کند (R-22).</p>
      <div class="qa-btns">${btn('ثبت بررسی', 'review', { kind: 'primary' })}</div>
      ${p.reviews?.length ? `<div class="qa-table-wrap"><table class="qa-table"><thead><tr><th>زمان</th><th>نتیجه</th><th>مدیر</th><th>یادداشت</th></tr></thead><tbody>${p.reviews.map(r => `<tr><td>${when(r.created_at || r.at)}</td><td>${OUTCOME[r.outcome] || esc(r.outcome)}</td><td>${esc(r.reviewer || r.actor || '')}</td><td>${esc(r.note || '')}</td></tr>`).join('')}</tbody></table></div>` : ''}
    </section>` : '';
  return `${runs}${ops}<div class="ad-cols">${player}${review}</div>`;
}

// ------------------------------------------------------------------------------------------------ A06 change history
const HIST_F = [['all', 'همه', null], ['config', 'تنظیمات', 'config.publish'], ['cp', 'چک‌پوینت‌ها', 'config.publish'], ['game', 'خاموشی بازی', 'game.'], ['review', 'بررسی دستی', 'review.']];
function flat(o, pre = '', out = {}) {
  if (o && typeof o === 'object') { for (const [k, v] of Object.entries(o)) flat(v, Array.isArray(o) ? `${pre}[${k}]` : pre ? `${pre}.${k}` : k, out); }
  else out[pre] = o;
  return out;
}
function diffOf(e) {
  let b = {}, a = {};
  try { b = flat(JSON.parse(e.before_json || 'null') || {}); a = flat(JSON.parse(e.after_json || 'null') || {}); } catch { /* not JSON */ }
  return [...new Set([...Object.keys(b), ...Object.keys(a)])].filter(k => JSON.stringify(b[k]) !== JSON.stringify(a[k])).map(k => ({ k, b: b[k], a: a[k] }));
}
function histRow(e) {
  const actor = e.actor.startsWith('system:') ? 'سیستم' : esc(e.actor);
  let part = esc(e.action), ver = '—', before = '', after = '';
  const ver1 = (e.target || '').match(/^config:(\d+)$/);
  if (e.action === 'config.publish') {
    const d = diffOf(e);
    part = [...new Set(d.map(x => TITLE[section(x.k)]))].join('، ') || 'تنظیمات';
    ver = ver1 ? (e.before_json ? `${fa(ver1[1] - 1)} → ${fa(ver1[1])}` : fa(ver1[1])) : '—';
    before = d.slice(0, 4).map(x => `${esc(label(x.k))}: ${esc(val(x.k, x.b))}`).join('<br>');
    after = d.slice(0, 4).map(x => `${esc(label(x.k))}: ${esc(val(x.k, x.a))}`).join('<br>') + (d.length > 4 ? `<br><span class="qs-note">و ${fa(d.length - 4)} تغییر دیگر</span>` : '');
    if (!e.before_json) { before = '—'; after = 'مقادیر شروع'; }
  } else if (e.action.startsWith('game.')) {
    part = 'خاموشی بازی';
    let msg = ''; try { msg = JSON.parse(e.after_json || '{}').player_message || ''; } catch { /* plain */ }
    before = e.action === 'game.on' ? 'خاموش' : 'روشن'; after = (e.action === 'game.on' ? 'روشن' : 'خاموش') + (msg ? ` · «${esc(msg)}»` : '');
  } else if (e.action === 'review.create') {
    part = `بررسی دستی — <span class="ad-mono">${esc((e.target || '').replace('user:', ''))}</span>`;
    let o = {}; try { o = JSON.parse(e.after_json || '{}'); } catch { /* plain */ }
    after = OUTCOME[o.outcome] || esc(o.outcome || '');
  } else if (e.action === 'player.block' || e.action === 'player.unblock') {
    part = (e.action === 'player.block' ? 'مسدودسازی' : 'رفع مسدودیت') + ` — <span class="ad-mono">${esc((e.target || '').replace('user:', ''))}</span>`;
    before = e.action === 'player.block' ? 'فعال' : 'مسدود'; after = (e.action === 'player.block' ? 'مسدود' : 'فعال') + (e.reason ? ` · ${esc(e.reason)}` : '');
  } else if (e.action.startsWith('campaign.')) {
    part = 'کمپین‌ها'; let n = ''; try { n = JSON.parse(e.after_json || '{}').name || ''; } catch { /* plain */ }
    after = ({ 'campaign.create': 'ایجاد', 'campaign.update': 'ویرایش', 'campaign.end': 'پایان' })[e.action] + (n ? ` · ${esc(n)}` : '');
  } else if (e.action === 'config.discard') { part = 'لغو پیش‌نویس'; ver = ver1 ? fa(ver1[1]) : '—'; }
  else if (e.action === 'operation.retry') { part = 'تلاش دوباره عملیات'; after = `<span class="ad-mono">${esc(short((e.target || '').replace('operation:', '')))}</span>`; }
  else if (e.action === 'catalog.publish') { part = 'کاتالوگ کالا'; after = esc(e.reason || ''); }
  return `<tr><td>${when(e.at)}</td><td>${actor}</td><td>${part}</td><td>${ver}</td><td>${before || '—'}</td><td>${after || '—'}</td></tr>`;
}
function a06() {
  const h = S.hist;
  const chips = `<div class="ad-filters" role="group" aria-label="فیلتر بخش">${HIST_F.map(([k, t]) => `<button type="button" class="ad-chip" data-act="hist-f" data-f="${k}" aria-pressed="${h.f === k}">${t}</button>`).join('')}</div>`;
  let body;
  if (h.err) body = `<div class="ad-banner" role="alert">${esc(h.err)} ${btn('تلاش دوباره', 'hist-load')}</div>`;
  else if (!h.items) body = `<div class="qa-card">${skel()}${skel('80%')}${skel('60%')}</div>`;
  else {
    // shortcut: read-access and draft-save events are filtered client-side per page; add a server filter if pages become sparse
    let items = h.items.filter(e => !e.action.startsWith('admin.read.') && e.action !== 'config.draft');
    if (h.f === 'cp') items = items.filter(e => diffOf(e).some(x => x.k.startsWith('checkpoints')));
    body = items.length ? `<section class="qa-card"><div class="qa-table-wrap"><table class="qa-table"><thead><tr><th>زمان</th><th>مدیر</th><th>بخش</th><th>نسخه</th><th>قبل</th><th>بعد</th></tr></thead><tbody>${items.map(histRow).join('')}</tbody></table></div>
      ${h.more ? `<div>${btn(h.loading ? '<span class="qs-spin"></span>' : 'موارد قدیمی‌تر', 'hist-more', { kind: 'secondary', dis: h.loading })}</div>` : ''}</section>`
      : `<div class="ad-empty"><b>هنوز تغییری ثبت نشده</b>اولین انتشار تنظیمات یا بررسی دستی اینجا نمایش داده می‌شود.</div>`;
  }
  return `<div class="qa-note">فقط مشاهده؛ تاریخچه قابل ویرایش یا حذف نیست. دسترسی‌های خواندنی در سابقه حسابرسی سرور ثبت می‌شوند.</div>${chips}${body}`;
}

// ------------------------------------------------------------------------------------------------ A07 campaigns
function campStatus(c) {
  const now = Date.now();
  if (c.status === 'ENDED' || Date.parse(c.ends_at) <= now) return ['پایان‌یافته', ''];
  if (Date.parse(c.starts_at) <= now) return ['فعال', 'ok'];
  return ['زمان‌بندی‌شده', 'warn'];
}
function a07() {
  const C = S.camps, cps = (S.ov?.active?.config.checkpoints || []).filter(c => c.enabled);
  const list = !C.list ? `<div class="qa-card">${skel()}${skel('70%')}</div>`
    : C.list.length ? `<div class="qa-table-wrap"><table class="qa-table"><thead><tr><th>نام</th><th>بازه (تهران)</th><th>چک‌پوینت‌ها</th><th>کد</th><th>صادرشده / سقف</th><th>بودجه مصرف‌شده</th><th>وضعیت</th><th></th></tr></thead><tbody>
      ${C.list.map(c => { const [t, k] = campStatus(c); const editable = canEdit() && t === 'زمان‌بندی‌شده', live = canEdit() && t !== 'پایان‌یافته';
        return `<tr><td>${esc(c.name)}</td><td>${when(c.starts_at)} تا ${when(c.ends_at)}</td>
        <td>${c.code_checkpoint_ids.map(id => esc((cps.find(x => x.id === id) || {}).required_correct ?? id)).map(faRaw).join(' و ')}</td>
        <td>${fa(c.code_amount_toman)} تومان</td><td>${fa(c.codes_issued)} / ${fa(c.code_cap)}</td><td>${fa(c.budget_used_toman)} / ${fa(c.budget_toman)} تومان</td><td>${pill(t, k)}</td>
        <td class="qa-btns">${editable ? btn('ویرایش', 'camp-edit', { kind: 'secondary', attrs: `data-id="${esc(c.campaign_id)}"` }) : ''}${live ? btn('پایان', 'camp-end', { kind: 'text', attrs: `data-id="${esc(c.campaign_id)}"` }) : ''}</td></tr>`; }).join('')}</tbody></table></div>`
      : `<div class="ad-empty"><b>کمپینی ثبت نشده</b>پاداش پیش‌فرض دیجی‌کوین است.</div>`;
  const f = C.form, er = C.errors;
  const fe = k => er[k] ? `<span class="qa-err" role="alert">${esc(CAMP_ERR(er[k]))}</span>` : '';
  const form = f ? `<section class="qa-card"><h2>${f.id ? 'ویرایش کمپین' : 'کمپین جدید'}</h2><form data-form="camp" novalidate style="display:flex;flex-direction:column;gap:16px">
      <div class="qa-field"><label for="c-name">نام کمپین</label><div><input id="c-name" class="qa-input qa-input--wide" data-model="camps.form.name" value="${esc(f.name)}" maxlength="80">${fe('name')}</div></div>
      <div class="qa-field"><label>بازه<small>به وقت تهران</small></label><div class="ad-range"><input class="qa-input" type="datetime-local" data-model="camps.form.starts" value="${esc(f.starts)}" aria-label="شروع" style="width:auto">
        تا <input class="qa-input" type="datetime-local" data-model="camps.form.ends" value="${esc(f.ends)}" aria-label="پایان" style="width:auto">${fe('starts_at')}${fe('ends_at')}</div></div>
      <div class="qa-field"><label>چک‌پوینت‌های دارای کد</label><div><div class="ad-chk">${cps.map(c => `<label><input type="checkbox" data-act="camp-cp" value="${esc(c.id)}" ${f.cps.includes(c.id) ? 'checked' : ''}>${fa(c.required_correct)}</label>`).join('')}</div>${fe('code_checkpoint_ids')}</div></div>
      <div class="qa-field"><label>مبلغ کد<small>برای هر کمپین</small></label><div><span class="qa-in"><input class="qa-input" data-model="camps.form.amount" value="${esc(f.amount)}" inputmode="numeric" dir="ltr"><span class="qa-unit">تومان</span></span>${fe('code_amount_toman')}</div></div>
      <div class="qa-field"><label>اعتبار کد</label><div><span class="qa-in"><input class="qa-input" data-model="camps.form.validity" value="${esc(f.validity)}" inputmode="numeric" dir="ltr"><span class="qa-unit">روز پس از صدور</span></span>${fe('validity_days')}</div></div>
      <div class="qa-field"><label>سقف صدور کد<small>یک کد برای هر بازیکن در هر کمپین (R-37)</small></label><div><span class="qa-in"><input class="qa-input" data-model="camps.form.cap" value="${esc(f.cap)}" inputmode="numeric" dir="ltr"><span class="qa-unit">کد</span></span>${fe('code_cap')}</div></div>
      <div class="qa-field"><label>بودجه کمپین</label><div><span class="qa-in"><input class="qa-input" data-model="camps.form.budget" value="${esc(f.budget)}" inputmode="numeric" dir="ltr"><span class="qa-unit">تومان</span></span>${fe('budget_toman')}</div></div>
      <div class="qa-field"><label>منبع کد<small>تصمیم باز</small></label><span>${pill('در انتظار تصمیم', 'warn')} <span class="qs-note">در محیط توسعه کدهای جعلی با پیشوند DEV- صادر می‌شوند.</span></span></div>
      <div class="qa-field" style="grid-column:1/-1"><label for="c-terms">شرایط استفاده<small>به بازیکن نمایش داده می‌شود (۱ تا ۵۰۰ نویسه)</small></label><div><textarea id="c-terms" class="ad-text" data-model="camps.form.terms" maxlength="500">${esc(f.terms)}</textarea>${fe('terms')}</div></div>
      ${C.err ? `<div class="ad-banner" role="alert" style="grid-column:1/-1">${esc(C.err)}</div>` : ''}
      <div class="qa-btns" style="grid-column:1/-1"><button class="qs-btn qs-btn--primary${C.busy ? ' is-loading' : ''}" type="submit">ذخیره کمپین</button>${btn('لغو', 'camp-cancel')}</div>
    </form></section>` : (canEdit() ? `<div>${btn('+ کمپین جدید', 'camp-new', { kind: 'secondary' })}</div>` : '');
  return `<div class="qa-note">پاداش پیش‌فرض دیجی‌کوین است. در بازه یک کمپین فعال، چک‌پوینت‌های انتخاب‌شده علاوه بر دیجی‌کوین کد تخفیف هم می‌دهند. کد در نتیجه دست نمایش داده و به حساب بازیکن اضافه می‌شود.</div>
    <section class="qa-card"><h2>کمپین‌ها</h2>${list}</section>${form}`;
}
const CAMP_ERR = m => ({ '1–80 characters': 'بین ۱ تا ۸۰ نویسه', 'end must be after start': 'پایان باید بعد از شروع باشد', 'at least one checkpoint': 'دست‌کم یک چک‌پوینت',
  'must be positive': 'باید بیشتر از صفر باشد', 'must be above 0': 'باید بیشتر از صفر باشد', '1–500 characters': 'بین ۱ تا ۵۰۰ نویسه', 'overlaps another campaign': 'با کمپین دیگری هم‌پوشانی دارد' })[m] || m;

// ------------------------------------------------------------------------------------------------ dialogs (A00.PUBLISH_CONFIRM, A05.KILL_CONFIRM, A05.BLOCK_CONFIRM …)
function dialog() {
  const d = S.dialog;
  if (!d) return '';
  const okDis = okDisabled(d);
  const wrap = (title, body, okText, okKind = 'primary') => `<div class="ad-scrim" data-act="dlg-scrim"><div class="ad-dialog" role="dialog" aria-modal="true" aria-labelledby="dlg-t">
    <h2 id="dlg-t">${title}</h2>${body}${d.err ? `<div class="ad-banner" role="alert">${esc(d.err)}</div>` : ''}
    <div class="qa-btns"><button type="button" class="qs-btn qs-btn--${okKind}${d.busy ? ' is-loading' : ''}" data-act="dlg-ok" ${okDis ? 'disabled' : ''}>${okText}</button>${btn('انصراف', 'dlg-cancel')}</div></div></div>`;
  const reason = (req, ph) => `<label class="qa-field" style="grid-template-columns:1fr;gap:6px"><span>دلیل ${req ? '(الزامی؛ فقط برای تیم)' : '(اختیاری؛ فقط برای تیم)'}</span><textarea class="ad-text" data-model="dialog.reason" maxlength="500" placeholder="${ph || ''}">${esc(d.reason || '')}</textarea></label>`;
  if (d.kind === 'publish') {
    const ch = S.ov.draft_changes || [];
    return wrap(`انتشار نسخه ${fa(S.ov.draft.version)}؟`, `<p>این تغییرات فقط برای دست‌هایی که بعد از انتشار شروع می‌شوند اعمال می‌شود.</p>
      <ul>${ch.map(c => `<li>${esc(label(c.field))}: ${esc(val(c.field, c.before))} ← ${esc(val(c.field, c.after))}</li>`).join('')}</ul>
      ${S.ov.draft_daily_cap !== S.ov.active_daily_cap ? `<p>سقف پاداش روزانه هر حساب: ${fa(S.ov.active_daily_cap)} ← ${fa(S.ov.draft_daily_cap)} دیجی‌کوین</p>` : ''}${reason(false)}`, `انتشار نسخه ${fa(S.ov.draft.version)}`);
  }
  if (d.kind === 'kill') return wrap('بازی برای همه خاموش شود؟', `<p>شروع دست جدید متوقف می‌شود. دست‌های در جریان در فاصله بعدی بین سؤال‌ها پایان می‌یابند و پاداش بالاترین چک‌پوینت آن‌ها حفظ می‌شود. پرداخت‌های ادامه که باز هستند ابتدا تعیین تکلیف یا بازگردانده می‌شوند.</p>
      <p>پیام به بازیکن: «${esc(S.ops.message.trim() || 'متن پیش‌فرض بازی')}»</p>${reason(false)}`, 'خاموش کن', 'danger');
  if (d.kind === 'game-on') return wrap('بازی دوباره روشن شود؟', `<p>شروع دست جدید برای همه بازیکنان دوباره ممکن می‌شود.</p>${reason(false)}`, 'روشن کن');
  if (d.kind === 'block') return wrap('این بازیکن مسدود شود؟', `<p>بازیکن تا رفع مسدودیت توسط مدیر نمی‌تواند بازی کند و پیام «بازی غیرفعال شده است» را می‌بیند. دست در جریان او همان لحظه پایان می‌یابد و هیچ پاداشی از آن دست (چک‌پوینت و استریک) پرداخت نمی‌شود و آن دست در رکورد و استریک حساب نمی‌شود.</p>
      ${reason(true, 'مثلاً: الگوی پاسخ خودکار در ۳ دست متوالی')}
      <label class="qa-field" style="grid-template-columns:1fr;gap:6px"><span>ارجاع بررسی دستی (الزامی)</span><input class="qa-input qa-input--wide" data-model="dialog.ref" value="${esc(d.ref || '')}" dir="ltr" placeholder="شناسه بررسی یا تیکت"></label>`,
    'مسدود کن', 'danger');
  if (d.kind === 'unblock') return wrap('مسدودیت بازیکن رفع شود؟', `<p>بازیکن از دست بعدی دوباره می‌تواند بازی کند. دست‌های باطل‌شده قبلی برنمی‌گردند.</p>${reason(true)}`, 'رفع مسدودیت');
  if (d.kind === 'retry') return wrap('عملیات دوباره ارسال شود؟', `<p>این عملیات با همان کلید یکتا دوباره به سرویس کوین فرستاده می‌شود؛ پرداخت تکراری ایجاد نمی‌شود.</p>${reason(true)}`, 'تلاش دوباره');
  if (d.kind === 'camp-end') return wrap('کمپین پایان یابد؟', `<p>از همین لحظه کد جدیدی صادر نمی‌شود. کدهای صادرشده معتبر می‌مانند.</p>${reason(true)}`, 'پایان کمپین', 'danger');
  return '';
}

// confirm stays disabled until required fields are filled (reason is required for block/unblock/retry/end; the review reference for block)
function okDisabled(d) {
  if (['publish', 'kill', 'game-on'].includes(d.kind)) return false;
  return !(d.reason || '').trim() || (d.kind === 'block' && !(d.ref || '').trim());
}

// ------------------------------------------------------------------------------------------------ render
function render() {
  const fid = document.activeElement?.id;
  if (!S.token) { $app.innerHTML = signIn(); return; }
  const main = { A01: () => configGate(a01), A02: () => configGate(a02), A03: () => configGate(a03), A04: () => configGate(a04), A05: a05, A06: a06, A07: a07 }[S.section] || (() => '');
  $app.innerHTML = shell(main());
  if (fid) document.getElementById(fid)?.focus();
  if (S.dialog && !S.dialog.focused) { S.dialog.focused = true; $app.querySelector('.ad-dialog textarea, .ad-dialog input, .ad-dialog [data-act="dlg-ok"]')?.focus(); }
}

// ------------------------------------------------------------------------------------------------ section loading
async function enter(sec) {
  S.section = NAV.some(n => n[0] === sec) ? sec : 'A01';
  if (location.hash.slice(1) !== S.section) history.replaceState(null, '', '#' + S.section);
  S.banner = null;
  render();
  if (!S.ov) { await loadConfig(); render(); }
  if (S.section === 'A05') loadOps();
  if (S.section === 'A06') loadHist(true);
  if (S.section === 'A07') loadCamps();
}
async function loadOps() {
  try {
    const [m, a, ov] = await Promise.all([api('GET', '/monitoring'), api('GET', '/alerts'), api('GET', '/config')]);
    S.mon = m; S.alerts = a; S.ov.game_enabled = ov.game_enabled; S.ov.player_message = ov.player_message;
  } catch (e) { if (e.status !== 401) S.banner = { kind: 'bad', text: esc(failText(e)) }; }
  render();
}
async function loadHist(reset) {
  const h = S.hist;
  if (reset) { h.items = null; h.more = false; }
  h.loading = true; h.err = null; render();
  const area = HIST_F.find(x => x[0] === h.f)[2];
  const before = !reset && h.items?.length ? h.items[h.items.length - 1].id : 0;
  try {
    const page = await api('GET', `/audit?${area ? 'area=' + encodeURIComponent(area) + '&' : ''}${before ? 'before_id=' + before : ''}`);
    h.items = reset ? page : [...(h.items || []), ...page];
    h.more = page.length === 100;
  } catch (e) { if (e.status !== 401) h.err = failText(e); }
  h.loading = false; render();
}
async function loadCamps() {
  try { S.camps.list = await api('GET', '/campaigns'); } catch (e) { if (e.status !== 401) S.banner = { kind: 'bad', text: esc(failText(e)) }; }
  render();
}
async function search() {
  const o = S.ops, q = o.q.trim();
  if (!q) return;
  const isRun = !q.startsWith('u_') && /^[0-9a-z]{26}$/i.test(q);
  const k = isRun ? 'run_id' : 'user_id';
  Object.assign(o, { loading: true, err: null, searched: true, player: null, sel: isRun ? q : null });
  render();
  try {
    const [runs, ops] = await Promise.all([api('GET', `/runs?${k}=${encodeURIComponent(q)}`), api('GET', `/operations?${k}=${encodeURIComponent(q)}`)]);
    o.runs = runs; o.operations = ops;
    const user = isRun ? runs[0]?.user_id : q;
    if (user && (runs.length || ops.length || !isRun)) {
      try { o.player = await api('GET', `/players/${encodeURIComponent(user)}`); } catch (e) { if (e.status !== 404) throw e; }
      if (o.player && !runs.length && !ops.length && !o.player.created_at) o.player = null;
    }
  } catch (e) { if (e.status !== 401) o.err = failText(e); }
  o.loading = false; render();
}
async function refreshPlayer() {
  const u = S.ops.player?.user_id;
  if (!u) return;
  const k = S.ops.sel && !S.ops.q.startsWith('u_') ? 'run_id' : 'user_id', q = k === 'run_id' ? S.ops.sel : u;
  try {
    [S.ops.player, S.ops.runs, S.ops.operations] = await Promise.all([api('GET', `/players/${encodeURIComponent(u)}`),
      api('GET', `/runs?${k}=${encodeURIComponent(q)}`), api('GET', `/operations?${k}=${encodeURIComponent(q)}`)]);
  } catch (e) { if (e.status !== 401) toast(failText(e)); }
}

// ------------------------------------------------------------------------------------------------ dialog actions
async function dialogOk() {
  const d = S.dialog;
  if (!d || d.busy) return;
  d.busy = true; d.err = null; render();
  const reason = (d.reason || '').trim() || null;
  try {
    if (d.kind === 'publish') {
      const v = S.ov.draft.version;
      const r = await api('POST', `/config/versions/${v}/publish`, { version: v, expected_active_version: S.ov.active.version, reason }, d.key);
      S.dialog = null; S.publishFailed = null;
      S.banner = { kind: 'ok', text: `✓ منتشر شد · نسخه ${fa(r.active_version)} برای دست‌های جدید فعال است · ${when(r.published_at)} · ${esc(S.me.display_name || S.me.username)}`, link: 'A06' };
      await loadConfig();
    } else if (d.kind === 'kill' || d.kind === 'game-on') {
      const on = d.kind === 'game-on';
      const r = await api('POST', '/game/availability', { enabled: on, player_message: on ? null : (S.ops.message.trim() || null), reason }, d.key);
      S.dialog = null;
      S.banner = { kind: on ? 'ok' : 'warn', text: on ? 'بازی دوباره روشن شد.' : `بازی خاموش شد. ${fa(r.runs_closed)} دست بین سؤال‌ها بسته شد؛ ${fa(r.questions_still_open)} دست با سؤال باز بعد از پاسخ یا پایان مهلت بسته می‌شود؛ ${fa(r.payments_pending)} پرداخت باز تعیین تکلیف می‌شود.` };
      await loadOps();
    } else if (d.kind === 'block' || d.kind === 'unblock') {
      const u = S.ops.player.user_id;
      await api('POST', `/players/${encodeURIComponent(u)}/${d.kind}`, d.kind === 'block' ? { reason, review_reference: d.ref.trim() } : { reason }, d.key);
      S.dialog = null; toast(d.kind === 'block' ? 'بازیکن مسدود شد.' : 'مسدودیت رفع شد.');
      await refreshPlayer();
    } else if (d.kind === 'retry') {
      await api('POST', `/operations/${encodeURIComponent(d.id)}/retry`, { reason }, d.key);
      S.dialog = null; toast('عملیات دوباره در صف ارسال قرار گرفت.');
      await refreshPlayer();
    } else if (d.kind === 'camp-end') {
      await api('POST', `/campaigns/${encodeURIComponent(d.id)}/end`, { reason }, d.key);
      S.dialog = null; toast('کمپین پایان یافت.');
      await loadCamps();
    }
  } catch (e) {
    if (e.status === 401) return;
    if (d.kind === 'publish') {
      S.dialog = null;
      if (e.code === 'VERSION_CONFLICT') { S.banner = { kind: 'warn', text: 'نسخه فعال در این فاصله توسط مدیر دیگری تغییر کرد. صفحه با نسخه جدید به‌روز شد؛ تغییرات را دوباره بررسی و منتشر کن.' }; await loadConfig(); }
      else if (e.code === 'VALIDATION_FAILED') { S.banner = { kind: 'bad', text: 'پیش‌نویس خطا دارد و منتشر نشد.' }; await loadConfig(); }
      else S.publishFailed = { key: d.key, reason };            // A00.PUBLISH_FAILED: retry reuses the same idempotency key
    } else { d.busy = false; d.err = failText(e); }
  }
  render();
}

// ------------------------------------------------------------------------------------------------ events
document.addEventListener('submit', async ev => {
  const f = ev.target.dataset.form;
  if (!f) return;
  ev.preventDefault();
  if (f === 'signin') {
    const fd = new FormData(ev.target);
    S.signBusy = true; S.authMsg = null; render();
    try {
      const r = await api('POST', '/session', { username: String(fd.get('username') || '').trim(), password: String(fd.get('password') || '') });
      S.token = r.token;
      S.me = await api('GET', '/me');
      S.me.display_name = r.display_name;
      S.signBusy = false;
      enter(location.hash.slice(1) || S.section);
    } catch (e) {
      S.signBusy = false;
      if (e.status === 403) S.denied = String(fd.get('username') || '');
      else S.authMsg = e.code === 'INVALID_CREDENTIALS' ? 'نام کاربری یا رمز عبور درست نیست.' : failText(e);
      if (e.status !== 403) S.token = null;
      render();
    }
  } else if (f === 'search') search();
  else if (f === 'camp') saveCamp();
});

async function saveCamp() {
  const C = S.camps, f = C.form;
  const n = s => { const v = Number(latin(s)); return Number.isFinite(v) ? v : 0; };
  const body = { name: f.name.trim(), starts_at: fromTehran(f.starts), ends_at: fromTehran(f.ends), code_checkpoint_ids: f.cps, code_amount_toman: n(f.amount),
    validity_days: n(f.validity), terms: f.terms.trim(), code_cap: n(f.cap), budget_toman: n(f.budget) };
  C.errors = {}; C.err = null;
  if (!body.starts_at) C.errors.starts_at = 'زمان شروع لازم است';
  if (!body.ends_at) C.errors.ends_at = 'زمان پایان لازم است';
  if (Object.keys(C.errors).length) { render(); return; }
  C.busy = true; render();
  try {
    await api('POST', f.id ? `/campaigns/${encodeURIComponent(f.id)}` : '/campaigns', body, f.key);
    C.form = null; toast('کمپین ذخیره شد.'); await loadCamps();
  } catch (e) {
    if (e.status === 401) return;
    if (e.code === 'VALIDATION_FAILED' && e.body.fields) C.errors = e.body.fields;
    else if (e.code === 'CAMPAIGN_RUNNING') C.err = 'کمپین شروع شده یا پایان یافته و قابل ویرایش نیست.';
    else C.err = failText(e);
    f.key = uuid();                                              // a corrected body is a new request
  }
  C.busy = false; render();
}

function model(el) {
  const [root, ...rest] = el.dataset.model.split('.');
  let o = { ops: S.ops, camps: S.camps, dialog: S.dialog }[root];
  const last = rest.pop();
  for (const k of rest) o = o?.[k];
  if (!o) return;
  o[last] = el.value;
}

document.addEventListener('input', ev => {
  const el = ev.target;
  if (!el.dataset.model) return;
  model(el);
  const ok = el.closest('.ad-dialog') && $app.querySelector('[data-act="dlg-ok"]');
  if (ok) ok.disabled = okDisabled(S.dialog);
});
document.addEventListener('change', ev => {
  const el = ev.target;
  if (el.dataset.path && canEdit()) onFieldChange(el);
  else if (el.dataset.model && el.type === 'radio') model(el);
  else if (el.dataset.act === 'camp-cp') { const f = S.camps.form; f.cps = el.checked ? [...new Set([...f.cps, el.value])] : f.cps.filter(x => x !== el.value); }
});
document.addEventListener('keydown', ev => {
  if (ev.key === 'Escape' && S.dialog && !S.dialog.busy) { S.dialog = null; render(); }
  if ((ev.key === 'Enter' || ev.key === ' ') && ev.target.dataset?.act === 'sel-run') { ev.preventDefault(); ev.target.click(); }
});
window.addEventListener('hashchange', () => { if (S.token && location.hash.slice(1) !== S.section) enter(location.hash.slice(1)); });

document.addEventListener('click', async ev => {
  const t = ev.target.closest('[data-act]');
  if (!t || t.disabled) return;
  const act = t.dataset.act;
  if (act === 'nav') { ev.preventDefault(); enter(t.dataset.to); return; }
  if (act === 'dlg-scrim') { if (ev.target === t && !S.dialog?.busy) { S.dialog = null; render(); } return; }
  switch (act) {
    case 'signout':
      if (S.token) api('DELETE', '/session').catch(() => {});
      Object.assign(S, { token: null, me: null, denied: null, ov: null, work: null, authMsg: null, banner: null, dialog: null }); render(); break;
    case 'banner-close': S.banner = null; render(); break;
    case 'reload': S.loadErr = null; render(); await loadConfig(); render(); break;
    case 'toggle': setPath(S.work, t.dataset.path, !getPath(S.work, t.dataset.path)); saveDraft(); break;
    case 'save-retry': saveDraft(); break;
    case 'discard':
      try { await api('POST', `/config/versions/${S.ov.draft.version}/discard`, {}); S.publishFailed = null; S.saveErr = false; await loadConfig(); toast('تغییرات لغو شد.'); }
      catch (e) { if (e.status !== 401) toast(failText(e)); }
      render(); break;
    case 'publish': S.dialog = { kind: 'publish', key: uuid() }; render(); break;
    case 'publish-retry': S.dialog = { kind: 'publish', key: S.publishFailed.key, reason: S.publishFailed.reason || '' }; dialogOk(); break;
    case 'cp-add': {
      const cps = S.work.checkpoints, last = cps[cps.length - 1], step = (last ? last.required_correct : 0) + 1;
      let id = 'cp_' + step; while (cps.some(c => c.id === id)) id += '_n';
      cps.push({ id, required_correct: step, title: '', badge_asset_id: BADGES[BADGES.length - 1], reward: 0, enabled: true });
      saveDraft(); break;
    }
    case 'cp-del': S.work.checkpoints.splice(+t.dataset.i, 1); saveDraft(); break;
    case 'pairs':
      S.pairsBusy = true; render();
      try { S.pairs = await api('POST', '/difficulty/pair-counts', { bands: S.work.bands }); } catch (e) { if (e.status !== 401) toast(failText(e)); }
      S.pairsBusy = false; render(); break;
    case 'kill': S.dialog = { kind: 'kill', key: uuid() }; render(); break;
    case 'game-on': S.dialog = { kind: 'game-on', key: uuid() }; render(); break;
    case 'search': search(); break;
    case 'sel-run': S.ops.sel = t.dataset.id; render(); break;
    case 'review': {
      const o = S.ops;
      try {
        await api('POST', '/reviews', { user_id: o.player.user_id, run_id: o.sel, outcome: o.outcome, note: o.note.trim() || null });
        o.note = ''; toast('بررسی ثبت شد.'); await refreshPlayer();
      } catch (e) { if (e.status !== 401) toast(failText(e)); }
      render(); break;
    }
    case 'block': S.dialog = { kind: 'block', key: uuid(), ref: S.ops.player.reviews?.[0]?.review_id || '' }; render(); break;
    case 'unblock': S.dialog = { kind: 'unblock', key: uuid() }; render(); break;
    case 'retry-op': S.dialog = { kind: 'retry', key: uuid(), id: t.dataset.id }; render(); break;
    case 'hist-f': S.hist.f = t.dataset.f; loadHist(true); break;
    case 'hist-more': loadHist(false); break;
    case 'hist-load': loadHist(true); break;
    case 'camp-new': S.camps.form = { key: uuid(), id: null, name: '', starts: '', ends: '', cps: [], amount: '', validity: '', cap: '', budget: '', terms: '' }; S.camps.errors = {}; S.camps.err = null; render(); break;
    case 'camp-edit': {
      const c = S.camps.list.find(x => x.campaign_id === t.dataset.id);
      S.camps.form = { key: uuid(), id: c.campaign_id, name: c.name, starts: toTehran(c.starts_at), ends: toTehran(c.ends_at), cps: [...c.code_checkpoint_ids],
        amount: String(c.code_amount_toman), validity: String(c.validity_days), cap: String(c.code_cap), budget: String(c.budget_toman), terms: c.terms };
      S.camps.errors = {}; S.camps.err = null; render(); break;
    }
    case 'camp-cancel': S.camps.form = null; render(); break;
    case 'camp-end': S.dialog = { kind: 'camp-end', key: uuid(), id: t.dataset.id }; render(); break;
    case 'dlg-ok': dialogOk(); break;
    case 'dlg-cancel': if (!S.dialog?.busy) { S.dialog = null; render(); } break;
  }
});

render();
