import React, { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { JobDetailResponse } from '../types';

export const JobDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [data, setData] = useState<JobDetailResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    apiFetch<JobDetailResponse>(`/jobs/${id}`)
      .then(setData)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'Job not found'))
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) {
    return <div className="p-12 text-center text-slate-400 text-sm">Loading job intelligence...</div>;
  }

  if (error || !data) {
    return (
      <div className="p-8 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-sm">
        {error || 'Job not found'}
        <div className="mt-4">
          <Link to="/jobs" className="text-indigo-400 hover:underline">
            ← Back to Jobs Feed
          </Link>
        </div>
      </div>
    );
  }

  const { job, analysis, score, decision_trace } = data;

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      <div className="flex items-center justify-between">
        <Link to="/jobs" className="text-sm text-slate-400 hover:text-slate-200 flex items-center gap-1">
          ← Back to Jobs Feed
        </Link>
        <div className="flex items-center gap-2">
          <span className="px-3 py-1 rounded-full text-xs font-semibold bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
            {job.status}
          </span>
        </div>
      </div>

      {/* Hero Header */}
      <div className="p-6 rounded-2xl bg-slate-800/80 border border-slate-700/70 space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-white tracking-tight">{job.title}</h1>
            <p className="text-base text-slate-300 font-medium mt-1">
              {job.company_name_raw || 'Direct Employer'}
            </p>
          </div>
          {job.application_url && (
            <a
              href={job.application_url}
              target="_blank"
              rel="noopener noreferrer"
              className="px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-medium text-sm transition-all shadow-lg shadow-indigo-600/20 text-center"
            >
              Apply on Official Board ↗
            </a>
          )}
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 pt-3 border-t border-slate-700/50 text-xs">
          <div>
            <span className="text-slate-500 block">Location</span>
            <span className="text-white font-medium">{job.location_raw || 'United Kingdom'}</span>
          </div>
          <div>
            <span className="text-slate-500 block">Workplace Type</span>
            <span className="text-white font-medium">{job.remote_type || 'HYBRID'}</span>
          </div>
          <div>
            <span className="text-slate-500 block">Compensation</span>
            <span className="text-emerald-400 font-semibold">
              {job.salary_min ? `£${job.salary_min.toLocaleString()} - £${job.salary_max?.toLocaleString()}` : 'Competitive'}
            </span>
          </div>
          <div>
            <span className="text-slate-500 block">Posted Date</span>
            <span className="text-white font-medium">
              {job.posted_at ? new Date(job.posted_at).toLocaleDateString() : 'Recent'}
            </span>
          </div>
        </div>
      </div>

      {/* Two Column Grid: Analysis & Score vs Description */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left 2 Cols: Description & Skills */}
        <div className="lg:col-span-2 space-y-6">
          <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 mb-3">
              Job Description
            </h2>
            <div className="text-sm text-slate-300 leading-relaxed whitespace-pre-line">
              {job.description_text}
            </div>
          </div>

          {job.skills_extracted && job.skills_extracted.length > 0 && (
            <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60">
              <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 mb-3">
                Extracted Skills & Technologies
              </h2>
              <div className="flex flex-wrap gap-2">
                {job.skills_extracted.map((skill, i) => (
                  <span
                    key={i}
                    className="px-3 py-1 rounded-lg text-xs font-medium bg-slate-900 text-indigo-300 border border-slate-700/60"
                  >
                    {skill}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Right 1 Col: Intelligence, Scoring & Audit */}
        <div className="space-y-6">
          {/* Score Card */}
          <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 mb-4 flex items-center justify-between">
              <span>Compatibility Score</span>
              <span className="text-xs font-mono text-indigo-400">Phase 3</span>
            </h2>

            {score ? (
              <div className="space-y-4">
                <div className="flex items-baseline justify-between">
                  <span className="text-3xl font-extrabold text-white">{score.overall}/100</span>
                  <span className="px-2.5 py-0.5 rounded text-xs font-bold bg-emerald-500/20 text-emerald-400">
                    {score.recommendation}
                  </span>
                </div>
                <div className="space-y-1.5 text-xs">
                  {Object.entries(score.breakdown).map(([cat, val]) => (
                    <div key={cat} className="flex justify-between text-slate-300">
                      <span className="capitalize">{cat}</span>
                      <span className="font-mono font-medium">{val} pts</span>
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              <div className="p-4 rounded-lg bg-slate-900/60 border border-slate-800 text-center">
                <span className="text-2xl font-bold text-slate-400">-- / 100</span>
                <p className="text-xs text-slate-500 mt-2">
                  Scoring calculation runs in Phase 3 after rule-filtering gate.
                </p>
              </div>
            )}
          </div>

          {/* Sponsorship Intelligence Card */}
          <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 mb-4 flex items-center justify-between">
              <span>Sponsorship Signal</span>
              <span className="text-xs font-mono text-indigo-400">Signals 1–4</span>
            </h2>

            {analysis?.sponsorship ? (
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-bold text-white">{analysis.sponsorship.status}</span>
                  <span className="text-xs font-mono text-emerald-400">
                    {(analysis.sponsorship.confidence * 100).toFixed(0)}% Conf
                  </span>
                </div>
                <p className="text-xs text-slate-300 leading-relaxed">{analysis.sponsorship.reason}</p>
              </div>
            ) : (
              <div className="p-4 rounded-lg bg-slate-900/60 border border-slate-800 text-center">
                <p className="text-xs text-slate-400 font-medium">Home Office Register Cross-Check</p>
                <p className="text-[11px] text-slate-500 mt-1">
                  Sponsorship evaluation fuses Home Office Register + JD analysis in Phase 3.
                </p>
              </div>
            )}
          </div>

          {/* Decision Trace */}
          <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300 mb-3">
              Auditable Decision Trace
            </h2>
            {decision_trace && decision_trace.length > 0 ? (
              <div className="space-y-2">
                {decision_trace.map((step, i) => (
                  <div key={i} className="text-xs p-2 rounded bg-slate-900 border border-slate-800 flex justify-between">
                    <span className="font-mono text-slate-300">{step.step}</span>
                    <span className="font-semibold text-emerald-400">{step.outcome}</span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-xs text-slate-500">
                Decision steps are logged idempotently as each filter evaluates the job.
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
