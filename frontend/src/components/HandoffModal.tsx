import React, { useState } from 'react';
import type { HandoffSession } from '../types';
import { completeHandoff, cancelHandoff } from '../api/client';

interface HandoffModalProps {
  session: HandoffSession;
  onClose: () => void;
  onResumed: () => void;
}

export const HandoffModal: React.FC<HandoffModalProps> = ({ session, onClose, onResumed }) => {
  const [submitting, setSubmitting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const blockReasonLabels: Record<string, string> = {
    CAPTCHA: 'CAPTCHA Challenge',
    MFA: 'Multi-Factor Authentication (MFA)',
    CLOUDFLARE_TURNSTILE: 'Cloudflare Security Verification',
    EMPLOYER_SECURITY_CHECK: 'Employer Security Checkpoint',
  };

  const handleConfirm = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await completeHandoff(session.id);
      onResumed();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to confirm handoff completion. The session may have expired.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = async () => {
    if (!window.confirm('Are you sure you want to cancel this application handoff?')) {
      return;
    }
    setCancelling(true);
    setError(null);
    try {
      await cancelHandoff(session.id);
      onResumed();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to cancel handoff session.');
    } finally {
      setCancelling(false);
    }
  };

  const isExpired = new Date(session.expiresAt).getTime() <= Date.now();

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4" role="dialog" aria-modal="true">
      <div className="bg-slate-900 border border-amber-500/30 rounded-xl shadow-2xl max-w-lg w-full overflow-hidden animate-in fade-in zoom-in-95 duration-200">
        <div className="bg-gradient-to-r from-amber-950/60 to-slate-900 p-6 border-b border-amber-500/20">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-amber-500/20 border border-amber-500/30 text-amber-400">
              <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
              </svg>
            </div>
            <div>
              <h3 className="text-lg font-semibold text-white">Candidate Verification Required</h3>
              <span className="inline-flex items-center text-xs font-medium px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 mt-1">
                {blockReasonLabels[session.blockReason] || session.blockReason}
              </span>
            </div>
          </div>
        </div>

        <div className="p-6 space-y-4">
          <p className="text-sm text-slate-300">
            The employer portal has presented a security verification gate. Robin adheres to strict safety guidelines and{' '}
            <strong className="text-white">never attempts to solve or bypass CAPTCHAs, MFA, or anti-bot challenges automatically</strong>.
          </p>

          <div className="bg-slate-800/80 rounded-lg p-4 border border-slate-700/60 space-y-3">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400">Resolution Steps</h4>
            <ol className="text-sm text-slate-300 space-y-2 list-decimal list-inside">
              <li>
                <span>Open the employer&apos;s application page in your browser:</span>
                <div className="mt-1 pl-5">
                  <a
                    href={session.targetUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1.5 text-xs text-blue-400 hover:text-blue-300 underline font-medium"
                  >
                    Open Application Form
                    <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                    </svg>
                  </a>
                </div>
              </li>
              <li>Complete the verification challenge or enter your one-time code on the employer page.</li>
              <li>Return here and confirm completion below so Robin can resume guarded submission.</li>
            </ol>
          </div>

          <div className="text-xs text-slate-400 flex items-center justify-between">
            <span>Session Status: <strong className={isExpired ? 'text-red-400' : 'text-emerald-400'}>{isExpired ? 'EXPIRED' : session.status}</strong></span>
            <span>Expires at: {new Date(session.expiresAt).toLocaleTimeString()}</span>
          </div>

          {error && (
            <div className="p-3 rounded-lg bg-red-950/60 border border-red-500/40 text-red-200 text-xs">
              {error}
            </div>
          )}
        </div>

        <div className="bg-slate-950 px-6 py-4 border-t border-slate-800 flex items-center justify-between gap-3">
          <button
            type="button"
            onClick={handleCancel}
            disabled={cancelling || submitting}
            className="px-3 py-1.5 rounded-lg border border-red-500/30 text-red-400 hover:bg-red-500/10 text-xs font-medium transition disabled:opacity-50"
          >
            {cancelling ? 'Cancelling...' : 'Cancel Application'}
          </button>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="px-3 py-1.5 rounded-lg border border-slate-700 text-slate-300 hover:bg-slate-800 text-xs font-medium transition"
            >
              Dismiss
            </button>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={submitting || cancelling || isExpired}
              className="px-4 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold shadow-lg shadow-emerald-900/40 transition disabled:opacity-50"
            >
              {submitting ? 'Resuming...' : 'Confirm & Resume Application'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
