-- READY_TO_SUBMIT: the owner has explicitly approved a validated automation
-- plan for submission. It is distinct from AWAITING_APPROVAL (review pending)
-- and from COMPLETED (which for greenhouse plans must never imply a submit):
-- the plan is ready, but actual submission stays disabled in Phase 3 — no
-- worker plan ever contains a submit step, REAL_SUBMIT remains a hard stop,
-- and no code path transitions READY_TO_SUBMIT to SUBMITTED.
ALTER TABLE automation_plans DROP CONSTRAINT automation_plans_status_check;
ALTER TABLE automation_plans ADD CONSTRAINT automation_plans_status_check
  CHECK (status IN ('PREPARED','RUNNING','AWAITING_APPROVAL','AWAITING_SUBMIT_APPROVAL',
                    'READY_TO_SUBMIT','COMPLETED','SUBMITTED','FAILED','BLOCKED_ANTI_BOT','ABANDONED'));
