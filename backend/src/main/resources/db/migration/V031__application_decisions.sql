-- Phase 5: Automatic application decision engine
-- Records every decision the pipeline makes when a match reaches
-- the APPLY threshold — AUTO_APPLY, NEEDS_REVIEW, or SKIP —
-- together with the reason and the inputs that produced it.
-- This audit trail is what Phase 6 (review queue) and Phase 7
-- (auto-approval rules) will consume.

CREATE TABLE IF NOT EXISTS application_decisions (
    id               UUID         PRIMARY KEY,
    profile_id       UUID         NOT NULL,
    job_id           UUID         NOT NULL,
    match_score      INT          NOT NULL,
    recommendation   TEXT         NOT NULL,
    application_mode TEXT         NOT NULL,
    decision         TEXT         NOT NULL
        CHECK (decision IN ('AUTO_APPLY', 'NEEDS_REVIEW', 'SKIP')),
    reason           TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One decision per (profile, job): replays upsert rather than duplicate.
CREATE UNIQUE INDEX IF NOT EXISTS idx_application_decisions_profile_job
    ON application_decisions (profile_id, job_id);

-- Phase 6 will query "show me everything pending review for this user":
CREATE INDEX IF NOT EXISTS idx_application_decisions_review_queue
    ON application_decisions (profile_id, decision, created_at DESC)
    WHERE decision = 'NEEDS_REVIEW';
