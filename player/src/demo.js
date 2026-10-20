// Guide and onboarding demo (brief 2026-10-20): two invented products drawn as simple art, so the lesson never shows a
// catalog product a real question could use (D-5) and never touches the server. Prices are demo values, not data.
const MUG = '<svg viewBox="0 0 120 120" aria-hidden="true"><ellipse cx="58" cy="102" rx="34" ry="6" fill="#E7E9EE"/>' +
  '<path d="M84 46h8a14 14 0 0 1 0 28h-8" fill="none" stroke="#C42537" stroke-width="7"/>' +
  '<path d="M26 34h60v52a14 14 0 0 1-14 14H40a14 14 0 0 1-14-14Z" fill="#EF4056"/><path d="M26 34h60v10H26Z" fill="#D21E3B"/>' +
  '<path d="M38 52v30" stroke="#fff" stroke-width="5" stroke-linecap="round" opacity=".55"/>' +
  '<path d="M46 14q-6 8 0 14M58 12q-6 8 0 14M70 14q-6 8 0 14" stroke="#A3A9B3" stroke-width="3" fill="none" stroke-linecap="round"/></svg>';
const HEADPHONES = '<svg viewBox="0 0 120 120" aria-hidden="true"><ellipse cx="60" cy="104" rx="38" ry="6" fill="#E7E9EE"/>' +
  '<path d="M24 70V58a36 36 0 0 1 72 0v12" fill="none" stroke="#1E2129" stroke-width="8" stroke-linecap="round"/>' +
  '<rect x="14" y="62" width="22" height="34" rx="9" fill="#2F7FD6"/><rect x="84" y="62" width="22" height="34" rx="9" fill="#2F7FD6"/>' +
  '<rect x="20" y="68" width="10" height="22" rx="5" fill="#5AAEFF"/><rect x="90" y="68" width="10" height="22" rx="5" fill="#5AAEFF"/></svg>';

export const DEMO_FIRST = { displayTitle: 'ماگ سرامیکی (نمونه راهنما)', imageUrl: 'demo:first', price: 245000, art: MUG };
export const DEMO_SECOND = { displayTitle: 'هدفون بی‌سیم (نمونه راهنما)', imageUrl: 'demo:second', art: HEADPHONES };
export const DEMO_SECOND_PRICE = 890000;   // dearer: «گرون‌تر» is the right answer in the demo
