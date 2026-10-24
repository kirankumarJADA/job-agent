// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { apiFetch } from '../api/client';
import { RobinMailboxCard, type ManagedMailStatus } from './RobinMailboxCard';

vi.mock('../api/client', () => ({ apiFetch: vi.fn() }));

const mocked = vi.mocked(apiFetch);
const ADDRESS = 'u7kq2m9x4vb3tn5p@mail.robin.example';

function serve(status: ManagedMailStatus, afterReserve?: ManagedMailStatus, messages: unknown[] = []) {
  mocked.mockImplementation((async (path: string, options?: RequestInit) => {
    if (path === '/managed-mail/address' && options?.method === 'POST') return afterReserve ?? status;
    if (path === '/managed-mail/address') return status;
    if (path.startsWith('/managed-mail/messages')) return { items: messages };
    throw new Error(`Unexpected API call: ${path}`);
  }) as never);
}

describe('RobinMailboxCard', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it('names the missing settings and offers nothing when managed mail is not configured', async () => {
    serve({ readiness: 'NOT_CONFIGURED', usableForRegistration: false,
      missing: ['APP_MANAGED_MAIL_DOMAIN', 'MAILGUN_WEBHOOK_SIGNING_KEY'] });
    render(<RobinMailboxCard />);
    expect(await screen.findByText(/APP_MANAGED_MAIL_DOMAIN, MAILGUN_WEBHOOK_SIGNING_KEY/)).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByText('Active')).toBeNull();
  });

  it('reserves an address but never presents it as working before receiving is proven', async () => {
    const pending: ManagedMailStatus = { readiness: 'PENDING_RECEIVING_VERIFICATION', domain: 'mail.robin.example',
      usableForRegistration: false };
    serve(pending, { ...pending, address: ADDRESS, addressStatus: 'ACTIVE' });
    render(<RobinMailboxCard />);
    fireEvent.click(await screen.findByRole('button', { name: 'Reserve my Robin address' }));
    expect(await screen.findByText(ADDRESS)).toBeTruthy();
    expect(screen.getByText('Not receiving yet')).toBeTruthy();
    expect(screen.getByText(/will not register any employer account with it/)).toBeTruthy();
    expect(screen.queryByText('Active')).toBeNull();
    expect(mocked).toHaveBeenCalledWith('/managed-mail/address', { method: 'POST' });
  });

  it('shows an active address with recent deliveries as metadata only', async () => {
    serve({ readiness: 'ACTIVE', domain: 'mail.robin.example', address: ADDRESS, addressStatus: 'ACTIVE',
      usableForRegistration: true, receivingVerifiedAt: '2026-10-11T10:00:00Z', lastInboundAt: '2026-10-11T10:05:00Z' },
    undefined, [{
      id: 'm-1', from: 'no-reply@careers.employer.example', senderDomain: 'careers.employer.example',
      subject: '[number] is your verification code', receivedAt: '2026-10-11T10:05:00Z',
      usedForVerification: true, contentHeld: false, senderCheckFailed: false, spamFlagged: false,
    }]);
    const { container } = render(<RobinMailboxCard />);
    expect(await screen.findByText('Active')).toBeTruthy();
    expect(screen.getByText(ADDRESS)).toBeTruthy();
    expect(await screen.findByText('[number] is your verification code')).toBeTruthy();
    expect(screen.getByText('Used for verification')).toBeTruthy();
    expect(screen.getByText(/Your Gmail connection\s+is not needed for this/)).toBeTruthy();
    expect(screen.queryByText('Not receiving yet')).toBeNull();
    expect(container.textContent).not.toMatch(/\b\d{6}\b/);
  });

  it('reports a load failure instead of guessing a state', async () => {
    mocked.mockRejectedValue(new Error('Service unavailable'));
    render(<RobinMailboxCard />);
    expect(await screen.findByText('Service unavailable')).toBeTruthy();
    expect(screen.queryByText('Active')).toBeNull();
  });
});
