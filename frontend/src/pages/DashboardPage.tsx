import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { Job } from '../types';
import {
  Alert,
  EmptyState,
  JobStatusPill,
  MetricCard,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  TraceBlock,
  WorkplacePill,
} from '../components/ui';

/**
 * Dashboard — Robin's landing page.
 *
 * Presentation-only redesign of the old dark "Mission Control": the same real
 * API data (system health, the five most recent jobs) and the same handlers
 * (seed UK jobs, LLM failover ping, benchmark link), under the light design
 * system with metric cards, a proper actions section and polished empty
 * states.
 */
export const DashboardPage: React.FC = () => {
  const [health, setHealth] = useState<{ status: string; components?: Record<string, string> } | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [seeding, setSeeding] = useState(false);
  const [seedMessage, setSeedMessage] = useState<string | null>(null);
  const [pingResult, setPingResult] = useState<any>(null);
  const [pinging, setPinging] = useState(false);

  useEffect(() => {
    apiFetch<{ status: string; components?: Record<string, string> }>('/system/health')
      .then(setHealth)
      .catch(() => setHealth({ status: 'DOWN' }));

    apiFetch<{ items: Job[] }>('/jobs?limit=5')
      .then((res) => setJobs(res.items || []))
      .catch(() => setJobs([]));
  }, []);

  const handleSeedJobs = async () => {
    setSeeding(true);
    setSeedMessage(null);
    try {
      const res = await apiFetch<{ status: string; count?: number }>('/jobs/seed-uk', { method: 'POST' });
      setSeedMessage(`Successfully seeded ${res.count || 25} real UK tech jobs!`);
      const refreshed = await apiFetch<{ items: Job[] }>('/jobs?limit=5');
      setJobs(refreshed.items || []);
    } catch (err: unknown) {
      setSeedMessage(err instanceof Error ? err.message : 'Seeding failed');
    } finally {
      setSeeding(false);
    }
  };

  const handlePingFailover = async () => {
    setPinging(true);
    setPingResult(null);
    try {
      const res = await apiFetch('/system/llm/ping?forceFallback=true');
      setPingResult(res);
    } catch (err: unknown) {
      setPingResult({ error: err instanceof Error ? err.message : 'Ping failed' });
    } finally {
      setPinging(false);
    }
  };

  const backendUp = health?.status === 'UP';

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Overview"
          title="Dashboard"
          subtitle="Your AI job agent at a glance: system health, the latest discovered postings, and the levers that drive discovery."
          actions={
            <span
              className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-semibold ${
                backendUp
                  ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
                  : 'border-amber-300 bg-amber-50 text-amber-800'
              }`}
            >
              <span
                aria-hidden="true"
                className={`h-2 w-2 rounded-full ${backendUp ? 'animate-pulse bg-emerald-500' : 'bg-amber-500'}`}
              />
              Backend {health?.status || 'checking…'}
            </span>
          }
        />

        {/* Metric cards — same real values as before */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label="Jobs in pipeline"
            value={jobs.length > 0 ? `${jobs.length}+` : '0'}
            hint="Most recently ingested (top 5)"
          />
          <MetricCard
            label="Application mode"
            value="Assisted"
            hint="Human-in-the-loop gate active"
          />
          <MetricCard label="Target market" value="United Kingdom" hint="GBP (£) salary data" />
          <MetricCard
            label="Model router"
            value="Multi-provider"
            hint="Empirical failover + usage ledger"
          />
        </div>

        {/* Quick actions — same endpoints and handlers as before */}
        <section className="rounded-xl border border-line bg-surface shadow-card">
          <div className="border-b border-line px-5 py-3.5">
            <h2 className="text-sm font-semibold text-ink">Quick actions</h2>
            <p className="mt-0.5 text-xs text-ink-muted">
              Populate the feed and verify the LLM routing stack without leaving the dashboard.
            </p>
          </div>
          <div className="space-y-4 px-5 py-4">
            <div className="flex flex-wrap gap-3">
              <PrimaryButton onClick={handleSeedJobs} disabled={seeding}>
                {seeding ? 'Seeding UK jobs…' : 'Seed 25 UK tech jobs'}
              </PrimaryButton>
              <SecondaryButton onClick={handlePingFailover} disabled={pinging}>
                {pinging ? 'Testing failover…' : 'Test LLM failover ping'}
              </SecondaryButton>
              <SecondaryButton href="/models">Run benchmark suite</SecondaryButton>
            </div>

            {seedMessage && <Alert tone="info">{seedMessage}</Alert>}

            {pingResult && (
              <div>
                <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-[0.12em] text-ink-faint">
                  Live router trace output
                </p>
                <TraceBlock>{JSON.stringify(pingResult, null, 2)}</TraceBlock>
              </div>
            )}
          </div>
        </section>

        {/* Recent postings — same /jobs?limit=5 data */}
        <section className="rounded-xl border border-line bg-surface shadow-card">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-5 py-3.5">
            <h2 className="text-sm font-semibold text-ink">Recently ingested postings</h2>
            <Link
              to="/jobs"
              className="text-xs font-semibold text-forest-700 hover:text-forest-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
            >
              View all jobs →
            </Link>
          </div>
          <div className="divide-y divide-line">
            {jobs.length === 0 ? (
              <div className="p-5">
                <EmptyState
                  title="No jobs discovered yet"
                  body="Seed the feed with 25 real UK tech jobs, or add a posting directly from the Jobs Feed page."
                  actions={<PrimaryButton onClick={handleSeedJobs} disabled={seeding}>Seed UK tech jobs</PrimaryButton>}
                />
              </div>
            ) : (
              jobs.map((job) => (
                <div
                  key={job.id}
                  className="flex flex-col gap-3 px-5 py-4 transition-colors hover:bg-cream-50/70 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <Link
                        to={`/jobs/${job.id}`}
                        className="truncate text-sm font-semibold text-ink hover:text-forest-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
                      >
                        {job.title}
                      </Link>
                      <WorkplacePill type={job.remote_type} />
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-ink-muted">
                      <span className="font-medium text-ink-soft">
                        {job.company_name_raw || 'Unknown company'}
                      </span>
                      <span aria-hidden="true">·</span>
                      <span>{job.location_raw || 'UK'}</span>
                      {job.salary_min && (
                        <>
                          <span aria-hidden="true">·</span>
                          <span className="font-semibold text-forest-700">
                            £{job.salary_min.toLocaleString()} – £{job.salary_max?.toLocaleString()}
                          </span>
                        </>
                      )}
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    <JobStatusPill status={job.status} />
                    <Link
                      to={`/jobs/${job.id}`}
                      className="rounded-lg border border-line bg-surface px-3 py-1.5 text-xs font-semibold text-ink-soft transition-colors hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
                    >
                      View
                    </Link>
                  </div>
                </div>
              ))
            )}
          </div>
        </section>
      </div>
    </PageShell>
  );
};
