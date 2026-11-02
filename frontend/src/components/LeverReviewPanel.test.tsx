// @vitest-environment jsdom
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { LeverReviewPanel } from './LeverReviewPanel';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));
const mocked = vi.mocked(apiFetch);

function renderPanel(onChanged = vi.fn()) {
  return render(React.createElement(LeverReviewPanel, { planId: 'plan-1', onChanged }));
}

const PLAN = (result: unknown[], status = 'AWAITING_APPROVAL') => ({
  id: 'plan-1', applicationId: 'app-1', targetUrl: 'https://jobs.lever.co/acme/x/apply',
  status, plan: { planType: 'LEVER' }, result, submitApproved: false,
  updatedAt: '2026-01-01T00:00:00Z',
});

describe('LeverReviewPanel', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('shows what Robin filled and what still needs the candidate, never claiming success as submission', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'LEVER_OUTCOME', planType: 'LEVER',
      filled: [{ key: 'name', label: 'Name' }, { key: 'email', label: 'Email' }],
      requiredGaps: [{ key: 'phone', label: 'Phone' }],
      resumeUploadState: 'NO_ARTIFACT', resumeUploadReason: 'no reviewed résumé is available for this application',
      applicationUrl: 'https://jobs.lever.co/acme/x/apply',
    }]));

    renderPanel();

    expect(await screen.findByText('Name')).toBeTruthy();
    expect(screen.getByText('Phone')).toBeTruthy();
    expect(screen.getByText(/no reviewed résumé is available/)).toBeTruthy();
    expect(screen.getByRole('link', { name: /Open the employer's application page/ })
      .getAttribute('href')).toBe('https://jobs.lever.co/acme/x/apply');
    expect(screen.queryByText(/submitted/i)).toBeNull();
  });

  it('shows the résumé as attached-but-not-submitted, only once the backend reports ATTACHED', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'LEVER_OUTCOME', planType: 'LEVER',
      filled: [{ key: 'name', label: 'Name' }],
      requiredGaps: [], resumeUploadState: 'ATTACHED', resumeUploadReason: null,
    }]));

    renderPanel();

    expect(await screen.findByText(/Attached to the form — not yet submitted/)).toBeTruthy();
    expect(screen.getByText(/Nothing is outstanding/)).toBeTruthy();
  });

  it('shows a stopped preparation (CAPTCHA/anti-bot) with the real reason and a retry action, not a bare failure', async () => {
    mocked.mockResolvedValue(PLAN(
      [{ type: 'PLAN_ERROR', outcome: 'BLOCKED_ANTI_BOT', reason: 'LEVER_CAPTCHA_PRESENT', message: 'a CAPTCHA was present on the form' }],
      'BLOCKED_ANTI_BOT',
    ));

    renderPanel();

    expect(await screen.findByText(/CAPTCHA, a login wall, or an anti-bot check/)).toBeTruthy();
    expect(screen.getByText(/a CAPTCHA was present on the form/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry preparation' })).toBeTruthy();
  });

  it('retry preparation calls the existing generic re-prepare endpoint, not a Lever-specific one', async () => {
    mocked.mockResolvedValueOnce(PLAN([], 'FAILED')).mockResolvedValueOnce({ status: 'queued' });
    renderPanel();
    await screen.findByText('Robin stopped before finishing this form.');

    fireEvent.click(screen.getByRole('button', { name: 'Retry preparation' }));

    await screen.findByText('Retry queued');
    expect(mocked).toHaveBeenCalledWith('/applications/app-1/re-prepare', { method: 'POST' });
  });

  it('marking as reviewed calls the existing generic review endpoint and never a submit-sounding one', async () => {
    mocked.mockResolvedValueOnce(PLAN([{
      type: 'LEVER_OUTCOME', planType: 'LEVER', filled: [], requiredGaps: [], resumeUploadState: 'NO_FIELD',
    }])).mockResolvedValueOnce({ acknowledged: true });
    renderPanel();
    await screen.findByText("I finished this on the employer's site");

    fireEvent.click(screen.getByRole('button', { name: "I finished this on the employer's site" }));

    await screen.findByText('Marked as reviewed');
    expect(mocked).toHaveBeenCalledWith('/automation/plans/plan-1/review', { method: 'POST', body: JSON.stringify({ acknowledge: true }) });
  });

  it('a plan with no outcome yet shows a waiting state, never a fabricated one', async () => {
    mocked.mockResolvedValue(PLAN([]));
    renderPanel();
    expect(await screen.findByText(/has not yet reported what it found/)).toBeTruthy();
  });
});
