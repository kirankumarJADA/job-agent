// @vitest-environment jsdom
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { ZohoRecruitReviewPanel } from './ZohoRecruitReviewPanel';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));
const mocked = vi.mocked(apiFetch);

function renderPanel(onChanged = vi.fn()) {
  return render(React.createElement(ZohoRecruitReviewPanel, { planId: 'plan-1', onChanged }));
}

const PLAN = (result: unknown[]) => ({
  id: 'plan-1', applicationId: 'app-1', targetUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x',
  status: 'AWAITING_APPROVAL', plan: { planType: 'ZOHO_RECRUIT' }, result, submitApproved: false,
  updatedAt: '2026-01-01T00:00:00Z',
});

describe('ZohoRecruitReviewPanel', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('shows what Robin filled and what still needs the candidate, never claiming success as submission', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT',
      filled: [{ label: 'First Name *', category: 'IDENTITY_FIRST_NAME' }],
      requiredGaps: [{ label: 'Mobile *', category: 'COMPOSITE_WIDGET_PART' }],
      resumeUploadState: 'NO_ARTIFACT', resumeUploadReason: 'no reviewed résumé is available for this application',
      applicationUrl: 'https://acme.zohorecruit.com/jobs/Careers/1/x',
    }]));

    renderPanel();

    expect(await screen.findByText('First Name *')).toBeTruthy();
    expect(screen.getByText('Mobile *')).toBeTruthy();
    expect(screen.getByText(/no reviewed résumé is available/)).toBeTruthy();
    expect(screen.getByRole('link', { name: /Open the employer's application page/ })
      .getAttribute('href')).toBe('https://acme.zohorecruit.com/jobs/Careers/1/x');
    // Never implies the application was actually submitted anywhere.
    expect(screen.queryByText(/submitted/i)).toBeNull();
  });

  it('shows the résumé as attached-but-not-submitted — never a bare "uploaded" claim — only once the backend reports ATTACHED', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT',
      filled: [{ label: 'First Name *', category: 'IDENTITY_FIRST_NAME' }],
      requiredGaps: [], resumeUploadState: 'ATTACHED', resumeUploadReason: null,
    }]));

    renderPanel();

    expect(await screen.findByText(/Attached to the form — not yet submitted/)).toBeTruthy();
    expect(screen.getByText('Nothing is outstanding — review the filled fields, then finish this on the employer\'s page.')).toBeTruthy();
    // A setInputFiles selection is never described as a completed "upload".
    expect(screen.queryByText(/^Uploaded automatically/)).toBeNull();
  });

  it('shows the specific reason a résumé could not be attached, never a bare failure', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT',
      filled: [], requiredGaps: [{ label: 'Resume *', category: 'RESUME_UPLOAD' }],
      resumeUploadState: 'REJECTED', resumeUploadReason: 'the file no longer matches its reviewed checksum',
    }]));

    renderPanel();

    expect(await screen.findByText(/the file no longer matches its reviewed checksum/)).toBeTruthy();
  });

  it('shows that this form does not ask for a cover letter when no such field was found', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT',
      filled: [], requiredGaps: [], resumeUploadState: 'NO_FIELD', resumeUploadReason: null,
      coverLetterUploadState: 'NO_FIELD', coverLetterUploadReason: null,
    }]));

    renderPanel();

    expect(await screen.findByText(/does not ask for a cover letter/)).toBeTruthy();
  });

  it('shows the cover letter as attached-but-not-submitted only once the backend reports ATTACHED', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT',
      filled: [{ label: 'Cover Letter', category: 'COVER_LETTER_UPLOAD' }], requiredGaps: [],
      resumeUploadState: 'NO_FIELD', resumeUploadReason: null,
      coverLetterUploadState: 'ATTACHED', coverLetterUploadReason: null,
    }]));

    renderPanel();

    expect((await screen.findAllByText(/Attached to the form — not yet submitted/)).length).toBeGreaterThan(0);
  });

  it('shows the specific reason a cover letter could not be attached, never a bare failure', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT',
      filled: [], requiredGaps: [{ label: 'Cover Letter *', category: 'COVER_LETTER_UPLOAD' }],
      resumeUploadState: 'NO_FIELD', resumeUploadReason: null,
      coverLetterUploadState: 'NO_ARTIFACT',
      coverLetterUploadReason: 'no approved cover letter is available for this application',
    }]));

    renderPanel();

    expect(await screen.findByText(/no approved cover letter is available for this application/)).toBeTruthy();
  });

  it('records a review acknowledgement via the generic review endpoint, not an approval call', async () => {
    mocked.mockImplementation((url: string) => {
      if (url === '/automation/plans/plan-1') return Promise.resolve(PLAN([{
        type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT', filled: [], requiredGaps: [],
        resumeUploadState: 'NO_FIELD',
      }]));
      return Promise.resolve(undefined);
    });
    const onChanged = vi.fn();
    renderPanel(onChanged);

    fireEvent.click(await screen.findByRole('button', { name: /I finished this on the employer's site/ }));

    expect(await screen.findByRole('button', { name: /Marked as reviewed/ })).toBeTruthy();
    expect(mocked).toHaveBeenCalledWith('/automation/plans/plan-1/review', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ acknowledge: true }),
    }));
    expect(mocked).not.toHaveBeenCalledWith('/automation/plans/plan-1/approve-submit', expect.anything());
    expect(onChanged).toHaveBeenCalled();
  });

  it('shows an honest error, never a fabricated outcome, when the plan cannot be loaded', async () => {
    mocked.mockRejectedValue(new Error('503'));
    renderPanel();
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(/Filled automatically/)).toBeNull();
  });

  it('shows a preparing state, never a stale outcome, for a requeued plan whose result was cleared', async () => {
    // The exact post-retry shape: requeueFailedPlan moved the plan back to
    // PREPARED and cleared its stale result in the same atomic update — the
    // panel must show "preparing", never resurface the failed attempt's
    // earlier outcome or pill.
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
        type: 'ZOHO_RECRUIT_OUTCOME', planType: 'ZOHO_RECRUIT',
        filled: [], requiredGaps: [], resumeUploadState: 'NO_FIELD',
      }]),
      status: 'FAILED',
      result: [{ type: 'PLAN_ERROR', outcome: 'FAILED', reason: null, message: 'lost the browser lease' }],
    });

    renderPanel();

    expect(await screen.findByText(/Robin stopped before finishing this form/)).toBeTruthy();
    expect(screen.getByText(/lost the browser lease/)).toBeTruthy();
    // The stopped-state view takes over entirely — never the "needs review" UI for a plan with nothing to review yet.
    expect(screen.queryByText(/Filled automatically/)).toBeNull();
  });

  it('shows a distinct blocked state for an anti-bot stop, naming the CAPTCHA explicitly', async () => {
    mocked.mockResolvedValue({ ...PLAN([]), status: 'BLOCKED_ANTI_BOT' });
    renderPanel();
    expect((await screen.findAllByText(/CAPTCHA or anti-bot check/)).length).toBeGreaterThan(0);
  });

  it('retries preparation through the real re-prepare endpoint, keyed by applicationId, not a mock state', async () => {
    mocked.mockImplementation((url: string) => {
      if (url === '/automation/plans/plan-1') return Promise.resolve({ ...PLAN([]), status: 'FAILED' });
      return Promise.resolve({ status: 'REPREPARATION_QUEUED' });
    });
    const onChanged = vi.fn();
    renderPanel(onChanged);

    fireEvent.click(await screen.findByRole('button', { name: /Retry preparation/ }));

    expect(await screen.findByRole('button', { name: /Retry queued/ })).toBeTruthy();
    expect(mocked).toHaveBeenCalledWith('/applications/app-1/re-prepare', expect.objectContaining({ method: 'POST' }));
    expect(onChanged).toHaveBeenCalled();
  });
});
