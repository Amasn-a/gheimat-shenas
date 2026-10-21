-- Redesign 2026-10-21: each checkpoint pays its own reward when it is crossed (0/1/3/5 at 5/10/15/20), plus one
-- record reward per run when a record above 20 is matched. Runs pinned to an older configuration keep the old
-- "highest checkpoint at run end" rule (pay_on_crossing absent → false).

-- one reward per (run, reward key): the checkpoint id, or 'record:<target>'
ALTER TABLE pe_coin_operation ADD COLUMN reward_key TEXT;
UPDATE pe_coin_operation SET reward_key = 'run-end' WHERE type = 'CHECKPOINT_REWARD';
DROP INDEX pe_op_one_checkpoint_reward;
CREATE UNIQUE INDEX pe_op_one_run_reward ON pe_coin_operation (run_id, reward_key) WHERE type IN ('CHECKPOINT_REWARD','RECORD_REWARD');
ALTER TABLE pe_coin_operation DROP CONSTRAINT pe_coin_operation_type_check;
ALTER TABLE pe_coin_operation ADD CONSTRAINT pe_coin_operation_type_check
    CHECK (type IN ('CHARGE','CHECKPOINT_REWARD','STREAK_REWARD','REFUND','RECORD_REWARD'));

-- the all-time record when the run started: the frozen target of the record reward (null for older runs: no reward)
ALTER TABLE pe_run ADD COLUMN best_score_at_start INTEGER;

-- stored configuration versions get the new fields with their pre-redesign meaning (highest checkpoint at run end, no
-- record reward, no comparison percentage), so runs pinned to them finish under the rules they started with
UPDATE pe_config_version
SET payload = payload || jsonb_build_object('pay_on_crossing', false, 'record_reward', 0, 'record_reward_above', 0)
WHERE NOT payload ? 'pay_on_crossing';
UPDATE pe_config_version
SET payload = jsonb_set(payload, '{checkpoints}',
    (SELECT coalesce(jsonb_agg(c || jsonb_build_object('comparison_percent', null) ORDER BY o), '[]'::jsonb)
     FROM jsonb_array_elements(payload->'checkpoints') WITH ORDINALITY AS t(c, o)))
WHERE jsonb_typeof(payload->'checkpoints') = 'array'
  AND EXISTS (SELECT 1 FROM jsonb_array_elements(payload->'checkpoints') c WHERE NOT c ? 'comparison_percent');
