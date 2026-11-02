import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import type { AutomationPlanView, LeverField, LeverOutcome, PlanErrorResult } from '../types';
import { Alert, Loading, SectionCard, StatusPill } from './ui';

/**
 * Phase 6: the human-review experience for a Lever automation plan —
 * deliberately NOT the Greenhouse "Approve for submission" flow
 * (`ApplyWorkspace`), because this adapter has no real-submission capability
 * at all: Lever's real submission is gated behind an hCaptcha widget Robin
 * never attempts to solve, and approving a LEVER plan for submission would
 * 409 against the backend's own precondition (only GREENHOUSE execution
 * plans can be approved for submission). Shown instead whenever
 * `application.planType === 'LEVER'`.
 *
 * Mirrors {@code ZohoRecruitReviewPanel} closely: reads the plan's
 * structured `result` through the existing, generic
 * `GET /automation/plans/{id}` endpoint (no Lever-specific API was added),
 * "I finished this on the employer's site" reuses the existing generic
 * `/review` acknowledgement endpoint, and a stopped (FAILED/BLOCKED_ANTI_BOT)
 * plan offers the same generic "Retry preparation"
 * (`POST /applications/{id}/re-prepare`) action.
 */
export const LeverReviewPanel: React.FC<{
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
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'Could not load this Lever plan'));
  }, [planId]);

  useEffect(() => {
    load();
  }, [load]);

  const outcome: LeverOutcome | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'LEVER_OUTCOME') as LeverOutcome | undefined ?? null;
  const planError: PlanErrorResult | null =
    (plan?.result ?? []).find((entry) => entry?.type === 'PLAN_ERROR') as PlanErrorResult | undefined ?? null;
  const effectiveUrl = applicationUrl || outcome?.applicationUrl || plan?.targetUrl || null;
  const stopped = plan?.status === 'FAILED' || plan?.status === 'BLOCKED_ANTI_BOT';

  const markReviewed = async () => {
    setReviewing(true);
    setError(null);
    try {
      await apiFetch(`/automation/plans/${planId}/review`, { method: 'POST', body: JSON.stringify({ acknowledge: true }) });
      setReviewed(true);
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not record that you reviewed this');
    } finally {
      setReviewing(false);
    }
  };

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
    ? <StatusPill tone="red">{plan?.status === 'BLOCKED_ANTI_BOT' ? 'Blocked — CAPTCHA or anti-bot check' : 'Preparation failed'}</StatusPill>
    : outcome
      ? <StatusPill tone="amber">Human review required</StatusPill>
      : <StatusPill tone="slate">Preparing…</StatusPill>;

  return (
    <SectionCard title="Lever — your review is needed" hint="Robin prepared this application but never submits on Lever" actions={pill}>
      {error && <Alert tone="error">{error}</Alert>}
      {!plan && !error && <Loading>Loading what Robin found on the employer&apos;s form…</Loading>}

      {plan && stopped && (
        <div className="space-y-3 text-sm">
          <p className="text-ink-soft">
            {plan.status === 'BLOCKED_ANTI_BOT'
              ? 'Robin stopped before finishing this form because the employer’s site presented a CAPTCHA, a login wall, or an anti-bot check — Robin never attempts to solve or bypass these.'
              : 'Robin stopped before finishing this form.'}
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
          <p className="text-[11px] text-ink-faint">
            This queues a fresh preparation attempt with your latest reviewed documents and answers
            — it will not resubmit anything, since Robin never submits on Lever.
          </p>
        </div>
      )}

      {plan && !stopped && !outcome && (
        <p className="text-sm text-ink-muted">The worker has not yet reported what it found on this form. Check back shortly, or refresh.</p>
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
          <DocumentStatus state={outcome.resumeUploadState} reason={outcome.resumeUploadReason} />

          <div className="flex flex-wrap items-center gap-3 border-t border-line pt-3">
            {effectiveUrl && (
              <a href={effectiveUrl} target="_blank" rel="noopener noreferrer"
                className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900">
                Open the employer&apos;s application page
              </a>
            )}
            <button type="button" disabled={reviewing || reviewed} onClick={markReviewed}
              className="rounded-md border border-emerald-500/40 px-3 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 disabled:opacity-50">
              {reviewed ? 'Marked as reviewed' : reviewing ? 'Recording…' : "I finished this on the employer's site"}
            </button>
          </div>
          <p className="text-[11px] text-ink-faint">
            Robin never submits on Lever — its real submission is protected by a CAPTCHA, which makes
            that unsafe to automate. Finish and submit the application yourself on the employer&apos;s
            own page; marking this reviewed only records that you have seen it.
          </p>
        </div>
      )}
    </SectionCard>
  );
};

const DocumentStatus: React.FC<{ state: string; reason?: string | null }> = ({ state, reason }) => (
  <div>
    <p className="font-semibold text-ink-soft">Résumé</p>
    {state === 'NO_FIELD' && <p className="mt-1 text-ink-faint">This form has no résumé field, or none was found.</p>}
    {(state === 'NO_ARTIFACT' || state === 'REJECTED') && (
      <p className="mt-1 text-ink-muted">Not attached automatically{reason ? `: ${reason}` : ''}. Attach it yourself on the employer&apos;s page.</p>
    )}
    {state === 'ATTACHED' && (
      <p className="mt-1 text-emerald-700">Attached to the form — not yet submitted. Finish and submit this application yourself on the employer&apos;s page.</p>
    )}
  </div>
);

const FieldList: React.FC<{ title: string; tone: 'emerald' | 'amber'; items: LeverField[]; emptyText: string }> = ({
  title, tone, items, emptyText,
}) => (
  <div>
    <p className="font-semibold text-ink-soft">{title} ({items.length})</p>
    {items.length === 0 ? (
      <p className="mt-1 text-ink-faint">{emptyText}</p>
    ) : (
      <ul className={`mt-1 space-y-0.5 ${tone === 'emerald' ? 'text-emerald-700' : 'text-amber-700'}`}>
        {items.map((item, index) => (
          <li key={`${item.label ?? item.key}-${index}`}>{item.label || item.key}</li>
        ))}
      </ul>
    )}
  </div>
);
