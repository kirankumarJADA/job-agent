import React, { useState } from 'react';
import { apiFetch } from '../api/client';
import type {
  TrackApplicationRef,
  TrackBasis,
  TrackEventClassification,
  TrackReviewItem,
  TrackStage,
  TrackSubmissionState,
  TrackTimePrecision,
  TrackVerification,
} from '../types';
import { Alert, SecondaryButton, Select, type PillTone } from './ui';

/**
 * Shared pieces of the TRACK (Phase 8.3) UI: error classification, honest
 * time rendering, tone mapping and the employer-message review card used by
 * both the per-application panel and the review queue.
 *
 * Tone rule: green ("emerald") is reserved for evidence that passed the
 * documented verification policy. A candidate report, a candidate
 * confirmation or an inference never renders as a verified success.
 */

export type TrackFailureKind = 'unavailable' | 'denied' | 'signedOut' | 'conflict' | 'invalid' | 'error';

export interface TrackFailure {
  kind: TrackFailureKind;
  message: string;
}

function statusOf(err: unknown): number | null {
  if (typeof err === 'object' && err !== null && typeof (err as { status?: unknown }).status === 'number') {
    return (err as { status: number }).status;
  }
  return null;
}

/** The server's own explanation, when the body carried one; never a stack trace. */
function serverMessage(err: unknown): string | null {
  const raw = err instanceof Error ? err.message : typeof err === 'string' ? err : '';
  if (!raw) return null;
  let text = raw;
  try {
    const parsed = JSON.parse(raw) as { message?: unknown; detail?: unknown; error?: unknown };
    const candidate = parsed.message ?? parsed.detail ?? parsed.error;
    if (typeof candidate === 'string') text = candidate;
  } catch {
    /* not JSON: the message is already the server's detail text */
  }
  if (/exception|\bat [a-z]+\.[a-z]|^HTTP \d+$/i.test(text)) return null;
  return text.length > 300 ? `${text.slice(0, 300)}…` : text;
}

/**
 * Maps a failed request onto the states the TRACK UI distinguishes. A generic
 * failure never shows the raw error (it may carry backend detail); refusals
 * the server explains (400/409/503) show the server's own wording.
 */
export function classifyTrackError(err: unknown, fallback: string): TrackFailure {
  const status = statusOf(err);
  const message = serverMessage(err);
  switch (status) {
    case 503:
      return { kind: 'unavailable', message: message ?? 'Tracking is unavailable right now. Nothing was changed.' };
    case 401:
      return { kind: 'signedOut', message: 'Your session has ended. Sign in again to see tracking information.' };
    case 403:
    case 404:
      return { kind: 'denied', message: 'This application is not available to your account.' };
    case 409:
      return { kind: 'conflict', message: message ?? 'This was changed in the meantime. Reload and try again.' };
    case 400:
      return { kind: 'invalid', message: message ?? 'The request was not valid.' };
    default:
      return { kind: 'error', message: fallback };
  }
}

/**
 * Renders a failure as an explicit state — never as an empty or guessed
 * status. `write` failures say plainly that nothing was recorded.
 */
export const TrackFailureNotice: React.FC<{
  failure: TrackFailure;
  onRetry?: () => void;
  what?: string;
  mode?: 'read' | 'write';
}> = ({ failure, onRetry, what = 'Tracking information', mode = 'read' }) => {
  let title: string;
  if (failure.kind === 'denied') title = 'Not available to your account.';
  else if (failure.kind === 'signedOut') title = 'Signed out.';
  else if (mode === 'write') title = 'Not recorded.';
  else if (failure.kind === 'unavailable') title = `${what} unavailable.`;
  else title = `${what} could not be loaded.`;
  return (
    <Alert tone="error">
      <strong>{title}</strong> {failure.message}
      {mode === 'read' && failure.kind === 'unavailable' && ' No status has been guessed.'}
      {onRetry && failure.kind !== 'denied' && failure.kind !== 'signedOut' && (
        <button type="button" onClick={onRetry} className="ml-2 font-semibold underline">
          Retry
        </button>
      )}
    </Alert>
  );
};

const DATE_ONLY: Intl.DateTimeFormatOptions = { timeZone: 'UTC', year: 'numeric', month: 'short', day: 'numeric' };
const DATE_TIME: Intl.DateTimeFormatOptions = { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' };

function safeDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  const date = new Date(value.length === 10 ? `${value}T00:00:00Z` : value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * When something happened, at the precision Robin actually knows: a time, a
 * date only, or "Date unknown". A missing time is never filled in with the
 * time Robin recorded it.
 */
export function describeWhen(
  at: string | null | undefined,
  on: string | null | undefined,
  precision: TrackTimePrecision | null | undefined,
  recordedAt?: string | null,
): string {
  if (precision === 'UNKNOWN' || (!at && !on)) {
    const recorded = safeDate(recordedAt);
    return recorded ? `Date unknown · recorded ${recorded.toLocaleDateString(undefined, DATE_ONLY)}` : 'Date unknown';
  }
  if (precision === 'DATE' || (!at && on)) {
    const day = safeDate(on ?? at);
    return day ? day.toLocaleDateString(undefined, DATE_ONLY) : 'Date unknown';
  }
  const exact = safeDate(at);
  return exact ? exact.toLocaleString(undefined, DATE_TIME) : 'Date unknown';
}

export function stageTone(status: TrackStage | undefined, basis: TrackBasis | null | undefined): PillTone {
  switch (status) {
    case 'NEEDS_REVIEW':
      return 'amber';
    case 'REJECTED':
    case 'STOPPED':
      return 'red';
    case 'UNKNOWN':
    case 'WITHDRAWN':
    case undefined:
      return 'slate';
    case 'PREPARING':
    case 'READY_TO_APPLY':
    case 'APPLYING':
      return 'sky';
    default:
      if (basis === 'VERIFIED') return 'emerald';
      if (basis === 'UNVERIFIED' || basis === 'INFERRED') return 'amber';
      return 'forest';
  }
}

export function verificationTone(verification: TrackVerification): PillTone {
  switch (verification) {
    case 'VERIFIED':
      return 'emerald';
    case 'UNVERIFIED':
      return 'amber';
    case 'REJECTED':
      return 'red';
    default:
      return 'slate';
  }
}

export function eventTone(classification: TrackEventClassification, verification: TrackVerification): PillTone {
  switch (classification) {
    case 'EXTERNAL':
      return verificationTone(verification);
    case 'INFERRED':
      return 'amber';
    case 'CANDIDATE_REPORTED':
      return 'forest';
    default:
      return 'slate';
  }
}

export function submissionTone(state: TrackSubmissionState | undefined): PillTone {
  switch (state) {
    case 'CONFIRMED':
      return 'emerald';
    case 'UNVERIFIED':
      return 'amber';
    case 'CONFLICTING':
      return 'red';
    default:
      return 'slate';
  }
}

/** Short wording for the list view, where only the submission state is known. */
export function submissionSummary(state: TrackSubmissionState | undefined): string {
  switch (state) {
    case 'CONFIRMED':
      return 'Submission confirmed by verified employer evidence';
    case 'UNVERIFIED':
      return 'Submission not verified';
    case 'CONFLICTING':
      return 'Conflicting submission evidence';
    case 'UNKNOWN':
      return 'No evidence of submission';
    default:
      return 'Submission state unavailable';
  }
}

export const ACTOR_LABELS: Record<string, string> = {
  ROBIN: 'Robin',
  YOU: 'You',
  EMPLOYER: 'Employer',
};

/** Stable id for one draft of a candidate report, reused when the same submission is retried. */
export function newClientId(prefix: string): string {
  const random =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  return `${prefix}-${random}`.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 64);
}

/* ------------------------------------------------------------------ */
/* Employer-message review                                             */
/* ------------------------------------------------------------------ */

const REVIEW_HEADINGS: Record<TrackReviewItem['reviewType'], string> = {
  CONFLICT: 'This message identifies a different application than the one it was added to',
  ASSOCIATION: 'Which application is this message about?',
  UNMATCHED: 'Robin could not tie this message to an application',
  MEANING: 'Confirm what this message means',
};

/**
 * One employer message awaiting the candidate. Robin never picks among
 * similar applications for them: unless the message is already tied to one
 * application by a strong identifier (a "meaning" review), nothing is
 * preselected and Confirm stays disabled until the candidate chooses.
 * A confirmation is recorded as the candidate's — never as verification.
 */
export const TrackReviewCard: React.FC<{
  item: TrackReviewItem;
  /** The owner's applications, offered only when the message identifies none. */
  fallbackApplications?: TrackApplicationRef[];
  onDecided: (message: string) => void;
  onStale?: () => void;
}> = ({ item, fallbackApplications = [], onDecided, onStale }) => {
  const options: Array<TrackApplicationRef & { signals?: string[] }> = [];
  const seen = new Set<string>();
  const add = (ref: TrackApplicationRef & { signals?: string[] }) => {
    if (!seen.has(ref.applicationId)) {
      seen.add(ref.applicationId);
      options.push(ref);
    }
  };
  if (item.currentApplication) add(item.currentApplication);
  item.candidates.forEach(add);
  const offeringFallback = options.length === 0;
  if (offeringFallback) fallbackApplications.forEach(add);

  const [selected, setSelected] = useState<string>(
    item.reviewType === 'MEANING' && item.currentApplication ? item.currentApplication.applicationId : '',
  );
  const [category, setCategory] = useState<string>(item.category);
  const [busy, setBusy] = useState<'confirm' | 'reject' | null>(null);
  const [failure, setFailure] = useState<TrackFailure | null>(null);

  const decide = async (decision: 'CONFIRM' | 'REJECT') => {
    setBusy(decision === 'CONFIRM' ? 'confirm' : 'reject');
    setFailure(null);
    try {
      await apiFetch(`/track/emails/${item.emailId}/review`, {
        method: 'POST',
        body: JSON.stringify(
          decision === 'CONFIRM'
            ? { decision, applicationId: selected, category, expectedVersion: item.decisionVersion }
            : { decision, expectedVersion: item.decisionVersion },
        ),
      });
      onDecided(
        decision === 'CONFIRM'
          ? 'Recorded as your confirmation. It is shown as confirmed by you — not as independently verified evidence.'
          : 'Recorded: you said this message is not about that application.',
      );
    } catch (err) {
      const classified = classifyTrackError(err, 'Your decision could not be recorded. Nothing was changed.');
      setFailure(classified);
      if (classified.kind === 'conflict') onStale?.();
    } finally {
      setBusy(null);
    }
  };

  const titleId = `track-review-${item.emailId}`;
  return (
    <article aria-labelledby={titleId} className="rounded-lg border border-amber-200 bg-amber-50/40 px-4 py-3 text-sm" data-testid="track-review">
      <p id={titleId} className="font-semibold text-ink">{REVIEW_HEADINGS[item.reviewType]}</p>
      <dl className="mt-2 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-2">
        <div>
          <dt className="text-xs text-ink-muted">Subject</dt>
          <dd className="text-ink-body">{item.subject || '(no subject)'}</dd>
        </div>
        <div>
          <dt className="text-xs text-ink-muted">From</dt>
          <dd className="text-ink-body">{item.from || 'Sender not given'}</dd>
        </div>
        <div>
          <dt className="text-xs text-ink-muted">Received</dt>
          <dd className="text-ink-body">
            {item.receivedTimeKnown && item.receivedAt ? describeWhen(item.receivedAt, null, 'EXACT') : 'Date unknown'}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-ink-muted">Added</dt>
          <dd className="text-ink-body">
            {item.addedBy === 'MAILBOX_CONNECTOR' ? 'Retrieved from your connected mailbox' : 'Added by you — cannot be verified'}
          </dd>
        </div>
      </dl>

      <p className="mt-2 text-ink-body">
        {item.categoryConfirmed ? 'You confirmed it as: ' : 'Robin thinks it is: '}
        <strong>{item.categoryLabel}</strong>
        {!item.categoryConfirmed && <span className="text-amber-800"> (inferred, not confirmed)</span>}
      </p>
      {item.confidence != null && !item.categoryConfirmed && (
        <p className="text-xs text-ink-muted">
          Rule strength {item.confidence.toFixed(2)}. {item.confidenceNote}
        </p>
      )}
      {item.excerpt && (
        <blockquote className="mt-2 border-l-2 border-line pl-3 text-xs italic text-ink-muted">“{item.excerpt}”</blockquote>
      )}
      {item.rationale && item.rationale.length > 0 && (
        <ul className="mt-2 list-disc space-y-0.5 pl-5 text-xs text-ink-muted">
          {item.rationale.map((reason) => (
            <li key={reason.code}>{reason.message}</li>
          ))}
        </ul>
      )}

      <fieldset className="mt-3">
        <legend className="text-xs font-semibold text-ink-soft">
          {offeringFallback ? 'Choose an application only if you are sure' : 'Application'}
        </legend>
        {options.length === 0 ? (
          <p className="text-xs text-ink-muted">You have no applications to attach it to.</p>
        ) : (
          <ul className="mt-1 space-y-1">
            {options.map((option) => (
              <li key={option.applicationId}>
                <label className="flex items-start gap-2">
                  <input
                    type="radio"
                    name={`track-review-app-${item.emailId}`}
                    value={option.applicationId}
                    checked={selected === option.applicationId}
                    onChange={() => setSelected(option.applicationId)}
                  />
                  <span>
                    <span className="font-medium text-ink">{option.jobTitle}</span> · {option.company}
                    {option.location ? ` · ${option.location}` : ''}
                    {option.createdAt ? ` · created ${describeWhen(option.createdAt, null, 'EXACT')}` : ''}
                    {item.currentApplication?.applicationId === option.applicationId && (
                      <span className="text-ink-muted"> — currently attached</span>
                    )}
                    {option.signals && option.signals.length > 0 && (
                      <span className="block text-xs text-ink-muted">{option.signals.join(' · ')}</span>
                    )}
                  </span>
                </label>
              </li>
            ))}
          </ul>
        )}
      </fieldset>

      <label className="mt-3 block text-xs font-semibold text-ink-soft" htmlFor={`track-review-category-${item.emailId}`}>
        What the message means
      </label>
      <Select
        id={`track-review-category-${item.emailId}`}
        value={category}
        onChange={(event) => setCategory(event.target.value)}
        className="mt-1 max-w-sm"
      >
        {item.allowedCategories.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </Select>

      {failure && (
        <div className="mt-2">
          <TrackFailureNotice failure={failure} mode="write" />
        </div>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        <SecondaryButton type="button" disabled={!selected || busy !== null} onClick={() => decide('CONFIRM')}>
          {busy === 'confirm' ? 'Recording…' : 'Confirm for the selected application'}
        </SecondaryButton>
        <SecondaryButton type="button" disabled={busy !== null} onClick={() => decide('REJECT')}>
          {busy === 'reject' ? 'Recording…' : 'Not about any of these applications'}
        </SecondaryButton>
      </div>
      <p className="mt-2 text-xs text-ink-faint">
        Your decision is recorded as yours. It never makes the message verified employer evidence.
      </p>
    </article>
  );
};
