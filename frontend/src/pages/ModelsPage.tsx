import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { LlmModel, RoutingPolicy, BenchmarkRun } from '../types';
import {
  DataTable,
  JobStatusPill,
  Loading,
  MetricCard,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  SectionCard,
  StatusPill,
  TraceBlock,
} from '../components/ui';

/**
 * Models, Routing & Benchmarks.
 *
 * Presentation-only redesign: same four GETs, same toggle/ping/benchmark/
 * promote handlers. Tables render through the shared DataTable primitive.
 */
export const ModelsPage: React.FC = () => {
  const [models, setModels] = useState<LlmModel[]>([]);
  const [routing, setRouting] = useState<RoutingPolicy[]>([]);
  const [runs, setRuns] = useState<BenchmarkRun[]>([]);
  const [stats, setStats] = useState<Array<{ provider_id: string; call_count: number; avg_latency_ms: number; success_rate: number }>>([]);
  const [loading, setLoading] = useState(true);

  // Ping diagnostic state
  const [forceFallback, setForceFallback] = useState(false);
  const [pingTrace, setPingTrace] = useState<any>(null);
  const [pinging, setPinging] = useState(false);

  // Benchmark trigger state
  const [runningBenchmark, setRunningBenchmark] = useState(false);
  const [benchmarkMsg, setBenchmarkMsg] = useState<string | null>(null);
  const [promotingId, setPromotingId] = useState<string | null>(null);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [m, r, bRuns, st] = await Promise.all([
        apiFetch<LlmModel[]>('/models'),
        apiFetch<RoutingPolicy[]>('/routing'),
        apiFetch<BenchmarkRun[]>('/benchmarks/runs').catch(() => []),
        apiFetch<Array<{ provider_id: string; call_count: number; avg_latency_ms: number; success_rate: number }>>('/llm-calls/stats').catch(() => []),
      ]);
      setModels(m || []);
      setRouting(r || []);
      setRuns(bRuns || []);
      setStats(st || []);
    } catch {
      // Ignored
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const handleToggleModel = async (id: string, currentEnabled: boolean) => {
    try {
      await apiFetch(`/models/${id}`, {
        method: 'PUT',
        body: JSON.stringify({ enabled: !currentEnabled }),
      });
      fetchData();
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : 'Toggle failed');
    }
  };

  const handlePing = async () => {
    setPinging(true);
    setPingTrace(null);
    try {
      const res = await apiFetch(`/system/llm/ping?forceFallback=${forceFallback}`);
      setPingTrace(res);
      fetchData();
    } catch (err: unknown) {
      setPingTrace({ error: err instanceof Error ? err.message : 'Ping failed' });
    } finally {
      setPinging(false);
    }
  };

  const handleStartBenchmark = async () => {
    setRunningBenchmark(true);
    setBenchmarkMsg(null);
    try {
      const modelIds = models.filter((m) => m.enabled).map((m) => m.id);
      const res = await apiFetch<{ runId: string; status: string }>('/benchmarks/runs', {
        method: 'POST',
        body: JSON.stringify({
          suite: 'job_classification@v1',
          modelIds,
        }),
      });
      setBenchmarkMsg(`Benchmark run initiated (ID: ${res.runId}). Cases are running through the router seam.`);
      fetchData();
    } catch (err: unknown) {
      setBenchmarkMsg(err instanceof Error ? err.message : 'Run start failed');
    } finally {
      setRunningBenchmark(false);
    }
  };

  const handlePromote = async (runId: string) => {
    setPromotingId(runId);
    try {
      await apiFetch(`/benchmarks/runs/${runId}/promote`, { method: 'POST' });
      alert('Model successfully promoted to Routing Policy based on benchmark performance!');
      fetchData();
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : 'Promotion failed');
    } finally {
      setPromotingId(null);
    }
  };

  if (loading) {
    return <PageShell><Loading>Loading model registry & benchmarks…</Loading></PageShell>;
  }

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Agent operations"
          title="Models, Routing & Benchmarks"
          subtitle="Multi-provider LLM registry, empirical router policies, and the golden test harness that promotes winners."
        />

        {/* Router diagnostic */}
        <SectionCard
          title="Live router diagnostic & failover test"
          hint="Verifies circuit-breaker fallback and usage-ledger write"
          actions={
            <div className="flex flex-wrap items-center gap-3">
              <label className="inline-flex cursor-pointer items-center gap-2 text-xs font-medium text-ink-soft">
                <input
                  type="checkbox"
                  checked={forceFallback}
                  onChange={(e) => setForceFallback(e.target.checked)}
                  className="h-4 w-4 rounded border-line accent-forest-700"
                />
                Force fallback (simulate failure)
              </label>
              <PrimaryButton onClick={handlePing} disabled={pinging} className="px-4 py-2 text-xs">
                {pinging ? 'Pinging router…' : 'Ping router'}
              </PrimaryButton>
            </div>
          }
          bodyClassName="space-y-3"
        >
          {pingTrace ? (
            <>
              <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">
                Execution trace output
              </p>
              <TraceBlock>{JSON.stringify(pingTrace, null, 2)}</TraceBlock>
            </>
          ) : (
            <p className="text-xs text-ink-muted">
              Run a ping to exercise the primary model and, if it fails or fallback is forced, the fallback chain — results land in the usage ledger.
            </p>
          )}
        </SectionCard>

        {/* Model registry */}
        <SectionCard title="Registered LLM providers & models" bodyClassName="pt-2">
          <DataTable
            columns={['Model', 'Provider', 'Context window', 'State', '']}
            emptyMessage="No models registered."
            rows={models.map((model) => [
              <div>
                <p className="font-semibold text-ink">{model.display_name || model.model_key}</p>
                <p className="font-mono text-[11px] text-ink-muted">{model.model_key}</p>
              </div>,
              <span className="font-mono text-xs">{model.provider_id}</span>,
              <span className="font-mono text-xs">{model.context_window ? model.context_window.toLocaleString() : '—'}</span>,
              <StatusPill tone={model.enabled ? 'emerald' : 'slate'}>{model.enabled ? 'Enabled' : 'Disabled'}</StatusPill>,
              <SecondaryButton
                onClick={() => handleToggleModel(model.id, model.enabled)}
                className="px-3 py-1.5 text-xs"
              >
                {model.enabled ? 'Disable' : 'Enable'}
              </SecondaryButton>,
            ])}
          />
        </SectionCard>

        {/* Routing policies */}
        <SectionCard title="Active empirical routing policies" bodyClassName="pt-2">
          <DataTable
            columns={['Task type', 'Primary model', 'Basis', 'Rationale / source', 'Updated']}
            emptyMessage="No custom policies promoted yet. Operating on the default fallback chain."
            rows={routing.map((policy) => [
              <span className="font-mono text-xs font-semibold text-forest-700">{policy.task_type}</span>,
              <span className="font-mono text-xs text-ink">{policy.primary_model_id || 'Default'}</span>,
              <StatusPill tone={policy.basis === 'BENCHMARK' ? 'emerald' : policy.basis === 'MANUAL' ? 'sky' : 'slate'}>{policy.basis}</StatusPill>,
              <span className="block max-w-xs truncate text-xs" title={policy.rationale || ''}>{policy.rationale || 'N/A'}</span>,
              <span className="whitespace-nowrap text-xs text-ink-muted">{new Date(policy.updated_at).toLocaleString()}</span>,
            ])}
          />
        </SectionCard>

        {/* Benchmark harness */}
        <SectionCard
          title="Golden benchmark harness"
          hint="Suite: job_classification@v1 (20 curated cases)"
          actions={
            <PrimaryButton onClick={handleStartBenchmark} disabled={runningBenchmark} className="px-4 py-2 text-xs">
              {runningBenchmark ? 'Executing runs…' : 'Run benchmark suite'}
            </PrimaryButton>
          }
          bodyClassName="space-y-3 pt-2"
        >
          {benchmarkMsg && (
            <p className="rounded-lg border border-forest-200 bg-forest-50 px-3.5 py-2.5 text-xs text-forest-900">
              {benchmarkMsg}
            </p>
          )}
          <DataTable
            columns={['Run ID', 'Suite', 'Task', 'Status', 'Started', 'Actions']}
            emptyMessage='No benchmark runs recorded. Click "Run benchmark suite" to begin.'
            rows={runs.map((run) => [
              <span className="font-mono text-xs text-ink-muted">{run.id.slice(0, 8)}…</span>,
              <span className="font-medium text-ink">{run.suite}</span>,
              <span className="font-mono text-xs text-forest-700">{run.task_type}</span>,
              <JobStatusPill status={run.status} />,
              <span className="whitespace-nowrap text-xs text-ink-muted">{new Date(run.started_at).toLocaleString()}</span>,
              run.status === 'COMPLETED' ? (
                <SecondaryButton
                  onClick={() => handlePromote(run.id)}
                  disabled={promotingId === run.id}
                  className="px-3 py-1.5 text-xs"
                >
                  {promotingId === run.id ? 'Promoting…' : 'Promote model'}
                </SecondaryButton>
              ) : (
                <span className="text-xs text-ink-faint">—</span>
              ),
            ])}
          />
        </SectionCard>

        {/* Usage ledger */}
        {stats.length > 0 && (
          <div>
            <p className="mb-3 text-sm font-semibold text-ink">Production usage ledger summary</p>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              {stats.map((st, i) => (
                <MetricCard
                  key={i}
                  label={st.provider_id}
                  value={`${st.call_count} calls`}
                  hint={
                    <>
                      <span className="font-mono">{Math.round(st.avg_latency_ms)}ms avg</span>
                      {' · '}
                      <span className="font-mono text-forest-700">{((st.success_rate || 0) * 100).toFixed(1)}% success</span>
                    </>
                  }
                />
              ))}
            </div>
          </div>
        )}
      </div>
    </PageShell>
  );
};
