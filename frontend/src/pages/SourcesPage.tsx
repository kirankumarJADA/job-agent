import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { JobSource } from '../types';

export const SourcesPage: React.FC = () => {
  const [sources, setSources] = useState<JobSource[]>([]);
  const [loading, setLoading] = useState(true);
  const [checkingId, setCheckingId] = useState<string | null>(null);

  const fetchSources = async () => {
    setLoading(true);
    try {
      const s = await apiFetch<JobSource[]>('/sources');
      setSources(s || []);
    } catch {
      setSources([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSources();
  }, []);

  const handleHealthCheck = async (id: string) => {
    setCheckingId(id);
    try {
      await apiFetch(`/sources/${id}/health-check`, { method: 'POST' });
      fetchSources();
    } catch (err: unknown) {
      alert(err instanceof Error ? err.message : 'Health check failed');
    } finally {
      setCheckingId(null);
    }
  };

  if (loading) {
    return <div className="p-12 text-center text-slate-400 text-sm">Loading job source registry...</div>;
  }

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-white tracking-tight">Job Sources &amp; Connectors</h1>
        <p className="text-sm text-slate-400">Public ATS board registry and compliance governance policies</p>
      </div>

      <div className="p-4 rounded-xl bg-slate-800/40 border border-slate-700/50 text-xs text-slate-300 leading-relaxed">
        <span className="font-semibold text-indigo-400 block mb-1">Compliance Policy Engine:</span>
        All automated discovery connects strictly via legitimate public ATS boards (Greenhouse, Lever, SmartRecruiters, Ashby).
        No automated LinkedIn scraping is permitted; third-party links resolve to direct company boards.
      </div>

      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
          Configured ATS Sources ({sources.length})
        </h2>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-slate-400 border-b border-slate-700/60">
              <tr>
                <th className="pb-2">Source Name</th>
                <th className="pb-2">Platform Kind</th>
                <th className="pb-2">Org Identifier</th>
                <th className="pb-2">Policy</th>
                <th className="pb-2">Rate Limit</th>
                <th className="pb-2">Status</th>
                <th className="pb-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-700/40 text-slate-300">
              {sources.length === 0 ? (
                <tr>
                  <td colSpan={7} className="py-4 text-center text-slate-500">
                    No sources found in database.
                  </td>
                </tr>
              ) : (
                sources.map((src) => (
                  <tr key={src.id}>
                    <td className="py-3 font-medium text-white">{src.display_name}</td>
                    <td className="py-3 font-mono text-indigo-400">{src.kind}</td>
                    <td className="py-3 font-mono text-slate-400">{src.org_identifier}</td>
                    <td className="py-3">
                      <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
                        {src.policy}
                      </span>
                    </td>
                    <td className="py-3 text-slate-400 font-mono">{src.rate_limit_per_min}/min</td>
                    <td className="py-3">
                      <span className="inline-flex items-center gap-1.5 text-[11px] text-emerald-400 font-medium">
                        <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
                        Healthy
                      </span>
                    </td>
                    <td className="py-3 text-right">
                      <button
                        onClick={() => handleHealthCheck(src.id)}
                        disabled={checkingId === src.id}
                        className="px-2.5 py-1 rounded bg-slate-700 hover:bg-slate-600 text-slate-200 text-[11px] font-medium transition-colors"
                      >
                        {checkingId === src.id ? 'Pinging...' : 'Ping Board'}
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
