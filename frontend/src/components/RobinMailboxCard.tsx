import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import { Alert, SectionCard, StatusPill } from './ui';

/**
 * Robin-managed email (Phase 8.5): the candidate's own receive-only address
 * that Robin registers new employer accounts with, so verification codes reach
 * Robin without the personal Gmail connection.
 *
 * Honesty rules this card follows:
 * - the address is shown as working ("Active") only when the server says it is
 *   usable for registration, which requires a signed delivery to have proven
 *   that the domain receives mail;
 * - a reserved address that has not been proven is labelled "Not receiving
 *   yet" and is never described as in use;
 * - message rows are metadata only (the server redacts subjects and never
 *   returns bodies, codes or links).
 */

export type ManagedMailReadiness = 'NOT_CONFIGURED' | 'PENDING_RECEIVING_VERIFICATION' | 'ACTIVE';

export interface ManagedMailStatus {
  readiness: ManagedMailReadiness;
  provider?: string | null;
  domain?: string | null;
  address?: string | null;
  addressStatus?: string | null;
  usableForRegistration: boolean;
  receivingVerifiedAt?: string | null;
  lastInboundAt?: string | null;
  missing?: string[];
}

export interface ManagedMessage {
  id: string;
  from?: string | null;
  senderDomain?: string | null;
  subject?: string | null;
  receivedAt: string;
  usedForVerification: boolean;
  contentHeld: boolean;
  senderCheckFailed: boolean;
  spamFlagged: boolean;
}

const RECENT_LIMIT = 5;

export const RobinMailboxCard: React.FC = () => {
  const [status, setStatus] = useState<ManagedMailStatus | null>(null);
  const [messages, setMessages] = useState<ManagedMessage[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadMessages = useCallback(async () => {
    try {
      const res = await apiFetch<{ items: ManagedMessage[] }>(`/managed-mail/messages?limit=${RECENT_LIMIT}`);
      setMessages(res.items ?? []);
    } catch {
      // The address card is still useful without the message list.
      setMessages([]);
    }
  }, []);

  const load = useCallback(async () => {
    try {
      const next = await apiFetch<ManagedMailStatus>('/managed-mail/address');
      setStatus(next);
      if (next.address) await loadMessages();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load your Robin email address');
    }
  }, [loadMessages]);

  useEffect(() => { load(); }, [load]);

  const reserve = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const next = await apiFetch<ManagedMailStatus>('/managed-mail/address', { method: 'POST' });
      setStatus(next);
      if (next.address) await loadMessages();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not reserve a Robin email address');
    } finally {
      setBusy(false);
    }
  }, [loadMessages]);

  const active = status?.readiness === 'ACTIVE' && status.usableForRegistration === true;
  const disabled = status?.addressStatus === 'DISABLED';
  const pending = status?.readiness === 'PENDING_RECEIVING_VERIFICATION' && !disabled;

  return (
    <SectionCard title="Robin email address" hint="for employer sign-ups and verification codes">
      <div className="space-y-3 text-xs">
        {error && <Alert tone="error">{error}</Alert>}

        {status?.readiness === 'NOT_CONFIGURED' && (
          <p className="text-ink-muted">
            Robin-managed email is not set up on this deployment, so Robin does not create employer
            accounts for you yet. The operator needs to set {(status.missing ?? []).join(', ') || 'the managed-mail settings'} and
            route the mail domain to the inbound provider.
          </p>
        )}

        {status && status.readiness !== 'NOT_CONFIGURED' && !status.address && (
          <>
            <p className="text-ink-muted">
              Robin can give you a private address on {status.domain} for employer accounts. Employers send
              their verification codes there, so you do not need to connect your personal mailbox for sign-ups.
              {status.readiness !== 'ACTIVE' && ' Receiving on this domain has not been confirmed yet.'}
            </p>
            <button
              type="button"
              disabled={busy}
              onClick={reserve}
              className="rounded-md bg-forest-900 px-3 py-1.5 text-xs font-semibold text-cream-50 hover:bg-forest-800 disabled:opacity-50"
            >
              {busy ? 'Reserving…' : status.readiness === 'ACTIVE' ? 'Get my Robin address' : 'Reserve my Robin address'}
            </button>
          </>
        )}

        {status?.address && (
          <>
            <div className="flex flex-wrap items-center gap-2">
              {active && <StatusPill tone="emerald">Active</StatusPill>}
              {pending && <StatusPill tone="amber">Not receiving yet</StatusPill>}
              {disabled && <StatusPill tone="slate">Disabled</StatusPill>}
              <code className="break-all font-semibold text-ink">{status.address}</code>
            </div>
            {active && (
              <p className="text-ink-muted">
                New employer accounts are registered with this address, and Robin reads their verification
                codes from it. Robin only receives mail here — it never sends from it. Your Gmail connection
                is not needed for this and stays read-only for tracking.
              </p>
            )}
            {disabled && (
              <p className="text-ink-muted">This address is disabled. Robin does not use it for new employer accounts.</p>
            )}
            {pending && (
              <p className="text-ink-muted">
                Robin has not yet received a verified delivery for {status.domain}, so this address is reserved
                but not in use: Robin will not register any employer account with it. Once a test message sent
                to it arrives, it becomes active.
              </p>
            )}
            {status.lastInboundAt && (
              <p className="text-ink-muted">Last delivery to this domain: {new Date(status.lastInboundAt).toLocaleString()}</p>
            )}
            {messages.length > 0 && (
              <div>
                <p className="mb-1 font-semibold text-ink">Recent messages</p>
                <ul className="space-y-1">
                  {messages.map((message) => (
                    <li key={message.id} className="flex flex-wrap items-center gap-2 text-ink-muted">
                      <span className="font-medium text-ink">{message.from || message.senderDomain || 'Unknown sender'}</span>
                      <span>{message.subject || '(no subject)'}</span>
                      <span>{new Date(message.receivedAt).toLocaleString()}</span>
                      {message.usedForVerification && <StatusPill tone="forest">Used for verification</StatusPill>}
                      {message.senderCheckFailed && <StatusPill tone="red">Sender check failed</StatusPill>}
                      {message.spamFlagged && <StatusPill tone="red">Flagged as spam</StatusPill>}
                    </li>
                  ))}
                </ul>
                <p className="mt-1 text-ink-faint">
                  Robin keeps only the sender, a redacted subject and the time. Message content is kept only
                  briefly (two hours by default) and is erased as soon as a code is used.
                </p>
              </div>
            )}
          </>
        )}
      </div>
    </SectionCard>
  );
};
