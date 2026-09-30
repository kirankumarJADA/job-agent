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
 * cover letter and draft answers). Preparation progress is visible through the
 * application status and the per-application timeline.
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
        subtitle="Applications created from your APPLY match decisions, with preparation status. Ready-to-apply packages are queued for the automation worker."
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
            </SectionCard>
          ))}
        </div>
      )}
    </PageShell>
  );
};
