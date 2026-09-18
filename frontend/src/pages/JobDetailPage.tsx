import React, { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { JobDetailResponse, ResumeAtsAnalysis } from '../types';

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
          {/* Tailored CV Subsystem: generated from the canonical Master Profile */}
          <TailoredCvSection jobId={job.id} />

          {/* Cover Letter Subsystem */}
          <CoverLetterSection jobId={job.id} />

          {/* Application QA Subsystem */}
          <ApplicationQASection jobId={job.id} />

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

const TailoredCvSection: React.FC<{ jobId: string }> = ({ jobId }) => {
  const [analysis, setAnalysis] = useState<ResumeAtsAnalysis | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const tailor = async () => {
    setLoading(true); setError(null);
    try { setAnalysis(await apiFetch<ResumeAtsAnalysis>('/resume-intelligence/tailor', { method: 'POST', body: JSON.stringify({ jobId }) })); }
    catch (e) { setError(e instanceof Error ? e.message : 'Tailoring failed'); }
    finally { setLoading(false); }
  };
  return <div className="p-6 rounded-xl bg-slate-800/60 border border-indigo-500/20 space-y-3">
    <div className="flex items-center justify-between"><h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">Job-specific ATS CV</h2><span className="text-xs text-indigo-400">Master Profile → immutable snapshot</span></div>
    {error && <div className="text-xs text-red-400">{error}</div>}
    {analysis ? <><div className="grid grid-cols-2 gap-2 text-xs"><div className="text-slate-400">CV version <span className="block text-white font-mono">{analysis.cvVersionId}</span></div><div className="text-slate-400">Master revision <span className="block text-white">{analysis.profileRevision}</span></div><div className="text-slate-400">Evidence claims <span className="block text-emerald-400">{analysis.verifiedEvidence.length}</span></div><div className="text-slate-400">Gaps <span className="block text-amber-400">{analysis.gaps.length ? analysis.gaps.join(', ') : 'None detected'}</span></div></div><div className="space-y-2 text-xs"><div><span className="text-slate-500">Matched verified evidence</span><div className="flex flex-wrap gap-1 mt-1">{analysis.verifiedEvidence.map((item, index) => <span key={`${item.evidence_id}-${index}`} className="px-2 py-1 rounded bg-emerald-500/10 text-emerald-300 border border-emerald-500/20">{item.claim}</span>)}</div></div><div className="p-2 rounded bg-slate-900 text-slate-300">ATS keyword coverage: <strong className="text-indigo-300">{String(analysis.atsReport.keyword_coverage ?? 0)}%</strong> <span className="text-slate-500">(heuristic, not a hiring guarantee)</span></div></div><pre className="max-h-48 overflow-auto p-3 rounded bg-slate-900 text-[11px] text-slate-300 whitespace-pre-wrap">{analysis.resumeMarkdown}</pre><div className="flex items-center justify-between pt-2 border-t border-slate-700"><span className="text-[11px] text-slate-500">Immutable PDF artifact · SHA-256 {analysis.contentSha256}</span><a href={`${import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'}/resume-intelligence/cv/${analysis.cvVersionId}/artifact`} target="_blank" rel="noreferrer" className="px-3 py-1.5 rounded bg-indigo-600 text-xs text-white">Download exact CV PDF</a></div></> : <><p className="text-xs text-slate-400">This never edits the Master Profile. It creates a distinct, provenance-linked CV for this job.</p><button onClick={tailor} disabled={loading} className="w-full py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-xs font-semibold text-white disabled:opacity-50">{loading ? 'Analysing verified evidence...' : 'Generate job-specific CV'}</button></>}
  </div>;
};

const CoverLetterSection: React.FC<{ jobId: string }> = ({ jobId }) => {
  const [coverLetters, setCoverLetters] = useState<import('../types').CoverLetter[]>([]);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchLetters = () => {
    apiFetch<import('../types').CoverLetter[]>(`/cover-letters/job/${jobId}`)
      .then(setCoverLetters)
      .catch(() => {});
  };

  useEffect(() => {
    fetchLetters();
  }, [jobId]);

  const handleGenerate = async () => {
    setGenerating(true);
    setError(null);
    try {
      await apiFetch('/cover-letters/generate', {
        method: 'POST',
        body: JSON.stringify({ jobId }),
      });
      fetchLetters();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Generation failed');
    } finally {
      setGenerating(false);
    }
  };

  const handleApproval = async (id: string, approved: boolean) => {
    try {
      await apiFetch(`/cover-letters/${id}/approval`, {
        method: 'PUT',
        body: JSON.stringify({ approved }),
      });
      fetchLetters();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Update failed');
    }
  };

  const latest = coverLetters[0];

  return (
    <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
          Cover Letter
        </h2>
        <span className="text-xs font-mono text-indigo-400">Feature 2</span>
      </div>

      {error && <div className="p-2 rounded bg-red-500/10 border border-red-500/20 text-red-400 text-xs">{error}</div>}

      {latest ? (
        <div className="space-y-3">
          <div className="flex items-center justify-between text-xs">
            <span className="font-medium text-white">{latest.title}</span>
            <span className={`px-2 py-0.5 rounded text-[11px] font-bold ${latest.is_approved ? 'bg-emerald-500/20 text-emerald-400' : 'bg-amber-500/20 text-amber-400'}`}>
              {latest.is_approved ? 'Approved' : 'Pending Review'}
            </span>
          </div>

          <div className="max-h-48 overflow-y-auto p-3 rounded-lg bg-slate-900/80 border border-slate-800 text-xs text-slate-300 font-mono whitespace-pre-wrap leading-relaxed">
            {latest.body_markdown}
          </div>

          <div className="flex items-center justify-between pt-2">
            <button
              onClick={() => handleApproval(latest.id, !latest.is_approved)}
              className="px-3 py-1.5 rounded-lg text-xs font-medium bg-slate-700 hover:bg-slate-600 text-white transition-colors"
            >
              {latest.is_approved ? 'Mark Unapproved' : 'Approve Letter'}
            </button>
            <button
              onClick={handleGenerate}
              disabled={generating}
              className="px-3 py-1.5 rounded-lg text-xs font-medium bg-indigo-600 hover:bg-indigo-500 text-white disabled:opacity-50 transition-colors"
            >
              {generating ? 'Regenerating...' : 'Regenerate v' + (latest.version + 1)}
            </button>
          </div>
        </div>
      ) : (
        <div className="text-center py-4 space-y-3">
          <p className="text-xs text-slate-400">No cover letter generated yet for this job.</p>
          <button
            onClick={handleGenerate}
            disabled={generating}
            className="w-full py-2 px-4 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold disabled:opacity-50 transition-all shadow-md shadow-indigo-600/20"
          >
            {generating ? 'Synthesizing with Anti-Fabrication...' : 'Generate ATS Cover Letter'}
          </button>
        </div>
      )}
    </div>
  );
};

const ApplicationQASection: React.FC<{ jobId: string }> = ({ jobId }) => {
  const [answers, setAnswers] = useState<import('../types').ApplicationAnswer[]>([]);
  const [questionText, setQuestionText] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchAnswers = () => {
    apiFetch<import('../types').ApplicationAnswer[]>(`/application-answers/job/${jobId}`)
      .then(setAnswers)
      .catch(() => {});
  };

  useEffect(() => {
    fetchAnswers();
  }, [jobId]);

  const handleDraft = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!questionText.trim()) return;
    setDrafting(true);
    setError(null);
    try {
      await apiFetch('/application-answers/draft', {
        method: 'POST',
        body: JSON.stringify({ jobId, questionText }),
      });
      setQuestionText('');
      fetchAnswers();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Drafting failed');
    } finally {
      setDrafting(false);
    }
  };

  return (
    <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
          Application Q&A
        </h2>
        <span className="text-xs font-mono text-indigo-400">Feature 3</span>
      </div>

      {error && <div className="p-2 rounded bg-red-500/10 border border-red-500/20 text-red-400 text-xs">{error}</div>}

      <form onSubmit={handleDraft} className="space-y-2">
        <input
          type="text"
          value={questionText}
          onChange={(e) => setQuestionText(e.target.value)}
          placeholder="e.g. Why do you want to work here?"
          className="w-full px-3 py-2 rounded-lg bg-slate-900 border border-slate-700 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-indigo-500"
        />
        <button
          type="submit"
          disabled={drafting || !questionText.trim()}
          className="w-full py-1.5 px-3 rounded-lg bg-slate-700 hover:bg-slate-600 text-white text-xs font-medium disabled:opacity-50 transition-colors"
        >
          {drafting ? 'Drafting from Verified Facts...' : 'Draft Grounded Answer'}
        </button>
      </form>

      {answers.length > 0 && (
        <div className="space-y-3 pt-2">
          {answers.map((ans) => (
            <div key={ans.id} className="p-3 rounded-lg bg-slate-900/80 border border-slate-800 space-y-1.5 text-xs">
              <div className="flex items-center justify-between">
                <span className="font-medium text-slate-300 line-clamp-1">{ans.question_text}</span>
                <span className={`px-2 py-0.5 rounded text-[10px] font-bold ${ans.status === 'ANSWERED' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-amber-500/20 text-amber-400'}`}>
                  {ans.status}
                </span>
              </div>
              <p className="text-slate-400 leading-relaxed font-sans">{ans.answer_text}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};


