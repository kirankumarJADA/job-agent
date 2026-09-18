import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { AuditLog } from '../types';
import { NotificationItem } from '../components/NotificationBell';

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

  const severityStyle = (severity: string) => {
    switch (severity) {
      case 'ERROR': return 'border-l-2 border-red-500';
      case 'WARN': return 'border-l-2 border-amber-500';
      default: return 'border-l-2 border-indigo-500/50';
    }
  };

  const severityBadge = (severity: string) => {
    switch (severity) {
      case 'ERROR': return 'bg-red-500/20 text-red-400';
      case 'WARN': return 'bg-amber-500/20 text-amber-400';
      default: return 'bg-indigo-500/20 text-indigo-400';
    }
  };

  if (loading) {
    return <div className="p-12 text-center text-slate-400 text-sm">Loading notifications &amp; audit trail...</div>;
  }

  return (
    <div className="space-y-8 max-w-5xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-white tracking-tight">System Logs &amp; Audit Trail</h1>
        <p className="text-sm text-slate-400">Append-only immutable audit trail and the notification feed (Feature 8 fan-out)</p>
      </div>

      {/* Notifications feed */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-amber-400">
            Notifications ({notifications.length})
          </h2>
          <div className="flex items-center gap-3 text-xs">
            <label className="flex items-center gap-1.5 text-slate-300 cursor-pointer">
              <input
                type="checkbox"
                checked={unreadOnly}
                onChange={(e) => setUnreadOnly(e.target.checked)}
                className="accent-indigo-500"
              />
              Unread only
            </label>
            <button
              onClick={markAllRead}
              className="text-indigo-400 hover:text-indigo-300"
            >
              Mark all read
            </button>
            <button onClick={load} className="text-slate-400 hover:text-slate-200">↻</button>
          </div>
        </div>

        <div className="space-y-2">
          {notifications.length === 0 ? (
            <p className="text-xs text-slate-500 py-2">
              No notifications{unreadOnly ? ' unread' : ' yet'}. Pipeline events (job matched, CV/cover letter generated,
              submissions, recruiter replies, hard stops, approvals) fan out here.
            </p>
          ) : (
            notifications.map((n) => (
              <div
                key={n.id}
                className={`p-3 rounded-lg bg-slate-900 border border-slate-800 ${severityStyle(n.severity)} ${
                  n.read_at ? 'opacity-60' : ''
                } flex justify-between items-start gap-3 text-xs`}
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold ${severityBadge(n.severity)}`}>
                      {n.severity}
                    </span>
                    <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-800 text-slate-300">
                      {n.category}
                    </span>
                    {n.job_id && (
                      <span className="font-mono text-[10px] text-slate-500" title={n.job_id}>
                        job:{n.job_id.slice(0, 8)}
                      </span>
                    )}
                    {n.application_id && (
                      <span className="font-mono text-[10px] text-slate-500" title={n.application_id}>
                        app:{n.application_id.slice(0, 8)}
                      </span>
                    )}
                  </div>
                  <div className="font-semibold text-white mt-1">{n.title}</div>
                  {n.body && <p className="text-slate-400 mt-0.5">{n.body}</p>}
                </div>
                <div className="text-right shrink-0">
                  <div className="text-slate-500 text-[11px] whitespace-nowrap">
                    {new Date(n.created_at).toLocaleString()}
                  </div>
                  {n.read_at ? (
                    <span className="text-[10px] text-slate-600">read</span>
                  ) : (
                    <span className="text-[10px] text-amber-500">unread</span>
                  )}
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      {/* Audit Log Table */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
            Append-Only Audit History ({logs.length})
          </h2>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-slate-400 border-b border-slate-700/60">
              <tr>
                <th className="pb-2">Timestamp</th>
                <th className="pb-2">Action</th>
                <th className="pb-2">Actor</th>
                <th className="pb-2">Entity</th>
                <th className="pb-2">Correlation ID</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-700/40 text-slate-300">
              {logs.length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-4 text-center text-slate-500">
                    No audit records recorded yet.
                  </td>
                </tr>
              ) : (
                logs.map((log) => (
                  <tr key={log.id} className="hover:bg-slate-900/30">
                    <td className="py-2.5 text-slate-400 font-mono text-[11px]">
                      {new Date(log.created_at).toLocaleString()}
                    </td>
                    <td className="py-2.5 font-semibold text-indigo-400">{log.action}</td>
                    <td className="py-2.5 text-slate-300">{log.actor}</td>
                    <td className="py-2.5 font-mono text-slate-400">{log.entity_type || '—'}</td>
                    <td className="py-2.5 font-mono text-slate-500 text-[11px]">
                      {log.correlation_id ? `${log.correlation_id.slice(0, 8)}...` : '—'}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
