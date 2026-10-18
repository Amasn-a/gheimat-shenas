// Persian formatting helpers. Amounts use Persian digits; Latin model codes stay as written (handoff title rules).
const PD = '۰۱۲۳۴۵۶۷۸۹';
export const fa = x => String(x).replace(/[0-9]/g, d => PD[d]);
export const money = n => fa(Math.round(n).toLocaleString('en-US'));
export const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
// Display rule for display_title (handoff): RLM keeps a Persian number after a Latin model code on the correct side; NBSP keeps a
// number with its unit and a short last word with the one before; U+2011 / U+2060 stop a line break inside a code at - or /.
export const fmtTitle = s => String(s).replace(/([A-Za-z0-9][^\s،]*[\s،]+)(?=[۰-۹])/g, '$1‏')
  .replace(/(^|\s|‏)([۰-۹][۰-۹.]*) (?=[^\s۰-۹A-Za-z])/g, '$1$2 ').replace(/ (\S{1,4})$/, ' $1')
  .replace(/(\S)-(?=\S)/g, '$1‑').replace(/(\S)\/(?=\S)/g, '$1/⁠');
export const title = s => esc(fmtTitle(s));
export function untilInstant(ms) {   // hh:mm:ss to a server instant (CP-30 countdown)
  const s = Math.max(0, Math.floor(ms / 1000));
  const z = v => String(v).padStart(2, '0');
  return fa(`${z(Math.floor(s / 3600))}:${z(Math.floor(s % 3600 / 60))}:${z(s % 60)}`);
}
