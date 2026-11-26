// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { SubmissionReceiptModal } from './SubmissionReceiptModal';
import type { SubmissionReceipt } from '../types';
import rawFixtures from '../test/fixtures/submissionReceiptFixtures.json';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));
const mockedApiFetch = vi.mocked(apiFetch);

const contractFixtures: {
  confirmed: SubmissionReceipt;
  reconciliation: SubmissionReceipt;
  submittedUnverified: SubmissionReceipt;
  failed: SubmissionReceipt;
  empty: SubmissionReceipt;
  browserObserved: SubmissionReceipt;
} = rawFixtures;

const CONFIRMED_RECEIPT = contractFixtures.confirmed;
const RECONCILIATION_RECEIPT = contractFixtures.reconciliation;
const SUBMITTED_UNVERIFIED_RECEIPT = contractFixtures.submittedUnverified;
const FAILED_RECEIPT = contractFixtures.failed;
const EMPTY_RECEIPT = contractFixtures.empty;
const BROWSER_OBSERVED_RECEIPT = contractFixtures.browserObserved;

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
    expect(screen.getByText('GH-CONF-98765')).toBeTruthy();
    expect(screen.getByText(/cv-44444444-4444-7444-8444-444444444444\.pdf/)).toBeTruthy();
    expect(screen.getByText(/cover-letter-55555555-5555-7555-8555-555555555555\.txt/)).toBeTruthy();
    expect(screen.getByText(/Acme Senior Platform Engineer Cover Letter/)).toBeTruthy();
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
        applicationId="22222222-2222-7222-8222-222222222222"
      />
    );

    expect(await screen.findByText('Unconfirmed Submission — Reconciliation Required')).toBeTruthy();
    expect(screen.getByText(/\[UNKNOWN\] Submit click ran but employer confirmation response timed out\./)).toBeTruthy();
    expect(screen.queryByText('Confirmed Submission Proof')).toBeNull();
  });

  it('displays failed attempt reason and code from real PLAN_ERROR serialization', async () => {
    mockedApiFetch.mockResolvedValueOnce(FAILED_RECEIPT);

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={vi.fn()}
        applicationId="22222222-2222-7222-8222-222222222222"
      />
    );

    expect(await screen.findByText(/Repeated crashes \(3\/3\): Wait timeout Authorization: \[REDACTED\]/)).toBeTruthy();
    expect(screen.getByText(/DEAD_LETTER_POISON_PILL/)).toBeTruthy();
    expect(screen.queryByText('Confirmed Submission Proof')).toBeNull();
  });

  it('refuses to display Confirmed Submission Proof when status is SUBMITTED but evidence is unverified', async () => {
    mockedApiFetch.mockResolvedValueOnce(SUBMITTED_UNVERIFIED_RECEIPT);

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={vi.fn()}
        applicationId="22222222-2222-7222-8222-222222222222"
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
        applicationId="22222222-2222-7222-8222-222222222222"
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
        applicationId="22222222-2222-7222-8222-222222222222"
      />
    );

    expect(await screen.findByText('Confirmed Submission Proof')).toBeTruthy();

    // Check attempt history buttons
    const attempt1Btn = screen.getByRole('button', { name: /Attempt #1/ });
    fireEvent.click(attempt1Btn);

    expect(mockedApiFetch).toHaveBeenCalledWith(
      '/applications/22222222-2222-7222-8222-222222222222/receipts/66666666-6666-7666-8666-666666666661'
    );
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

  it('renders clean UNIVERSAL browser-observed submission distinctly from confirmed proof and reconciliation errors', async () => {
    mockedApiFetch.mockResolvedValueOnce(BROWSER_OBSERVED_RECEIPT);

    render(
      <SubmissionReceiptModal
        isOpen={true}
        onClose={vi.fn()}
        applicationId="22222222-2222-7222-8222-222222222222"
      />
    );

    expect(await screen.findByText('Browser-Observed Submission — Awaiting Employer Verification')).toBeTruthy();
    expect(screen.getByText('SUBMITTED — BROWSER OBSERVED')).toBeTruthy();
    expect(screen.getByText('APP-98765')).toBeTruthy();
    expect(screen.queryByText('Confirmed Submission Proof')).toBeNull();
    expect(screen.queryByText('Unconfirmed Submission — Reconciliation Required')).toBeNull();
  });
});
