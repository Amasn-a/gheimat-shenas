// Shared-element transitions across re-renders (UX-2, UX-6). The screen is rebuilt on every state change; elements that
// are the same thing on both screens carry the same data-m key (a product's card, image, title and price; the ask row;
// the dock). Before replacing the DOM their boxes are measured; afterwards each animates from its old box to its new box
// (position and size together), cross-fading looks that differ. Photos keep their aspect ratio (contain-fit inside a box
// that changes shape). The root cause of the old jump was a remounted card animated from a fixed offset plus a
// checkpoint overlay opening in the same frame; both are gone. Durations come from durations.js; reduced motion skips it.
export const reducedMotion = () => !!(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches);
const EASE = 'cubic-bezier(.3,.7,.2,1)';   // brisk start, long smooth deceleration, no overshoot
const MPROPS = ['font-family', 'font-size', 'font-weight', 'line-height', 'color', 'letter-spacing', 'text-align', 'direction', 'background-color', 'border-radius', 'box-shadow'];
let MO = null;   // the running transition { layer, t0, ms, end }

export const running = () => !!MO;
export const remaining = () => MO ? Math.max(0, MO.ms - (performance.now() - MO.t0)) : 0;
export function end() { if (MO) MO.end(); }

function boxOf(root, el) {
  const p = root.getBoundingClientRect(), k = p.width / root.offsetWidth || 1, r = el.getBoundingClientRect();
  return { x: (r.left - p.left) / k, y: (r.top - p.top) / k, w: r.width / k, h: r.height / k };
}

/** Keyed elements as they look now; a running transition's ghosts stand in for their hidden targets. */
export function snapshot(root) {
  const out = new Map();
  root.querySelectorAll('[data-m]').forEach(el => {
    const k = el.dataset.m, gs = MO ? MO.layer.querySelectorAll(`[data-mghost="${CSS.escape(k)}"]`) : [], cs = getComputedStyle(el);
    const pa = el.parentElement.closest('[data-m]');
    out.set(k, { k, fx: el.dataset.mfx || 'fade', r: boxOf(root, gs.length ? gs[gs.length - 1] : el), html: el.outerHTML, pk: pa ? pa.dataset.m : null,
      st: Object.fromEntries(MPROPS.map(p => [p, cs.getPropertyValue(p)])) });
  });
  return out;
}

export function morph(root, before, ms, onEnd) {
  const after = new Map(); root.querySelectorAll('[data-m]').forEach(el => after.set(el.dataset.m, el));
  const layer = document.createElement('div'); layer.className = 'mo-layer'; layer.setAttribute('aria-hidden', 'true'); root.appendChild(layer);
  const anims = [], hid = [], R = r => ({ left: r.x + 'px', top: r.y + 'px', width: r.w + 'px', height: r.h + 'px' });
  const A = (el, kf, o) => { const a = el.animate(kf, { duration: ms, easing: EASE, fill: 'both', ...o }); anims.push(a); return a; };
  const ghost = (b, k = b.k) => {
    const t = document.createElement('div'); t.innerHTML = b.html; const g = t.firstElementChild;
    g.removeAttribute('data-m'); g.querySelectorAll('[data-m]').forEach(x => x.removeAttribute('data-m')); g.setAttribute('data-mghost', k);
    g.querySelectorAll('[id]').forEach(x => x.removeAttribute('id'));
    Object.assign(g.style, { position: 'absolute', margin: 0, boxSizing: 'border-box', animation: 'none', ...R(b.r) });
    MPROPS.forEach(p => g.style.setProperty(p, b.st[p])); layer.appendChild(g); return g;
  };
  for (const [k, b] of before) {
    const el = after.get(k);
    if (!el) {   // leaving: only the outermost keyed element animates, carrying its contents
      if (!b.pk || after.has(b.pk)) A(ghost(b), [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translateY(-14px) scale(.97)' }], { duration: ms * .55 });
      continue;
    }
    const n = boxOf(root, el), cs = getComputedStyle(el), nst = Object.fromEntries(MPROPS.map(p => [p, cs.getPropertyValue(p)]));
    if (b.fx === 'frame') {   // the card's surface travels, always opaque: new look underneath, old look on top fading away
      const surf = (st, kf) => { const g = document.createElement('div'); g.setAttribute('data-mghost', k);
        Object.assign(g.style, { position: 'absolute', background: st['background-color'], borderRadius: st['border-radius'], boxShadow: st['box-shadow'] });
        layer.appendChild(g); A(g, kf); };
      surf(nst, [{ ...R(b.r), opacity: 1 }, { opacity: 1, offset: .8 }, { ...R(n), opacity: 0 }]);
      surf(b.st, [{ ...R(b.r), opacity: 1 }, { opacity: 0, offset: .55 }, { ...R(n), opacity: 0 }]);
      A(el, [{ opacity: 0 }, { opacity: 0, offset: .6 }, { opacity: 1, offset: .85 }, { opacity: 1 }]);
    } else if (b.fx === 'box') {   // image box: contain-fit keeps the photo's proportions while the box changes shape
      const g = ghost(b); A(g, [{ ...R(b.r), borderRadius: b.st['border-radius'] }, { ...R(n), borderRadius: nst['border-radius'] }]);
      el.style.visibility = 'hidden'; hid.push(el);
    } else if (b.fx === 'text') {   // title / price: travels with the card, old look cross-fading into the new one (RTL: anchored right)
      const s = Math.max(.55, Math.min(1.8, n.h / (b.r.h || 1))), dx = (n.x + n.w) - (b.r.x + b.r.w), dy = n.y - b.r.y;
      const go = ghost(b); go.style.transformOrigin = '100% 0';
      A(go, [{ transform: 'none', opacity: 1 }, { opacity: 1, offset: .15 }, { opacity: 0, offset: .55 }, { transform: `translate(${dx}px,${dy}px) scale(${s})`, opacity: 0 }]);
      const gn = ghost({ ...b, r: n, html: el.outerHTML, st: nst }); gn.style.transformOrigin = '100% 0';
      A(gn, [{ transform: `translate(${-dx}px,${-dy}px) scale(${1 / s})`, opacity: 0 }, { opacity: 1, offset: .35 }, { transform: 'none', opacity: 1 }]);
      el.style.visibility = 'hidden'; hid.push(el);
    } else {   // same slot, different content: cross-fade in place
      A(ghost(b), [{ opacity: 1 }, { opacity: 0 }], { duration: ms * .5 });
      A(el, [{ opacity: 0 }, { opacity: 1 }], { delay: ms * .2, duration: ms * .8 });
    }
  }
  for (const [k, el] of after) {
    const pa = el.parentElement.closest('[data-m]');
    if (!before.has(k) && (!pa || before.has(pa.dataset.m)))   // arriving (or a new part of a card that stays)
      A(el, [{ opacity: 0, transform: 'translateY(12px) scale(.985)' }, { opacity: 1, transform: 'none' }], { delay: ms * .4, duration: ms * .6 });
  }
  const mo = { layer, t0: performance.now(), ms, end: () => {
    if (MO !== mo) return;
    clearTimeout(mo.tm); anims.forEach(a => a.cancel()); hid.forEach(e => { e.style.visibility = ''; }); layer.remove(); MO = null;
    if (onEnd) onEnd();
  } };
  mo.tm = setTimeout(mo.end, ms + 40); MO = mo;
}
