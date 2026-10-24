import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { AtsCapabilityRow, DiscoveryRunSummary, Job } from '../types';
import {
  Alert,
  Chip,
  EmptyState,
  JobCard,
  Loading,
  PageHeader,
  PageShell,
  RemovableFilterChip,
  SecondaryButton,
  Select,
  TextInput,
} from '../components/ui';

const PAGE_SIZE = 50;
/** Keystroke/number-entry debounce before a new filter combination is actually fetched. */
const DEBOUNCE_MS = 400;

/**
 * Every control here corresponds to a real, queryable backend field — see
 * `JobRepository.FeedFilters` and `JobsController#listJobs`. Dimensions
 * evaluated and deliberately NOT implemented:
 *   - Source/ATS type: Robin's candidate feed deliberately never reveals
 *     which site, ATS or aggregator surfaced a posting (Phase 8.2.5 source
 *     hiding, enforced server-side) — a filter here would contradict that.
 *   - A fixed experience-level/employment-type dropdown: no controlled
 *     vocabulary for either exists anywhere in Robin today (not even in the
 *     candidate's own Preferences page) — both are heterogeneous free text
 *     from the underlying sources (see ArbeitnowAdapter's own javadoc), so
 *     these are free-text "contains" filters instead of a guessed dropdown.
 */
type Filters = {
  q: string;
  status: string;
  location: string;
  remoteType: string;
  employmentType: string;
  experienceLevel: string;
  salaryMin: string;
  sponsorship: string;
  postedAfter: string;
  sort: string;
};

const EMPTY_FILTERS: Filters = {
  q: '', status: '', location: '', remoteType: '', employmentType: '', experienceLevel: '',
  salaryMin: '', sponsorship: '', postedAfter: '', sort: '',
};

function filtersFromParams(params: URLSearchParams): Filters {
  return {
    q: params.get('q') || '',
    status: params.get('status') || '',
    location: params.get('location') || '',
    remoteType: params.get('remote_type') || '',
    employmentType: params.get('employment_type') || '',
    experienceLevel: params.get('experience_level') || '',
    salaryMin: params.get('salary_min') || '',
    sponsorship: params.get('sponsorship') || '',
    // The URL/UI carry a bare date (YYYY-MM-DD); the API wants a full instant.
    postedAfter: params.get('posted_after') ? params.get('posted_after')!.slice(0, 10) : '',
    sort: params.get('sort') || '',
  };
}

function paramsFromFilters(filters: Filters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.q.trim()) params.set('q', filters.q.trim());
  if (filters.status) params.set('status', filters.status);
  if (filters.location.trim()) params.set('location', filters.location.trim());
  if (filters.remoteType) params.set('remote_type', filters.remoteType);
  if (filters.employmentType.trim()) params.set('employment_type', filters.employmentType.trim());
  if (filters.experienceLevel.trim()) params.set('experience_level', filters.experienceLevel.trim());
  if (filters.salaryMin.trim()) params.set('salary_min', filters.salaryMin.trim());
  if (filters.sponsorship) params.set('sponsorship', filters.sponsorship);
  if (filters.postedAfter) params.set('posted_after', `${filters.postedAfter}T00:00:00Z`);
  if (filters.sort) params.set('sort', filters.sort);
  return params;
}

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

/** Human labels for the active-filter chips — kept next to the fields they describe. */
function activeFilterChips(filters: Filters): Array<{ key: keyof Filters; label: string }> {
  const chips: Array<{ key: keyof Filters; label: string }> = [];
  if (filters.q.trim()) chips.push({ key: 'q', label: `Search: ${filters.q.trim()}` });
  if (filters.status) chips.push({ key: 'status', label: `Status: ${filters.status}` });
  if (filters.location.trim()) chips.push({ key: 'location', label: `Location: ${filters.location.trim()}` });
  if (filters.remoteType) chips.push({ key: 'remoteType', label: `Workplace: ${filters.remoteType}` });
  if (filters.employmentType.trim()) chips.push({ key: 'employmentType', label: `Employment: ${filters.employmentType.trim()}` });
  if (filters.experienceLevel.trim()) chips.push({ key: 'experienceLevel', label: `Experience: ${filters.experienceLevel.trim()}` });
  if (filters.salaryMin.trim()) chips.push({ key: 'salaryMin', label: `Salary ≥ ${filters.salaryMin.trim()}` });
  if (filters.sponsorship) chips.push({ key: 'sponsorship', label: `Sponsorship: ${filters.sponsorship}` });
  if (filters.postedAfter) chips.push({ key: 'postedAfter', label: `Posted after: ${filters.postedAfter}` });
  return chips;
}

/**
 * FIND workspace — the candidate's job catalogue/search view.
 *
 * Source hiding (Phase 8.2.5): this page never shows which website, ATS
 * platform or aggregator a posting came from. Running discovery, importing a
 * board URL and source health live on the operational Job Sources page.
 *
 * Filters/sort are kept in the URL (via useSearchParams) so a filtered,
 * sorted view is bookmarkable/shareable and survives back/forward
 * navigation — sorting always happens server-side, before pagination, on
 * the persisted data the backend already has; this page never re-sorts or
 * re-scores anything itself.
 */
export const JobsFeedPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [filters, setFilters] = useState<Filters>(() => filtersFromParams(searchParams));
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(false);
  const [jobsLoaded, setJobsLoaded] = useState(false);
  const [jobsError, setJobsError] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string>('');
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  // Phase 9: the preference-driven, multi-source discovery trigger. Robin
  // selects applicable sources internally (see PreferredSourcesDiscoveryService);
  // this page only ever shows an aggregate summary, never a source/platform
  // name, consistent with Job's own "source hiding" contract.
  const [discovering, setDiscovering] = useState(false);
  const [discoveryResult, setDiscoveryResult] = useState<DiscoveryRunSummary | null>(null);
  const [discoveryError, setDiscoveryError] = useState<string | null>(null);

  // Phase 10: fetched once — static product facts, not per-job data — and
  // passed to every JobCard so its apply-capability label is driven by the
  // same backend-verified model the application workflow itself enforces,
  // never a separate frontend guess. A failed fetch leaves this null, which
  // ApplyCapabilityPill already treats as "say nothing" rather than guessing.
  const [atsCapabilities, setAtsCapabilities] = useState<AtsCapabilityRow[] | null>(null);
  useEffect(() => {
    apiFetch<AtsCapabilityRow[]>('/ats-capabilities').then(setAtsCapabilities).catch(() => setAtsCapabilities(null));
  }, []);

  const abortRef = useRef<AbortController | null>(null);
  const requestSeq = useRef(0);

  const buildQuery = useCallback((cursor?: string) => {
    const params = paramsFromFilters(filters);
    params.set('limit', String(PAGE_SIZE));
    if (cursor) params.set('cursor', cursor);
    return '/jobs?' + params.toString();
  }, [filters]);

  const fetchJobs = useCallback(async () => {
    // Cancel whatever request is still in flight so a slow, now-stale
    // response can never land after (and overwrite) a newer one.
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const seq = ++requestSeq.current;

    setLoading(true);
    setJobsError(null);
    setLoadMoreError(null);
    try {
      const response = await apiFetch<{ items: Job[]; next_cursor?: string }>(
        buildQuery(), { signal: controller.signal },
      );
      if (requestSeq.current !== seq) return; // a newer request has already superseded this one
      setJobs(response.items || []);
      setNextCursor(response.next_cursor || '');
    } catch (error: unknown) {
      if (error instanceof DOMException && error.name === 'AbortError') return;
      if (requestSeq.current !== seq) return;
      setJobs([]);
      setNextCursor('');
      setJobsError(error instanceof Error ? error.message : 'Could not load the job catalogue.');
    } finally {
      if (requestSeq.current === seq) {
        setJobsLoaded(true);
        setLoading(false);
      }
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

  const runDiscoveryForMe = async () => {
    setDiscovering(true);
    setDiscoveryError(null);
    setDiscoveryResult(null);
    try {
      const summary = await apiFetch<DiscoveryRunSummary>('/discovery/run-for-me', { method: 'POST' });
      setDiscoveryResult(summary);
      if (!summary.quotaExceeded && summary.sourcesQueried > 0) {
        void fetchJobs();
      }
    } catch (error: unknown) {
      setDiscoveryError(error instanceof Error ? error.message : 'Could not run discovery right now.');
    } finally {
      setDiscovering(false);
    }
  };

  // Debounced: every filter change (typing or a dropdown) lands here, the URL
  // is synced, and the actual fetch fires once input settles — multiple rapid
  // changes collapse into a single request, and the AbortController above
  // still protects against any one of them resolving out of order.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSearchParams(paramsFromFilters(filters), { replace: true });
      void fetchJobs();
    }, DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const setFilter = (key: keyof Filters, value: string) => setFilters((current) => ({ ...current, [key]: value }));
  const clearFilter = (key: keyof Filters) => setFilter(key, '');
  const resetAllFilters = () => setFilters(EMPTY_FILTERS);

  const chips = useMemo(() => activeFilterChips(filters), [filters]);
  const hasAnyFilter = chips.length > 0;

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Stage 1 · FIND"
          title="Find jobs"
          subtitle="Search the indexed job catalogue, filter by what Robin actually knows about each posting, and sort by your own match score."
          actions={
            <div className="flex flex-wrap items-center gap-2">
              <SecondaryButton onClick={() => void runDiscoveryForMe()} disabled={discovering}>
                {discovering ? 'Searching your sources…' : 'Find more jobs for me'}
              </SecondaryButton>
              <SecondaryButton href="/sources">Manage discovery</SecondaryButton>
            </div>
          }
        />

        {discoveryError && <Alert tone="error">{discoveryError}</Alert>}
        {discoveryResult && (
          <Alert tone={discoveryResult.quotaExceeded ? 'info' : discoveryResult.sourcesFailed > 0 ? 'info' : 'success'}>
            {discoveryResult.quotaExceeded ? (
              <>Today&apos;s discovery limit has been reached. Try again tomorrow.</>
            ) : discoveryResult.sourcesQueried === 0 ? (
              <>No discovery sources are configured for your selected countries yet — check your Preferences.</>
            ) : (
              <>
                Checked {discoveryResult.sourcesQueried} source{discoveryResult.sourcesQueried === 1 ? '' : 's'}:{' '}
                {discoveryResult.newJobs} new job{discoveryResult.newJobs === 1 ? '' : 's'} found,{' '}
                {discoveryResult.updatedJobs} updated
                {discoveryResult.sourcesFailed > 0 || discoveryResult.sourcesRequireConfiguration > 0 ? (
                  <>
                    {' '}({discoveryResult.sourcesRequireConfiguration > 0
                      ? `${discoveryResult.sourcesRequireConfiguration} source(s) need configuration`
                      : ''}
                    {discoveryResult.sourcesRequireConfiguration > 0 && discoveryResult.sourcesFailed > 0 ? ', ' : ''}
                    {discoveryResult.sourcesFailed > 0 ? `${discoveryResult.sourcesFailed} source(s) temporarily unreachable` : ''})
                  </>
                ) : null}
                .
              </>
            )}
          </Alert>
        )}

        <div className="space-y-3 rounded-xl border border-line bg-surface p-4 shadow-card">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative flex-1">
              <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-ink-faint">
                <svg viewBox="0 0 20 20" fill="none" className="h-4 w-4" aria-hidden="true">
                  <circle cx="9" cy="9" r="5.5" stroke="currentColor" strokeWidth="1.6" />
                  <path d="m13.5 13.5 3 3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                </svg>
              </span>
              <TextInput
                type="text"
                value={filters.q}
                onChange={(event) => setFilter('q', event.target.value)}
                placeholder="Search title, employer or job text (e.g. Java, Kubernetes, Senior)…"
                className="pl-9"
                aria-label="Search jobs"
              />
            </div>
            <div className="sm:w-48">
              <Select
                value={filters.sort}
                onChange={(event) => setFilter('sort', event.target.value)}
                aria-label="Sort jobs by"
              >
                <option value="">Newest discovered first</option>
                <option value="recent">Newest discovered first</option>
                <option value="match">Best match first</option>
                <option value="posted">Most recently posted first</option>
              </Select>
            </div>
            <SecondaryButton onClick={() => void fetchJobs()} disabled={loading}>
              {loading ? 'Refreshing…' : 'Refresh'}
            </SecondaryButton>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <TextInput
                type="text"
                value={filters.location}
                onChange={(event) => setFilter('location', event.target.value)}
                placeholder="Location (city or country)"
                aria-label="Filter by location"
              />
            </div>
            <div>
              <Select
                value={filters.remoteType}
                onChange={(event) => setFilter('remoteType', event.target.value)}
                aria-label="Filter by workplace type"
              >
                <option value="">Any workplace type</option>
                <option value="REMOTE">Remote</option>
                <option value="HYBRID">Hybrid</option>
                <option value="ONSITE">Onsite</option>
              </Select>
            </div>
            <div>
              <TextInput
                type="text"
                value={filters.employmentType}
                onChange={(event) => setFilter('employmentType', event.target.value)}
                placeholder="Employment type contains…"
                aria-label="Filter by employment type"
              />
            </div>
            <div>
              <TextInput
                type="text"
                value={filters.experienceLevel}
                onChange={(event) => setFilter('experienceLevel', event.target.value)}
                placeholder="Experience level contains…"
                aria-label="Filter by experience level"
              />
            </div>
            <div>
              <TextInput
                type="number"
                min={0}
                value={filters.salaryMin}
                onChange={(event) => setFilter('salaryMin', event.target.value)}
                placeholder="Minimum salary"
                aria-label="Filter by minimum salary"
              />
            </div>
            <div>
              <Select
                value={filters.sponsorship}
                onChange={(event) => setFilter('sponsorship', event.target.value)}
                aria-label="Filter by sponsorship assessment"
              >
                <option value="">Any sponsorship status</option>
                <option value="LIKELY_AVAILABLE">Likely available</option>
                <option value="POSSIBLE">Possible</option>
                <option value="LIKELY_NOT_AVAILABLE">Likely not available</option>
                <option value="NOT_AVAILABLE">Not available</option>
              </Select>
            </div>
            <div>
              <TextInput
                type="date"
                value={filters.postedAfter}
                onChange={(event) => setFilter('postedAfter', event.target.value)}
                aria-label="Filter by earliest posting date"
              />
            </div>
            <div>
              <Select
                value={filters.status}
                onChange={(event) => setFilter('status', event.target.value)}
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
          </div>

          {hasAnyFilter && (
            <div className="flex flex-wrap items-center gap-1.5 border-t border-line pt-3">
              <span className="text-[11px] font-semibold uppercase tracking-wide text-ink-faint">Active filters:</span>
              {chips.map((chip) => (
                <RemovableFilterChip key={chip.key} label={chip.label} onRemove={() => clearFilter(chip.key)} />
              ))}
              <button
                type="button"
                onClick={resetAllFilters}
                className="ml-1 text-[11px] font-semibold text-forest-700 underline-offset-2 hover:underline"
              >
                Reset all
              </button>
            </div>
          )}
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
            body={hasAnyFilter
              ? 'No indexed jobs match this combination of search and filters. Try removing one, or reset all filters.'
              : 'No indexed jobs yet. Run discovery from Job Sources.'}
            actions={hasAnyFilter ? <SecondaryButton onClick={resetAllFilters}>Reset all filters</SecondaryButton> : undefined}
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
                  atsCapabilities={atsCapabilities}
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
