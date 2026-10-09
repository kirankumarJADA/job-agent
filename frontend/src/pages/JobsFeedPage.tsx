import React, { useEffect, useState, useCallback } from 'react';
import { apiFetch } from '../api/client';
import { Job, JobSource } from '../types';
import {
  Alert,
  Chip,
  EmptyState,
  JobCard,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  Select,
  TextInput,
} from '../components/ui';

interface SourceHealthResponse {
  id: string;
  display_name?: string;
  kind?: string;
  enabled?: boolean;
  failure_streak?: number;
  last_run_at?: string | null;
  health?: string | Record<string, unknown> | null;
}

interface DiscoveryMessage {
  tone: 'success' | 'error' | 'info';
  text: string;
}

function parseHealth(value: SourceHealthResponse['health']): Record<string, unknown> {
  if (value && typeof value === 'object') return value;
  if (typeof value === 'string') {
    try {
      const parsed: unknown = JSON.parse(value);
      if (parsed && typeof parsed === 'object') return parsed as Record<string, unknown>;
    } catch {
      // An unparseable health payload is treated as unknown, not success.
    }
  }
  return {};
}

/**
 * FIND workspace — a real job catalogue/search view, with a discovery action
 * for enabled Greenhouse/Ashby boards through the existing source health-check
 * endpoint. That endpoint actually invokes board discovery and records source
 * health; it is not a placeholder action.
 */
export const JobsFeedPage: React.FC = () => {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [loading, setLoading] = useState(false);
  const [jobsLoaded, setJobsLoaded] = useState(false);
  const [jobsError, setJobsError] = useState<string | null>(null);
  const [sources, setSources] = useState<JobSource[] | null>(null);
  const [sourcesError, setSourcesError] = useState<string | null>(null);
  const [discoveringId, setDiscoveringId] = useState<string | null>(null);
  const [discoveryMessage, setDiscoveryMessage] = useState<DiscoveryMessage | null>(null);
  const [importUrl, setImportUrl] = useState('');
  const [importMsg, setImportMsg] = useState<DiscoveryMessage | null>(null);

  const fetchJobs = useCallback(async () => {
    setLoading(true);
    setJobsError(null);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('q', search.trim());
      if (statusFilter) params.set('status', statusFilter);
      params.set('limit', '50');

      const response = await apiFetch<{ items: Job[]; next_cursor?: string }>(
        '/jobs?' + params.toString(),
      );
      setJobs(response.items || []);
    } catch (error: unknown) {
      setJobs([]);
      setJobsError(error instanceof Error ? error.message : 'Could not load the job catalogue.');
    } finally {
      setJobsLoaded(true);
      setLoading(false);
    }
  }, [search, statusFilter]);

  const fetchSources = useCallback(async () => {
    setSourcesError(null);
    try {
      const response = await apiFetch<JobSource[]>('/sources');
      setSources(response || []);
    } catch (error: unknown) {
      setSources(null);
      setSourcesError(error instanceof Error ? error.message : 'Could not load discovery sources.');
    }
  }, []);

  useEffect(() => {
    void fetchJobs();
  }, [fetchJobs]);

  useEffect(() => {
    void fetchSources();
  }, [fetchSources]);

  const runDiscovery = async (source: JobSource) => {
    if (!source.enabled || !['GREENHOUSE', 'ASHBY'].includes(source.kind.toUpperCase())) return;
    setDiscoveringId(source.id);
    setDiscoveryMessage(null);
    try {
      // SourcesController invokes the real board connector for these source
      // types and persists source health. It does not submit applications.
      const result = await apiFetch<SourceHealthResponse>(
        '/sources/' + encodeURIComponent(source.id) + '/health-check',
        { method: 'POST' },
      );
      const health = parseHealth(result.health);
      const status = typeof health.status === 'string' ? health.status : '';
      const detail = typeof health.detail === 'string' ? health.detail : '';
      const failure = typeof health.error === 'string' ? health.error : '';

      if (status === 'ok') {
        setDiscoveryMessage({
          tone: 'success',
          text: source.display_name + ': discovery completed. ' + (detail || 'The source health check succeeded.'),
        });
      } else if (status === 'failed') {
        setDiscoveryMessage({
          tone: 'error',
          text: source.display_name + ': discovery reported a failure. ' + (failure || 'Check the source health record for details.'),
        });
      } else {
        setDiscoveryMessage({
          tone: 'info',
          text: source.display_name + ': the request returned, but the API did not provide a confirmed discovery outcome. Check Job Sources for the recorded state.',
        });
      }

      // Refresh even after partial failure: a connector may have ingested some
      // jobs before reporting an extraction error.
      await fetchJobs();
      await fetchSources();
    } catch (error: unknown) {
      setDiscoveryMessage({
        tone: 'error',
        text: source.display_name + ': ' + (error instanceof Error ? error.message : 'Discovery failed.'),
      });
      await fetchSources();
    } finally {
      setDiscoveringId(null);
    }
  };

  const handleImport = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!importUrl.trim()) return;
    setImportMsg(null);
    try {
      const result = await apiFetch<{ status: string }>('/jobs/import-url', {
        method: 'POST',
        body: JSON.stringify({ url: importUrl.trim() }),
      });
      if (result.status === 'RESOLUTION_PENDING') {
        setImportMsg({
          tone: 'info',
          text: 'The URL passed validation, but resolution is not implemented by this endpoint yet. This action did not import a job into your feed.',
        });
      } else {
        setImportMsg({
          tone: 'info',
          text: 'The API returned status: ' + result.status + '. Refresh the feed to check whether a job was ingested.',
        });
      }
      setImportUrl('');
    } catch (error: unknown) {
      setImportMsg({
        tone: 'error',
        text: error instanceof Error ? error.message : 'The URL could not be validated.',
      });
    }
  };

  const supportedSources = (sources || []).filter((source) =>
    ['GREENHOUSE', 'ASHBY'].includes(source.kind.toUpperCase()),
  );

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Stage 1 · FIND"
          title="Find jobs"
          subtitle="Discover jobs from supported public ATS boards, search the indexed catalogue and inspect each posting's match details."
          actions={<SecondaryButton href="/sources">Manage sources</SecondaryButton>}
        />

        <section className="rounded-xl border border-line bg-surface p-4 shadow-card sm:p-5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h2 className="text-sm font-semibold text-ink">Run discovery</h2>
              <p className="mt-1 max-w-2xl text-xs leading-relaxed text-ink-muted">
                These actions run the configured public Greenhouse or Ashby board connector and refresh the feed. Only enabled sources can run. This does not apply to jobs.
              </p>
            </div>
            {sources && <span className="rounded-full bg-forest-50 px-2.5 py-1 text-xs font-semibold text-forest-800">{supportedSources.filter((source) => source.enabled).length} enabled boards</span>}
          </div>

          {sourcesError && <div className="mt-3"><Alert tone="error">Discovery sources could not be loaded: {sourcesError}</Alert></div>}
          {discoveryMessage && <div className="mt-3"><Alert tone={discoveryMessage.tone}>{discoveryMessage.text}</Alert></div>}

          {sources === null && !sourcesError ? (
            <div className="mt-3"><Loading>Loading configured discovery sources…</Loading></div>
          ) : supportedSources.length === 0 ? (
            <div className="mt-3">
              <EmptyState
                title="No supported board sources configured"
                body="Add or enable a Greenhouse or Ashby source to run discovery here. Other connector types may require a separate URL-based workflow."
                actions={<SecondaryButton href="/sources">Open Job Sources</SecondaryButton>}
              />
            </div>
          ) : (
            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
              {supportedSources.map((source) => (
                <div key={source.id} className="flex items-center justify-between gap-3 rounded-lg border border-line p-3">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-ink">{source.display_name}</p>
                    <p className="mt-0.5 text-xs text-ink-muted">
                      {source.kind} · {source.org_identifier || 'No board identifier'}
                    </p>
                    <p className="mt-1 text-[11px] text-ink-faint">
                      {source.enabled ? 'Enabled source' : 'Disabled source'}
                      {source.last_run_at ? ' · Last run recorded' : ' · No prior run recorded'}
                      {source.failure_streak > 0 ? ' · Consecutive failures: ' + source.failure_streak : ''}
                    </p>
                  </div>
                  <PrimaryButton
                    onClick={() => void runDiscovery(source)}
                    disabled={!source.enabled || discoveringId !== null || !source.org_identifier}
                    className="shrink-0 px-3 py-2 text-xs"
                  >
                    {discoveringId === source.id ? 'Discovering…' : 'Discover'}
                  </PrimaryButton>
                </div>
              ))}
            </div>
          )}
        </section>

        <section className="rounded-xl border border-line bg-surface p-4 shadow-card sm:p-5">
          <form onSubmit={handleImport} className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="flex-1">
              <label htmlFor="import-url" className="mb-1 block text-xs font-semibold text-ink-soft">
                Check a direct job URL
              </label>
              <TextInput
                id="import-url"
                type="url"
                value={importUrl}
                onChange={(event) => setImportUrl(event.target.value)}
                placeholder="Paste a public job posting URL"
              />
              <p className="mt-1 text-[11px] text-ink-faint">
                The current endpoint validates the URL but returns RESOLUTION_PENDING; it does not yet ingest the job.
              </p>
            </div>
            <PrimaryButton type="submit" className="sm:self-end">Validate URL</PrimaryButton>
          </form>
          {importMsg && <div className="mt-3"><Alert tone={importMsg.tone}>{importMsg.text}</Alert></div>}
        </section>

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
            body="No indexed jobs match this search and status filter. Run discovery from an enabled source above or broaden your search."
          />
        ) : (
          <>
            <p className="text-xs font-medium text-ink-muted" role="status">
              Showing {jobs.length} indexed {jobs.length === 1 ? 'job' : 'jobs'} in this result page.
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
