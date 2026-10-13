-- Phase 7: configurable per-user auto-approval rules.
--
-- One rule per profile (the owner). A rule NEVER overrides hard stops,
-- required-field failures or artifact-integrity failures - those are
-- enforced downstream at plan build, worker execution and the approval
-- precondition, and this table can only tighten or relax the score
-- threshold and switch automatic application creation on/off.
--
-- enabled = false (the default for a new row) means the user has explicitly
-- DISABLED automatic approval: every APPLY match queues for human review,
-- even in CONTROLLED_AUTO. Absence of a row leaves the Phase 5 defaults
-- untouched (ASSISTED threshold 85, CONTROLLED_AUTO auto-applies).

CREATE TABLE IF NOT EXISTS user_approval_rules (
    id                   UUID        PRIMARY KEY,
    profile_id           UUID        NOT NULL UNIQUE REFERENCES profiles(id) ON DELETE CASCADE,
    auto_approve_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    min_score            INT         NOT NULL DEFAULT 85 CHECK (min_score BETWEEN 1 AND 100),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
