import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '../api/client';
import type { SandboxRegistration, SandboxRegistrationsResponse } from '../types';
import {
  Alert,
  EmptyState,
  Label,
  Loading,
  PrimaryButton,
  SecondaryButton,
  SectionCard,
  Select,
  StatusPill,
  TextInput,
} from './ui';

export const SandboxRegistrationsPanel: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [revokingId, setRevokingId] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<SandboxRegistrationsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const [atsPlatform, setAtsPlatform] = useState('GREENHOUSE');
  const [postingUrl, setPostingUrl] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiFetch<SandboxRegistrationsResponse>('/sandbox-registrations');
      setCatalog(data);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to load sandbox registrations.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = postingUrl.trim();
    if (!trimmed) {
      setFeedback({ type: 'error', text: 'Enter a canonical Greenhouse sandbox posting URL.' });
      return;
    }
    setSubmitting(true);
    setFeedback(null);
    try {
      // Never send profileId, approvedBy, or permittedDomain from client UI; server derives all three.
      const created = await apiFetch<SandboxRegistration>('/sandbox-registrations', {
        method: 'POST',
        body: JSON.stringify({ atsPlatform, postingUrl: trimmed }),
      });
      setPostingUrl('');
      setFeedback({
        type: 'success',
        text: `Authorized sandbox posting ${created.postingUrl} (${created.permittedDomain}).`,
      });
      await load();
    } catch (err: unknown) {
      setFeedback({
        type: 'error',
        text: err instanceof Error ? err.message : 'Could not register sandbox posting.',
      });
    } finally {
      setSubmitting(false);
    }
  };

  const handleRevoke = async (id: string) => {
    setRevokingId(id);
    setFeedback(null);
    try {
      await apiFetch<SandboxRegistration>(`/sandbox-registrations/${id}/revoke`, { method: 'POST' });
      setFeedback({ type: 'success', text: 'Sandbox registration revoked. Audit history preserved.' });
      await load();
    } catch (err: unknown) {
      setFeedback({
        type: 'error',
        text: err instanceof Error ? err.message : 'Could not revoke sandbox registration.',
      });
    } finally {
      setRevokingId(null);
    }
  };

  if (loading) {
    return <Loading>Loading sandbox registrations…</Loading>;
  }

  if (error) {
    return (
      <Alert tone="error">
        <div className="flex flex-col gap-2">
          <p>{error}</p>
          <div>
            <SecondaryButton onClick={() => void load()}>Retry</SecondaryButton>
          </div>
        </div>
      </Alert>
    );
  }

  return (
    <div className="space-y-6">
      {feedback && (
        <Alert tone={feedback.type === 'success' ? 'success' : 'error'}>{feedback.text}</Alert>
      )}

      <SectionCard
        title="Register Candidate-Owned Sandbox Posting"
        hint="Server-verified domain & SSRF rules"
      >
        {catalog && !catalog.configured ? (
          <Alert tone="info">
            Sandbox registrations are disabled on this server because no allowlisted sandbox domains are configured.
          </Alert>
        ) : (
          <form onSubmit={handleRegister} className="space-y-4">
            {catalog && (
              <div className="rounded-lg border border-line bg-surface-sunken px-3.5 py-2.5 text-xs text-ink-muted space-y-1">
                <p>
                  <span className="font-semibold text-ink">Allowed domains:</span>{' '}
                  {catalog.allowedDomains.join(', ')}
                </p>
                {catalog.allowedPathPrefixes.length > 0 && (
                  <p>
                    <span className="font-semibold text-ink">Allowed path prefixes:</span>{' '}
                    {catalog.allowedPathPrefixes.join(', ')}
                  </p>
                )}
                <p>
                  The server derives permitted domain, candidate ownership, and canonical URL directly from your session
                  and server configuration.
                </p>
              </div>
            )}

            <div className="grid gap-4 sm:grid-cols-3">
              <div>
                <Label htmlFor="sandbox-platform">ATS Platform</Label>
                <Select
                  id="sandbox-platform"
                  value={atsPlatform}
                  onChange={(e) => setAtsPlatform(e.target.value)}
                >
                  <option value="GREENHOUSE">GREENHOUSE</option>
                </Select>
              </div>
              <div className="sm:col-span-2">
                <Label htmlFor="sandbox-posting-url">Canonical Sandbox Posting URL</Label>
                <TextInput
                  id="sandbox-posting-url"
                  type="url"
                  placeholder="https://boards.greenhouse.io/robin-sandbox/jobs/12345"
                  value={postingUrl}
                  onChange={(e) => setPostingUrl(e.target.value)}
                  disabled={submitting}
                />
              </div>
            </div>

            <div className="flex justify-end">
              <PrimaryButton type="submit" disabled={submitting}>
                {submitting ? 'Registering…' : 'Authorize Sandbox Posting'}
              </PrimaryButton>
            </div>
          </form>
        )}
      </SectionCard>

      <SectionCard
        title="Authorized Sandbox History"
        hint={`${catalog?.items.length ?? 0} total`}
      >
        {!catalog || catalog.items.length === 0 ? (
          <EmptyState
            title="No sandbox postings authorized yet"
            body="Authorize a server-allowlisted Greenhouse sandbox posting URL above to verify sandbox receipts."
          />
        ) : (
          <ul className="divide-y divide-line">
            {catalog.items.map((item) => (
              <li
                key={item.id}
                className="flex flex-col gap-3 py-3.5 sm:flex-row sm:items-center sm:justify-between"
              >
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-xs font-semibold uppercase tracking-wide text-forest-700">
                      {item.atsPlatform}
                    </span>
                    <StatusPill tone={item.active ? 'emerald' : 'slate'}>
                      {item.active ? 'Active' : 'Revoked'}
                    </StatusPill>
                    <span className="font-mono text-xs text-ink-faint">{item.permittedDomain}</span>
                  </div>
                  <p className="break-all font-mono text-xs text-ink">{item.postingUrl}</p>
                  <p className="text-xs text-ink-muted">
                    Approved by {item.approvedBy} at {new Date(item.approvedAt).toLocaleString()}
                    {item.revokedAt && ` • Revoked ${new Date(item.revokedAt).toLocaleString()}`}
                  </p>
                </div>
                {item.active && (
                  <div className="shrink-0">
                    <SecondaryButton
                      type="button"
                      disabled={revokingId === item.id}
                      onClick={() => void handleRevoke(item.id)}
                    >
                      {revokingId === item.id ? 'Revoking…' : 'Revoke'}
                    </SecondaryButton>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  );
};
