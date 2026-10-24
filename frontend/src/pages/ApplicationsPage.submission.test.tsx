// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { apiFetch, ApiError } from '../api/client';
import type { ApplicationSummary } from '../types';
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

const managedMailNotConfigured = { readiness: 'NOT_CONFIGURED', usableForRegistration: false, missing: ['APP_MANAGED_MAIL_DOMAIN'] };

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
    planId: 'plan-1',
    planStatus: null,
    planType: 'GREENHOUSE',
    ...overrides,
  };
}

function serveBaseline(items: ApplicationSummary[]) {
  mocked.mockImplementation((async (path: string, options?: { method?: string }) => {
    if (path === '/applications') return { items };
    if (path === '/track/reviews') return { items: [], mailboxConnected: false };
    if (path === '/managed-mail/address') return managedMailNotConfigured;
    if (path.startsWith('/prep/jobs/')) throw new ApiError(503, 'unavailable in this test');
    if (path.startsWith('/apply/applications/')) throw new ApiError(503, JSON.stringify({ message: 'Not loaded in this test.' }));
    if (path.endsWith('/package')) throw new ApiError(503, 'unavailable in this test');
    if (options?.method === 'POST' && path.startsWith('/automation/plans/')) {
      throw new Error(`Unexpected POST in this test: ${path}`);
    }
    throw new Error(`Unexpected API call: ${path}`);
  }) as never);
}

function renderPage() {
  return render(
    <MemoryRouter>
      <ApplicationsPage />
    </MemoryRouter>,
  );
}

/**
 * Phase 1 (real Greenhouse submission): the one, deliberately separate human
 * action that actually queues a real submission click, distinct from
 * approval alone. Covers the exact lifecycle copy a candidate must be able
 * to tell apart: approved-but-not-submitted, queued, in flight, and
 * confirmed submitted.
 */
describe('Applications page — real submission queuing', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('offers "Submit to employer now" only once a plan is READY_TO_SUBMIT, and queues it on click', async () => {
    let planStatus = 'READY_TO_SUBMIT';
    let queued = false;
    mocked.mockImplementation((async (path: string, options?: { method?: string }) => {
      if (path === '/applications') return { items: [application({ planStatus })] };
      if (path === '/track/reviews') return { items: [], mailboxConnected: false };
      if (path === '/managed-mail/address') return managedMailNotConfigured;
      if (path.startsWith('/prep/jobs/')) throw new ApiError(503, 'unavailable in this test');
      if (path.startsWith('/apply/applications/')) throw new ApiError(503, JSON.stringify({ message: 'Not loaded in this test.' }));
      if (path === '/automation/plans/plan-1/package') throw new ApiError(503, 'unavailable in this test');
      if (path === '/automation/plans/plan-1/prepare-submit' && options?.method === 'POST') {
        queued = true;
        planStatus = 'PREPARED';
        return { status: 'PREPARED', submissionQueued: true };
      }
      throw new Error(`Unexpected API call: ${path}`);
    }) as never);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    const button = within(card).getByRole('button', { name: /Submit to employer now/ });

    fireEvent.click(button);

    // fetchApplications() flips loading=true/false around the refetch, which
    // unmounts and remounts the applications grid — re-find the card fresh
    // from the document rather than reusing the pre-click (now stale) node.
    await screen.findByText(/Real submission queued/);
    const refreshedCard = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(refreshedCard).getByText(/Real submission queued/)).toBeTruthy();
    expect(queued).toBe(true);
  });

  it('shows the server refusal verbatim and never a false success when queuing is refused', async () => {
    serveBaseline([application({ planStatus: 'READY_TO_SUBMIT' })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    const button = within(card).getByRole('button', { name: /Submit to employer now/ });

    mocked.mockImplementationOnce((async () => {
      throw new ApiError(409, JSON.stringify({ error: 'package drift detected since approval' }));
    }) as never);

    fireEvent.click(button);

    expect(await screen.findByText(/package drift detected since approval/)).toBeTruthy();
    // Status must not have silently advanced past READY_TO_SUBMIT.
    expect(within(card).getByRole('button', { name: /Submit to employer now/ })).toBeTruthy();
  });

  it('never offers the submit action before approval (AWAITING_APPROVAL shows only the approval actions)', async () => {
    serveBaseline([application({ planStatus: 'AWAITING_APPROVAL' })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(card).getByRole('button', { name: /Approve for submission/ })).toBeTruthy();
    expect(within(card).queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
  });

  it('shows a queued, waiting-for-worker state for PREPARED with no action button', async () => {
    serveBaseline([application({ planStatus: 'PREPARED' })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(card).getByText(/Real submission queued — waiting for the worker/)).toBeTruthy();
    expect(within(card).queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
  });

  it('shows an in-progress, unconfirmed state for RUNNING', async () => {
    serveBaseline([application({ planStatus: 'RUNNING' })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(card).getByText(/Submitting now — outcome not yet confirmed/)).toBeTruthy();
  });

  it('shows the confirmed-submitted state for SUBMITTED, distinctly from every other state', async () => {
    serveBaseline([application({ planStatus: 'SUBMITTED' })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(card).getByText(/Confirmed submitted by the employer/)).toBeTruthy();
    expect(within(card).queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
    expect(within(card).queryByRole('button', { name: /Approve for submission/ })).toBeNull();
  });

  it('points a FAILED plan at recovery instead of a bare failure label, and never offers to submit it', async () => {
    serveBaseline([application({ planStatus: 'FAILED' })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(card).getByText(/Fix & requeue/)).toBeTruthy();
    expect(within(card).queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
  });

  it('reports an anti-bot block as a genuinely uncertain outcome, not a failure or a success', async () => {
    serveBaseline([application({ planStatus: 'BLOCKED_ANTI_BOT' })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(card).getByText(/uncertain outcome/)).toBeTruthy();
    expect(within(card).queryByText(/Confirmed submitted/)).toBeNull();
  });

  it('reports an uncertain submission outcome as RECONCILIATION_REQUIRED, never offers blind submit, and exposes reconciliation', async () => {
    let planStatus = 'RECONCILIATION_REQUIRED';
    let reconciled = false;
    mocked.mockImplementation((async (path: string, options?: { method?: string }) => {
      if (path === '/applications') return { items: [application({ planStatus })] };
      if (path === '/track/reviews') return { items: [], mailboxConnected: false };
      if (path === '/managed-mail/address') return managedMailNotConfigured;
      if (path.startsWith('/prep/jobs/')) throw new ApiError(503, 'unavailable in this test');
      if (path.startsWith('/apply/applications/')) throw new ApiError(503, JSON.stringify({ message: 'Not loaded in this test.' }));
      if (path === '/automation/plans/plan-1/package') throw new ApiError(503, 'unavailable in this test');
      if (path === '/automation/plans/plan-1/reconcile' && options?.method === 'POST') {
        reconciled = true;
        planStatus = 'PREPARED';
        return { status: 'RECONCILIATION_RETRY_CREATED', supersededPlanId: 'plan-1', newPlanId: 'plan-2' };
      }
      throw new Error(`Unexpected API call: ${path}`);
    }) as never);

    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    expect(within(card).getByText(/Submission unconfirmed — human reconciliation required/)).toBeTruthy();
    expect(within(card).queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
    expect(within(card).queryByRole('button', { name: /Approve for submission/ })).toBeNull();

    const button = within(card).getByRole('button', { name: /Reconcile & prepare retry/ });
    fireEvent.click(button);

    await screen.findByText(/Real submission queued/);
    expect(reconciled).toBe(true);
  });

  it('shows the server refusal verbatim when reconciliation evidence is insufficient', async () => {
    serveBaseline([application({ planStatus: 'RECONCILIATION_REQUIRED' })]);
    renderPage();
    const card = (await screen.findByText('Platform Engineer')).closest('section') as HTMLElement;
    const button = within(card).getByRole('button', { name: /Reconcile & prepare retry/ });

    mocked.mockImplementationOnce((async () => {
      throw new ApiError(409, JSON.stringify({ error: 'this attempt\'s outcome cannot be safely resolved automatically' }));
    }) as never);

    fireEvent.click(button);

    expect(await screen.findByText(/this attempt's outcome cannot be safely resolved automatically/)).toBeTruthy();
    expect(within(card).getByRole('button', { name: /Reconcile & prepare retry/ })).toBeTruthy();
  });
});
