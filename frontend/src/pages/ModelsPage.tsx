import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { LlmModel, RoutingPolicy, BenchmarkRun } from '../types';

export const ModelsPage: React.FC = () => {
  const [models, setModels] = useState<LlmModel[]>([]);
  const [routing, setRouting] = useState<RoutingPolicy[]>([]);
  const [runs, setRuns] = useState<BenchmarkRun[]>([]);
  const [stats, setStats] = useState<Array<{ provider_id: string; call_count: number; avg_latency_ms: number; success_rate: number }>>([]);
  const [loading, setLoading] = useState(true);

  // Ping Diagnostic State
  const [forceFallback, setForceFallback] = useState(false);
  const [pingTrace, setPingTrace] = useState<any>(null);
  const [pinging, setPinging] = useState(false);

  // Benchmark Trigger State
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
    return <div className="p-12 text-center text-slate-400 text-sm">Loading model registry &amp; benchmarks...</div>;
  }

  return (
    <div className="space-y-8 max-w-5xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-white tracking-tight">Models, Routing &amp; Benchmarks</h1>
        <p className="text-sm text-slate-400">Multi-provider LLM registry, empirical router policies, and golden test harness</p>
      </div>

      {/* Interactive Ping Diagnostics */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
              Live Router Diagnostic &amp; Failover Test
            </h2>
            <p className="text-xs text-slate-400">Verifies circuit breaker fallback and usage ledger write</p>
          </div>

          <div className="flex items-center gap-4">
            <label className="inline-flex items-center gap-2 text-xs text-slate-300 cursor-pointer">
              <input
                type="checkbox"
                checked={forceFallback}
                onChange={(e) => setForceFallback(e.target.checked)}
                className="rounded bg-slate-800 border-slate-700 text-indigo-600 focus:ring-indigo-500"
              />
              Force Fallback (Simulate Failure)
            </label>

            <button
              onClick={handlePing}
              disabled={pinging}
              className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold transition-all shadow-md disabled:opacity-50"
            >
              {pinging ? 'Pinging Router...' : '⚡ Ping Router'}
            </button>
          </div>
        </div>

        {pingTrace && (
          <div className="p-4 rounded-lg bg-slate-950 border border-slate-800">
            <div className="text-xs font-mono text-slate-400 mb-1">Execution Trace Output:</div>
            <pre className="text-xs font-mono text-emerald-400 overflow-x-auto">
              {JSON.stringify(pingTrace, null, 2)}
            </pre>
          </div>
        )}
      </div>

      {/* Model Registry Table */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
          Registered LLM Providers &amp; Models
        </h2>

        <div className="divide-y divide-slate-700/40">
          {models.map((model) => (
            <div key={model.id} className="py-3 flex items-center justify-between gap-4">
              <div>
                <div className="flex items-center gap-2">
                  <span className="font-semibold text-sm text-white">{model.display_name || model.model_key}</span>
                  <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-slate-700 text-slate-300">
                    {model.provider_id}
                  </span>
                </div>
                <p className="text-xs text-slate-400 font-mono mt-0.5">{model.model_key}</p>
              </div>

              <button
                onClick={() => handleToggleModel(model.id, model.enabled)}
                className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors ${
                  model.enabled
                    ? 'bg-emerald-500/20 text-emerald-400 hover:bg-emerald-500/30'
                    : 'bg-slate-700 text-slate-400 hover:bg-slate-600'
                }`}
              >
                {model.enabled ? 'Enabled' : 'Disabled'}
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* Active Routing Policies Table */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
          Active Empirical Routing Policies
        </h2>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-slate-400 border-b border-slate-700/60">
              <tr>
                <th className="pb-2">Task Type</th>
                <th className="pb-2">Primary Model</th>
                <th className="pb-2">Basis</th>
                <th className="pb-2">Rationale / Source</th>
                <th className="pb-2">Updated</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-700/40 text-slate-300">
              {routing.length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-4 text-center text-slate-500">
                    No custom policies promoted yet. Operating on default fallback chain.
                  </td>
                </tr>
              ) : (
                routing.map((policy) => (
                  <tr key={policy.task_type}>
                    <td className="py-2.5 font-mono text-indigo-400 font-semibold">{policy.task_type}</td>
                    <td className="py-2.5 font-mono text-white">{policy.primary_model_id || 'Default'}</td>
                    <td className="py-2.5">
                      <span className="px-2 py-0.5 rounded bg-slate-700 text-slate-300 text-[11px] font-semibold">
                        {policy.basis}
                      </span>
                    </td>
                    <td className="py-2.5 text-slate-400 max-w-xs truncate">{policy.rationale || 'N/A'}</td>
                    <td className="py-2.5 text-slate-500">{new Date(policy.updated_at).toLocaleTimeString()}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Benchmark Suite & Promotion Section */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
              Golden Benchmark Harness
            </h2>
            <p className="text-xs text-slate-400">Suite: job_classification@v1 (20 curated cases)</p>
          </div>

          <button
            onClick={handleStartBenchmark}
            disabled={runningBenchmark}
            className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold transition-all shadow-md disabled:opacity-50"
          >
            {runningBenchmark ? 'Executing Runs...' : '🎯 Run Benchmark Suite'}
          </button>
        </div>

        {benchmarkMsg && (
          <div className="p-3 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-300 text-xs">
            {benchmarkMsg}
          </div>
        )}

        <div className="overflow-x-auto pt-2">
          <table className="w-full text-left text-xs">
            <thead className="text-slate-400 border-b border-slate-700/60">
              <tr>
                <th className="pb-2">Run ID</th>
                <th className="pb-2">Suite</th>
                <th className="pb-2">Task</th>
                <th className="pb-2">Status</th>
                <th className="pb-2">Started</th>
                <th className="pb-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-700/40 text-slate-300">
              {runs.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-4 text-center text-slate-500">
                    No benchmark runs recorded. Click &quot;Run Benchmark Suite&quot; above to begin!
                  </td>
                </tr>
              ) : (
                runs.map((run) => (
                  <tr key={run.id}>
                    <td className="py-2.5 font-mono text-slate-400">{run.id.slice(0, 8)}...</td>
                    <td className="py-2.5 font-medium text-white">{run.suite}</td>
                    <td className="py-2.5 font-mono text-indigo-400">{run.task_type}</td>
                    <td className="py-2.5">
                      <span
                        className={`px-2 py-0.5 rounded text-[11px] font-semibold ${
                          run.status === 'COMPLETED'
                            ? 'bg-emerald-500/20 text-emerald-400'
                            : run.status === 'RUNNING'
                            ? 'bg-amber-500/20 text-amber-400'
                            : 'bg-red-500/20 text-red-400'
                        }`}
                      >
                        {run.status}
                      </span>
                    </td>
                    <td className="py-2.5 text-slate-500">{new Date(run.started_at).toLocaleTimeString()}</td>
                    <td className="py-2.5 text-right">
                      {run.status === 'COMPLETED' && (
                        <button
                          onClick={() => handlePromote(run.id)}
                          disabled={promotingId === run.id}
                          className="px-3 py-1 rounded bg-indigo-600/20 hover:bg-indigo-600/30 text-indigo-400 border border-indigo-500/30 font-semibold text-[11px] transition-colors"
                        >
                          {promotingId === run.id ? 'Promoting...' : 'Promote Model'}
                        </button>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* LLM Usage Ledger Stats */}
      {stats.length > 0 && (
        <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
            Production Usage Ledger Summary
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            {stats.map((st, i) => (
              <div key={i} className="p-4 rounded-lg bg-slate-900/60 border border-slate-800">
                <span className="text-xs font-mono text-slate-400 uppercase">{st.provider_id}</span>
                <div className="text-2xl font-bold text-white mt-1">{st.call_count} calls</div>
                <div className="flex justify-between text-xs text-slate-400 mt-2">
                  <span>Avg Latency:</span>
                  <span className="font-mono text-indigo-300">{Math.round(st.avg_latency_ms)}ms</span>
                </div>
                <div className="flex justify-between text-xs text-slate-400 mt-1">
                  <span>Success Rate:</span>
                  <span className="font-mono text-emerald-400">{((st.success_rate || 0) * 100).toFixed(1)}%</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
