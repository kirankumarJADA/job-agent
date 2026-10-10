import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import type { PrepReadiness } from '../types';
import { StatusPill } from './ui';

const TONE: Record<PrepReadiness['overall'], 'slate' | 'red' | 'amber' | 'emerald'> = {
  NOT_STARTED: 'slate',
  BLOCKED: 'red',
  IN_PROGRESS: 'amber',
  READY_FOR_REVIEW: 'emerald',
};

/**
 * Preparation state of one application, read from the readiness API (derived
 * from its linked CV, letter and answers). The application's own status
 * (e.g. READY_TO_APPLY) is not used as evidence of preparation.
 */
export const ApplicationPrepStatus: React.FC<{ jobId: string; applicationId: string }> = ({ jobId, applicationId }) => {
  const [readiness, setReadiness] = useState<PrepReadiness | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    apiFetch<PrepReadiness>(`/prep/jobs/${jobId}/readiness?applicationId=${applicationId}`)
      .then((value) => { if (!cancelled) setReadiness(value); })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [jobId, applicationId]);

  return (
    <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-3 text-xs">
      <span className="font-semibold text-ink-soft">Preparation:</span>
      {failed ? (
        <span className="text-red-700">status unavailable</span>
      ) : !readiness ? (
        <span className="text-ink-muted">checking…</span>
      ) : (
        <>
          <StatusPill tone={TONE[readiness.overall]}>{readiness.overallLabel}</StatusPill>
          {readiness.blockers.length > 0 && <span className="text-red-700">{readiness.blockers.length} blocker(s)</span>}
          {readiness.actions.length > 0 && <span className="text-amber-800">{readiness.actions.length} action(s) remaining</span>}
        </>
      )}
      <Link to={`/jobs/${jobId}#application-package`} className="ml-auto font-semibold text-forest-700 hover:underline">
        Open PREP workspace →
      </Link>
    </div>
  );
};
