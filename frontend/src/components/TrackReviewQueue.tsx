import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import type { TrackApplicationRef, TrackReviewQueueResponse } from '../types';
import { Alert, Loading, SectionCard } from './ui';
import { type TrackFailure, TrackFailureNotice, TrackReviewCard, classifyTrackError } from './trackShared';

/**
 * Employer messages waiting for the candidate (Phase 8.3): proposed matches
 * to similar applications, messages added to one application that identify
 * another, messages Robin could not tie to any application, and inferred
 * meanings that only the candidate can confirm. Robin never resolves these
 * itself; each decision is recorded as the candidate's, never as verified
 * evidence.
 */
export const TrackReviewQueue: React.FC<{
  applications?: TrackApplicationRef[];
  refreshKey?: number;
  onChanged?: () => void;
}> = ({ applications = [], refreshKey = 0, onChanged }) => {
  const [data, setData] = useState<TrackReviewQueueResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<TrackFailure | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  const reload = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setFailure(null);
    apiFetch<TrackReviewQueueResponse>('/track/reviews')
      .then((value) => {
        if (!cancelled) setData(value);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setData(null);
        setFailure(classifyTrackError(err, 'Please try again in a moment.'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [version, refreshKey]);

  const decided = (message: string) => {
    setNotice(message);
    reload();
    onChanged?.();
  };

  const items = data?.items ?? [];
  return (
    <SectionCard
      title="Employer messages to review"
      hint={data ? `${items.length} waiting` : undefined}
      className="mb-4"
    >
      {loading && !data && !failure && <Loading>Loading employer messages…</Loading>}
      {failure && <TrackFailureNotice failure={failure} what="Employer messages" onRetry={reload} />}
      {notice && (
        <div className="mb-3">
          <Alert tone="info">{notice}</Alert>
        </div>
      )}
      {data && items.length === 0 && (
        <p className="text-sm text-ink-muted">No employer messages need your review.</p>
      )}
      {items.length > 0 && (
        <div className="space-y-3">
          {items.map((item) => (
            <TrackReviewCard
              key={`${item.emailId}-${item.decisionVersion}`}
              item={item}
              fallbackApplications={applications}
              onDecided={decided}
              onStale={reload}
            />
          ))}
        </div>
      )}
      {data && !data.mailboxConnected && (
        <p className="mt-3 text-xs text-ink-faint">
          Robin cannot read your mailbox: no mailbox connection exists in this version. Messages appear here only when you
          add them to an application.
        </p>
      )}
    </SectionCard>
  );
};
