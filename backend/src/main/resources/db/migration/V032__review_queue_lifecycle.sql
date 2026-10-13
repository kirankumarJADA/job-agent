-- Phase 6: human review queue lifecycle for application decisions.
--
-- Phase 5 recorded decisions as AUTO_APPLY / NEEDS_REVIEW / SKIP. The review
-- queue extends the lifecycle so a human can act on NEEDS_REVIEW items:
--   APPROVED  - the owner approved; the application was created
--   REJECTED  - the owner declined; no application will be created
--   PAUSED    - held out of the active queue, resumable
--   EXPIRED   - unreviewed past the configured window (lazy sweep)
-- reviewed_at records when a human acted (or when expiry swept the row), and
-- application_id links the application created by an approval.

ALTER TABLE application_decisions DROP CONSTRAINT application_decisions_decision_check;
ALTER TABLE application_decisions ADD CONSTRAINT application_decisions_decision_check
    CHECK (decision IN ('AUTO_APPLY', 'NEEDS_REVIEW', 'SKIP',
                        'APPROVED', 'REJECTED', 'PAUSED', 'EXPIRED'));

ALTER TABLE application_decisions ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
ALTER TABLE application_decisions ADD COLUMN IF NOT EXISTS application_id UUID;
