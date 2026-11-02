// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { SubmissionReceiptModal } from './SubmissionReceiptModal';
import type { SubmissionReceipt } from '../types';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));
const mockedApiFetch = vi.mocked(apiFetch);

const CONFIRMED_RECEIPT: SubmissionReceipt = {
  receiptId: 'rcpt-confirmed-123',
  applicationId: 'app-1',
  attemptId: 'att-2',
  idempotencyKey: 'idemp-2',
  companyName: 'Acme Corp',
  jobTitle: 'Senior Platform Engineer',
  applicationUrl: 'https://careers.acme.example/jobs/42',
  status: 'SUBMITTED',
  failureStage: null,
  failureReason: null,
  generatedAt: '2026-10-22T12:00:00Z',
  submittedAt: '2026-10-22T11:59:00Z',
  identity: {
    fullName: 'Jane Doe',
    email: 'jane@example.com',
    phone: '+44 7700 900123',
    city: 'London',
    country: 'United Kingdom',
    rightToWorkUk: true,
    requiresVisaSponsorship: false,
  },
  documents: {
    cvId: 'cv-1',
    cvVersion: 3,
    cvFilename: 'Jane_Doe_Platform_CV.pdf',
    cvSha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    coverLetterId: 'cl-1',
    coverLetterTitle: 'Acme Senior Platform Engineer Cover Letter',
    coverLetterSha256: 'ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb',
    coverLetterProvided: true,
  },
  answers: [
    {
      key: 'years_experience',
      questionText: 'How many years of experience do you have with Kubernetes?',
      answerText: '7 years managing enterprise production clusters',
    },
  ],
  confirmation: {
    confirmed: true,
    confirmationNumber: 'ACM-CONF-98765',
    receiptToken: 'tok-abc-xyz',
    detectedAt: '2026-10-22T11:59:05Z',
    pageTitle: 'Thank You For Applying | Acme Careers',
    observedSignals: ['CONFIRMATION_HEADING', 'SUBMISSION_REFERENCE_TOKEN'],
  },
  reconciliation: {
    needed: false,
    reason: null,
    lastReconciledAt: null,
    attemptCount: 2,
  },
  failure: {
    failed: false,
    failureStage: null,
    errorMessage: null,
    occurredAt: null,
  },
  authorization: {
    authorized: true,
    authorizedBy: 'Jane Doe',
    authorizedAt: '2026-10-22T11:55:00Z',
    consentSummary: 'Explicit candidate consent verified',
  },
  attempts: [
    {
      attemptId: 'att-2',
      idempotencyKey: 'idemp-2',
      status: 'SUBMITTED',
      stage: 'CONFIRMATION',
      createdAt: '2026-10-22T11:58:00Z',
      completedAt: '2026-10-22T11:59:00Z',
      isCurrent: true,
    },
    {
      attemptId: 'att-1',
      idempotencyKey: 'idemp-1',
      status: 'RECONCILIATION_REQUIRED',
      stage: 'SUBMIT',
      createdAt: '2026-10-22T10:00:00Z',
      completedAt: '2026-10-22T10:02:00Z',
      isCurrent: false,
    },
  ],
};

const RECONCILIATION_RECEIPT: SubmissionReceipt = {
  receiptId: 'rcpt-recon-456',
  applicationId: 'app-1',
  attemptId: 'att-1',
  idempotencyKey: 'idemp-1',
  companyName: 'Acme Corp',
  jobTitle: 'Senior Platform Engineer',
  applicationUrl: 'https://careers.acme.example/jobs/42',
  status: 'RECONCILIATION_REQUIRED',
  failureStage: null,
  failureReason: null,
  generatedAt: '2026-10-22T10:02:00Z',
  submittedAt: null,
  identity: {
    fullName: 'Jane Doe',
    email: 'jane@example.com',
    phone: '+44 7700 900123',
    city: 'London',
    country: 'United Kingdom',
    rightToWorkUk: true,
    requiresVisaSponsorship: false,
  },
  documents: {
    cvId: 'cv-1',
    cvVersion: 2,
    cvFilename: 'Jane_Doe_CV.pdf',
    cvSha256: 'a1b2c3d4e5f6',
    coverLetterId: null,
    coverLetterTitle: null,
    coverLetterSha256: null,
    coverLetterProvided: false,
  },
  answers: [],
  confirmation: {
    confirmed: false,
    confirmationNumber: null,
    receiptToken: null,
    detectedAt: null,
    pageTitle: null,
    observedSignals: [],
  },
  reconciliation: {
    needed: true,
    reason: 'Submission dispatched but confirmation screen was not detected.',
    lastReconciledAt: '2026-10-22T10:05:00Z',
    attemptCount: 1,
  },
  failure: {
    failed: false,
    failureStage: null,
    errorMessage: null,
    occurredAt: null,
  },
  authorization: {
    authorized: true,
    authorizedBy: 'Jane Doe',
    authorizedAt: '2026-10-22T09:55:00Z',
    consentSummary: 'Explicit candidate consent verified',
  },
  attempts: [
    {
      attemptId: 'att-1',
      idempotencyKey: 'idemp-1',
      status: 'RECONCILIATION_REQUIRED',
      stage: 'SUBMIT',
      createdAt: '2026-10-22T10:00:00Z',
      completedAt: '2026-10-22T10:02:00Z',
      isCurrent: true,
    },
  ],
};

describe('SubmissionReceiptModal', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders nothing when isOpen is false', () => {
    const { container } = render(
      <SubmissionReceiptModal
        isOpen={false}
        onClose={vi.fn()}
        applicationId="app-1"
        companyName="Acme Corp"
        jobTitle="Senior Platform Engineer"
      />
    );
    expect(container.firstChild).toBeNull();
  });

  it('fetches and displays confirmed submission receipt with cryptographic provenance and answers', async () => {
    mockedApiFetch.mockResolvedValueOnce(CONFIRMED_RECEIPT);

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={vi.fn()}
        applicationId="app-1"
        companyName="Acme Corp"
        jobTitle="Senior Platform Engineer"
      />
    );

    expect(mockedApiFetch).toHaveBeenCalledWith('/applications/app-1/receipt');

    expect(await screen.findByText('Confirmed Submission Proof')).toBeTruthy();
    expect(screen.getByText('ACM-CONF-98765')).toBeTruthy();
    expect(screen.getByText('tok-abc-xyz')).toBeTruthy();
    expect(screen.getByText(/Jane_Doe_Platform_CV\.pdf/)).toBeTruthy();
    expect(screen.getByText(/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855/)).toBeTruthy();
    expect(screen.getByText(/How many years of experience do you have with Kubernetes\?/)).toBeTruthy();
    expect(screen.getByText(/7 years managing enterprise production clusters/)).toBeTruthy();
    expect(screen.getAllByText('Jane Doe').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('jane@example.com')).toBeTruthy();
  });

  it('truthfully displays unconfirmed submission warning when reconciliation is required', async () => {
    mockedApiFetch.mockResolvedValueOnce(RECONCILIATION_RECEIPT);

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={vi.fn()}
        applicationId="app-1"
      />
    );

    expect(await screen.findByText('Unconfirmed Submission — Reconciliation Required')).toBeTruthy();
    expect(screen.getByText(/Submission dispatched but confirmation screen was not detected/)).toBeTruthy();
    expect(screen.queryByText('Confirmed Submission Proof')).toBeNull();
  });

  it('permits navigating through attempt history', async () => {
    mockedApiFetch
      .mockResolvedValueOnce(CONFIRMED_RECEIPT)
      .mockResolvedValueOnce(RECONCILIATION_RECEIPT);

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={vi.fn()}
        applicationId="app-1"
      />
    );

    expect(await screen.findByText('Confirmed Submission Proof')).toBeTruthy();

    // Check attempt history buttons
    const attempt1Btn = screen.getByRole('button', { name: /Attempt #1/ });
    fireEvent.click(attempt1Btn);

    expect(mockedApiFetch).toHaveBeenCalledWith('/applications/app-1/receipts/att-1');
    expect(await screen.findByText('Unconfirmed Submission — Reconciliation Required')).toBeTruthy();
  });

  it('calls onClose when close button is clicked', async () => {
    mockedApiFetch.mockResolvedValueOnce(CONFIRMED_RECEIPT);
    const onClose = vi.fn();

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={onClose}
        applicationId="app-1"
      />
    );

    expect(await screen.findByText('Confirmed Submission Proof')).toBeTruthy();
    const closeBtn = screen.getByLabelText('Close receipt');
    fireEvent.click(closeBtn);

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
