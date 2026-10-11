// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { apiFetch, ApiError } from '../api/client';
import type { TrackReviewItem } from '../types';
import { TrackReviewQueue } from './TrackReviewQueue';

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

const CATEGORIES = [
  { value: 'APPLICATION_ACKNOWLEDGEMENT', label: 'Application acknowledgement' },
  { value: 'INTERVIEW_INVITATION', label: 'Interview invitation' },
  { value: 'REJECTION', label: 'Rejection' },
  { value: 'UNRELATED_OR_AMBIGUOUS', label: 'Unrelated or unclear message' },
];

function item(overrides: Partial<TrackReviewItem> = {}): TrackReviewItem {
  return {
    emailId: 'em-1',
    status: 'PENDING_REVIEW',
    reviewType: 'ASSOCIATION',
    subject: 'Interview: Platform Engineer',
    from: 'talent@fixture.example',
    receivedAt: '2026-10-05T09:30:00Z',
    receivedTimeKnown: true,
    category: 'INTERVIEW_INVITATION',
    categoryLabel: 'Interview invitation',
    categoryConfirmed: false,
    confidence: 0.85,
    confidenceNote: "A rule-strength score for Robin's inference — not proof of anything.",
    excerpt: 'We would like to invite you to an interview for the Platform Engineer role.',
    rationale: [
      {
        code: 'WEAK_SIGNALS_ONLY',
        message: 'The company name and job title match more than one of your applications, so Robin did not choose.',
      },
    ],
    decisionVersion: 0,
    addedBy: 'YOU',
    currentApplication: null,
    candidates: [
      {
        applicationId: 'app-1', jobTitle: 'Platform Engineer', company: 'Fixture Co', location: 'London',
        createdAt: '2026-10-01T09:00:00Z', signals: ['Company name matches', 'Job title matches'],
      },
      {
        applicationId: 'app-2', jobTitle: 'Platform Engineer', company: 'Fixture Co', location: 'Manchester',
        createdAt: '2026-10-02T09:00:00Z', signals: ['Company name matches', 'Job title matches'],
      },
    ],
    allowedCategories: CATEGORIES,
    ...overrides,
  };
}

type Handler = (body: Record<string, unknown> | null) => unknown;

function serve(routes: Record<string, Handler | unknown>) {
  mocked.mockImplementation((async (path: string, init?: RequestInit) => {
    const key = `${(init?.method ?? 'GET').toUpperCase()} ${path.split('?')[0]}`;
    if (!(key in routes)) throw new Error(`Unexpected API call: ${key}`);
    const route = routes[key];
    const value = typeof route === 'function'
      ? (route as Handler)(init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null)
      : route;
    if (value instanceof Error) throw value;
    return value;
  }) as never);
}

function reviewGets() {
  return mocked.mock.calls.filter(([path, init]) => path === '/track/reviews' && !(init as RequestInit | undefined)?.method);
}

describe('TrackReviewQueue', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('shows a loading state, then an explicit empty state with the mailbox limitation', async () => {
    let resolve: (value: unknown) => void = () => {};
    mocked.mockReturnValueOnce(new Promise((r) => { resolve = r; }) as never);
    render(<TrackReviewQueue />);
    expect(screen.getByText(/Loading employer messages/)).toBeTruthy();
    resolve({ items: [], mailboxConnected: false });
    expect(await screen.findByText('No employer messages need your review.')).toBeTruthy();
    expect(screen.getByText(/no mailbox connection exists in this version/)).toBeTruthy();
  });

  it('never chooses between applications with similar titles for the candidate', async () => {
    const posted: Array<Record<string, unknown> | null> = [];
    let items = [item()];
    serve({
      'GET /track/reviews': () => ({ items, mailboxConnected: false }),
      'POST /track/emails/em-1/review': (body: Record<string, unknown> | null) => {
        posted.push(body);
        items = [];
        return { emailId: 'em-1', applicationId: 'app-2', status: 'CONFIRMED', reviews: [] };
      },
    });
    const onChanged = vi.fn();
    render(<TrackReviewQueue onChanged={onChanged} />);

    const card = await screen.findByTestId('track-review');
    expect(within(card).getByText('Which application is this message about?')).toBeTruthy();
    expect(within(card).getByText(/inferred, not confirmed/)).toBeTruthy();
    expect(within(card).getByText(/not proof of anything/)).toBeTruthy();
    expect(within(card).getByText(/so Robin did not choose/)).toBeTruthy();
    const radios = within(card).getAllByRole('radio') as HTMLInputElement[];
    expect(radios).toHaveLength(2);
    expect(radios.every((radio) => !radio.checked)).toBe(true);
    const confirm = within(card).getByRole('button', { name: 'Confirm for the selected application' }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);

    fireEvent.click(radios[1]);
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);

    expect(await screen.findByText(/not as independently verified evidence/)).toBeTruthy();
    expect(posted).toEqual([{ decision: 'CONFIRM', applicationId: 'app-2', category: 'INTERVIEW_INVITATION', expectedVersion: 0 }]);
    expect(await screen.findByText('No employer messages need your review.')).toBeTruthy();
    expect(reviewGets().length).toBeGreaterThanOrEqual(2);
    expect(onChanged).toHaveBeenCalled();
  });

  it('lets the candidate correct what a message means', async () => {
    const posted: Array<Record<string, unknown> | null> = [];
    serve({
      'GET /track/reviews': { items: [item()], mailboxConnected: false },
      'POST /track/emails/em-1/review': (body: Record<string, unknown> | null) => {
        posted.push(body);
        return { emailId: 'em-1', applicationId: 'app-1', status: 'CONFIRMED', reviews: [] };
      },
    });
    render(<TrackReviewQueue />);
    const card = await screen.findByTestId('track-review');
    fireEvent.click(within(card).getAllByRole('radio')[0]);
    fireEvent.change(within(card).getByLabelText('What the message means'), { target: { value: 'REJECTION' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Confirm for the selected application' }));
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toMatchObject({ decision: 'CONFIRM', applicationId: 'app-1', category: 'REJECTION' });
  });

  it('records a rejection of the proposed association', async () => {
    const posted: Array<Record<string, unknown> | null> = [];
    serve({
      'GET /track/reviews': { items: [item()], mailboxConnected: false },
      'POST /track/emails/em-1/review': (body: Record<string, unknown> | null) => {
        posted.push(body);
        return { emailId: 'em-1', applicationId: null, status: 'REJECTED', reviews: [] };
      },
    });
    render(<TrackReviewQueue />);
    const card = await screen.findByTestId('track-review');
    fireEvent.click(within(card).getByRole('button', { name: 'Not about any of these applications' }));
    expect(await screen.findByText(/you said this message is not about that application/)).toBeTruthy();
    expect(posted).toEqual([{ decision: 'REJECT', expectedVersion: 0 }]);
  });

  it('reports a stale decision and reloads the queue instead of guessing', async () => {
    serve({
      'GET /track/reviews': { items: [item()], mailboxConnected: false },
      'POST /track/emails/em-1/review': new ApiError(409, 'This message was reviewed in the meantime. Reload and try again.'),
    });
    render(<TrackReviewQueue />);
    const card = await screen.findByTestId('track-review');
    fireEvent.click(within(card).getByRole('button', { name: 'Not about any of these applications' }));
    expect(await screen.findByText(/reviewed in the meantime/)).toBeTruthy();
    expect(screen.getByText('Not recorded.')).toBeTruthy();
    await waitFor(() => expect(reviewGets().length).toBeGreaterThanOrEqual(2));
  });

  it('shows a message added to the wrong application as a conflict to resolve', async () => {
    serve({
      'GET /track/reviews': {
        items: [item({
          status: 'CONFLICT', reviewType: 'CONFLICT',
          currentApplication: { applicationId: 'app-1', jobTitle: 'Platform Engineer', company: 'Fixture Co', location: 'London' },
          candidates: [{ applicationId: 'app-3', jobTitle: 'Data Engineer', company: 'Other Co', location: null, signals: ['Links to this job\'s posting'] }],
          rationale: [{ code: 'ASSERTION_CONTRADICTED', message: 'The message itself identifies a different application.' }],
        })],
        mailboxConnected: false,
      },
    });
    render(<TrackReviewQueue />);
    const card = await screen.findByTestId('track-review');
    expect(within(card).getByText(/identifies a different application than the one it was added to/)).toBeTruthy();
    expect(within(card).getByText(/currently attached/)).toBeTruthy();
    expect(within(card).getByText(/Links to this job's posting/)).toBeTruthy();
    expect((within(card).getAllByRole('radio') as HTMLInputElement[]).every((radio) => !radio.checked)).toBe(true);
  });

  it('offers the candidate\'s applications for an unmatched message, without preselecting one', async () => {
    serve({
      'GET /track/reviews': {
        items: [item({ status: 'UNMATCHED', reviewType: 'UNMATCHED', candidates: [], rationale: [{ code: 'NO_IDENTIFIER', message: 'Nothing in the message identifies one of your applications.' }] })],
        mailboxConnected: false,
      },
    });
    render(
      <TrackReviewQueue
        applications={[
          { applicationId: 'app-1', jobTitle: 'Platform Engineer', company: 'Fixture Co' },
          { applicationId: 'app-9', jobTitle: 'SRE', company: 'Another Co' },
        ]}
      />,
    );
    const card = await screen.findByTestId('track-review');
    expect(within(card).getByText('Choose an application only if you are sure')).toBeTruthy();
    const radios = within(card).getAllByRole('radio') as HTMLInputElement[];
    expect(radios).toHaveLength(2);
    expect(radios.every((radio) => !radio.checked)).toBe(true);
  });

  it('preselects the application only when a strong identifier already ties the message to it', async () => {
    serve({
      'GET /track/reviews': {
        items: [item({
          status: 'LINKED', reviewType: 'MEANING',
          currentApplication: { applicationId: 'app-1', jobTitle: 'Platform Engineer', company: 'Fixture Co' },
          candidates: [{ applicationId: 'app-1', jobTitle: 'Platform Engineer', company: 'Fixture Co', signals: ["Quotes this job's requisition number"] }],
        })],
        mailboxConnected: false,
      },
    });
    render(<TrackReviewQueue />);
    const card = await screen.findByTestId('track-review');
    expect(within(card).getByText('Confirm what this message means')).toBeTruthy();
    const radios = within(card).getAllByRole('radio') as HTMLInputElement[];
    expect(radios).toHaveLength(1);
    expect(radios[0].checked).toBe(true);
  });

  it.each([
    [503, 'Employer messages unavailable.'],
    [403, 'Not available to your account.'],
    [401, 'Signed out.'],
  ])('shows an explicit state when loading fails with HTTP %s', async (status, title) => {
    serve({ 'GET /track/reviews': new ApiError(status, 'Tracking is unavailable right now. Nothing was changed.') });
    render(<TrackReviewQueue />);
    expect(await screen.findByText(title)).toBeTruthy();
    expect(screen.queryByText('No employer messages need your review.')).toBeNull();
  });

  it('shows an unexpected failure with a retry and without backend detail', async () => {
    let attempts = 0;
    serve({
      'GET /track/reviews': () => {
        attempts += 1;
        return attempts === 1 ? new Error('org.postgresql.util.PSQLException: boom') : { items: [], mailboxConnected: false };
      },
    });
    render(<TrackReviewQueue />);
    expect(await screen.findByText('Employer messages could not be loaded.')).toBeTruthy();
    expect(screen.queryByText(/PSQLException/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No employer messages need your review.')).toBeTruthy();
  });
});
