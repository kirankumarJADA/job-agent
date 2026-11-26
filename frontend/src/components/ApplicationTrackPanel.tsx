import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { apiFetch } from '../api/client';
import type {
  ApplicationTrack,
  EmailIngestResult,
  TrackReceipt,
  TrackReportKind,
  TrackSummary,
  TrackTimelineItem,
  TrackTimelinePage,
} from '../types';
import { Alert, Label, Loading, SecondaryButton, Select, StatusPill, TextInput, Textarea } from './ui';
import { SubmissionReceiptModal } from './SubmissionReceiptModal';
import {
  ACTOR_LABELS,
  type TrackFailure,
  TrackFailureNotice,
  TrackReviewCard,
  classifyTrackError,
  describeWhen,
  evidenceTimeSourceLabel,
  eventTone,
  newClientId,
  receiptLinkIssue,
  stageTone,
  submissionLevelLabel,
  submissionLevelTone,
  submissionSummary,
  submissionTone,
  verificationTone,
} from './trackShared';

const TIMELINE_PAGE = 20;

const REPORT_OPTIONS: Array<{ value: TrackReportKind; label: string }> = [
  { value: 'SUBMITTED', label: 'I submitted this application myself' },
  { value: 'ACKNOWLEDGED', label: 'The employer acknowledged my application' },
  { value: 'RECRUITER_CONTACT', label: 'A recruiter or the employer contacted me' },
  { value: 'ASSESSMENT', label: 'I was asked to complete an assessment' },
  { value: 'INTERVIEW', label: 'I was invited to an interview' },
  { value: 'OFFER', label: 'I received an offer' },
  { value: 'REJECTED', label: 'The employer did not progress my application' },
  { value: 'WITHDRAWN', label: 'I withdrew my application' },
];

const RECEIPT_POLICY =
  'Robin treats a submission as confirmed only when an employer acknowledgement is verified: it was retrieved ' +
  'through a connected mailbox, the mail provider authenticated the sender for its own domain, it was delivered ' +
  'to your connected address, it identifies this application by its job link, posting identifier or ' +
  'requisition number, and it explicitly says the application was received. Your own statements and messages ' +
  'you add are recorded but stay unverified.';

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * TRACK for one application (Phase 8.3): the status Robin derives from
 * evidence and why, whether submission and acknowledgement are established,
 * what needs the candidate, what is unknown, the submission evidence with the
 * verification policy's reasons, and the chronological timeline.
 *
 * Every write is a request: the panel re-reads the server's state afterwards
 * and shows only what the server derived. A successful request never renders
 * as a submission — the submission line reads "confirmed" only when the
 * server says a verified employer acknowledgement exists. REAL_SUBMIT stays
 * hard-stopped; nothing here contacts an employer.
 */
export const ApplicationTrackPanel: React.FC<{
  applicationId: string;
  summary?: TrackSummary | null;
  defaultOpen?: boolean;
  /** Bumped by the page when something elsewhere may have changed this application's evidence. */
  refreshKey?: number;
  onChanged?: () => void;
}> = ({ applicationId, summary = null, defaultOpen = false, refreshKey = 0, onChanged }) => {
  const [open, setOpen] = useState(defaultOpen);
  const [track, setTrack] = useState<ApplicationTrack | null>(null);
  const [failure, setFailure] = useState<TrackFailure | null>(null);
  const [loading, setLoading] = useState(false);
  const [timeline, setTimeline] = useState<TrackTimelineItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string>('');
  const [timelineFailure, setTimelineFailure] = useState<TrackFailure | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [receiptModalOpen, setReceiptModalOpen] = useState(false);
  const [version, setVersion] = useState(0);

  const reload = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    setLoading(true);
    setFailure(null);
    setTimelineFailure(null);
    Promise.allSettled([
      apiFetch<ApplicationTrack>(`/applications/${applicationId}/track`),
      apiFetch<TrackTimelinePage>(`/applications/${applicationId}/track/timeline?limit=${TIMELINE_PAGE}`),
    ]).then(([trackResult, timelineResult]) => {
      if (cancelled) return;
      if (trackResult.status === 'fulfilled') {
        setTrack(trackResult.value);
      } else {
        setTrack(null);
        setFailure(classifyTrackError(trackResult.reason, 'Please try again in a moment.'));
      }
      if (timelineResult.status === 'fulfilled') {
        setTimeline(timelineResult.value.items ?? []);
        setNextCursor(timelineResult.value.nextCursor ?? '');
      } else {
        setTimeline([]);
        setNextCursor('');
        setTimelineFailure(classifyTrackError(timelineResult.reason, 'Please try again in a moment.'));
      }
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [open, applicationId, version, refreshKey]);

  const loadMore = async () => {
    if (!nextCursor) return;
    setLoadingMore(true);
    setTimelineFailure(null);
    try {
      const page = await apiFetch<TrackTimelinePage>(
        `/applications/${applicationId}/track/timeline?limit=${TIMELINE_PAGE}&cursor=${encodeURIComponent(nextCursor)}`,
      );
      setTimeline((items) => [...items, ...(page.items ?? [])]);
      setNextCursor(page.nextCursor ?? '');
    } catch (err) {
      setTimelineFailure(classifyTrackError(err, 'Earlier pages are still shown. Please try again.'));
    } finally {
      setLoadingMore(false);
    }
  };

  const afterWrite = (message: string) => {
    setNotice(message);
    reload();
    onChanged?.();
  };

  const headerStatus = track
    ? {
        label: track.label,
        // A conflict between evidence sources is shown in red, and sandbox is never styled green.
        tone: stageTone(track.status, track.basis, {
          conflicting: track.conflicts.length > 0,
          sandbox: Boolean(track.submission.sandbox),
        }),
        submission: track.submission.state,
        sandbox: Boolean(track.submission.sandbox),
      }
    : summary && summary.availability === 'AVAILABLE'
      ? {
          label: summary.label ?? 'Status unknown',
          tone: stageTone(summary.status, summary.basis, {
            conflicting: summary.submission === 'CONFLICTING',
            sandbox: Boolean(summary.sandbox),
          }),
          submission: summary.submission,
          sandbox: Boolean(summary.sandbox),
        }
      : null;

  return (
    <div className="mt-4 rounded-lg border border-line" data-testid="track-panel">
      <div className="flex flex-wrap items-center gap-2 px-3 py-2 text-sm">
        <span className="font-semibold text-ink-soft">Tracking</span>
        {headerStatus ? (
          <>
            <StatusPill tone={headerStatus.tone}>{headerStatus.label}</StatusPill>
            <StatusPill tone={submissionTone(headerStatus.submission, headerStatus.sandbox)}>
              {submissionSummary(headerStatus.submission, headerStatus.sandbox)}
            </StatusPill>
          </>
        ) : (
          <StatusPill tone="slate">Status unavailable</StatusPill>
        )}
        {!track && summary?.needsReview && (
          <span
            className={`text-xs font-medium ${summary.submission === 'CONFLICTING' ? 'text-red-800' : 'text-amber-800'}`}
          >
            Needs your review
          </span>
        )}
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen((value) => !value)}
          className="ml-auto text-xs font-semibold text-forest-700 hover:underline"
        >
          {open ? 'Hide timeline and evidence' : 'Show timeline and evidence'}
        </button>
      </div>

      {open && (
        <div className="space-y-4 border-t border-line px-3 py-3">
          {loading && !track && !failure && <Loading>Loading tracking evidence…</Loading>}
          {failure && <TrackFailureNotice failure={failure} onRetry={reload} />}
          {notice && <Alert tone="info">{notice}</Alert>}

          {track && (
            <>
              <StatusSection track={track} onOpenReceipt={() => setReceiptModalOpen(true)} />
              <AttentionSection track={track} />
              <EvidenceSection
                applicationId={applicationId}
                receipts={track.receipts}
                onChanged={afterWrite}
                onStale={reload}
              />
              {track.reviews.length > 0 && (
                <section aria-label="Employer messages about this application">
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-amber-700">
                    Employer messages that need your review
                  </h3>
                  <div className="mt-2 space-y-2">
                    {track.reviews.map((item) => (
                      <TrackReviewCard key={item.emailId} item={item} onDecided={afterWrite} onStale={reload} />
                    ))}
                  </div>
                </section>
              )}
              <ReportForm applicationId={applicationId} onRecorded={afterWrite} />
              <AddMessageForm applicationId={applicationId} mailboxNote={track.mailbox.note} onAdded={afterWrite} />
            </>
          )}

          {track && (
            <TimelineSection
              items={timeline}
              failure={timelineFailure}
              hasMore={nextCursor !== ''}
              loadingMore={loadingMore}
              onLoadMore={loadMore}
              onRetry={timeline.length > 0 && nextCursor !== '' ? loadMore : reload}
            />
          )}
        </div>
      )}
      <SubmissionReceiptModal
        isOpen={receiptModalOpen}
        onClose={() => setReceiptModalOpen(false)}
        applicationId={applicationId}
        companyName={track?.application?.company}
        jobTitle={track?.application?.jobTitle}
      />
    </div>
  );
};

function stageOrder(stage: string): number {
  switch (stage) {
    case 'APPLIED': return 1;
    case 'ACKNOWLEDGED': return 2;
    case 'IN_CONTACT':
    case 'ASSESSMENT':
    case 'INTERVIEW': return 3;
    case 'OFFER': return 4;
    default: return 0;
  }
}

const STAGES: Array<{ key: string; label: string }> = [
  { key: 'APPLIED', label: 'Applied' },
  { key: 'ACKNOWLEDGED', label: 'Acknowledged' },
  { key: 'INTERVIEW', label: 'Interview' },
  { key: 'OFFER', label: 'Offer' },
];

const StatusSection: React.FC<{ track: ApplicationTrack; onOpenReceipt: () => void }> = ({ track, onOpenReceipt }) => (
  <section aria-label="Current status">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <StatusPill
          tone={stageTone(track.status, track.basis, {
            conflicting: track.conflicts.length > 0,
            sandbox: Boolean(track.submission.sandbox),
          })}
        >
          {track.label}
        </StatusPill>
        {track.since && (
          <span className="text-xs text-ink-muted">
            since {describeWhen(track.since, track.since, track.sincePrecision ?? 'EXACT')}
          </span>
        )}
      </div>
      <SecondaryButton onClick={onOpenReceipt} className="text-xs py-1 px-2.5">
        <svg className="w-3.5 h-3.5 text-forest-700 mr-1 inline-block" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        Submission Receipt
      </SecondaryButton>
    </div>

    {/* Visual Timeline Stepper */}
    <div className="mt-4 border-t border-line pt-3" aria-label="Application progress stepper">
      <div className="flex items-center justify-between">
        {STAGES.map((s, idx) => {
          const isCurrent = track.status === s.key;
          const isCompleted = stageOrder(track.status) >= stageOrder(s.key);
          return (
            <div key={s.key} className="flex items-center flex-1 last:flex-none">
              <div className="flex flex-col items-center">
                <div className={`w-6 h-6 rounded-full flex items-center justify-center text-[11px] font-semibold ${
                  isCurrent ? 'bg-forest-700 text-white ring-2 ring-forest-400' :
                  isCompleted ? 'bg-emerald-600 text-white' : 'bg-slate-200 text-ink-muted'
                }`}>
                  {idx + 1}
                </div>
                <span className={`text-[11px] mt-1 font-medium ${isCurrent ? 'text-forest-800 font-bold' : isCompleted ? 'text-emerald-800' : 'text-ink-muted'}`}>
                  {s.label}
                </span>
              </div>
              {idx < STAGES.length - 1 && (
                <div className={`flex-1 h-0.5 mx-2 ${stageOrder(track.status) > stageOrder(s.key) ? 'bg-emerald-500' : 'bg-slate-200'}`} />
              )}
            </div>
          );
        })}
      </div>
    </div>

    {track.followUp?.due && (
      <div className="mt-3">
        <Alert tone="info">
          <strong>Follow-up reminder:</strong> This application has had no employer response for over 14 days. Consider sending a professional follow-up email.
        </Alert>
      </div>
    )}

    {track.threads && track.threads.length > 0 && (
      <div className="mt-4 border-t border-line pt-3">
        <h4 className="text-xs font-semibold uppercase tracking-wide text-ink-muted mb-2">Recruiter Conversation Threads</h4>
        <div className="space-y-1.5">
          {track.threads.map((t) => (
            <div key={t.threadId} className="flex items-center justify-between p-2 rounded border border-line bg-cream-50/50 text-xs">
              <div>
                <span className="font-semibold text-ink">{t.subject}</span>
                <span className="ml-1.5 text-ink-faint">({t.messageCount} {t.messageCount === 1 ? 'msg' : 'msgs'})</span>
              </div>
              <div className="flex items-center gap-2">
                <StatusPill tone="sky">{t.latestClassification}</StatusPill>
                {t.lastMessageAt && (
                  <span className="text-ink-faint text-[11px]">{new Date(t.lastMessageAt).toLocaleDateString()}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    )}

    <p className="mt-2 text-sm text-ink-body">{track.reason}</p>
    <dl className="mt-3 grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-3">
      <div>
        <dt className="text-xs text-ink-muted">Submission</dt>
        <dd>
          <span className="flex flex-wrap items-center gap-1">
            <StatusPill tone={submissionTone(track.submission.state)}>{track.submission.label}</StatusPill>
            {track.submission.sandbox && (
              <span data-testid="sandbox-tag">
                <StatusPill tone="sky">SANDBOX — not a live employer</StatusPill>
              </span>
            )}
          </span>
          {submissionLevelLabel(track.submission.verificationLevel) && (
            <span className="mt-1 block" data-testid="submission-level">
              <StatusPill tone={submissionLevelTone(track.submission.verificationLevel)}>
                {submissionLevelLabel(track.submission.verificationLevel)}
              </StatusPill>
            </span>
          )}
          <span className="mt-1 block text-xs text-ink-muted">{track.submission.reason}</span>
          <SubmissionRecordNote track={track} />
        </dd>
      </div>
      <div>
        <dt className="text-xs text-ink-muted">Employer acknowledgement</dt>
        <dd>
          <StatusPill tone={track.acknowledgement.state === 'VERIFIED' ? 'emerald' : track.acknowledgement.state === 'UNVERIFIED' ? 'amber' : 'slate'}>
            {track.acknowledgement.label}
          </StatusPill>
        </dd>
      </div>
      <div>
        <dt className="text-xs text-ink-muted">Employer messages</dt>
        <dd className="text-ink-body">
          {track.communications.linkedMessages} linked · {track.communications.awaitingReview} awaiting your review
        </dd>
      </div>
    </dl>
    <p className="mt-2 text-xs text-ink-faint">
      {track.robinSubmission.note} Robin&apos;s internal workflow status: {track.recordedStatusLabel}.
    </p>
  </section>
);

/** Robin's own submission record and its time source; never presented as employer verification. */
const SubmissionRecordNote: React.FC<{ track: ApplicationTrack }> = ({ track }) => {
  const record = track.submissionEvidence;
  if (!record?.recorded) return null;
  return (
    <span className="mt-1 block text-xs text-ink-muted" data-testid="submission-record">
      {record.sandboxVerified
        ? 'Recorded by Robin with a verified sandbox receipt'
        : record.browserObserved
          ? "Robin's browser saw the employer's confirmation page"
          : "Robin's worker reported a submission whose evidence could not be verified"}
      {record.recordedAt ? ` · recorded ${describeWhen(record.recordedAt, record.recordedAt, 'EXACT')} (Robin server time)` : ''}
      {record.confirmationReference ? ` · reference seen: ${record.confirmationReference}` : ''}
      {' · a browser observation is not employer verification'}
    </span>
  );
};

const AttentionSection: React.FC<{ track: ApplicationTrack }> = ({ track }) => (
  <section aria-label="What needs you and what is unknown" className="space-y-3 text-sm">
    {track.pending && (
      <Alert tone="info">
        An employer message suggests <strong>{track.pending.label}</strong> (inferred, not confirmed). Confirm it below to
        update the status.
      </Alert>
    )}
    {track.conflicts.length > 0 && (
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wide text-red-700">Conflicting evidence</h3>
        <ul className="mt-1 list-disc space-y-1 pl-5 text-red-800">
          {track.conflicts.map((conflict) => (
            <li key={`${conflict.code}-${conflict.message}`}>
              {conflict.message}
              {conflict.references && conflict.references.some((r) => !r.startsWith('receipt:')) && (
                <span className="block text-xs text-red-700" data-testid="conflict-references">
                  Kept as recorded: {conflict.references.filter((r) => !r.startsWith('receipt:')).join(' · ')}
                </span>
              )}
            </li>
          ))}
        </ul>
      </div>
    )}
    <div>
      <h3 className="text-xs font-semibold uppercase tracking-wide text-amber-700">Needs your attention</h3>
      {track.attention.length === 0 ? (
        <p className="text-ink-muted">Nothing right now.</p>
      ) : (
        <ul className="mt-1 list-disc space-y-1 pl-5 text-amber-900">
          {track.attention.map((item) => (
            <li key={`${item.code}-${item.emailId ?? ''}-${item.receiptId ?? ''}`}>{item.message}</li>
          ))}
        </ul>
      )}
    </div>
    <div>
      <h3 className="text-xs font-semibold uppercase tracking-wide text-sky-700">Not known</h3>
      {track.unknowns.length === 0 ? (
        <p className="text-ink-muted">Nothing outstanding.</p>
      ) : (
        <ul className="mt-1 list-disc space-y-1 pl-5 text-sky-900">
          {track.unknowns.map((unknown) => (
            <li key={unknown}>{unknown}</li>
          ))}
        </ul>
      )}
    </div>
  </section>
);

const EvidenceSection: React.FC<{
  applicationId: string;
  receipts: TrackReceipt[];
  onChanged: (message: string) => void;
  onStale: () => void;
}> = ({ applicationId, receipts, onChanged, onStale }) => {
  const [busy, setBusy] = useState<string | null>(null);
  const [failure, setFailure] = useState<TrackFailure | null>(null);

  const review = async (receipt: TrackReceipt, decision: 'CONFIRM' | 'REJECT') => {
    setBusy(receipt.id);
    setFailure(null);
    try {
      await apiFetch(`/applications/${applicationId}/track/receipts/${receipt.id}/review`, {
        method: 'POST',
        body: JSON.stringify({ decision, expectedVersion: receipt.reviewVersion }),
      });
      onChanged(
        decision === 'CONFIRM'
          ? 'Recorded: you confirmed this evidence is about this application. Your confirmation does not make it verified.'
          : 'Recorded: you rejected this evidence for this application.',
      );
    } catch (err) {
      const classified = classifyTrackError(err, 'Your review could not be recorded. Nothing was changed.');
      setFailure(classified);
      if (classified.kind === 'conflict') onStale();
    } finally {
      setBusy(null);
    }
  };

  return (
    <section aria-label="Submission evidence">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-soft">Submission evidence</h3>
      <p className="mt-1 text-xs text-ink-muted">{RECEIPT_POLICY}</p>
      {failure && (
        <div className="mt-2">
          <TrackFailureNotice failure={failure} mode="write" />
        </div>
      )}
      {receipts.length === 0 ? (
        <p className="mt-2 text-sm text-ink-muted">No submission evidence has been recorded for this application.</p>
      ) : (
        <ul className="mt-2 space-y-2">
          {receipts.map((receipt) => (
            <li key={receipt.id} className="rounded-lg border border-line px-3 py-2 text-sm" data-testid="track-receipt">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium text-ink">{receipt.evidenceLabel}</span>
                {receiptLinkIssue(receipt) ? (
                  <StatusPill tone="red">Conflicts with the submission</StatusPill>
                ) : (
                  <StatusPill tone={verificationTone(receipt.verification)}>{receipt.verificationLabel}</StatusPill>
                )}
                <span className="text-xs text-ink-muted">{receipt.channelLabel}</span>
              </div>
              {receiptLinkIssue(receipt) && (
                <p className="mt-1 text-xs font-medium text-red-800" data-testid="receipt-link-issue">
                  {receiptLinkIssue(receipt)}. Confirm it if it is about this application, or reject it.
                </p>
              )}
              {receipt.summary && <p className="mt-1 text-ink-body">{receipt.summary}</p>}
              <p className="mt-1 text-xs text-ink-muted">
                Evidence dated {describeWhen(receipt.evidenceAt, receipt.evidenceAt, receipt.evidenceAtPrecision)}
                {evidenceTimeSourceLabel(receipt.evidenceAtSource) ? ` (${evidenceTimeSourceLabel(receipt.evidenceAtSource)})` : ''}
                {receipt.externalReference ? ` · reference quoted: ${receipt.externalReference}` : ''}
              </p>
              {receipt.reasons.length > 0 && (
                <div className="mt-1">
                  <p className="text-xs font-semibold text-ink-soft">Why: </p>
                  <ul className="list-disc space-y-0.5 pl-5 text-xs text-ink-muted">
                    {receipt.reasons.map((reason) => (
                      <li key={reason.code}>{reason.message}</li>
                    ))}
                  </ul>
                </div>
              )}
              {receipt.candidateReview && (
                <p className="mt-1 text-xs text-ink-body">
                  {receipt.candidateReview === 'CONFIRMED'
                    ? 'You confirmed this evidence is about this application (your confirmation does not make it verified).'
                    : 'You rejected this evidence for this application.'}
                </p>
              )}
              {receipt.evidenceType === 'EMPLOYER_ACKNOWLEDGEMENT_EMAIL' && (
                <div className="mt-2 flex flex-wrap gap-2">
                  {receipt.candidateReview !== 'CONFIRMED' && (
                    <SecondaryButton type="button" disabled={busy !== null} onClick={() => review(receipt, 'CONFIRM')}>
                      {busy === receipt.id ? 'Recording…' : 'It is about this application'}
                    </SecondaryButton>
                  )}
                  {receipt.candidateReview !== 'REJECTED' && (
                    <SecondaryButton type="button" disabled={busy !== null} onClick={() => review(receipt, 'REJECT')}>
                      {busy === receipt.id ? 'Recording…' : 'Not about this application'}
                    </SecondaryButton>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
};

const ReportForm: React.FC<{ applicationId: string; onRecorded: (message: string) => void }> = ({ applicationId, onRecorded }) => {
  const [kind, setKind] = useState<TrackReportKind>('SUBMITTED');
  const [occurredOn, setOccurredOn] = useState('');
  const [note, setNote] = useState('');
  const [draftId, setDraftId] = useState(() => newClientId('report'));
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<TrackFailure | null>(null);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setFailure(null);
    try {
      const result = await apiFetch<ApplicationTrack>(`/applications/${applicationId}/track/reports`, {
        method: 'POST',
        body: JSON.stringify({
          kind,
          occurredOn: occurredOn || null,
          note: note.trim() || null,
          // The same draft retried after a failure is recorded once.
          clientReportId: draftId,
        }),
      });
      setDraftId(newClientId('report'));
      setNote('');
      setOccurredOn('');
      onRecorded(
        result?.replayed
          ? 'This update was already recorded; nothing was duplicated.'
          : 'Recorded as reported by you. The employer has not verified it.',
      );
    } catch (err) {
      setFailure(classifyTrackError(err, 'Your update could not be recorded. Nothing was changed.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} aria-label="Record an update" className="rounded-lg border border-line px-3 py-3 text-sm">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-soft">Record an update yourself</h3>
      <p className="mt-1 text-xs text-ink-muted">
        Updates you record are labelled as reported by you. Robin cannot verify them with the employer.
      </p>
      <div className="mt-2 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="sm:col-span-2">
          <Label htmlFor={`track-report-kind-${applicationId}`}>What happened</Label>
          <Select
            id={`track-report-kind-${applicationId}`}
            value={kind}
            onChange={(event) => setKind(event.target.value as TrackReportKind)}
          >
            {REPORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
        </div>
        <div>
          <Label htmlFor={`track-report-date-${applicationId}`}>Date (optional)</Label>
          <TextInput
            id={`track-report-date-${applicationId}`}
            type="date"
            max={todayIso()}
            value={occurredOn}
            onChange={(event) => setOccurredOn(event.target.value)}
          />
        </div>
      </div>
      <div className="mt-2">
        <Label htmlFor={`track-report-note-${applicationId}`}>Note (optional)</Label>
        <Textarea
          id={`track-report-note-${applicationId}`}
          maxLength={500}
          rows={2}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </div>
      {failure && (
        <div className="mt-2">
          <TrackFailureNotice failure={failure} mode="write" />
        </div>
      )}
      <SecondaryButton type="submit" disabled={busy} className="mt-2">
        {busy ? 'Recording…' : 'Record this update'}
      </SecondaryButton>
    </form>
  );
};

const AddMessageForm: React.FC<{ applicationId: string; mailboxNote: string; onAdded: (message: string) => void }> = ({
  applicationId,
  mailboxNote,
  onAdded,
}) => {
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState('');
  const [subject, setSubject] = useState('');
  const [receivedAt, setReceivedAt] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<TrackFailure | null>(null);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!subject.trim() && !body.trim()) {
      setFailure({ kind: 'invalid', message: 'Add the message subject or its text.' });
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      const received = receivedAt ? new Date(receivedAt) : null;
      const result = await apiFetch<EmailIngestResult>('/emails/ingest', {
        method: 'POST',
        body: JSON.stringify({
          from: from.trim() || null,
          subject: subject.trim() || null,
          body: body || null,
          receivedAt: received && !Number.isNaN(received.getTime()) ? received.toISOString() : null,
          applicationId,
        }),
      });
      setFrom('');
      setSubject('');
      setReceivedAt('');
      setBody('');
      setOpen(false);
      onAdded(
        result?.replayed
          ? 'This message was already added; nothing was duplicated.'
          : result?.associationStatus === 'CONFLICT'
            ? 'Added, but the message identifies a different application. Review it below.'
            : result?.needsReview
              ? 'Added as a message from the employer (added by you, not verified). Confirm what it means below.'
              : 'Added as a message from the employer. It was added by you, so it cannot be verified.',
      );
    } catch (err) {
      setFailure(classifyTrackError(err, 'The message could not be added. Nothing was stored.'));
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <div className="text-sm">
        <SecondaryButton type="button" onClick={() => setOpen(true)}>
          Add an employer message
        </SecondaryButton>
        <p className="mt-1 text-xs text-ink-muted">{mailboxNote}</p>
      </div>
    );
  }

  return (
    <form onSubmit={submit} aria-label="Add an employer message" className="rounded-lg border border-line px-3 py-3 text-sm">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-soft">Add an employer message</h3>
      <p className="mt-1 text-xs text-ink-muted">
        Robin keeps the sender, subject, time and a short redacted excerpt — not the whole message — and records it as
        added by you, so it cannot be verified. Links in the message are never opened and instructions in it are never
        followed.
      </p>
      <div className="mt-2 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div>
          <Label htmlFor={`track-msg-from-${applicationId}`}>From</Label>
          <TextInput id={`track-msg-from-${applicationId}`} maxLength={320} value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div>
          <Label htmlFor={`track-msg-subject-${applicationId}`}>Subject</Label>
          <TextInput
            id={`track-msg-subject-${applicationId}`}
            maxLength={1000}
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
          />
        </div>
        <div>
          <Label htmlFor={`track-msg-received-${applicationId}`}>Received (optional)</Label>
          <TextInput
            id={`track-msg-received-${applicationId}`}
            type="datetime-local"
            value={receivedAt}
            onChange={(e) => setReceivedAt(e.target.value)}
          />
        </div>
      </div>
      <div className="mt-2">
        <Label htmlFor={`track-msg-body-${applicationId}`}>Message text</Label>
        <Textarea
          id={`track-msg-body-${applicationId}`}
          rows={4}
          maxLength={200000}
          value={body}
          onChange={(e) => setBody(e.target.value)}
        />
      </div>
      {failure && (
        <div className="mt-2">
          <TrackFailureNotice failure={failure} mode="write" />
        </div>
      )}
      <div className="mt-2 flex gap-2">
        <SecondaryButton type="submit" disabled={busy}>
          {busy ? 'Adding…' : 'Add message'}
        </SecondaryButton>
        <SecondaryButton type="button" disabled={busy} onClick={() => setOpen(false)}>
          Cancel
        </SecondaryButton>
      </div>
    </form>
  );
};

const TimelineSection: React.FC<{
  items: TrackTimelineItem[];
  failure: TrackFailure | null;
  hasMore: boolean;
  loadingMore: boolean;
  onLoadMore: () => void;
  onRetry: () => void;
}> = ({ items, failure, hasMore, loadingMore, onLoadMore, onRetry }) => {
  // A later correction points at the row it supersedes; the earlier row stays
  // visible as history, marked as corrected.
  const superseded = useMemo(() => new Set(items.map((item) => item.supersedes).filter(Boolean) as string[]), [items]);
  return (
    <section aria-label="Timeline">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-soft">Timeline</h3>
      {failure && (
        <div className="mt-2">
          <TrackFailureNotice failure={failure} what="The timeline" onRetry={onRetry} />
        </div>
      )}
      {!failure && items.length === 0 && <p className="mt-2 text-sm text-ink-muted">No timeline events recorded yet.</p>}
      {items.length > 0 && (
        <ol className="mt-2 space-y-2 border-l border-line pl-4">
          {items.map((item) => (
            <li key={item.id} className="text-sm" data-testid="track-event">
              <div className="flex flex-wrap items-center gap-2 text-xs text-ink-muted">
                <time>{describeWhen(item.occurredAt, item.occurredOn, item.occurredAtPrecision, item.recordedAt)}</time>
                <span>· {ACTOR_LABELS[item.actor] ?? 'Robin'}</span>
                <StatusPill tone={eventTone(item.classification, item.verification)}>{item.label}</StatusPill>
                {superseded.has(item.id) && <span className="italic">Corrected later — kept as history</span>}
              </div>
              <p className="mt-0.5 text-ink-body">{item.summary}</p>
            </li>
          ))}
        </ol>
      )}
      {hasMore && (
        <SecondaryButton type="button" className="mt-2" disabled={loadingMore} onClick={onLoadMore}>
          {loadingMore ? 'Loading…' : 'Load more events'}
        </SecondaryButton>
      )}
    </section>
  );
};
