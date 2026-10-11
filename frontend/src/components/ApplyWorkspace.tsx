import React, { useCallback, useEffect, useState } from 'react';
import { ApiError, apiFetch } from '../api/client';
import type { ApplyReadiness, ApplyReadinessItem, ApplyQuestionItem } from '../types';
import { Alert, Loading, PrimaryButton, SecondaryButton, SectionCard, StatusPill } from './ui';

/**
 * APPLY review workspace for one application (Phase 8.2).
 *
 * Shows exactly what the server-side readiness gate sees: the selected
 * document versions with their review and integrity status, the employer's
 * captured questions with evidence-backed required-ness and answer provenance,
 * the duplicate-application check, the approval-rule decision, and a preview of
 * the exact package versions. Hard blockers, warnings and UNKNOWN information
 * are kept visually distinct — an unknown requirement is never rendered as
 * satisfied or optional.
 *
 * Approval and package creation are requests, not claims: the server re-runs
 * the readiness gate over current records and the response is reported exactly
 * as returned. Nothing here submits an application — REAL_SUBMIT stays
 * hard-stopped.
 */
export const ApplyWorkspace: React.FC<{
  applicationId: string;
  planId?: string | null;
  planStatus?: string | null;
  onChanged?: () => void;
}> = ({ applicationId, planId = null, planStatus = null, onChanged }) => {
  const [readiness, setReadiness] = useState<ApplyReadiness | null>(null);
  const [loading, setLoading] = useState(true);
  const [unavailable, setUnavailable] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<{ error: string; blockers: ApplyReadinessItem[] } | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setUnavailable(null);
    apiFetch<ApplyReadiness>(`/apply/applications/${applicationId}/readiness`)
      .then((value) => {
        if (!cancelled) setReadiness(value);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setReadiness(null);
        if (err instanceof ApiError && err.status === 503) {
          setUnavailable(refusalOf(err).error);
        } else {
          setError(err instanceof Error ? err.message : 'The APPLY state could not be loaded.');
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [applicationId, version]);

  const preparePackage = async () => {
    setBusy('package');
    setRefusal(null);
    setSuccess(null);
    try {
      const result = await apiFetch<{ planId: string; status: string }>(
        `/apply/applications/${applicationId}/package`,
        { method: 'POST' },
      );
      setSuccess(
        `Execution package ${result.planId} is prepared (${result.status}). ` +
          'It carries the exact document versions shown below. Nothing has been submitted.',
      );
      onChanged?.();
      refresh();
    } catch (err) {
      setRefusal(refusalOf(err));
    } finally {
      setBusy(null);
    }
  };

  const approve = async () => {
    if (!planId) return;
    setBusy('approve');
    setRefusal(null);
    setSuccess(null);
    try {
      const result = await apiFetch<{ status: string; submissionEnabled: boolean }>(
        `/automation/plans/${planId}/approve-submit`,
        { method: 'POST' },
      );
      // Reported only after the server has confirmed it.
      setSuccess(
        `Server confirmed the approval: ${result.status}. ` +
          'Actual submission stays disabled (REAL_SUBMIT is hard-stopped).',
      );
      onChanged?.();
      refresh();
    } catch (err) {
      setRefusal(refusalOf(err));
    } finally {
      setBusy(null);
    }
  };

  const confirmAnswer = async (answerId: string) => {
    setBusy(`answer:${answerId}`);
    setRefusal(null);
    setSuccess(null);
    try {
      await apiFetch(`/application-answers/${answerId}`, {
        method: 'PUT',
        body: JSON.stringify({ confirmForAutofill: true }),
      });
      setSuccess('Answer confirmed. The state below is re-derived from your stored records.');
      onChanged?.();
      refresh();
    } catch (err) {
      setRefusal(refusalOf(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="mt-4 space-y-4" data-testid="apply-workspace">
      {loading && <Loading>Deriving the APPLY state from your records…</Loading>}

      {unavailable && (
        <Alert tone="error">
          <strong>Preparation state unavailable.</strong> {unavailable} Nothing has been approved.
        </Alert>
      )}

      {error && !unavailable && (
        <Alert tone="error">
          <strong>Could not load the APPLY state.</strong> {error}
        </Alert>
      )}

      {refusal && (
        <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-300" role="alert">
          <strong>The server refused this action:</strong> {refusal.error}
          {refusal.blockers.length > 0 && (
            <ul className="mt-2 list-disc space-y-1 pl-5">
              {refusal.blockers.map((blocker) => (
                <li key={`${blocker.area}-${blocker.code}`}>
                  [{blocker.area}] {blocker.message}
                </li>
              ))}
            </ul>
          )}
          <div className="mt-1 text-xs text-red-400">Nothing was approved or changed by this request.</div>
        </div>
      )}

      {success && (
        <Alert tone="success">
          <strong>Confirmed by the server.</strong> {success}
        </Alert>
      )}

      {readiness && !loading && (
        <>
          <SectionCard
            title="Application under review"
            hint={`${readiness.application.jobTitle} · ${readiness.application.company}`}
            actions={
              readiness.packageReady ? (
                <StatusPill tone="emerald">Ready for an execution package</StatusPill>
              ) : (
                <StatusPill tone="red">Blocked · {readiness.blockers.length} blocker(s)</StatusPill>
              )
            }
          >
            <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-ink-muted">Application</dt>
                <dd className="font-mono text-xs text-ink-body">{readiness.application.applicationId}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Application URL</dt>
                <dd className="break-all text-ink-body">{readiness.application.applicationUrl || '—'}</dd>
              </div>
            </dl>
            <p className="mt-3 text-xs text-ink-faint">{readiness.note}</p>
          </SectionCard>

          <SectionCard title="Selected documents" hint="Exact immutable versions — never substituted">
            <RequirementBadge requirement={readiness.documents.coverLetterRequirement} />
            <div className="mt-3 space-y-3 text-sm">
              <DocumentRow
                label="CV"
                present={readiness.documents.cv != null}
                detail={
                  readiness.documents.cv
                    ? `version ${readiness.documents.cv.versionId} · reviewed ${readiness.documents.cv.reviewedAt || 'n/a'} · pdf sha ${shorten(readiness.documents.cv.pdfSha256)} (${readiness.documents.cv.byteSize} bytes)`
                    : 'No reviewed CV is bound to this application. A package cannot be built until one is.'
                }
              />
              <DocumentRow
                label="Cover letter"
                present={readiness.documents.coverLetter != null}
                detail={
                  readiness.documents.coverLetter
                    ? `version ${readiness.documents.coverLetter.version} (${readiness.documents.coverLetter.versionId}) · ${readiness.documents.coverLetter.origin || 'unknown origin'} · body sha ${shorten(readiness.documents.coverLetter.bodySha256)}`
                    : 'No approved cover letter is bound to this application.'
                }
              />
            </div>
          </SectionCard>

          <SectionCard
            title="Employer questions"
            hint={
              readiness.questions.formCaptured
                ? `captured from ${readiness.questions.source ?? 'the employer form'}`
                : 'form not captured'
            }
          >
            {!readiness.questions.formCaptured && (
              <p className="text-sm text-ink-muted">
                The employer&apos;s application form has not been inspected for this job, so required
                questions and the cover-letter requirement are <strong>unknown</strong> — not assumed
                optional, and not assumed satisfied.
              </p>
            )}
            {readiness.questions.formCaptured && readiness.questions.items.length === 0 && (
              <p className="text-sm text-ink-muted">The captured form contains no screening questions.</p>
            )}
            <ul className="mt-2 space-y-3">
              {readiness.questions.items.map((question) => (
                <QuestionRow
                  key={question.questionKey}
                  question={question}
                  busy={busy}
                  onConfirm={confirmAnswer}
                />
              ))}
            </ul>
          </SectionCard>

          <SectionCard title="Duplicate-application check">
            {readiness.duplicates.status === 'NONE' ? (
              <p className="text-sm text-ink-muted">
                No earlier application of yours matches this role across job boards.
              </p>
            ) : (
              <div className="rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-sm text-amber-200">
                <strong>Duplicate detected.</strong> You already have an application for this role:
                <ul className="mt-1 list-disc pl-5">
                  {readiness.duplicates.items.map((item) => (
                    <li key={item.applicationId}>
                      application {item.applicationId} — {item.matchReason}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </SectionCard>

          <SectionCard title="Approval rule decision">
            <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-ink-muted">Application mode</dt>
                <dd className="text-ink-body">{readiness.decision.applicationMode}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Auto-approval rule</dt>
                <dd className="text-ink-body">{readiness.decision.ruleAvailability}</dd>
              </div>
            </dl>
          </SectionCard>

          <SectionCard title="Blockers, warnings and unknowns" hint="Kept distinct on purpose">
            <div className="space-y-3 text-sm">
              <div>
                <h3 className="text-xs font-semibold uppercase tracking-wide text-red-400">
                  Hard blockers ({readiness.blockers.length})
                </h3>
                {readiness.blockers.length === 0 ? (
                  <p className="text-ink-muted">None.</p>
                ) : (
                  <ul className="mt-1 list-disc space-y-1 pl-5 text-red-300">
                    {readiness.blockers.map((item) => (
                      <li key={`${item.area}-${item.code}`}>
                        [{item.area}] {item.message}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h3 className="text-xs font-semibold uppercase tracking-wide text-amber-400">
                  Warnings ({readiness.warnings.length})
                </h3>
                {readiness.warnings.length === 0 ? (
                  <p className="text-ink-muted">None.</p>
                ) : (
                  <ul className="mt-1 list-disc space-y-1 pl-5 text-amber-200">
                    {readiness.warnings.map((item) => (
                      <li key={`${item.area}-${item.code}`}>
                        [{item.area}] {item.message}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h3 className="text-xs font-semibold uppercase tracking-wide text-sky-400">
                  Unknown — not checked or not supplied ({readiness.unknowns.length})
                </h3>
                {readiness.unknowns.length === 0 ? (
                  <p className="text-ink-muted">None.</p>
                ) : (
                  <ul className="mt-1 list-disc space-y-1 pl-5 text-sky-200">
                    {readiness.unknowns.map((message) => (
                      <li key={message}>{message}</li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </SectionCard>

          <SectionCard title="Package preview" hint="What an execution package would carry">
            <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-ink-muted">CV version</dt>
                <dd className="font-mono text-xs text-ink-body">
                  {readiness.packagePreview.documents.cv ?? '—'}
                </dd>
              </div>
              <div>
                <dt className="text-ink-muted">Cover-letter version</dt>
                <dd className="font-mono text-xs text-ink-body">
                  {readiness.packagePreview.documents.coverLetter ?? '—'}
                </dd>
              </div>
              <div>
                <dt className="text-ink-muted">Cover-letter requirement</dt>
                <dd className="text-ink-body">{readiness.packagePreview.coverLetterRequirement}</dd>
              </div>
            </dl>
            <p className="mt-3 text-xs text-ink-faint">{readiness.packagePreview.note}</p>
          </SectionCard>

          <SectionCard title="Actions" hint="Server-side checks run again on every action">
            <div className="flex flex-wrap items-center gap-3">
              <PrimaryButton
                type="button"
                onClick={preparePackage}
                disabled={busy !== null || !readiness.packageReady}
                title={readiness.packageReady ? undefined : 'Resolve the blockers above first'}
              >
                {busy === 'package' ? 'Preparing…' : 'Prepare execution package'}
              </PrimaryButton>
              {planId && planStatus === 'AWAITING_APPROVAL' && (
                <SecondaryButton type="button" onClick={approve} disabled={busy !== null}>
                  {busy === 'approve' ? 'Approving…' : 'Approve for submission'}
                </SecondaryButton>
              )}
              {planId && planStatus !== 'AWAITING_APPROVAL' && (
                <span className="text-xs text-ink-muted">
                  Automation plan {planId} is {planStatus ?? 'not awaiting approval'} — approval is only
                  possible once the plan awaits human review.
                </span>
              )}
            </div>
            <p className="mt-3 text-xs text-ink-faint">
              Approval records intent only. No endpoint submits an application: REAL_SUBMIT remains
              hard-stopped.
            </p>
          </SectionCard>
        </>
      )}
    </div>
  );
};

/* ------------------------------------------------------------------ */
/* Small presentational helpers                                        */
/* ------------------------------------------------------------------ */

const RequirementBadge: React.FC<{ requirement: 'REQUIRED' | 'OPTIONAL' | 'UNKNOWN' }> = ({ requirement }) => {
  if (requirement === 'UNKNOWN') {
    return (
      <StatusPill tone="sky">
        Cover-letter requirement: UNKNOWN — the employer&apos;s form has not supplied this
      </StatusPill>
    );
  }
  return (
    <StatusPill tone={requirement === 'REQUIRED' ? 'amber' : 'slate'}>
      Cover-letter requirement: {requirement}
    </StatusPill>
  );
};

const DocumentRow: React.FC<{ label: string; present: boolean; detail: string }> = ({
  label,
  present,
  detail,
}) => (
  <div className="rounded-lg border border-line bg-surface-sunken px-3 py-2">
    <div className="flex items-center gap-2">
      <span className="font-medium text-ink">{label}</span>
      <StatusPill tone={present ? 'emerald' : 'red'}>{present ? 'selected' : 'missing'}</StatusPill>
    </div>
    <p className="mt-1 break-all text-xs text-ink-muted">{detail}</p>
  </div>
);

const QuestionRow: React.FC<{
  question: ApplyQuestionItem;
  busy: string | null;
  onConfirm: (answerId: string) => void;
}> = ({ question, busy, onConfirm }) => (
  <li className="rounded-lg border border-line bg-surface-sunken px-3 py-2">
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <span className="font-medium text-ink">{question.questionText || question.questionKey}</span>
      <StatusPill tone={question.requiredState === 'REQUIRED' ? 'amber' : question.requiredState === 'UNKNOWN' ? 'sky' : 'slate'}>
        {question.requiredState === 'UNKNOWN' ? 'requirement UNKNOWN' : question.requiredState}
      </StatusPill>
      <StatusPill tone={question.answerState === 'CONFIRMED' ? 'emerald' : 'red'}>
        answer {question.answerState}
      </StatusPill>
      {question.answerOrigin && (
        <span className="text-xs text-ink-faint">
          {question.answerOrigin === 'CANDIDATE_CONFIRMED'
            ? 'confirmed by you'
            : `origin: ${question.answerOrigin}`}
        </span>
      )}
      {question.answerState !== 'CONFIRMED' && question.answerId && (
        <button
          type="button"
          className="ml-auto rounded-md border border-emerald-500/40 px-3 py-1 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/10 disabled:opacity-50"
          disabled={busy !== null}
          onClick={() => onConfirm(question.answerId!)}
        >
          {busy === `answer:${question.answerId}` ? 'Confirming…' : 'Confirm this answer'}
        </button>
      )}
    </div>
    <p className="mt-1 text-xs text-ink-muted">
      Source: {question.source} · form: {question.formUrl}
    </p>
  </li>
);

function shorten(sha: string | null | undefined): string {
  if (!sha) return '—';
  return sha.length > 16 ? `${sha.slice(0, 12)}…` : sha;
}

/** Parses a server refusal body without ever inventing a friendlier reason. */
function refusalOf(err: unknown): { error: string; blockers: ApplyReadinessItem[] } {
  const message = err instanceof Error ? err.message : 'The request was refused.';
  try {
    const parsed = JSON.parse(message) as { error?: string; message?: string; blockers?: ApplyReadinessItem[] };
    if (parsed && typeof parsed === 'object') {
      return {
        error: typeof parsed.error === 'string' ? parsed.error : typeof parsed.message === 'string' ? parsed.message : message,
        blockers: Array.isArray(parsed.blockers) ? parsed.blockers : [],
      };
    }
  } catch {
    /* not a JSON body: show the raw message verbatim */
  }
  return { error: message, blockers: [] };
}
