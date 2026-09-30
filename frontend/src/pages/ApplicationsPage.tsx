import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { ApplicationSummary } from '../types';
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
                    {application.planStatus === 'AWAITING_SUBMIT_APPROVAL' && (
                      <span className="text-amber-400 text-xs font-medium">Needs approval</span>
                    )}
                    {application.planStatus === 'FAILED' && (
                      <span className="text-red-400 text-xs">Plan failed</span>
                    )}
                    {application.planStatus === 'BLOCKED_ANTI_BOT' && (
                      <span className="text-red-400 text-xs">Blocked by anti-bot</span>
                    )}
                  </div>
                </div>
              )}
            </SectionCard>
          ))}
        </div>
      )}
    </PageShell>
  );
};

function PlanStatusBadge({ status }: { status?: string | null }) {
  if (!status) return null;
  const colors: Record<string, string> = {
    PREPARED: 'bg-blue-500/20 text-blue-300',
    RUNNING: 'bg-yellow-500/20 text-yellow-300',
    COMPLETED: 'bg-green-500/20 text-green-300',
    SUBMITTED: 'bg-green-500/20 text-green-300',
    FAILED: 'bg-red-500/20 text-red-300',
    BLOCKED_ANTI_BOT: 'bg-red-500/20 text-red-300',
    AWAITING_SUBMIT_APPROVAL: 'bg-amber-500/20 text-amber-300',
    ABANDONED: 'bg-gray-500/20 text-gray-400',
  };
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${colors[status] || 'bg-gray-500/20 text-gray-400'}`}>
      {status.replace(/_/g, ' ')}
    </span>
  );
}
