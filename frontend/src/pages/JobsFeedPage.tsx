import React, { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { Job } from '../types';

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
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Jobs Feed</h1>
          <p className="text-sm text-slate-400">Search and review discovered UK job postings with full-text search</p>
        </div>
      </div>

      {/* Quick Add Form */}
      <div className="p-4 rounded-xl bg-slate-800/40 border border-slate-700/50">
        <form onSubmit={handleImport} className="flex flex-col sm:flex-row items-center gap-3">
          <input
            type="url"
            value={importUrl}
            onChange={(e) => setImportUrl(e.target.value)}
            placeholder="Paste LinkedIn or ATS job URL (e.g. boards.greenhouse.io/...)"
            className="flex-1 w-full rounded-lg bg-slate-900 border border-slate-700 px-3.5 py-2 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          <button
            type="submit"
            className="w-full sm:w-auto px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium transition-colors shadow-md"
          >
            Quick Add
          </button>
        </form>
        {importMsg && (
          <div className="mt-2 text-xs text-indigo-400">
            {importMsg}
          </div>
        )}
      </div>

      {/* Filters Bar */}
      <div className="flex flex-col sm:flex-row items-center gap-4 bg-slate-800/60 p-4 rounded-xl border border-slate-700/60">
        <div className="flex-1 w-full relative">
          <span className="absolute inset-y-0 left-3 flex items-center text-slate-500">🔍</span>
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search keywords (e.g. Kotlin, Kubernetes, Senior)..."
            className="w-full pl-9 pr-3.5 py-2 rounded-lg bg-slate-900 border border-slate-700 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </div>

        <div className="w-full sm:w-48">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="w-full py-2 px-3 rounded-lg bg-slate-900 border border-slate-700 text-sm text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            <option value="">All Statuses</option>
            <option value="DISCOVERED">DISCOVERED</option>
            <option value="FILTERED_OUT">FILTERED_OUT</option>
            <option value="ANALYSED">ANALYSED</option>
            <option value="SCORED">SCORED</option>
            <option value="DECIDED">DECIDED</option>
            <option value="ARCHIVED">ARCHIVED</option>
          </select>
        </div>
      </div>

      {/* Jobs List */}
      <div className="space-y-3">
        {loading ? (
          <div className="p-12 text-center text-slate-400 text-sm">Searching job database...</div>
        ) : jobs.length === 0 ? (
          <div className="p-12 rounded-xl bg-slate-800/40 border border-slate-700/50 text-center text-slate-400 text-sm">
            No matching jobs found. Try adjusting your search query or filters.
          </div>
        ) : (
          jobs.map((job) => (
            <div
              key={job.id}
              className="p-5 rounded-xl bg-slate-800/60 border border-slate-700/60 hover:border-slate-600 transition-all group"
            >
              <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
                <div className="space-y-1.5 flex-1">
                  <div className="flex items-center gap-2.5 flex-wrap">
                    <Link
                      to={`/jobs/${job.id}`}
                      className="text-base font-bold text-white group-hover:text-indigo-400 transition-colors"
                    >
                      {job.title}
                    </Link>
                    {job.remote_type && (
                      <span className="px-2 py-0.5 rounded text-[11px] font-semibold bg-slate-700 text-slate-300">
                        {job.remote_type}
                      </span>
                    )}
                    <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
                      {job.status}
                    </span>
                  </div>

                  <div className="text-xs text-slate-400 flex items-center gap-2 flex-wrap">
                    <span className="font-semibold text-slate-200">{job.company_name_raw || 'Company'}</span>
                    <span>•</span>
                    <span>📍 {job.location_raw || 'UK'}</span>
                    {job.salary_min && (
                      <>
                        <span>•</span>
                        <span className="text-emerald-400 font-semibold">
                          💰 £{job.salary_min.toLocaleString()} - £{job.salary_max?.toLocaleString()} / yr
                        </span>
                      </>
                    )}
                  </div>

                  <p className="text-xs text-slate-300 line-clamp-2 pt-1 leading-relaxed">
                    {job.description_text}
                  </p>

                  {job.skills_extracted && job.skills_extracted.length > 0 && (
                    <div className="flex items-center gap-1.5 flex-wrap pt-1.5">
                      {job.skills_extracted.slice(0, 6).map((skill, i) => (
                        <span
                          key={i}
                          className="px-2 py-0.5 rounded text-[11px] bg-slate-900/80 text-slate-400 border border-slate-700/50"
                        >
                          {skill}
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                <div className="sm:text-right flex sm:flex-col items-center sm:items-end justify-between gap-2">
                  <span className="text-[11px] text-slate-500">
                    Seen {new Date(job.first_seen_at).toLocaleDateString()}
                  </span>
                  <Link
                    to={`/jobs/${job.id}`}
                    className="px-4 py-2 rounded-lg text-xs font-semibold bg-indigo-600/20 hover:bg-indigo-600/30 text-indigo-400 border border-indigo-500/30 transition-colors"
                  >
                    View Details
                  </Link>
                </div>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};
