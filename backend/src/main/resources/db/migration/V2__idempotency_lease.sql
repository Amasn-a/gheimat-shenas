-- An IN_PROGRESS idempotency row is a lease, not a permanent claim: if the process handling the request dies, a retry
-- with the same key takes the row over once lease_until has passed. owner identifies the attempt holding the lease, so
-- a slow original attempt can neither overwrite nor delete the row of the attempt that took it over.
ALTER TABLE pe_idempotency ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE pe_idempotency ADD COLUMN owner TEXT;
