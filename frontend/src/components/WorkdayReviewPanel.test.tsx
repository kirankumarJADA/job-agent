// @vitest-environment jsdom
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { WorkdayReviewPanel } from './WorkdayReviewPanel';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));
const mocked = vi.mocked(apiFetch);

function renderPanel(onChanged = vi.fn()) {
  return render(React.createElement(WorkdayReviewPanel, { planId: 'plan-1', onChanged }));
}

const PLAN = (result: unknown[], status = 'AWAITING_APPROVAL') => ({
  id: 'plan-1', applicationId: 'app-1', targetUrl: 'https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/x/apply',
  status, plan: { planType: 'WORKDAY' }, result, submitApproved: false,
  updatedAt: '2026-01-01T00:00:00Z',
});

describe('WorkdayReviewPanel', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks(); });

  it('tells the candidate to save a credential first, never attempting a fabricated sign-in', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: false, loginStatus: 'CREDENTIAL_REQUIRED',
      reason: null, applicationUrl: 'https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/x/apply',
    }]));

    renderPanel();

    expect(await screen.findByText(/no saved Workday credential exists/)).toBeTruthy();
    expect(screen.getByText('Credential required')).toBeTruthy();
    expect(screen.queryByText(/submitted/i)).toBeNull();
    expect(screen.queryByText(/filled/i)).toBeNull();
  });

  it('shows a verified sign-in with an explicit disclaimer that Robin never fills or submits the form', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: true, loginStatus: 'LOGIN_SUCCEEDED',
      reason: null, applicationUrl: 'https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/x/apply',
    }]));

    renderPanel();

    expect(await screen.findByText(/verified against the employer's real sign-in page/)).toBeTruthy();
    expect(screen.getByText(/does not fill out or submit the Workday application form/)).toBeTruthy();
    expect(screen.getByText('Sign-in verified')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Open the employer's application page/ }).getAttribute('href'))
      .toBe('https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/x/apply');
  });

  it('shows a genuine login failure with the real reason, never a fabricated success', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: true, loginStatus: 'LOGIN_FAILED',
      reason: 'the sign-in form is still present after submitting — sign-in did not succeed', applicationUrl: null,
    }]));

    renderPanel();

    expect(await screen.findByText('Sign-in did not succeed.')).toBeTruthy();
    expect(screen.getByText(/sign-in form is still present after submitting/)).toBeTruthy();
  });

  it('shows a stopped preparation (MFA/CAPTCHA) with the real reason and a retry action, not a bare failure', async () => {
    mocked.mockResolvedValue(PLAN(
      [{ type: 'PLAN_ERROR', outcome: 'BLOCKED_ANTI_BOT', reason: 'WORKDAY_MFA_REQUIRED', message: 'a one-time verification code was required' }],
      'BLOCKED_ANTI_BOT',
    ));

    renderPanel();

    expect(await screen.findByText(/CAPTCHA or a one-time verification code \(MFA\)/)).toBeTruthy();
    expect(screen.getByText(/a one-time verification code was required/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry preparation' })).toBeTruthy();
  });

  it('retry preparation calls the existing generic re-prepare endpoint, not a Workday-specific one', async () => {
    mocked.mockResolvedValueOnce(PLAN([], 'FAILED')).mockResolvedValueOnce({ status: 'queued' });
    renderPanel();
    await screen.findByText('Robin stopped before finishing this sign-in check.');

    fireEvent.click(screen.getByRole('button', { name: 'Retry preparation' }));

    await screen.findByText('Retry queued');
    expect(mocked).toHaveBeenCalledWith('/applications/app-1/re-prepare', { method: 'POST' });
  });

  it('a plan with no outcome yet shows a waiting state, never a fabricated one', async () => {
    mocked.mockResolvedValue(PLAN([]));
    renderPanel();
    expect(await screen.findByText(/has not yet reported what it found/)).toBeTruthy();
  });

  it('never implies submission happened for any outcome', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: true, loginStatus: 'LOGIN_SUCCEEDED',
      reason: null, applicationUrl: null,
    }]));
    renderPanel();
    await screen.findByText('Sign-in verified');
    expect(screen.queryByText(/submitted/i)).toBeNull();
  });

  it('the "let Robin create this account" button stays disabled until the candidate explicitly ticks the authorization checkbox', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: false, loginStatus: 'CREDENTIAL_REQUIRED',
      reason: null, applicationUrl: null,
    }]));
    renderPanel();
    await screen.findByText('Credential required');

    const button = screen.getByRole('button', { name: 'Let Robin create this account' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);

    fireEvent.click(screen.getByRole('checkbox'));
    expect(button.disabled).toBe(false);
  });

  it('account creation only fires after the checkbox is ticked, and posts explicit authorization — never a default', async () => {
    mocked.mockResolvedValueOnce(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: false, loginStatus: 'CREDENTIAL_REQUIRED',
      reason: null, applicationUrl: null,
    }])).mockResolvedValueOnce({ planId: 'plan-2', sessionId: 'session-1', reusedExisting: false, status: 'PREPARED' });
    renderPanel();
    await screen.findByText('Credential required');

    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: 'Let Robin create this account' }));

    await screen.findByText('Account creation requested');
    expect(mocked).toHaveBeenCalledWith('/apply/applications/app-1/workday-account',
      { method: 'POST', body: JSON.stringify({ acceptWorkdayTerms: true }) });
  });

  it('shows a successful account creation and polls for a verification message, never implying it is already verified', async () => {
    mocked.mockResolvedValueOnce(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: false, accountCreationAttempted: true,
      loginStatus: 'ACCOUNT_CREATED', reason: null, applicationUrl: null,
    }])).mockResolvedValueOnce({ status: 'VERIFICATION_MESSAGE_PENDING', provider: null, receivedAt: null, detail: null });
    renderPanel();

    expect(await screen.findByText(/Robin created a new Workday account/)).toBeTruthy();
    expect(await screen.findByText(/No verification message has been found yet/)).toBeTruthy();
    expect(screen.getByText('Account created')).toBeTruthy();
    expect(screen.queryByText(/verified$/i)).toBeNull();
    expect(mocked).toHaveBeenCalledWith('/apply/applications/app-1/workday-account/verification-status');
  });

  it('shows a matched verification message with its provider and timestamp, never an OTP or link', async () => {
    const receivedAt = '2026-01-02T03:04:00Z';
    mocked.mockResolvedValueOnce(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: false, accountCreationAttempted: true,
      loginStatus: 'ACCOUNT_CREATED', reason: null, applicationUrl: null,
    }])).mockResolvedValueOnce({ status: 'VERIFICATION_MESSAGE_MATCHED', provider: 'robin-managed', receivedAt, detail: null });
    renderPanel();

    expect(await screen.findByText(/A verification message from this employer was found/)).toBeTruthy();
    expect(screen.getByText(/Robin does not open or act on it/)).toBeTruthy();
  });

  it('"Check again" re-polls the verification-status endpoint', async () => {
    mocked.mockResolvedValueOnce(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: false, accountCreationAttempted: true,
      loginStatus: 'ACCOUNT_CREATED', reason: null, applicationUrl: null,
    }])).mockResolvedValueOnce({ status: 'VERIFICATION_MESSAGE_PENDING', provider: null, receivedAt: null, detail: null })
      .mockResolvedValueOnce({ status: 'VERIFICATION_MESSAGE_MATCHED', provider: 'robin-managed', receivedAt: '2026-01-02T03:04:00Z', detail: null });
    renderPanel();
    await screen.findByText(/No verification message has been found yet/);

    fireEvent.click(screen.getByRole('button', { name: 'Check again' }));

    expect(await screen.findByText(/A verification message from this employer was found/)).toBeTruthy();
  });

  it('shows a failed account creation with the real reason, never a generic message', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: false, accountCreationAttempted: true,
      loginStatus: 'ACCOUNT_CREATE_FAILED',
      reason: 'the employer’s site reported that an account already exists for this email', applicationUrl: null,
    }]));
    renderPanel();

    expect(await screen.findByText('Account creation did not succeed.')).toBeTruthy();
    expect(screen.getByText(/account already exists/)).toBeTruthy();
    expect(screen.getByText('Account creation failed')).toBeTruthy();
  });

  it('shows TERMS_NOT_AUTHORIZED honestly, never implying an account was created without explicit authorization', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_OUTCOME', planType: 'WORKDAY', credentialSaved: false, accountCreationAttempted: true,
      loginStatus: 'TERMS_NOT_AUTHORIZED',
      reason: 'account creation was not explicitly authorized for this step', applicationUrl: null,
    }]));
    renderPanel();

    expect(await screen.findByText('Account creation was not authorized.')).toBeTruthy();
    expect(screen.getByText('Authorization required')).toBeTruthy();
  });

  // ── Phase 19: Workday Multi-step Application Workflow Tests ───────────

  it('renders Workday application preparation with wizard step, filled fields, and document status', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_APPLICATION_OUTCOME',
      planType: 'WORKDAY',
      currentStep: 'MY_INFORMATION',
      filled: [
        { label: 'First Name', category: 'STANDARD', value: 'Jane' },
        { label: 'Last Name', category: 'STANDARD', value: 'Doe' },
        { label: 'Email Address', category: 'STANDARD', value: 'jane@example.com' },
      ],
      requiredGaps: [],
      resumeUploadState: 'ATTACHED',
      coverLetterUploadState: 'ATTACHED',
      formFound: true,
      applicationUrl: 'https://nvidia.wd5.myworkdayjobs.com/en-US/job/apply',
    }], 'AWAITING_APPROVAL'));

    renderPanel();

    expect(await screen.findByText('Workday — review application preparation')).toBeTruthy();
    expect(screen.getByText('Step reached on Workday:')).toBeTruthy();
    expect(screen.getByText('My Information')).toBeTruthy();
    expect(screen.getByText('Filled automatically (3)')).toBeTruthy();
    expect(screen.getByText(/First Name/)).toBeTruthy();
    expect(screen.getByText(/Résumé \/ CV/)).toBeTruthy();
    expect(screen.getByText('Review needed before authorizing submission')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Approve for Submission' })).toBeTruthy();
  });

  it('approves a clean Workday application plan and calls approve-submit', async () => {
    mocked.mockResolvedValueOnce(PLAN([{
      type: 'WORKDAY_APPLICATION_OUTCOME',
      planType: 'WORKDAY',
      currentStep: 'REVIEW',
      filled: [{ label: 'Legal Name', category: 'STANDARD', value: 'Jane Doe' }],
      requiredGaps: [],
      resumeUploadState: 'ATTACHED',
    }], 'AWAITING_APPROVAL')).mockResolvedValueOnce({ status: 'READY_TO_SUBMIT' });

    renderPanel();
    await screen.findByRole('button', { name: 'Approve for Submission' });

    fireEvent.click(screen.getByRole('button', { name: 'Approve for Submission' }));

    expect(mocked).toHaveBeenCalledWith('/automation/plans/plan-1/approve-submit', { method: 'POST' });
  });

  it('shows human-required warning and blocks approval when required gaps remain', async () => {
    mocked.mockResolvedValue(PLAN([{
      type: 'WORKDAY_APPLICATION_OUTCOME',
      planType: 'WORKDAY',
      currentStep: 'APPLICATION_QUESTIONS',
      filled: [{ label: 'Email', category: 'STANDARD', value: 'jane@example.com' }],
      requiredGaps: [
        { label: 'Are you legally authorized to work in the UK?', category: 'REQUIRES_HUMAN', required: true },
      ],
      resumeUploadState: 'ATTACHED',
    }], 'AWAITING_APPROVAL'));

    renderPanel();

    expect(await screen.findByText('Needs your input before you can authorize submission (1)')).toBeTruthy();
    expect(screen.getByText(/Are you legally authorized to work in the UK\?/)).toBeTruthy();
    expect(screen.getByText('Human input required')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Approve for Submission' })).toBeNull();
    expect(screen.getByText(/Resolve the outstanding fields above before approving submission/)).toBeTruthy();
  });

  it('shows submit button when plan is READY_TO_SUBMIT and calls prepare-submit', async () => {
    mocked.mockResolvedValueOnce(PLAN([{
      type: 'WORKDAY_APPLICATION_OUTCOME',
      planType: 'WORKDAY',
      currentStep: 'REVIEW',
      filled: [{ label: 'Full Name', category: 'STANDARD', value: 'Jane Doe' }],
      requiredGaps: [],
      resumeUploadState: 'ATTACHED',
    }], 'READY_TO_SUBMIT')).mockResolvedValueOnce({ status: 'PREPARED', submissionQueued: true });

    renderPanel();

    expect(await screen.findByText('Ready to submit')).toBeTruthy();
    const submitBtn = screen.getByRole('button', { name: 'Submit application now' });
    fireEvent.click(submitBtn);

    expect(mocked).toHaveBeenCalledWith('/automation/plans/plan-1/prepare-submit', { method: 'POST' });
  });

  it('renders reconciliation required banner and retry action on unconfirmed submission', async () => {
    mocked.mockResolvedValueOnce(PLAN([
      {
        type: 'WORKDAY_APPLICATION_OUTCOME',
        planType: 'WORKDAY',
        currentStep: 'REVIEW',
        filled: [{ label: 'Full Name', category: 'STANDARD' }],
        requiredGaps: [],
        resumeUploadState: 'ATTACHED',
      },
      {
        type: 'SUBMISSION_RECONCILIATION',
        planType: 'WORKDAY',
        category: 'VALIDATION_ERROR',
        evidence: 'Platform validation errors detected on review page',
      },
    ], 'RECONCILIATION_REQUIRED')).mockResolvedValueOnce({ status: 'RECONCILED' });

    renderPanel();

    expect(await screen.findByText('Reconciliation required')).toBeTruthy();
    expect(screen.getByText('Submission reconciliation required')).toBeTruthy();
    expect(screen.getByText(/Platform validation errors detected on review page/)).toBeTruthy();

    const retryBtn = screen.getByRole('button', { name: 'Retry application preparation' });
    fireEvent.click(retryBtn);

    expect(mocked).toHaveBeenCalledWith('/automation/plans/plan-1/reconcile', { method: 'POST' });
  });
});
