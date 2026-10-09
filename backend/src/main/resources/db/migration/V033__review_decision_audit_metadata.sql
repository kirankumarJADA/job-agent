-- Phase 6 hardening: reviewer attribution, rejection rationale and update timestamps.
-- Additive only: do not rewrite migrations that may already have run in production.
ALTER TABLE application_decisions
    ADD COLUMN IF NOT EXISTS reviewed_by TEXT,
    ADD COLUMN IF NOT EXISTS review_reason TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_application_decisions_owner_updated
    ON application_decisions (profile_id, updated_at DESC);
