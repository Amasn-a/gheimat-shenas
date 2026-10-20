-- Production (DDL applied out of band): create these with CREATE INDEX CONCURRENTLY to avoid blocking writes on pe_run.
-- The kill switch walks every active run directly (not the newest N runs of the history): keyset pagination by run_id.
CREATE INDEX pe_run_active_id ON pe_run (run_id) WHERE state <> 'FINISHED';
-- Entitlements that exhausted their retries are listed for the audited admin retry.
CREATE INDEX pe_entitlement_review ON pe_campaign_entitlement (created_at) WHERE status = 'NEEDS_REVIEW';
