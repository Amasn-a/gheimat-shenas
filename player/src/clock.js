// Server clock (handoff "Frontend platform rules"): serverNow() = performance.now() + offset taken from the lowest
// round-trip response. Date.now() is never used for game time. After the page was hidden the offset is re-learned,
// because monotonic clocks may pause during device sleep.
let offset = null, bestRtt = Infinity;

export function observe(serverTimeIso, sentAt, receivedAt) {
  const server = Date.parse(serverTimeIso);
  if (!Number.isFinite(server)) return;
  const rtt = receivedAt - sentAt;
  if (rtt <= bestRtt || offset === null) {
    bestRtt = rtt;
    offset = server + rtt / 2 - receivedAt;
  }
}

export const synced = () => offset !== null;
export const serverNow = () => performance.now() + (offset ?? (Date.now() - performance.now()));
export const ms = iso => Date.parse(iso);
export function resetAfterSleep() { bestRtt = Infinity; }
