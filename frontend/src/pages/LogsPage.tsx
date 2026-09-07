import React, { useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { AuditLog } from '../types';

export const LogsPage: React.FC = () => {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [notifications, setNotifications] = useState<Array<{ id: string; severity: string; category: string; title: string; body?: string; created_at: string }>>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      apiFetch<AuditLog[]>('/audit').catch(() => []),
      apiFetch<Array<{ id: string; severity: string; category: string; title: string; body?: string; created_at: string }>>('/notifications').catch(() => []),
    ])
      .then(([aLogs, notifs]) => {
        setLogs(aLogs || []);
        setNotifications(notifs || []);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <div className="p-12 text-center text-slate-400 text-sm">Loading audit logs &amp; events...</div>;
  }

  return (
    <div className="space-y-8 max-w-5xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-white tracking-tight">System Logs &amp; Audit Trail</h1>
        <p className="text-sm text-slate-400">Append-only immutable audit trail and transactional outbox event delivery log</p>
      </div>

      {/* Notifications / DLQ alerts */}
      {notifications.length > 0 && (
        <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-3">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-amber-400">
            System Notifications &amp; DLQ Events ({notifications.length})
          </h2>
          <div className="space-y-2">
            {notifications.map((n) => (
              <div key={n.id} className="p-3 rounded-lg bg-slate-900 border border-slate-800 flex justify-between items-center text-xs">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-500/20 text-amber-400">
                      {n.category}
                    </span>
                    <span className="font-semibold text-white">{n.title}</span>
                  </div>
                  {n.body && <p className="text-slate-400 mt-1">{n.body}</p>}
                </div>
                <span className="text-slate-500 text-[11px] whitespace-nowrap">
                  {new Date(n.created_at).toLocaleTimeString()}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Audit Log Table */}
      <div className="p-6 rounded-xl bg-slate-800/60 border border-slate-700/60 space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-wider text-slate-300">
          Append-Only Audit History ({logs.length})
        </h2>

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
                    No audit records recorded yet. Mutations to profile, preferences, or auth write here automatically.
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
                    <td className="py-2.5 font-mono text-slate-400">
                      {log.entity_type ? `${log.entity_type}` : '—'}
                    </td>
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
