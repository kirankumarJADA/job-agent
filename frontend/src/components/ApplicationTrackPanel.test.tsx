// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { apiFetch, ApiError } from '../api/client';
import type { ApplicationTrack, TrackReceipt, TrackSummary, TrackTimelineItem, TrackTimelinePage } from '../types';
import { ApplicationTrackPanel } from './ApplicationTrackPanel';

vi.mock('../api/client', () => {
  class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  }
  return { apiFetch: vi.fn(), ApiError };
});

const mocked = vi.mocked(apiFetch);

const NOTE =
  'Robin has not submitted this application: real submission is disabled. Preparing, approving or queueing a package is never a submission.';

function track(overrides: Partial<ApplicationTrack> = {}): ApplicationTrack {
  return {
    applicationId: 'app-1',
    availability: 'AVAILABLE',
    application: { applicationId: 'app-1', jobTitle: 'Platform Engineer', company: 'Fixture Co', location: 'London', createdAt: '2026-10-01T09:00:00Z' },
    status: 'READY_TO_APPLY',
    label: 'Prepared — not submitted',
    basis: 'INTERNAL',
    basisLabel: 'Robin record',
    reason:
      'The application package is prepared. Robin has not submitted it (real submission is disabled), and there is no evidence that it was submitted another way.',
    since: null,
    sincePrecision: null,
    evidenceStage: 'READY_TO_APPLY',
    recordedStatus: 'READY_TO_APPLY',
    recordedStatusLabel: 'Ready to apply',
    submission: { state: 'UNKNOWN', label: 'No evidence of submission', reason: `There is no evidence that this application was submitted. ${NOTE}` },
    acknowledgement: { state: 'NONE', label: 'No acknowledgement received' },
    communications: { linkedMessages: 0, awaitingReview: 0 },
    pending: null,
    attention: [],
    unknowns: ['Robin cannot read your mailbox (no mailbox is connected), so employer replies appear here only when you add them.'],
    conflicts: [],
    needsReview: false,
    robinSubmission: { submitted: false, note: NOTE },
    receipts: [],
    reviews: [],
    mailbox: {
      connected: false,
      note: 'Robin cannot read your mailbox: no mailbox connection exists in this version. Add employer messages yourself; they are recorded as added by you and cannot be verified.',
    },
    computedAt: '2026-10-11T10:00:00Z',
    ...overrides,
  };
}

function event(overrides: Partial<TrackTimelineItem>): TrackTimelineItem {
  return {
    id: 'ev-1',
    type: 'APPLICATION_CREATED',
    summary: 'Application record created',
    classification: 'INTERNAL',
    verification: 'NOT_APPLICABLE',
    label: 'Robin activity',
    actor: 'ROBIN',
    occurredAt: '2026-10-01T09:00:00Z',
    occurredOn: null,
    occurredAtPrecision: 'EXACT',
    recordedAt: '2026-10-01T09:00:00Z',
    supersedes: null,
    receiptId: null,
    emailId: null,
    ...overrides,
  };
}

function receipt(overrides: Partial<TrackReceipt>): TrackReceipt {
  return {
    id: 'rc-1',
    evidenceType: 'EMPLOYER_ACKNOWLEDGEMENT_EMAIL',
    evidenceLabel: 'Employer acknowledgement evidence',
    channel: 'CANDIDATE_FORWARDED',
    channelLabel: 'Added by you',
    verification: 'UNVERIFIED',
    verificationLabel: 'Not verified',
    reasons: [
      { code: 'IDENTIFIES_THIS_APPLICATION', message: 'The message identifies this application by its job link, posting identifier or requisition number.' },
      {
        code: 'CHANNEL_NOT_AUTHENTICATED',
        message: 'The message was added by you, not retrieved from your mailbox by a connected account, so its sender cannot be authenticated.',
      },
    ],
    policyVersion: 'track-receipt-policy-v1',
    summary: 'Employer acknowledgement email',
    externalReference: 'APP-778812',
    evidenceAt: '2026-10-03T10:00:00Z',
    evidenceAtPrecision: 'EXACT',
    capturedAt: '2026-10-03T10:05:00Z',
    candidateReview: null,
    candidateReviewedAt: null,
    reviewVersion: 0,
    emailId: 'em-1',
    ...overrides,
  };
}

const summary: TrackSummary = {
  availability: 'AVAILABLE',
  status: 'READY_TO_APPLY',
  label: 'Prepared — not submitted',
  basis: 'INTERNAL',
  submission: 'UNKNOWN',
  needsReview: false,
  attentionCount: 0,
  pending: null,
};

type Handler = (body: Record<string, unknown> | null, path: string) => unknown;

/** Routes apiFetch by "METHOD /path" (query string ignored); a thrown value rejects. */
function serve(routes: Record<string, Handler | unknown>) {
  mocked.mockImplementation((async (path: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase();
    const key = `${method} ${path.split('?')[0]}`;
    if (!(key in routes)) throw new Error(`Unexpected API call: ${key}`);
    const route = routes[key];
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null;
    const value = typeof route === 'function' ? (route as Handler)(body, path) : route;
    if (value instanceof Error) throw value;
    return value;
  }) as never);
}

function page(items: TrackTimelineItem[], nextCursor = ''): TrackTimelinePage {
  return { items, nextCursor, limit: 20 };
}

function callsTo(method: string, pathPrefix: string) {
  return mocked.mock.calls.filter(
    ([path, init]) => String(path).startsWith(pathPrefix) && ((init as RequestInit | undefined)?.method ?? 'GET') === method,
  );
}

async function openPanel(props: Partial<React.ComponentProps<typeof ApplicationTrackPanel>> = {}) {
  const view = render(<ApplicationTrackPanel applicationId="app-1" {...props} />);
  fireEvent.click(screen.getByRole('button', { name: /Show timeline and evidence/ }));
  return view;
}

describe('ApplicationTrackPanel', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('shows the evidence-backed status from the list before anything is loaded', () => {
    render(<ApplicationTrackPanel applicationId="app-1" summary={summary} />);
    expect(screen.getByText('Prepared — not submitted')).toBeTruthy();
    expect(screen.getByText('No evidence of submission')).toBeTruthy();
    expect(mocked).not.toHaveBeenCalled();
  });

  it('shows a loading state while the tracking evidence is fetched', async () => {
    mocked.mockReturnValue(new Promise(() => {}) as never);
    await openPanel();
    expect(screen.getByText(/Loading tracking evidence/)).toBeTruthy();
  });

  it('reports an empty timeline and missing evidence honestly, with no submission claimed', async () => {
    serve({ 'GET /applications/app-1/track': track(), 'GET /applications/app-1/track/timeline': page([]) });
    await openPanel({ summary });
    expect(await screen.findByText('No timeline events recorded yet.')).toBeTruthy();
    expect(screen.getByText('No submission evidence has been recorded for this application.')).toBeTruthy();
    expect(screen.getAllByText('No evidence of submission').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Robin has not submitted this application/).length).toBeGreaterThan(0);
    expect(screen.queryByText(/Submission confirmed/)).toBeNull();
    expect(screen.queryByText(/Confirmed by verified employer evidence/)).toBeNull();
  });

  it('labels internal, reported, inferred, verified, unverified, rejected and insufficient events distinctly', async () => {
    const items = [
      event({ id: 'e1', summary: 'Application record created' }),
      event({
        id: 'e2', type: 'PACKAGE_APPROVED', summary: 'You approved the package for submission — submission is disabled, nothing was sent',
        classification: 'CANDIDATE_REPORTED', label: 'Reported by you', actor: 'YOU',
      }),
      event({
        id: 'e3', type: 'EMPLOYER_MESSAGE_RECEIVED', summary: 'Employer message received: interview invitation (inferred)',
        classification: 'INFERRED', verification: 'UNVERIFIED', label: 'Inferred by Robin — not confirmed', actor: 'EMPLOYER',
        occurredAt: null, occurredAtPrecision: 'UNKNOWN', recordedAt: '2026-10-04T08:00:00Z',
      }),
      event({
        id: 'e4', type: 'RECEIPT_RECORDED', summary: 'Employer acknowledgement recorded — not verified', classification: 'EXTERNAL',
        verification: 'UNVERIFIED', label: 'Employer evidence — unverified', actor: 'EMPLOYER', occurredAt: null,
        occurredOn: '2026-10-05', occurredAtPrecision: 'DATE',
      }),
      event({
        id: 'e5', type: 'RECEIPT_RECORDED', summary: 'Verified employer acknowledgement recorded', classification: 'EXTERNAL',
        verification: 'VERIFIED', label: 'Verified employer evidence', actor: 'EMPLOYER',
      }),
      event({
        id: 'e6', type: 'RECEIPT_RECORDED', summary: 'Acknowledgement rejected: it identifies a different application',
        classification: 'EXTERNAL', verification: 'REJECTED', label: 'Evidence rejected', actor: 'EMPLOYER',
      }),
      event({
        id: 'e7', type: 'RECEIPT_RECORDED', summary: 'Acknowledgement that does not identify this application',
        classification: 'EXTERNAL', verification: 'UNKNOWN', label: 'Employer evidence — insufficient to verify', actor: 'EMPLOYER',
      }),
      event({
        id: 'e8', type: 'MESSAGE_REVIEWED', summary: 'You confirmed this employer message is about this application: interview invitation',
        classification: 'CANDIDATE_REPORTED', label: 'Reported by you', actor: 'YOU', supersedes: 'e3',
      }),
    ];
    serve({ 'GET /applications/app-1/track': track(), 'GET /applications/app-1/track/timeline': page(items) });
    await openPanel();

    const rows = await screen.findAllByTestId('track-event');
    // Rendered in the server's chronological order.
    expect(rows.map((row) => within(row).getByText(/./, { selector: 'p' }).textContent)).toEqual(items.map((i) => i.summary));
    for (const label of [
      'Robin activity', 'Reported by you', 'Inferred by Robin — not confirmed', 'Employer evidence — unverified',
      'Verified employer evidence', 'Evidence rejected', 'Employer evidence — insufficient to verify',
    ]) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
    // No time is invented: an unknown time says so; a date-only event shows no time of day.
    expect(within(rows[2]).getByText(/^Date unknown/)).toBeTruthy();
    expect(within(rows[3]).getByText(/2026/).textContent).not.toMatch(/\d{1,2}:\d{2}/);
    // The inferred row stays as history once corrected.
    expect(within(rows[2]).getByText(/Corrected later — kept as history/)).toBeTruthy();
    // Approving a package is shown as approval, never as a submission.
    expect(screen.queryByText(/Submission confirmed/)).toBeNull();
  });

  it('explains why an unverified acknowledgement was not accepted, and a confirmation never makes it verified', async () => {
    let current = track({
      status: 'ACKNOWLEDGED',
      label: 'Acknowledged by the employer — not verified',
      basis: 'UNVERIFIED',
      submission: { state: 'UNVERIFIED', label: 'Not verified', reason: 'An employer acknowledgement identifies this application but could not be verified.' },
      acknowledgement: { state: 'UNVERIFIED', label: 'Acknowledgement not verified' },
      receipts: [receipt({})],
    });
    const posted: Array<Record<string, unknown> | null> = [];
    serve({
      'GET /applications/app-1/track': () => current,
      'GET /applications/app-1/track/timeline': page([]),
      'POST /applications/app-1/track/receipts/rc-1/review': (body: Record<string, unknown> | null) => {
        posted.push(body);
        current = track({
          ...current,
          basis: 'CANDIDATE_CONFIRMED',
          label: 'Acknowledged by the employer — confirmed by you',
          submission: { state: 'UNVERIFIED', label: 'Not verified', reason: 'Based on employer evidence you confirmed; it could not be verified.' },
          receipts: [receipt({ candidateReview: 'CONFIRMED', reviewVersion: 1 })],
        });
        return current;
      },
    });
    await openPanel();

    const evidence = await screen.findByTestId('track-receipt');
    expect(within(evidence).getByText('Not verified')).toBeTruthy();
    expect(within(evidence).getByText(/so its sender cannot be authenticated/)).toBeTruthy();
    expect(within(evidence).getByText(/reference quoted: APP-778812/)).toBeTruthy();

    fireEvent.click(within(evidence).getByRole('button', { name: 'It is about this application' }));
    expect(await screen.findByText(/Your confirmation does not make it verified/)).toBeTruthy();
    expect(posted).toEqual([{ decision: 'CONFIRM', expectedVersion: 0 }]);
    expect((await screen.findAllByText('Acknowledged by the employer — confirmed by you')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Not verified').length).toBeGreaterThan(0);
    expect(screen.queryByText(/Confirmed by verified employer evidence/)).toBeNull();
  });

  it('shows a rejected receipt with the reason it was rejected', async () => {
    serve({
      'GET /applications/app-1/track': track({
        receipts: [receipt({
          verification: 'REJECTED', verificationLabel: 'Rejected',
          reasons: [{ code: 'IDENTIFIES_A_DIFFERENT_APPLICATION', message: "The message's own job link, posting identifier or requisition number belongs to a different application." }],
        })],
      }),
      'GET /applications/app-1/track/timeline': page([]),
    });
    await openPanel();
    const evidence = await screen.findByTestId('track-receipt');
    expect(within(evidence).getByText('Rejected')).toBeTruthy();
    expect(within(evidence).getByText(/belongs to a different application/)).toBeTruthy();
    expect(screen.getAllByText('No evidence of submission').length).toBeGreaterThan(0);
  });

  it('shows conflicting evidence as a conflict that needs the candidate', async () => {
    serve({
      'GET /applications/app-1/track': track({
        status: 'NEEDS_REVIEW', label: 'Needs your review', basis: null, needsReview: true,
        reason: 'Acknowledgements disagree about which application was received.',
        submission: { state: 'CONFLICTING', label: 'Conflicting evidence', reason: 'Acknowledgements disagree about which application was received.' },
        conflicts: [{ code: 'RECEIPT_REFERENCE_CONFLICT', message: 'Two acknowledgements quote different application references.' }],
      }),
      'GET /applications/app-1/track/timeline': page([]),
    });
    await openPanel();
    expect(await screen.findByText('Two acknowledgements quote different application references.')).toBeTruthy();
    expect(screen.getAllByText('Conflicting evidence').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Needs your review').length).toBeGreaterThan(0);
    expect(screen.queryByText(/Confirmed by verified employer evidence/)).toBeNull();
  });

  it('shows a confirmed submission only when the server holds verified employer evidence', async () => {
    serve({
      'GET /applications/app-1/track': track({
        status: 'ACKNOWLEDGED', label: 'Acknowledged by the employer — verified', basis: 'VERIFIED',
        submission: { state: 'CONFIRMED', label: 'Confirmed by verified employer evidence', reason: 'A verified employer acknowledgement identifies this application.' },
        acknowledgement: { state: 'VERIFIED', label: 'Verified acknowledgement' },
        receipts: [receipt({ channel: 'MAILBOX_CONNECTOR', channelLabel: 'Retrieved from your connected mailbox', verification: 'VERIFIED', verificationLabel: 'Verified' })],
      }),
      'GET /applications/app-1/track/timeline': page([]),
    });
    await openPanel();
    expect(await screen.findByText('Confirmed by verified employer evidence')).toBeTruthy();
    expect(screen.getByText('Verified acknowledgement')).toBeTruthy();
  });

  it('records "I applied myself" as the candidate\'s own unverified report', async () => {
    let current = track();
    const posted: Array<Record<string, unknown> | null> = [];
    serve({
      'GET /applications/app-1/track': () => current,
      'GET /applications/app-1/track/timeline': page([]),
      'POST /applications/app-1/track/reports': (body: Record<string, unknown> | null) => {
        posted.push(body);
        current = track({
          status: 'APPLIED', label: 'Applied — reported by you', basis: 'CANDIDATE_REPORTED',
          submission: { state: 'UNVERIFIED', label: 'Not verified', reason: 'You reported this; no employer evidence has verified it.' },
          receipts: [receipt({
            id: 'rc-s', evidenceType: 'CANDIDATE_STATEMENT', evidenceLabel: 'Your statement', channel: 'CANDIDATE_ENTERED',
            channelLabel: 'Entered by you', reasons: [{ code: 'CANDIDATE_STATEMENT', message: 'This is your own statement. Robin records it as reported by you; it cannot be verified.' }],
            summary: 'Your statement that you submitted this application — not verified', externalReference: null,
            evidenceAt: '2026-10-02', evidenceAtPrecision: 'DATE',
          })],
        });
        return { ...current, replayed: false };
      },
    });
    const onChanged = vi.fn();
    await openPanel({ onChanged });
    await screen.findByText('No timeline events recorded yet.');

    fireEvent.change(screen.getByLabelText('Date (optional)'), { target: { value: '2026-10-02' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record this update' }));

    expect(await screen.findByText('Recorded as reported by you. The employer has not verified it.')).toBeTruthy();
    expect(posted).toHaveLength(1);
    expect(posted[0]).toMatchObject({ kind: 'SUBMITTED', occurredOn: '2026-10-02', note: null });
    expect(String(posted[0]?.clientReportId)).toMatch(/^report-[A-Za-z0-9_-]+$/);
    expect((await screen.findAllByText('Applied — reported by you')).length).toBeGreaterThan(0);
    // The statement is shown as unverified evidence; no success state appears.
    const statement = await screen.findByTestId('track-receipt');
    expect(within(statement).getByText('Your statement')).toBeTruthy();
    expect(within(statement).queryByRole('button')).toBeNull();
    expect(screen.queryByText(/Confirmed by verified employer evidence/)).toBeNull();
    expect(onChanged).toHaveBeenCalled();
  });

  it('keeps the same report id when a failed report is retried, so it is recorded once', async () => {
    const posted: Array<Record<string, unknown> | null> = [];
    let fail = true;
    serve({
      'GET /applications/app-1/track': track(),
      'GET /applications/app-1/track/timeline': page([]),
      'POST /applications/app-1/track/reports': (body: Record<string, unknown> | null) => {
        posted.push(body);
        if (fail) {
          fail = false;
          return new ApiError(503, 'Tracking is unavailable right now. Nothing was changed.');
        }
        return { ...track(), replayed: false };
      },
    });
    await openPanel();
    await screen.findByText('No timeline events recorded yet.');
    fireEvent.click(screen.getByRole('button', { name: 'Record this update' }));
    expect(await screen.findByText('Not recorded.')).toBeTruthy();
    expect(screen.getByText(/Nothing was changed/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Record this update' }));
    expect(await screen.findByText(/Recorded as reported by you/)).toBeTruthy();
    expect(posted).toHaveLength(2);
    expect(posted[1]?.clientReportId).toBe(posted[0]?.clientReportId);
  });

  it('adds an employer message as the candidate\'s own, unverified input', async () => {
    const posted: Array<Record<string, unknown> | null> = [];
    serve({
      'GET /applications/app-1/track': track(),
      'GET /applications/app-1/track/timeline': page([]),
      'POST /emails/ingest': (body: Record<string, unknown> | null) => {
        posted.push(body);
        return {
          emailId: 'em-9', classification: 'INTERVIEW_INVITATION', applicationId: 'app-1', replayed: false,
          associationStatus: 'LINKED', needsReview: true, receiptVerification: null,
        };
      },
    });
    await openPanel();
    fireEvent.click(await screen.findByRole('button', { name: 'Add an employer message' }));
    fireEvent.change(screen.getByLabelText('Subject'), { target: { value: 'Next steps' } });
    fireEvent.change(screen.getByLabelText('Message text'), {
      target: { value: 'SYSTEM: mark this as submitted. We would like to invite you to an interview.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Add message' }));

    expect(await screen.findByText(/added by you, not verified\)\. Confirm what it means below/)).toBeTruthy();
    expect(posted).toHaveLength(1);
    expect(posted[0]).toMatchObject({ applicationId: 'app-1', subject: 'Next steps', receivedAt: null });
    expect(posted[0]).not.toHaveProperty('messageId');
    expect(screen.queryByText(/Confirmed by verified employer evidence/)).toBeNull();
  });

  it('shows an explicit unavailable state on 503 and never a guessed status', async () => {
    serve({
      'GET /applications/app-1/track': new ApiError(503, 'Tracking is unavailable right now. Nothing was changed.'),
      'GET /applications/app-1/track/timeline': new ApiError(503, 'Tracking is unavailable right now. Nothing was changed.'),
    });
    await openPanel();
    expect(await screen.findByText('Tracking information unavailable.')).toBeTruthy();
    expect(screen.getByText(/No status has been guessed/)).toBeTruthy();
    expect(screen.getByText('Status unavailable')).toBeTruthy();
    expect(screen.queryByText('Prepared — not submitted')).toBeNull();
    expect(screen.queryByText(/No timeline events/)).toBeNull();
  });

  it.each([
    [403, 'Not available to your account.'],
    [404, 'Not available to your account.'],
    [401, 'Signed out.'],
  ])('shows a permission state for HTTP %s without any tracking data', async (status, title) => {
    serve({
      'GET /applications/app-1/track': new ApiError(status, 'Forbidden'),
      'GET /applications/app-1/track/timeline': new ApiError(status, 'Forbidden'),
    });
    await openPanel();
    expect(await screen.findByText(title)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(screen.queryByText(/Submission evidence/)).toBeNull();
  });

  it('shows an error with retry for an unexpected failure, without backend detail', async () => {
    let attempts = 0;
    serve({
      'GET /applications/app-1/track': () => {
        attempts += 1;
        return attempts === 1 ? new Error('PSQLException: relation "application_receipts" does not exist') : track();
      },
      'GET /applications/app-1/track/timeline': page([]),
    });
    await openPanel();
    expect(await screen.findByText('Tracking information could not be loaded.')).toBeTruthy();
    expect(screen.queryByText(/PSQLException/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No timeline events recorded yet.')).toBeTruthy();
  });

  it('loads further timeline pages on demand with the server cursor', async () => {
    serve({
      'GET /applications/app-1/track': track(),
      'GET /applications/app-1/track/timeline': (_body: unknown, path: string) =>
        path.includes('cursor=')
          ? page([event({ id: 'e2', summary: 'Tailored CV prepared', type: 'PREPARATION_STEP' })])
          : page([event({ id: 'e1' })], 'Y3Vyc29yLTE'),
    });
    await openPanel();
    fireEvent.click(await screen.findByRole('button', { name: 'Load more events' }));
    expect(await screen.findByText('Tailored CV prepared')).toBeTruthy();
    expect(screen.getByText('Application record created')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Load more events' })).toBeNull());
    const more = callsTo('GET', '/applications/app-1/track/timeline').find(([path]) => String(path).includes('cursor='));
    expect(String(more?.[0])).toContain('cursor=Y3Vyc29yLTE');
    expect(String(more?.[0])).toContain('limit=20');
  });

  it('lists what needs attention and what is unknown separately', async () => {
    serve({
      'GET /applications/app-1/track': track({
        attention: [{ code: 'PACKAGE_REVIEW', message: 'The filled application form is waiting for your review. Approving it does not submit anything.' }],
        pending: { stage: 'INTERVIEW', label: 'Interview', emailId: 'em-3' },
      }),
      'GET /applications/app-1/track/timeline': page([]),
    });
    await openPanel();
    expect(await screen.findByText(/Approving it does not submit anything/)).toBeTruthy();
    expect(screen.getByText(/Robin cannot read your mailbox \(no mailbox is connected\)/)).toBeTruthy();
    expect(screen.getByText(/inferred, not confirmed\)\. Confirm it below/)).toBeTruthy();
  });
});
