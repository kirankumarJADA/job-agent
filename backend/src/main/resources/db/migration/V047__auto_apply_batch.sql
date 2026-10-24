-- Phase 5 (Robin x Tsenta continuation): controlled Auto Apply batch workflow.
--
-- This is deliberately SEPARATE from the Phase 5/6/7 continuous decision
-- engine (application_decisions, user_approval_rules) that already runs
-- automatically on every JOB_MATCHED APPLY event. That engine is unconditional
-- background behaviour driven by application_mode; this table set backs an
-- explicit, user-triggered, BOUNDED sweep over the candidate's existing
-- matched-job backlog, with its own opt-in, its own candidate-configurable
-- daily cap and its own auditable run history. Both paths end at the exact
-- same primitives (ApplicationPipelineService#createApplicationFromMatch,
-- the existing async CV/cover-letter/answer preparation pipeline) and both
-- stop well short of submission: REAL_SUBMIT stays hard-stopped and every
-- created application still requires human review/approval downstream.

-- One settings row per candidate. Absence means the batch feature has never
-- been configured; enabled defaults to FALSE so saving nothing never starts
-- automatic activity.
CREATE TABLE auto_apply_settings (
    profile_id       UUID        PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    enabled          BOOLEAN     NOT NULL DEFAULT FALSE,
    min_score        INT         NOT NULL DEFAULT 70 CHECK (min_score BETWEEN 1 AND 100),
    daily_cap        INT         NOT NULL DEFAULT 10 CHECK (daily_cap BETWEEN 1 AND 50),
    max_batch_size   INT         NOT NULL DEFAULT 25 CHECK (max_batch_size BETWEEN 1 AND 50),
    remote_type      TEXT        CHECK (remote_type IS NULL OR remote_type IN ('REMOTE','HYBRID','ONSITE','UNKNOWN')),
    location         TEXT,
    employment_type  TEXT,
    salary_min       NUMERIC(12,2),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per explicit batch invocation. Bounded and auditable: every run has
-- a start, an end (or a recorded failure) and the exact outcome counts the
-- task requires, so "a run happened" is never inferred from a queued task.
CREATE TABLE auto_apply_runs (
    id                       UUID        PRIMARY KEY,
    profile_id               UUID        NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    status                   TEXT        NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    jobs_considered          INT         NOT NULL DEFAULT 0,
    jobs_eligible            INT         NOT NULL DEFAULT 0,
    prepared                 INT         NOT NULL DEFAULT 0,
    skipped_duplicate        INT         NOT NULL DEFAULT 0,
    skipped_threshold        INT         NOT NULL DEFAULT 0,
    skipped_missing_profile  INT         NOT NULL DEFAULT 0,
    skipped_unsupported_flow INT         NOT NULL DEFAULT 0,
    quota_limited            INT         NOT NULL DEFAULT 0,
    failures                 INT         NOT NULL DEFAULT 0,
    started_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at             TIMESTAMPTZ,
    error                    TEXT
);

CREATE INDEX idx_auto_apply_runs_profile ON auto_apply_runs (profile_id, started_at DESC);

-- One row per job the run actually looked at, so a run's totals are always
-- reconstructable from evidence rather than trusted as a bare summary.
CREATE TABLE auto_apply_run_items (
    id             UUID        PRIMARY KEY,
    run_id         UUID        NOT NULL REFERENCES auto_apply_runs(id) ON DELETE CASCADE,
    job_id         UUID        NOT NULL REFERENCES jobs(id),
    application_id UUID        REFERENCES applications(id),
    match_score    INT,
    outcome        TEXT        NOT NULL CHECK (outcome IN (
                        'PREPARED','SKIPPED_DUPLICATE','SKIPPED_THRESHOLD',
                        'SKIPPED_MISSING_PROFILE','SKIPPED_UNSUPPORTED_FLOW',
                        'QUOTA_LIMITED','FAILED')),
    detail         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_auto_apply_run_items_run ON auto_apply_run_items (run_id);
