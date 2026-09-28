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
  INFO: 'border-sky-500/40 bg-sky-500/10',
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
        className="relative rounded-lg p-2 text-ink-soft transition-colors hover:bg-cream-100 hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600"
      >
        <span className="text-base leading-none">🔔</span>
        {unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-red-500 text-white text-[10px] font-bold flex items-center justify-center shadow">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-50 mt-2 w-96 max-w-[90vw] overflow-hidden rounded-xl border border-line bg-surface shadow-pop">
          <div className="flex items-center justify-between border-b border-line px-4 py-3">
            <span className="text-sm font-semibold text-ink">Notifications</span>
            {unread > 0 && (
              <button
                onClick={markAllRead}
                className="text-xs font-semibold text-forest-700 hover:text-forest-900"
              >
                Mark all read
              </button>
            )}
          </div>

          <div className="max-h-96 overflow-y-auto">
            {loading ? (
              <div className="px-4 py-6 text-center text-xs text-ink-muted">Loading…</div>
            ) : items.length === 0 ? (
              <div className="px-4 py-6 text-center text-xs text-ink-muted">
                No unread notifications
              </div>
            ) : (
              items.map((n) => (
                <button
                  key={n.id}
                  onClick={() => markReadAndFollow(n)}
                  className={`w-full border-l-2 px-4 py-3 text-left transition-colors hover:bg-cream-50/70 focus:outline-none focus-visible:bg-forest-50 ${
                    severityColor[n.severity] ?? severityColor.INFO
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span className="rounded bg-surface-sunken px-1.5 py-0.5 text-[9px] font-bold uppercase text-ink-soft">
                      {n.category}
                    </span>
                    <span className="ml-auto text-[10px] text-ink-faint">
                      {new Date(n.created_at).toLocaleTimeString()}
                    </span>
                  </div>
                  <div className="mt-1 text-sm font-medium text-ink">{n.title}</div>
                  {n.body && <div className="mt-0.5 line-clamp-2 text-xs text-ink-muted">{n.body}</div>}
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
};
