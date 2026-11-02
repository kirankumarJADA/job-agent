// @vitest-environment jsdom
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { AshbyReviewPanel } from './AshbyReviewPanel';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));
const mocked = vi.mocked(apiFetch);

function renderPanel(planStatus: string | null = null, onChanged = vi.fn()) {
  return render(React.createElement(AshbyReviewPanel, { planId: 'plan-1', planStatus, onChanged }));
}

const PLAN = (result: unknown[], status = 'AWAITING_APPROVAL') => ({
  id: 'plan-1', applicationId: 'app-1', targetUrl: 'https://jobs.ashbyhq.com/ramp/x/application',
  status, plan: { planType: 'ASHBY' }, result, submitApproved: false,
  updatedAt: '2026-01-01T00:00:00Z',
});

describe('AshbyReviewPanel', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('shows what Robin filled and what still needs the candidate, never claiming success as submission', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ASHBY_OUTCOME', planType: 'ASHBY',
      filled: [{ label: 'Legal Name', category: 'IDENTITY_NAME' }],
      requiredGaps: [{ label: 'Where do you plan on working from?', category: 'REQUIRES_HUMAN' }],
      resumeUploadState: 'NO_ARTIFACT', resumeUploadReason: 'no reviewed résumé is available for this application',
      applicationUrl: 'https://jobs.ashbyhq.com/ramp/x/application',
    }]));

    renderPanel();

    expect(await screen.findByText('Legal Name')).toBeTruthy();
    expect(screen.getByText('Where do you plan on working from?')).toBeTruthy();
    expect(screen.getByText(/no reviewed résumé is available/)).toBeTruthy();
    expect(screen.getByRole('link', { name: /Open the employer's application page/ })
      .getAttribute('href')).toBe('https://jobs.ashbyhq.com/ramp/x/application');
    // Never implies the application was actually submitted anywhere.
    expect(screen.queryByText(/^submitted$/i)).toBeNull();
  });

  it('shows the résumé as attached-but-not-submitted — never a bare "uploaded" claim — only once the backend reports ATTACHED', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ASHBY_OUTCOME', planType: 'ASHBY',
      filled: [{ label: 'Legal Name', category: 'IDENTITY_NAME' }],
      requiredGaps: [], resumeUploadState: 'ATTACHED', resumeUploadReason: null,
    }]));

    renderPanel();

    expect(await screen.findByText(/Attached to the form — not yet submitted to the employer/)).toBeTruthy();
    expect(screen.getByText('Nothing is outstanding.')).toBeTruthy();
    // When there are no gaps, the panel points to approval/authorization, not a claim of submission.
    expect(await screen.findByText(/authorize Robin to submit this application directly on Ashby/)).toBeTruthy();
  });

  it('shows the specific reason a résumé could not be attached, never a bare failure', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ASHBY_OUTCOME', planType: 'ASHBY',
      filled: [], requiredGaps: [{ label: 'Resume', category: 'RESUME_UPLOAD' }],
      resumeUploadState: 'REJECTED', resumeUploadReason: 'the file no longer matches its reviewed checksum',
    }]));

    renderPanel();

    expect(await screen.findByText(/the file no longer matches its reviewed checksum/)).toBeTruthy();
  });

  it('points to resolving outstanding fields first when required gaps remain, never implying submission is ready', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ASHBY_OUTCOME', planType: 'ASHBY',
      filled: [], requiredGaps: [{ label: 'Phone', category: 'IDENTITY_PHONE' }],
      resumeUploadState: 'NO_FIELD', resumeUploadReason: null,
    }]));

    renderPanel();

    expect(await screen.findByText(/Resolve the outstanding fields above/)).toBeTruthy();
  });

  it('never renders its own submit or review-acknowledgement button — the generic automation panel owns those actions', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ASHBY_OUTCOME', planType: 'ASHBY', filled: [], requiredGaps: [], resumeUploadState: 'NO_FIELD',
    }]));

    renderPanel();

    await screen.findByText('Nothing was auto-filled.');
    expect(screen.queryByRole('button', { name: /submit/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /I finished this on the employer's site/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /I reviewed the form/i })).toBeNull();
  });

  it('shows a distinct Submitted pill when the plan status is SUBMITTED, driven by the shared plan status, never invented locally', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ASHBY_OUTCOME', planType: 'ASHBY', filled: [], requiredGaps: [], resumeUploadState: 'ATTACHED',
    }], 'SUBMITTED'));

    renderPanel('SUBMITTED');

    expect(await screen.findByText('Submitted')).toBeTruthy();
  });

  it('shows an honest error, never a fabricated outcome, when the plan cannot be loaded', async () => {
    mocked.mockRejectedValue(new Error('503'));
    renderPanel();
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(/Filled automatically/)).toBeNull();
  });

  it('shows a preparing state, never a stale outcome, for a requeued plan whose result was cleared', async () => {
    mocked.mockResolvedValue({ ...PLAN([]), status: 'PREPARED' });
    renderPanel();
    expect(await screen.findByText(/has not yet reported what it found/)).toBeTruthy();
    expect(screen.queryByText(/Preparation failed/)).toBeNull();
    expect(screen.queryByText(/Filled automatically/)).toBeNull();
  });

  it('reports that the worker has not finished yet rather than guessing an outcome', async () => {
    mocked.mockResolvedValue(PLAN([]));
    renderPanel();
    expect(await screen.findByText(/has not yet reported what it found/)).toBeTruthy();
  });

  it('shows a distinct failed state with the real reason instead of the review UI', async () => {
    mocked.mockResolvedValue({
      ...PLAN([{
        type: 'ASHBY_OUTCOME', planType: 'ASHBY', filled: [], requiredGaps: [], resumeUploadState: 'NO_FIELD',
      }], 'FAILED'),
      result: [{ type: 'PLAN_ERROR', outcome: 'FAILED', reason: null, message: 'lost the browser lease' }],
    });

    renderPanel('FAILED');

    expect(await screen.findByText(/Robin stopped before finishing this form/)).toBeTruthy();
    expect(screen.getByText(/lost the browser lease/)).toBeTruthy();
    expect(screen.queryByText(/Filled automatically/)).toBeNull();
  });

  it('shows a distinct blocked state for an anti-bot stop, naming the CAPTCHA explicitly', async () => {
    mocked.mockResolvedValue({ ...PLAN([], 'BLOCKED_ANTI_BOT') });
    renderPanel('BLOCKED_ANTI_BOT');
    expect((await screen.findAllByText(/CAPTCHA or anti-bot check/)).length).toBeGreaterThan(0);
  });

  it('retries preparation through the real re-prepare endpoint, keyed by applicationId, not a mock state', async () => {
    mocked.mockImplementation((url: string) => {
      if (url === '/automation/plans/plan-1') return Promise.resolve({ ...PLAN([], 'FAILED') });
      return Promise.resolve({ status: 'REPREPARATION_QUEUED' });
    });
    const onChanged = vi.fn();
    renderPanel('FAILED', onChanged);

    fireEvent.click(await screen.findByRole('button', { name: /Retry preparation/ }));

    expect(await screen.findByRole('button', { name: /Retry queued/ })).toBeTruthy();
    expect(mocked).toHaveBeenCalledWith('/applications/app-1/re-prepare', expect.objectContaining({ method: 'POST' }));
    expect(onChanged).toHaveBeenCalled();
  });

  it('shows a distinct Reconciliation required pill and evidence when plan is RECONCILIATION_REQUIRED', async () => {
    mocked.mockResolvedValue({
      ...PLAN([], 'RECONCILIATION_REQUIRED'),
      result: [{
        type: 'SUBMISSION_RECONCILIATION',
        planType: 'ASHBY',
        category: 'SUBMISSION_REJECTED',
        evidence: "Ashby's own submission response reported success:false",
      }],
    });
    renderPanel('RECONCILIATION_REQUIRED');
    expect(await screen.findByText('Reconciliation required')).toBeTruthy();
    expect(screen.getByText(/acceptance could not be confirmed/)).toBeTruthy();
    expect(screen.getByText(/SUBMISSION_REJECTED/)).toBeTruthy();
  });
});
