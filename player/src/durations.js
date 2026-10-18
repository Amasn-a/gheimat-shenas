// Motion and hold durations in one place (contract §11 "Motion tuning"). These are ENGINEERING STARTING POINTS tuned
// at 375×812, not owner-approved numbers, and they never extend a question or offer deadline.
export const DUR = {
  correctHoldMs: 1300,      // readable correct verdict before the promotion (range 900–1,400)
  missHoldMs: 1700,         // wrong / timeout: real price readable before the offer or the result (1,400–2,000)
  promoteMs: 540,           // answered card → reference slot (450–650)
  shrinkMs: 340,            // big reference → compact ticket on «نمایش بده» (250–400)
  verdictMs: 260,           // hidden tag → price plate, ask row → verdict
  cpGuardMs: 1200,          // checkpoint: taps ignored at first so the earning tap cannot dismiss it (1,000–1,500)
  cpEntranceMs: 400,        // automatic checkpoint hold is measured from here, once the content is readable
  afterCpGuardMs: 450,      // a tap that closes the checkpoint cannot fall through to «نمایش بده»
  imageSlowMs: 700,         // CP-22 hint when the comparison photo has not arrived by then
  toastMs: 2900,
  copiedMs: 2000,           // «کپی شد» (handoff value, proposed)
};
// The automatic checkpoint continuation itself comes from the run configuration (checkpoint_auto_continue_ms, F-01, 5,000 proposed).
