// Game start. Loaded by host-boot.js (production entry, bundled with it) or by dev/reviewer.js (development host), each
// after configureHost(). No reviewer controls, fake data or test hooks are part of this bundle.
// Light theme only. The supplied Digikala font is loaded before the first text is painted (§2): until it is ready the
// page stays blank (white); a failed load is retried and the interface never falls back to another font.
import { init } from './game.js';

document.documentElement.setAttribute('data-theme', 'light');
const SAMPLE = 'قیمت‌شناس ۰۱۲۳۴۵۶۷۸۹ گرون‌تره';
async function fontReady() {
  for (let i = 0; ; i++) {
    try {
      const faces = await Promise.all([400, 500, 700, 800].map(w => document.fonts.load(`${w} 16px Digikala`, SAMPLE)));
      if (faces.every(f => f.length) && document.fonts.check('700 16px Digikala', SAMPLE)) return;
    } catch (e) { /* retried below */ }
    await new Promise(r => setTimeout(r, Math.min(4000, 500 * 2 ** i)));
  }
}
(document.fonts ? fontReady() : Promise.resolve()).then(() => {
  document.documentElement.classList.add('fonts-ready');
  init(document.getElementById('app'));
});
