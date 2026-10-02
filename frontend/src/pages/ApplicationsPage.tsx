import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { ApplicationSummary, ApplicationTimelineEvent, AutomationPackageView } from '../types';
import {
  EmptyState,
  Loading,
  PageHeader,
  PageShell,
  SectionCard,
  StatusPill,
} from '../components/ui';

/**
 * Applications — the candidate's queue of auto-created and tracked
 * applications, newest first.
 *
 * Rows come from GET /api/v1/applications (owner-scoped; the pipeline creates
 * them from APPLY match decisions and preparation attaches the tailored CV,
 * cover letter and draft answers). Phase 2 adds automation plan status:
 * inspection plans are created after preparation and executed by the worker.
 */
export const ApplicationsPage: React.FC = () => {
  const [applications, setApplications] = useState<ApplicationSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reviewingPlan, setReviewingPlan] = useState<string | null>(null);
  const [approvingPlan, setApprovingPlan] = useState<string | null>(null);
  const [reviewError, setReviewError] = useState<string | null>(null);

  const fetchApplications = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<{ items: ApplicationSummary[] }>('/applications');
      setApplications(res.items || []);
    } catch {
      setError('Could not load your applications. Please try again.');
      setApplications([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchApplications();
  }, [fetchApplications]);

  const acknowledgeReview = async (planId: string) => {
    setReviewingPlan(planId);
    setReviewError(null);
    try {
      await apiFetch(`/automation/plans/${planId}/review`, {
        method: 'POST',
        body: JSON.stringify({ acknowledge: true }),
      });
      await fetchApplications();
    } catch (err) {
      setReviewError(err instanceof Error ? err.message : 'Could not record review');
    } finally {
      setReviewingPlan(null);
    }
  };

  const approvePlan = async (planId: string) => {
    setApprovingPlan(planId);
    setReviewError(null);
    try {
      // Explicit APPROVED_FOR_SUBMISSION. The backend keeps actual submission
      // disabled: the plan becomes READY_TO_SUBMIT and nothing ever submits it.
      await apiFetch(`/automation/plans/${planId}/approve-submit`, { method: 'POST' });
      await fetchApplications();
    } catch (err) {
      setReviewError(err instanceof Error ? err.message : 'Approval failed');
    } finally {
      setApprovingPlan(null);
    }
  };

  return (
    <PageShell>
      <PageHeader
        eyebrow="Pipeline"
        title="Applications"
        subtitle="Applications created from your APPLY match decisions, with preparation and automation status."
        actions={
          <button
            type="button"
            onClick={fetchApplications}
            className="rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-ink-body transition-colors hover:bg-surface-sunken"
          >
            Refresh
          </button>
        }
      />

      {loading && <Loading>Loading your applications…</Loading>}

      {error && (
        <div className="mb-4 rounded-lg border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-300" role="alert">
          {error}
        </div>
      )}

      {!loading && !error && applications.length === 0 && (
        <EmptyState
          title="No applications yet"
          body="When a discovered job matches your profile strongly enough (APPLY), an application is created here automatically and prepared for review."
        />
      )}

      {reviewError && <div className="mb-4 rounded-lg border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-300" role="alert">{reviewError}</div>}

      {!loading && applications.length > 0 && (
        <div className="space-y-3">
          {applications.map((application) => (
            <SectionCard
              key={application.id}
              title={application.jobTitle}
              hint={<span className="text-sm text-ink-muted">{application.company}</span>}
              actions={<StatusPill>{application.status}</StatusPill>}
            >
              <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
                <div>
                  <dt className="text-ink-muted">Location</dt>
                  <dd className="text-ink-body">{application.jobLocation || '—'}</dd>
                </div>
                <div>
                  <dt className="text-ink-muted">Match</dt>
                  <dd className="text-ink-body">
                    {application.matchScore != null
                      ? `${application.matchScore}/100 · ${application.matchRecommendation || '—'}`
                      : '—'}
                  </dd>
                </div>
                <div>
                  <dt className="text-ink-muted">Mode</dt>
                  <dd className="text-ink-body">{application.mode}</dd>
                </div>
                <div>
                  <dt className="text-ink-muted">Created</dt>
                  <dd className="text-ink-body">
                    {new Date(application.createdAt).toLocaleDateString(undefined, {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                    })}
                  </dd>
                </div>
              </dl>

              {application.planId && (
                <div className="mt-3 rounded-lg border border-line bg-surface-sunken px-3 py-2">
                  <div className="flex items-center gap-3 text-sm">
                    <span className="text-ink-muted">Automation</span>
                    <PlanStatusBadge status={application.planStatus} />
                    {application.planStatus === 'AWAITING_APPROVAL' && (
                      <span className="text-amber-400 text-xs font-medium">Human review required</span>
                    )}
                    {application.planStatus === 'READY_TO_SUBMIT' && (
                      <span className="text-emerald-400 text-xs font-medium">Ready to submit — actual submission is disabled</span>
                    )}
                    {application.planStatus === 'AWAITING_SUBMIT_APPROVAL' && (
                      <span className="text-red-400 text-xs font-medium">Submission is disabled</span>
                    )}
                    {application.planStatus === 'FAILED' && (
                      <span className="text-red-400 text-xs">Plan failed</span>
                    )}
                    {application.planStatus === 'BLOCKED_ANTI_BOT' && (
                      <span className="text-red-400 text-xs">Blocked by anti-bot</span>
                    )}
                    {application.planStatus === 'AWAITING_APPROVAL' && application.planId && (
                      <div className="ml-auto flex items-center gap-2">
                        <button
                          type="button"
                          disabled={reviewingPlan === application.planId || approvingPlan === application.planId}
                          onClick={() => acknowledgeReview(application.planId!)}
                          className="rounded-md border border-amber-500/40 px-3 py-1 text-xs font-semibold text-amber-200 hover:bg-amber-500/10 disabled:opacity-50"
                        >
                          {reviewingPlan === application.planId ? 'Recording…' : 'I reviewed the form'}
                        </button>
                        <button
                          type="button"
                          disabled={reviewingPlan === application.planId || approvingPlan === application.planId}
                          onClick={() => approvePlan(application.planId!)}
                          className="rounded-md border border-emerald-500/40 px-3 py-1 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/10 disabled:opacity-50"
                        >
                          {approvingPlan === application.planId ? 'Approving…' : 'Approve for submission'}
                        </button>
                      </div>
                    )}
                  </div>
                  {application.planId && application.id && (
                    <RobinDecisionPanel planId={application.planId} applicationId={application.id} />
                  )}
                </div>
              )}
            </SectionCard>
          ))}
        </div>
      )}
    </PageShell>
  );
};

/**
 * The human-in-the-loop decision record for one automation plan, answering
 * the five questions the product owes the candidate: what Robin knows, what
 * it filled, what it could not determine, what it needs from the user, and
 * why anything was blocked. Data is owner-scoped: the read-only execution
 * package plus the application's own timeline.
 */
const RobinDecisionPanel: React.FC<{ planId: string; applicationId: string }> = ({ planId, applicationId }) => {
  const [open, setOpen] = useState(false);
  const [data, setData] = useState<{ pkg: AutomationPackageView; timeline: ApplicationTimelineEvent[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    Promise.all([
      apiFetch<AutomationPackageView>(`/automation/plans/${planId}/package`),
      apiFetch<{ items: ApplicationTimelineEvent[] }>(`/applications/${applicationId}/timeline`),
    ])
      .then(([pkg, timeline]) => setData({ pkg, timeline: timeline.items ?? [] }))
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'Could not load the decision record'));
  }, [planId, applicationId]);

  useEffect(() => {
    if (open && !data && !error) load();
  }, [open, data, error, load]);

  const blockedEvents = (data?.timeline ?? []).filter((event) =>
    event.type === 'AUTOMATION_PLAN_FAILED'
    || event.type === 'HARD_STOP'
    || (event.type === 'PREPARATION' && event.payload?.status === 'FAILED'));

  return (
    <div className="mt-2 border-t border-line pt-2">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="text-xs font-semibold text-ink-muted hover:text-ink"
      >
        {open ? '▾' : '▸'} What Robin did, could not determine, and needs from you
      </button>
      {open && (
        <div className="mt-2 space-y-3 text-xs">
          {error && <p className="text-red-400">{error}</p>}
          {!data && !error && <Loading>Loading decision record…</Loading>}
          {data && (
            <>
              <div>
                <p className="font-semibold text-ink-soft">What Robin knows about you</p>
                <p className="mt-1 text-ink-muted">
                  {data.pkg.candidate.fullName || '—'} · {data.pkg.candidate.email || '—'}
                  {data.pkg.candidate.phone ? ` · ${data.pkg.candidate.phone}` : ''}
                  {data.pkg.candidate.location ? ` · ${data.pkg.candidate.location}` : ''}
                  {data.pkg.cv ? ' · tailored CV attached' : ' · no CV attached'}
                  {data.pkg.coverLetter ? ' · cover letter attached' : ''}
                </p>
              </div>
              <div>
                <p className="font-semibold text-ink-soft">What Robin filled (deterministic, verified data only)</p>
                {(() => {
                  const filled = (data.pkg.fields ?? []).filter(
                    (field) => field.classification === 'SUPPORTED_AUTO' && field.value);
                  return filled.length > 0 ? (
                    <ul className="mt-1 space-y-0.5 text-ink-muted">
                      {filled.map((field) => (
                        <li key={field.key ?? field.label}>
                          {field.label}: <span className="font-mono text-ink">{field.value}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="mt-1 text-ink-faint">Nothing was auto-filled.</p>
                  );
                })()}
              </div>
              <div>
                <p className="font-semibold text-ink-soft">What Robin could not determine</p>
                {(() => {
                  const undetermined = [
                    ...(data.pkg.human ?? []),
                    ...(data.pkg.unsupported ?? []),
                    ...(data.pkg.fields ?? [])
                      .filter((field) => field.classification === 'REQUIRES_HUMAN' || field.classification === 'UNSUPPORTED')
                      .map((field) => ({ key: field.key ?? '', label: field.label, classification: field.classification, reason: field.reason })),
                  ];
                  const seen = new Set<string>();
                  const unique = undetermined.filter((item) => {
                    const dedupKey = `${item.key}|${item.reason}`;
                    if (seen.has(dedupKey)) return false;
                    seen.add(dedupKey);
                    return true;
                  });
                  return unique.length > 0 ? (
                    <ul className="mt-1 space-y-0.5 text-ink-muted">
                      {unique.map((item) => (
                        <li key={`${item.key}-${item.reason}`}>
                          {item.label || item.key || 'Field'} — {item.reason || item.classification}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="mt-1 text-ink-faint">Every field was determined.</p>
                  );
                })()}
              </div>
              <div>
                <p className="font-semibold text-ink-soft">What Robin needs from you</p>
                {(data.pkg.requiredGaps ?? []).length > 0 ? (
                  <ul className="mt-1 space-y-0.5 text-amber-300">
                    {data.pkg.requiredGaps.map((gap) => (
                      <li key={`${gap.key}-${gap.reason}`}>
                        {gap.label || gap.key}: {gap.reason}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="mt-1 text-ink-faint">
                    No required gaps. Review the filled fields above and use “Approve for submission” when ready
                    (submission itself stays disabled).
                  </p>
                )}
              </div>
              {blockedEvents.length > 0 && (
                <div>
                  <p className="font-semibold text-ink-soft">Why actions were blocked</p>
                  <ul className="mt-1 space-y-0.5 text-red-300">
                    {blockedEvents.map((event) => (
                      <li key={event.id}>
                        {String(event.payload?.step ?? event.type)}: {String(event.payload?.error ?? event.payload?.detail ?? 'blocked by safety rules')}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

function PlanStatusBadge({ status }: { status?: string | null }) {  if (!status) return null;
  const colors: Record<string, string> = {
    PREPARED: 'bg-blue-500/20 text-blue-300',
    RUNNING: 'bg-yellow-500/20 text-yellow-300',
    COMPLETED: 'bg-green-500/20 text-green-300',
    SUBMITTED: 'bg-green-500/20 text-green-300',
    FAILED: 'bg-red-500/20 text-red-300',
    BLOCKED_ANTI_BOT: 'bg-red-500/20 text-red-300',
    AWAITING_APPROVAL: 'bg-amber-500/20 text-amber-300',
    READY_TO_SUBMIT: 'bg-emerald-500/20 text-emerald-300',
    AWAITING_SUBMIT_APPROVAL: 'bg-red-500/20 text-red-300',
    ABANDONED: 'bg-gray-500/20 text-gray-400',
  };
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${colors[status] || 'bg-gray-500/20 text-gray-400'}`}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}
