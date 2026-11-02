import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import type { AshbyOutcome, AutomationPlanView, PlanErrorResult, SubmissionReconciliationResult, ZohoDocumentUploadState, ZohoRecruitField } from '../types';
import { Alert, Loading, SectionCard, StatusPill } from './ui';

/**
 * Phase 14: the human-review experience for an ASHBY automation plan.
 *
 * <p>Unlike {@code ZohoRecruitReviewPanel}/{@code LeverReviewPanel} (which
 * exist specifically BECAUSE those adapters have no real-submission
 * capability at all, so the generic Greenhouse-style "Approve for
 * submission" flow in {@code ApplicationsPage}'s inline automation block
 * would 409), Ashby genuinely CAN submit — so this panel is shown
 * ADDITIONALLY alongside that existing generic block (not instead of it):
 * this panel shows what Robin filled and what still needs attention: the
 * generic block below it (unchanged, reused as-is) drives the actual
 * review-acknowledge / approve-for-submission / submit-to-employer actions,
 * since `AutomationController`'s `approve-submit`/`prepare-submit`/`review`
 * endpoints are all already ATS-agnostic and dispatch correctly for ASHBY.
 * No new endpoint was added for this panel.
 *
 * <p>Reads the plan's structured `result` (persisted by
 * {@code AutomationController#complete} via `transitionWithResult`) through
 * the existing, generic `GET /automation/plans/{id}` endpoint — same as
 * every other review panel.
 *
 * <p>Recovery mirrors {@code ZohoRecruitReviewPanel} exactly: FAILED/
 * BLOCKED_ANTI_BOT skip the outcome-based UI in favor of a stopped-state
 * view with a "Retry preparation" button, reusing the existing, generic
 * `POST /applications/{id}/re-prepare` action.
 */
export const AshbyReviewPanel: React.FC<{
  planId: string;
  planStatus?: string | null;
  applicationUrl?: string | null;
  onChanged?: () => void;
}> = ({ planId, planStatus, applicationUrl, onChanged }) => {
  const [plan, setPlan] = useState<AutomationPlanView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [retried, setRetried] = useState(false);

  const load = useCallback(() => {
    setError(null);
    apiFetch<AutomationPlanView>(`/automation/plans/${planId}`)
      .then(setPlan)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'Could not load this Ashby plan'));
  }, [planId]);

  useEffect(() => {
    load();
  }, [load]);

  const outcome: AshbyOutcome | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'ASHBY_OUTCOME') as AshbyOutcome | undefined ?? null;
  const planError: PlanErrorResult | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'PLAN_ERROR') as PlanErrorResult | undefined ?? null;
  const reconciliation: SubmissionReconciliationResult | null =
    (plan?.result ?? []).find((entry): entry is SubmissionReconciliationResult => entry?.type === 'SUBMISSION_RECONCILIATION') ?? null;
  const effectiveUrl = applicationUrl || outcome?.applicationUrl || plan?.targetUrl || null;
  const status = planStatus ?? plan?.status ?? null;
  // Same distinction ZohoRecruitReviewPanel draws: FAILED/BLOCKED_ANTI_BOT
  // mean the worker never finished filling the form, so there is nothing to
  // review yet regardless of any stale outcome from an earlier attempt.
  const stopped = status === 'FAILED' || status === 'BLOCKED_ANTI_BOT';
  const submitted = status === 'SUBMITTED';
  const reconciliationRequired = status === 'RECONCILIATION_REQUIRED';

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
        {status === 'BLOCKED_ANTI_BOT' ? 'Blocked — CAPTCHA or anti-bot check' : 'Preparation failed'}
      </StatusPill>
    )
    : submitted
      ? <StatusPill tone="emerald">Submitted</StatusPill>
      : reconciliationRequired
        ? <StatusPill tone="purple">Reconciliation required</StatusPill>
        : outcome
          ? <StatusPill tone="amber">Review needed before authorizing submission</StatusPill>
          : <StatusPill tone="slate">Preparing…</StatusPill>;

  return (
    <SectionCard
      title="Ashby — review what Robin filled"
      hint="Robin can submit this application directly, once you authorize it below"
      actions={pill}
    >
      {error && <Alert tone="error">{error}</Alert>}
      {!plan && !error && <Loading>Loading what Robin found on the employer&apos;s form…</Loading>}

      {plan && reconciliationRequired && (
        <div className="space-y-3 text-sm">
          <p className="text-ink-soft">
            A real submission click was attempted for this Ashby application, but the employer&apos;s
            acceptance could not be confirmed. Robin will never retry automatically.
            {reconciliation?.category ? ` [${reconciliation.category}]: ${reconciliation.evidence ?? ''}` : ''}
          </p>
          <p className="text-xs text-ink-muted">
            If the employer&apos;s system confirmed non-acceptance (submission rejected), safe recovery can prepare a new independent attempt using the action below.
          </p>
        </div>
      )}

      {plan && stopped && (
        <div className="space-y-3 text-sm">
          <p className="text-ink-soft">
            {status === 'BLOCKED_ANTI_BOT'
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
            This queues a fresh preparation attempt with your latest reviewed documents — it will not
            submit anything by itself.
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
            title="Needs your input before you can authorize submission"
            tone="amber"
            items={outcome.requiredGaps}
            emptyText="Nothing is outstanding."
          />

          <DocumentStatus
            title="Résumé"
            state={outcome.resumeUploadState}
            reason={outcome.resumeUploadReason}
            noFieldText="This form has no résumé field, or none was found."
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
          </div>
          <p className="text-[11px] text-ink-faint">
            {outcome.requiredGaps.length > 0
              ? 'Resolve the outstanding fields above, then use the review and approval actions below ' +
                'to authorize a real submission once you are ready — Robin will not submit until you do.'
              : 'Everything above is ready for your review. Use the actions in the Automation panel ' +
                'below to acknowledge your review, approve, and — as a separate, explicit step — ' +
                'authorize Robin to submit this application directly on Ashby.'}
          </p>
        </div>
      )}
    </SectionCard>
  );
};

const CATEGORY_LABELS: Record<string, string> = {
  IDENTITY_NAME: 'Legal name',
  IDENTITY_EMAIL: 'Email',
  IDENTITY_PHONE: 'Phone',
  IDENTITY_LINKEDIN: 'LinkedIn',
  RESUME_UPLOAD: 'Résumé upload',
  CAPTCHA: 'CAPTCHA',
  REQUIRES_HUMAN: 'Needs your input',
  UNRECOGNIZED: 'Unrecognized field',
};

/**
 * Mirrors {@code ZohoRecruitReviewPanel}'s own `DocumentStatus` exactly —
 * same honesty rule: `ATTACHED` is never phrased as a completed upload,
 * since a successful browser-side file selection is not proof the
 * employer's server received anything. Ashby has no cover-letter field on
 * the one posting this adapter was verified against, so only a résumé
 * status is ever shown here.
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
        Not attached automatically{reason ? `: ${reason}` : ''}.
      </p>
    )}
    {state === 'ATTACHED' && (
      <p className="mt-1 text-emerald-700">
        Attached to the form — not yet submitted to the employer.
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
