-- Owner brief of 2026-10-20.
-- 1. Extra-life timing: the visible offer window (deadline) and the purchase arrival deadline (accept_deadline) are
--    separate instants. Offers created before this migration keep their old behaviour: arrival deadline = deadline.
ALTER TABLE pe_offer ADD COLUMN accept_deadline TIMESTAMPTZ;
UPDATE pe_offer SET accept_deadline = deadline WHERE accept_deadline IS NULL;
ALTER TABLE pe_offer ALTER COLUMN accept_deadline SET NOT NULL;
ALTER TABLE pe_offer ADD CONSTRAINT pe_offer_accept_after_visible CHECK (accept_deadline >= deadline);
-- Arrival of a purchase request recorded before its run lock (receipt-first, like pe_question.received_at).
ALTER TABLE pe_offer ADD COLUMN purchase_received_at TIMESTAMPTZ;

-- Published configuration versions are immutable; the two new timing fields are added with the values those versions
-- effectively had (purchase arrival deadline = visible window; no wait after it), so runs pinned to them keep behaving
-- as before. The startup step AdminService.ensureTimingContract publishes a new version with the 2026-10-20 values.
UPDATE pe_config_version
SET payload = payload || jsonb_build_object('offer_accept_ms', (payload->>'offer_ms')::bigint, 'payment_resolution_ms', 0)
WHERE NOT payload ? 'offer_accept_ms';

-- 2. Daily reward claim: the streak reward earned by the first finished run of a start day waits as CLAIMABLE until the
--    player claims it in the daily reward modal; the claim turns it into a normal PENDING credit.
ALTER TABLE pe_coin_operation DROP CONSTRAINT pe_coin_operation_status_check;
ALTER TABLE pe_coin_operation ADD CONSTRAINT pe_coin_operation_status_check
    CHECK (status IN ('CLAIMABLE','PENDING','UNKNOWN','SUCCEEDED','FAILED','HELD','NEEDS_REVIEW'));
ALTER TABLE pe_coin_operation ADD COLUMN claimed_at TIMESTAMPTZ;
CREATE INDEX pe_op_claimable ON pe_coin_operation (user_id) WHERE status = 'CLAIMABLE';

-- 3. One Idempotency-Key names one operation of one caller: reusing it on another path is refused, not a new operation.
CREATE UNIQUE INDEX pe_idempotency_one_key ON pe_idempotency (scope, idem_key);
