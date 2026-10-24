import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import type {
  AutomationPlanView,
  PlanErrorResult,
  SubmissionReconciliationResult,
  WorkdayAccountVerificationStatus,
  WorkdayApplicationOutcome,
  WorkdayField,
  WorkdayOutcome,
} from '../types';
import { Alert, Loading, SectionCard, StatusPill } from './ui';

/**
 * Phase 7, 11, 11A & Phase 19: Workday candidate review experience.
 *
 * Supports both:
 * 1. Multi-step application workflow (Phase 19) when WORKDAY_APPLICATION_OUTCOME is present:
 *    displays wizard step progress, filled fields, required human gaps, CV and cover letter
 *    upload status, guarded approval for submission, submit-now, and platform reconciliation.
 * 2. Sign-in readiness check and account creation (Phase 7/11/11A) when only WORKDAY_OUTCOME is present:
 *    verifies saved credentials and manages account creation readiness without touching the form.
 */
export const WorkdayReviewPanel: React.FC<{
  planId: string;
  planStatus?: string | null;
  applicationUrl?: string | null;
  onChanged?: () => void;
}> = ({ planId, planStatus, applicationUrl, onChanged }) => {
  const [plan, setPlan] = useState<AutomationPlanView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [retried, setRetried] = useState(false);
  const [agreedToWorkdayTerms, setAgreedToWorkdayTerms] = useState(false);
  const [creatingAccount, setCreatingAccount] = useState(false);
  const [accountCreationRequested, setAccountCreationRequested] = useState(false);
  const [verificationStatus, setVerificationStatus] = useState<WorkdayAccountVerificationStatus | null>(null);
  const [checkingVerification, setCheckingVerification] = useState(false);

  // Submission & reconciliation actions
  const [approving, setApproving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [reconciling, setReconciling] = useState(false);

  const load = useCallback(() => {
    setError(null);
    apiFetch<AutomationPlanView>(`/automation/plans/${planId}`)
      .then(setPlan)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'Could not load this Workday plan'));
  }, [planId]);

  useEffect(() => {
    load();
  }, [load]);

  const applicationOutcome: WorkdayApplicationOutcome | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'WORKDAY_APPLICATION_OUTCOME') as WorkdayApplicationOutcome | undefined ?? null;
  const loginOutcome: WorkdayOutcome | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'WORKDAY_OUTCOME') as WorkdayOutcome | undefined ?? null;
  const planError: PlanErrorResult | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'PLAN_ERROR') as PlanErrorResult | undefined ?? null;
  const reconciliation: SubmissionReconciliationResult | null =
    (plan?.result ?? []).find((entry): entry is SubmissionReconciliationResult => entry?.type === 'SUBMISSION_RECONCILIATION') ?? null;

  const checkVerification = useCallback(() => {
    if (!plan) return;
    setCheckingVerification(true);
    apiFetch<WorkdayAccountVerificationStatus>(`/apply/applications/${plan.applicationId}/workday-account/verification-status`)
      .then(setVerificationStatus)
      .catch(() => {})
      .finally(() => setCheckingVerification(false));
  }, [plan]);

  useEffect(() => {
    if (plan && loginOutcome?.loginStatus === 'ACCOUNT_CREATED') checkVerification();
  }, [plan, loginOutcome?.loginStatus, checkVerification]);

  const effectiveUrl = applicationUrl || applicationOutcome?.applicationUrl || loginOutcome?.applicationUrl || plan?.targetUrl || null;
  const status = planStatus ?? plan?.status ?? null;
  const stopped = status === 'FAILED' || status === 'BLOCKED_ANTI_BOT';
  const isSubmitted = status === 'SUBMITTED';
  const isReconciliationRequired = status === 'RECONCILIATION_REQUIRED';
  const isAwaitingApproval = status === 'AWAITING_APPROVAL';
  const isReadyToSubmit = status === 'READY_TO_SUBMIT';

  const retryPreparation = async () => {
    if (!plan) return;
    setRetrying(true);
    setError(null);
    try {
      await apiFetch(`/applications/${plan.applicationId}/re-prepare`, { method: 'POST' });
      setRetried(true);
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not queue this application for another preparation attempt');
    } finally {
      setRetrying(false);
    }
  };

  const approveSubmission = async () => {
    if (!plan) return;
    setApproving(true);
    setError(null);
    try {
      await apiFetch(`/automation/plans/${planId}/approve-submit`, { method: 'POST' });
      load();
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not approve Workday plan for submission');
    } finally {
      setApproving(false);
    }
  };

  const submitNow = async () => {
    if (!plan) return;
    setSubmitting(true);
    setError(null);
    try {
      await apiFetch(`/automation/plans/${planId}/prepare-submit`, { method: 'POST' });
      load();
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not queue Workday submission');
    } finally {
      setSubmitting(false);
    }
  };

  const reconcileRetry = async () => {
    if (!plan) return;
    setReconciling(true);
    setError(null);
    try {
      await apiFetch(`/automation/plans/${planId}/reconcile`, { method: 'POST' });
      load();
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not reconcile Workday plan');
    } finally {
      setReconciling(false);
    }
  };

  const createAccount = async () => {
    if (!plan || !agreedToWorkdayTerms) return;
    setCreatingAccount(true);
    setError(null);
    try {
      await apiFetch(`/apply/applications/${plan.applicationId}/workday-account`, {
        method: 'POST',
        body: JSON.stringify({ acceptWorkdayTerms: true }),
      });
      setAccountCreationRequested(true);
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not start Workday account creation');
    } finally {
      setCreatingAccount(false);
    }
  };

  const pill = stopped
    ? <StatusPill tone="red">{status === 'BLOCKED_ANTI_BOT' ? 'Blocked — CAPTCHA or MFA required' : 'Preparation failed'}</StatusPill>
    : isSubmitted
      ? <StatusPill tone="emerald">Submitted</StatusPill>
      : isReconciliationRequired
        ? <StatusPill tone="purple">Reconciliation required</StatusPill>
        : isReadyToSubmit
          ? <StatusPill tone="emerald">Ready to submit</StatusPill>
          : applicationOutcome
            ? (applicationOutcome.requiredGaps.length > 0
                ? <StatusPill tone="amber">Human input required</StatusPill>
                : <StatusPill tone="amber">Review needed before authorizing submission</StatusPill>)
            : loginOutcome?.loginStatus === 'ACCOUNT_CREATED'
              ? <StatusPill tone="emerald">Account created</StatusPill>
              : loginOutcome?.loginStatus === 'ACCOUNT_CREATE_FAILED'
                ? <StatusPill tone="red">Account creation failed</StatusPill>
                : loginOutcome?.loginStatus === 'TERMS_NOT_AUTHORIZED'
                  ? <StatusPill tone="amber">Authorization required</StatusPill>
                  : loginOutcome?.loginStatus === 'CREDENTIAL_REQUIRED'
                    ? <StatusPill tone="amber">Credential required</StatusPill>
                    : loginOutcome?.loginStatus === 'LOGIN_SUCCEEDED'
                      ? <StatusPill tone="emerald">Sign-in verified</StatusPill>
                      : loginOutcome
                        ? <StatusPill tone="amber">Human review required</StatusPill>
                        : <StatusPill tone="slate">Preparing…</StatusPill>;

  const title = applicationOutcome
    ? 'Workday — review application preparation'
    : 'Workday — sign-in readiness check';

  const hint = applicationOutcome
    ? 'Robin inspects and fills multi-step Workday forms; final submission is guarded and requires your explicit approval'
    : 'Robin only verifies sign-in here; it never fills out or submits the Workday application form';

  return (
    <SectionCard title={title} hint={hint} actions={pill}>
      {error && <Alert tone="error">{error}</Alert>}
      {!plan && !error && <Loading>Loading what Robin found on the employer&apos;s site…</Loading>}

      {plan && stopped && (
        <div className="space-y-3 text-sm">
          <p className="text-ink-soft">
            {status === 'BLOCKED_ANTI_BOT'
              ? 'Robin stopped because the employer’s site required a CAPTCHA or a one-time verification code (MFA) — Robin never attempts to solve or bypass these.'
              : (applicationOutcome ? 'Robin stopped before finishing this form.' : 'Robin stopped before finishing this sign-in check.')}
            {planError?.message ? ` Reason: ${planError.message}` : ''}
          </p>
          <div className="flex flex-wrap items-center gap-3">
            {effectiveUrl && (
              <a href={effectiveUrl} target="_blank" rel="noopener noreferrer"
                className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900">
                Open the employer&apos;s application page
              </a>
            )}
            <button type="button" disabled={retrying || retried} onClick={retryPreparation}
              className="rounded-md border border-forest-500/40 px-3 py-1.5 text-xs font-semibold text-forest-700 hover:bg-forest-50 disabled:opacity-50">
              {retried ? 'Retry queued' : retrying ? 'Queuing retry…' : 'Retry preparation'}
            </button>
          </div>
        </div>
      )}

      {plan && isReconciliationRequired && (
        <div className="space-y-3 text-sm rounded-md border border-purple-200 bg-purple-50 p-3 mb-4">
          <p className="text-purple-900 font-semibold">
            Submission reconciliation required
          </p>
          <p className="text-purple-800">
            A real submission click was attempted for this Workday application, but confirmation could not be verified automatically.
            {reconciliation?.category ? ` [${reconciliation.category}]: ${reconciliation.evidence ?? ''}` : ''}
          </p>
          <div className="flex flex-wrap items-center gap-3">
            <button type="button" disabled={reconciling} onClick={reconcileRetry}
              className="rounded-md bg-purple-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-purple-700 disabled:opacity-50">
              {reconciling ? 'Reconciling…' : 'Retry application preparation'}
            </button>
          </div>
        </div>
      )}

      {plan && !stopped && !applicationOutcome && !loginOutcome && (
        <p className="text-sm text-ink-muted">The worker has not yet reported what it found on this page. Check back shortly, or refresh.</p>
      )}

      {/* Phase 19: Workday Multi-step Application Workflow */}
      {!stopped && applicationOutcome && (
        <div className="space-y-4 text-sm">
          {applicationOutcome.currentStep && (
            <div className="flex items-center gap-2 text-xs text-ink-muted">
              <span>Step reached on Workday:</span>
              <span className="font-semibold text-ink-soft">{formatStepName(applicationOutcome.currentStep)}</span>
            </div>
          )}

          <WorkdayFieldList
            title="Filled automatically"
            tone="emerald"
            items={applicationOutcome.filled}
            emptyText="Nothing was auto-filled."
          />

          <WorkdayFieldList
            title="Needs your input before you can authorize submission"
            tone="amber"
            items={applicationOutcome.requiredGaps}
            emptyText="Nothing is outstanding."
          />

          <div className="space-y-2 border-t border-line pt-3">
            <WorkdayDocumentStatus
              title="Résumé / CV"
              state={applicationOutcome.resumeUploadState}
              reason={applicationOutcome.resumeUploadReason}
            />
            {applicationOutcome.coverLetterUploadState && applicationOutcome.coverLetterUploadState !== 'NOT_REQUESTED' && (
              <WorkdayDocumentStatus
                title="Cover Letter"
                state={applicationOutcome.coverLetterUploadState}
                reason={applicationOutcome.coverLetterUploadReason}
              />
            )}
          </div>

          {/* Submission Authorization and Action Controls */}
          <div className="rounded-md border border-line bg-slate-50 p-3 space-y-3">
            {isSubmitted && (
              <p className="text-emerald-700 font-semibold">Your application has been submitted to Workday.</p>
            )}

            {isReadyToSubmit && (
              <div className="space-y-2">
                <p className="text-ink-soft font-semibold">Submission authorized</p>
                <p className="text-ink-muted text-xs">
                  You have reviewed and approved this application. Click below to submit it directly on Workday.
                </p>
                <button type="button" disabled={submitting} onClick={submitNow}
                  className="rounded-md bg-forest-600 px-4 py-2 text-xs font-semibold text-white hover:bg-forest-700 disabled:opacity-50">
                  {submitting ? 'Submitting to Workday…' : 'Submit application now'}
                </button>
              </div>
            )}

            {isAwaitingApproval && (
              <div className="space-y-2">
                <p className="text-ink-soft font-semibold">Candidate review</p>
                {applicationOutcome.requiredGaps.length > 0 ? (
                  <p className="text-amber-700 text-xs">
                    Resolve the outstanding fields above before approving submission. Robin will not submit incomplete declarations or unanswered required questions.
                  </p>
                ) : (
                  <>
                    <p className="text-ink-muted text-xs">
                      All required standard fields and verified documents are prepared. Explicitly authorize Robin to submit once you have reviewed the details above.
                    </p>
                    <button type="button" disabled={approving} onClick={approveSubmission}
                      className="rounded-md border border-forest-500 bg-white px-4 py-2 text-xs font-semibold text-forest-700 hover:bg-forest-50 disabled:opacity-50">
                      {approving ? 'Approving…' : 'Approve for Submission'}
                    </button>
                  </>
                )}
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-3 border-t border-line pt-3">
            {effectiveUrl && (
              <a href={effectiveUrl} target="_blank" rel="noopener noreferrer"
                className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900">
                Open the employer&apos;s application page
              </a>
            )}
          </div>
          <p className="text-[11px] text-ink-faint">
            Robin never submits without explicit candidate approval, and strictly avoids hidden anti-bot traps.
          </p>
        </div>
      )}

      {/* Phase 7/11/11A: Legacy Workday Sign-in Readiness Check */}
      {!stopped && !applicationOutcome && loginOutcome && (
        <div className="space-y-4 text-sm">
          {loginOutcome.loginStatus === 'CREDENTIAL_REQUIRED' && (
            <div className="space-y-3">
              <div className="space-y-2">
                <p className="text-ink-soft">
                  Robin could not verify sign-in because no saved Workday credential exists for this employer yet.
                </p>
                <p className="text-ink-muted">
                  Save your Workday username and password for this employer in your account settings, then retry
                  preparation — Robin will then attempt to verify it signs in before you apply.
                </p>
              </div>
              <div className="rounded-md border border-line bg-slate-50 p-3 space-y-2">
                <p className="text-ink-soft font-semibold">Don&apos;t have an account with this employer yet?</p>
                <p className="text-ink-muted">
                  Robin can create one for you instead: it will register the account with your Robin-managed
                  e-mail address and a freshly generated password, saved securely for this employer only. You
                  will still need to finish verifying it yourself if the employer sends a verification message —
                  check your mailbox once this completes.
                </p>
                <label className="flex items-start gap-2 text-ink-muted">
                  <input type="checkbox" checked={agreedToWorkdayTerms}
                    onChange={(e) => setAgreedToWorkdayTerms(e.target.checked)}
                    className="mt-0.5" disabled={creatingAccount || accountCreationRequested} />
                  <span>
                    I authorize Robin to create a new account with this employer on my behalf, including
                    accepting any account-creation terms the employer&apos;s own site presents.
                  </span>
                </label>
                <button type="button" disabled={!agreedToWorkdayTerms || creatingAccount || accountCreationRequested}
                  onClick={createAccount}
                  className="rounded-md border border-forest-500/40 px-3 py-1.5 text-xs font-semibold text-forest-700 hover:bg-forest-50 disabled:opacity-50">
                  {accountCreationRequested ? 'Account creation requested' : creatingAccount ? 'Requesting…' : 'Let Robin create this account'}
                </button>
              </div>
            </div>
          )}
          {loginOutcome.loginStatus === 'ACCOUNT_CREATED' && (
            <div className="space-y-2">
              <p className="text-emerald-700 font-semibold">Robin created a new Workday account for this employer.</p>
              <p className="text-ink-muted">
                This only confirms the account was created — not that it is verified, signed in, or that
                anything was submitted. Retry preparation afterwards so Robin can confirm the saved credential
                actually works.
              </p>
              <div className="rounded-md border border-line bg-slate-50 p-3 space-y-2">
                {verificationStatus?.status === 'VERIFICATION_MESSAGE_MATCHED' ? (
                  <p className="text-ink-soft">
                    A verification message from this employer was found
                    {verificationStatus.provider ? ` in your ${verificationStatus.provider === 'robin-managed' ? 'Robin-managed' : 'connected'} mailbox` : ''}
                    {verificationStatus.receivedAt ? ` at ${new Date(verificationStatus.receivedAt).toLocaleString()}` : ''}.
                    Robin does not open or act on it — open it yourself in your mailbox and follow whatever the
                    employer asks, then retry preparation.
                  </p>
                ) : verificationStatus?.status === 'VERIFICATION_MESSAGE_PENDING' ? (
                  <p className="text-ink-muted">No verification message has been found yet. Check your mailbox, or check again shortly.</p>
                ) : verificationStatus?.status === 'HUMAN_ASSISTANCE_REQUIRED' ? (
                  <p className="text-amber-700">{verificationStatus.detail ?? 'This account session needs your attention.'}</p>
                ) : (
                  <p className="text-ink-muted">Checking for a verification message…</p>
                )}
                <button type="button" disabled={checkingVerification} onClick={checkVerification}
                  className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900 disabled:opacity-50">
                  {checkingVerification ? 'Checking…' : 'Check again'}
                </button>
              </div>
            </div>
          )}
          {loginOutcome.loginStatus === 'ACCOUNT_CREATE_FAILED' && (
            <div className="space-y-2">
              <p className="text-amber-700 font-semibold">Account creation did not succeed.</p>
              <p className="text-ink-muted">{loginOutcome.reason ?? 'The employer’s site did not accept the new account.'}</p>
            </div>
          )}
          {loginOutcome.loginStatus === 'TERMS_NOT_AUTHORIZED' && (
            <div className="space-y-2">
              <p className="text-amber-700 font-semibold">Account creation was not authorized.</p>
              <p className="text-ink-muted">{loginOutcome.reason ?? 'Account creation requires your explicit authorization.'}</p>
            </div>
          )}
          {loginOutcome.loginStatus === 'LOGIN_SUCCEEDED' && (
            <div className="space-y-2">
              <p className="text-emerald-700 font-semibold">Your saved credential was verified against the employer&apos;s real sign-in page.</p>
              <p className="text-ink-muted">
                Robin does not fill out or submit the Workday application form itself — this only confirms your
                credential works. Finish the application yourself on the employer&apos;s site.
              </p>
            </div>
          )}
          {loginOutcome.loginStatus === 'LOGIN_FAILED' && (
            <div className="space-y-2">
              <p className="text-amber-700 font-semibold">Sign-in did not succeed.</p>
              <p className="text-ink-muted">{loginOutcome.reason ?? 'The employer’s sign-in page did not accept your saved credential.'}</p>
              <p className="text-ink-muted">Update your saved credential for this employer, then retry preparation.</p>
            </div>
          )}
          {loginOutcome.loginStatus === 'FORM_NOT_FOUND' && (
            <div className="space-y-2">
              <p className="text-amber-700 font-semibold">Robin could not find the employer&apos;s sign-in form.</p>
              <p className="text-ink-muted">{loginOutcome.reason ?? 'The page did not look like Workday’s usual sign-in page when Robin checked.'}</p>
            </div>
          )}

          <div className="flex flex-wrap items-center gap-3 border-t border-line pt-3">
            {effectiveUrl && (
              <a href={effectiveUrl} target="_blank" rel="noopener noreferrer"
                className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900">
                Open the employer&apos;s application page
              </a>
            )}
          </div>
          <p className="text-[11px] text-ink-faint">
            Robin never fills out or submits the Workday application form, and never fills the form&apos;s hidden
            anti-bot trap field — this check only tells you whether your saved credential signs in before you
            apply yourself on the employer&apos;s own page.
          </p>
        </div>
      )}
    </SectionCard>
  );
};

function formatStepName(step: string): string {
  switch (step) {
    case 'MY_INFORMATION': return 'My Information';
    case 'MY_EXPERIENCE': return 'My Experience';
    case 'APPLICATION_QUESTIONS': return 'Application Questions';
    case 'VOLUNTARY_DISCLOSURES': return 'Voluntary Disclosures';
    case 'REVIEW': return 'Review';
    default: return step;
  }
}

const WORKDAY_CATEGORY_LABELS: Record<string, string> = {
  STANDARD: 'Standard field',
  OPTIONAL_STANDARD: 'Optional field',
  EXPERIENCE: 'Work experience',
  DECLARATION: 'Declaration',
  CUSTOM: 'Custom question',
  REQUIRES_HUMAN: 'Needs your input',
  SENSITIVE: 'Sensitive question',
  UNKNOWN: 'Unrecognized question',
};

const WorkdayDocumentStatus: React.FC<{
  title: string;
  state: 'ATTACHED' | 'FAILED' | 'MISSING' | 'NOT_REQUESTED';
  reason?: string | null;
}> = ({ title, state, reason }) => (
  <div>
    <p className="font-semibold text-ink-soft">{title}</p>
    {state === 'NOT_REQUESTED' && <p className="mt-1 text-ink-faint">Not requested on this form.</p>}
    {(state === 'MISSING' || state === 'FAILED') && (
      <p className="mt-1 text-ink-muted">
        Not attached automatically{reason ? `: ${reason}` : ''}.
      </p>
    )}
    {state === 'ATTACHED' && (
      <p className="mt-1 text-emerald-700">
        Attached to the form — not yet submitted to the employer.
      </p>
    )}
  </div>
);

const WorkdayFieldList: React.FC<{
  title: string;
  tone: 'emerald' | 'amber';
  items: WorkdayField[];
  emptyText: string;
}> = ({ title, tone, items, emptyText }) => (
  <div>
    <p className="font-semibold text-ink-soft">
      {title} ({items.length})
    </p>
    {items.length === 0 ? (
      <p className="mt-1 text-ink-faint">{emptyText}</p>
    ) : (
      <ul className={`mt-1 space-y-0.5 ${tone === 'emerald' ? 'text-emerald-700' : 'text-amber-700'}`}>
        {items.map((item, index) => (
          <li key={`${item.label}-${item.category}-${index}`}>
            {item.label}
            {item.value && (
              <span className="text-ink-soft font-mono text-xs"> = &quot;{item.value}&quot;</span>
            )}
            {WORKDAY_CATEGORY_LABELS[item.category] && (
              <span className="text-ink-faint"> — {WORKDAY_CATEGORY_LABELS[item.category]}</span>
            )}
          </li>
        ))}
      </ul>
    )}
  </div>
);
