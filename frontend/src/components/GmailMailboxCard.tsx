import React, { useCallback, useEffect, useState } from 'react';
import { API_BASE, apiFetch } from '../api/client';
import { Alert, SectionCard, StatusPill } from './ui';
import { gmailAuthorizeUrl } from './gmailMailboxLinks';

/**
 * Gmail mailbox connection card (Phase 8.4, TRACK): connect the candidate's
 * own Gmail account read-only so employer messages flow into TRACK, show the
 * connected identity, scopes in plain language, sync state, and errors.
 * Robin never sends, deletes, labels or modifies mail.
 */
export const GmailMailboxCard: React.FC = () => {
  const [status, setStatus] = useState<GmailStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setStatus(await apiFetch<GmailStatus>('/integrations/gmail/status'));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load Gmail status');
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const connect = useCallback(() => {
    setError(null);
    setNotice(null);
    // Full-page redirect to Google's consent screen; the callback returns to
    // /applications?track&gmail=<status>.
    window.location.href = `${API_BASE.replace(/\/api\/v1$/, '')}/integrations/gmail/authorize`;
  }, []);

  const sync = useCallback(async () => {
    setBusy(true); setError(null); setNotice(null);
    try {
      const res = await apiFetch<{ status: string; ingested: number; considered: number }>(
        '/integrations/gmail/sync', { method: 'POST' });
      setNotice(`${res.status === 'PARTIAL' ? 'Partial sync' : 'Sync finished'}: ${res.ingested} new message(s) considered out of ${res.considered} retrieved.${res.status === 'PARTIAL' ? ' Run sync again to continue safely.' : ''}`);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sync failed');
    } finally {
      setBusy(false);
    }
  }, [load]);

  const disconnect = useCallback(async () => {
    setBusy(true); setError(null); setNotice(null);
    try {
      await apiFetch('/integrations/gmail/disconnect', { method: 'POST' });
      setNotice('Gmail disconnected. Previously captured messages and evidence are kept.');
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Disconnect failed');
    } finally {
      setBusy(false);
    }
  }, [load]);

  const connected = status?.connected === true;

  return (
    <SectionCard title="Gmail connection (read-only)">
      <div className="space-y-3 text-xs">
        {error && <Alert tone="error">{error}</Alert>}
        {notice && <Alert tone="success">{notice}</Alert>}

        {status && !status.configured && (
          <p className="text-ink-muted">
            Gmail sync is not configured on this deployment. The operator needs to set
            GMAIL_CLIENT_ID, GMAIL_CLIENT_SECRET, GMAIL_REDIRECT_URI, APP_GMAIL_TOKEN_KEY,
            APP_GMAIL_STATE_SECRET and APP_FRONTEND_URL.
          </p>
        )}

        {status?.configured && !connected && (
          <>
            <p className="text-ink-muted">
              Connect your Gmail account read-only so employer replies and acknowledgements
              flow into TRACK automatically. Robin can read messages only — it can never
              send, delete, label or modify anything in your mailbox.
            </p>
            <button
              type="button"
              onClick={connect}
              className="rounded-md bg-forest-900 px-3 py-1.5 text-xs font-semibold text-cream-50 hover:bg-forest-800"
            >
              Connect Gmail (read-only)
            </button>
          </>
        )}

        {status?.configured && connected && (
          <>
            <div className="flex items-center gap-2">
              <StatusPill tone="emerald">Connected</StatusPill>
              <span className="font-semibold text-ink">{status.accountEmail}</span>
            </div>
            <p className="text-ink-muted">
              Robin has read-only access to this mailbox ({status.scopes}). It reads new
              employer messages to track your applications — it cannot send or change anything.
            </p>
            {status.lastSyncAt && (
              <p className="text-ink-muted">
                Last sync: {new Date(status.lastSyncAt).toLocaleString()}
                {status.lastSyncStatus ? ` (${status.lastSyncStatus})` : ''}
              </p>
            )}
            {status.lastSyncError && <p className="text-red-400">Last sync error: {status.lastSyncError}</p>}
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                disabled={busy}
                onClick={sync}
                className="rounded-md bg-forest-900 px-3 py-1.5 text-xs font-semibold text-cream-50 hover:bg-forest-800 disabled:opacity-50"
              >
                {busy ? 'Syncing…' : 'Sync now'}
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={disconnect}
                className="rounded-md border border-red-300 px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50 disabled:opacity-50"
              >
                Disconnect Gmail
              </button>
            </div>
          </>
        )}
      </div>
    </SectionCard>
  );
};

interface GmailStatus {
  configured: boolean;
  connected: boolean;
  accountEmail?: string;
  scopes?: string;
  status?: string;
  lastSyncAt?: string;
  lastSyncStatus?: string;
  lastSyncError?: string;
}


