// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { apiFetch } from '../api/client';
import type { AutomationPlanView } from '../types';
import { UniversalReviewPanel } from './UniversalReviewPanel';

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(),
}));

const mocked = vi.mocked(apiFetch);

function makePlan(overrides: Partial<AutomationPlanView> = {}): AutomationPlanView {
  return {
    id: 'plan-u-1',
    applicationId: 'app-u-1',
    targetUrl: 'https://careers.acme.com/jobs/1/apply',
    status: 'AWAITING_APPROVAL',
    submitApproved: false,
    updatedAt: '2026-11-26T10:00:00Z',
    result: [
      {
        type: 'UNIVERSAL_OUTCOME',
        planType: 'UNIVERSAL',
        filled: ['First Name', 'Email'],
        requiredGaps: [],
        resumeUploadState: 'ATTACHED',
        coverLetterUploadState: 'NO_FIELD',
        pageIndex: 2,
        completedPages: [1],
        closedShadowHosts: ['x-closed-captcha'],
        validationErrors: [],
        blockedResources: [{ url: 'http://169.254.169.254/latest', resourceType: 'script', reason: 'BLOCKED_PRIVATE_OR_LOCAL_HOST' }],
        loginSucceeded: true,
      },
    ],
    ...overrides,
  };
}

describe('UniversalReviewPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => {
    cleanup();
  });

  it('renders execution details, closed shadow DOM notice, redacted blocked resource, and upload states', async () => {
    mocked.mockResolvedValueOnce(makePlan());
    render(<UniversalReviewPanel planId="plan-u-1" />);

    expect(await screen.findByText(/Page 2/)).toBeTruthy();
    expect(screen.getByText('ATTACHED')).toBeTruthy();
    expect(screen.getByText(/Signed in on employer portal using your saved credential/i)).toBeTruthy();
    expect(screen.getByText(/Closed Shadow DOM <x-closed-captcha> cannot be inspected automatically/)).toBeTruthy();
    expect(screen.getByText(/169\.254\.169\.254\/latest \(BLOCKED_PRIVATE_OR_LOCAL_HOST\)/)).toBeTruthy();
  });

  it('disables Resume & retry while in-flight to prevent duplicate resume requests and displays 409 blockers verbatim', async () => {
    let rejectResume!: (err: Error) => void;
    mocked.mockResolvedValueOnce(makePlan({
      result: [{
        type: 'UNIVERSAL_OUTCOME',
        planType: 'UNIVERSAL',
        filled: ['First Name'],
        requiredGaps: [{ questionKey: 'notice', questionText: 'Notice period?', reason: 'Missing confirmed answer' }],
      }],
    }));
    mocked.mockImplementationOnce(() => new Promise((_, rej) => { rejectResume = rej; }) as never);

    render(<UniversalReviewPanel planId="plan-u-1" />);
    const resumeBtn = await screen.findByRole('button', { name: /Resume & retry form pass/ }) as HTMLButtonElement;
    fireEvent.click(resumeBtn);

    expect(resumeBtn.disabled).toBe(true);
    fireEvent.click(resumeBtn);
    expect(mocked.mock.calls.filter(([u]) => String(u).endsWith('/resume')).length).toBe(1);

    rejectResume(new Error(JSON.stringify({
      error: 'resolve remaining blockers before resuming',
      blockers: [{ message: '"Notice period?" requires candidate answer (NEEDS_USER_INPUT).' }],
    })));

    expect(await screen.findByText(/resolve remaining blockers before resuming/i)).toBeTruthy();
  });

  it('supports two-step Approve -> Queue final submission and blocks auto-retry in RECONCILIATION_REQUIRED', async () => {
    const onChanged = vi.fn();
    mocked
      .mockResolvedValueOnce(makePlan())
      .mockResolvedValueOnce({ status: 'READY_TO_SUBMIT' })
      .mockResolvedValueOnce(makePlan({ status: 'READY_TO_SUBMIT', submitApproved: true }));

    render(<UniversalReviewPanel planId="plan-u-1" onChanged={onChanged} />);
    const approveBtn = await screen.findByRole('button', { name: /Approve for submission/ });
    fireEvent.click(approveBtn);

    await waitFor(() => {
      expect(mocked).toHaveBeenCalledWith('/automation/plans/plan-u-1/approve-submit', { method: 'POST' });
    });
    expect(await screen.findByRole('button', { name: /Queue final submission/ })).toBeTruthy();
    expect(onChanged).toHaveBeenCalled();
  });

  it('resumes an active pre-submit handoff session via /handoff/{sessionId}/resume', async () => {
    mocked
      .mockResolvedValueOnce(makePlan({ status: 'HANDOFF_REQUIRED' }))
      .mockResolvedValueOnce({
        id: 'hs-1',
        planId: 'plan-u-1',
        applicationId: 'app-u-1',
        profileId: 'prof-1',
        status: 'PENDING',
        blockReason: 'EMPLOYER_SECURITY_CHECK',
        targetUrl: 'https://careers.acme.com/apply',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
      .mockResolvedValueOnce({ sessionId: 'hs-1', planId: 'plan-u-1', status: 'COMPLETED', planStatus: 'PREPARED' })
      .mockResolvedValueOnce(makePlan({ status: 'PREPARED' }));

    render(<UniversalReviewPanel planId="plan-u-1" />);
    const handoffBtn = await screen.findByRole('button', { name: /Resume automation after verification/ });
    fireEvent.click(handoffBtn);

    await waitFor(() => {
      expect(mocked).toHaveBeenCalledWith('/handoff/hs-1/resume', { method: 'POST' });
    });
  });
});
