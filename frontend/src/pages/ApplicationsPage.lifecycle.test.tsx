// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
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

function makeApp(overrides: Partial<ApplicationSummary> = {}): ApplicationSummary {
  return {
    id: 'app-1',
    jobId: 'job-1',
    status: 'READY_TO_APPLY',
    mode: 'ASSISTED',
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    jobTitle: 'Backend Engineer',
    company: 'TestCorp',
    jobLocation: 'London',
    matchScore: 85,
    matchRecommendation: 'APPLY',
    planId: 'plan-1',
    planStatus: 'AWAITING_APPROVAL',
    planType: 'GREENHOUSE',
    ...overrides,
  };
}

describe('ApplicationsPage — Cross-ATS Lifecycle Consistency', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => {
    cleanup();
  });

  const setupDefaultMocks = (items: ApplicationSummary[]) => {
    mocked.mockImplementation((async (path: string) => {
      if (path === '/applications') return { items };
      if (path === '/track/reviews') return { items: [], mailboxConnected: false };
      if (path === '/managed-mail/address') return { readiness: 'NOT_CONFIGURED', usableForRegistration: false };
      if (path.startsWith('/automation/plans/')) {
        return {
          id: 'plan-1',
          applicationId: 'app-1',
          targetUrl: 'https://example.com/apply',
          status: 'AWAITING_APPROVAL',
          result: [],
          submitApproved: false,
          updatedAt: '2026-10-01T09:00:00Z',
        };
      }
      if (path.startsWith('/apply/applications/')) {
        return {
          availability: 'AVAILABLE',
          application: { applicationId: 'app-1', jobId: 'job-1', jobTitle: 'Eng', company: 'Corp', status: 'READY_TO_APPLY' },
          documents: { cv: { versionId: 'v1' }, coverLetter: null, coverLetterRequirement: 'OPTIONAL' },
          questions: { formCaptured: true, items: [] },
          duplicates: { status: 'NONE', items: [] },
          decision: { applicationMode: 'ASSISTED', ruleAvailability: 'CONFIGURED' },
          blockers: [],
          warnings: [],
          unknowns: [],
          packageReady: true,
          packagePreview: { documents: { cv: 'v1' }, coverLetterRequirement: 'OPTIONAL', note: 'test' },
        };
      }
      throw new Error(`Unexpected path: ${path}`);
    }) as never);
  };

  it('renders submission approval buttons for GREENHOUSE plans', async () => {
    setupDefaultMocks([makeApp({ planType: 'GREENHOUSE', planStatus: 'AWAITING_APPROVAL' })]);
    render(
      <MemoryRouter>
        <ApplicationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /Approve for submission/ }).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /I reviewed the form/ })).toBeTruthy();
  });

  it('renders submission approval buttons for ASHBY plans', async () => {
    setupDefaultMocks([makeApp({ planType: 'ASHBY', planStatus: 'AWAITING_APPROVAL' })]);
    render(
      <MemoryRouter>
        <ApplicationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /Approve for submission/ }).length).toBeGreaterThan(0);
  });

  it('suppresses submission approval controls for LEVER plans', async () => {
    setupDefaultMocks([makeApp({ planType: 'LEVER', planStatus: 'AWAITING_APPROVAL' })]);
    render(
      <MemoryRouter>
        <ApplicationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Approve for submission/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
  });

  it('suppresses submission approval controls for ZOHO_RECRUIT plans', async () => {
    setupDefaultMocks([makeApp({ planType: 'ZOHO_RECRUIT', planStatus: 'AWAITING_APPROVAL' })]);
    render(
      <MemoryRouter>
        <ApplicationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Approve for submission/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
  });

  it('suppresses submission approval controls for WORKDAY plans', async () => {
    setupDefaultMocks([makeApp({ planType: 'WORKDAY', planStatus: 'AWAITING_APPROVAL' })]);
    render(
      <MemoryRouter>
        <ApplicationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Approve for submission/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
  });

  it('renders form inspection card for INSPECTION plans without submit controls', async () => {
    setupDefaultMocks([makeApp({ planType: 'INSPECTION', planStatus: 'COMPLETED' })]);
    render(
      <MemoryRouter>
        <ApplicationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    expect(screen.getByText('Form inspection')).toBeTruthy();
    expect(screen.getByText(/Inspection complete — this employer requires manual application/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Approve for submission/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
  });

  it('fails closed when planType is null, undefined or unknown, never exposing submission controls', async () => {
    setupDefaultMocks([
      makeApp({ id: 'app-null', planId: 'plan-null', planType: null, planStatus: 'AWAITING_APPROVAL' }),
      makeApp({ id: 'app-undef', planId: 'plan-undef', planType: undefined, planStatus: 'AWAITING_APPROVAL' }),
      makeApp({ id: 'app-unk', planId: 'plan-unk', planType: 'CUSTOM_UNKNOWN', planStatus: 'AWAITING_APPROVAL' }),
    ]);
    render(
      <MemoryRouter>
        <ApplicationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findAllByText('Backend Engineer')).toBeTruthy();
    // None of these unknown/missing plan types should ever expose submission approval or queuing
    expect(screen.queryByRole('button', { name: /Approve for submission/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Submit to employer now/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /I reviewed the form/ })).toBeNull();
  });

  it('extracts clear human error message when reconciliation returns 409', async () => {
    const defaultHandler = (path: string) => {
      if (path === '/applications') return Promise.resolve({ items: [makeApp({ planType: 'GREENHOUSE', planStatus: 'RECONCILIATION_REQUIRED' })] });
      if (path === '/track/reviews') return Promise.resolve({ items: [], mailboxConnected: false });
      if (path === '/managed-mail/address') return Promise.resolve({ readiness: 'NOT_CONFIGURED', usableForRegistration: false });
      if (path.startsWith('/automation/plans/')) {
        return Promise.resolve({
          id: 'plan-1',
          applicationId: 'app-1',
          targetUrl: 'https://example.com/apply',
          status: 'RECONCILIATION_REQUIRED',
          result: [],
          submitApproved: false,
          updatedAt: '2026-10-01T09:00:00Z',
        });
      }
      if (path.startsWith('/apply/applications/')) {
        return Promise.resolve({
          availability: 'AVAILABLE',
          application: { applicationId: 'app-1', jobId: 'job-1', jobTitle: 'Eng', company: 'Corp', status: 'READY_TO_APPLY' },
          documents: { cv: { versionId: 'v1' }, coverLetter: null, coverLetterRequirement: 'OPTIONAL' },
          questions: { formCaptured: true, items: [] },
          duplicates: { status: 'NONE', items: [] },
          decision: { applicationMode: 'ASSISTED', ruleAvailability: 'CONFIGURED' },
          blockers: [],
          warnings: [],
          unknowns: [],
          packageReady: true,
          packagePreview: { documents: { cv: 'v1' }, coverLetterRequirement: 'OPTIONAL', note: 'test' },
        });
      }
      return Promise.reject(new Error(`Unexpected path: ${path}`));
    };

    mocked.mockImplementation(((path: string, options?: RequestInit) => {
      if (path === '/automation/plans/plan-1/reconcile' && options?.method === 'POST') {
        return Promise.reject(new Error(JSON.stringify({
          error: 'reconciliation cannot create a new attempt without platform-confirmed evidence that the employer rejected the previous one',
          code: 'RECONCILIATION_EVIDENCE_INSUFFICIENT',
          category: 'UNKNOWN',
        })));
      }
      return defaultHandler(path);
    }) as never);

    render(
      <MemoryRouter>
        <ApplicationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Backend Engineer')).toBeTruthy();
    const reconcileBtn = screen.getByRole('button', { name: /Reconcile & prepare retry/ });

    fireEvent.click(reconcileBtn);

    expect(await screen.findByText('reconciliation cannot create a new attempt without platform-confirmed evidence that the employer rejected the previous one')).toBeTruthy();
    // Raw JSON brackets should not be shown to user
    expect(screen.queryByText(/RECONCILIATION_EVIDENCE_INSUFFICIENT/)).toBeNull();
  });
});
