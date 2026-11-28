import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import type {
  AutomationPlanView,
  HandoffSession,
  PlanErrorResult,
  SubmissionReconciliationResult,
  UniversalOutcome,
} from '../types';
import { Alert, Loading, SectionCard, StatusPill } from './ui';

/**
 * Candidate review, exception-recovery, and approval controls for the Universal
 * browser automation engine (`planType = UNIVERSAL`).
 */
export const UniversalReviewPanel: React.FC<{
  planId: string;
  planStatus?: string | null;
  applicationId?: string | null;
  embedded?: boolean;
  onChanged?: () => void;
}> = ({ planId, planStatus, applicationId, embedded = false, onChanged }) => {
  const [plan, setPlan] = useState<AutomationPlanView | null>(null);
  const [handoffSession, setHandoffSession] = useState<HandoffSession | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    apiFetch<AutomationPlanView>(`/automation/plans/${planId}`)
      .then(async (loaded) => {
        setPlan(loaded);
        const effStatus = planStatus ?? loaded?.status ?? null;
        if (effStatus === 'HANDOFF_REQUIRED') {
          try {
            const session = await apiFetch<HandoffSession>(`/handoff/plans/${planId}`);
            setHandoffSession(session);
          } catch {
            setHandoffSession(null);
          }
        } else {
          setHandoffSession(null);
        }
      })
      .catch((err: unknown) => {
        setError(parseActionError(err, 'Could not load this Universal automation plan.'));
      });
  }, [planId, planStatus]);

  useEffect(() => {
    load();
  }, [load]);

  const results = Array.isArray(plan?.result) ? plan.result : [];
  const outcome: UniversalOutcome | null =
    (results.find((entry) => entry != null && typeof entry === 'object' && entry.type === 'UNIVERSAL_OUTCOME') as
      | UniversalOutcome
      | undefined) ?? null;
  const planError: PlanErrorResult | null =
    (results.find((entry) => entry != null && typeof entry === 'object' && entry.type === 'PLAN_ERROR') as
      | PlanErrorResult
      | undefined) ?? null;
  const reconciliation: SubmissionReconciliationResult | null =
    (results.find(
      (entry): entry is SubmissionReconciliationResult =>
        entry != null && typeof entry === 'object' && entry.type === 'SUBMISSION_RECONCILIATION',
    )) ?? null;

  const status = planStatus ?? plan?.status ?? null;
  const requiredGaps = Array.isArray(outcome?.requiredGaps) ? outcome.requiredGaps : [];
  const validationErrors = Array.isArray(outcome?.validationErrors) ? outcome.validationErrors : [];
  const blockedResources = Array.isArray(outcome?.blockedResources) ? outcome.blockedResources : [];
  const closedShadowHosts = Array.isArray(outcome?.closedShadowHosts) ? outcome.closedShadowHosts : [];
  const unsupported = Array.isArray(outcome?.unsupported) ? outcome.unsupported : [];
  const filled = Array.isArray(outcome?.filled) ? outcome.filled : [];

  const runAction = async (key: string, endpoint: string, successMessage: string) => {
    if (busyAction !== null) return;
    setBusyAction(key);
    setError(null);
    setNotice(null);
    try {
      await apiFetch(endpoint, { method: 'POST' });
      setNotice(successMessage);
      load();
      onChanged?.();
    } catch (err) {
      setError(parseActionError(err, 'The server refused this action.'));
    } finally {
      setBusyAction(null);
    }
  };

  const pill = status === 'SUBMITTED'
    ? <StatusPill tone="emerald">Submitted</StatusPill>
    : status === 'READY_TO_SUBMIT'
      ? <StatusPill tone="emerald">Approved — ready to queue submit</StatusPill>
      : status === 'HANDOFF_REQUIRED'
        ? <StatusPill tone="amber">Paused for verification or login</StatusPill>
        : status === 'RECONCILIATION_REQUIRED'
          ? <StatusPill tone="purple">Reconciliation required</StatusPill>
          : status === 'FAILED' || status === 'BLOCKED_ANTI_BOT'
            ? <StatusPill tone="red">{status === 'BLOCKED_ANTI_BOT' ? 'Blocked by security check' : 'Execution failed'}</StatusPill>
            : status === 'AWAITING_APPROVAL'
              ? <StatusPill tone={requiredGaps.length > 0 || validationErrors.length > 0 ? 'amber' : 'sky'}>
                  {requiredGaps.length > 0 ? `${requiredGaps.length} unanswered requirement(s)` : 'Ready for candidate review'}
                </StatusPill>
              : <StatusPill tone="slate">{status || 'Loading…'}</StatusPill>;

  return (
    <SectionCard
      title="Universal browser execution"
      hint="Live-DOM multi-step preparation and guarded submission"
      actions={pill}
    >
      {!plan && !error && <Loading>Loading Universal execution state…</Loading>}
      {error && <Alert tone="error">{error}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}

      {plan && (
        <div className="space-y-3 text-sm" data-testid="universal-review-panel">
          {outcome && (
            <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2 lg:grid-cols-4 text-xs">
              <div>
                <dt className="text-ink-muted">Current step / page</dt>
                <dd className="text-ink-body font-medium">
                  Page {outcome.pageIndex ?? 1}
                  {outcome.completedPages && outcome.completedPages.length > 0
                    ? ` (completed: ${outcome.completedPages.join(', ')})`
                    : ''}
                </dd>
              </div>
              <div>
                <dt className="text-ink-muted">Auto-filled controls</dt>
                <dd className="text-ink-body font-medium">{filled.length} field(s)</dd>
              </div>
              <div>
                <dt className="text-ink-muted">CV upload</dt>
                <dd className="text-ink-body font-medium">{outcome.resumeUploadState || 'NO_FIELD'}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Cover letter upload</dt>
                <dd className="text-ink-body font-medium">{outcome.coverLetterUploadState || 'NO_FIELD'}</dd>
              </div>
            </dl>
          )}

          {outcome?.loginSucceeded && (
            <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-xs text-emerald-200">
              Signed in on employer portal using your saved credential for this domain.
            </div>
          )}

          {planError && (
            <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-200">
              <strong>Execution stopped ({planError.reason || planError.outcome}):</strong> {planError.message}
            </div>
          )}

          {reconciliation && (
            <div className="rounded-lg border border-purple-500/30 bg-purple-500/10 px-3 py-2 text-xs text-purple-200">
              <strong>Submission unconfirmed [{reconciliation.category}]:</strong> {reconciliation.evidence}
            </div>
          )}

          {requiredGaps.length > 0 && (
            <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200">
              <strong>Required items needing attention before continuing:</strong>
              <ul className="mt-1 list-disc pl-5 space-y-0.5">
                {requiredGaps.map((gap, idx) => (
                  <li key={gap.questionKey || gap.questionText || idx}>
                    {gap.questionText || gap.questionKey}
                    {gap.reason ? ` — ${gap.reason}` : ''}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {validationErrors.length > 0 && (
            <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-200">
              <strong>Form validation errors prevented advancing:</strong>
              <ul className="mt-1 list-disc pl-5 space-y-0.5">
                {validationErrors.map((msg) => <li key={msg}>{msg}</li>)}
              </ul>
            </div>
          )}

          {(closedShadowHosts.length > 0 || unsupported.length > 0) && (
            <div className="rounded-lg border border-sky-500/30 bg-sky-500/10 px-3 py-2 text-xs text-sky-200">
              <strong>Unsupported or closed controls skipped safely:</strong>
              <ul className="mt-1 list-disc pl-5 space-y-0.5">
                {closedShadowHosts.map((host) => (
                  <li key={host}>Closed Shadow DOM &lt;{host}&gt; cannot be inspected automatically.</li>
                ))}
                {unsupported.map((item, idx) => (
                  <li key={item.key || item.label || idx}>
                    {item.label || item.key}: {item.reason || 'Requires manual handling'}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {blockedResources.length > 0 && (
            <div className="rounded-lg border border-line bg-surface px-3 py-2 text-xs text-ink-muted">
              <strong>Blocked unsafe subresource request(s):</strong>{' '}
              {blockedResources.map((b) => `${b.url} (${b.reason})`).join(', ')}
            </div>
          )}

          <div className="flex flex-wrap items-center gap-2 pt-1">
            {status === 'AWAITING_APPROVAL' && !plan.submitApproved && (
              <button
                type="button"
                disabled={busyAction !== null}
                onClick={() => runAction(
                  'resume',
                  `/automation/plans/${planId}/resume`,
                  'Plan re-queued to resume form execution.',
                )}
                className="rounded-md border border-sky-500/40 px-3 py-1 text-xs font-semibold text-sky-200 hover:bg-sky-500/10 disabled:opacity-50"
              >
                {busyAction === 'resume' ? 'Resuming…' : 'Resume & retry form pass'}
              </button>
            )}

            {status === 'HANDOFF_REQUIRED' && handoffSession?.id && (
              <button
                type="button"
                disabled={busyAction !== null}
                onClick={() => runAction(
                  'handoff-resume',
                  `/handoff/${handoffSession.id}/resume`,
                  'Security blocker marked resolved; automation re-queued.',
                )}
                className="rounded-md border border-amber-500/50 bg-amber-500/10 px-3 py-1 text-xs font-semibold text-amber-200 hover:bg-amber-500/20 disabled:opacity-50"
              >
                {busyAction === 'handoff-resume' ? 'Resuming…' : 'Resume automation after verification'}
              </button>
            )}

            {!embedded && status === 'AWAITING_APPROVAL' && !plan.submitApproved && requiredGaps.length === 0 && (
              <button
                type="button"
                disabled={busyAction !== null}
                onClick={() => runAction(
                  'approve',
                  `/automation/plans/${planId}/approve-submit`,
                  'Approved for submission. Queue final submission explicitly when ready.',
                )}
                className="rounded-md border border-emerald-500/40 px-3 py-1 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/10 disabled:opacity-50"
              >
                {busyAction === 'approve' ? 'Approving…' : 'Approve for submission'}
              </button>
            )}

            {!embedded && status === 'READY_TO_SUBMIT' && plan.submitApproved && (
              <button
                type="button"
                disabled={busyAction !== null}
                onClick={() => runAction(
                  'prepare-submit',
                  `/automation/plans/${planId}/prepare-submit`,
                  'Final submission queued for the worker.',
                )}
                className="rounded-md bg-emerald-600 px-3 py-1 text-xs font-semibold text-white hover:bg-emerald-500 disabled:opacity-50"
              >
                {busyAction === 'prepare-submit' ? 'Queueing…' : 'Queue final submission'}
              </button>
            )}

            {(status === 'FAILED' || status === 'BLOCKED_ANTI_BOT') && (applicationId || plan.applicationId) && (
              <button
                type="button"
                disabled={busyAction !== null}
                onClick={() => runAction(
                  'reprepare',
                  `/applications/${applicationId || plan.applicationId}/re-prepare`,
                  'Application re-queued for preparation.',
                )}
                className="rounded-md border border-amber-500/40 px-3 py-1 text-xs font-semibold text-amber-200 hover:bg-amber-500/10 disabled:opacity-50"
              >
                {busyAction === 'reprepare' ? 'Retrying…' : 'Retry preparation'}
              </button>
            )}

            {!embedded && status === 'RECONCILIATION_REQUIRED' && (
              <button
                type="button"
                disabled={busyAction !== null}
                onClick={() => runAction(
                  'reconcile',
                  `/automation/plans/${planId}/reconcile`,
                  'Reconciliation completed.',
                )}
                className="rounded-md border border-purple-500/40 px-3 py-1 text-xs font-semibold text-purple-200 hover:bg-purple-500/10 disabled:opacity-50"
              >
                {busyAction === 'reconcile' ? 'Reconciling…' : 'Reconcile & prepare retry'}
              </button>
            )}
          </div>
        </div>
      )}
    </SectionCard>
  );
};

function parseActionError(err: unknown, fallback: string): string {
  const msg = err instanceof Error ? err.message : fallback;
  try {
    const parsed = JSON.parse(msg) as { error?: string; detail?: string; message?: string; blockers?: Array<{ message?: string }> };
    if (parsed && typeof parsed === 'object') {
      const base = parsed.error || parsed.detail || parsed.message || fallback;
      if (Array.isArray(parsed.blockers) && parsed.blockers.length > 0) {
        const details = parsed.blockers.map((b) => b.message).filter(Boolean).join('; ');
        if (details) return `${base}: ${details}`;
      }
      return base;
    }
  } catch {
    // non-JSON message
  }
  return msg || fallback;
}
