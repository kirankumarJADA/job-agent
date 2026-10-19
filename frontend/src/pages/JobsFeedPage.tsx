import React, { useEffect, useState, useCallback } from 'react';
import { apiFetch } from '../api/client';
import { Job } from '../types';
import {
  Alert,
  Chip,
  EmptyState,
  JobCard,
  Loading,
  PageHeader,
  PageShell,
  SecondaryButton,
  Select,
  TextInput,
} from '../components/ui';

const PAGE_SIZE = 50;

/**
 * Describes exactly what is loaded. Counts refer to the loaded pages only —
 * the catalogue is cursor-paginated, so no total is claimed.
 */
export function feedSummary(jobs: Job[], hasMore: boolean): string {
  const scored = jobs.filter((job) => job.match_score != null).length;
  const recommended = jobs.filter(
    (job) => job.match_recommendation === 'APPLY' || job.match_recommendation === 'REVIEW',
  ).length;
  const stale = jobs.filter((job) => job.stale).length;
  const parts = [
    `${jobs.length} ${jobs.length === 1 ? 'job' : 'jobs'} loaded`,
    `${scored} scored for you`,
    `${recommended} recommended (APPLY or REVIEW)`,
  ];
  if (stale > 0) parts.push(`${stale} not seen for 30+ days`);
  return parts.join(' · ') + (hasMore ? ' · more available' : '');
}

/**
 * FIND workspace — the candidate's job catalogue/search view.
 *
 * Source hiding (Phase 8.2.5): this page never shows which website, ATS
 * platform or aggregator a posting came from. Running discovery, importing a
 * board URL and source health live on the operational Job Sources page.
 */
export const JobsFeedPage: React.FC = () => {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [loading, setLoading] = useState(false);
  const [jobsLoaded, setJobsLoaded] = useState(false);
  const [jobsError, setJobsError] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string>('');
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  const buildQuery = useCallback((cursor?: string) => {
    const params = new URLSearchParams();
    if (search.trim()) params.set('q', search.trim());
    if (statusFilter) params.set('status', statusFilter);
    params.set('limit', String(PAGE_SIZE));
    if (cursor) params.set('cursor', cursor);
    return '/jobs?' + params.toString();
  }, [search, statusFilter]);

  const fetchJobs = useCallback(async () => {
    setLoading(true);
    setJobsError(null);
    setLoadMoreError(null);
    try {
      const response = await apiFetch<{ items: Job[]; next_cursor?: string }>(buildQuery());
      setJobs(response.items || []);
      setNextCursor(response.next_cursor || '');
    } catch (error: unknown) {
      setJobs([]);
      setNextCursor('');
      setJobsError(error instanceof Error ? error.message : 'Could not load the job catalogue.');
    } finally {
      setJobsLoaded(true);
      setLoading(false);
    }
  }, [buildQuery]);

  // Keyset pagination: the API returns next_cursor only when more rows exist.
  const loadMore = async () => {
    if (!nextCursor) return;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const response = await apiFetch<{ items: Job[]; next_cursor?: string }>(buildQuery(nextCursor));
      setJobs((current) => {
        const seen = new Set(current.map((job) => job.id));
        return [...current, ...(response.items || []).filter((job) => !seen.has(job.id))];
      });
      setNextCursor(response.next_cursor || '');
    } catch (error: unknown) {
      setLoadMoreError(error instanceof Error ? error.message : 'Could not load more jobs.');
    } finally {
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    void fetchJobs();
  }, [fetchJobs]);

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Stage 1 · FIND"
          title="Find jobs"
          subtitle="Search the indexed job catalogue and inspect each posting's match details."
          actions={<SecondaryButton href="/sources">Manage discovery</SecondaryButton>}
        />

        <div className="flex flex-col gap-3 rounded-xl border border-line bg-surface p-4 shadow-card sm:flex-row sm:items-center">
          <div className="relative flex-1">
            <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-ink-faint">
              <svg viewBox="0 0 20 20" fill="none" className="h-4 w-4" aria-hidden="true">
                <circle cx="9" cy="9" r="5.5" stroke="currentColor" strokeWidth="1.6" />
                <path d="m13.5 13.5 3 3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
            </span>
            <TextInput
              type="text"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search indexed job text (e.g. Java, Kubernetes, Senior)…"
              className="pl-9"
              aria-label="Search jobs"
            />
          </div>
          <div className="sm:w-52">
            <Select
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
              aria-label="Filter by pipeline status"
            >
              <option value="">All statuses</option>
              <option value="DISCOVERED">DISCOVERED</option>
              <option value="FILTERED_OUT">FILTERED_OUT</option>
              <option value="ANALYSED">ANALYSED</option>
              <option value="SCORED">SCORED</option>
              <option value="DECIDED">DECIDED</option>
              <option value="ARCHIVED">ARCHIVED</option>
              <option value="PIPELINE_ERROR">PIPELINE_ERROR</option>
            </Select>
          </div>
          <SecondaryButton onClick={() => void fetchJobs()} disabled={loading}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </SecondaryButton>
        </div>

        {jobsError && <Alert tone="error">Job catalogue could not be loaded: {jobsError}</Alert>}

        {loading && !jobsLoaded ? (
          <Loading>Loading the indexed job catalogue…</Loading>
        ) : loading ? (
          <Loading>Refreshing job results…</Loading>
        ) : jobsError ? (
          <EmptyState title="Job listings are unavailable" body="Robin couldn't retrieve the job catalogue. This is an API/load failure, not an empty feed." actions={<SecondaryButton onClick={() => void fetchJobs()}>Retry</SecondaryButton>} />
        ) : jobs.length === 0 ? (
          <EmptyState
            title="No matching jobs"
            body="No indexed jobs match this search and status filter. Broaden your search, or run discovery from Job Sources."
          />
        ) : (
          <>
            <p className="text-xs font-medium text-ink-muted" role="status">
              {feedSummary(jobs, Boolean(nextCursor))}
            </p>
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
              {jobs.map((job) => (
                <JobCard
                  key={job.id}
                  job={job}
                  actions={<span className="inline-flex items-center gap-1 text-xs font-semibold text-forest-700">View match details →</span>}
                  footer={
                    job.skills_extracted && job.skills_extracted.length > 6 ? (
                      <span className="text-[11px] text-ink-faint">+{job.skills_extracted.length - 6} more skills listed</span>
                    ) : job.employment_type ? (
                      <span className="inline-flex items-center gap-1.5 text-[11px] text-ink-faint">
                        <Chip>{job.employment_type}</Chip>
                      </span>
                    ) : null
                  }
                />
              ))}
            </div>
            {loadMoreError && <Alert tone="error">More jobs could not be loaded: {loadMoreError}</Alert>}
            {nextCursor && (
              <div className="flex justify-center">
                <SecondaryButton onClick={() => void loadMore()} disabled={loadingMore}>
                  {loadingMore ? 'Loading more…' : 'Load more jobs'}
                </SecondaryButton>
              </div>
            )}
          </>
        )}
      </div>
    </PageShell>
  );
};
