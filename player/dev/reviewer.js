// DEVELOPMENT HOST + REVIEWER CONTROLS. Not part of the production bundle. Talks to the real backend; the only fakes are
// the backend's in-project DigiCoin provider (scripted through /dev/v1) and this page's stand-in for the Super App host.
import { configureHost } from '../src/host.js';

const params = new URLSearchParams(location.search);
const store = { get: k => { try { return sessionStorage.getItem(k); } catch (e) { return null; } }, set: (k, v) => { try { sessionStorage.setItem(k, v); } catch (e) { /* ignore */ } } };
const user = params.get('user') || store.get('pe.dev.user') || 'player1';
store.set('pe.dev.user', user);
const device = params.get('device') || store.get('pe.dev.device') || crypto.randomUUID();
store.set('pe.dev.device', device);
let themeOverride = params.get('theme');

configureHost({
  getToken: async () => 'dev.' + user,          // DEVELOPMENT token format accepted only by the dev backend
  refreshToken: async () => 'dev.' + user,
  deviceId: () => device,
  theme: () => themeOverride || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
  haptic: kind => console.info('[dev host] haptic', kind),
  openProduct: async url => { console.info('[dev host] open product', url); return true; },
  close: () => console.info('[dev host] close WebView'),
});

const escAttr = v => String(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);   // URL values are untrusted, even in development
const panel = document.createElement('aside');
panel.className = 'pe-devpanel';
panel.innerHTML = `
<header><span><b class="tag">DEV</b> reviewer controls</span><button id="dvMin">–</button></header>
<div>state <span class="state" id="dvState">…</span></div>
<small>Real backend · fake DigiCoin (in-project) · dev login · dev host stand-in</small>
<fieldset><legend>Player</legend>
  user <input type="text" id="dvUser" value="${escAttr(user)}"> <button id="dvUserGo">switch</button><br>
  device <code>${escAttr(device.slice(0, 8))}</code> <button id="dvNewDevice">new device</button>
</fieldset>
<fieldset><legend>Fake DigiCoin (backend)</legend>
  balance <input type="number" id="dvBal" value="120" min="0"> <button id="dvBalGo">set</button><br>
  next charge <select id="dvCharge"><option value="">normal</option><option value="failed">rejected</option><option value="unknown_applied">applied, response lost</option><option value="late_success">confirms late (15 s)</option><option value="unknown_not_applied">lost, not applied</option></select>
  <button id="dvChargeGo">script</button><br>
  balance read <select id="dvBalRead"><option value="">normal</option><option value="unreadable">unreadable</option></select> <button id="dvBalReadGo">script</button>
</fieldset>
<fieldset><legend>View</legend>
  theme <select id="dvTheme"><option value="">follow system</option><option value="light">light</option><option value="dark">dark</option></select>
</fieldset>`;
document.body.appendChild(panel);
if (params.get('panel') === '0') panel.hidden = true;
const $ = s => panel.querySelector(s);
const dev = (path, body) => fetch('/dev/v1/price-expert' + path, body ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}).then(r => r.json());
$('#dvMin').onclick = () => panel.classList.toggle('min');
$('#dvUserGo').onclick = () => { store.set('pe.dev.user', $('#dvUser').value.trim()); location.search = ''; };
$('#dvNewDevice').onclick = () => { store.set('pe.dev.device', crypto.randomUUID()); location.reload(); };
$('#dvBalGo').onclick = () => dev('/fake-coin/u_' + user, { balance: Number($('#dvBal').value) });
$('#dvChargeGo').onclick = () => { const o = $('#dvCharge').value; if (o) dev('/fake-coin/u_' + user, { script: [{ op: 'charge', outcome: o, late_ms: 15000 }] }); };
$('#dvBalReadGo').onclick = () => { const o = $('#dvBalRead').value; if (o) dev('/fake-coin/u_' + user, { script: [{ op: 'balance', outcome: o }] }); };
$('#dvTheme').onchange = e => { themeOverride = e.target.value || null; document.documentElement.setAttribute('data-theme', themeOverride || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')); };
dev('/fake-coin/u_' + user).then(r => { if (r.balance != null) $('#dvBal').value = r.balance; }).catch(() => {});
setInterval(() => { const v = document.getElementById('app').dataset.view; if (v) $('#dvState').textContent = v; }, 200);

await import('../src/main.js');
