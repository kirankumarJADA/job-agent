// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { SubmissionReceiptModal } from './SubmissionReceiptModal';
import type { SubmissionReceipt } from '../types';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));
const mockedApiFetch = vi.mocked(apiFetch);

const CONFIRMED_RECEIPT: SubmissionReceipt = {
  receiptId: 'att-2',
  applicationId: 'app-1',
  jobId: 'job-1',
  jobTitle: 'Senior Platform Engineer',
  company: 'Acme Corp',
  applicationUrl: 'https://careers.acme.example/jobs/42',
  atsKind: 'GREENHOUSE',
  attemptNumber: 2,
  isLatest: true,
  idempotencyKey: 'idemp-2',
  status: 'SUBMITTED',
  submitted: true,
  submittedAt: '2026-10-22T11:59:00Z',
  createdAt: '2026-10-22T11:58:00Z',
  updatedAt: '2026-10-22T11:59:00Z',
  authorization: {
    approved: true,
    policy: 'EXPLICIT_CANDIDATE_APPROVAL',
    approvedAt: '2026-10-22T11:55:00Z',
    actor: 'Jane Doe',
  },
  submittedIdentity: {
    name: 'Jane Doe',
    firstName: 'Jane',
    lastName: 'Doe',
    email: 'jane@example.com',
    phone: '+44 7700 900123',
    addressLine1: 'London, United Kingdom',
    linkedIn: 'https://linkedin.com/in/janedoe',
  },
  submittedAnswers: [
    {
      question: 'How many years of experience do you have with Kubernetes?',
      answer: '7 years managing enterprise production clusters',
    },
  ],
  cv: {
    versionId: 'cv-1',
    title: 'Tailored Platform CV',
    kind: 'TAILORED',
    sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    byteSize: 54321,
    purpose: 'TAILORED_CV',
    fileName: 'Jane_Doe_Platform_CV.pdf',
    validationPassed: true,
  },
  coverLetter: {
    versionId: 'cl-1',
    title: 'Acme Senior Platform Engineer Cover Letter',
    kind: 'TAILORED',
    sha256: 'ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb',
    byteSize: 12345,
    purpose: 'COVER_LETTER',
    fileName: 'cover-letter.txt',
    validationPassed: true,
  },
  confirmation: {
    confirmed: true,
    confirmationId: 'ACM-CONF-98765',
    observedSignals: ['CONFIRMATION_HEADING', 'SUBMISSION_REFERENCE_TOKEN'],
    summary: 'Application confirmation verified against stored worker evidence.',
  },
  reconciliation: {
    required: false,
    reason: null,
    suggestedAction: null,
  },
  failure: {
    failed: false,
    reason: null,
    code: null,
  },
  attemptHistory: [
    {
      attemptId: 'att-1',
      attemptNumber: 1,
      atsKind: 'GREENHOUSE',
      idempotencyKey: 'idemp-1',
      status: 'RECONCILIATION_REQUIRED',
      submitted: false,
      createdAt: '2026-10-22T10:00:00Z',
      updatedAt: '2026-10-22T10:02:00Z',
      isLatest: false,
    },
    {
      attemptId: 'att-2',
      attemptNumber: 2,
      atsKind: 'GREENHOUSE',
      idempotencyKey: 'idemp-2',
      status: 'SUBMITTED',
      submitted: true,
      createdAt: '2026-10-22T11:58:00Z',
      updatedAt: '2026-10-22T11:59:00Z',
      isLatest: true,
    },
  ],
};

const RECONCILIATION_RECEIPT: SubmissionReceipt = {
  receiptId: 'att-1',
  applicationId: 'app-1',
  jobId: 'job-1',
  jobTitle: 'Senior Platform Engineer',
  company: 'Acme Corp',
  applicationUrl: 'https://careers.acme.example/jobs/42',
  atsKind: 'GREENHOUSE',
  attemptNumber: 1,
  isLatest: true,
  idempotencyKey: 'idemp-1',
  status: 'RECONCILIATION_REQUIRED',
  submitted: false,
  submittedAt: null,
  createdAt: '2026-10-22T10:00:00Z',
  updatedAt: '2026-10-22T10:02:00Z',
  authorization: {
    approved: true,
    policy: 'EXPLICIT_CANDIDATE_APPROVAL',
    approvedAt: '2026-10-22T09:55:00Z',
    actor: 'Jane Doe',
  },
  submittedIdentity: {
    name: 'Jane Doe',
    firstName: 'Jane',
    lastName: 'Doe',
    email: 'jane@example.com',
    phone: '+44 7700 900123',
    addressLine1: 'London, United Kingdom',
    linkedIn: null,
  },
  submittedAnswers: [],
  cv: {
    versionId: 'cv-1',
    title: 'Base CV',
    kind: 'TAILORED',
    sha256: 'a1b2c3d4e5f6',
    byteSize: 21000,
    purpose: 'TAILORED_CV',
    fileName: 'Jane_Doe_CV.pdf',
    validationPassed: true,
  },
  coverLetter: null,
  confirmation: {
    confirmed: false,
    confirmationId: null,
    observedSignals: [],
    summary: 'Unverified submission: awaiting confirmation evidence.',
  },
  reconciliation: {
    required: true,
    reason: 'Submission dispatched but confirmation screen was not detected.',
    suggestedAction: 'Verify directly with the employer or reconcile safely.',
  },
  failure: {
    failed: false,
    reason: null,
    code: null,
  },
  attemptHistory: [
    {
      attemptId: 'att-1',
      attemptNumber: 1,
      atsKind: 'GREENHOUSE',
      idempotencyKey: 'idemp-1',
      status: 'RECONCILIATION_REQUIRED',
      submitted: false,
      createdAt: '2026-10-22T10:00:00Z',
      updatedAt: '2026-10-22T10:02:00Z',
      isLatest: true,
    },
  ],
};

const SUBMITTED_UNVERIFIED_RECEIPT: SubmissionReceipt = {
  ...CONFIRMED_RECEIPT,
  confirmation: {
    confirmed: false,
    confirmationId: 'CLAIMED-ONLY-999',
    observedSignals: [],
    summary: 'Unverified submission: stored evidence integrity check failed.',
  },
  reconciliation: {
    required: true,
    reason: 'UNVERIFIED_RECEIPT_EVIDENCE',
    suggestedAction: 'Genuine employer confirmation evidence is unavailable or unverified. Verify directly with the employer.',
  },
};

const EMPTY_RECEIPT: SubmissionReceipt = {
  ...RECONCILIATION_RECEIPT,
  receiptId: null,
  atsKind: 'NOT_ATTEMPTED',
  attemptNumber: 0,
  idempotencyKey: null,
  status: 'PREPARED',
  submitted: false,
  authorization: {
    approved: false,
    policy: 'EXPLICIT_CANDIDATE_APPROVAL',
    approvedAt: null,
    actor: null,
  },
  cv: null,
  coverLetter: null,
  confirmation: {
    confirmed: false,
    confirmationId: null,
    observedSignals: [],
    summary: 'No submission attempt has been executed yet.',
  },
  reconciliation: {
    required: false,
    reason: null,
    suggestedAction: null,
  },
  attemptHistory: [],
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

  it('refuses to display Confirmed Submission Proof when status is SUBMITTED but evidence is unverified', async () => {
    mockedApiFetch.mockResolvedValueOnce(SUBMITTED_UNVERIFIED_RECEIPT);

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={vi.fn()}
        applicationId="app-1"
      />
    );

    expect(await screen.findByText('Unconfirmed Submission — Reconciliation Required')).toBeTruthy();
    expect(screen.getByText('SUBMITTED — UNVERIFIED')).toBeTruthy();
    expect(screen.queryByText('Confirmed Submission Proof')).toBeNull();
    expect(screen.queryByText('CLAIMED-ONLY-999')).toBeNull();
  });

  it('handles unattempted empty receipt with null receiptId and missing documents cleanly', async () => {
    mockedApiFetch.mockResolvedValueOnce(EMPTY_RECEIPT);

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={vi.fn()}
        applicationId="app-1"
      />
    );

    expect(await screen.findByText('No Submission Executed Yet')).toBeTruthy();
    expect(screen.getByText('No CV checksum recorded')).toBeTruthy();
    expect(screen.getByText('None requested/provided')).toBeTruthy();
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
