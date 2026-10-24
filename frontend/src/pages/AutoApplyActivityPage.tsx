import React, { useCallback, useEffect, useState } from 'react';

import { apiFetch } from '../api/client';
import type { ApprovalActivity } from '../types';
import {
  Alert,
  Loading,
  MetricCard,
  PageHeader,
  PageShell,
  SecondaryButton,
  SectionCard,
  StatusPill,
} from '../components/ui';

/**
 * Auto Apply Activity (Robin x Tsenta continuation).
 *
 * A read-only run-history view over the decision engine's own persisted
 * decisions (GET /api/v1/approval-rules/activity) — how many matches were
 * considered today and over the last 7 days, how each one resolved, and
 * today's usage against the daily application quota that Approval Rules
 * already promises is enforced. This page never computes anything itself;
 * it only renders what the backend already decided and recorded, so it can
 * never drift from the engine's actual behaviour.
 *
 * A standalone page (not a section of Approval Rules) so a slow or failing
 * activity fetch can never affect the approval-rule form's own load path.
 */

const DECISION_LABELS: Record<string, string> = {
  AUTO_APPLY: 'Auto-applied',
  NEEDS_REVIEW: 'Sent to review',
  SKIP: 'Skipped (below threshold)',
  APPROVED: 'Approved by you',
  REJECTED: 'Rejected by you',
  PAUSED: 'Paused',
  EXPIRED: 'Expired unreviewed',
};

const DECISION_ORDER = ['AUTO_APPLY', 'NEEDS_REVIEW', 'APPROVED', 'REJECTED', 'PAUSED', 'EXPIRED', 'SKIP'];

function decisionLabel(key: string): string {
  return DECISION_LABELS[key] ?? key;
}

function sortedEntries(byDecision: Record<string, number>): [string, number][] {
  return Object.entries(byDecision).sort(
    ([a], [b]) => DECISION_ORDER.indexOf(a) - DECISION_ORDER.indexOf(b),
  );
}

const WindowBreakdown: React.FC<{ title: string; considered: number; byDecision: Record<string, number> }> = ({
  title,
  considered,
  byDecision,
}) => {
  const entries = sortedEntries(byDecision);
  return (
    <SectionCard title={title} actions={<StatusPill tone="slate">{considered} considered</StatusPill>}>
      {entries.length === 0 ? (
        <p className="text-sm text-ink-muted">No matches were considered in this window.</p>
      ) : (
        <ul className="divide-y divide-line">
          {entries.map(([decision, count]) => (
            <li key={decision} className="flex items-center justify-between py-2 text-sm">
              <span className="text-ink-muted">{decisionLabel(decision)}</span>
              <span className="font-mono font-semibold text-ink">{count}</span>
            </li>
          ))}
        </ul>
      )}
    </SectionCard>
  );
};

export const AutoApplyActivityPage: React.FC = () => {
  const [activity, setActivity] = useState<ApprovalActivity | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch<ApprovalActivity>('/approval-rules/activity');
      setActivity(res);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Could not load Auto Apply activity.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const refreshButton = (
    <SecondaryButton type="button" onClick={() => void load()} disabled={loading}>
      {loading ? 'Refreshing…' : 'Refresh'}
    </SecondaryButton>
  );

  if (loading) {
    return (
      <PageShell>
        <Loading>Loading Auto Apply activity…</Loading>
      </PageShell>
    );
  }

  if (error || !activity) {
    return (
      <PageShell>
        <div className="space-y-6 max-w-4xl">
          <PageHeader
            eyebrow="Run history"
            title="Auto Apply Activity"
            subtitle="How the decision engine has handled your matches, over time."
            actions={refreshButton}
          />
          <Alert tone="error">{error ?? 'Could not load Auto Apply activity.'}</Alert>
        </div>
      </PageShell>
    );
  }

  const quota = activity.dailyApplicationQuota;
  const quotaTone = quota.remaining <= 0 ? 'red' : quota.remaining <= quota.limit * 0.2 ? 'amber' : 'forest';

  return (
    <PageShell>
      <div className="space-y-6 max-w-4xl">
        <PageHeader
          eyebrow="Run history"
          title="Auto Apply Activity"
          subtitle="A read-only view of what the decision engine has actually done — this page never changes any setting."
          actions={refreshButton}
        />

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <MetricCard label="Considered today" value={activity.today.considered} />
          <MetricCard label="Considered last 7 days" value={activity.last7Days.considered} />
          <MetricCard
            label="Daily application quota"
            value={`${quota.used} / ${quota.limit}`}
            accent={<StatusPill tone={quotaTone}>{quota.remaining} left today</StatusPill>}
          />
        </div>

        <WindowBreakdown title="Today" considered={activity.today.considered} byDecision={activity.today.byDecision} />
        <WindowBreakdown
          title="Last 7 days"
          considered={activity.last7Days.considered}
          byDecision={activity.last7Days.byDecision}
        />

        <div className="rounded-lg border border-line bg-surface-sunken px-5 py-4 text-xs leading-relaxed text-ink-soft">
          <p className="font-semibold text-ink">Where these numbers come from</p>
          <p className="mt-1">
            Every row here is a decision the engine already recorded when a match was evaluated — this page does
            not run any additional scoring or create or change any application. Configure how matches are decided
            on the{' '}
            <a href="/approval-rules" className="font-medium text-forest-700 underline hover:text-forest-900">
              Approval Rules
            </a>{' '}
            page.
          </p>
        </div>
      </div>
    </PageShell>
  );
};
