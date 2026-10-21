/* Icon set of the redesign (stand-ins: no official Digikala icon package was supplied, R-06/R-26). 24×24 strokes.
   Direction rules (redesign §2): back points right (the RTL start); forward/continue points left; the play triangle keeps
   its standard right-pointing shape. Nothing here is mirrored by CSS, so DOM order and drawing never flip twice. */
const S = (d, w = 2) => `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${d}</svg>`;
export const I = {
  back: S('<path d="M9 5l7 7-7 7"/>', 2.4),               // points right
  fwd: S('<path d="M15 5l-7 7 7 7"/>', 2.4),               // points left
  play: '<svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M7 4.5v15l12.5-7.5z" fill="currentColor"/></svg>',
  up: S('<path d="M5 15l7-7 7 7"/>', 2.4),
  down: S('<path d="M5 9l7 7 7-7"/>', 2.4),
  home: S('<path d="M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z"/>'),
  check: S('<path d="M5 12l5 5 9-10"/>', 2.6),
  x: S('<path d="M6 6l12 12M18 6L6 18"/>', 2.6),
  lock: S('<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>'),
  clock: S('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>'),
  wifi: S('<path d="M2 8.5a16 16 0 0 1 5-3M22 8.5a16 16 0 0 0-9-3.4M5 12.5a11 11 0 0 1 4-2.3M19 12.5a11 11 0 0 0-3-1.9M8.5 16a6 6 0 0 1 7 0M12 20h.01M3 3l18 18"/>'),
  refresh: S('<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>'),
  alert: S('<path d="M12 3l10 18H2zM12 10v5M12 18h.01"/>'),
  info: S('<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5h.01"/>'),
  user: S('<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>'),
  power: S('<path d="M12 3v8M6.3 7.3a8 8 0 1 0 11.4 0"/>'),
  devices: S('<rect x="3" y="4" width="13" height="10" rx="1.5"/><rect x="15" y="9" width="6" height="11" rx="1.5"/><path d="M7 18h6"/>'),
  undo: S('<path d="M9 5L4 10l5 5"/><path d="M4 10h10a5 5 0 0 1 0 10h-3"/>', 2.4),
};
