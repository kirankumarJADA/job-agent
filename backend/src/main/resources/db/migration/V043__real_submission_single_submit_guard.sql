-- Phase 4 (real Greenhouse submission): a database-level guard that no two
-- automation plans for the same application can ever both reach SUBMITTED.
-- The idempotency_key uniqueness (V009/V022) already means a candidate's own
-- create call cannot produce two plans for one application in the normal
-- path; this index is defense in depth for that one new, genuinely
-- irreversible terminal state regardless of how a second plan might arise.
-- A partial unique index (not a plain unique constraint) because every other
-- status is allowed to repeat across plans/attempts for the same application.
create unique index if not exists automation_plans_one_submission_per_application
  on automation_plans(application_id)
  where status = 'SUBMITTED';
