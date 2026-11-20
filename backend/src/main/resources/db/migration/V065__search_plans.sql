-- V065: Configurable Candidate Search Plans
-- Allows candidates and autonomous discovery operators to define targeted, multi-source search plans
-- with query facets, provider preferences, and cadence controls.

CREATE TABLE search_plans (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (char_length(trim(name)) >= 1 AND char_length(name) <= 120),
    target_titles TEXT[] NOT NULL DEFAULT '{}',
    query_facets JSONB NOT NULL DEFAULT '{}'::jsonb,
    target_sources TEXT[] NOT NULL DEFAULT '{}',
    search_frequency TEXT NOT NULL DEFAULT 'DAILY' CHECK (search_frequency IN ('HOURLY', 'DAILY', 'WEEKLY', 'MANUAL')),
    max_results_per_run INTEGER NOT NULL DEFAULT 50 CHECK (max_results_per_run >= 1 AND max_results_per_run <= 500),
    active BOOLEAN NOT NULL DEFAULT true,
    last_run_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for candidate-scoped lookups
CREATE INDEX search_plans_profile_idx ON search_plans (profile_id, active);

-- Index for scheduler looking for active plans due for execution
CREATE INDEX search_plans_frequency_idx ON search_plans (search_frequency, active) WHERE active = true;

COMMENT ON TABLE search_plans IS 'Configurable search plans defining candidate-scoped discovery strategies across providers';
