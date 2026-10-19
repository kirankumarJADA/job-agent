import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import type { AutomationPlanView, PlanErrorResult, ZohoDocumentUploadState, ZohoRecruitField, ZohoRecruitOutcome } from '../types';
import { Alert, Loading, SectionCard, StatusPill } from './ui';

/**
 * Robin x Tsenta continuation: the human-review experience for a Zoho
 * Recruit automation plan — deliberately NOT the Greenhouse "Approve for
 * submission" flow (`ApplyWorkspace`/`ApplicationsPage`'s inline automation
 * block), because this adapter has no real-submission capability at all:
 * approving a Zoho Recruit plan for submission would 409 against the
 * backend's own `approvalPrecondition` ("only greenhouse execution plans can
 * be approved for submission"). Shown instead whenever
 * `application.planType === 'ZOHO_RECRUIT'`.
 *
 * Reads the plan's structured `result` (persisted by
 * `AutomationController#complete` via `transitionWithResult` — see the
 * backend's own javadoc) through the existing, generic
 * `GET /automation/plans/{id}` endpoint; no Zoho-specific API was added.
 * "I finished this on the employer's site" reuses the existing, generic
 * `/review` acknowledgement endpoint (no planType check there either) —
 * it only records an audit trail, exactly like Greenhouse's "I reviewed the
 * form" button, never transitions the plan to any submitted-sounding state.
 *
 * Recovery: when the plan's own `status` is FAILED or BLOCKED_ANTI_BOT, the
 * outcome-based UI below is skipped entirely — there is nothing to review
 * yet — in favor of a distinct stopped-state view with the real reason (from
 * a persisted PLAN_ERROR result, where available) and a "Retry preparation"
 * button that calls the existing, generic `POST /applications/{id}/re-prepare`
 * action (the same one that drives a brand-new application's preparation).
 * This is a real, backend-enforced retry: the backend requeues this plan from
 * FAILED/BLOCKED_ANTI_BOT back to PREPARED and rebuilds it with fresh
 * artifacts (see `ZohoRecruitExecutionPlanService#createExecutionPlan`); it
 * is not a decorative button or local-only state change.
 */
export const ZohoRecruitReviewPanel: React.FC<{
  planId: string;
  applicationUrl?: string | null;
  onChanged?: () => void;
}> = ({ planId, applicationUrl, onChanged }) => {
  const [plan, setPlan] = useState<AutomationPlanView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const [reviewed, setReviewed] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [retried, setRetried] = useState(false);

  const load = useCallback(() => {
    setError(null);
    apiFetch<AutomationPlanView>(`/automation/plans/${planId}`)
      .then(setPlan)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'Could not load this Zoho Recruit plan'));
  }, [planId]);

  useEffect(() => {
    load();
  }, [load]);

  const outcome: ZohoRecruitOutcome | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'ZOHO_RECRUIT_OUTCOME') as ZohoRecruitOutcome | undefined ?? null;
  const planError: PlanErrorResult | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'PLAN_ERROR') as PlanErrorResult | undefined ?? null;
  const effectiveUrl = applicationUrl || outcome?.applicationUrl || plan?.targetUrl || null;
  // FAILED and BLOCKED_ANTI_BOT are genuinely distinct from AWAITING_APPROVAL:
  // there is nothing to review yet because the worker never finished filling
  // the form, so this branch takes priority over the outcome-based UI below
  // even if a stale `outcome` from an earlier attempt is still present.
  const stopped = plan?.status === 'FAILED' || plan?.status === 'BLOCKED_ANTI_BOT';

  const markReviewed = async () => {
    setReviewing(true);
    setError(null);
    try {
      await apiFetch(`/automation/plans/${planId}/review`, {
        method: 'POST',
        body: JSON.stringify({ acknowledge: true }),
      });
      setReviewed(true);
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not record that you reviewed this');
    } finally {
      setReviewing(false);
    }
  };

  // Reuses the existing, generic re-preparation action — the same one that
  // already re-runs CV/cover-letter/answer selection and automation-plan
  // creation for a brand-new application — rather than inventing a Zoho- or
  // retry-specific endpoint. The backend requeues this plan from FAILED/
  // BLOCKED_ANTI_BOT back to PREPARED and rebuilds it with fresh artifacts;
  // an in-flight or already-reviewed plan is structurally untouched by that
  // path, so this can never race a plan that is not actually stuck.
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

  const pill = stopped
    ? (
      <StatusPill tone="red">
        {plan?.status === 'BLOCKED_ANTI_BOT' ? 'Blocked — CAPTCHA or anti-bot check' : 'Preparation failed'}
      </StatusPill>
    )
    : outcome
      ? <StatusPill tone="amber">Human review required</StatusPill>
      : <StatusPill tone="slate">Preparing…</StatusPill>;

  return (
    <SectionCard
      title="Zoho Recruit — your review is needed"
      hint="Robin prepared this application but never submits on Zoho Recruit"
      actions={pill}
    >
      {error && <Alert tone="error">{error}</Alert>}
      {!plan && !error && <Loading>Loading what Robin found on the employer&apos;s form…</Loading>}

      {plan && stopped && (
        <div className="space-y-3 text-sm">
          <p className="text-ink-soft">
            {plan.status === 'BLOCKED_ANTI_BOT'
              ? 'Robin stopped before finishing this form because the employer’s site presented a CAPTCHA or anti-bot check — Robin never attempts to solve or bypass these.'
              : 'Robin stopped before finishing this form.'}
            {planError?.message ? ` Reason: ${planError.message}` : ''}
          </p>
          <div className="flex flex-wrap items-center gap-3">
            {effectiveUrl && (
              <a
                href={effectiveUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900"
              >
                Open the employer&apos;s application page
              </a>
            )}
            <button
              type="button"
              disabled={retrying || retried}
              onClick={retryPreparation}
              className="rounded-md border border-forest-500/40 px-3 py-1.5 text-xs font-semibold text-forest-700 hover:bg-forest-50 disabled:opacity-50"
            >
              {retried ? 'Retry queued' : retrying ? 'Queuing retry…' : 'Retry preparation'}
            </button>
          </div>
          <p className="text-[11px] text-ink-faint">
            This queues a fresh preparation attempt with your latest reviewed documents and answers
            — it will not resubmit anything, since Robin never submits on Zoho Recruit.
          </p>
        </div>
      )}

      {plan && !stopped && !outcome && (
        <p className="text-sm text-ink-muted">
          The worker has not yet reported what it found on this form. Check back shortly, or refresh.
        </p>
      )}

      {!stopped && outcome && (
        <div className="space-y-4 text-sm">
          <FieldList title="Filled automatically" tone="emerald" items={outcome.filled} emptyText="Nothing was auto-filled." />
          <FieldList
            title="Needs your input before you can submit"
            tone="amber"
            items={outcome.requiredGaps}
            emptyText="Nothing is outstanding — review the filled fields, then finish this on the employer's page."
          />

          <DocumentStatus
            title="Résumé"
            state={outcome.resumeUploadState}
            reason={outcome.resumeUploadReason}
            noFieldText="This form has no résumé field, or none was found."
          />
          <DocumentStatus
            title="Cover letter"
            state={outcome.coverLetterUploadState ?? 'NO_FIELD'}
            reason={outcome.coverLetterUploadReason}
            noFieldText="This employer's form does not ask for a cover letter."
          />

          <div className="flex flex-wrap items-center gap-3 border-t border-line pt-3">
            {effectiveUrl && (
              <a
                href={effectiveUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900"
              >
                Open the employer&apos;s application page
              </a>
            )}
            <button
              type="button"
              disabled={reviewing || reviewed}
              onClick={markReviewed}
              className="rounded-md border border-emerald-500/40 px-3 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
            >
              {reviewed ? 'Marked as reviewed' : reviewing ? 'Recording…' : "I finished this on the employer's site"}
            </button>
          </div>
          <p className="text-[11px] text-ink-faint">
            Robin never submits on Zoho Recruit — a CAPTCHA (when present) and required consent
            checkboxes make that unsafe to automate. Finish and submit the application yourself on
            the employer&apos;s own page; marking this reviewed only records that you have seen it.
          </p>
        </div>
      )}
    </SectionCard>
  );
};

const CATEGORY_LABELS: Record<string, string> = {
  IDENTITY_FIRST_NAME: 'First name',
  IDENTITY_LAST_NAME: 'Last name',
  IDENTITY_EMAIL: 'Email',
  IDENTITY_PHONE: 'Phone / mobile',
  RESUME_UPLOAD: 'Résumé upload',
  COVER_LETTER_UPLOAD: 'Cover letter upload',
  CAPTCHA: 'CAPTCHA',
  REQUIRES_HUMAN: 'Needs your input',
  UNRECOGNIZED: 'Unrecognized field',
  COMPOSITE_WIDGET_PART: 'Country/dial-code picker',
};

/**
 * Robin x Tsenta hardening: renders the honest document-upload state —
 * never a single "uploaded" claim. `ATTACHED` reads as "attached to the
 * form — not yet submitted," since a successful `setInputFiles` call is not
 * proof the employer's server received anything, and this adapter never
 * clicks Submit at all. `CONFIRMED` is included for completeness even
 * though no worker code produces it yet (see `ZohoDocumentUploadState`'s
 * own doc comment in `types.ts`).
 */
const DocumentStatus: React.FC<{
  title: string;
  state: ZohoDocumentUploadState;
  reason?: string | null;
  noFieldText: string;
}> = ({ title, state, reason, noFieldText }) => (
  <div>
    <p className="font-semibold text-ink-soft">{title}</p>
    {state === 'NO_FIELD' && <p className="mt-1 text-ink-faint">{noFieldText}</p>}
    {(state === 'NO_ARTIFACT' || state === 'REJECTED') && (
      <p className="mt-1 text-ink-muted">
        Not attached automatically{reason ? `: ${reason}` : ''}. Attach it yourself on the employer&apos;s page.
      </p>
    )}
    {state === 'ATTACHED' && (
      <p className="mt-1 text-emerald-700">
        Attached to the form — not yet submitted. Finish and submit this application yourself on the employer&apos;s page.
      </p>
    )}
    {state === 'CONFIRMED' && (
      <p className="mt-1 text-emerald-700">Uploaded and confirmed by the employer&apos;s site.</p>
    )}
  </div>
);

const FieldList: React.FC<{
  title: string;
  tone: 'emerald' | 'amber';
  items: ZohoRecruitField[];
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
          <li key={`${item.label ?? item.category}-${index}`}>
            {item.label || CATEGORY_LABELS[item.category] || item.category}
            {item.label && CATEGORY_LABELS[item.category] && (
              <span className="text-ink-faint"> — {CATEGORY_LABELS[item.category]}</span>
            )}
          </li>
        ))}
      </ul>
    )}
  </div>
);
