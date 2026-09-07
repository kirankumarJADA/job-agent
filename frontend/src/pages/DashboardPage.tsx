import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { Job } from '../types';

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

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Mission Control</h1>
          <p className="text-sm text-slate-400">Personal AI Job Agent — Phase 1 Command Center</p>
        </div>
        <div className="flex items-center gap-2">
          <span
            className={`inline-flex items-center px-3 py-1 rounded-full text-xs font-semibold ${
              health?.status === 'UP'
                ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                : 'bg-amber-500/10 text-amber-400 border border-amber-500/20'
            }`}
          >
            <span className="w-2 h-2 rounded-full bg-current mr-2 animate-pulse" />
            Backend: {health?.status || 'CHECKING...'}
          </span>
        </div>
      </div>

      {/* Metric Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="p-5 rounded-xl bg-slate-800/60 border border-slate-700/60">
          <div className="text-xs font-medium uppercase tracking-wider text-slate-400">Total Jobs Ingested</div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-3xl font-bold text-white">{jobs.length > 0 ? `${jobs.length}+` : '2'}</span>
            <span className="text-xs text-indigo-400">FTS Indexed</span>
          </div>
        </div>

        <div className="p-5 rounded-xl bg-slate-800/60 border border-slate-700/60">
          <div className="text-xs font-medium uppercase tracking-wider text-slate-400">Application Mode</div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-2xl font-bold text-emerald-400">ASSISTED</span>
            <span className="text-xs text-slate-500">HITL Gate Active</span>
          </div>
        </div>

        <div className="p-5 rounded-xl bg-slate-800/60 border border-slate-700/60">
          <div className="text-xs font-medium uppercase tracking-wider text-slate-400">Target Market</div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-2xl font-bold text-white">United Kingdom</span>
            <span className="text-xs text-slate-400">GBP (£)</span>
          </div>
        </div>

        <div className="p-5 rounded-xl bg-slate-800/60 border border-slate-700/60">
          <div className="text-xs font-medium uppercase tracking-wider text-slate-400">Model Router</div>
          <div className="mt-2 flex items-baseline gap-2">
            <span className="text-xl font-bold text-indigo-400">Multi-Provider</span>
            <span className="text-xs text-emerald-400">Ledger Active</span>
          </div>
        </div>
      </div>

      {/* Quick Action Bar */}
      <div className="p-5 rounded-xl bg-slate-800/40 border border-slate-700/50">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 mb-3">
          Phase 1 Quick Actions
        </h2>
        <div className="flex flex-wrap gap-3">
          <button
            onClick={handleSeedJobs}
            disabled={seeding}
            className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium transition-all shadow-md shadow-indigo-600/20 disabled:opacity-50"
          >
            {seeding ? 'Seeding UK Jobs...' : '🌱 Seed 25 UK Tech Jobs'}
          </button>

          <button
            onClick={handlePingFailover}
            disabled={pinging}
            className="px-4 py-2 rounded-lg bg-slate-700 hover:bg-slate-600 text-slate-200 text-sm font-medium transition-all disabled:opacity-50"
          >
            {pinging ? 'Testing Failover...' : '⚡ Test LLM Failover Ping'}
          </button>

          <Link
            to="/models"
            className="px-4 py-2 rounded-lg bg-slate-700 hover:bg-slate-600 text-slate-200 text-sm font-medium transition-all inline-flex items-center"
          >
            🎯 Run Benchmark Suite
          </Link>
        </div>

        {seedMessage && (
          <div className="mt-3 p-3 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-300 text-xs">
            {seedMessage}
          </div>
        )}

        {pingResult && (
          <div className="mt-4 p-4 rounded-lg bg-slate-950 border border-slate-800">
            <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2">
              Live Router Trace Output:
            </div>
            <pre className="text-xs text-emerald-400 font-mono overflow-x-auto">
              {JSON.stringify(pingResult, null, 2)}
            </pre>
          </div>
        )}
      </div>

      {/* Recent Discovered Jobs */}
      <div className="rounded-xl bg-slate-800/60 border border-slate-700/60 overflow-hidden">
        <div className="p-4 border-b border-slate-700/60 flex items-center justify-between">
          <h2 className="font-semibold text-white text-sm">Recently Ingested Postings</h2>
          <Link to="/jobs" className="text-xs text-indigo-400 hover:text-indigo-300 font-medium">
            View All Jobs →
          </Link>
        </div>

        <div className="divide-y divide-slate-700/40">
          {jobs.length === 0 ? (
            <div className="p-8 text-center text-slate-400 text-sm">
              No jobs discovered yet. Click &quot;Seed 25 UK Tech Jobs&quot; above to populate the feed!
            </div>
          ) : (
            jobs.map((job) => (
              <div key={job.id} className="p-4 hover:bg-slate-800/40 transition-colors flex items-center justify-between gap-4">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <Link to={`/jobs/${job.id}`} className="font-medium text-white hover:text-indigo-400 truncate">
                      {job.title}
                    </Link>
                    {job.remote_type && (
                      <span className="px-2 py-0.5 rounded text-[11px] font-semibold bg-slate-700 text-slate-300">
                        {job.remote_type}
                      </span>
                    )}
                  </div>
                  <div className="text-xs text-slate-400 mt-1 flex items-center gap-3">
                    <span className="font-medium text-slate-300">{job.company_name_raw || 'Unknown Co'}</span>
                    <span>•</span>
                    <span>{job.location_raw || 'UK'}</span>
                    {job.salary_min && (
                      <>
                        <span>•</span>
                        <span className="text-emerald-400 font-semibold">
                          £{job.salary_min.toLocaleString()} - £{job.salary_max?.toLocaleString()}
                        </span>
                      </>
                    )}
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <span className="px-2.5 py-1 rounded-full text-xs font-medium bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
                    {job.status}
                  </span>
                  <Link
                    to={`/jobs/${job.id}`}
                    className="px-3 py-1.5 rounded-lg text-xs font-medium bg-slate-700 hover:bg-slate-600 text-slate-200 transition-colors"
                  >
                    View
                  </Link>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};
