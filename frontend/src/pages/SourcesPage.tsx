import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { BoardImportResult, JobSource, PlatformCoverage } from '../types';
import {
  Alert,
  DataTable,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  SectionCard,
  Select,
  StatusPill,
  TextInput,
} from '../components/ui';
import type { PillTone } from '../components/ui';

/**
 * Job Sources — the OPERATIONS view of discovery.
 *
 * This is the only place that names platforms, source boards and their
 * health. Candidate job views (feed, cards, detail, prep, apply, dashboard)
 * never show which site, ATS or aggregator surfaced a posting.
 *
 * Every state shown here is the recorded state from the API: a source is
 * never displayed as healthy unless its last run said so.
 */

interface Message {
  tone: 'success' | 'error' | 'info';
  text: string;
}

interface HealthCheckResponse {
  id: string;
  display_name?: string;
  health?: string | Record<string, unknown> | null;
  run?: { status: string; complete: boolean; counts: Record<string, number | boolean> };
}

export function parseJson(value: unknown): Record<string, unknown> {
  if (value && typeof value === 'object') return value as Record<string, unknown>;
  if (typeof value === 'string') {
    try {
      const parsed: unknown = JSON.parse(value);
      if (parsed && typeof parsed === 'object') return parsed as Record<string, unknown>;
    } catch {
      // Unparseable recorded state is shown as unknown, never as success.
    }
  }
  return {};
}

/** Recorded health → display. "Not checked yet" is not "healthy". */
export function healthDisplay(source: JobSource): { tone: PillTone; label: string } {
  if (!source.enabled || source.policy === 'DISABLED') return { tone: 'slate', label: 'Disabled' };
  const health = parseJson(source.health);
  const status = typeof health.status === 'string' ? health.status : '';
  if (status === 'ok') return { tone: 'emerald', label: 'Last run OK' };
  if (status === 'degraded') return { tone: 'amber', label: 'Last run partial' };
  if (status === 'failed') {
    return { tone: 'red', label: `Failing (${source.failure_streak || 1})` };
  }
  return { tone: 'slate', label: 'Not checked yet' };
}

const STATUS_TONES: Record<PlatformCoverage['status'], PillTone> = {
  SUPPORTED: 'emerald',
  NEEDS_BOARD_CONFIGURATION: 'sky',
  TEMPORARILY_UNAVAILABLE: 'amber',
  BLOCKED: 'red',
  UNVERIFIED: 'slate',
};

export const SourcesPage: React.FC = () => {
  const [sources, setSources] = useState<JobSource[] | null>(null);
  const [sourcesError, setSourcesError] = useState<string | null>(null);
  const [coverage, setCoverage] = useState<PlatformCoverage[] | null>(null);
  const [coverageError, setCoverageError] = useState<string | null>(null);
  const [checkingId, setCheckingId] = useState<string | null>(null);
  const [runMessage, setRunMessage] = useState<Message | null>(null);

  const [importUrl, setImportUrl] = useState('');
  const [importPlatform, setImportPlatform] = useState('');
  const [importBoard, setImportBoard] = useState('');
  const [importResult, setImportResult] = useState<BoardImportResult | null>(null);
  const [importError, setImportError] = useState<string | null>(null);
  const [importBusy, setImportBusy] = useState(false);

  const fetchSources = useCallback(async () => {
    setSourcesError(null);
    try {
      setSources((await apiFetch<JobSource[]>('/sources')) || []);
    } catch (error: unknown) {
      setSources(null);
      setSourcesError(error instanceof Error ? error.message : 'Could not load discovery sources.');
    }
  }, []);

  const fetchCoverage = useCallback(async () => {
    setCoverageError(null);
    try {
      setCoverage((await apiFetch<PlatformCoverage[]>('/sources/coverage')) || []);
    } catch (error: unknown) {
      setCoverage(null);
      setCoverageError(error instanceof Error ? error.message : 'Could not load platform coverage.');
    }
  }, []);

  useEffect(() => {
    void fetchSources();
    void fetchCoverage();
  }, [fetchSources, fetchCoverage]);

  const runnableKinds = new Set((coverage || []).filter((row) => row.adapter).map((row) => row.kind));

  const runDiscovery = async (source: JobSource) => {
    if (!source.enabled || !runnableKinds.has(source.kind)) return;
    setCheckingId(source.id);
    setRunMessage(null);
    try {
      const result = await apiFetch<HealthCheckResponse>(
        '/sources/' + encodeURIComponent(source.id) + '/health-check',
        { method: 'POST' },
      );
      const health = parseJson(result.health);
      const status = typeof health.status === 'string' ? health.status : '';
      const detail = typeof health.detail === 'string' ? health.detail : '';
      const failure = typeof health.error === 'string' ? health.error : '';
      if (status === 'ok') {
        setRunMessage({ tone: 'success', text: `${source.display_name}: discovery completed. ${detail}`.trim() });
      } else if (status === 'failed' || status === 'degraded') {
        setRunMessage({
          tone: 'error',
          text: `${source.display_name}: discovery reported a failure. ${failure || 'Check the recorded health for details.'}`,
        });
      } else {
        setRunMessage({
          tone: 'info',
          text: `${source.display_name}: the request returned without a confirmed outcome. The recorded state is shown below.`,
        });
      }
    } catch (error: unknown) {
      setRunMessage({ tone: 'error', text: `${source.display_name}: ${error instanceof Error ? error.message : 'Discovery failed.'}` });
    } finally {
      setCheckingId(null);
      await fetchSources();
      await fetchCoverage();
    }
  };

  const submitImport = async (mode: 'check' | 'confirm') => {
    if (!importUrl.trim()) return;
    setImportBusy(true);
    setImportError(null);
    if (mode === 'check') setImportResult(null);
    try {
      const body: Record<string, unknown> = { url: importUrl.trim(), preview: mode === 'check' };
      if (importPlatform) body.platform = importPlatform;
      if (importBoard.trim()) body.board = importBoard.trim();
      if (mode === 'confirm') body.confirm = true;
      const result = await apiFetch<BoardImportResult>('/jobs/import-url', { method: 'POST', body: JSON.stringify(body) });
      setImportResult(result);
      if (mode === 'confirm') {
        await fetchSources();
        await fetchCoverage();
      }
    } catch (error: unknown) {
      setImportError(error instanceof Error ? error.message : 'The URL could not be checked.');
    } finally {
      setImportBusy(false);
    }
  };

  const supportedPlatforms = (coverage || []).filter((row) => row.adapter);

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Discovery · operations"
          title="Job Sources"
          subtitle="The public job boards Robin follows, their recorded health, and the platforms it can read."
        />

        <div className="rounded-xl border border-forest-200 bg-forest-50 px-4 py-3 text-xs leading-relaxed text-forest-900">
          <span className="mb-0.5 block font-bold">How discovery is allowed to work</span>
          Discovery reads public job-board endpoints and public listing pages only — no logins, no CAPTCHAs or
          bot challenges, no private dashboards. Every request is SSRF-checked, time-limited, size-limited and
          rate-limited. A board that can only be reached by working around an access control is reported as
          blocked, not scraped. Discovery never submits applications.
        </div>

        {/* ---------------------------------------------------------------- import */}
        <SectionCard title="Add a board from a URL" hint="careers page, board or job URL">
          <form
            className="space-y-3"
            onSubmit={(event) => {
              event.preventDefault();
              void submitImport('check');
            }}
          >
            <div>
              <label htmlFor="import-url" className="mb-1 block text-xs font-semibold text-ink-soft">
                Public careers or job URL
              </label>
              <TextInput
                id="import-url"
                type="url"
                value={importUrl}
                onChange={(event) => setImportUrl(event.target.value)}
                placeholder="https://…"
              />
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label htmlFor="import-platform" className="mb-1 block text-xs font-semibold text-ink-soft">
                  Platform (only if not detected)
                </label>
                <Select id="import-platform" value={importPlatform} onChange={(event) => setImportPlatform(event.target.value)}>
                  <option value="">Detect automatically</option>
                  {supportedPlatforms.map((row) => (
                    <option key={row.kind} value={row.kind}>{row.name}</option>
                  ))}
                </Select>
              </div>
              <div>
                <label htmlFor="import-board" className="mb-1 block text-xs font-semibold text-ink-soft">
                  Board identifier (only if asked)
                </label>
                <TextInput id="import-board" value={importBoard} onChange={(event) => setImportBoard(event.target.value)} />
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              <SecondaryButton type="submit" disabled={importBusy || !importUrl.trim()}>
                {importBusy ? 'Checking…' : 'Check URL'}
              </SecondaryButton>
              {importResult?.status === 'RESOLVED' && (
                <PrimaryButton type="button" disabled={importBusy} onClick={() => void submitImport('confirm')}>
                  Add board and run discovery
                </PrimaryButton>
              )}
            </div>
          </form>
          {importError && <div className="mt-3"><Alert tone="error">{importError}</Alert></div>}
          {importResult && <div className="mt-3"><ImportOutcome result={importResult} /></div>}
        </SectionCard>

        {/* ---------------------------------------------------------------- sources */}
        <SectionCard
          title="Configured sources"
          hint={sources ? `${sources.length} configured` : undefined}
          bodyClassName="pt-2"
        >
          {runMessage && <div className="mb-3"><Alert tone={runMessage.tone}>{runMessage.text}</Alert></div>}
          {sourcesError && <Alert tone="error">Discovery sources could not be loaded: {sourcesError}</Alert>}
          {sources === null && !sourcesError ? (
            <Loading>Loading job sources…</Loading>
          ) : sources ? (
            <DataTable
              columns={['Source', 'Platform', 'Board', 'Policy', 'Health', 'Last run', 'Actions']}
              emptyMessage="No sources configured yet. Add one from a URL above."
              rows={sources.map((src) => {
                const health = healthDisplay(src);
                const lastRun = parseJson(src.last_run);
                const runnable = src.enabled && src.policy !== 'DISABLED' && runnableKinds.has(src.kind);
                const error = parseJson(src.health).error;
                return [
                  <span className="font-semibold text-ink">{src.display_name}</span>,
                  <span className="font-mono text-xs text-forest-700">{src.kind}</span>,
                  <span className="font-mono text-xs text-ink-muted">{src.org_identifier}</span>,
                  <StatusPill tone="forest">{src.policy}</StatusPill>,
                  <span className="flex flex-col gap-1">
                    <StatusPill tone={health.tone}>{health.label}</StatusPill>
                    {typeof error === 'string' && error && <span className="text-[11px] text-red-700">{error}</span>}
                  </span>,
                  <span className="text-[11px] text-ink-muted">
                    {typeof lastRun.listed === 'number'
                      ? `${lastRun.listed} listed · ${lastRun.created ?? 0} new · ${lastRun.updated ?? 0} updated` +
                        (lastRun.complete === false ? ' · partial listing' : '')
                      : 'No run recorded'}
                  </span>,
                  <PrimaryButton
                    onClick={() => void runDiscovery(src)}
                    disabled={!runnable || checkingId !== null}
                    className="whitespace-nowrap px-3 py-1.5 text-xs"
                    aria-label={`Run discovery for ${src.display_name}`}
                  >
                    {checkingId === src.id ? 'Running…' : 'Run discovery'}
                  </PrimaryButton>,
                ];
              })}
            />
          ) : null}
        </SectionCard>

        {/* ---------------------------------------------------------------- coverage */}
        <SectionCard title="Platform coverage" hint="30 ATS platforms + 1 aggregator" bodyClassName="pt-2">
          {coverageError && <Alert tone="error">Platform coverage could not be loaded: {coverageError}</Alert>}
          {coverage === null && !coverageError ? (
            <Loading>Loading platform coverage…</Loading>
          ) : coverage ? (
            <DataTable
              columns={['Platform', 'Status', 'Board format', 'Sources']}
              rows={coverage.map((row) => [
                <span className="font-semibold text-ink">{row.name}</span>,
                <StatusPill tone={STATUS_TONES[row.status]}>{row.status.replace(/_/g, ' ')}</StatusPill>,
                <span className="text-xs text-ink-muted">{row.configuration}</span>,
                <span className="whitespace-nowrap text-xs text-ink-muted">
                  {row.enabled_sources} enabled{row.failing_sources > 0 ? ` · ${row.failing_sources} failing` : ''}
                </span>,
              ])}
            />
          ) : null}
        </SectionCard>
      </div>
    </PageShell>
  );
};

const ImportOutcome: React.FC<{ result: BoardImportResult }> = ({ result }) => {
  if (result.status === 'CONFIGURED') {
    const counts = result.run?.counts || {};
    return (
      <Alert tone={result.run?.failure ? 'error' : 'success'}>
        Board added. First run: {String(result.run?.status ?? 'unknown')}
        {typeof counts.listed === 'number' ? ` — ${counts.listed} listed, ${counts.created ?? 0} new` : ''}
        {result.run?.failure ? ` (${result.run.failure})` : ''}.
      </Alert>
    );
  }
  if (result.status === 'SOURCE_DISABLED') return <Alert tone="info">{result.message}</Alert>;
  if (result.status === 'UNSUPPORTED') return <Alert tone="error">{result.message}</Alert>;
  if (result.status === 'UNRECOGNIZED' || result.status === 'NEEDS_CONFIGURATION') {
    return <Alert tone="info">{result.message}</Alert>;
  }
  if (result.status === 'RESOLVED') {
    return (
      <Alert tone="info">
        <span className="block">
          {result.platform?.name} board <span className="font-mono">{result.board}</span>
          {result.existing_source ? ' is already configured.' : ' was found.'}
        </span>
        {result.preview && (
          <span className="mt-1 block text-xs">
            First page: {result.preview.listed_on_first_page} posting(s)
            {result.preview.more_pages_possible ? ' (more pages may exist)' : ''}
            {result.preview.problem ? ` — problem: ${result.preview.problem}` : ''}
            {result.preview.sample_titles.length > 0 ? ` — e.g. ${result.preview.sample_titles.join('; ')}` : ''}
          </span>
        )}
      </Alert>
    );
  }
  return <Alert tone="info">The API returned status {result.status}.</Alert>;
};
