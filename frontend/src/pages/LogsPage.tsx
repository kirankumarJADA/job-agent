import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { AuditLog } from '../types';
import { NotificationItem } from '../components/NotificationBell';
import {
  DataTable,
  EmptyState,
  Loading,
  PageHeader,
  PageShell,
  SectionCard,
  StatusPill,
} from '../components/ui';

/**
 * System Logs & Audit Trail.
 *
 * Presentation-only redesign: same GET /audit + GET /notifications calls, the
 * unread-only toggle, mark-all-read and refresh handlers. Severity accents
 * carry over as light-theme pills and left borders.
 */
export const LogsPage: React.FC = () => {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [aLogs, notifs] = await Promise.all([
        apiFetch<AuditLog[]>('/audit').catch(() => [] as AuditLog[]),
        apiFetch<{ items: NotificationItem[] }>(
          unreadOnly ? '/notifications?unread=true&limit=100' : '/notifications?limit=100'
        ).catch(() => ({ items: [] as NotificationItem[] })),
      ]);
      setLogs(aLogs || []);
      setNotifications((notifs && notifs.items) || []);
    } finally {
      setLoading(false);
    }
  }, [unreadOnly]);

  useEffect(() => {
    load();
  }, [load]);

  const markAllRead = async () => {
    try {
      await apiFetch('/notifications/read-all', { method: 'POST' });
      setNotifications((list) => list.map((n) => ({ ...n, read_at: new Date().toISOString() })));
    } catch {
      /* non-fatal */
    }
  };

  const severityBorder = (severity: string) => {
    switch (severity) {
      case 'ERROR': return 'border-l-red-500';
      case 'WARN': return 'border-l-amber-500';
      default: return 'border-l-forest-300';
    }
  };

  const severityPill = (severity: string) => {
    switch (severity) {
      case 'ERROR': return <StatusPill tone="red">{severity}</StatusPill>;
      case 'WARN': return <StatusPill tone="amber">{severity}</StatusPill>;
      default: return <StatusPill tone="sky">{severity}</StatusPill>;
    }
  };

  if (loading) {
    return <PageShell><Loading>Loading notifications & audit trail…</Loading></PageShell>;
  }

  return (
    <PageShell>
      <div className="space-y-6">
        <PageHeader
          eyebrow="Transparency"
          title="System Logs & Audit Trail"
          subtitle="The append-only audit history of everything the agent did, plus the notification feed."
        />

        {/* Notifications feed */}
        <SectionCard
          title={`Notifications (${notifications.length})`}
          actions={
            <div className="flex items-center gap-3 text-xs">
              <label className="flex cursor-pointer items-center gap-1.5 font-medium text-ink-soft">
                <input
                  type="checkbox"
                  checked={unreadOnly}
                  onChange={(e) => setUnreadOnly(e.target.checked)}
                  className="h-4 w-4 rounded border-line accent-forest-700"
                />
                Unread only
              </label>
              <button
                type="button"
                onClick={markAllRead}
                className="font-semibold text-forest-700 hover:text-forest-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
              >
                Mark all read
              </button>
              <button
                type="button"
                onClick={load}
                aria-label="Refresh notifications and audit log"
                className="rounded-md p-1 text-ink-muted transition-colors hover:bg-cream-100 hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
              >
                <svg viewBox="0 0 20 20" fill="none" className="h-4 w-4" aria-hidden="true">
                  <path
                    d="M16.5 10a6.5 6.5 0 1 1-1.9-4.6M16.5 3.5v3h-3"
                    stroke="currentColor"
                    strokeWidth="1.6"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </button>
            </div>
          }
          bodyClassName="space-y-2"
        >
          {notifications.length === 0 ? (
            <p className="rounded-lg border border-dashed border-line bg-cream-50 px-4 py-3 text-xs leading-relaxed text-ink-muted">
              No notifications{unreadOnly ? ' unread' : ' yet'}. Pipeline events (job matched, CV/cover letter
              generated, submissions, recruiter replies, hard stops, approvals) fan out here.
            </p>
          ) : (
            notifications.map((n) => (
              <div
                key={n.id}
                className={`flex items-start justify-between gap-3 rounded-lg border border-l-2 border-line bg-cream-50/70 p-3.5 text-xs ${severityBorder(n.severity)} ${
                  n.read_at ? 'opacity-60' : ''
                }`}
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-1.5">
                    {severityPill(n.severity)}
                    <StatusPill tone="slate">{n.category}</StatusPill>
                    {n.job_id && (
                      <span className="font-mono text-[10px] text-ink-faint" title={n.job_id}>
                        job:{n.job_id.slice(0, 8)}
                      </span>
                    )}
                    {n.application_id && (
                      <span className="font-mono text-[10px] text-ink-faint" title={n.application_id}>
                        app:{n.application_id.slice(0, 8)}
                      </span>
                    )}
                  </div>
                  <div className="mt-1.5 font-semibold text-ink">{n.title}</div>
                  {n.body && <p className="mt-0.5 leading-relaxed text-ink-muted">{n.body}</p>}
                </div>
                <div className="shrink-0 text-right">
                  <div className="whitespace-nowrap text-[11px] text-ink-faint">
                    {new Date(n.created_at).toLocaleString()}
                  </div>
                  {n.read_at ? (
                    <span className="text-[10px] text-ink-faint">read</span>
                  ) : (
                    <span className="text-[10px] font-semibold text-amber-600">unread</span>
                  )}
                </div>
              </div>
            ))
          )}
        </SectionCard>

        {/* Audit history */}
        <SectionCard
          title={`Append-only audit history (${logs.length})`}
          bodyClassName="pt-2"
        >
          {logs.length === 0 ? (
            <EmptyState
              title="No audit records yet"
              body="Every state transition and command the agent performs is appended here with its actor and correlation ID."
            />
          ) : (
            <DataTable
              columns={['Timestamp', 'Action', 'Actor', 'Entity', 'Correlation ID']}
              rows={logs.map((log) => [
                <span className="whitespace-nowrap font-mono text-[11px] text-ink-muted">
                  {new Date(log.created_at).toLocaleString()}
                </span>,
                <span className="font-semibold text-forest-700">{log.action}</span>,
                <span className="text-ink-soft">{log.actor}</span>,
                <span className="font-mono text-xs">{log.entity_type || '—'}</span>,
                <span className="font-mono text-[11px] text-ink-muted">
                  {log.correlation_id ? `${log.correlation_id.slice(0, 8)}…` : '—'}
                </span>,
              ])}
            />
          )}
        </SectionCard>
      </div>
    </PageShell>
  );
};
