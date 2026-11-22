-- V066: Search Plan Execution Leases & Concurrency Protection
-- Adds atomic lease ownership tokens and expiration tracking for multi-instance coordination.

ALTER TABLE search_plans
    ADD COLUMN execution_owner_token UUID,
    ADD COLUMN execution_lease_expires_at TIMESTAMPTZ;

-- Index for execution lease coordination and stale recovery
CREATE INDEX search_plans_lease_idx ON search_plans (active, last_execution_status, execution_lease_expires_at);

COMMENT ON COLUMN search_plans.execution_owner_token IS 'Cryptographic worker token claiming the current execution lease';
COMMENT ON COLUMN search_plans.execution_lease_expires_at IS 'Timestamp after which the execution lease is considered abandoned';
