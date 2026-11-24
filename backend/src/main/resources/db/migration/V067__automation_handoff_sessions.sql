-- Phase 3: ATS Handoff Sessions for Candidate-Controlled CAPTCHA/MFA Resolution
-- Allows automation to pause safely when an employer security checkpoint (CAPTCHA, MFA, Cloudflare Turnstile) is reached.
-- Candidate resolves the challenge in their own browser session and explicitly confirms completion.

ALTER TABLE automation_plans DROP CONSTRAINT IF EXISTS automation_plans_status_check;
ALTER TABLE automation_plans ADD CONSTRAINT automation_plans_status_check
  CHECK (status IN ('PREPARED','RUNNING','AWAITING_APPROVAL','AWAITING_SUBMIT_APPROVAL',
                    'READY_TO_SUBMIT','COMPLETED','SUBMITTED','FAILED','BLOCKED_ANTI_BOT',
                    'ABANDONED','RECONCILIATION_REQUIRED','HANDOFF_REQUIRED'));

CREATE TABLE IF NOT EXISTS automation_handoff_sessions (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES automation_plans(id) ON DELETE CASCADE,
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'EXPIRED', 'CANCELLED')),
    block_reason TEXT NOT NULL CHECK (block_reason IN ('CAPTCHA', 'MFA', 'CLOUDFLARE_TURNSTILE', 'EMPLOYER_SECURITY_CHECK')),
    target_url TEXT NOT NULL,
    handoff_token UUID NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS handoff_sessions_plan_idx ON automation_handoff_sessions(plan_id);
CREATE INDEX IF NOT EXISTS handoff_sessions_profile_status_idx ON automation_handoff_sessions(profile_id, status);
CREATE INDEX IF NOT EXISTS handoff_sessions_token_idx ON automation_handoff_sessions(handoff_token);
CREATE INDEX IF NOT EXISTS handoff_sessions_app_idx ON automation_handoff_sessions(application_id);
