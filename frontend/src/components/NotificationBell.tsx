import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiFetch } from '../api/client';

/**
 * Feature 8 frontend surface: the notification bell.
 *
 * - Polls GET /notifications/unread-count every 30s (lightweight; the
 *   full list is fetched only when the dropdown opens).
 * - Shows the unread badge and a dropdown of the most recent unread
 *   notifications with severity-coloured accents.
 * - Clicking one marks it read and follows its link (job → /jobs/:id,
 *   application → /applications/:id).
 * - Polling pauses while the tab is hidden (Page Visibility API) so an
 *   idle background tab doesn't churn the API.
 */
export interface NotificationItem {
  id: string;
  severity: string;
  category: string;
  title: string;
  body?: string | null;
  link?: string | null;
  dedup_key?: string | null;
  job_id?: string | null;
  application_id?: string | null;
  read_at?: string | null;
  created_at: string;
}

const severityColor: Record<string, string> = {
  ERROR: 'border-red-500/40 bg-red-500/10',
  WARN: 'border-amber-500/40 bg-amber-500/10',
  INFO: 'border-indigo-500/30 bg-indigo-500/10',
};

export const NotificationBell: React.FC = () => {
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  const poll = useCallback(async () => {
    if (document.hidden) return;
    try {
      const res = await apiFetch<{ unread_count: number }>('/notifications/unread-count');
      setUnread(res.unread_count ?? 0);
    } catch {
      /* backend unreachable — keep last count, don't spam console */
    }
  }, []);

  const loadList = useCallback(async () => {
    setLoading(true);
    try {
      const res = await apiFetch<{ items: NotificationItem[] }>('/notifications?unread=true&limit=20');
      setItems(res.items ?? []);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    poll();
    const timer = setInterval(poll, 30000);
    const onVisible = () => {
      if (!document.hidden) poll();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [poll]);

  useEffect(() => {
    if (open) loadList();
  }, [open, loadList]);

  useEffect(() => {
    const onClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  const markReadAndFollow = async (n: NotificationItem) => {
    try {
      await apiFetch(`/notifications/${n.id}/read`, { method: 'POST' });
    } catch {
      /* non-fatal */
    }
    setUnread((u) => Math.max(0, u - 1));
    setItems((list) => list.filter((i) => i.id !== n.id));
    setOpen(false);
    if (n.link) navigate(n.link);
  };

  const markAllRead = async () => {
    try {
      await apiFetch('/notifications/read-all', { method: 'POST' });
    } catch {
      /* non-fatal */
    }
    setUnread(0);
    setItems([]);
  };

  return (
    <div className="relative" ref={containerRef}>
      <button
        onClick={() => setOpen((o) => !o)}
        title="Notifications"
        className="relative p-2 rounded-lg hover:bg-slate-800 text-slate-300 hover:text-white transition-colors"
      >
        <span className="text-base leading-none">🔔</span>
        {unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-red-500 text-white text-[10px] font-bold flex items-center justify-center shadow">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 mt-2 w-96 max-w-[90vw] rounded-xl bg-slate-900 border border-slate-700 shadow-2xl z-50 overflow-hidden">
          <div className="flex items-center justify-between px-4 py-3 border-b border-slate-800">
            <span className="text-sm font-semibold text-white">Notifications</span>
            {unread > 0 && (
              <button
                onClick={markAllRead}
                className="text-xs text-indigo-400 hover:text-indigo-300"
              >
                Mark all read
              </button>
            )}
          </div>

          <div className="max-h-96 overflow-y-auto">
            {loading ? (
              <div className="px-4 py-6 text-center text-xs text-slate-500">Loading…</div>
            ) : items.length === 0 ? (
              <div className="px-4 py-6 text-center text-xs text-slate-500">
                No unread notifications
              </div>
            ) : (
              items.map((n) => (
                <button
                  key={n.id}
                  onClick={() => markReadAndFollow(n)}
                  className={`w-full text-left px-4 py-3 border-l-2 hover:bg-slate-800/60 transition-colors ${
                    severityColor[n.severity] ?? severityColor.INFO
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-slate-800 text-slate-300 uppercase">
                      {n.category}
                    </span>
                    <span className="text-[10px] text-slate-500 ml-auto">
                      {new Date(n.created_at).toLocaleTimeString()}
                    </span>
                  </div>
                  <div className="text-sm text-white mt-1">{n.title}</div>
                  {n.body && <div className="text-xs text-slate-400 mt-0.5 line-clamp-2">{n.body}</div>}
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
};
