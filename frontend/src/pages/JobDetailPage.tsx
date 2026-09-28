import React, { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { ApplicationAnswer, CoverLetter, JobDetailResponse, ResumeAtsAnalysis } from '../types';
import {
  Alert,
  Chip,
  DocumentPreview,
  JobStatusPill,
  Loading,
  PageHeader,
  PageShell,
  PrimaryButton,
  SecondaryButton,
  SectionCard,
  StatusPill,
  WorkplacePill,
} from '../components/ui';

/**
 * Job Details — the deepest page in the product.
 *
 * Presentation-only redesign. Same data and handlers as before: the job +
 * analysis + score + decision trace from /jobs/:id, and the three application
 * subsystems (tailored CV, cover letter, application Q&A) with their generate,
 * approve and draft actions untouched.
 *
 * Layout hierarchy: job header → intelligence (2-col with score sidebar) →
 * application package (full width, so generated documents are finally easy to
 * read instead of being squeezed into a narrow column).
 */
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
    return (
      <PageShell>
        <Loading>Loading job intelligence…</Loading>
      </PageShell>
    );
  }

  if (error || !data) {
    return (
      <PageShell>
        <div className="space-y-4">
          <Alert tone="error">{error || 'Job not found'}</Alert>
          <SecondaryButton href="/jobs">← Back to Jobs Feed</SecondaryButton>
        </div>
      </PageShell>
    );
  }

  const { job, analysis, score, decision_trace } = data;

  return (
    <PageShell>
      <div className="space-y-6">
        {/* Breadcrumb + status */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <nav aria-label="Breadcrumb" className="text-sm">
            <Link
              to="/jobs"
              className="font-medium text-ink-muted transition-colors hover:text-forest-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
            >
              ← Jobs Feed
            </Link>
            <span aria-hidden="true" className="mx-2 text-ink-faint">/</span>
            <span className="font-semibold text-ink">{job.title}</span>
          </nav>
          <div className="flex items-center gap-2">
            <WorkplacePill type={job.remote_type} />
            <JobStatusPill status={job.status} />
          </div>
        </div>

        {/* Job header */}
        <header className="rounded-xl border border-line bg-surface p-6 shadow-card">
          <div className="flex flex-col gap-5 lg:flex-row lg:items-start lg:justify-between">
            <div className="min-w-0">
              <h1 className="text-2xl font-bold tracking-tight text-ink">{job.title}</h1>
              <p className="mt-1 text-base font-medium text-ink-soft">
                {job.company_name_raw || 'Direct employer'}
              </p>
              <div className="mt-4 grid grid-cols-1 gap-x-8 gap-y-3 text-sm sm:grid-cols-2 xl:grid-cols-4">
                <MetaItem label="Location">{job.location_raw || 'United Kingdom'}</MetaItem>
                <MetaItem label="Workplace type">{job.remote_type || 'HYBRID'}</MetaItem>
                <MetaItem label="Compensation">
                  {job.salary_min
                    ? `£${job.salary_min.toLocaleString()} – £${job.salary_max?.toLocaleString()}`
                    : 'Competitive'}
                </MetaItem>
                <MetaItem label="Posted">
                  {job.posted_at ? new Date(job.posted_at).toLocaleDateString() : 'Recently'}
                </MetaItem>
              </div>
            </div>
            {job.application_url && (
              <div className="shrink-0">
                <a
                  href={job.application_url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-forest-900 px-5 py-2.5 text-sm font-semibold text-cream-50 shadow-raise transition-colors hover:bg-forest-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 focus-visible:ring-offset-2"
                >
                  Apply on official board
                  <svg viewBox="0 0 16 16" fill="none" className="h-3.5 w-3.5" aria-hidden="true">
                    <path
                      d="M4.5 11.5 11.5 4.5M6 4.5h5.5V10"
                      stroke="currentColor"
                      strokeWidth="1.6"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                </a>
                <p className="mt-1.5 text-center text-[11px] text-ink-faint">
                  Opens the employer's board
                </p>
              </div>
            )}
          </div>
        </header>

        {/* Intelligence: description + sponsorship left, score + trace right */}
        <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
          <div className="space-y-6 xl:col-span-2">
            <SectionCard title="Job description">
              <div className="whitespace-pre-line text-sm leading-relaxed text-ink-soft">
                {job.description_text}
              </div>
            </SectionCard>

            {analysis?.summary && (
              <SectionCard title="Analyst summary" hint="LLM analysis of this posting">
                <p className="text-sm leading-relaxed text-ink-soft">{analysis.summary}</p>
              </SectionCard>
            )}

            {analysis?.match_explanation && (
              <SectionCard title="Why this matches your profile">
                <p className="text-sm leading-relaxed text-ink-soft">{analysis.match_explanation}</p>
              </SectionCard>
            )}

            {analysis?.skills_required && (
              <SectionCard title="Skills required" hint="Grouped by requirement level">
                <div className="space-y-3">
                  {Object.entries(analysis.skills_required).map(([group, skills]) => (
                    <div key={group}>
                      <p className="text-xs font-semibold capitalize text-ink-muted">{group}</p>
                      <div className="mt-1.5 flex flex-wrap gap-1.5">
                        {skills.map((skill, i) => (
                          <Chip key={`${skill}-${i}`}>{skill}</Chip>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </SectionCard>
            )}

            {job.skills_extracted && job.skills_extracted.length > 0 && (
              <SectionCard title="Extracted skills & technologies">
                <div className="flex flex-wrap gap-1.5">
                  {job.skills_extracted.map((skill, i) => (
                    <Chip key={`${skill}-${i}`}>{skill}</Chip>
                  ))}
                </div>
              </SectionCard>
            )}
          </div>

          <div className="space-y-6">
            {/* Score */}
            <SectionCard title="Compatibility score">
              {score ? (
                <div className="space-y-4">
                  <div className="flex items-center justify-between">
                    <span className="text-3xl font-extrabold tracking-tight text-ink">
                      {score.overall}
                      <span className="text-base font-semibold text-ink-faint">/100</span>
                    </span>
                    <StatusPill tone={score.recommendation === 'APPLY' ? 'emerald' : score.recommendation === 'REVIEW' ? 'amber' : 'slate'}>
                      {score.recommendation}
                    </StatusPill>
                  </div>
                  <ScoreBar value={score.overall} />
                  <div className="space-y-1.5 text-xs">
                    {Object.entries(score.breakdown).map(([cat, val]) => (
                      <div key={cat} className="flex items-center justify-between text-ink-soft">
                        <span className="capitalize">{cat.replace(/_/g, ' ').toLowerCase()}</span>
                        <span className="font-mono font-semibold text-ink">{val} pts</span>
                      </div>
                    ))}
                  </div>
                  {score.explanation && (
                    <p className="border-t border-line pt-3 text-xs leading-relaxed text-ink-muted">
                      {score.explanation}
                    </p>
                  )}
                </div>
              ) : (
                <div className="rounded-lg border border-dashed border-line bg-cream-50 p-4 text-center">
                  <span className="text-2xl font-bold text-ink-faint">— / 100</span>
                  <p className="mt-2 text-xs leading-relaxed text-ink-muted">
                    Scoring runs in Phase 3 after the rule-filtering gate.
                  </p>
                </div>
              )}
            </SectionCard>

            {/* Sponsorship */}
            <SectionCard title="Sponsorship signal" hint="Home Office cross-check">
              {analysis?.sponsorship ? (
                <div className="space-y-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-bold text-ink">{analysis.sponsorship.status}</span>
                    <span className="font-mono text-xs font-semibold text-forest-700">
                      {(analysis.sponsorship.confidence * 100).toFixed(0)}% confidence
                    </span>
                  </div>
                  <p className="text-xs leading-relaxed text-ink-soft">{analysis.sponsorship.reason}</p>
                  {analysis.sponsorship.evidence?.length > 0 && (
                    <div className="space-y-1.5 border-t border-line pt-2.5">
                      <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">
                        Evidence
                      </p>
                      {analysis.sponsorship.evidence.map((ev, i) => (
                        <div key={i} className="rounded-md border border-line bg-cream-50 px-2.5 py-1.5 text-[11px] text-ink-soft">
                          <span className="font-semibold text-ink">{ev.type}</span>
                          {ev.snippet && <span className="ml-1.5">— “{ev.snippet}”</span>}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ) : (
                <p className="text-xs leading-relaxed text-ink-muted">
                  Sponsorship evaluation fuses the Home Office register with JD analysis in Phase 3.
                </p>
              )}
            </SectionCard>

            {/* Decision trace */}
            <SectionCard title="Decision trace">
              {decision_trace && decision_trace.length > 0 ? (
                <ol className="space-y-1.5">
                  {decision_trace.map((step, i) => (
                    <li
                      key={i}
                      className="flex items-center justify-between gap-2 rounded-md border border-line bg-cream-50 px-2.5 py-1.5"
                    >
                      <span className="truncate font-mono text-[11px] text-ink-soft">{step.step}</span>
                      <span className="shrink-0 text-[11px] font-bold text-forest-700">{step.outcome}</span>
                    </li>
                  ))}
                </ol>
              ) : (
                <p className="text-xs leading-relaxed text-ink-muted">
                  Decision steps are logged idempotently as each filter evaluates the job.
                </p>
              )}
            </SectionCard>
          </div>
        </div>

        {/* Application package — full width so documents are readable */}
        <div className="space-y-4">
          <PageHeader
            eyebrow="Application package"
            title="Apply with preparation"
            subtitle="Everything Robin prepares for this specific job: a tailored CV snapshot, a grounded cover letter and drafted application answers."
          />
          <div className="space-y-6">
            <TailoredCvSection jobId={job.id} />
            <CoverLetterSection jobId={job.id} />
            <ApplicationQASection jobId={job.id} />
          </div>
        </div>
      </div>
    </PageShell>
  );
};

/* ------------------------------------------------------------------ */
/* Small shared pieces                                                 */
/* ------------------------------------------------------------------ */

const MetaItem: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div>
    <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">{label}</p>
    <p className="mt-0.5 font-medium text-ink">{children}</p>
  </div>
);

const ScoreBar: React.FC<{ value: number }> = ({ value }) => (
  <div
    role="img"
    aria-label={`Overall compatibility ${value} out of 100`}
    className="h-2 w-full overflow-hidden rounded-full bg-cream-200"
  >
    <div
      className="h-full rounded-full bg-forest-700 transition-all"
      style={{ width: `${Math.max(0, Math.min(100, value))}%` }}
    />
  </div>
);

const SectionHint: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="text-[11px] text-ink-faint">{children}</span>
);

/* ------------------------------------------------------------------ */
/* Tailored CV subsystem                                               */
/* ------------------------------------------------------------------ */

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
  return (
    <SectionCard
      title="Job-specific ATS CV"
      hint="Generated from your Master Profile — an immutable snapshot"
      bodyClassName="space-y-4"
    >
      {error && <Alert tone="error">{error}</Alert>}
      {analysis ? (
        <>
          <div className="grid grid-cols-2 gap-3 text-xs sm:grid-cols-4">
            <Stat label="CV version" value={analysis.cvVersionId} mono />
            <Stat label="Master revision" value={`r${analysis.profileRevision}`} />
            <Stat label="Evidence claims" value={String(analysis.verifiedEvidence.length)} tone="text-forest-700" />
            <Stat
              label="Gaps"
              value={analysis.gaps.length ? `${analysis.gaps.length}` : 'None'}
              tone={analysis.gaps.length ? 'text-amber-700' : 'text-forest-700'}
            />
          </div>

          {analysis.gaps.length > 0 && (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-xs text-amber-900">
              <span className="font-semibold">Gaps detected: </span>{analysis.gaps.join(' · ')}
            </div>
          )}

          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">
              Matched verified evidence
            </p>
            <div className="flex flex-wrap gap-1.5">
              {analysis.verifiedEvidence.map((item, index) => (
                <StatusPill key={`${item.evidence_id}-${index}`} tone="emerald">
                  {item.claim}
                </StatusPill>
              ))}
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2 rounded-lg border border-line bg-cream-50 px-3.5 py-2.5 text-xs text-ink-soft">
            <span>
              ATS keyword coverage: <strong className="font-semibold text-forest-700">{String(analysis.atsReport.keyword_coverage ?? 0)}%</strong>
            </span>
            <span className="text-ink-faint">(heuristic, not a hiring guarantee)</span>
          </div>

          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">
              Tailored CV document
            </p>
            <DocumentPreview className="max-h-[32rem]">{analysis.resumeMarkdown}</DocumentPreview>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-3.5">
            <span className="text-[11px] text-ink-faint">
              Immutable PDF artifact · SHA-256 {analysis.contentSha256.slice(0, 16)}…
            </span>
            <a
              href={`${import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'}/resume-intelligence/cv/${analysis.cvVersionId}/artifact`}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-forest-900 px-4 py-2 text-xs font-semibold text-cream-50 transition-colors hover:bg-forest-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 focus-visible:ring-offset-2"
            >
              Download exact CV PDF
            </a>
          </div>
        </>
      ) : (
        <>
          <p className="text-sm leading-relaxed text-ink-soft">
            This never edits your Master Profile. It creates a distinct, provenance-linked CV tailored to this job.
          </p>
          <PrimaryButton onClick={tailor} disabled={loading}>
            {loading ? 'Analysing verified evidence…' : 'Generate job-specific CV'}
          </PrimaryButton>
        </>
      )}
    </SectionCard>
  );
};

const Stat: React.FC<{ label: string; value: string; mono?: boolean; tone?: string }> = ({
  label,
  value,
  mono = false,
  tone = 'text-ink',
}) => (
  <div>
    <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">{label}</p>
    <p className={`mt-0.5 truncate font-semibold ${mono ? 'font-mono text-[11px]' : ''} ${tone}`} title={value}>
      {value}
    </p>
  </div>
);

/* ------------------------------------------------------------------ */
/* Cover letter subsystem                                              */
/* ------------------------------------------------------------------ */

const CoverLetterSection: React.FC<{ jobId: string }> = ({ jobId }) => {
  const [coverLetters, setCoverLetters] = useState<CoverLetter[]>([]);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchLetters = () => {
    apiFetch<CoverLetter[]>(`/cover-letters/job/${jobId}`)
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
    <SectionCard
      title="Cover letter"
      hint="Every claim grounded in your verified evidence"
      actions={latest ? <SectionHint>Version {latest.version + 1} available on regenerate</SectionHint> : undefined}
      bodyClassName="space-y-4"
    >
      {error && <Alert tone="error">{error}</Alert>}

      {latest ? (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-sm font-semibold text-ink">{latest.title}</span>
            <StatusPill tone={latest.isApproved ? 'emerald' : 'amber'}>
              {latest.isApproved ? 'Approved' : 'Pending review'}
            </StatusPill>
          </div>

          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">
              Letter document
            </p>
            <DocumentPreview className="max-h-[32rem]">{latest.bodyMarkdown}</DocumentPreview>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-3.5">
            <SecondaryButton onClick={() => handleApproval(latest.id, !latest.isApproved)}>
              {latest.isApproved ? 'Mark unapproved' : 'Approve letter'}
            </SecondaryButton>
            <PrimaryButton onClick={handleGenerate} disabled={generating}>
              {generating ? 'Regenerating…' : `Regenerate v${latest.version + 1}`}
            </PrimaryButton>
          </div>
        </>
      ) : (
        <div className="space-y-3">
          <p className="text-sm leading-relaxed text-ink-soft">
            No cover letter generated yet for this job. Robin drafts it from your verified evidence — never invented claims.
          </p>
          <PrimaryButton onClick={handleGenerate} disabled={generating}>
            {generating ? 'Synthesising with anti-fabrication checks…' : 'Generate ATS cover letter'}
          </PrimaryButton>
        </div>
      )}
    </SectionCard>
  );
};

/* ------------------------------------------------------------------ */
/* Application Q&A subsystem                                           */
/* ------------------------------------------------------------------ */

const ApplicationQASection: React.FC<{ jobId: string }> = ({ jobId }) => {
  const [answers, setAnswers] = useState<ApplicationAnswer[]>([]);
  const [questionText, setQuestionText] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchAnswers = () => {
    apiFetch<ApplicationAnswer[]>(`/application-answers/job/${jobId}`)
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
    <SectionCard
      title="Application Q&A"
      hint="Drafted from verified facts only"
      bodyClassName="space-y-4"
    >
      {error && <Alert tone="error">{error}</Alert>}

      <form onSubmit={handleDraft} className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex-1">
          <label htmlFor="qa-question" className="mb-1 block text-xs font-semibold text-ink-soft">
            Screening question
          </label>
          <input
            id="qa-question"
            type="text"
            value={questionText}
            onChange={(e) => setQuestionText(e.target.value)}
            placeholder="e.g. Why do you want to work here?"
            className="w-full rounded-lg border border-line bg-surface px-3.5 py-2 text-sm text-ink placeholder-ink-faint transition-colors focus:border-forest-600 focus:outline-none focus:ring-2 focus:ring-forest-600/20"
          />
        </div>
        <button
          type="submit"
          disabled={drafting || !questionText.trim()}
          className="inline-flex items-center justify-center gap-1.5 rounded-lg border border-line bg-surface px-4 py-2 text-sm font-semibold text-ink-soft transition-colors hover:border-forest-300 hover:bg-forest-50 hover:text-forest-900 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 focus-visible:ring-offset-2"
        >
          {drafting ? 'Drafting from verified facts…' : 'Draft grounded answer'}
        </button>
      </form>

      {answers.length > 0 ? (
        <div className="space-y-3 border-t border-line pt-4">
          {answers.map((ans) => (
            <div key={ans.id} className="rounded-lg border border-line bg-cream-50/70 p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-semibold text-ink">{ans.questionText}</span>
                <StatusPill tone={ans.status === 'ANSWERED' ? 'emerald' : ans.status === 'HARD_STOP' ? 'red' : 'amber'}>
                  {ans.status}
                </StatusPill>
              </div>
              <p className="mt-2 text-sm leading-relaxed text-ink-soft">{ans.answerText}</p>
              {ans.validationNotes?.issues && ans.validationNotes.issues.length > 0 && (
                <ul className="mt-2 space-y-0.5 border-t border-line pt-2 text-xs text-amber-800">
                  {ans.validationNotes.issues.map((issue, i) => (
                    <li key={i}>• {issue}</li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </div>
      ) : (
        <p className="border-t border-line pt-4 text-sm leading-relaxed text-ink-muted">
          Drafted answers appear here. Robin only answers from your verified Master Profile evidence —
          anything it cannot support is flagged for your input.
        </p>
      )}
    </SectionCard>
  );
};
