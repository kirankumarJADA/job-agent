import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import { apiFetch } from '../api/client';
import {
  Alert,
  EmptyState,
  Loading,
  PageHeader,
  PageShell,
  SecondaryButton,
  SectionCard,
  StatusPill,
} from '../components/ui';

/**
 * Review Queue (Phase 6) — where the human acts on matches that the decision
 * engine queued instead of auto-applying (MANUAL mode, mid-range scores,
 * exhausted quota). Every item is a NEEDS_REVIEW decision owned by the
 * signed-in candidate; approving it creates the application through the same
 * idempotent pipeline an automatic APPLY would have used.
 */
export const ReviewQueuePage: React.FC = () => {
  const [items, setItems] = useState<ReviewItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const [rejectReasons, setRejectReasons] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await apiFetch<{ items: ReviewItem[] }>('/review-queue');
      setItems(res.items || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load the review queue');
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setExpandedId(id);
    setDetail(null);
    try {
      const res = await apiFetch<ReviewDetail>(`/review-queue/${id}`);
      setDetail(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load review details');
    }
  }, [expandedId]); // eslint-disable-line react-hooks/exhaustive-deps

  const act = async (id: string, action: 'approve' | 'reject' | 'pause' | 'resume') => {
    setBusyId(id + action);
    setError(null);
    try {
      await apiFetch(`/review-queue/${id}/${action}`, {
        method: 'POST',
        ...(action === 'reject' ? { body: JSON.stringify({ reason: rejectReasons[id] || '' }) } : {}),
      });
      if (action === 'approve') setExpandedId(null);
      await load();
      if (expandedId === id && action !== 'pause' && action !== 'resume') setExpandedId(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : `${action} failed`);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Agent operations"
          title="Review Queue"
          subtitle="Matches that need your decision before Robin creates the application. Approving creates it exactly as an automatic APPLY would — nothing is ever submitted here."
          actions={<SecondaryButton href="/applications">Open Applications</SecondaryButton>}
        />

        {error && <Alert tone="error">{error}</Alert>}
        {items === null && !error && <Loading>Loading review queue…</Loading>}

        {items !== null && items.length === 0 && (
          <EmptyState
            title="Nothing needs your review"
            body="Matches that the decision engine queues for human review will appear here. Everything else is already handled automatically."
          />
        )}

        {items !== null && (
          <p className="text-xs font-semibold text-ink-muted" aria-live="polite">
            {items.length} pending review {items.length === 1 ? 'item' : 'items'}
          </p>
        )}

        {items !== null && items.length > 0 && (
          <div className="space-y-3">
            {items.map((item) => (
              <SectionCard key={item.decisionId} title={item.jobTitle}>
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <StatusPill tone="amber">{item.decision === 'PAUSED' ? 'Paused' : 'Needs review'}</StatusPill>
                      <span className="text-xs font-semibold text-ink-faint">Score {item.matchScore}/100</span>
                    </div>
                    <p className="truncate text-xs text-ink-muted">{item.companyName}</p>
                    <p className="text-xs text-ink-muted">{item.reason}</p>
                    <p className="text-[11px] text-ink-faint">
                      {item.recommendation || 'Match'} · {item.applicationMode || 'Default'} mode · Hard filters: {item.hardFilterOutcome || 'unknown'}
                      {item.jobStale ? ' · Posting may be stale' : ''}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    <button
                      type="button"
                      onClick={() => (expandedId === item.decisionId ? setExpandedId(null) : openDetail(item.decisionId))}
                      className="rounded-md border border-line px-3 py-1 text-xs font-semibold text-ink-soft hover:border-forest-300"
                    >
                      {expandedId === item.decisionId ? 'Hide details' : 'Inspect'}
                    </button>
                  </div>
                </div>

                {expandedId === item.decisionId && (
                  <div className="mt-3 space-y-3 border-t border-line pt-3 text-xs">
                    {!detail && <Loading>Loading details…</Loading>}
                    {detail && (
                      <>
                        <div className="grid gap-2 sm:grid-cols-2">
                          <p><span className="text-ink-muted">Company:</span> {detail.companyName || '—'}</p>
                          <p><span className="text-ink-muted">Location:</span> {detail.location || '—'}</p>
                          <p><span className="text-ink-muted">Remote type:</span> {detail.remoteType || '—'}</p>
                          <p>
                            <span className="text-ink-muted">Salary:</span>{' '}
                            {detail.salaryMin ? `£${Number(detail.salaryMin).toLocaleString()}–£${Number(detail.salaryMax ?? '').toLocaleString()}` : 'Not disclosed'}
                          </p>
                        </div>
                        <p className="text-ink-muted">
                          Decision reason: {detail.reason}
                        </p>
                        <p className="text-ink-muted">
                          Hard-filter outcome: {detail.hardFilterOutcome || 'Unknown'}.
                          {detail.hardFilterReasons && detail.hardFilterReasons !== '[]' ? ` Reasons: ${detail.hardFilterReasons}` : ' No stored hard-filter rejection reasons.'}
                        </p>
                        <p className="text-ink-muted">
                          Application: {detail.applicationExists ? 'exists' : 'not created'} ·
                          Preparation events: {detail.preparationExists ? 'recorded' : 'not started'}
                          {detail.jobStale ? ' · Posting is stale; approval is blocked until refreshed.' : ''}
                        </p>
                        <p className="text-ink-faint">
                          Decision updated: {detail.updatedAt ? new Date(detail.updatedAt).toLocaleString() : '—'}
                        </p>
                        <p className="text-ink-muted">
                          Full posting on the{' '}
                          <Link className="font-semibold text-forest-700 hover:text-forest-900" to={`/jobs/${detail.jobId}`}>
                            job detail page
                          </Link>{' '}
                          (verified profile, tailored CV and cover letter are prepared there after approval).
                        </p>
                        <div className="flex flex-wrap gap-2 pt-1">
                          {item.decision === 'NEEDS_REVIEW' && (
                            <>
                              <button
                                type="button"
                                disabled={busyId === item.decisionId + 'approve'}
                                onClick={() => act(item.decisionId, 'approve')}
                                className="rounded-md bg-forest-900 px-3 py-1.5 text-xs font-semibold text-cream-50 hover:bg-forest-800 disabled:opacity-50"
                              >
                                {busyId === item.decisionId + 'approve' ? 'Approving…' : 'Approve & create application'}
                              </button>
                              <label className="flex min-w-[220px] flex-1 flex-col gap-1 text-ink-muted">
                                Rejection reason (optional)
                                <textarea
                                  value={rejectReasons[item.decisionId] || ''}
                                  onChange={(event) => setRejectReasons((previous) => ({ ...previous, [item.decisionId]: event.target.value }))}
                                  maxLength={1000}
                                  rows={2}
                                  className="rounded-md border border-line bg-white px-2 py-1 text-xs text-ink"
                                  placeholder="Why are you skipping this role?"
                                />
                              </label>
                              <button
                                type="button"
                                disabled={busyId === item.decisionId + 'reject'}
                                onClick={() => act(item.decisionId, 'reject')}
                                className="rounded-md border border-red-300 px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:opacity-50"
                              >
                                {busyId === item.decisionId + 'reject' ? 'Rejecting…' : 'Reject'}
                              </button>
                              <button
                                type="button"
                                disabled={busyId === item.decisionId + 'pause'}
                                onClick={() => act(item.decisionId, 'pause')}
                                className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:bg-cream-50 disabled:opacity-50"
                              >
                                Pause
                              </button>
                            </>
                          )}
                          {item.decision === 'PAUSED' && (
                            <button
                              type="button"
                              disabled={busyId === item.decisionId + 'resume'}
                              onClick={() => act(item.decisionId, 'resume')}
                              className="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:bg-cream-50 disabled:opacity-50"
                            >
                              Resume review
                            </button>
                          )}
                        </div>
                        <p className="text-[11px] text-ink-faint">
                          Approving creates the application and starts preparation. Actual submission to the employer
                          remains disabled and always requires explicit approval at the later gate.
                        </p>
                      </>
                    )}
                  </div>
                )}
              </SectionCard>
            ))}
          </div>
        )}
      </div>
    </PageShell>
  );
};

interface ReviewItem {
  decisionId: string;
  jobId: string;
  jobTitle: string;
  companyName: string;
  location: string;
  applicationUrl: string;
  matchScore: number;
  recommendation?: string;
  hardFilterOutcome?: string;
  hardFilterReasons?: string;
  applicationMode?: string;
  applicationExists?: boolean;
  preparationExists?: boolean;
  jobStale?: boolean;
  decision: 'NEEDS_REVIEW' | 'PAUSED';
  reason: string;
  createdAt: string;
  updatedAt?: string;
}

interface ReviewDetail extends ReviewItem {
  remoteType: string;
  salaryMin: number | null;
  salaryMax: number | null;
  salaryCurrency: string;
  descriptionText: string;
  applicationId: string | null;
  hardFilterOutcome?: string;
  hardFilterReasons?: string;
  applicationMode?: string;
  recommendation?: string;
  applicationExists?: boolean;
  preparationExists?: boolean;
  jobStale?: boolean;
  updatedAt?: string;
}
