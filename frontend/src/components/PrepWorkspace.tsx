import React, { useCallback, useEffect, useState } from 'react';
import { apiDownload, apiFetch, saveDownload } from '../api/client';
import type {
  ApplicationAnswer,
  CoverLetter,
  CvComparison,
  DocumentValidation,
  LatestCvResponse,
  PrepItem,
  PrepReadiness,
  ValidationFinding,
} from '../types';
import {
  Alert,
  DocumentPreview,
  Loading,
  PageHeader,
  PrimaryButton,
  SecondaryButton,
  SectionCard,
  StatusPill,
} from './ui';

/**
 * PREP workspace for one job: readiness derived by the backend from stored
 * records, the tailored CV (validation, review, comparison, verified PDF),
 * cover-letter versions (validation, approval, correction, verified PDF) and
 * screening answers.
 *
 * Nothing here shows a document as generated, approved, valid or ready
 * unless the API response for that record says so.
 */
export const PrepWorkspace: React.FC<{ jobId: string }> = ({ jobId }) => {
  const [readiness, setReadiness] = useState<PrepReadiness | null>(null);
  const [readinessError, setReadinessError] = useState<string | null>(null);
  const [readinessLoading, setReadinessLoading] = useState(true);
  const [version, setVersion] = useState(0);

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    let cancelled = false;
    setReadinessLoading(true);
    setReadinessError(null);
    apiFetch<PrepReadiness>(`/prep/jobs/${jobId}/readiness`)
      .then((value) => { if (!cancelled) setReadiness(value); })
      .catch((err: unknown) => {
        if (!cancelled) {
          setReadiness(null);
          setReadinessError(err instanceof Error ? err.message : 'Preparation status could not be loaded.');
        }
      })
      .finally(() => { if (!cancelled) setReadinessLoading(false); });
    return () => { cancelled = true; };
  }, [jobId, version]);

  const applicationId = readiness?.applicationId ?? null;

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Stage 2 · PREP"
        title="Prepare this application"
        subtitle="A job-specific CV built only from your verified profile, a cover letter you review, and answers you confirm. Nothing here submits an application."
      />
      <ReadinessPanel
        readiness={readiness}
        error={readinessError}
        loading={readinessLoading}
        onRefresh={refresh}
      />
      <TailoredCvSection jobId={jobId} applicationId={applicationId} onChanged={refresh} />
      <CoverLetterSection jobId={jobId} applicationId={applicationId} onChanged={refresh} />
      <ApplicationQASection jobId={jobId} applicationId={applicationId} onChanged={refresh} />
    </div>
  );
};

/* ------------------------------------------------------------------ */
/* Readiness                                                           */
/* ------------------------------------------------------------------ */

const OVERALL_TONE: Record<PrepReadiness['overall'], 'slate' | 'red' | 'amber' | 'emerald'> = {
  NOT_STARTED: 'slate',
  BLOCKED: 'red',
  IN_PROGRESS: 'amber',
  READY_FOR_REVIEW: 'emerald',
};

export const ReadinessPanel: React.FC<{
  readiness: PrepReadiness | null;
  error: string | null;
  loading: boolean;
  onRefresh: () => void;
}> = ({ readiness, error, loading, onRefresh }) => (
  <SectionCard
    title="Preparation readiness"
    hint="Computed from your stored documents and answers"
    actions={<SecondaryButton onClick={onRefresh} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</SecondaryButton>}
    bodyClassName="space-y-3"
  >
    {loading && !readiness && !error ? (
      <Loading>Checking your documents…</Loading>
    ) : error ? (
      <Alert tone="error">
        Preparation status is unavailable: {error}. This is a loading failure, not an empty preparation.
      </Alert>
    ) : readiness ? (
      <>
        <div className="flex flex-wrap items-center gap-2">
          <StatusPill tone={OVERALL_TONE[readiness.overall]}>{readiness.overallLabel}</StatusPill>
          <span className="text-xs text-ink-muted">
            CV: {readiness.cv.state.replace(/_/g, ' ').toLowerCase()} · Cover letter: {readiness.coverLetter.state.replace(/_/g, ' ').toLowerCase()}
            {' '}(requirement {readiness.coverLetter.requirement.toLowerCase()}) · Answers: {readiness.answers.confirmed}/{readiness.answers.total} confirmed
          </span>
        </div>
        {readiness.applicationId ? (
          <p className="text-xs text-ink-muted">
            Linked to your application (status {readiness.applicationStatus}). That status alone does not mean documents are prepared.
          </p>
        ) : (
          <p className="text-xs text-ink-muted">You have no application for this job yet; documents are prepared for the job and attach when generated for an application.</p>
        )}
        <ItemList title="Blockers" tone="red" items={readiness.blockers} />
        <ItemList title="Remaining actions" tone="amber" items={readiness.actions} />
        {readiness.notChecked.length > 0 && (
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">Not checked</p>
            <ul className="mt-1 space-y-0.5 text-xs text-ink-muted">
              {readiness.notChecked.map((text) => <li key={text}>• {text}</li>)}
            </ul>
          </div>
        )}
      </>
    ) : null}
  </SectionCard>
);

const ItemList: React.FC<{ title: string; tone: 'red' | 'amber'; items: PrepItem[] }> = ({ title, tone, items }) =>
  items.length === 0 ? null : (
    <div>
      <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">{title}</p>
      <ul className={`mt-1 space-y-0.5 text-xs ${tone === 'red' ? 'text-red-700' : 'text-amber-800'}`}>
        {items.map((item, i) => <li key={`${item.code}-${i}`}>• {item.message}</li>)}
      </ul>
    </div>
  );

/* ------------------------------------------------------------------ */
/* Shared pieces                                                       */
/* ------------------------------------------------------------------ */

export const FindingsList: React.FC<{ validation: DocumentValidation | null | undefined; legacyNote?: string }> = ({
  validation,
  legacyNote,
}) => {
  if (!validation || !validation.validator_version) {
    return <Alert tone="info">{legacyNote || 'This document has not been checked by the current validation.'}</Alert>;
  }
  const blockers = validation.findings.filter((f) => f.severity === 'BLOCKER');
  const warnings = validation.findings.filter((f) => f.severity === 'WARNING');
  return (
    <div className="space-y-2">
      {blockers.length === 0 ? (
        <p className="text-xs font-medium text-emerald-700">
          No unsupported claims of the checked kinds were found. {validation.scope ? '(' + validation.scope + ')' : ''}
        </p>
      ) : (
        <FindingGroup title={`${blockers.length} blocking finding(s) — must be resolved before approval`} findings={blockers} tone="text-red-700" />
      )}
      {warnings.length > 0 && (
        <FindingGroup title={`${warnings.length} warning(s) for you to check (heuristic, may be false positives)`} findings={warnings} tone="text-amber-800" />
      )}
    </div>
  );
};

const FindingGroup: React.FC<{ title: string; findings: ValidationFinding[]; tone: string }> = ({ title, findings, tone }) => (
  <div>
    <p className={`text-xs font-semibold ${tone}`}>{title}</p>
    <ul className={`mt-1 space-y-0.5 text-xs ${tone}`}>
      {findings.map((f, i) => (
        <li key={`${f.code}-${i}`}>
          • {f.message} <span className="text-ink-faint">[{f.kind === 'DETERMINISTIC' ? 'checked against your records' : 'heuristic'}]</span>
        </li>
      ))}
    </ul>
  </div>
);

function useVerifiedDownload() {
  const [state, setState] = useState<{ busy: boolean; message: string | null; error: string | null }>({
    busy: false, message: null, error: null,
  });
  const download = async (endpoint: string, fallbackName: string) => {
    setState({ busy: true, message: null, error: null });
    try {
      const file = await apiDownload(endpoint, fallbackName);
      saveDownload(file);
      setState({ busy: false, message: `Downloaded ${file.filename}. Checksum verified (SHA-256 ${file.sha256.slice(0, 12)}…).`, error: null });
    } catch (err: unknown) {
      setState({ busy: false, message: null, error: err instanceof Error ? err.message : 'Download failed' });
    }
  };
  return { ...state, download };
}

/* ------------------------------------------------------------------ */
/* Tailored CV                                                         */
/* ------------------------------------------------------------------ */

export const TailoredCvSection: React.FC<{ jobId: string; applicationId: string | null; onChanged: () => void }> = ({
  jobId, applicationId, onChanged,
}) => {
  const [data, setData] = useState<LatestCvResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [comparison, setComparison] = useState<CvComparison | null>(null);
  const [comparisonError, setComparisonError] = useState<string | null>(null);
  const [showComparison, setShowComparison] = useState(false);
  const file = useVerifiedDownload();

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    const query = applicationId ? `?applicationId=${applicationId}` : '';
    apiFetch<LatestCvResponse>(`/resume-intelligence/job/${jobId}${query}`)
      .then((value) => {
        if (value.cv === null && applicationId) {
          // No CV for the application yet: fall back to one generated for the job.
          return apiFetch<LatestCvResponse>(`/resume-intelligence/job/${jobId}`).then(setData);
        }
        setData(value);
        return undefined;
      })
      .catch((err: unknown) => { setData(null); setLoadError(err instanceof Error ? err.message : 'Could not load the CV'); })
      .finally(() => setLoading(false));
  }, [jobId, applicationId]);

  useEffect(() => { load(); }, [load]);

  const cv = data?.cv ?? null;
  const analysis = cv?.analysis;
  const validation = analysis?.atsReport?.validation as DocumentValidation | undefined;
  const renderer = analysis?.atsReport?.renderer as { pages?: number; substituted_characters?: number } | undefined;
  const blocked = !!validation && !validation.passed;
  const approved = !!cv?.review?.approved && cv.review.contentSha256 === cv.artifact.sha256;

  const generate = async () => {
    setBusy(true); setActionError(null); setNotice(null);
    const previous = analysis?.cvVersionId;
    try {
      const result = await apiFetch<{ cvVersionId: string }>('/resume-intelligence/tailor', {
        method: 'POST', body: JSON.stringify({ jobId, applicationId }),
      });
      setNotice(result.cvVersionId === previous
        ? 'Your profile and the job are unchanged, so the existing CV version was kept.'
        : 'A new CV version was generated. Review it before approving.');
      setComparison(null);
      load(); onChanged();
    } catch (err: unknown) {
      setActionError(err instanceof Error ? err.message : 'CV generation failed');
    } finally { setBusy(false); }
  };

  const review = async (approve: boolean) => {
    if (!analysis) return;
    setBusy(true); setActionError(null); setNotice(null);
    try {
      await apiFetch(`/resume-intelligence/cv/${analysis.cvVersionId}/review`, {
        method: 'PUT', body: JSON.stringify({ approved: approve }),
      });
      setNotice(approve ? 'CV approved.' : 'Approval withdrawn.');
      load(); onChanged();
    } catch (err: unknown) {
      setActionError(err instanceof Error ? err.message : 'Review could not be saved');
    } finally { setBusy(false); }
  };

  const toggleComparison = async () => {
    if (!analysis) return;
    const next = !showComparison;
    setShowComparison(next);
    if (next && !comparison) {
      setComparisonError(null);
      try {
        setComparison(await apiFetch<CvComparison>(`/resume-intelligence/cv/${analysis.cvVersionId}/comparison`));
      } catch (err: unknown) {
        setComparisonError(err instanceof Error ? err.message : 'Comparison could not be loaded');
      }
    }
  };

  return (
    <SectionCard title="Job-specific CV" hint="Built only from your verified profile records — never edits your profile" bodyClassName="space-y-4">
      {actionError && <Alert tone="error">{actionError}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}
      {/* Keep the current CV on screen while it refreshes, so actions are not
          unmounted under the user's pointer when readiness updates. */}
      {loading && !data ? (
        <Loading>Loading your tailored CV…</Loading>
      ) : loadError ? (
        <div className="space-y-2">
          <Alert tone="error">The CV could not be loaded: {loadError}</Alert>
          <SecondaryButton onClick={load}>Retry</SecondaryButton>
        </div>
      ) : !analysis ? (
        <div className="space-y-3">
          <p className="text-sm text-ink-soft">No tailored CV exists for this job yet.</p>
          <PrimaryButton onClick={generate} disabled={busy}>{busy ? 'Generating…' : 'Generate job-specific CV'}</PrimaryButton>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <StatusPill tone={approved ? 'emerald' : blocked || !cv?.artifact.intact ? 'red' : 'amber'}>
              {approved ? 'Approved by you' : blocked ? 'Blocked by validation' : !cv?.artifact.intact ? 'PDF integrity failed' : 'Awaiting your review'}
            </StatusPill>
            <span className="text-xs text-ink-muted">
              Version {analysis.cvVersionId.slice(0, 8)} · profile revision r{analysis.profileRevision}
              {renderer?.pages ? ` · ${renderer.pages} page(s)` : ''} · {analysis.verifiedEvidence.length} evidence links
            </span>
          </div>

          <FindingsList validation={validation} legacyNote="This CV was generated before the current checks and PDF renderer. Regenerate it to validate it and get a readable PDF." />

          {analysis.gaps.length > 0 && (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-xs text-amber-900">
              <span className="font-semibold">Job requirements with no evidence in your profile (not added to the CV): </span>
              {analysis.gaps.join(' · ')}
            </div>
          )}
          <p className="text-xs text-ink-muted">
            Keyword coverage {String(analysis.atsReport?.keyword_coverage ?? '—')}% — a heuristic count of job keywords with evidence in your profile. It is not an ATS score and guarantees nothing.
          </p>

          <div>
            <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">CV content</p>
            <DocumentPreview className="max-h-[32rem]">{analysis.resumeMarkdown}</DocumentPreview>
          </div>

          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3.5">
            <SecondaryButton
              onClick={() => file.download(`/resume-intelligence/cv/${analysis.cvVersionId}/artifact`, `tailored-cv-${analysis.cvVersionId}.pdf`)}
              disabled={file.busy || !cv?.artifact.intact}
            >
              {file.busy ? 'Downloading…' : 'Download PDF'}
            </SecondaryButton>
            {approved ? (
              <SecondaryButton onClick={() => review(false)} disabled={busy}>Withdraw approval</SecondaryButton>
            ) : (
              <PrimaryButton onClick={() => review(true)} disabled={busy || blocked || !validation || !cv?.artifact.intact}>
                Approve this CV
              </PrimaryButton>
            )}
            <SecondaryButton onClick={toggleComparison}>{showComparison ? 'Hide comparison' : 'Compare with your profile'}</SecondaryButton>
            <SecondaryButton onClick={generate} disabled={busy}>{busy ? 'Working…' : 'Regenerate'}</SecondaryButton>
          </div>
          {(blocked || !validation) && !approved && (
            <p className="text-xs text-ink-muted">Approval is unavailable until the CV has no blocking findings under the current checks.</p>
          )}
          {file.error && <Alert tone="error">{file.error}</Alert>}
          {file.message && <p className="text-xs text-emerald-700" role="status">{file.message}</p>}

          {showComparison && (
            comparisonError ? <Alert tone="error">The comparison could not be loaded: {comparisonError}</Alert>
              : !comparison ? <Loading>Comparing with your profile…</Loading>
              : <ComparisonView comparison={comparison} />
          )}
        </>
      )}
    </SectionCard>
  );
};

export const ComparisonView: React.FC<{ comparison: CvComparison }> = ({ comparison }) => (
  <div className="space-y-4 rounded-lg border border-line bg-cream-50/60 p-4" aria-label="Profile versus tailored CV comparison">
    <p className="text-xs text-ink-muted">{comparison.method}</p>
    <Alert tone={comparison.sourceMatchesGeneration ? 'success' : 'info'}>{comparison.sourceNote}</Alert>

    <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
      <LabelList title="Emphasised for this job" items={comparison.skills.emphasisedForJob.map((s) => s.label)} />
      <LabelList title="Other skills retained" items={comparison.skills.retained.map((s) => s.label)} />
      <LabelList title="Skills omitted" items={comparison.skills.omitted.map((s) => s.label)} empty="None" />
    </div>

    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
      <LabelList title="Requirements with evidence" items={comparison.requirements.evidenced.map((r) => `${r.requirement} — ${r.sourceLabel}`)} />
      <LabelList title="Requirements missing from your profile" items={comparison.requirements.missingFromProfile} empty="None" />
    </div>
    <p className="text-[11px] text-ink-faint">{comparison.requirements.note}</p>

    {comparison.sections.map((section) => (
      <div key={section.section}>
        <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">{section.section}</p>
        {section.items.length === 0 ? (
          <p className="text-xs text-ink-muted">No {section.section.toLowerCase()} records in your profile.</p>
        ) : (
          <ul className="mt-1 space-y-0.5 text-xs text-ink-soft">
            {section.items.map((item) => (
              <li key={item.id}>
                <StatusPill tone={item.status === 'RETAINED' ? 'emerald' : item.status === 'SHORTENED' ? 'amber' : 'slate'}>{item.status}</StatusPill>{' '}
                {item.label}
                {item.sourceBullets > 0 ? ` (${item.renderedBullets}/${item.sourceBullets} bullet points)` : ''}
              </li>
            ))}
          </ul>
        )}
      </div>
    ))}

    {comparison.attention.length > 0 && (
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">Needs your attention</p>
        <ul className="mt-1 space-y-0.5 text-xs text-amber-800">
          {comparison.attention.map((a, i) => <li key={i}>• {a.message}</li>)}
        </ul>
      </div>
    )}
  </div>
);

const LabelList: React.FC<{ title: string; items: string[]; empty?: string }> = ({ title, items, empty = '—' }) => (
  <div>
    <p className="text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-faint">{title}</p>
    {items.length === 0 ? <p className="text-xs text-ink-muted">{empty}</p> : (
      <ul className="mt-1 space-y-0.5 text-xs text-ink-soft">{items.map((item) => <li key={item}>• {item}</li>)}</ul>
    )}
  </div>
);

/* ------------------------------------------------------------------ */
/* Cover letter                                                        */
/* ------------------------------------------------------------------ */

export const CoverLetterSection: React.FC<{ jobId: string; applicationId: string | null; onChanged: () => void }> = ({
  jobId, applicationId, onChanged,
}) => {
  const [letters, setLetters] = useState<CoverLetter[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const file = useVerifiedDownload();

  const load = useCallback(() => {
    setLoadError(null);
    apiFetch<CoverLetter[]>(`/cover-letters/job/${jobId}`)
      .then((value) => { setLetters(value); setSelectedId((current) => current && value.some((l) => l.id === current) ? current : value[0]?.id ?? null); })
      .catch((err: unknown) => { setLetters(null); setLoadError(err instanceof Error ? err.message : 'Could not load cover letters'); });
  }, [jobId]);

  useEffect(() => { load(); }, [load]);

  const letter = letters?.find((l) => l.id === selectedId) ?? null;
  const validation = letter?.claimsValidation as DocumentValidation | undefined;

  const run = async (action: () => Promise<unknown>, success: string) => {
    setBusy(true); setActionError(null); setNotice(null);
    try {
      await action();
      setNotice(success);
      load(); onChanged();
    } catch (err: unknown) {
      setActionError(err instanceof Error ? err.message : 'The request failed');
    } finally { setBusy(false); }
  };

  const generate = () => run(async () => {
    const result = await apiFetch<{ coverLetter: CoverLetter }>('/cover-letters/generate', {
      method: 'POST', body: JSON.stringify({ jobId, applicationId }),
    });
    setSelectedId(result.coverLetter.id);
  }, 'A new cover-letter version was generated. Read it before approving.');

  const approve = (approved: boolean) => letter && run(() => apiFetch(`/cover-letters/${letter.id}/approval`, {
    method: 'PUT', body: JSON.stringify({ approved }),
  }), approved ? 'Cover letter approved after re-validation.' : 'Approval withdrawn.');

  const submitCorrection = () => letter && run(async () => {
    const result = await apiFetch<{ coverLetter: CoverLetter }>(`/cover-letters/${letter.id}/corrections`, {
      method: 'POST', body: JSON.stringify({ bodyMarkdown: draft }),
    });
    setEditing(false);
    setSelectedId(result.coverLetter.id);
  }, 'Your correction was saved as a new version and re-validated. The previous version is unchanged.');

  return (
    <SectionCard title="Cover letter" hint="Every version is kept; corrections create a new version" bodyClassName="space-y-4">
      {actionError && <Alert tone="error">{actionError}</Alert>}
      {notice && <Alert tone="success">{notice}</Alert>}
      {loadError ? (
        <div className="space-y-2">
          <Alert tone="error">Cover letters could not be loaded: {loadError}</Alert>
          <SecondaryButton onClick={load}>Retry</SecondaryButton>
        </div>
      ) : letters === null ? (
        <Loading>Loading cover letters…</Loading>
      ) : !letter ? (
        <div className="space-y-3">
          <p className="text-sm text-ink-soft">No cover letter has been generated for this job. Whether this employer requires one is not known.</p>
          <PrimaryButton onClick={generate} disabled={busy}>{busy ? 'Generating…' : 'Generate cover letter'}</PrimaryButton>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="letter-version" className="text-xs font-semibold text-ink-soft">Version</label>
            <select
              id="letter-version"
              value={letter.id}
              onChange={(e) => { setSelectedId(e.target.value); setEditing(false); }}
              className="rounded-md border border-line bg-surface px-2 py-1 text-xs"
            >
              {letters.map((l) => (
                <option key={l.id} value={l.id}>
                  v{l.version}{l.origin === 'USER_CORRECTED' ? ' (your correction)' : ''}{l.isApproved ? ' — approved' : ''}
                </option>
              ))}
            </select>
            <StatusPill tone={letter.isApproved ? 'emerald' : validation?.validator_version && !validation.passed ? 'red' : 'amber'}>
              {letter.isApproved ? 'Approved by you' : validation?.validator_version && !validation.passed ? 'Blocked by validation' : 'Awaiting your review'}
            </StatusPill>
            {applicationId && (
              <span className="text-xs text-ink-muted">
                {letter.applicationId === applicationId ? 'Attached to your application' : 'Not attached to your application'}
              </span>
            )}
          </div>

          <FindingsList validation={validation} legacyNote="This letter was checked only by earlier, weaker checks. Approving it re-runs the current checks first." />

          {editing ? (
            <div className="space-y-2">
              <label htmlFor="letter-correction" className="block text-xs font-semibold text-ink-soft">Corrected letter (saved as a new version)</label>
              <textarea
                id="letter-correction"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                rows={14}
                className="w-full rounded-lg border border-line bg-surface p-3 text-sm text-ink"
              />
              <div className="flex gap-2">
                <PrimaryButton onClick={submitCorrection} disabled={busy || !draft.trim()}>Save correction</PrimaryButton>
                <SecondaryButton onClick={() => setEditing(false)}>Cancel</SecondaryButton>
              </div>
            </div>
          ) : (
            <DocumentPreview className="max-h-[32rem]">{letter.bodyMarkdown}</DocumentPreview>
          )}

          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3.5">
            <SecondaryButton onClick={() => file.download(`/cover-letters/${letter.id}/pdf`, `cover-letter-${letter.id}.pdf`)} disabled={file.busy}>
              {file.busy ? 'Downloading…' : 'Download PDF'}
            </SecondaryButton>
            {letter.isApproved ? (
              <SecondaryButton onClick={() => approve(false)} disabled={busy}>Withdraw approval</SecondaryButton>
            ) : (
              <PrimaryButton onClick={() => approve(true)} disabled={busy}>Approve this version</PrimaryButton>
            )}
            {!editing && <SecondaryButton onClick={() => { setDraft(letter.bodyMarkdown); setEditing(true); }}>Correct this letter</SecondaryButton>}
            <SecondaryButton onClick={generate} disabled={busy}>{busy ? 'Working…' : 'Generate a new version'}</SecondaryButton>
          </div>
          {file.error && <Alert tone="error">{file.error}</Alert>}
          {file.message && <p className="text-xs text-emerald-700" role="status">{file.message}</p>}
        </>
      )}
    </SectionCard>
  );
};

/* ------------------------------------------------------------------ */
/* Application Q&A                                                     */
/* ------------------------------------------------------------------ */

export const ApplicationQASection: React.FC<{ jobId: string; applicationId: string | null; onChanged: () => void }> = ({
  jobId, applicationId, onChanged,
}) => {
  const [answers, setAnswers] = useState<ApplicationAnswer[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [questionText, setQuestionText] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoadError(null);
    apiFetch<ApplicationAnswer[]>(`/application-answers/job/${jobId}`)
      .then(setAnswers)
      .catch((err: unknown) => { setAnswers(null); setLoadError(err instanceof Error ? err.message : 'Could not load answers'); });
  }, [jobId]);

  useEffect(() => { load(); }, [load]);

  const handleDraft = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!questionText.trim()) return;
    setDrafting(true); setError(null);
    try {
      await apiFetch('/application-answers/draft', {
        method: 'POST', body: JSON.stringify({ jobId, applicationId, questionText }),
      });
      setQuestionText('');
      load(); onChanged();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Drafting failed');
    } finally { setDrafting(false); }
  };

  const toggleConfirm = async (answer: ApplicationAnswer) => {
    setError(null);
    try {
      await apiFetch(`/application-answers/${answer.id}`, {
        method: 'PUT', body: JSON.stringify({ confirmForAutofill: !answer.humanConfirmed }),
      });
      load(); onChanged();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Confirmation update failed');
    }
  };

  return (
    <SectionCard title="Screening questions" hint="Drafted from your verified profile; you confirm each answer" bodyClassName="space-y-4">
      {error && <Alert tone="error">{error}</Alert>}
      <form onSubmit={handleDraft} className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex-1">
          <label htmlFor="qa-question" className="mb-1 block text-xs font-semibold text-ink-soft">Screening question</label>
          <input
            id="qa-question"
            type="text"
            value={questionText}
            onChange={(e) => setQuestionText(e.target.value)}
            placeholder="e.g. Why do you want to work here?"
            className="w-full rounded-lg border border-line bg-surface px-3.5 py-2 text-sm text-ink"
          />
        </div>
        <PrimaryButton type="submit" disabled={drafting || !questionText.trim()}>
          {drafting ? 'Drafting from verified facts…' : 'Draft grounded answer'}
        </PrimaryButton>
      </form>
      <p className="text-[11px] text-ink-faint">The employer's actual required questions are not known yet, so this list is only the questions you add.</p>

      {loadError ? (
        <div className="space-y-2">
          <Alert tone="error">Answers could not be loaded: {loadError}</Alert>
          <SecondaryButton onClick={load}>Retry</SecondaryButton>
        </div>
      ) : answers === null ? (
        <Loading>Loading answers…</Loading>
      ) : answers.length === 0 ? (
        <p className="text-sm text-ink-muted">No screening answers yet.</p>
      ) : (
        <div className="space-y-3">
          {answers.map((ans) => (
            <div key={ans.id} className="rounded-lg border border-line bg-cream-50/70 p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-semibold text-ink">{ans.questionText}</span>
                <StatusPill tone={ans.status === 'ANSWERED' ? (ans.humanConfirmed ? 'emerald' : 'amber') : 'red'}>
                  {ans.status === 'ANSWERED' ? (ans.humanConfirmed ? 'Confirmed by you' : 'Awaiting your confirmation') : ans.status}
                </StatusPill>
              </div>
              <p className="mt-2 text-sm leading-relaxed text-ink-soft">{ans.answerText}</p>
              {ans.validationNotes?.issues && ans.validationNotes.issues.length > 0 && (
                <ul className="mt-2 space-y-0.5 border-t border-line pt-2 text-xs text-red-700">
                  {ans.validationNotes.issues.map((issue, i) => <li key={i}>• {issue}</li>)}
                </ul>
              )}
              {ans.status === 'ANSWERED' && (
                <button
                  type="button"
                  onClick={() => toggleConfirm(ans)}
                  className="mt-3 rounded-md border border-line bg-surface px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-forest-400"
                >
                  {ans.humanConfirmed ? 'Remove confirmation' : 'I reviewed this answer — confirm it'}
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </SectionCard>
  );
};
