-- Phase 3: Single Active PENDING Handoff Session per Automation Plan
-- Prevents competing or duplicate handoff sessions under concurrent initiation races.

-- 1. Expire any existing overdue pending sessions
UPDATE automation_handoff_sessions
SET status = 'EXPIRED', updated_at = clock_timestamp()
WHERE status = 'PENDING' AND expires_at <= clock_timestamp();

-- 2. If multiple PENDING sessions exist for the same plan, keep the newest one and cancel older ones
UPDATE automation_handoff_sessions s
SET status = 'CANCELLED', cancelled_at = clock_timestamp(), updated_at = clock_timestamp()
WHERE s.status = 'PENDING'
  AND s.id NOT IN (
      SELECT DISTINCT ON (plan_id) id
      FROM automation_handoff_sessions
      WHERE status = 'PENDING'
      ORDER BY plan_id, created_at DESC
  );

-- 3. Unique partial index: exactly one PENDING session per automation plan
CREATE UNIQUE INDEX IF NOT EXISTS handoff_sessions_single_pending_per_plan
  ON automation_handoff_sessions(plan_id)
  WHERE status = 'PENDING';
