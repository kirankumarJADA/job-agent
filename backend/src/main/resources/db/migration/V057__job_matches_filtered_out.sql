-- V057__job_matches_filtered_out.sql
-- Allow per-profile 'FILTERED_OUT' recommendation in job_matches (Agent 2, Wave 1).
-- Replaces mutating shared catalogue rows (jobs.status / jobs.filter_reasons) with candidate-scoped preference state.

alter table job_matches drop constraint if exists job_matches_recommendation_check;
alter table job_matches add constraint job_matches_recommendation_check
    check (recommendation in ('APPLY', 'REVIEW', 'SKIP', 'FILTERED_OUT'));
