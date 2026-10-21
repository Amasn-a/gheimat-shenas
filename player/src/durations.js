// Motion and hold durations in one place (contract §11 "Motion tuning"). These are ENGINEERING STARTING POINTS tuned
// at 375×812, not owner-approved numbers, and they never extend a question or offer deadline.
export const DUR = {
  correctHoldMs: 1300,      // readable correct verdict before the promotion (range 900–1,400)
  missHoldMs: 1000,         // wrong / timeout: the verdict in place after the server's result, then the loss screen (§8: 700–1,000)
  promoteMs: 540,           // answered card → reference slot (450–650)
  shrinkMs: 340,            // big reference → compact ticket on «نمایش بده» (250–400)
  verdictMs: 260,           // hidden tag → price plate, ask row → verdict
  cpGuardMs: 600,           // checkpoint: «ادامه بده» ignores taps at first so a double tap on the answer cannot dismiss it
  afterCpGuardMs: 450,      // a tap that closes the checkpoint cannot fall through to «نمایش بده»
  imageSlowMs: 700,         // CP-22 hint when the comparison photo has not arrived by then
  toastMs: 2900,
  copiedMs: 2000,           // «کپی شد» (handoff value, proposed)
};
// The checkpoint celebration has no automatic continuation since the redesign (§10): it closes with «ادامه بده» only.
