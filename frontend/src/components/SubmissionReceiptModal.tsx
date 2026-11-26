import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import type { SubmissionReceipt } from '../types';
import { Alert, Loading, SecondaryButton, StatusPill } from './ui';

export interface SubmissionReceiptModalProps {
  isOpen: boolean;
  onClose: () => void;
  applicationId: string;
  companyName?: string;
  jobTitle?: string;
}

export const SubmissionReceiptModal: React.FC<SubmissionReceiptModalProps> = ({
  isOpen,
  onClose,
  applicationId,
  companyName,
  jobTitle,
}) => {
  const [receipt, setReceipt] = useState<SubmissionReceipt | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedAttemptId, setSelectedAttemptId] = useState<string | null>(null);
  const [copiedText, setCopiedText] = useState<string | null>(null);

  const loadReceipt = useCallback((attemptId: string | null) => {
    setLoading(true);
    setError(null);
    const endpoint = attemptId
      ? `/applications/${applicationId}/receipts/${attemptId}`
      : `/applications/${applicationId}/receipt`;

    apiFetch<SubmissionReceipt>(endpoint)
      .then((data) => {
        setReceipt(data);
        if (!attemptId) {
          setSelectedAttemptId(
            data.receiptId ?? data.attemptHistory?.find((a) => a.isLatest)?.attemptId ?? null
          );
        }
      })
      .catch((err: unknown) => {
        setError(err instanceof Error ? err.message : 'Failed to load submission receipt.');
      })
      .finally(() => {
        setLoading(false);
      });
  }, [applicationId]);

  useEffect(() => {
    if (isOpen) {
      setSelectedAttemptId(null);
      loadReceipt(null);
    } else {
      setReceipt(null);
      setError(null);
      setSelectedAttemptId(null);
      setCopiedText(null);
    }
  }, [isOpen, loadReceipt]);

  const handleSelectAttempt = (attemptId: string) => {
    if (attemptId === selectedAttemptId) return;
    setSelectedAttemptId(attemptId);
    loadReceipt(attemptId);
  };

  const copyToClipboard = (text: string, label: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopiedText(label);
      setTimeout(() => setCopiedText(null), 2500);
    });
  };

  if (!isOpen) return null;

  const isConfirmedProof =
    receipt != null &&
    receipt.status === 'SUBMITTED' &&
    receipt.confirmation?.confirmed === true &&
    receipt.reconciliation?.required !== true;

  const isBrowserObserved =
    receipt != null &&
    receipt.status === 'SUBMITTED' &&
    !isConfirmedProof &&
    receipt.verificationLevel === 'BROWSER_OBSERVED' &&
    receipt.reconciliation?.required !== true;

  const needsReconciliation =
    receipt != null &&
    (receipt.status === 'RECONCILIATION_REQUIRED' ||
      receipt.reconciliation?.required === true ||
      (receipt.status === 'SUBMITTED' && !isConfirmedProof && !isBrowserObserved));

  const isFailedAttempt =
    receipt != null &&
    !isConfirmedProof &&
    !isBrowserObserved &&
    !needsReconciliation &&
    (receipt.status === 'FAILED' || receipt.status === 'BLOCKED_ANTI_BOT' || receipt.failure?.failed === true);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="receipt-modal-title"
      className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6"
    >
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-ink-950/60 backdrop-blur-sm transition-opacity"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Modal Dialog */}
      <div className="relative flex max-h-[90vh] w-full max-w-3xl flex-col rounded-2xl border border-line bg-surface shadow-2xl overflow-hidden z-10">
        {/* Header */}
        <div className="flex items-start justify-between border-b border-line px-6 py-4 bg-surface-raised">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-[11px] font-bold uppercase tracking-wider text-forest-700">
                Application Submission Receipt
              </span>
              {receipt && (
                <StatusPill
                  tone={
                    isConfirmedProof
                      ? 'emerald'
                      : isBrowserObserved
                      ? 'sky'
                      : needsReconciliation
                      ? 'amber'
                      : isFailedAttempt
                      ? 'red'
                      : 'slate'
                  }
                >
                  {isBrowserObserved
                    ? 'SUBMITTED — BROWSER OBSERVED'
                    : receipt.status === 'SUBMITTED' && !isConfirmedProof
                    ? 'SUBMITTED — UNVERIFIED'
                    : receipt.status}
                </StatusPill>
              )}
            </div>
            <h2 id="receipt-modal-title" className="mt-1 text-lg font-bold text-ink">
              {jobTitle || receipt?.jobTitle || 'Application Receipt'}
              {(companyName || receipt?.company) && (
                <span className="font-normal text-ink-muted"> at {companyName || receipt?.company}</span>
              )}
            </h2>
            {receipt && (
              <p className="mt-0.5 text-xs text-ink-faint font-mono">
                Receipt ID: {receipt.receiptId ?? 'Not yet attempted'}
                {' • '}Attempt #{receipt.attemptNumber}
                {receipt.atsKind ? ` • ${receipt.atsKind}` : ''}
                {receipt.updatedAt ? ` • Updated ${new Date(receipt.updatedAt).toLocaleString()}` : ''}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-ink-muted hover:bg-forest-50 hover:text-ink transition-colors"
            aria-label="Close receipt"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Content Body */}
        <div className="flex-1 overflow-y-auto px-6 py-5 space-y-6">
          {loading && !receipt && (
            <div className="py-12 flex justify-center">
              <Loading />
            </div>
          )}

          {error && (
            <Alert tone="error">
              <div className="flex flex-col gap-2">
                <p className="font-medium">{error}</p>
                <div>
                  <SecondaryButton onClick={() => loadReceipt(selectedAttemptId)}>Retry</SecondaryButton>
                </div>
              </div>
            </Alert>
          )}

          {receipt && (
            <>
              {/* Attempt History Switcher (when multiple attempts exist) */}
              {receipt.attemptHistory && receipt.attemptHistory.length > 1 && (
                <div className="rounded-xl border border-line bg-surface-sunken p-3">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-semibold text-ink-muted uppercase tracking-wider">
                      Submission Attempts &amp; Retries ({receipt.attemptHistory.length})
                    </span>
                    <span className="text-[11px] text-ink-faint">
                      Select an attempt to inspect its exact submitted payload
                    </span>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {receipt.attemptHistory.map((att) => {
                      const isSelected =
                        att.attemptId === receipt.receiptId ||
                        att.attemptNumber === receipt.attemptNumber ||
                        att.attemptId === selectedAttemptId;
                      return (
                        <button
                          key={att.attemptId}
                          type="button"
                          onClick={() => handleSelectAttempt(att.attemptId)}
                          className={`flex items-center gap-2 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors border ${
                            isSelected
                              ? 'border-forest-700 bg-forest-900 text-cream-50 shadow-sm'
                              : 'border-line bg-surface text-ink-soft hover:border-forest-300 hover:bg-forest-50'
                          }`}
                        >
                          <span>Attempt #{att.attemptNumber}</span>
                          <span
                            className={`inline-block w-2 h-2 rounded-full ${
                              att.status === 'SUBMITTED'
                                ? 'bg-emerald-400'
                                : att.status === 'RECONCILIATION_REQUIRED'
                                ? 'bg-amber-400'
                                : 'bg-red-400'
                            }`}
                          />
                          {att.isLatest && (
                            <span className="text-[10px] opacity-75 font-normal">(latest)</span>
                          )}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Status Outcome Banner */}
              {isConfirmedProof && (
                <div className="rounded-xl border border-emerald-300 bg-emerald-50/80 p-4">
                  <div className="flex items-start gap-3">
                    <div className="rounded-full bg-emerald-100 p-1.5 text-emerald-800">
                      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                      </svg>
                    </div>
                    <div className="flex-1">
                      <h3 className="text-sm font-semibold text-emerald-950">
                        Confirmed Submission Proof
                      </h3>
                      <p className="mt-1 text-xs text-emerald-900 leading-relaxed">
                        Robin confirmed this submission on the employer&apos;s platform. Submission proofs and verification
                        evidence have been verified and archived below.
                      </p>
                      <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
                        {receipt.confirmation?.confirmationId && (
                          <div className="rounded-lg bg-emerald-100/70 p-2">
                            <span className="font-semibold text-emerald-950">Confirmation ID:</span>{' '}
                            <span className="font-mono font-medium">{receipt.confirmation.confirmationId}</span>
                          </div>
                        )}
                        {receipt.submittedAt && (
                          <div className="rounded-lg bg-emerald-100/70 p-2">
                            <span className="font-semibold text-emerald-950">Submitted At:</span>{' '}
                            <span>{new Date(receipt.submittedAt).toLocaleString()}</span>
                          </div>
                        )}
                        {receipt.confirmation?.summary && (
                          <div className="sm:col-span-2 rounded-lg bg-emerald-100/70 p-2">
                            <span className="font-semibold text-emerald-950">Evidence Summary:</span>{' '}
                            <span>{receipt.confirmation.summary}</span>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {isBrowserObserved && (
                <div className="rounded-xl border border-sky-300 bg-sky-50/80 p-4">
                  <div className="flex items-start gap-3">
                    <div className="rounded-full bg-sky-100 p-1.5 text-sky-800">
                      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                      </svg>
                    </div>
                    <div className="flex-1">
                      <h3 className="text-sm font-semibold text-sky-950">
                        Browser-Observed Submission — Awaiting Employer Verification
                      </h3>
                      <p className="mt-1 text-xs text-sky-900 leading-relaxed">
                        Robin observed the form submission and confirmation view in the browser and archived encrypted DOM
                        evidence. Employer acknowledgement has not yet been independently verified by email or ATS API.
                      </p>
                      <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
                        {receipt.confirmation?.confirmationId && (
                          <div className="rounded-lg bg-sky-100/70 p-2">
                            <span className="font-semibold text-sky-950">Observed Reference:</span>{' '}
                            <span className="font-mono font-medium">{receipt.confirmation.confirmationId}</span>
                          </div>
                        )}
                        {receipt.submittedAt && (
                          <div className="rounded-lg bg-sky-100/70 p-2">
                            <span className="font-semibold text-sky-950">Submitted At:</span>{' '}
                            <span>{new Date(receipt.submittedAt).toLocaleString()}</span>
                          </div>
                        )}
                        {receipt.confirmation?.summary && (
                          <div className="sm:col-span-2 rounded-lg bg-sky-100/70 p-2">
                            <span className="font-semibold text-sky-950">Observation Summary:</span>{' '}
                            <span>{receipt.confirmation.summary}</span>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {needsReconciliation && (
                <div className="rounded-xl border border-amber-300 bg-amber-50/80 p-4">
                  <div className="flex items-start gap-3">
                    <div className="rounded-full bg-amber-100 p-1.5 text-amber-800">
                      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                      </svg>
                    </div>
                    <div className="flex-1">
                      <h3 className="text-sm font-semibold text-amber-950">
                        Unconfirmed Submission — Reconciliation Required
                      </h3>
                      <p className="mt-1 text-xs text-amber-900 leading-relaxed">
                        The submission was dispatched, or stored receipt evidence is incomplete/unverified. Under Robin&apos;s
                        strict truthfulness policy, Robin never fabricates confirmation or assumes unobserved success.
                      </p>
                      {(receipt.reconciliation?.reason || receipt.confirmation?.summary) && (
                        <div className="mt-2 space-y-1 text-xs font-mono bg-amber-100/70 rounded-lg p-2 text-amber-950">
                          {receipt.reconciliation?.reason && <div>{receipt.reconciliation.reason}</div>}
                          {receipt.confirmation?.summary && <div className="font-sans">{receipt.confirmation.summary}</div>}
                        </div>
                      )}
                      {receipt.reconciliation?.suggestedAction && (
                        <p className="mt-2 text-xs text-amber-900 font-medium">
                          Next step: {receipt.reconciliation.suggestedAction}
                        </p>
                      )}
                    </div>
                  </div>
                </div>
              )}

              {isFailedAttempt && (
                <div className="rounded-xl border border-red-300 bg-red-50/80 p-4">
                  <div className="flex items-start gap-3">
                    <div className="rounded-full bg-red-100 p-1.5 text-red-800">
                      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </div>
                    <div className="flex-1">
                      <h3 className="text-sm font-semibold text-red-950">
                        Submission Failed{receipt.failure?.code ? ` (${receipt.failure.code})` : ''}
                      </h3>
                      <p className="mt-1 text-xs text-red-900 leading-relaxed">
                        {receipt.failure?.reason || 'An error occurred during execution.'}
                      </p>
                    </div>
                  </div>
                </div>
              )}

              {!isConfirmedProof && !isBrowserObserved && !needsReconciliation && !isFailedAttempt && receipt.attemptNumber === 0 && (
                <div className="rounded-xl border border-line bg-surface-sunken p-4">
                  <h3 className="text-sm font-semibold text-ink">No Submission Executed Yet</h3>
                  <p className="mt-1 text-xs text-ink-muted leading-relaxed">
                    {receipt.confirmation?.summary || 'No submission attempt has been executed yet.'}
                  </p>
                </div>
              )}

              {/* Document Provenance Section */}
              <div className="rounded-xl border border-line bg-surface p-4 space-y-3">
                <div className="flex items-center justify-between border-b border-line pb-2">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-ink-muted">
                    Submitted Documents &amp; Provenance
                  </h3>
                  <span className="text-[11px] text-ink-faint">Cryptographic SHA-256 Fingerprints</span>
                </div>
                <div className="space-y-3">
                  {/* CV Record */}
                  <div className="rounded-lg border border-line bg-surface-sunken p-3">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 flex-wrap">
                        <svg className="w-4 h-4 text-forest-700" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                        </svg>
                        <span className="text-xs font-semibold text-ink">
                          Curriculum Vitae{receipt.cv?.title ? ` — ${receipt.cv.title}` : ''}
                        </span>
                        {receipt.cv?.validationPassed === true && (
                          <StatusPill tone="emerald">Validated</StatusPill>
                        )}
                      </div>
                      {receipt.cv?.fileName && (
                        <span className="text-xs text-ink-muted">{receipt.cv.fileName}</span>
                      )}
                    </div>
                    {receipt.cv?.sha256 ? (
                      <div className="mt-2 flex items-center justify-between gap-2 rounded bg-surface border border-line px-2.5 py-1.5">
                        <span className="font-mono text-[11px] text-ink-soft truncate select-all">
                          SHA-256: {receipt.cv.sha256}
                        </span>
                        <button
                          type="button"
                          onClick={() => copyToClipboard(receipt.cv!.sha256!, 'cv')}
                          className="shrink-0 text-xs font-medium text-forest-700 hover:text-forest-900"
                        >
                          {copiedText === 'cv' ? 'Copied!' : 'Copy'}
                        </button>
                      </div>
                    ) : (
                      <p className="mt-1 text-xs text-ink-faint italic">No CV checksum recorded</p>
                    )}
                  </div>

                  {/* Cover Letter Record */}
                  <div className="rounded-lg border border-line bg-surface-sunken p-3">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 flex-wrap">
                        <svg className="w-4 h-4 text-forest-700" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                        </svg>
                        <span className="text-xs font-semibold text-ink">
                          Cover Letter{receipt.coverLetter?.title ? ` — ${receipt.coverLetter.title}` : ''}
                        </span>
                      </div>
                      <span className="text-xs text-ink-muted">
                        {receipt.coverLetter ? (receipt.coverLetter.fileName || 'Included') : 'None requested/provided'}
                      </span>
                    </div>
                    {receipt.coverLetter?.sha256 && (
                      <div className="mt-2 flex items-center justify-between gap-2 rounded bg-surface border border-line px-2.5 py-1.5">
                        <span className="font-mono text-[11px] text-ink-soft truncate select-all">
                          SHA-256: {receipt.coverLetter.sha256}
                        </span>
                        <button
                          type="button"
                          onClick={() => copyToClipboard(receipt.coverLetter!.sha256!, 'coverLetter')}
                          className="shrink-0 text-xs font-medium text-forest-700 hover:text-forest-900"
                        >
                          {copiedText === 'coverLetter' ? 'Copied!' : 'Copy'}
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* Candidate Identity Section */}
              <div className="rounded-xl border border-line bg-surface p-4 space-y-3">
                <h3 className="text-xs font-bold uppercase tracking-wider text-ink-muted border-b border-line pb-2">
                  Submitted Candidate Identity
                </h3>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                  <div>
                    <span className="text-ink-muted block text-[11px]">Full Name</span>
                    <span className="font-medium text-ink">
                      {receipt.submittedIdentity?.name
                        || [receipt.submittedIdentity?.firstName, receipt.submittedIdentity?.lastName].filter(Boolean).join(' ')
                        || '—'}
                    </span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Email Address</span>
                    <span className="font-medium text-ink">{receipt.submittedIdentity?.email || '—'}</span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Phone Number</span>
                    <span className="font-medium text-ink">{receipt.submittedIdentity?.phone || '—'}</span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Address</span>
                    <span className="font-medium text-ink">{receipt.submittedIdentity?.addressLine1 || '—'}</span>
                  </div>
                  <div className="sm:col-span-2">
                    <span className="text-ink-muted block text-[11px]">LinkedIn</span>
                    <span className="font-medium text-ink break-all">{receipt.submittedIdentity?.linkedIn || '—'}</span>
                  </div>
                </div>
              </div>

              {/* Submitted Questionnaire Answers */}
              {receipt.submittedAnswers && receipt.submittedAnswers.length > 0 && (
                <div className="rounded-xl border border-line bg-surface p-4 space-y-3">
                  <div className="flex items-center justify-between border-b border-line pb-2">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-ink-muted">
                      Questionnaire &amp; Custom Answers ({receipt.submittedAnswers.length})
                    </h3>
                    <span className="text-[11px] text-ink-faint">Exact answers delivered</span>
                  </div>
                  <div className="space-y-2.5">
                    {receipt.submittedAnswers.map((ans, idx) => (
                      <div key={idx} className="rounded-lg bg-surface-sunken p-3 text-xs border border-line">
                        <p className="font-semibold text-ink">{ans.question}</p>
                        <p className="mt-1 text-ink-soft whitespace-pre-wrap">{ans.answer}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Platform Confirmation & Signals */}
              <div className="rounded-xl border border-line bg-surface p-4 space-y-3">
                <h3 className="text-xs font-bold uppercase tracking-wider text-ink-muted border-b border-line pb-2">
                  Platform Verification &amp; Technical Metadata
                </h3>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
                  <div>
                    <span className="text-ink-muted block text-[11px]">Idempotency Key</span>
                    <span className="font-mono text-ink-soft text-[11px] truncate block select-all">
                      {receipt.idempotencyKey || '—'}
                    </span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Receipt / Attempt ID</span>
                    <span className="font-mono text-ink-soft text-[11px] truncate block select-all">
                      {receipt.receiptId || '—'}
                    </span>
                  </div>
                  {receipt.applicationUrl && (
                    <div className="sm:col-span-2">
                      <span className="text-ink-muted block text-[11px]">Employer Portal URL</span>
                      <a
                        href={receipt.applicationUrl}
                        target="_blank"
                        rel="noreferrer noopener"
                        className="text-forest-700 hover:underline truncate block text-xs"
                      >
                        {receipt.applicationUrl}
                      </a>
                    </div>
                  )}
                  {receipt.confirmation?.observedSignals && receipt.confirmation.observedSignals.length > 0 && (
                    <div className="sm:col-span-2">
                      <span className="text-ink-muted block text-[11px]">Observed Confirmation Signals</span>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {receipt.confirmation.observedSignals.map((sig, i) => (
                          <span key={i} className="rounded bg-surface-sunken border border-line px-2 py-0.5 text-[11px] font-mono text-ink-soft">
                            {sig}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>

              {/* Candidate Authorization Audit Record */}
              <div className="rounded-xl border border-line bg-surface p-4">
                <div className="flex items-center justify-between border-b border-line pb-2 mb-2">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-ink-muted">
                    Candidate Authorization &amp; Audit Trail
                  </h3>
                  <StatusPill tone={receipt.authorization?.approved ? 'emerald' : 'amber'}>
                    {receipt.authorization?.approved ? 'Authorized' : 'Unconfirmed'}
                  </StatusPill>
                </div>
                <div className="text-xs text-ink-soft space-y-1">
                  <p>
                    <span className="font-medium text-ink">Authorized by:</span>{' '}
                    {receipt.authorization?.actor || 'Candidate (via Robin Web Application)'}
                  </p>
                  {receipt.authorization?.policy && (
                    <p>
                      <span className="font-medium text-ink">Policy:</span>{' '}
                      <span className="font-mono">{receipt.authorization.policy}</span>
                    </p>
                  )}
                  {receipt.authorization?.approvedAt && (
                    <p>
                      <span className="font-medium text-ink">Timestamp:</span>{' '}
                      {new Date(receipt.authorization.approvedAt).toLocaleString()}
                    </p>
                  )}
                  <p className="text-[11px] text-ink-faint mt-1">
                    Explicit candidate consent was verified before dispatching automation payload to the employer portal.
                  </p>
                </div>
              </div>
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-line px-6 py-4 bg-surface-raised">
          <div className="text-xs text-ink-faint">
            Robin • Post-Submission Evidence &amp; Integrity Guarantee
          </div>
          <div className="flex items-center gap-2">
            {receipt && (
              <SecondaryButton
                onClick={() => {
                  const summary = `Submission Receipt\nCompany: ${companyName || receipt.company || 'N/A'}\nJob: ${jobTitle || receipt.jobTitle || 'N/A'}\nReceipt ID: ${receipt.receiptId ?? 'N/A'}\nStatus: ${receipt.status}\nCV SHA-256: ${receipt.cv?.sha256 || 'N/A'}\nConfirmation: ${isConfirmedProof ? (receipt.confirmation?.confirmationId || 'Confirmed') : 'UNCONFIRMED'}`;
                  copyToClipboard(summary, 'all');
                }}
              >
                {copiedText === 'all' ? 'Copied Summary!' : 'Copy Summary'}
              </SecondaryButton>
            )}
            <SecondaryButton onClick={onClose}>Close</SecondaryButton>
          </div>
        </div>
      </div>
    </div>
  );
};
