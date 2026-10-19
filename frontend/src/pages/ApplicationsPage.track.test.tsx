// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { apiFetch, ApiError } from '../api/client';
import type { ApplicationSummary, TrackSummary } from '../types';
import { ApplicationsPage } from './ApplicationsPage';

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

function application(overrides: Partial<ApplicationSummary> = {}): ApplicationSummary {
  return {
    id: 'app-1',
    jobId: 'job-1',
    status: 'READY_TO_APPLY',
    mode: 'ASSISTED',
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    jobTitle: 'Platform Engineer',
    company: 'Fixture Co',
    jobLocation: 'London',
    matchScore: 82,
    matchRecommendation: 'APPLY',
    planId: null,
    planStatus: null,
    ...overrides,
  };
}

const prepared: TrackSummary = {
  availability: 'AVAILABLE',
  status: 'READY_TO_APPLY',
  label: 'Prepared — not submitted',
  basis: 'INTERNAL',
  submission: 'UNKNOWN',
  needsReview: false,
  attentionCount: 0,
  pending: null,
};

function serve(items: ApplicationSummary[]) {
  mocked.mockImplementation((async (path: string) => {
    if (path === '/applications') return { items };
    if (path === '/track/reviews') return { items: [], mailboxConnected: false };
    if (path === '/managed-mail/address') return managedMailNotConfigured;
    if (path.startsWith('/prep/jobs/')) throw new ApiError(503, 'unavailable in this test');
    if (path.startsWith('/apply/applications/')) throw new ApiError(503, JSON.stringify({ message: 'Not loaded in this test.' }));
    throw new Error(`Unexpected API call: ${path}`);
  }) as never);
}

const managedMailNotConfigured = { readiness: 'NOT_CONFIGURED', usableForRegistration: false, missing: ['APP_MANAGED_MAIL_DOMAIN'] };

function renderPage() {
  return render(
    <MemoryRouter>
      <ApplicationsPage />
    </MemoryRouter>,
  );
}

describe('Applications page — TRACK status', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('shows the evidence-backed status, keeping the recorded workflow status labelled as such', async () => {
    serve([application({ track: prepared, planId: 'plan-1', planStatus: 'READY_TO_SUBMIT', planSubmitApproved: true })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(card).getAllByText('Prepared — not submitted').length).toBeGreaterThan(0);
    expect(within(card).getByText('Recorded workflow status')).toBeTruthy();
    expect(within(card).getByText('READY_TO_APPLY')).toBeTruthy();
    expect(within(card).getByText('No evidence of submission')).toBeTruthy();
    // An approved package is not a submission: no submitted or confirmed state anywhere.
    expect(within(card).queryByText(/^Submitted$/)).toBeNull();
    expect(within(card).queryByText(/Submission confirmed/)).toBeNull();
  });

  it('says the status is unavailable rather than guessing it', async () => {
    serve([application({ track: { availability: 'UNAVAILABLE' } })]);
    renderPage();
    expect((await screen.findAllByText('Status unavailable')).length).toBeGreaterThan(0);
    expect(screen.queryByText('Prepared — not submitted')).toBeNull();
  });

  it('labels a server response without TRACK data as the recorded status', async () => {
    serve([application({ track: undefined })]);
    renderPage();
    expect(await screen.findByText('Recorded: READY_TO_APPLY')).toBeTruthy();
  });

  it('marks applications whose evidence needs the candidate', async () => {
    serve([
      application({
        track: { ...prepared, status: 'NEEDS_REVIEW', label: 'Needs your review', basis: null, needsReview: true, attentionCount: 2 },
      }),
    ]);
    renderPage();
    expect((await screen.findAllByText('Needs your review')).length).toBeGreaterThan(0);
  });

  it('lists employer messages awaiting review above the applications', async () => {
    serve([application({ track: prepared })]);
    renderPage();
    expect(await screen.findByText('Employer messages to review')).toBeTruthy();
    expect(await screen.findByText('No employer messages need your review.')).toBeTruthy();
  });

  it('shows the Gmail mailbox card once for the account, not once per application', async () => {
    mocked.mockImplementation((async (path: string) => {
      if (path === '/applications') {
        return { items: [application({ id: 'app-1', track: prepared }), application({ id: 'app-2', jobId: 'job-2', track: prepared })] };
      }
      if (path === '/track/reviews') return { items: [], mailboxConnected: false };
      if (path === '/integrations/gmail/status') return { configured: true, connected: false };
      if (path === '/managed-mail/address') return managedMailNotConfigured;
      if (path.startsWith('/prep/jobs/')) throw new ApiError(503, 'unavailable in this test');
      if (path.startsWith('/apply/applications/')) throw new ApiError(503, JSON.stringify({ message: 'Not loaded in this test.' }));
      throw new Error(`Unexpected API call: ${path}`);
    }) as never);
    renderPage();
    expect((await screen.findAllByText('Platform Engineer')).length).toBe(2);
    expect(screen.getAllByText('Gmail connection (read-only)')).toHaveLength(1);
    expect(await screen.findAllByRole('button', { name: /Connect Gmail \(read-only\)/ })).toHaveLength(1);
    expect(mocked.mock.calls.filter(([path]) => path === '/integrations/gmail/status')).toHaveLength(1);
  });

  it('shows the Robin-managed address card once for the account, next to the Gmail card', async () => {
    serve([application({ id: 'app-1', track: prepared }), application({ id: 'app-2', jobId: 'job-2', track: prepared })]);
    renderPage();
    expect((await screen.findAllByText('Platform Engineer')).length).toBe(2);
    expect(screen.getAllByText('Robin email address')).toHaveLength(1);
    expect(await screen.findByText(/Robin-managed email is not set up on this deployment/)).toBeTruthy();
    expect(mocked.mock.calls.filter(([path]) => path === '/managed-mail/address')).toHaveLength(1);
  });

  it('opens the tracking panel for one application on request', async () => {
    serve([application({ track: prepared })]);
    mocked.mockImplementation((async (path: string) => {
      if (path === '/applications') return { items: [application({ track: prepared })] };
      if (path === '/track/reviews') return { items: [], mailboxConnected: false };
      if (path.startsWith('/applications/app-1/track/timeline')) return { items: [], nextCursor: '', limit: 20 };
      if (path === '/applications/app-1/track') throw new ApiError(404, 'No application with that id');
      if (path === '/managed-mail/address') return managedMailNotConfigured;
      if (path.startsWith('/prep/jobs/')) throw new ApiError(503, 'unavailable in this test');
      if (path.startsWith('/apply/applications/')) throw new ApiError(503, JSON.stringify({ message: 'Not loaded in this test.' }));
      throw new Error(`Unexpected API call: ${path}`);
    }) as never);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /Show timeline and evidence/ }));
    expect(await screen.findByText('Not available to your account.')).toBeTruthy();
  });
});
