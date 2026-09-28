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
  PrimaryButton,
  Select,
  TextInput,
} from '../components/ui';

/**
 * Jobs Feed — browse and search every discovered UK posting.
 *
 * Presentation-only redesign: the same fetch pipeline (full-text search + status
 * filter + limit), the same URL-import handler, the same job data. The list is
 * now a responsive card grid (1 col mobile / 2 col tablet / 3 col desktop)
 * built on the shared JobCard primitive.
 */
export const JobsFeedPage: React.FC = () => {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [loading, setLoading] = useState(false);
  const [importUrl, setImportUrl] = useState('');
  const [importMsg, setImportMsg] = useState<string | null>(null);

  const fetchJobs = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('q', search.trim());
      if (statusFilter) params.set('status', statusFilter);
      params.set('limit', '50');

      const res = await apiFetch<{ items: Job[]; next_cursor?: string }>(`/jobs?${params.toString()}`);
      setJobs(res.items || []);
    } catch {
      setJobs([]);
    } finally {
      setLoading(false);
    }
  }, [search, statusFilter]);

  useEffect(() => {
    fetchJobs();
  }, [fetchJobs]);

  const handleImport = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!importUrl) return;
    setImportMsg(null);
    try {
      const res = await apiFetch<{ status: string }>('/jobs/import-url', {
        method: 'POST',
        body: JSON.stringify({ url: importUrl }),
      });
      setImportMsg(`Job URL queued for resolution (Status: ${res.status})`);
      setImportUrl('');
    } catch (err: unknown) {
      setImportMsg(err instanceof Error ? err.message : 'Import failed');
    }
  };

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Opportunities"
          title="Jobs Feed"
          subtitle="Every posting Robin has discovered, with full-text search across titles, companies and descriptions."
        />

        {/* Add a job by URL — same /jobs/import-url handler as before */}
        <section className="rounded-xl border border-line bg-surface p-4 shadow-card sm:p-5">
          <form onSubmit={handleImport} className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="flex-1">
              <label htmlFor="import-url" className="mb-1 block text-xs font-semibold text-ink-soft">
                Add a posting by link
              </label>
              <TextInput
                id="import-url"
                type="url"
                value={importUrl}
                onChange={(e) => setImportUrl(e.target.value)}
                placeholder="Paste a LinkedIn or ATS job URL (e.g. boards.greenhouse.io/...)"
              />
            </div>
            <PrimaryButton type="submit" className="sm:mb-0 sm:self-end">
              Quick add
            </PrimaryButton>
          </form>
          {importMsg && (
            <div className="mt-3">
              <Alert tone="info">{importMsg}</Alert>
            </div>
          )}
        </section>

        {/* Search + status filter — same query params as before */}
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
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search keywords (e.g. Kotlin, Kubernetes, Senior)…"
              className="pl-9"
              aria-label="Search jobs"
            />
          </div>
          <div className="sm:w-52">
            <Select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              aria-label="Filter by pipeline status"
            >
              <option value="">All statuses</option>
              <option value="DISCOVERED">DISCOVERED</option>
              <option value="FILTERED_OUT">FILTERED_OUT</option>
              <option value="ANALYSED">ANALYSED</option>
              <option value="SCORED">SCORED</option>
              <option value="DECIDED">DECIDED</option>
              <option value="ARCHIVED">ARCHIVED</option>
            </Select>
          </div>
        </div>

        {/* Results */}
        {loading ? (
          <Loading>Searching the job database…</Loading>
        ) : jobs.length === 0 ? (
          <EmptyState
            title="No matching jobs yet"
            body="Try different keywords or clear the status filter — or add a posting directly with Quick add above."
          />
        ) : (
          <>
            <p className="text-xs font-medium text-ink-muted" role="status">
              {jobs.length} {jobs.length === 1 ? 'job' : 'jobs'} found
            </p>
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
              {jobs.map((job) => (
                <JobCard
                  key={job.id}
                  job={job}
                  actions={
                    <span className="inline-flex items-center gap-1 text-xs font-semibold text-forest-700">
                      View details
                      <svg viewBox="0 0 16 16" fill="none" className="h-3.5 w-3.5" aria-hidden="true">
                        <path
                          d="m6 3.5 4.5 4.5L6 12.5"
                          stroke="currentColor"
                          strokeWidth="1.6"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        />
                      </svg>
                    </span>
                  }
                  footer={
                    job.skills_extracted && job.skills_extracted.length > 6 ? (
                      <span className="text-[11px] text-ink-faint">
                        +{job.skills_extracted.length - 6} more skills listed
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1.5 text-[11px] text-ink-faint">
                        <Chip>{job.employment_type || 'FULL_TIME'}</Chip>
                      </span>
                    )
                  }
                />
              ))}
            </div>
          </>
        )}
      </div>
    </PageShell>
  );
};
