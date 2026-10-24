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
          setSelectedAttemptId(data.attemptId);
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
                    receipt.status === 'SUBMITTED'
                      ? 'emerald'
                      : receipt.status === 'RECONCILIATION_REQUIRED'
                      ? 'amber'
                      : 'red'
                  }
                >
                  {receipt.status}
                </StatusPill>
              )}
            </div>
            <h2 id="receipt-modal-title" className="mt-1 text-lg font-bold text-ink">
              {jobTitle || receipt?.jobTitle || 'Application Receipt'}
              {(companyName || receipt?.companyName) && (
                <span className="font-normal text-ink-muted"> at {companyName || receipt?.companyName}</span>
              )}
            </h2>
            {receipt && (
              <p className="mt-0.5 text-xs text-ink-faint font-mono">
                Receipt ID: {receipt.receiptId} • Generated {new Date(receipt.generatedAt).toLocaleString()}
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
              {receipt.attempts && receipt.attempts.length > 1 && (
                <div className="rounded-xl border border-line bg-surface-sunken p-3">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-semibold text-ink-muted uppercase tracking-wider">
                      Submission Attempts &amp; Retries ({receipt.attempts.length})
                    </span>
                    <span className="text-[11px] text-ink-faint">
                      Select an attempt to inspect its exact submitted payload
                    </span>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {receipt.attempts.map((att, idx) => {
                      const isSelected = att.attemptId === receipt.attemptId;
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
                          <span>Attempt #{receipt.attempts.length - idx}</span>
                          <span
                            className={`inline-block w-2 h-2 rounded-full ${
                              att.status === 'SUBMITTED'
                                ? 'bg-emerald-400'
                                : att.status === 'RECONCILIATION_REQUIRED'
                                ? 'bg-amber-400'
                                : 'bg-red-400'
                            }`}
                          />
                          {att.isCurrent && (
                            <span className="text-[10px] opacity-75 font-normal">(latest)</span>
                          )}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Status Outcome Banner */}
              {receipt.status === 'SUBMITTED' && (
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
                        Robin confirmed this submission on the employer's platform. Submission proofs and confirmation
                        tokens have been verified and archived below.
                      </p>
                      <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
                        {receipt.confirmation.confirmationNumber && (
                          <div className="rounded-lg bg-emerald-100/70 p-2">
                            <span className="font-semibold text-emerald-950">Confirmation Number:</span>{' '}
                            <span className="font-mono font-medium">{receipt.confirmation.confirmationNumber}</span>
                          </div>
                        )}
                        {receipt.confirmation.receiptToken && (
                          <div className="rounded-lg bg-emerald-100/70 p-2">
                            <span className="font-semibold text-emerald-950">Receipt Token:</span>{' '}
                            <span className="font-mono font-medium">{receipt.confirmation.receiptToken}</span>
                          </div>
                        )}
                        {receipt.submittedAt && (
                          <div className="rounded-lg bg-emerald-100/70 p-2">
                            <span className="font-semibold text-emerald-950">Submitted At:</span>{' '}
                            <span>{new Date(receipt.submittedAt).toLocaleString()}</span>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {receipt.status === 'RECONCILIATION_REQUIRED' && (
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
                        The submission was dispatched, but conclusive confirmation from the platform was not detected.
                        Under Robin's strict truthfulness policy, Robin never fabricates confirmation or assumes
                        unobserved success. Robin will reconcile this status using inbound recruiter communications or mailbox evidence.
                      </p>
                      {receipt.reconciliation.reason && (
                        <div className="mt-2 text-xs font-mono bg-amber-100/70 rounded-lg p-2 text-amber-950">
                          {receipt.reconciliation.reason}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              )}

              {receipt.status === 'FAILED' && (
                <div className="rounded-xl border border-red-300 bg-red-50/80 p-4">
                  <div className="flex items-start gap-3">
                    <div className="rounded-full bg-red-100 p-1.5 text-red-800">
                      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </div>
                    <div className="flex-1">
                      <h3 className="text-sm font-semibold text-red-950">
                        Submission Failed at Stage: {receipt.failureStage || 'EXECUTION'}
                      </h3>
                      <p className="mt-1 text-xs text-red-900 leading-relaxed">
                        {receipt.failureReason || receipt.failure.errorMessage || 'An error occurred during execution.'}
                      </p>
                    </div>
                  </div>
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
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <svg className="w-4 h-4 text-forest-700" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                        </svg>
                        <span className="text-xs font-semibold text-ink">
                          Curriculum Vitae {receipt.documents.cvVersion ? `(v${receipt.documents.cvVersion})` : ''}
                        </span>
                      </div>
                      {receipt.documents.cvFilename && (
                        <span className="text-xs text-ink-muted">{receipt.documents.cvFilename}</span>
                      )}
                    </div>
                    {receipt.documents.cvSha256 ? (
                      <div className="mt-2 flex items-center justify-between gap-2 rounded bg-surface border border-line px-2.5 py-1.5">
                        <span className="font-mono text-[11px] text-ink-soft truncate select-all">
                          SHA-256: {receipt.documents.cvSha256}
                        </span>
                        <button
                          type="button"
                          onClick={() => copyToClipboard(receipt.documents.cvSha256!, 'cv')}
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
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <svg className="w-4 h-4 text-forest-700" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                        </svg>
                        <span className="text-xs font-semibold text-ink">Cover Letter</span>
                      </div>
                      <span className="text-xs text-ink-muted">
                        {receipt.documents.coverLetterProvided ? (receipt.documents.coverLetterTitle || 'Included') : 'None requested/provided'}
                      </span>
                    </div>
                    {receipt.documents.coverLetterSha256 && (
                      <div className="mt-2 flex items-center justify-between gap-2 rounded bg-surface border border-line px-2.5 py-1.5">
                        <span className="font-mono text-[11px] text-ink-soft truncate select-all">
                          SHA-256: {receipt.documents.coverLetterSha256}
                        </span>
                        <button
                          type="button"
                          onClick={() => copyToClipboard(receipt.documents.coverLetterSha256!, 'coverLetter')}
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
                    <span className="font-medium text-ink">{receipt.identity.fullName || '—'}</span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Email Address</span>
                    <span className="font-medium text-ink">{receipt.identity.email || '—'}</span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Phone Number</span>
                    <span className="font-medium text-ink">{receipt.identity.phone || '—'}</span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Location</span>
                    <span className="font-medium text-ink">
                      {[receipt.identity.city, receipt.identity.country].filter(Boolean).join(', ') || '—'}
                    </span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Right to Work (UK)</span>
                    <span className="font-medium text-ink">
                      {receipt.identity.rightToWorkUk === true ? 'Yes' : receipt.identity.rightToWorkUk === false ? 'No' : '—'}
                    </span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Requires Visa Sponsorship</span>
                    <span className="font-medium text-ink">
                      {receipt.identity.requiresVisaSponsorship === true ? 'Yes' : receipt.identity.requiresVisaSponsorship === false ? 'No' : '—'}
                    </span>
                  </div>
                </div>
              </div>

              {/* Submitted Questionnaire Answers */}
              {receipt.answers && receipt.answers.length > 0 && (
                <div className="rounded-xl border border-line bg-surface p-4 space-y-3">
                  <div className="flex items-center justify-between border-b border-line pb-2">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-ink-muted">
                      Questionnaire &amp; Custom Answers ({receipt.answers.length})
                    </h3>
                    <span className="text-[11px] text-ink-faint">Exact answers delivered</span>
                  </div>
                  <div className="space-y-2.5">
                    {receipt.answers.map((ans, idx) => (
                      <div key={idx} className="rounded-lg bg-surface-sunken p-3 text-xs border border-line">
                        <p className="font-semibold text-ink">{ans.questionText || ans.key}</p>
                        <p className="mt-1 text-ink-soft whitespace-pre-wrap">{ans.answerText}</p>
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
                      {receipt.idempotencyKey}
                    </span>
                  </div>
                  <div>
                    <span className="text-ink-muted block text-[11px]">Attempt ID</span>
                    <span className="font-mono text-ink-soft text-[11px] truncate block select-all">
                      {receipt.attemptId}
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
                  {receipt.confirmation.pageTitle && (
                    <div className="sm:col-span-2">
                      <span className="text-ink-muted block text-[11px]">Observed Page Title</span>
                      <span className="font-medium text-ink">{receipt.confirmation.pageTitle}</span>
                    </div>
                  )}
                  {receipt.confirmation.observedSignals && receipt.confirmation.observedSignals.length > 0 && (
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
                  <StatusPill tone={receipt.authorization.authorized ? 'emerald' : 'amber'}>
                    {receipt.authorization.authorized ? 'Authorized' : 'Unconfirmed'}
                  </StatusPill>
                </div>
                <div className="text-xs text-ink-soft space-y-1">
                  <p>
                    <span className="font-medium text-ink">Authorized by:</span>{' '}
                    {receipt.authorization.authorizedBy || 'Candidate (via Robin Web Application)'}
                  </p>
                  {receipt.authorization.authorizedAt && (
                    <p>
                      <span className="font-medium text-ink">Timestamp:</span>{' '}
                      {new Date(receipt.authorization.authorizedAt).toLocaleString()}
                    </p>
                  )}
                  <p className="text-[11px] text-ink-faint mt-1">
                    {receipt.authorization.consentSummary ||
                      'Explicit candidate consent was verified before dispatching automation payload to the employer portal.'}
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
                  const summary = `Submission Receipt\nCompany: ${receipt.companyName}\nJob: ${receipt.jobTitle}\nReceipt ID: ${receipt.receiptId}\nStatus: ${receipt.status}\nCV SHA-256: ${receipt.documents.cvSha256 || 'N/A'}\nConfirmation: ${receipt.confirmation.confirmationNumber || receipt.confirmation.receiptToken || 'N/A'}`;
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
