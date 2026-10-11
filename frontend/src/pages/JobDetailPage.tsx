import React, { useCallback, useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { JobDetailResponse } from '../types';
import { PrepWorkspace } from '../components/PrepWorkspace';
import {
  Alert,
  Chip,
  JobStatusPill,
  Loading,
  PageShell,
  SecondaryButton,
  SectionCard,
  STALE_AFTER_DAYS,
  StatusPill,
  WorkplacePill,
  formatSalary,
} from '../components/ui';

/** Locale date, or null when absent/unparseable — never "Invalid Date" or an invented "Recently". */
function formatDate(value?: string | null): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString();
}

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
  const [creatingApplication, setCreatingApplication] = useState(false);
  const [applyState, setApplyState] = useState<{ ok: boolean; message: string } | null>(null);

  const fetchJob = useCallback((jobId: string) => {
    setLoading(true);
    apiFetch<JobDetailResponse>(`/jobs/${jobId}`)
      .then(setData)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'Job not found'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (!id) return;
    setApplyState(null);
    fetchJob(id);
  }, [id, fetchJob]);

  // The human half of the decision engine: REVIEW matches never auto-create
  // an application — this button is the explicit owner decision.
  const createApplication = async () => {
    if (!id) return;
    setCreatingApplication(true);
    setApplyState(null);
    try {
      const result = await apiFetch<{ created: boolean; status: string }>(`/jobs/${id}/apply`, { method: 'POST' });
      setApplyState({
        ok: true,
        message: result.created
          ? 'Application created — it will be prepared and shown on your Applications page.'
          : `You already have an application for this job (status: ${result.status}).`,
      });
      fetchJob(id);
    } catch (err: unknown) {
      setApplyState({ ok: false, message: err instanceof Error ? err.message : 'Could not create the application' });
    } finally {
      setCreatingApplication(false);
    }
  };

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

  const { job, analysis, match, decision_trace } = data;

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
                {job.company_name_raw || 'Company not stated'}
              </p>
              {/* Every value is what the posting states; missing data is shown as missing. */}
              <div className="mt-4 grid grid-cols-1 gap-x-8 gap-y-3 text-sm sm:grid-cols-2 xl:grid-cols-3">
                <MetaItem label="Location">{job.location_raw || 'Not listed'}</MetaItem>
                <MetaItem label="Workplace type">
                  {job.remote_type && job.remote_type !== 'UNKNOWN' ? job.remote_type : 'Not stated'}
                </MetaItem>
                <MetaItem label="Compensation">
                  {formatSalary(job.salary_min, job.salary_max, job.salary_currency)}
                </MetaItem>
                <MetaItem label="Posted">{formatDate(job.posted_at) || 'Not stated by the board'}</MetaItem>
                <MetaItem label="Last seen by discovery">{formatDate(job.last_seen_at) || 'Unknown'}</MetaItem>
                <MetaItem label="Source">
                  {job.source_name ? `${job.source_name}${job.source_kind ? ` (${job.source_kind})` : ''}` : 'Unknown source'}
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

        {job.removed && (
          <Alert tone="error">
            This posting has been removed from the catalogue. It no longer appears in the Jobs Feed and should not be applied to.
          </Alert>
        )}
        {!job.removed && job.stale && (
          <Alert tone="info">
            Discovery has not seen this posting for {STALE_AFTER_DAYS}+ days, so it may have closed. The review queue will refuse approval until the posting is refreshed by a discovery run.
          </Alert>
        )}

        {/* Next steps in the workflow — links only to workflows that exist. */}
        <nav aria-label="Next steps for this job" className="flex flex-wrap items-center gap-2 rounded-xl border border-line bg-surface px-4 py-3 text-xs shadow-card">
          <span className="font-semibold text-ink-soft">Next steps:</span>
          <Link to="/review-queue" className="rounded-md border border-line px-2.5 py-1 font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50">
            APPLY · Open review queue
          </Link>
          <Link to="/applications" className="rounded-md border border-line px-2.5 py-1 font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50">
            TRACK · Open applications
          </Link>
          <a href="#application-package" className="rounded-md border border-line px-2.5 py-1 font-semibold text-ink-soft hover:border-forest-300 hover:bg-forest-50">
            PREP · Documents for this job
          </a>
        </nav>

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
            <SectionCard title="Robin's match decision">
              {match ? (
                <div className="space-y-4">
                  <div className="flex items-center justify-between">
                    <span className="text-3xl font-extrabold tracking-tight text-ink">
                      {match.score}
                      <span className="text-base font-semibold text-ink-faint">/100</span>
                    </span>
                    <StatusPill tone={match.recommendation === 'APPLY' ? 'emerald' : match.recommendation === 'REVIEW' ? 'amber' : 'slate'}>
                      {match.recommendation}
                    </StatusPill>
                  </div>
                  <ScoreBar value={match.score} />
                  <div className="space-y-1.5 text-xs">
                    {(['skill_overlap', 'remote_fit', 'salary_fit'] as const).map((factor) => (
                      <div key={factor} className="flex items-center justify-between text-ink-soft">
                        <span className="capitalize">{factor.replace(/_/g, ' ')}</span>
                        <span className="font-mono font-semibold text-ink">
                          {match.breakdown?.[factor] != null ? `${match.breakdown[factor]}%` : '—'}
                        </span>
                      </div>
                    ))}
                  </div>
                  {match.breakdown?.why && (
                    <p className="border-t border-line pt-3 text-xs leading-relaxed text-ink-muted">
                      {match.breakdown.why}
                    </p>
                  )}
                  {match.breakdown?.decision && (
                    <p className="text-xs font-medium leading-relaxed text-ink-soft">{match.breakdown.decision}</p>
                  )}
                  {match.recommendation !== 'APPLY' && (
                    <div className="border-t border-line pt-3">
                      <button
                        type="button"
                        disabled={creatingApplication}
                        onClick={createApplication}
                        className="w-full rounded-lg bg-forest-900 px-4 py-2 text-sm font-semibold text-cream-50 shadow-raise transition-colors hover:bg-forest-800 disabled:opacity-50"
                      >
                        {creatingApplication ? 'Creating…' : 'Create application anyway'}
                      </button>
                      {applyState && (
                        <p className={`mt-2 text-xs leading-relaxed ${applyState.ok ? 'text-emerald-600' : 'text-red-600'}`} role="status">
                          {applyState.message}
                        </p>
                      )}
                    </div>
                  )}
                </div>
              ) : (
                <div className="rounded-lg border border-dashed border-line bg-cream-50 p-4 text-center">
                  <span className="text-2xl font-bold text-ink-faint">— / 100</span>
                  <p className="mt-2 text-xs leading-relaxed text-ink-muted">
                    Not scored for you yet. Matching runs automatically after discovery when the posting passes your hard filters; a posting your filters reject is not scored.
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
                  No sponsorship analysis is available for this posting.
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
                  No decision trace is returned for this posting yet.
                </p>
              )}
            </SectionCard>
          </div>
        </div>

        {/* Application package — full width so documents are readable */}
        <div id="application-package">
          <PrepWorkspace jobId={job.id} />
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
