-- Phase 15: RECONCILIATION_REQUIRED — the one new automation_plans status this
-- phase adds, and the only safe way to express "a real submit-click attempt
-- (Greenhouse REAL_SUBMIT_CLICK or Ashby ASHBY_SUBMIT_CLICK) happened and its
-- outcome is not confirmed, so this plan can never be re-approved or clicked
-- again automatically."
--
-- Why a new status, not AWAITING_APPROVAL: AWAITING_APPROVAL already means
-- "the worker finished a normal preparation pass; a real submission was never
-- attempted" for every plan type (GREENHOUSE/ZOHO_RECRUIT/LEVER/WORKDAY/ASHBY).
-- Reusing it for "a real click MAY have reached the employer and we don't
-- know" would conflate two meaningfully different facts — exactly what this
-- phase's task is explicit must never happen. The existing schema and
-- transition model (automation_plans.status, the generic transition()/
-- transitionWithResult() methods already used by every other outcome) can
-- express the new state with no other structural change, so no parallel
-- table or submission-attempt ledger was introduced.
--
-- Reached only from RUNNING, via the existing /complete endpoint's existing
-- transition machinery (see AutomationController#complete's new
-- "RECONCILIATION_REQUIRED" case) — never automatically retried from here
-- (see AutomationController#approvalPrecondition's REAL_SUBMIT_CLICK/
-- ASHBY_SUBMIT_CLICK checks), and never transitions back out except through
-- the new, explicit POST /plans/{id}/reconcile action, which — when the
-- persisted evidence genuinely supports it — creates a BRAND NEW plan row
-- for a fresh attempt (a distinct idempotency key, built by
-- GreenhouseExecutionPlanService#createRetryExecutionPlan /
-- AshbyExecutionPlanService#createRetryExecutionPlan) rather than ever
-- mutating or resubmitting the superseded row. The superseded row is left in
-- RECONCILIATION_REQUIRED forever — a permanent, auditable record that this
-- exact attempt's outcome was never established — never deleted, never
-- reset to look as though the attempt never happened.
ALTER TABLE automation_plans DROP CONSTRAINT automation_plans_status_check;
ALTER TABLE automation_plans ADD CONSTRAINT automation_plans_status_check
  CHECK (status IN ('PREPARED','RUNNING','AWAITING_APPROVAL','AWAITING_SUBMIT_APPROVAL',
                    'READY_TO_SUBMIT','COMPLETED','SUBMITTED','FAILED','BLOCKED_ANTI_BOT',
                    'ABANDONED','RECONCILIATION_REQUIRED'));

comment on constraint automation_plans_status_check on automation_plans is
    'RECONCILIATION_REQUIRED (Phase 15): a real submit-click attempt''s outcome was not confirmed; never auto-retried, never re-approved — see AutomationController#approvalPrecondition and the /plans/{id}/reconcile endpoint.';
